package hashita.controller;

import hashita.data.Candle;
import hashita.data.CandlePattern;
import hashita.data.PatternRecognitionResult;
import hashita.service.*;
import hashita.service.EntrySignalService.EntrySignal;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ✅ FIXED: Pattern Recognition Controller using CACHE-ONLY mode
 *
 * This controller NEVER calls IBKR - only uses cached data
 * For pattern analysis on historical data
 *
 * @version 4.0 - Cache-only with input validation
 */
@RestController
@RequestMapping("/api/patterns")
@RequiredArgsConstructor
@Slf4j
public class PatternRecognitionController {

    private final PatternAnalysisService patternAnalysisService;
    private final EntrySignalService entrySignalService;
    private final EnhancedEntrySignalService enhancedEntrySignalService;
    private final StrongPatternFilter strongPatternFilter;
    private final IBKRCandleService ibkrCandleService;
    private final PatternConfluenceService confluenceService;

    public enum PatternTypeFilter {
        ALL, CHART_ONLY, CANDLESTICK_ONLY, STRONG_ONLY
    }

    @GetMapping("/signals")
    public ResponseEntity<?> getQualitySignals(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{1,5}$") String symbol,
            @RequestParam @NotBlank @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$") String date,
            @RequestParam(defaultValue = "5") @Min(1) @Max(60) int interval,
            @RequestParam(defaultValue = "70") @Min(0) @Max(100) int minQuality,
            @RequestParam(defaultValue = "ALL") PatternTypeFilter patternType,
            @RequestParam(defaultValue = "false") boolean strictMarketHours) {  // ✅ ADD THIS PARAMETER

        try {
            log.info("🔎 Getting {} signals: {} on {} (min quality: {})",
                    patternType, symbol, date, minQuality);

            List<PatternRecognitionResult> patterns =
                    patternAnalysisService.analyzeStockForDate(symbol, date, interval);

            if (patterns.isEmpty()) {
                return ResponseEntity.ok(buildEmptyResponse(symbol, date, interval, patternType));
            }

            List<Candle> allCandles = ibkrCandleService.getCandlesWithContext(
                    symbol, date, interval, false);

            if (allCandles.isEmpty()) {
                log.warn("No cached candles for {} - run /api/candles/fetch-date first", symbol);
                return ResponseEntity.ok(buildEmptyResponse(symbol, date, interval, patternType));
            }

            List<EntrySignal> signals = patterns.stream()
                    .map(pattern -> evaluatePattern(pattern, allCandles))
                    .filter(Objects::nonNull)
                    // ✅ CHANGE THIS LINE - Make market hours optional:
                    .filter(s -> !strictMarketHours || isMarketHours(s))  // Only filter if strictMarketHours=true
                    .filter(s -> matchesPatternType(s.getPattern(), patternType))
                    .filter(s -> strongPatternFilter.isStrongPattern(
                            s.getPattern(), s.getSignalQuality(), s.isHasVolumeConfirmation()))
                    .filter(s -> s.getSignalQuality() >= minQuality)
                    .filter(s -> "LONG".equals(s.getDirection().name()))
                    .sorted(Comparator.comparing(EntrySignal::getSignalQuality).reversed())
                    .collect(Collectors.toList());

            signals = confluenceService.detectConfluence(signals);

            log.info("✅ Found {} {} signals (from {} total patterns)",
                    signals.size(), patternType, patterns.size());

            return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "intervalMinutes", interval,
                    "minQuality", minQuality,
                    "patternType", patternType.toString(),
                    "strictMarketHours", strictMarketHours,  // ✅ ADD THIS to response
                    "totalPatternsDetected", patterns.size(),
                    "qualitySignals", signals.size(),
                    "signals", signals
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_INPUT", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Error getting quality signals: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "INTERNAL_ERROR", "message", "Failed to get signals"));
        }
    }

    @GetMapping("/all")
    public ResponseEntity<?> getAllPatterns(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{1,5}$") String symbol,
            @RequestParam @NotBlank @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$") String date,
            @RequestParam(defaultValue = "5") @Min(1) @Max(60) int interval) {

        try {
            List<PatternRecognitionResult> patterns =
                    patternAnalysisService.analyzeStockForDate(symbol, date, interval);

            Map<String, List<PatternRecognitionResult>> byType = patterns.stream()
                    .collect(Collectors.groupingBy(p ->
                            p.getPattern().isChartPattern() ? "CHART" : "CANDLESTICK"));

            return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "intervalMinutes", interval,
                    "count", patterns.size(),
                    "chartPatterns", byType.getOrDefault("CHART", List.of()).size(),
                    "candlestickPatterns", byType.getOrDefault("CANDLESTICK", List.of()).size(),
                    "patterns", patterns,
                    "byType", byType
            ));

        } catch (Exception e) {
            log.error("Error getting all patterns: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "INTERNAL_ERROR", "message", e.getMessage()));
        }
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scanMultipleSymbols(@RequestBody ScanRequest request) {
        try {
            // ✅ FIX: Validate input
            if (request.symbols == null || request.symbols.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "INVALID_INPUT", "message", "symbols list is required"));
            }

            if (request.symbols.size() > 50) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "TOO_MANY_SYMBOLS", "message", "Maximum 50 symbols allowed"));
            }

            List<String> symbols = request.symbols;
            String date = request.date;
            Integer interval = request.interval != null ? request.interval : 5;
            Integer minQuality = request.minQuality != null ? request.minQuality : 70;
            PatternTypeFilter patternType = request.patternType != null ?
                    request.patternType : PatternTypeFilter.ALL;
            // ✅ ADD THIS: Get strictMarketHours from request (default to false for scans)
            boolean strictMarketHours = request.strictMarketHours != null ?
                    request.strictMarketHours : false;

            log.info("🔍 Scanning {} symbols for {} patterns", symbols.size(), patternType);

            Map<String, List<EntrySignal>> results = new LinkedHashMap<>();

            for (String symbol : symbols) {
                try {
                    ResponseEntity<?> response = getQualitySignals(
                            symbol, date, interval, minQuality, patternType, strictMarketHours);

                    if (response.getBody() != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> responseBody = (Map<String, Object>) response.getBody();

                        @SuppressWarnings("unchecked")
                        List<EntrySignal> signals = (List<EntrySignal>) responseBody.get("signals");

                        if (signals != null && !signals.isEmpty()) {
                            results.put(symbol, signals);
                        }
                    }

                } catch (Exception e) {
                    log.error("Error scanning {}: {}", symbol, e.getMessage());
                }
            }

            List<EntrySignal> bestSignals = results.values().stream()
                    .flatMap(List::stream)
                    .sorted(Comparator.comparing(EntrySignal::getSignalQuality).reversed())
                    .limit(10)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "scannedSymbols", symbols.size(),
                    "symbolsWithSignals", results.size(),
                    "date", date,
                    "interval", interval,
                    "minQuality", minQuality,
                    "patternType", patternType.toString(),
                    "strictMarketHours", strictMarketHours,  // ✅ ADD THIS
                    "resultsBySymbol", results,
                    "topOpportunities", bestSignals
            ));

        } catch (Exception e) {
            log.error("Error scanning symbols: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "SCAN_FAILED", "message", e.getMessage()));
        }
    }

    public static class ScanRequest {
        @NotNull
        @Size(min = 1, max = 50)
        public List<@Pattern(regexp = "^[A-Z]{1,5}$") String> symbols;

        @NotBlank
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$")
        public String date;

        @Min(1) @Max(60)
        public Integer interval;

        @Min(0) @Max(100)
        public Integer minQuality;

        public PatternTypeFilter patternType;

        // ✅ ADD THIS FIELD:
        public Boolean strictMarketHours;  // Optional, defaults to false in the method
    }

    private EntrySignal evaluatePattern(PatternRecognitionResult pattern, List<Candle> allCandles) {
        try {
            EntrySignal baseSignal = entrySignalService.evaluatePattern(pattern);
            if (baseSignal == null) return null;

            List<Candle> candlesUpToPattern = allCandles.stream()
                    .filter(c -> !c.getTimestamp().isAfter(pattern.getTimestamp()))
                    .collect(Collectors.toList());

            if (candlesUpToPattern.isEmpty()) return null;

            return enhancedEntrySignalService.evaluateWithFilters(
                    pattern, candlesUpToPattern, baseSignal);

        } catch (Exception e) {
            log.error("Error evaluating pattern: {}", e.getMessage());
            return null;
        }
    }

    private boolean isMarketHours(EntrySignal signal) {
        ZonedDateTime nyTime = signal.getTimestamp()
                .atZone(ZoneId.of("America/New_York"));
        int hour = nyTime.getHour();
        int minute = nyTime.getMinute();
        return !(hour < 9 || (hour == 9 && minute < 30) || hour >= 16);
    }

    private boolean matchesPatternType(CandlePattern pattern, PatternTypeFilter filter) {
        return switch (filter) {
            case ALL -> true;
            case CHART_ONLY -> pattern.isChartPattern();
            case CANDLESTICK_ONLY -> !pattern.isChartPattern();
            case STRONG_ONLY -> Set.of(
                    CandlePattern.FALLING_WEDGE, CandlePattern.RISING_WEDGE,
                    CandlePattern.BULL_FLAG, CandlePattern.BEAR_FLAG,
                    CandlePattern.BULLISH_ENGULFING, CandlePattern.BEARISH_ENGULFING,
                    CandlePattern.MORNING_STAR, CandlePattern.EVENING_STAR
            ).contains(pattern);
        };
    }

    private Map<String, Object> buildEmptyResponse(String symbol, String date, int interval,
                                                   PatternTypeFilter patternType) {
        return Map.of(
                "symbol", symbol,
                "date", date,
                "intervalMinutes", interval,
                "patternType", patternType.toString(),
                "count", 0,
                "message", "No patterns found or no cached data available",
                "signals", Collections.emptyList()
        );
    }
}