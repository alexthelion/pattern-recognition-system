package hashita.controller;

import hashita.data.Candle;
import hashita.data.PatternRecognitionResult;
import hashita.service.EntrySignalService;
import hashita.service.EntrySignalService.EntrySignal;
import hashita.service.EnhancedEntrySignalService;
import hashita.service.IBKRCandleService;
import hashita.service.PatternRecognitionService;
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
 * Real-time pattern detection for live trading decisions
 * Get current patterns for a symbol to make immediate trading decisions
 */
@RestController
@RequestMapping("/api/realtime")
@Slf4j
public class RealtimePatternController {

    @Autowired
    private IBKRCandleService ibkrCandleService;

    @Autowired
    private PatternRecognitionService patternRecognitionService;

    @Autowired
    private EntrySignalService entrySignalService;

    @Autowired
    private EnhancedEntrySignalService enhancedEntrySignalService;

    /**
     * Get real-time patterns for a symbol
     *
     * Endpoint: GET /api/realtime/patterns
     *
     * Use cases:
     * - Live trading decisions
     * - Real-time alerts
     * - Current market analysis
     *
     * @param symbol Stock symbol (e.g., "AAPL", "TSLA")
     * @param minQuality Minimum signal quality (0-100, default: 60)
     * @param datetime Optional: specific datetime to check (ISO format)
     *                 If not provided, uses current date
     * @param interval Candle interval in minutes (default: 5)
     * @param maxResults Maximum number of recent patterns to return (default: 10)
     * @param direction Filter by direction: "LONG", "SHORT", or "ALL" (default: "ALL")
     *
     * @return List of current/recent patterns with entry signals
     *
     * Examples:
     * GET /api/realtime/patterns?symbol=TSLA&minQuality=70
     * GET /api/realtime/patterns?symbol=AAPL&minQuality=80&direction=LONG
     * GET /api/realtime/patterns?symbol=MGN&datetime=2025-10-07T16:30:00Z&minQuality=60
     */
    @GetMapping("/patterns")
    public ResponseEntity<?> getRealtimePatterns(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "60") int minQuality,
            @RequestParam(required = false) String datetime,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "10") int maxResults,
            @RequestParam(defaultValue = "ALL") String direction) {

        try {
            long startTime = System.currentTimeMillis();

            // Parse target datetime (or use current)
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

            log.info("🔴 REALTIME: Getting patterns for {} at {} (minQuality: {})",
                    symbol, targetTime, minQuality);

            // Get candles with 5-day context for accurate indicators
            List<Candle> allCandles = ibkrCandleService.getCandlesWithContext(
                    symbol, targetDate, interval);

            if (allCandles.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "symbol", symbol,
                        "datetime", targetTime.toString(),
                        "message", "No candles available - market may be closed or data not loaded",
                        "patterns", Collections.emptyList()
                ));
            }

            // Filter candles up to target time (prevent look-ahead bias)
            List<Candle> candlesUpToNow = allCandles.stream()
                    .filter(c -> !c.getTimestamp().isAfter(targetTime))
                    .collect(Collectors.toList());

            if (candlesUpToNow.size() < 10) {
                log.warn("Insufficient candles for {}: {} candles", symbol, candlesUpToNow.size());
                return ResponseEntity.ok(Map.of(
                        "symbol", symbol,
                        "datetime", targetTime.toString(),
                        "message", "Insufficient data for pattern detection (need at least 10 candles)",
                        "patterns", Collections.emptyList()
                ));
            }

            // Detect patterns
            List<PatternRecognitionResult> allPatterns =
                    patternRecognitionService.scanForPatterns(candlesUpToNow, symbol);

            log.info("Found {} total patterns for {}", allPatterns.size(), symbol);

            // Convert to signals with enhanced filters
            List<Map<String, Object>> signals = allPatterns.stream()
                    .map(pattern -> {
                        // Get base signal
                        EntrySignal baseSignal = entrySignalService.evaluatePattern(pattern);
                        if (baseSignal == null) {
                            return null;
                        }

                        // Filter candles to pattern time (prevent look-ahead)
                        List<Candle> candlesUpToPattern = candlesUpToNow.stream()
                                .filter(c -> !c.getTimestamp().isAfter(pattern.getTimestamp()))
                                .collect(Collectors.toList());

                        if (candlesUpToPattern.isEmpty()) {
                            return null;
                        }

                        // Apply enhanced filters
                        EntrySignal signal = enhancedEntrySignalService.evaluateWithFilters(
                                pattern, candlesUpToPattern, baseSignal
                        );

                        if (signal == null) {
                            return null;
                        }

                        // Build response object
                        Map<String, Object> result = new HashMap<>();
                        result.put("symbol", signal.getSymbol());
                        result.put("pattern", signal.getPattern());
                        result.put("timestamp", signal.getTimestamp().toString());
                        result.put("direction", signal.getDirection().name());
                        result.put("entryPrice", signal.getEntryPrice());
                        result.put("stopLoss", signal.getStopLoss());
                        result.put("target", signal.getTarget());
                        result.put("signalQuality", signal.getSignalQuality());
                        result.put("confidence", signal.getConfidence());
                        result.put("urgency", signal.getUrgency());
                        result.put("reason", signal.getReason());
                        result.put("riskRewardRatio", signal.getRiskRewardRatio());
                        result.put("riskPercent", signal.getRiskPercent());
                        result.put("rewardPercent", signal.getRewardPercent());

                        // Calculate age (how old is this pattern)
                        long ageMinutes = java.time.Duration.between(
                                signal.getTimestamp(), targetTime).toMinutes();
                        result.put("ageMinutes", ageMinutes);
                        result.put("isFresh", ageMinutes <= interval * 2); // Fresh if within 2 candles

                        return result;
                    })
                    .filter(Objects::nonNull)
                    .filter(s -> (double) s.get("signalQuality") >= minQuality)
                    .filter(s -> {
                        if ("ALL".equals(direction)) {
                            return true;
                        }
                        return direction.equals(s.get("direction"));
                    })
                    .sorted((a, b) -> {
                        // Sort by timestamp DESC (newest first)
                        String timeA = (String) a.get("timestamp");
                        String timeB = (String) b.get("timestamp");
                        return timeB.compareTo(timeA);
                    })
                    .limit(maxResults)
                    .collect(Collectors.toList());

            long duration = System.currentTimeMillis() - startTime;

            // Get most recent candle info
            Candle latestCandle = candlesUpToNow.get(candlesUpToNow.size() - 1);

            log.info("✅ REALTIME: Found {} patterns for {} in {}ms",
                    signals.size(), symbol, duration);

            return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "requestedDatetime", targetTime.toString(),
                    "latestCandleTime", latestCandle.getTimestamp().toString(),
                    "latestPrice", latestCandle.getClose(),
                    "minQuality", minQuality,
                    "direction", direction,
                    "totalPatterns", signals.size(),
                    "processingTimeMs", duration,
                    "patterns", signals
            ));

        } catch (Exception e) {
            log.error("Error getting realtime patterns for {}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", e.getMessage(),
                            "symbol", symbol
                    ));
        }
    }

    /**
     * Get the most recent fresh pattern for quick trading decision
     *
     * Endpoint: GET /api/realtime/latest
     *
     * Returns only the BEST pattern from the last 2 candles
     * Perfect for: "Should I enter NOW?"
     *
     * @param symbol Stock symbol
     * @param minQuality Minimum quality (default: 70)
     * @param interval Candle interval (default: 5)
     * @param direction Filter by direction (default: "LONG")
     *
     * @return Single best recent pattern or null
     *
     * Example:
     * GET /api/realtime/latest?symbol=TSLA&minQuality=75&direction=LONG
     */
    @GetMapping("/latest")
    public ResponseEntity<?> getLatestPattern(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "70") int minQuality,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "LONG") String direction) {

        try {
            // Get current patterns
            ResponseEntity<?> response = getRealtimePatterns(
                    symbol, minQuality, null, interval, 1, direction);

            Map<String, Object> body = (Map<String, Object>) response.getBody();
            List<Map<String, Object>> patterns = (List<Map<String, Object>>) body.get("patterns");

            if (patterns.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "symbol", symbol,
                        "message", "No fresh patterns meeting criteria",
                        "pattern", null
                ));
            }

            Map<String, Object> latestPattern = patterns.get(0);

            // Check if it's fresh (within last 2 candles)
            long ageMinutes = (Long) latestPattern.get("ageMinutes");
            boolean isFresh = ageMinutes <= interval * 2;

            return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "isFresh", isFresh,
                    "ageMinutes", ageMinutes,
                    "recommendation", isFresh ? "CONSIDER_ENTRY" : "PATTERN_TOO_OLD",
                    "pattern", latestPattern
            ));

        } catch (Exception e) {
            log.error("Error getting latest pattern for {}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Check multiple symbols at once for quick screening
     *
     * Endpoint: POST /api/realtime/scan
     *
     * Body: {
     *   "symbols": ["AAPL", "TSLA", "MGN"],
     *   "minQuality": 70,
     *   "direction": "LONG"
     * }
     *
     * Returns: List of symbols with fresh patterns
     *
     * Perfect for: "Which stocks have patterns RIGHT NOW?"
     */
    @PostMapping("/scan")
    public ResponseEntity<?> scanMultipleSymbols(
            @RequestBody Map<String, Object> request) {

        try {
            List<String> symbols = (List<String>) request.get("symbols");
            int minQuality = request.containsKey("minQuality")
                    ? (int) request.get("minQuality") : 70;
            String direction = request.containsKey("direction")
                    ? (String) request.get("direction") : "LONG";
            int interval = request.containsKey("interval")
                    ? (int) request.get("interval") : 5;

            log.info("🔍 REALTIME SCAN: {} symbols, minQuality: {}",
                    symbols.size(), minQuality);

            long startTime = System.currentTimeMillis();

            // Scan all symbols in parallel
            List<Map<String, Object>> results = symbols.parallelStream()
                    .map(symbol -> {
                        try {
                            ResponseEntity<?> response = getRealtimePatterns(
                                    symbol, minQuality, null, interval, 5, direction);

                            Map<String, Object> body = (Map<String, Object>) response.getBody();
                            List<Map<String, Object>> patterns =
                                    (List<Map<String, Object>>) body.get("patterns");

                            if (patterns.isEmpty()) {
                                return null;
                            }

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
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            long duration = System.currentTimeMillis() - startTime;

            log.info("✅ SCAN COMPLETE: {}/{} symbols have patterns ({}ms)",
                    results.size(), symbols.size(), duration);

            return ResponseEntity.ok(Map.of(
                    "symbolsScanned", symbols.size(),
                    "symbolsWithPatterns", results.size(),
                    "processingTimeMs", duration,
                    "results", results
            ));

        } catch (Exception e) {
            log.error("Error scanning symbols: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}