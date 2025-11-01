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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ✅ Real-time Pattern Detection Controller
 *
 * <p>Provides live pattern detection using Interactive Brokers (IBKR) market data
 * with advanced features including confluence analysis, timezone support, RTH filtering,
 * and sophisticated filtering capabilities.</p>
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Real-time data fetching from IBKR</li>
 *   <li>Multi-timezone support (Israel, US ET, UTC)</li>
 *   <li>RTH (Regular Trading Hours) filtering</li>
 *   <li>Pattern confluence detection</li>
 *   <li>Volume confirmation analysis</li>
 *   <li>Age-based and date-based filtering</li>
 *   <li>Quality threshold filtering</li>
 * </ul>
 *
 * @version 3.3 - Added RTH filtering and improved date handling
 * @author Pattern Recognition System
 * @since 3.0
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
     * Pattern type filter enumeration
     */
    public enum PatternTypeFilter {
        ALL,
        CHART_ONLY,
        CANDLESTICK_ONLY,
        STRONG_ONLY
    }

    /**
     * Get real-time pattern detection with advanced filtering, timezone support, and RTH filtering
     *
     * <p>This endpoint fetches live candle data from Interactive Brokers and performs
     * comprehensive pattern recognition with confluence analysis. It supports multiple
     * timezones, RTH filtering, and sophisticated filtering options.</p>
     *
     * <h3>Important Notes:</h3>
     * <ul>
     *   <li>System ALWAYS analyzes 5 days of historical data for pattern context</li>
     *   <li>lookbackDays controls which patterns are DISPLAYED, not analyzed</li>
     *   <li>rthOnly filters patterns to Regular Trading Hours (9:30 AM - 4:00 PM ET)</li>
     * </ul>
     *
     * <h3>Usage Examples:</h3>
     * <pre>
     * // Show only today's patterns during market hours (Israel time)
     * GET /api/realtime/patterns?symbol=LAES&datetime=2025-10-28T22:30:00&timezone=Asia/Jerusalem&lookbackDays=0&rthOnly=true
     *
     * // Show patterns including after-hours
     * GET /api/realtime/patterns?symbol=LAES&datetime=2025-10-28T18:00:00&timezone=Asia/Jerusalem&rthOnly=false
     *
     * // High-quality patterns from today only
     * GET /api/realtime/patterns?symbol=AAPL&minQuality=85&lookbackDays=0&rthOnly=true
     * </pre>
     *
     * @param symbol Stock ticker symbol (1-5 uppercase letters)
     * @param minQuality Minimum signal quality threshold (0-100, default: 60)
     * @param datetime Target datetime in specified timezone (format: yyyy-MM-ddTHH:mm:ss)
     * @param timezone Timezone for datetime interpretation (default: "Asia/Jerusalem")
     * @param interval Candle interval in minutes (1-60, default: 5)
     * @param maxResults Maximum number of patterns to return (1-50, default: 10)
     * @param direction Trade direction filter (LONG/SHORT/ALL, default: ALL)
     * @param patternType Pattern category filter (default: ALL)
     * @param applyFilters Apply quality and volume filters (default: true)
     * @param maxAgeMinutes Maximum pattern age in minutes (0-10080, default: 240)
     * @param lookbackDays How many days of patterns to DISPLAY (0-5, default: 0)
     *                     Note: System analyzes 5 days for context regardless
     * @param rthOnly Show only Regular Trading Hours patterns (default: true)
     *                RTH = 9:30 AM - 4:00 PM ET, Monday-Friday
     *
     * @return ResponseEntity with pattern detection results
     */
    @GetMapping("/patterns")
    public ResponseEntity<?> getRealtimePatterns(
            @RequestParam
            @NotBlank(message = "Symbol is required")
            @Pattern(regexp = "^[A-Z]{1,5}$", message = "Symbol must be 1-5 uppercase letters")
            String symbol,

            @RequestParam(defaultValue = "60")
            @Min(value = 0, message = "minQuality must be between 0 and 100")
            @Max(value = 100, message = "minQuality must be between 0 and 100")
            int minQuality,

            @RequestParam(required = false)
            String datetime,

            @RequestParam(defaultValue = "Asia/Jerusalem")
            String timezone,

            @RequestParam(defaultValue = "5")
            @Min(value = 1, message = "interval must be between 1 and 60 minutes")
            @Max(value = 60, message = "interval must be between 1 and 60 minutes")
            int interval,

            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "maxResults must be between 1 and 50")
            @Max(value = 50, message = "maxResults must be between 1 and 50")
            int maxResults,

            @RequestParam(defaultValue = "ALL")
            String direction,

            @RequestParam(defaultValue = "ALL")
            PatternTypeFilter patternType,

            @RequestParam(defaultValue = "true")
            boolean applyFilters,

            @RequestParam(defaultValue = "240")
            @Min(value = 0, message = "maxAgeMinutes must be between 0 and 10080 (1 week)")
            @Max(value = 10080, message = "maxAgeMinutes must be between 0 and 10080 (1 week)")
            int maxAgeMinutes,

            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "lookbackDays must be between 0 and 5")
            @Max(value = 5, message = "lookbackDays must be between 0 and 5")
            int lookbackDays,

            @RequestParam(defaultValue = "true")
            boolean rthOnly) {

        try {
            long startTime = System.currentTimeMillis();

            // ========== STEP 1: Parse timezone and datetime ==========

            ZoneId zoneId;
            try {
                zoneId = ZoneId.of(timezone);
            } catch (Exception e) {
                log.error("Invalid timezone: {}", timezone);
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "INVALID_TIMEZONE");
                errorResponse.put("message", "Invalid timezone: " + timezone);
                errorResponse.put("providedTimezone", timezone);
                errorResponse.put("validExamples", List.of(
                        "Asia/Jerusalem (Israel)",
                        "America/New_York (US Eastern)",
                        "Europe/London (UK)",
                        "UTC (Universal)"
                ));
                return ResponseEntity.badRequest().body(errorResponse);
            }

            Instant targetTime;
            if (datetime != null && !datetime.isEmpty()) {
                try {
                    LocalDateTime localDateTime = LocalDateTime.parse(datetime,
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    targetTime = localDateTime.atZone(zoneId).toInstant();

                    log.info("🕐 Parsed time: {} {} = {} UTC",
                            datetime, timezone, targetTime);

                } catch (Exception e) {
                    log.error("Invalid datetime format: {}", datetime);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "INVALID_DATETIME");
                    errorResponse.put("message", "Invalid datetime format. Use: yyyy-MM-ddTHH:mm:ss");
                    errorResponse.put("providedDatetime", datetime);
                    errorResponse.put("providedTimezone", timezone);
                    errorResponse.put("expectedFormat", "2025-10-28T18:15:00");
                    errorResponse.put("examples", Map.of(
                            "Israel morning", "2025-10-28T09:00:00",
                            "Israel evening", "2025-10-28T22:30:00",
                            "US market open", "2025-10-28T09:30:00 (with timezone=America/New_York)",
                            "US market close", "2025-10-28T16:00:00 (with timezone=America/New_York)"
                    ));
                    return ResponseEntity.badRequest().body(errorResponse);
                }
            } else {
                targetTime = Instant.now();
            }

            // Calculate date ranges
            LocalDate requestedDate = LocalDate.ofInstant(targetTime, zoneId);
            LocalDate minDate = requestedDate.minusDays(lookbackDays);

            // Formatters
            DateTimeFormatter israelFormatter = DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("Asia/Jerusalem"));

            DateTimeFormatter usFormatter = DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("America/New_York"));

            log.info("🔍 REALTIME: {} patterns (quality: {}+, age: <{}min, lookback: {}d, RTH: {}, tz: {})",
                    symbol, minQuality, maxAgeMinutes, lookbackDays, rthOnly, timezone);
            log.info("  Target: {} {} = {} UTC", requestedDate, timezone, targetTime);
            log.info("  Display range: {} to {}", minDate, requestedDate);
            log.info("  Analysis: Using 5-day context for pattern formation");

            // ========== STEP 2: Fetch candles from IBKR ==========

            String targetDateStr = requestedDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

            List<Candle> allCandles = ibkrCandleService.getCandlesWithContext(
                    symbol, targetDateStr, interval, true);

            if (allCandles.isEmpty()) {
                log.warn("No candles available for {} on {}", symbol, targetDateStr);
                return ResponseEntity.ok(buildEmptyResponse(
                        symbol, targetTime, patternType, zoneId, israelFormatter, rthOnly));
            }

            log.info("  Candles fetched: {} (5-day context)", allCandles.size());

            // Filter candles up to target time
            List<Candle> candlesUpToNow = allCandles.stream()
                    .filter(c -> !c.getTimestamp().isAfter(targetTime))
                    .collect(Collectors.toList());

            if (candlesUpToNow.size() < 10) {
                log.warn("Insufficient candles for {}: only {} available",
                        symbol, candlesUpToNow.size());
                return ResponseEntity.ok(buildInsufficientDataResponse(
                        symbol, targetTime, patternType, candlesUpToNow.size(),
                        zoneId, israelFormatter, rthOnly));
            }

            log.info("  Candles for analysis: {} (up to target time)", candlesUpToNow.size());

            // ========== STEP 3: Detect patterns ==========

            List<PatternRecognitionResult> allPatterns =
                    patternRecognitionService.scanForPatterns(candlesUpToNow, symbol);

            log.info("  Patterns detected (all 5 days): {}", allPatterns.size());

            // Log pattern dates for debugging
            if (log.isDebugEnabled() && !allPatterns.isEmpty()) {
                Map<LocalDate, Long> patternsByDate = allPatterns.stream()
                        .collect(Collectors.groupingBy(
                                p -> LocalDate.ofInstant(p.getTimestamp(), zoneId),
                                Collectors.counting()
                        ));
                patternsByDate.forEach((date, count) ->
                        log.debug("    {}: {} patterns", date, count)
                );
            }

            // ========== STEP 4: Create EntrySignal objects ==========

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

            // ========== STEP 5: Apply confluence detection ==========

            entrySignals = confluenceService.detectConfluence(entrySignals);

            log.info("  After confluence analysis: {} signals", entrySignals.size());

            // ========== STEP 6: Convert to maps ==========

            List<Map<String, Object>> allSignalMaps = entrySignals.stream()
                    .map(signal -> createSignalMapFromSignal(signal, targetTime, interval))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            log.info("  Converted to maps: {}", allSignalMaps.size());

            // ========== STEP 7: Apply filters ==========

            // FILTER 1: By date range (what to display)
            List<Map<String, Object>> dateFiltered = allSignalMaps.stream()
                    .filter(signal -> {
                        Instant patternTime = Instant.parse((String) signal.get("timestamp"));
                        LocalDate patternDate = LocalDate.ofInstant(patternTime, zoneId);

                        boolean inRange = !patternDate.isBefore(minDate) &&
                                !patternDate.isAfter(requestedDate);

                        if (!inRange && log.isDebugEnabled()) {
                            log.debug("  ⏭️  Filtered out: {} pattern from {} (outside display range: {} to {})",
                                    signal.get("pattern"), patternDate, minDate, requestedDate);
                        }

                        return inRange;
                    })
                    .collect(Collectors.toList());

            log.info("  After date filter (display: {} to {}): {}",
                    minDate, requestedDate, dateFiltered.size());

            // FILTER 2: RTH Only (Regular Trading Hours)
            List<Map<String, Object>> rthFiltered = dateFiltered;

            if (rthOnly) {
                rthFiltered = dateFiltered.stream()
                        .filter(signal -> {
                            Instant patternTime = Instant.parse((String) signal.get("timestamp"));

                            // Convert to US Eastern Time
                            LocalDateTime etDateTime = LocalDateTime.ofInstant(
                                    patternTime,
                                    ZoneId.of("America/New_York")
                            );

                            // Check if weekday
                            java.time.DayOfWeek dayOfWeek = etDateTime.getDayOfWeek();
                            if (dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                                    dayOfWeek == java.time.DayOfWeek.SUNDAY) {
                                if (log.isDebugEnabled()) {
                                    log.debug("  ⏭️  Filtered out: {} pattern from {} (weekend)",
                                            signal.get("pattern"), etDateTime.toLocalDate());
                                }
                                return false;
                            }

                            // Check if during RTH (9:30 AM - 4:00 PM ET)
                            int hour = etDateTime.getHour();
                            int minute = etDateTime.getMinute();
                            int timeInMinutes = hour * 60 + minute;

                            // Market: 9:30 AM (570 min) - 4:00 PM (960 min)
                            boolean isDuringRTH = timeInMinutes >= 570 && timeInMinutes < 960;

                            if (!isDuringRTH && log.isDebugEnabled()) {
                                log.debug("  ⏭️  Filtered out: {} pattern from {} {:02d}:{:02d} ET (outside RTH)",
                                        signal.get("pattern"),
                                        etDateTime.toLocalDate(),
                                        hour,
                                        minute);
                            }

                            return isDuringRTH;
                        })
                        .collect(Collectors.toList());

                log.info("  After RTH filter (9:30 AM - 4:00 PM ET, Mon-Fri): {}",
                        rthFiltered.size());
            } else {
                log.info("  RTH filter disabled - showing all hours");
            }

            // FILTER 3: By age in minutes
            List<Map<String, Object>> ageFiltered = rthFiltered.stream()
                    .filter(signal -> {
                        long ageMinutes = ((Number) signal.get("ageMinutes")).longValue();
                        return ageMinutes <= maxAgeMinutes;
                    })
                    .collect(Collectors.toList());

            log.info("  After age filter (<= {}min): {}", maxAgeMinutes, ageFiltered.size());

            // FILTER 4: Pattern type
            List<Map<String, Object>> typeFiltered = ageFiltered.stream()
                    .filter(s -> matchesPatternType((CandlePattern) s.get("pattern"), patternType))
                    .collect(Collectors.toList());

            log.info("  After pattern type filter ({}): {}", patternType, typeFiltered.size());

            // FILTER 5: Strong pattern filter
            List<Map<String, Object>> strongFiltered = typeFiltered.stream()
                    .filter(s -> applyFilters ? strongPatternFilter.isStrongPattern(
                            (CandlePattern) s.get("pattern"),
                            (double) s.get("signalQuality"),
                            (boolean) s.get("hasVolumeConfirmation")) : true)
                    .collect(Collectors.toList());

            log.info("  After strong filter (enabled: {}): {}", applyFilters, strongFiltered.size());

            // FILTER 6: Quality threshold
            List<Map<String, Object>> qualityFiltered = strongFiltered.stream()
                    .filter(s -> (double) s.get("signalQuality") >= minQuality)
                    .collect(Collectors.toList());

            log.info("  After quality filter (>= {}): {}", minQuality, qualityFiltered.size());

            // FILTER 7: Direction
            List<Map<String, Object>> directionFiltered = qualityFiltered.stream()
                    .filter(s -> "ALL".equals(direction) || direction.equals(s.get("direction")))
                    .collect(Collectors.toList());

            log.info("  After direction filter ({}): {}", direction, directionFiltered.size());

            // ========== STEP 8: Sort and limit ==========

            List<Map<String, Object>> finalPatterns = directionFiltered.stream()
                    .sorted((a, b) -> {
                        long ageA = ((Number) a.get("ageMinutes")).longValue();
                        long ageB = ((Number) b.get("ageMinutes")).longValue();
                        return Long.compare(ageA, ageB);
                    })
                    .limit(maxResults)
                    .collect(Collectors.toList());

            // ========== STEP 9: Check if any patterns found ==========

            if (finalPatterns.isEmpty()) {
                log.info("  No patterns match criteria");

                Map<String, Object> notFoundResponse = new HashMap<>();
                notFoundResponse.put("message", "No patterns found matching criteria");
                notFoundResponse.put("symbol", symbol);
                notFoundResponse.put("requestedDatetime", targetTime.toString());
                notFoundResponse.put("requestedDatetimeIsrael", israelFormatter.format(targetTime));
                notFoundResponse.put("requestedDatetimeET", usFormatter.format(targetTime));
                notFoundResponse.put("requestedDate", requestedDate.toString());
                notFoundResponse.put("dateRange", minDate + " to " + requestedDate);
                notFoundResponse.put("timezone", timezone);
                notFoundResponse.put("rthOnly", rthOnly);
                notFoundResponse.put("rthDescription", rthOnly ?
                        "Only showing patterns from 9:30 AM - 4:00 PM ET, Monday-Friday" :
                        "Showing patterns from all hours");
                notFoundResponse.put("maxAgeMinutes", maxAgeMinutes);
                notFoundResponse.put("minQuality", minQuality);
                notFoundResponse.put("lookbackDays", lookbackDays);
                notFoundResponse.put("direction", direction);
                notFoundResponse.put("patternType", patternType.toString());
                notFoundResponse.put("filtersApplied", applyFilters);
                notFoundResponse.put("patternsDetected", allPatterns.size());
                notFoundResponse.put("patternsAfterAllFilters", 0);
                notFoundResponse.put("note", "System analyzed 5 days of data for context");
                notFoundResponse.put("suggestions", List.of(
                        "Try rthOnly=false to include after-hours patterns (current: " + rthOnly + ")",
                        "Try increasing lookbackDays (current: " + lookbackDays + ")",
                        "Try increasing maxAgeMinutes (current: " + maxAgeMinutes + ")",
                        "Try lowering minQuality (current: " + minQuality + ")",
                        "Try direction=ALL (current: " + direction + ")",
                        "Try patternType=ALL (current: " + patternType + ")",
                        "Try applyFilters=false (current: " + applyFilters + ")"
                ));

                return ResponseEntity.status(404).body(notFoundResponse);
            }

            // ========== STEP 10: Get current price ==========

            Candle latestCandle = candlesUpToNow.get(candlesUpToNow.size() - 1);
            Map<String, Object> priceInfo = getPriceInfo(symbol, latestCandle, targetTime);

            // ========== STEP 11: Build success response ==========

            long processingTime = System.currentTimeMillis() - startTime;

            log.info("✅ REALTIME: {} patterns returned for {} in {}ms",
                    finalPatterns.size(), symbol, processingTime);

            Map<String, Object> successResponse = new HashMap<>();
            successResponse.put("symbol", symbol);
            successResponse.put("requestedDatetime", targetTime.toString());
            successResponse.put("requestedDatetimeIsrael", israelFormatter.format(targetTime));
            successResponse.put("requestedDatetimeET", usFormatter.format(targetTime));
            successResponse.put("requestedDate", requestedDate.toString());
            successResponse.put("dateRange", minDate + " to " + requestedDate);
            successResponse.put("timezone", timezone);
            successResponse.put("rthOnly", rthOnly);
            successResponse.put("rthDescription", rthOnly ?
                    "Showing only Regular Trading Hours (9:30 AM - 4:00 PM ET, Mon-Fri)" :
                    "Showing patterns from all hours");
            successResponse.put("currentPrice", priceInfo.get("currentPrice"));
            successResponse.put("latestPrice", priceInfo.get("latestCandlePrice"));
            successResponse.put("latestCandleTime", priceInfo.get("latestCandleTime"));
            successResponse.put("latestCandlePrice", priceInfo.get("latestCandlePrice"));
            successResponse.put("priceIsRealTime", priceInfo.get("priceIsRealTime"));
            successResponse.put("priceAgeMinutes", priceInfo.get("priceAgeMinutes"));
            successResponse.put("candleAgeMinutes", priceInfo.get("candleAgeMinutes"));
            successResponse.put("patterns", finalPatterns);
            successResponse.put("totalPatterns", finalPatterns.size());
            successResponse.put("patternType", patternType.toString());
            successResponse.put("direction", direction);
            successResponse.put("minQuality", minQuality);
            successResponse.put("maxAgeMinutes", maxAgeMinutes);
            successResponse.put("lookbackDays", lookbackDays);
            successResponse.put("filtersApplied", applyFilters);
            successResponse.put("processingTimeMs", processingTime);

            // Debug info
            Map<String, Object> debugInfo = new HashMap<>();
            debugInfo.put("patternsDetected", allPatterns.size());
            debugInfo.put("afterDateFilter", dateFiltered.size());
            debugInfo.put("afterRTHFilter", rthFiltered.size());
            debugInfo.put("afterAgeFilter", ageFiltered.size());
            debugInfo.put("afterTypeFilter", typeFiltered.size());
            debugInfo.put("afterStrongFilter", strongFiltered.size());
            debugInfo.put("afterQualityFilter", qualityFiltered.size());
            debugInfo.put("afterDirectionFilter", directionFiltered.size());
            debugInfo.put("finalReturned", finalPatterns.size());
            successResponse.put("debug", debugInfo);

            return ResponseEntity.ok(successResponse);

        } catch (Exception e) {
            log.error("❌ Error detecting patterns for {}: {}", symbol, e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "PATTERN_DETECTION_FAILED");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("symbol", symbol);
            errorResponse.put("exceptionType", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    // ==================== HELPER METHODS ====================

    /**
     * Get current price with real-time data or fallback to candle
     */
    private Map<String, Object> getPriceInfo(String symbol, Candle latestCandle, Instant targetTime) {
        Map<String, Object> info = new HashMap<>();

        Double realtimePrice = marketDataService.getRealTimePrice(symbol);
        Double candlePrice = latestCandle.getClose();

        long candleAgeMinutes = java.time.Duration
                .between(latestCandle.getTimestamp(), targetTime)
                .toMinutes();

        if (realtimePrice != null) {
            info.put("currentPrice", realtimePrice);
            info.put("priceIsRealTime", true);
            info.put("priceAgeMinutes", 0L);
            log.debug("Using real-time price for {}: ${} (candle was: ${})",
                    symbol, realtimePrice, candlePrice);
        } else {
            info.put("currentPrice", candlePrice);
            info.put("priceIsRealTime", false);
            info.put("priceAgeMinutes", candleAgeMinutes);
            if (candleAgeMinutes > 5) {
                info.put("priceWarning", "Price is " + candleAgeMinutes + " minutes old");
            }
            log.debug("Using candle price for {}: ${} (age: {} min)",
                    symbol, candlePrice, candleAgeMinutes);
        }

        info.put("latestCandlePrice", candlePrice);
        info.put("latestCandleTime", latestCandle.getTimestamp().toString());
        info.put("candleAgeMinutes", candleAgeMinutes);

        return info;
    }

    /**
     * Create signal map from EntrySignal object
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

            // Israel timezone
            String israelTime = signal.getTimestamp()
                    .atZone(java.time.ZoneId.of("Asia/Jerusalem"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            map.put("timestampIsrael", israelTime);

            if (signal.getVolume() != null) {
                map.put("volume", signal.getVolume());
                map.put("avgVolume", signal.getAverageVolume());
                map.put("volumeRatio", signal.getVolumeRatio());
            }

            // Confluence metadata
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

    private Map<String, Object> buildEmptyResponse(
            String symbol, Instant time, PatternTypeFilter type,
            ZoneId zoneId, DateTimeFormatter formatter, boolean rthOnly) {
        Map<String, Object> response = new HashMap<>();
        response.put("symbol", symbol);
        response.put("requestedDatetime", time.toString());
        response.put("requestedDatetimeLocal", formatter.format(time));
        response.put("timezone", zoneId.getId());
        response.put("patternType", type.toString());
        response.put("rthOnly", rthOnly);
        response.put("message", "No candles available for this symbol");
        response.put("patterns", Collections.emptyList());
        response.put("totalPatterns", 0);
        return response;
    }

    private Map<String, Object> buildInsufficientDataResponse(
            String symbol, Instant time, PatternTypeFilter type, int candleCount,
            ZoneId zoneId, DateTimeFormatter formatter, boolean rthOnly) {
        Map<String, Object> response = new HashMap<>();
        response.put("symbol", symbol);
        response.put("requestedDatetime", time.toString());
        response.put("requestedDatetimeLocal", formatter.format(time));
        response.put("timezone", zoneId.getId());
        response.put("patternType", type.toString());
        response.put("rthOnly", rthOnly);
        response.put("message", "Insufficient data (need 10+ candles, got " + candleCount + ")");
        response.put("candlesAvailable", candleCount);
        response.put("candlesRequired", 10);
        response.put("patterns", Collections.emptyList());
        response.put("totalPatterns", 0);
        return response;
    }

    /**
     * ✅ SCAN endpoint - scan multiple symbols
     */
    @PostMapping("/scan")
    public ResponseEntity<?> scanMultipleSymbols(@RequestBody ScanRequest request) {
        try {
            if (request.symbols == null || request.symbols.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "INVALID_INPUT",
                                "message", "symbols list is required"));
            }

            if (request.symbols.size() > 50) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "TOO_MANY_SYMBOLS",
                                "message", "Maximum 50 symbols allowed"));
            }

            List<String> symbols = request.symbols;
            int minQuality = request.minQuality != null ? request.minQuality : 70;
            String direction = request.direction != null ? request.direction : "LONG";
            int interval = request.interval != null ? request.interval : 5;
            PatternTypeFilter patternType = request.patternType != null ?
                    request.patternType : PatternTypeFilter.ALL;

            log.info("🔍 SCAN: {} symbols, {} patterns", symbols.size(), patternType);

            long start = System.currentTimeMillis();
            List<Map<String, Object>> results = new ArrayList<>();

            for (String symbol : symbols) {
                Map<String, Object> result = scanSymbol(
                        symbol, minQuality, interval, direction, patternType);
                if (result != null) {
                    results.add(result);
                }
            }

            long duration = System.currentTimeMillis() - start;

            Map<String, Object> response = new HashMap<>();
            response.put("symbolsScanned", symbols.size());
            response.put("symbolsWithPatterns", results.size());
            response.put("patternType", patternType.toString());
            response.put("processingTimeMs", duration);
            response.put("results", results);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error scanning symbols: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "SCAN_FAILED", "message", e.getMessage()));
        }
    }

    /**
     * Scan request DTO
     */
    public static class ScanRequest {
        @NotNull
        @Size(min = 1, max = 50, message = "Must provide 1-50 symbols")
        public List<@Pattern(regexp = "^[A-Z]{1,5}$") String> symbols;

        @Min(0) @Max(100)
        public Integer minQuality;

        public String direction;

        @Min(1) @Max(60)
        public Integer interval;

        public PatternTypeFilter patternType;
    }

    private Map<String, Object> scanSymbol(
            String symbol, int minQuality, int interval,
            String direction, PatternTypeFilter patternType) {
        try {
            ResponseEntity<?> response = getRealtimePatterns(
                    symbol, minQuality, null, "Asia/Jerusalem", interval,
                    5, direction, patternType, true, 240, 0, true);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();

            if (body == null) return null;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> patterns =
                    (List<Map<String, Object>>) body.get("patterns");

            if (patterns == null || patterns.isEmpty()) return null;

            Map<String, Object> result = new HashMap<>();
            result.put("symbol", symbol);
            result.put("latestPrice", body.get("latestPrice"));
            result.put("patternsFound", patterns.size());
            result.put("bestPattern", patterns.get(0));

            return result;
        } catch (Exception e) {
            log.error("Error scanning {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Clear real-time price cache
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

    /**
     * Get latest patterns endpoint (backward compatibility)
     */
    @GetMapping("/latest")
    public ResponseEntity<?> getLatestPatterns(
            @RequestParam @NotBlank String symbol,
            @RequestParam(defaultValue = "60") int minQuality,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "10") int maxResults) {

        return getRealtimePatterns(
                symbol, minQuality, null, "Asia/Jerusalem", interval,
                maxResults, "ALL", PatternTypeFilter.ALL, true, 240, 0, true);
    }
}