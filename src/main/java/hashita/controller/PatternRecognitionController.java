package hashita.controller;

import hashita.service.EntrySignalService.EntrySignal;
import hashita.data.PatternRecognitionResult;
import hashita.data.Candle;
import hashita.service.PatternAnalysisService;
import hashita.service.EntrySignalService;
import hashita.service.EnhancedEntrySignalService;
import hashita.service.CandleBuilderService;
import hashita.repository.StockDataRepository;
import hashita.repository.TickerVolumeRepository;
import hashita.data.entities.StockData;
import hashita.data.entities.TickerVolume;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@Slf4j
public class PatternRecognitionController {

    @Autowired
    private PatternAnalysisService patternAnalysisService;

    @Autowired
    private EntrySignalService entrySignalService;

    @Autowired
    private EnhancedEntrySignalService enhancedEntrySignalService;

    @Autowired
    private CandleBuilderService candleBuilderService;

    @Autowired
    private StockDataRepository stockDataRepository;

    @Autowired
    private TickerVolumeRepository tickerVolumeRepository;

    /**
     * Get entry signals with ENHANCED filters (trend + ADX) - BULLISH ONLY
     *
     * Filters:
     * - Only LONG/bullish signals
     * - Trend alignment
     * - ADX strength
     * - Minimum quality threshold
     *
     * @param symbol Stock symbol (e.g., "BITF")
     * @param date Date in yyyy-MM-dd format (e.g., "2025-10-06")
     * @param interval Candle interval in minutes (default: 5)
     * @param minQuality Minimum signal quality 0-100 (default: 75)
     * @return List of bullish entry signals
     *
     * Example request:
     * GET /api/signals/entry-enhanced?symbol=BITF&date=2025-10-06&interval=5&minQuality=75
     *
     * Example response:
     * {
     *   "symbol": "BITF",
     *   "date": "2025-10-06",
     *   "intervalMinutes": 5,
     *   "totalPatterns": 34,
     *   "count": 2,
     *   "signals": [
     *     {
     *       "symbol": "BITF",
     *       "pattern": "BULLISH_ENGULFING",
     *       "direction": "LONG",
     *       "entryPrice": 3.46,
     *       "stopLoss": 3.35,
     *       "target": 3.76,
     *       "signalQuality": 95.5
     *     }
     *   ]
     * }
     */
    @GetMapping("/signals/entry-enhanced")
    public ResponseEntity<?> getEnhancedEntrySignals(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality) {

        try {
            log.info("Getting enhanced entry signals: symbol={}, minQuality={}",
                    symbol, minQuality);

            // 1. Get patterns
            List<PatternRecognitionResult> patterns =
                    patternAnalysisService.analyzeStockForDate(symbol, date, interval);

            if (patterns.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "symbol", symbol,
                        "date", date,
                        "intervalMinutes", interval,
                        "count", 0,
                        "signals", Collections.emptyList()
                ));
            }

            // 2. Get ALL candles for trend calculation
            List<Candle> allCandles = getCandlesForDate(symbol, date, interval);

            if (allCandles.isEmpty()) {
                log.warn("No candles found for {}", symbol);
                return ResponseEntity.ok(Map.of(
                        "symbol", symbol,
                        "date", date,
                        "count", 0,
                        "signals", Collections.emptyList()
                ));
            }

            // 3. Convert patterns to signals with filters
            List<EntrySignal> signals = patterns.stream()
                    .map(pattern -> {
                        // Get base signal from EntrySignalService
                        EntrySignal baseSignal = entrySignalService.evaluatePattern(pattern);

                        if (baseSignal == null) {
                            return null;
                        }

                        // Apply enhanced filters
                        return enhancedEntrySignalService.evaluateWithFilters(
                                pattern, allCandles, baseSignal
                        );
                    })
                    .filter(s -> s != null)
                    .filter(s -> s.getSignalQuality() >= minQuality)
                    .filter(s -> "LONG".equals(s.getDirection().name()))
                    .sorted(Comparator.comparing(EntrySignal::getTimestamp)
                            .thenComparing(Comparator.comparingDouble(EntrySignal::getSignalQuality).reversed()))
                    .collect(Collectors.toList());

            log.info("Found {} high-quality signals (quality >= {})",
                    signals.size(), minQuality);

            return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "intervalMinutes", interval,
                    "totalPatterns", patterns.size(),
                    "count", signals.size(),
                    "signals", signals
            ));

        } catch (Exception e) {
            log.error("Error getting enhanced entry signals", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Original entry signals endpoint (no filters)
     */
    @GetMapping("/signals/entry")
    public ResponseEntity<?> getEntrySignals(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval) {

        try {
            List<EntrySignal> signals = entrySignalService.findEntrySignals(symbol, date, interval);

            return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "intervalMinutes", interval,
                    "count", signals.size(),
                    "signals", signals
            ));

        } catch (Exception e) {
            log.error("Error getting entry signals", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get patterns only (no signal evaluation)
     */
    @GetMapping("/patterns/analyze")
    public ResponseEntity<?> analyzePatterns(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval) {

        try {
            List<PatternRecognitionResult> patterns =
                    patternAnalysisService.analyzeStockForDate(symbol, date, interval);

            return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "intervalMinutes", interval,
                    "count", patterns.size(),
                    "patterns", patterns
            ));

        } catch (Exception e) {
            log.error("Error analyzing patterns", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Helper method to get all candles for a date
     * This replicates the logic from PatternAnalysisService
     */
    private List<Candle> getCandlesForDate(String symbol, String date, int intervalMinutes) {
        try {
            // Get stock data
            StockData stockData = stockDataRepository
                    .findByStockInfoAndDate(symbol, date)
                    .orElse(null);

            if (stockData == null) {
                return Collections.emptyList();
            }

            // Get volume data (optional)
            List<TickerVolume.IntervalVolume> filteredVolumes;
            TickerVolume volumeData = tickerVolumeRepository
                    .findByStockInfoAndDate(symbol, date)
                    .orElse(null);

            if (volumeData == null) {
                filteredVolumes = Collections.emptyList();
            } else {
                filteredVolumes = volumeData.getIntervalVolumes().stream()
                        .filter(iv -> iv.getIntervalMinutes() == intervalMinutes)
                        .collect(Collectors.toList());
            }

            // Build candles
            return candleBuilderService.buildCandles(
                    stockData.getStocksPrices(),
                    filteredVolumes,
                    intervalMinutes
            );

        } catch (Exception e) {
            log.error("Error getting candles", e);
            return Collections.emptyList();
        }
    }
}