package hashita.controller;

import hashita.data.Candle;
import hashita.data.PatternRecognitionResult;
import hashita.data.entities.StockData;
import hashita.repository.StockDataRepository;
import hashita.service.EntrySignalService;
import hashita.service.EntrySignalService.EntrySignal;
import hashita.service.EnhancedEntrySignalService;
import hashita.service.PatternAnalysisService;
import hashita.service.IBKRCandleService;  // ✅ NEW
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * DEBUG: Test simulation to see what's happening
 * ✅ UPDATED: Now uses IBKR candles with 5-day context
 */
@RestController
@RequestMapping("/api/simulate/debug")
@Slf4j
public class AlertSimulationDebugController {

    @Autowired
    private StockDataRepository stockDataRepository;

    @Autowired
    private PatternAnalysisService patternAnalysisService;

    @Autowired
    private EntrySignalService entrySignalService;

    @Autowired
    private EnhancedEntrySignalService enhancedEntrySignalService;

    @Autowired
    private IBKRCandleService ibkrCandleService;  // ✅ NEW

    /**
     * Debug: Check what symbols are found and what patterns they have
     */
    @GetMapping("/test-symbol")
    public ResponseEntity<?> testSymbol(
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(required = false) String testSymbol) {

        try {
            Map<String, Object> debug = new LinkedHashMap<>();

            // Get all symbols for date
            List<StockData> data = stockDataRepository.findByDate(date);
            List<String> allSymbols = data.stream()
                    .map(StockData::getStockInfo)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());

            debug.put("date", date);
            debug.put("symbolsFound", allSymbols.size());
            debug.put("symbols", allSymbols);

            // If specific symbol requested, analyze it
            if (testSymbol != null && !testSymbol.isEmpty()) {
                Map<String, Object> symbolAnalysis = new LinkedHashMap<>();

                log.info("🔍 Testing symbol: {}", testSymbol);

                try {
                    List<PatternRecognitionResult> patterns =
                            patternAnalysisService.analyzeStockForDate(testSymbol, date, interval);

                    symbolAnalysis.put("patternsFound", patterns.size());

                    if (!patterns.isEmpty()) {
                        symbolAnalysis.put("patternTypes", patterns.stream()
                                .map(p -> p.getPattern().name())
                                .collect(Collectors.toList()));

                        // ✅ NEW: Get candles with 5-day context
                        List<Candle> candles = ibkrCandleService.getCandlesWithContext(
                                testSymbol, date, interval);

                        symbolAnalysis.put("candlesAvailable", candles.size());
                        symbolAnalysis.put("candleSource", "IBKR (5-day context)");

                        // Try to generate signals
                        List<EntrySignal> signals = new ArrayList<>();
                        for (PatternRecognitionResult pattern : patterns) {
                            EntrySignal baseSignal = entrySignalService.evaluatePattern(pattern);
                            if (baseSignal != null) {
                                // Filter candles up to pattern time
                                List<Candle> candlesUpToPattern = candles.stream()
                                        .filter(c -> !c.getTimestamp().isAfter(pattern.getTimestamp()))
                                        .collect(Collectors.toList());

                                EntrySignal enhancedSignal = enhancedEntrySignalService.evaluateWithFilters(
                                        pattern, candlesUpToPattern, baseSignal);
                                if (enhancedSignal != null) {
                                    signals.add(enhancedSignal);
                                }
                            }
                        }

                        symbolAnalysis.put("signalsGenerated", signals.size());
                        symbolAnalysis.put("signalDetails", signals.stream()
                                .map(s -> Map.of(
                                        "pattern", s.getPattern().name(),
                                        "direction", s.getDirection(),
                                        "quality", s.getSignalQuality(),
                                        "entryPrice", s.getEntryPrice()
                                ))
                                .collect(Collectors.toList()));

                        // Filter for BULLISH only
                        long bullishCount = signals.stream()
                                .filter(s -> "LONG".equals(s.getDirection().name()))
                                .count();
                        symbolAnalysis.put("bullishSignals", bullishCount);

                    } else {
                        symbolAnalysis.put("error", "No patterns found");
                    }

                } catch (Exception e) {
                    symbolAnalysis.put("error", e.getMessage());
                    symbolAnalysis.put("stackTrace", Arrays.stream(e.getStackTrace())
                            .limit(5)
                            .map(StackTraceElement::toString)
                            .collect(Collectors.toList()));
                }

                debug.put("symbolAnalysis", symbolAnalysis);
            } else {
                debug.put("note", "Add ?testSymbol=DVLT to test a specific symbol");
            }

            return ResponseEntity.ok(debug);

        } catch (Exception e) {
            log.error("Debug error", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", e.getMessage(),
                            "stackTrace", Arrays.stream(e.getStackTrace())
                                    .limit(10)
                                    .map(StackTraceElement::toString)
                                    .collect(Collectors.toList())
                    ));
        }
    }

    /**
     * Debug: Test pattern analysis for a symbol
     */
    @GetMapping("/patterns")
    public ResponseEntity<?> testPatterns(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval) {

        try {
            log.info("🔍 Testing patterns for {} on {}", symbol, date);

            List<PatternRecognitionResult> patterns =
                    patternAnalysisService.analyzeStockForDate(symbol, date, interval);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("symbol", symbol);
            result.put("date", date);
            result.put("interval", interval);
            result.put("patternsFound", patterns.size());

            if (!patterns.isEmpty()) {
                result.put("patterns", patterns.stream()
                        .map(p -> Map.of(
                                "pattern", p.getPattern().name(),
                                "confidence", p.getConfidence(),
                                "timestamp", p.getTimestamp().toString(),
                                "price", p.getPriceAtDetection(),
                                "hasVolume", p.isHasVolumeConfirmation()
                        ))
                        .collect(Collectors.toList()));
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error testing patterns", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Debug: Test full pipeline for one symbol
     */
    @GetMapping("/full-test")
    public ResponseEntity<?> fullTest(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "0") int minQuality) {

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("symbol", symbol);
            result.put("date", date);

            // Step 1: Get patterns
            log.info("🔍 Step 1: Getting patterns for {}", symbol);
            List<PatternRecognitionResult> patterns =
                    patternAnalysisService.analyzeStockForDate(symbol, date, interval);
            result.put("step1_patterns", patterns.size());

            if (patterns.isEmpty()) {
                result.put("reason", "No patterns found");
                return ResponseEntity.ok(result);
            }

            // Step 2: ✅ NEW: Get candles with 5-day context
            log.info("🔍 Step 2: Getting candles with 5-day context");
            List<Candle> candles = ibkrCandleService.getCandlesWithContext(symbol, date, interval);
            result.put("step2_candles", candles.size());
            result.put("step2_candleSource", "IBKR (5-day context)");

            if (candles.size() < 50) {
                result.put("reason", "Not enough candles (need 50+, got " + candles.size() + ")");
                return ResponseEntity.ok(result);
            }

            // Step 3: Generate base signals
            log.info("🔍 Step 3: Generating base signals");
            List<EntrySignal> baseSignals = new ArrayList<>();
            for (PatternRecognitionResult pattern : patterns) {
                EntrySignal signal = entrySignalService.evaluatePattern(pattern);
                if (signal != null) {
                    baseSignals.add(signal);
                }
            }
            result.put("step3_baseSignals", baseSignals.size());

            if (baseSignals.isEmpty()) {
                result.put("reason", "No valid base signals");
                return ResponseEntity.ok(result);
            }

            // Step 4: Apply enhanced filters
            log.info("🔍 Step 4: Applying enhanced filters");
            List<EntrySignal> enhancedSignals = new ArrayList<>();
            for (int i = 0; i < patterns.size() && i < baseSignals.size(); i++) {
                // Filter candles up to pattern time
                int finalI = i;
                List<Candle> candlesUpToPattern = candles.stream()
                        .filter(c -> !c.getTimestamp().isAfter(patterns.get(finalI).getTimestamp()))
                        .collect(Collectors.toList());

                EntrySignal enhanced = enhancedEntrySignalService.evaluateWithFilters(
                        patterns.get(i), candlesUpToPattern, baseSignals.get(i));
                if (enhanced != null) {
                    enhancedSignals.add(enhanced);
                }
            }
            result.put("step4_enhancedSignals", enhancedSignals.size());

            // Step 5: Filter BULLISH only
            log.info("🔍 Step 5: Filtering for BULLISH");
            List<EntrySignal> bullishSignals = enhancedSignals.stream()
                    .filter(s -> "LONG".equals(s.getDirection().name()))
                    .collect(Collectors.toList());
            result.put("step5_bullishSignals", bullishSignals.size());

            // Step 6: Filter by quality
            log.info("🔍 Step 6: Filtering by quality >= {}", minQuality);
            List<EntrySignal> qualitySignals = bullishSignals.stream()
                    .filter(s -> s.getSignalQuality() >= minQuality)
                    .collect(Collectors.toList());
            result.put("step6_qualitySignals", qualitySignals.size());

            // Show signal details
            if (!qualitySignals.isEmpty()) {
                result.put("signals", qualitySignals.stream()
                        .map(s -> Map.of(
                                "pattern", s.getPattern().name(),
                                "quality", s.getSignalQuality(),
                                "direction", s.getDirection(),
                                "entryPrice", s.getEntryPrice(),
                                "urgency", s.getUrgency().toString(),
                                "reason", s.getReason()
                        ))
                        .collect(Collectors.toList()));
            }

            // Show all signals before filtering
            result.put("allSignalsBeforeFiltering", enhancedSignals.stream()
                    .map(s -> Map.of(
                            "pattern", s.getPattern().name(),
                            "quality", s.getSignalQuality(),
                            "direction", s.getDirection(),
                            "reason", s.getReason()
                    ))
                    .collect(Collectors.toList()));

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error in full test", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", e.getMessage(),
                            "stackTrace", Arrays.stream(e.getStackTrace())
                                    .limit(10)
                                    .map(StackTraceElement::toString)
                                    .collect(Collectors.toList())
                    ));
        }
    }
}