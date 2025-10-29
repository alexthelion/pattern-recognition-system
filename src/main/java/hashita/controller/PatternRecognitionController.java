package hashita.controller;

import hashita.service.EntrySignalService.EntrySignal;
import hashita.service.IBKRCandleService;
import hashita.data.PatternRecognitionResult;
import hashita.data.Candle;
import hashita.service.PatternAnalysisService;
import hashita.service.EntrySignalService;
import hashita.service.EnhancedEntrySignalService;
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
    private IBKRCandleService ibkrCandleService;  // ✅ NEW: Use IBKR candles

    /**
     * Get entry signals with ENHANCED filters (trend + ADX) - BULLISH ONLY
     *
     * ✅ UPDATED: Now uses IBKR candles with 5-day context
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

            // 1. Get patterns (PatternAnalysisService uses IBKR candles internally)
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

            // 2. ✅ NEW: Get candles with 5-day context from IBKR cache
            List<Candle> allCandles = ibkrCandleService.getCandlesWithContext(symbol, date, interval);

            if (allCandles.isEmpty()) {
                log.warn("No candles found for {}", symbol);
                return ResponseEntity.ok(Map.of(
                        "symbol", symbol,
                        "date", date,
                        "count", 0,
                        "signals", Collections.emptyList()
                ));
            }

            // 3. Convert patterns to signals with filters (prevent look-ahead bias)
            List<EntrySignal> signals = patterns.stream()
                    .map(pattern -> {
                        // Get base signal from EntrySignalService
                        EntrySignal baseSignal = entrySignalService.evaluatePattern(pattern);

                        if (baseSignal == null) {
                            return null;
                        }

                        // CRITICAL: Filter candles to prevent look-ahead bias
                        // Only use candles UP TO (and including) the pattern's timestamp
                        List<Candle> candlesUpToPattern = allCandles.stream()
                                .filter(c -> !c.getTimestamp().isAfter(pattern.getTimestamp()))
                                .collect(Collectors.toList());

                        if (candlesUpToPattern.isEmpty()) {
                            log.warn("No historical candles for pattern at {}", pattern.getTimestamp());
                            return null;
                        }

                        // Apply enhanced filters with ONLY past data
                        return enhancedEntrySignalService.evaluateWithFilters(
                                pattern, candlesUpToPattern, baseSignal
                        );
                    })
                    .filter(s -> s != null)
                    .filter(s -> s.getSignalQuality() >= minQuality)
                    .filter(s -> "LONG".equals(s.getDirection().name()))
                    .sorted(Comparator.comparing(EntrySignal::getTimestamp))  // ✅ Sort by timestamp!
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
}