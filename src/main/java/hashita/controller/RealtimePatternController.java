package hashita.controller;

import hashita.data.Candle;
import hashita.data.CandlePattern;
import hashita.data.PatternRecognitionResult;
import hashita.service.*;
import hashita.service.EntrySignalService.EntrySignal;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ✅ FIXED: Real-time pattern detection with IBKR access + Pattern Confluence
 *
 * This controller is ALLOWED to call IBKR for live data
 * Uses getCandlesWithContext(allowFetch=true)
 *
 * @version 3.1 - Added pattern confluence detection
 */
@RestController
@RequestMapping("/api/realtime")
@Slf4j
@RequiredArgsConstructor
public class RealtimePatternController {

    private final IBKRCandleService ibkrCandleService;
    private final PatternRecognitionService patternRecognitionService;
    private final EntrySignalService entrySignalService;
    private final EnhancedEntrySignalService enhancedEntrySignalService;
    private final StrongPatternFilter strongPatternFilter;
    private final PatternConfluenceService confluenceService;
    private final MarketDataService marketDataService;

    /**
     * Pattern type filter
     */
    public enum PatternTypeFilter {
        ALL,
        CHART_ONLY,
        CANDLESTICK_ONLY,
        STRONG_ONLY
    }

    /**
     * Get current price with real-time data or fallback to candle
     */
    private Map<String, Object> getPriceInfo(String symbol, Candle latestCandle, Instant targetTime) {
        Map<String, Object> info = new HashMap<>();

        // Try to get real-time price from IBKR
        Double realtimePrice = marketDataService.getRealTimePrice(symbol);
        Double candlePrice = latestCandle.getClose();

        // Calculate how old the candle is
        long candleAgeMinutes = java.time.Duration
                .between(latestCandle.getTimestamp(), targetTime)
                .toMinutes();

        if (realtimePrice != null) {
            // We have real-time data!
            info.put("currentPrice", realtimePrice);
            info.put("priceIsRealTime", true);
            info.put("priceAgeMinutes", 0L);

            log.debug("Using real-time price for {}: ${} (candle was: ${})",
                    symbol, realtimePrice, candlePrice);
        } else {
            // Fallback to candle price
            info.put("currentPrice", candlePrice);
            info.put("priceIsRealTime", false);
            info.put("priceAgeMinutes", candleAgeMinutes);

            if (candleAgeMinutes > 5) {
                info.put("priceWarning", "Price is " + candleAgeMinutes + " minutes old. Real-time price unavailable.");
            }

            log.debug("Using candle price for {}: ${} (age: {} min)",
                    symbol, candlePrice, candleAgeMinutes);
        }

        // Always include candle reference data
        info.put("latestCandlePrice", candlePrice);
        info.put("latestCandleTime", latestCandle.getTimestamp().toString());
        info.put("candleAgeMinutes", candleAgeMinutes);

        return info;
    }

    /**
     * ✅ UPDATED: Real-time patterns with confluence detection
     *
     * This endpoint is ALLOWED to call IBKR for live data
     *
     * Examples:
     * GET /api/realtime/patterns?symbol=TSLA&patternType=CHART_ONLY
     * GET /api/realtime/patterns?symbol=AAPL&patternType=STRONG_ONLY&minQuality=80
     */
    @GetMapping("/patterns")
    public ResponseEntity<?> getRealtimePatterns(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{1,5}$",
                    message = "Symbol must be 1-5 uppercase letters") String symbol,
            @RequestParam(defaultValue = "60") @Min(0) @Max(100) int minQuality,
            @RequestParam(required = false) String datetime,
            @RequestParam(defaultValue = "5") @Min(1) @Max(60) int interval,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int maxResults,
            @RequestParam(defaultValue = "ALL") String direction,
            @RequestParam(defaultValue = "ALL") PatternTypeFilter patternType,
            @RequestParam(defaultValue = "true") boolean applyFilters) {

        try {
            long startTime = System.currentTimeMillis();

            Instant targetTime;
            String targetDate;

            if (datetime != null && !datetime.isEmpty()) {
                targetTime = Instant.parse(datetime);
                targetDate = targetTime.atZone(ZoneId.of("UTC"))
                        .toLocalDate()
                        .format(DateTimeFormatter.ISO_LOCAL_DATE);
            } else {
                targetTime = Instant.now();
                targetDate = LocalDate.now(ZoneId.of("UTC"))
                        .format(DateTimeFormatter.ISO_LOCAL_DATE);
            }

            log.info("🔴 REALTIME: {} patterns for {} (quality: {}, filters: {})",
                    patternType, symbol, minQuality, applyFilters);

            // ✅ FIXED: Use getCandlesWithContext with allowFetch=TRUE
            // This endpoint is ALLOWED to call IBKR for live data
            List<Candle> allCandles = ibkrCandleService.getCandlesWithContext(
                    symbol, targetDate, interval, true); // ← allowFetch=true!

            if (allCandles.isEmpty()) {
                return ResponseEntity.ok(buildEmptyResponse(symbol, targetTime, patternType));
            }

            List<Candle> candlesUpToNow = allCandles.stream()
                    .filter(c -> !c.getTimestamp().isAfter(targetTime))
                    .collect(Collectors.toList());

            if (candlesUpToNow.size() < 10) {
                return ResponseEntity.ok(buildInsufficientDataResponse(symbol, targetTime, patternType));
            }

            List<PatternRecognitionResult> allPatterns =
                    patternRecognitionService.scanForPatterns(candlesUpToNow, symbol);

            log.info("Found {} total patterns for {}", allPatterns.size(), symbol);

            // ✅ STEP 1: Create EntrySignal objects from patterns
            List<EntrySignal> entrySignals = allPatterns.stream()
                    .map(pattern -> {
                        EntrySignal base = entrySignalService.evaluatePattern(pattern);
                        if (base == null) return null;

                        List<Candle> upToPattern = candlesUpToNow.stream()
                                .filter(c -> !c.getTimestamp().isAfter(pattern.getTimestamp()))
                                .collect(Collectors.toList());

                        if (upToPattern.isEmpty()) return null;

                        return enhancedEntrySignalService.evaluateWithFilters(
                                pattern, upToPattern, base);
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // ✅ STEP 2: Apply confluence detection (NEW!)
            entrySignals = confluenceService.detectConfluence(entrySignals);

            // ✅ STEP 3: Convert to maps and apply filters
            List<Map<String, Object>> signals = entrySignals.stream()
                    .map(signal -> createSignalMapFromSignal(signal, targetTime, interval))
                    .filter(Objects::nonNull)
                    .filter(s -> matchesPatternType((CandlePattern) s.get("pattern"), patternType))
                    .filter(s -> applyFilters ? strongPatternFilter.isStrongPattern(
                            (CandlePattern) s.get("pattern"),
                            (double) s.get("signalQuality"),
                            (boolean) s.get("hasVolumeConfirmation")) : true)
                    .filter(s -> (double) s.get("signalQuality") >= minQuality)
                    .filter(s -> "ALL".equals(direction) || direction.equals(s.get("direction")))
                    .sorted((a, b) -> ((String) b.get("timestamp")).compareTo((String) a.get("timestamp")))
                    .limit(maxResults)
                    .collect(Collectors.toList());

            long duration = System.currentTimeMillis() - startTime;
            Candle latestCandle = candlesUpToNow.get(candlesUpToNow.size() - 1);

            // ✅ NEW: Get real-time price info
            Map<String, Object> priceInfo = getPriceInfo(symbol, latestCandle, targetTime);

            // Calculate price change from first pattern if we have real-time price
            if (Boolean.TRUE.equals(priceInfo.get("priceIsRealTime")) && !signals.isEmpty()) {
                Map<String, Object> firstSignal = signals.get(0);
                if (firstSignal.containsKey("entryPrice")) {
                    double entryPrice = ((Number) firstSignal.get("entryPrice")).doubleValue();
                    double currentPrice = ((Number) priceInfo.get("currentPrice")).doubleValue();

                    double changeAmount = currentPrice - entryPrice;
                    double changePercent = (changeAmount / entryPrice) * 100;

                    priceInfo.put("priceChangeFromPattern", changeAmount);
                    priceInfo.put("priceChangePercent", changePercent);
                }
            }

            log.info("✅ REALTIME: {} {} patterns in {}ms (price: ${}, realtime: {})",
                    signals.size(), patternType, duration,
                    priceInfo.get("currentPrice"),
                    priceInfo.get("priceIsRealTime"));

            Map<String, Object> response = new HashMap<>();
            response.put("symbol", symbol);
            response.put("requestedDatetime", targetTime.toString());

            // ✅ Add all price info
            response.putAll(priceInfo);

            // Backward compatibility
            response.put("latestPrice", priceInfo.get("currentPrice"));

            response.put("minQuality", minQuality);
            response.put("direction", direction);
            response.put("patternType", patternType.toString());
            response.put("filtersApplied", applyFilters);
            response.put("totalPatterns", signals.size());
            response.put("processingTimeMs", duration);
            response.put("patterns", signals);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.error("Invalid input: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error", "INVALID_INPUT",
                            "message", e.getMessage(),
                            "symbol", symbol
                    ));
        } catch (Exception e) {
            log.error("Error getting realtime patterns for {}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", "INTERNAL_ERROR",
                            "message", "Failed to get patterns",
                            "symbol", symbol
                    ));
        }
    }

    /**
     * Get latest pattern for a symbol (most recent)
     */
    @GetMapping("/latest")
    public ResponseEntity<?> getLatestPattern(
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{1,5}$") String symbol,
            @RequestParam(defaultValue = "70") @Min(0) @Max(100) int minQuality,
            @RequestParam(defaultValue = "5") @Min(1) @Max(60) int interval,
            @RequestParam(defaultValue = "LONG") String direction,
            @RequestParam(defaultValue = "ALL") PatternTypeFilter patternType) {

        try {
            ResponseEntity<?> response = getRealtimePatterns(
                    symbol, minQuality, null, interval, 1, direction, patternType, true);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();

            if (body == null) {
                return ResponseEntity.ok(Map.of(
                        "symbol", symbol,
                        "message", "No patterns found"
                ));
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> patterns =
                    (List<Map<String, Object>>) body.get("patterns");

            if (patterns == null || patterns.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "symbol", symbol,
                        "message", "No patterns found",
                        "latestPrice", body.getOrDefault("latestPrice", 0)
                ));
            }

            Map<String, Object> latest = patterns.get(0);
            long age = (Long) latest.get("ageMinutes");
            boolean fresh = age <= interval * 2;

            // Calculate unrealized P&L if we have price data
            Object entryPriceObj = latest.get("entryPrice");
            Object currentPriceObj = body.get("currentPrice");
            Boolean priceIsRealTime = (Boolean) body.get("priceIsRealTime");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("symbol", symbol);

            // Add current price info
            if (currentPriceObj != null) {
                double currentPrice = ((Number) currentPriceObj).doubleValue();
                result.put("currentPrice", currentPrice);
                result.put("priceIsRealTime", priceIsRealTime != null ? priceIsRealTime : false);

                // Add price age/warning
                if (body.containsKey("priceAgeMinutes")) {
                    result.put("priceAgeMinutes", body.get("priceAgeMinutes"));
                }
                if (body.containsKey("priceWarning")) {
                    result.put("priceWarning", body.get("priceWarning"));
                }

                // Calculate unrealized P&L
                if (entryPriceObj != null) {
                    double entryPrice = ((Number) entryPriceObj).doubleValue();
                    double pnl = currentPrice - entryPrice;
                    double pnlPercent = (pnl / entryPrice) * 100;

                    result.put("unrealizedPnL", pnl);
                    result.put("unrealizedPnLPercent", pnlPercent);
                }
            }

            // Backward compatibility
            result.put("latestPrice", currentPriceObj);

            // Pattern and metadata
            result.put("latestPattern", latest);
            result.put("isFresh", fresh);
            result.put("ageMinutes", age);
            result.put("recommendation", fresh ? "CONSIDER_ENTRY" : "PATTERN_TOO_OLD");

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error getting latest pattern: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "INTERNAL_ERROR", "message", e.getMessage()));
        }
    }

    /**
     * ✅ NEW: Scan multiple symbols for patterns
     *
     * POST /api/realtime/scan
     * {
     *   "symbols": ["AAPL", "TSLA", "NVDA"],
     *   "minQuality": 70,
     *   "direction": "LONG",
     *   "patternType": "STRONG_ONLY"
     * }
     */
    @PostMapping("/scan")
    public ResponseEntity<?> scanMultipleSymbols(@RequestBody ScanRequest request) {
        try {
            if (request.symbols == null || request.symbols.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "INVALID_INPUT", "message", "symbols list is required"));
            }

            if (request.symbols.size() > 50) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "TOO_MANY_SYMBOLS", "message", "Maximum 50 symbols allowed"));
            }

            List<String> symbols = request.symbols;
            int minQuality = request.minQuality != null ? request.minQuality : 70;
            String direction = request.direction != null ? request.direction : "LONG";
            int interval = request.interval != null ? request.interval : 5;
            PatternTypeFilter patternType = request.patternType != null ?
                    request.patternType : PatternTypeFilter.ALL;

            log.info("🔍 SCAN: {} symbols, {} patterns", symbols.size(), patternType);

            long start = System.currentTimeMillis();

            // ✅ FIX: Process with controlled parallelism (not unbounded)
            // Use sequential processing for realtime to avoid overwhelming IBKR
            List<Map<String, Object>> results = new ArrayList<>();

            for (String symbol : symbols) {
                Map<String, Object> result = scanSymbol(symbol, minQuality, interval, direction, patternType);
                if (result != null) {
                    results.add(result);
                }
            }

            long duration = System.currentTimeMillis() - start;

            return ResponseEntity.ok(Map.of(
                    "symbolsScanned", symbols.size(),
                    "symbolsWithPatterns", results.size(),
                    "patternType", patternType.toString(),
                    "processingTimeMs", duration,
                    "results", results
            ));

        } catch (Exception e) {
            log.error("Error scanning symbols: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "SCAN_FAILED", "message", e.getMessage()));
        }
    }

    /**
     * ✅ NEW: Request DTO with validation
     */
    public static class ScanRequest {
        @NotNull
        @Size(min = 1, max = 50, message = "Must provide 1-50 symbols")
        public List<@Pattern(regexp = "^[A-Z]{1,5}$", message = "Invalid symbol format") String> symbols;

        @Min(0) @Max(100)
        public Integer minQuality;

        public String direction;

        @Min(1) @Max(60)
        public Integer interval;

        public PatternTypeFilter patternType;
    }

    // ==================== HELPER METHODS ====================

    /**
     * DEPRECATED: Use createSignalMapFromSignal instead (kept for compatibility)
     */
    private Map<String, Object> createSignalMap(
            PatternRecognitionResult pattern,
            List<Candle> candles,
            Instant targetTime,
            int interval) {
        try {
            EntrySignal base = entrySignalService.evaluatePattern(pattern);
            if (base == null) return null;

            List<Candle> upToPattern = candles.stream()
                    .filter(c -> !c.getTimestamp().isAfter(pattern.getTimestamp()))
                    .collect(Collectors.toList());

            if (upToPattern.isEmpty()) return null;

            EntrySignal signal = enhancedEntrySignalService.evaluateWithFilters(
                    pattern, upToPattern, base);

            if (signal == null) return null;

            return createSignalMapFromSignal(signal, targetTime, interval);
        } catch (Exception e) {
            log.error("Error creating signal map: {}", e.getMessage());
            return null;
        }
    }

    /**
     * ✅ NEW: Create signal map from EntrySignal object (for confluence support)
     * This is used after confluence detection to convert EntrySignal to Map
     */
    private Map<String, Object> createSignalMapFromSignal(
            EntrySignal signal,
            Instant targetTime,
            int interval) {
        try {
            if (signal == null) return null;

            long age = java.time.Duration.between(signal.getTimestamp(), targetTime).toMinutes();

            Map<String, Object> map = new HashMap<>();
            map.put("symbol", signal.getSymbol());
            map.put("pattern", signal.getPattern());
            map.put("isChartPattern", signal.getPattern().isChartPattern());
            map.put("timestamp", signal.getTimestamp().toString());
            map.put("direction", signal.getDirection().name());
            map.put("entryPrice", signal.getEntryPrice());
            map.put("stopLoss", signal.getStopLoss());
            map.put("target", signal.getTarget());
            map.put("signalQuality", signal.getSignalQuality());
            map.put("confidence", signal.getConfidence());
            map.put("urgency", signal.getUrgency());
            map.put("reason", signal.getReason());
            map.put("riskRewardRatio", signal.getRiskRewardRatio());
            map.put("riskPercent", signal.getRiskPercent());
            map.put("rewardPercent", signal.getRewardPercent());
            map.put("ageMinutes", age);
            map.put("isFresh", age <= interval * 2);
            map.put("hasVolumeConfirmation", signal.isHasVolumeConfirmation());

            // ✅ ADD: Israel timezone timestamp
            String israelTime = signal.getTimestamp()
                    .atZone(java.time.ZoneId.of("Asia/Jerusalem"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            map.put("timestampIsrael", israelTime);

            if (signal.getVolume() != null) {
                map.put("volume", signal.getVolume());
                map.put("avgVolume", signal.getAverageVolume());
                map.put("volumeRatio", signal.getVolumeRatio());
            }

            // ✅ ADD: Confluence metadata if present
            if (signal.getIsConfluence() != null && signal.getIsConfluence()) {
                map.put("isConfluence", true);
                map.put("confluenceCount", signal.getConfluenceCount());
                map.put("confluentPatterns", signal.getConfluentPatterns());
            }

            return map;
        } catch (Exception e) {
            log.error("Error creating signal map from signal: {}", e.getMessage());
            return null;
        }
    }

    private boolean matchesPatternType(CandlePattern pattern, PatternTypeFilter filter) {
        return switch (filter) {
            case ALL -> true;
            case CHART_ONLY -> pattern.isChartPattern();
            case CANDLESTICK_ONLY -> !pattern.isChartPattern();
            case STRONG_ONLY -> Set.of(
                    CandlePattern.FALLING_WEDGE,
                    CandlePattern.RISING_WEDGE,
                    CandlePattern.BULL_FLAG,
                    CandlePattern.BEAR_FLAG,
                    CandlePattern.BULLISH_ENGULFING,
                    CandlePattern.BEARISH_ENGULFING,
                    CandlePattern.MORNING_STAR,
                    CandlePattern.EVENING_STAR
            ).contains(pattern);
        };
    }

    private Map<String, Object> scanSymbol(
            String symbol, int minQuality, int interval, String direction, PatternTypeFilter patternType) {
        try {
            ResponseEntity<?> response = getRealtimePatterns(
                    symbol, minQuality, null, interval, 5, direction, patternType, true);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();

            if (body == null) return null;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> patterns =
                    (List<Map<String, Object>>) body.get("patterns");

            if (patterns == null || patterns.isEmpty()) return null;

            return Map.of(
                    "symbol", symbol,
                    "latestPrice", body.get("latestPrice"),
                    "patternsFound", patterns.size(),
                    "bestPattern", patterns.get(0)
            );
        } catch (Exception e) {
            log.error("Error scanning {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildEmptyResponse(String symbol, Instant time, PatternTypeFilter type) {
        return Map.of(
                "symbol", symbol,
                "datetime", time.toString(),
                "patternType", type.toString(),
                "message", "No candles available",
                "patterns", Collections.emptyList()
        );
    }

    private Map<String, Object> buildInsufficientDataResponse(String symbol, Instant time, PatternTypeFilter type) {
        return Map.of(
                "symbol", symbol,
                "datetime", time.toString(),
                "patternType", type.toString(),
                "message", "Insufficient data (need 10+ candles)",
                "patterns", Collections.emptyList()
        );
    }

    /**
     * ✅ NEW: Clear real-time price cache
     *
     * GET /api/realtime/clear-cache
     * GET /api/realtime/clear-cache?symbol=AAPL
     */
    @GetMapping("/clear-cache")
    public ResponseEntity<?> clearPriceCache(
            @RequestParam(required = false) String symbol) {

        if (symbol != null) {
            marketDataService.clearCache(symbol);
            return ResponseEntity.ok(Map.of(
                    "message", "Cleared price cache for " + symbol,
                    "symbol", symbol
            ));
        } else {
            marketDataService.clearCache();
            return ResponseEntity.ok(Map.of(
                    "message", "Cleared all price caches"
            ));
        }
    }
}