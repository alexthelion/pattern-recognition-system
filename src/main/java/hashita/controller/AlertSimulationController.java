package hashita.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import hashita.data.Candle;
import hashita.data.CandlePattern;
import hashita.data.PatternRecognitionResult;
import hashita.data.entities.DailyAlertEnhanced;
import hashita.data.entities.StockData;
import hashita.repository.DailyAlertEnhancedRepository;
import hashita.repository.StockDataRepository;
import hashita.service.*;
import hashita.service.EntrySignalService.EntrySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ✅ UPDATED: Alert simulation with chart pattern support
 *
 * Uses MongoDB historical data (NOT IBKR - IBKR is only for realtime!)
 *
 * @version 3.0 - Chart patterns + Pattern type filter + MongoDB only
 */
@RestController
@RequestMapping("/api/simulate")
@Slf4j
@RequiredArgsConstructor
public class AlertSimulationController {

    private final PatternAnalysisService patternAnalysisService;
    private final EntrySignalService entrySignalService;
    private final EnhancedEntrySignalService enhancedEntrySignalService;
    private final StrongPatternFilter strongPatternFilter;
    private final StockDataRepository stockDataRepository;
    private final DailyAlertEnhancedRepository dailyAlertEnhancedRepository;
    private final PatternConfluenceService confluenceService;
    private final ObjectMapper objectMapper;
    /**
     * ✅ NEW: Pattern type filter
     */
    public enum PatternTypeFilter {
        ALL,
        CHART_ONLY,
        CANDLESTICK_ONLY,
        STRONG_ONLY
    }

    /**
     * Simulate bullish alerts for a given date
     *
     * ✅ NEW: Added patternType parameter
     */
    @GetMapping("/alerts/bullish")
    public ResponseEntity<?> simulateBullishAlerts(
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality,
            @RequestParam(defaultValue = "10") int maxAlertsPerSymbol,
            @RequestParam(defaultValue = "ALL") PatternTypeFilter patternType,
            @RequestParam(defaultValue = "false") boolean strictMarketHours) {

        return simulateAlertsByDirection(date, interval, minQuality,
                maxAlertsPerSymbol, "LONG", patternType, strictMarketHours);    }

    /**
     * Simulate bearish alerts for a given date
     *
     * ✅ NEW: Added patternType parameter
     */
    @GetMapping("/alerts/bearish")
    public ResponseEntity<?> simulateBearishAlerts(
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality,
            @RequestParam(defaultValue = "10") int maxAlertsPerSymbol,
            @RequestParam(defaultValue = "ALL") PatternTypeFilter patternType,
            @RequestParam(defaultValue = "false") boolean strictMarketHours) {

        return simulateAlertsByDirection(date, interval, minQuality, maxAlertsPerSymbol, "SHORT", patternType, strictMarketHours);
    }

    /**
     * Simulate all alerts (both bullish and bearish) for a given date
     *
     * ✅ NEW: Added patternType parameter
     */
    @GetMapping("/alerts/all")
    public ResponseEntity<?> simulateAllAlerts(
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality,
            @RequestParam(defaultValue = "10") int maxAlertsPerSymbol,
            @RequestParam(defaultValue = "ALL") PatternTypeFilter patternType,
            @RequestParam(defaultValue = "false") boolean strictMarketHours) {

        return simulateAlertsByDirection(date, interval, minQuality, maxAlertsPerSymbol, "ALL", patternType, strictMarketHours);
    }

    /**
     * Simulate alerts for a given direction
     *
     * ✅ NEW: Added patternType parameter
     */
    private ResponseEntity<?> simulateAlertsByDirection(
            String date, int interval, int minQuality, int maxAlertsPerSymbol,
            String direction, PatternTypeFilter patternType, boolean strictMarketHours) {

        try {
            LocalDate targetDate = LocalDate.parse(date);
            log.info("🎬 Simulating {} {} alerts for date: {}", direction, patternType, targetDate);

            List<String> symbols = getSymbolsForDate(targetDate);
            log.info("Found {} symbols with data on {}", symbols.size(), targetDate);

            List<Map<String, Object>> allAlerts = new ArrayList<>();
            int totalSignals = 0;

            for (String symbol : symbols) {
                try {
                    log.info("🔍 Processing symbol: {}", symbol);

                    List<Map<String, Object>> symbolAlerts = generateAlertsForSymbol(
                            symbol, date, interval, minQuality, maxAlertsPerSymbol, direction, patternType,
                            strictMarketHours);

                    allAlerts.addAll(symbolAlerts);
                    totalSignals += symbolAlerts.size();

                    if (!symbolAlerts.isEmpty()) {
                        log.info("  ✅ {}: {} signals ({} patterns)",
                                symbol, symbolAlerts.size(), patternType);
                    }

                } catch (Exception e) {
                    log.error("  ❌ {}: Error - {}", symbol, e.getMessage(), e);
                }
            }

            // Sort by candleTime, then by quality
            allAlerts.sort((a, b) -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> tickerDataA = (Map<String, Object>) a.get("tickerData");
                @SuppressWarnings("unchecked")
                Map<String, Object> tickerDataB = (Map<String, Object>) b.get("tickerData");

                String timeA = (String) tickerDataA.get("candleTime");
                String timeB = (String) tickerDataB.get("candleTime");
                int timeCompare = timeA.compareTo(timeB);
                if (timeCompare != 0) return timeCompare;

                Double qualityA = (Double) tickerDataA.get("signalQuality");
                Double qualityB = (Double) tickerDataB.get("signalQuality");
                return Double.compare(qualityB, qualityA);
            });

            return ResponseEntity.ok(Map.of(
                    "date", date,
                    "patternType", patternType.toString(),
                    "symbolsProcessed", symbols.size(),
                    "totalAlerts", totalSignals,
                    "minQuality", minQuality,
                    "maxAlertsPerSymbol", maxAlertsPerSymbol,
                    "alerts", allAlerts,
                    "summary", generateSummary(allAlerts)
            ));

        } catch (Exception e) {
            log.error("Error simulating alerts", e);
            String errorMessage = e.getMessage() != null
                    ? e.getMessage()
                    : "Internal server error";
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", errorMessage, "success", false));
        }
    }

    /**
     * Generate alerts for a single symbol
     *
     * ✅ UPDATED: Added patternType filter
     * ⚠️ IMPORTANT: This uses PatternAnalysisService which gets candles from MongoDB, NOT IBKR!
     */
    private List<Map<String, Object>> generateAlertsForSymbol(
            String symbol, String date, int interval, int minQuality, int maxAlerts, String direction, PatternTypeFilter patternType, boolean strictMarketHours) {

        // PatternAnalysisService internally uses MongoDB data
        List<PatternRecognitionResult> patterns =
                patternAnalysisService.analyzeStockForDate(symbol, date, interval);

        log.debug("    Step 1: {} patterns found", patterns.size());

        if (patterns.isEmpty()) {
            return Collections.emptyList();
        }

        // Get candles from patterns (they already contain candles from MongoDB)
        // PatternRecognitionResult has getCandles() method
        List<Candle> allCandles = patterns.isEmpty() ? Collections.emptyList() : patterns.get(0).getCandles();

        if (allCandles.isEmpty()) {
            log.warn("    No candles in patterns for {}, skipping", symbol);
            return Collections.emptyList();
        }

        List<EntrySignal> signals = patterns.stream()
                .map(pattern -> {
                    EntrySignal baseSignal = entrySignalService.evaluatePattern(pattern);
                    if (baseSignal == null) return null;

                    // Get candles from the pattern result (MongoDB data)
                    List<Candle> patternCandles = pattern.getCandles();

                    // Filter to prevent look-ahead bias
                    List<Candle> candlesUpToPattern = patternCandles.stream()
                            .filter(c -> !c.getTimestamp().isAfter(pattern.getTimestamp()))
                            .collect(Collectors.toList());

                    if (candlesUpToPattern.isEmpty()) {
                        log.warn("No historical candles for pattern at {}", pattern.getTimestamp());
                        return null;
                    }

                    return enhancedEntrySignalService.evaluateWithFilters(
                            pattern, candlesUpToPattern, baseSignal
                    );
                })
                .filter(s -> s != null)

                // ✅ NEW: Pattern type filter
                .filter(s -> matchesPatternType(s.getPattern(), patternType))

                // Strong pattern filter
                .filter(s -> {
                    boolean isStrong = strongPatternFilter.isStrongPattern(
                            s.getPattern(),
                            s.getSignalQuality(),
                            s.isHasVolumeConfirmation()
                    );

                    if (!isStrong) {
                        log.debug("    ❌ Weak pattern filtered: {} (quality: {})",
                                s.getPattern(), s.getSignalQuality());
                    }

                    return isStrong;
                })

                // Market hours filter (9:30 AM - 4:00 PM ET)
                .filter(s -> {
                    if (!strictMarketHours) {
                        log.debug("    ✅ Allowing pattern (simulation mode): {} at {}",
                                s.getSymbol(), s.getTimestamp());
                        return true;  // Allow all patterns
                    }
                    java.time.ZonedDateTime nyTime = s.getTimestamp()
                            .atZone(java.time.ZoneId.of("America/New_York"));
                    int hour = nyTime.getHour();
                    int minute = nyTime.getMinute();

                    if (hour < 9 || (hour == 9 && minute < 30) || hour >= 16) {
                        log.debug("    ❌ After-hours signal filtered: {} at {}",
                                s.getSymbol(), nyTime);
                        return false;
                    }
                    return true;
                })

                .filter(s -> {
                    if ("ALL".equals(direction)) {
                        return true;
                    }
                    return direction.equals(s.getDirection().name());
                })
                .filter(s -> s.getSignalQuality() >= minQuality)
                .sorted((a, b) -> Double.compare(b.getSignalQuality(), a.getSignalQuality()))
                .limit(maxAlerts)
                .collect(Collectors.toList());

        signals = confluenceService.detectConfluence(signals);

        log.debug("    Step 2: {} {} signals after filters (minQuality: {})",
                signals.size(), direction, minQuality);

        return signals.stream()
                .map(signal -> createAlertDocument(symbol, signal, date))
                .collect(Collectors.toList());
    }

    /**
     * ✅ NEW: Check if pattern matches the requested type
     */
    private boolean matchesPatternType(CandlePattern pattern, PatternTypeFilter filter) {
        return switch (filter) {
            case ALL -> true;
            case CHART_ONLY -> pattern.isChartPattern();
            case CANDLESTICK_ONLY -> !pattern.isChartPattern();
            case STRONG_ONLY -> Set.of(
                    CandlePattern.FALLING_WEDGE,
                    CandlePattern.RISING_WEDGE,
                    CandlePattern.BULLISH_ENGULFING,
                    CandlePattern.BEARISH_ENGULFING,
                    CandlePattern.MORNING_STAR,
                    CandlePattern.EVENING_STAR
            ).contains(pattern);
        };
    }

    /**
     * Create alert document in MongoDB DailyAlertEnhanced format
     *
     * ✅ UPDATED: Added isChartPattern field
     */
    private Map<String, Object> createAlertDocument(String symbol, EntrySignal signal, String date) {
        Map<String, Object> alert = new LinkedHashMap<>();

        alert.put("_id", generateObjectId());
        alert.put("date", date);
        alert.put("timestamp", signal.getTimestamp().toString());
        alert.put("source", "Simulated Alert");
        alert.put("israeliTime", hashita.util.TimeUtils.toIsraeliTime(signal.getTimestamp()));
        alert.put("israeliDateTime", hashita.util.TimeUtils.toIsraeliDateTime(signal.getTimestamp()));
        alert.put("israeliTimestamp", hashita.util.TimeUtils.toIsraeliISO(signal.getTimestamp()));

        Map<String, Object> tickerData = new LinkedHashMap<>();
        tickerData.put("symbol", symbol);
        tickerData.put("entryPrice", signal.getEntryPrice());
        tickerData.put("candleTime",
                signal.getTimestamp()
                        .atZone(ZoneId.of("Asia/Jerusalem"))
                        .format(DateTimeFormatter.ofPattern("HH:mm")));
        tickerData.put("stopLoss", signal.getStopLoss());
        tickerData.put("target", signal.getTarget());
        tickerData.put("pattern", signal.getPattern().name());
        tickerData.put("isChartPattern", signal.getPattern().isChartPattern()); // ✅ NEW
        tickerData.put("direction", signal.getDirection().name());
        tickerData.put("riskRewardRatio", signal.getRiskRewardRatio());
        tickerData.put("confidence", signal.getConfidence());
        tickerData.put("signalQuality", signal.getSignalQuality());
        tickerData.put("urgency", signal.getUrgency().name());
        tickerData.put("reason", signal.getReason());
        tickerData.put("hasVolumeConfirmation", signal.isHasVolumeConfirmation());
        tickerData.put("riskAmount", signal.getRiskAmount());
        tickerData.put("rewardAmount", signal.getRewardAmount());
        tickerData.put("riskPercent", signal.getRiskPercent());
        tickerData.put("rewardPercent", signal.getRewardPercent());
        tickerData.put("isGlobal", true);

        if (signal.getVolume() != null) {
            tickerData.put("volume", signal.getVolume());
            tickerData.put("avgVolume", signal.getAverageVolume());
            tickerData.put("volumeRatio", signal.getVolumeRatio());
        }

        if (signal.getIsConfluence() != null && signal.getIsConfluence()) {
            tickerData.put("isConfluence", true);
            tickerData.put("confluenceCount", signal.getConfluenceCount());
            tickerData.put("confluentPatterns", signal.getConfluentPatterns());
        }

        alert.put("tickerData", tickerData);
        alert.put("_class", "hashita.data.entities.DailyAlertEnhanced");

        return alert;
    }

    /**
     * Get all symbols that have data for a given date
     */
    private List<String> getSymbolsForDate(LocalDate date) {
        String dateStr = date.toString();
        List<StockData> data = stockDataRepository.findByDate(dateStr);

        return data.stream()
                .map(StockData::getStockInfo)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Generate summary statistics
     *
     * ✅ UPDATED: Added chart pattern count
     */
    private Map<String, Object> generateSummary(List<Map<String, Object>> alerts) {
        Map<String, Object> summary = new LinkedHashMap<>();

        if (alerts.isEmpty()) {
            return summary;
        }

        // Count by symbol
        Map<String, Long> bySymbol = alerts.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        a -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> ticker = (Map<String, Object>) a.get("tickerData");
                            return (String) ticker.get("symbol");
                        },
                        java.util.stream.Collectors.counting()
                ));

        // Count by pattern
        Map<String, Long> byPattern = alerts.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        a -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> ticker = (Map<String, Object>) a.get("tickerData");
                            return (String) ticker.get("pattern");
                        },
                        java.util.stream.Collectors.counting()
                ));

        // Count by urgency
        Map<String, Long> byUrgency = alerts.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        a -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> ticker = (Map<String, Object>) a.get("tickerData");
                            return (String) ticker.get("urgency");
                        },
                        java.util.stream.Collectors.counting()
                ));

        // Count chart vs candlestick patterns
        long chartPatterns = alerts.stream()
                .filter(a -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ticker = (Map<String, Object>) a.get("tickerData");
                    return (Boolean) ticker.get("isChartPattern");
                })
                .count();

        long candlestickPatterns = alerts.size() - chartPatterns;

        // Average quality
        double avgQuality = alerts.stream()
                .mapToDouble(a -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ticker = (Map<String, Object>) a.get("tickerData");
                    Object quality = ticker.get("signalQuality");
                    return quality instanceof Number ? ((Number) quality).doubleValue() : 0.0;
                })
                .average()
                .orElse(0.0);

        // Quality distribution
        Map<String, Long> qualityDist = new java.util.TreeMap<>();
        qualityDist.put("90-100%", alerts.stream()
                .filter(a -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ticker = (Map<String, Object>) a.get("tickerData");
                    Object quality = ticker.get("signalQuality");
                    double q = quality instanceof Number ? ((Number) quality).doubleValue() : 0.0;
                    return q >= 90;
                }).count());
        qualityDist.put("80-89%", alerts.stream()
                .filter(a -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ticker = (Map<String, Object>) a.get("tickerData");
                    Object quality = ticker.get("signalQuality");
                    double q = quality instanceof Number ? ((Number) quality).doubleValue() : 0.0;
                    return q >= 80 && q < 90;
                }).count());
        qualityDist.put("75-79%", alerts.stream()
                .filter(a -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ticker = (Map<String, Object>) a.get("tickerData");
                    Object quality = ticker.get("signalQuality");
                    double q = quality instanceof Number ? ((Number) quality).doubleValue() : 0.0;
                    return q >= 75 && q < 80;
                }).count());

        // Top symbols
        List<Map<String, Object>> topSymbols = bySymbol.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("symbol", e.getKey());
                    m.put("alerts", e.getValue());
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());

        // ✅ NEW: Time range in Israeli time
        String firstTime = (String) alerts.get(0).get("israeliTime");
        String lastTime = (String) alerts.get(alerts.size() - 1).get("israeliTime");
        String firstDateTime = (String) alerts.get(0).get("israeliDateTime");
        String lastDateTime = (String) alerts.get(alerts.size() - 1).get("israeliDateTime");

        summary.put("bySymbol", bySymbol);
        summary.put("byPattern", byPattern);
        summary.put("byUrgency", byUrgency);
        summary.put("chartPatterns", chartPatterns);
        summary.put("candlestickPatterns", candlestickPatterns);
        summary.put("averageQuality", String.format("%.2f", avgQuality));
        summary.put("qualityDistribution", qualityDist);
        summary.put("topSymbols", topSymbols);

        // ✅ NEW: Israeli time range
        summary.put("timeRangeIsraeli", Map.of(
                "first", firstTime,
                "last", lastTime,
                "firstDateTime", firstDateTime,
                "lastDateTime", lastDateTime
        ));

        return summary;
    }

    private String generateObjectId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /**
     * Get alert for specific symbol and time
     *
     * ✅ NEW: Added patternType parameter
     */
    @GetMapping("/alerts/symbol")
    public ResponseEntity<?> getAlertForSymbol(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam(required = false) String time,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality,
            @RequestParam(defaultValue = "ALL") PatternTypeFilter patternType,
            @RequestParam(defaultValue = "false") boolean strictMarketHours) {

        try {
            List<Map<String, Object>> alerts = generateAlertsForSymbol(
                    symbol, date, interval, minQuality, 20, "LONG", patternType,
                    strictMarketHours);

            if (time != null && !time.isEmpty()) {
                alerts = alerts.stream()
                        .filter(a -> {
                            String candleTime = (String) ((Map) a.get("tickerData")).get("candleTime");
                            return candleTime.equals(time);
                        })
                        .collect(Collectors.toList());
            }

            return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "time", time != null ? time : "all",
                    "patternType", patternType.toString(),
                    "count", alerts.size(),
                    "alerts", alerts
            ));

        } catch (Exception e) {
            log.error("Error getting alert for symbol", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Compare Twitter alert vs System alert
     */
    @GetMapping("/compare")
    public ResponseEntity<?> compareAlerts(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam String time,
            @RequestParam double twitterPrice,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "30") int minQuality,
            @RequestParam(defaultValue = "ALL") PatternTypeFilter patternType,
            @RequestParam(defaultValue = "false") boolean strictMarketHours) {

        try {
            log.info("🔍 Comparing Twitter alert vs System for {} at {} on {}",
                    symbol, time, date);

            List<Map<String, Object>> systemAlerts = generateAlertsForSymbol(
                    symbol, date, interval, minQuality, 50, "LONG", patternType,
                    strictMarketHours);

            Map<String, Object> closestAlert = null;
            int minTimeDiff = Integer.MAX_VALUE;

            for (Map<String, Object> alert : systemAlerts) {
                String candleTime = (String) ((Map) alert.get("tickerData")).get("candleTime");
                int timeDiff = Math.abs(timeToMinutes(candleTime) - timeToMinutes(time));

                if (timeDiff < minTimeDiff) {
                    minTimeDiff = timeDiff;
                    closestAlert = alert;
                }
            }

            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("symbol", symbol);
            comparison.put("date", date);
            comparison.put("patternType", patternType.toString());

            comparison.put("twitterAlert", Map.of(
                    "time", time,
                    "entryPrice", twitterPrice,
                    "source", "Twitter/Manual"
            ));

            if (closestAlert != null) {
                Map tickerData = (Map) closestAlert.get("tickerData");

                comparison.put("systemAlert", Map.of(
                        "time", tickerData.get("candleTime"),
                        "entryPrice", tickerData.get("entryPrice"),
                        "pattern", tickerData.get("pattern"),
                        "isChartPattern", tickerData.get("isChartPattern"),
                        "quality", tickerData.get("signalQuality"),
                        "urgency", tickerData.get("urgency"),
                        "stopLoss", tickerData.get("stopLoss"),
                        "target", tickerData.get("target"),
                        "reason", tickerData.get("reason")
                ));

                comparison.put("timeDifference", minTimeDiff + " minutes");

                double priceDiff = Math.abs((Double) tickerData.get("entryPrice") - twitterPrice);
                double priceDiffPct = (priceDiff / twitterPrice) * 100;

                comparison.put("priceDifference", Map.of(
                        "absolute", String.format("$%.2f", priceDiff),
                        "percent", String.format("%.2f%%", priceDiffPct)
                ));

                boolean wouldCatch = minTimeDiff <= 15;
                comparison.put("wouldCatchThis", wouldCatch);

                if (wouldCatch) {
                    comparison.put("verdict", "✅ System would have caught this!");
                } else {
                    comparison.put("verdict", "⚠️ System might have missed this (time diff: " + minTimeDiff + " min)");
                }

            } else {
                comparison.put("systemAlert", null);
                comparison.put("verdict", "❌ System had NO signals for this symbol on this date");
                comparison.put("allSystemAlerts", systemAlerts.size());
            }

            comparison.put("allSystemAlertsForSymbol", systemAlerts);

            return ResponseEntity.ok(comparison);

        } catch (Exception e) {
            log.error("Error comparing alerts", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private int timeToMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    /**
     * Persist simulated alerts to MongoDB - BULLISH ONLY
     *
     * ✅ NEW: Added patternType parameter
     */
    @PostMapping("/alerts/persist")
    @Transactional
    public ResponseEntity<?> persistAlerts(
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality,
            @RequestParam(defaultValue = "10") int maxAlertsPerSymbol,
            @RequestParam(defaultValue = "ALL") PatternTypeFilter patternType,
            @RequestParam(defaultValue = "false") boolean strictMarketHours) {

        long startTime = System.currentTimeMillis();

        try {
            log.info("🗄️ Persisting {} alerts for date: {}", patternType, date);

            LocalDate targetDate = LocalDate.parse(date);
            List<String> symbols = getSymbolsForDate(targetDate);

            List<Map<String, Object>> allAlertMaps = new ArrayList<>();
            for (String symbol : symbols) {
                try {
                    List<Map<String, Object>> symbolAlerts = generateAlertsForSymbol(
                            symbol, date, interval, minQuality, maxAlertsPerSymbol, "LONG", patternType,
                            strictMarketHours);
                    allAlertMaps.addAll(symbolAlerts);
                } catch (Exception e) {
                    log.warn("  ⚠️ {}: Error - {}", symbol, e.getMessage());
                }
            }

            log.info("Generated {} alerts to persist", allAlertMaps.size());

            long deletedCount = dailyAlertEnhancedRepository.countByDate(date);
            if (deletedCount > 0) {
                dailyAlertEnhancedRepository.deleteByDate(date);
                log.info("Deleted {} existing alerts for {}", deletedCount, date);
            }

            List<DailyAlertEnhanced> alertEntities = allAlertMaps.stream()
                    .map(this::mapToEntity)
                    .collect(Collectors.toList());

            int savedCount = 0;
            int skippedCount = 0;

            for (DailyAlertEnhanced alert : alertEntities) {
                try {
                    dailyAlertEnhancedRepository.save(alert);
                    savedCount++;
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    skippedCount++;
                    log.debug("Skipped duplicate alert: {}", alert.getTickerData());
                } catch (Exception e) {
                    log.warn("Error saving alert: {}", e.getMessage());
                    skippedCount++;
                }
            }

            log.info("✅ Saved {} new alerts for {} (skipped {} duplicates)",
                    savedCount, date, skippedCount);

            long executionTimeMs = System.currentTimeMillis() - startTime;
            long executionTimeSec = executionTimeMs / 1000;

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "date", date,
                    "patternType", patternType.toString(),
                    "savedCount", savedCount,
                    "skippedCount", skippedCount,
                    "executionTimeMs", executionTimeMs,
                    "executionTimeSec", executionTimeSec,
                    "message", String.format("Successfully persisted %d %s alerts for %s in %ds",
                            savedCount, patternType, date, executionTimeSec)
            ));

        } catch (Exception e) {
            log.error("Error persisting alerts", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "success", false,
                            "error", e.getMessage()
                    ));
        }
    }

    /**
     * Persist simulated alerts for a date range to MongoDB
     *
     * ✅ NEW: Added patternType parameter
     */
    @PostMapping("/alerts/persist/range")
    @Transactional
    public ResponseEntity<?> persistAlertsDateRange(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality,
            @RequestParam(defaultValue = "10") int maxAlertsPerSymbol,
            @RequestParam(defaultValue = "ALL") PatternTypeFilter patternType,
            @RequestParam(defaultValue = "false") boolean strictMarketHours) {

        long startTime = System.currentTimeMillis();

        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "success", false,
                                "error", "startDate must be before or equal to endDate"
                        ));
            }

            log.info("🗄️ Persisting {} alerts for date range: {} to {}", patternType, startDate, endDate);

            List<Map<String, Object>> dateResults = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger totalSaved = new AtomicInteger(0);
            AtomicInteger totalSkipped = new AtomicInteger(0);
            AtomicInteger datesProcessed = new AtomicInteger(0);

            List<LocalDate> dates = new ArrayList<>();
            LocalDate currentDate = start;
            while (!currentDate.isAfter(end)) {
                dates.add(currentDate);
                currentDate = currentDate.plusDays(1);
            }

            log.info("Processing {} dates in parallel...", dates.size());

            dates.parallelStream().forEach(date -> {
                String dateStr = date.toString();
                log.info("📅 Processing date: {}", dateStr);

                try {
                    List<String> symbols = getSymbolsForDate(date);

                    if (symbols.isEmpty()) {
                        log.warn("  ⚠️ No symbols found for {}", dateStr);
                        dateResults.add(Map.of(
                                "date", dateStr,
                                "savedCount", 0,
                                "skippedCount", 0,
                                "error", "No symbols found"
                        ));
                        return;
                    }

                    List<Map<String, Object>> allAlertMaps = new ArrayList<>();
                    for (String symbol : symbols) {
                        try {
                            List<Map<String, Object>> symbolAlerts = generateAlertsForSymbol(
                                    symbol, dateStr, interval, minQuality, maxAlertsPerSymbol, "LONG", patternType,
                                    strictMarketHours);
                            allAlertMaps.addAll(symbolAlerts);
                        } catch (Exception e) {
                            log.warn("  ⚠️ {}: Error - {}", symbol, e.getMessage());
                        }
                    }

                    long deletedCount = dailyAlertEnhancedRepository.countByDate(dateStr);
                    if (deletedCount > 0) {
                        dailyAlertEnhancedRepository.deleteByDate(dateStr);
                        log.info("  Deleted {} existing alerts for {}", deletedCount, dateStr);
                    }

                    List<DailyAlertEnhanced> alertEntities = allAlertMaps.stream()
                            .map(this::mapToEntity)
                            .collect(Collectors.toList());

                    int savedCount = 0;
                    int skippedCount = 0;

                    for (DailyAlertEnhanced alert : alertEntities) {
                        try {
                            dailyAlertEnhancedRepository.save(alert);
                            savedCount++;
                        } catch (org.springframework.dao.DuplicateKeyException e) {
                            skippedCount++;
                            log.debug("Skipped duplicate alert: {}", alert.getTickerData());
                        } catch (Exception e) {
                            log.warn("Error saving alert: {}", e.getMessage());
                            skippedCount++;
                        }
                    }

                    log.info("  ✅ Saved {} alerts for {} (skipped {})", savedCount, dateStr, skippedCount);

                    dateResults.add(Map.of(
                            "date", dateStr,
                            "savedCount", savedCount,
                            "skippedCount", skippedCount
                    ));

                    totalSaved.addAndGet(savedCount);
                    totalSkipped.addAndGet(skippedCount);
                    datesProcessed.incrementAndGet();

                } catch (Exception e) {
                    log.error("  ❌ Error processing date {}: {}", dateStr, e.getMessage());
                    dateResults.add(Map.of(
                            "date", dateStr,
                            "savedCount", 0,
                            "skippedCount", 0,
                            "error", e.getMessage()
                    ));
                }
            });

            long executionTimeMs = System.currentTimeMillis() - startTime;
            long executionTimeSec = executionTimeMs / 1000;

            log.info("✅ Completed date range: {} dates, {} saved, {} skipped in {}s",
                    datesProcessed.get(), totalSaved.get(), totalSkipped.get(), executionTimeSec);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("startDate", startDate);
            response.put("endDate", endDate);
            response.put("patternType", patternType.toString());  // ✅ ADD THIS if you added patternType
            response.put("datesProcessed", datesProcessed.get());
            response.put("totalSaved", totalSaved.get());
            response.put("totalSkipped", totalSkipped.get());
            response.put("executionTimeMs", executionTimeMs);
            response.put("executionTimeSec", executionTimeSec);
            response.put("results", dateResults);
            response.put("message", String.format("Successfully processed %d dates: %d saved, %d skipped in %ds",
                    datesProcessed.get(), totalSaved.get(), totalSkipped.get(), executionTimeSec));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error persisting date range", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "success", false,
                            "error", e.getMessage()
                    ));
        }
    }

    /**
     * Convert Map to DailyAlertEnhanced entity
     */
    private DailyAlertEnhanced mapToEntity(Map<String, Object> alertMap) {
        DailyAlertEnhanced alert = new DailyAlertEnhanced();

        alert.setId((String) alertMap.get("_id"));
        alert.setDate((String) alertMap.get("date"));

        String timestampStr = (String) alertMap.get("timestamp");
        alert.setTimestamp(Instant.parse(timestampStr));

        alert.setSource((String) alertMap.get("source"));

        @SuppressWarnings("unchecked")
        Map<String, Object> fullTickerData = (Map<String, Object>) alertMap.get("tickerData");

        Object qualityObj = fullTickerData.get("signalQuality");
        if (qualityObj instanceof Number) {
            alert.setSignalQuality(((Number) qualityObj).doubleValue());
        }

        Map<String, Object> simplifiedTickerData = new LinkedHashMap<>();
        simplifiedTickerData.put("symbol", fullTickerData.get("symbol"));
        simplifiedTickerData.put("entryPrice", fullTickerData.get("entryPrice"));
        simplifiedTickerData.put("candleTime", 5.0);
        simplifiedTickerData.put("isGlobal", fullTickerData.get("isGlobal"));
        simplifiedTickerData.put("pattern", fullTickerData.get("pattern"));
        simplifiedTickerData.put("timestamp", timestampStr);

        try {
            String tickerDataJson = objectMapper.writeValueAsString(simplifiedTickerData);
            alert.setTickerData(tickerDataJson);
        } catch (Exception e) {
            log.error("Error converting tickerData to JSON: {}", e.getMessage());
            throw new RuntimeException("Failed to convert tickerData", e);
        }

        return alert;
    }

    /**
     * Build alert map with Israeli time fields
     *
     * ✅ NEW: Adds israeliTime, israeliDateTime, and israeliTimestamp
     */
    private Map<String, Object> buildAlert(
            EntrySignal signal,
            String date,
            String source) {

        Map<String, Object> alert = new LinkedHashMap<>();

        // Generate unique ID
        alert.put("_id", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24));

        // Date and timestamps
        alert.put("date", date);
        alert.put("timestamp", signal.getTimestamp().toString());

        // ✅ NEW: Israeli time fields
        alert.put("israeliTime", hashita.util.TimeUtils.toIsraeliTime(signal.getTimestamp()));
        alert.put("israeliDateTime", hashita.util.TimeUtils.toIsraeliDateTime(signal.getTimestamp()));
        alert.put("israeliTimestamp", hashita.util.TimeUtils.toIsraeliISO(signal.getTimestamp()));

        alert.put("source", source);

        // Ticker data
        Map<String, Object> tickerData = new LinkedHashMap<>();
        tickerData.put("symbol", signal.getSymbol());
        tickerData.put("entryPrice", signal.getEntryPrice());

        // ✅ ENHANCED: candleTime now shows Israeli time
        tickerData.put("candleTime", hashita.util.TimeUtils.toIsraeliTime(signal.getTimestamp()));
        tickerData.put("candleTimeUTC", signal.getTimestamp().atZone(java.time.ZoneId.of("UTC"))
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));

        tickerData.put("stopLoss", signal.getStopLoss());
        tickerData.put("target", signal.getTarget());
        tickerData.put("pattern", signal.getPattern().name());
        tickerData.put("isChartPattern", signal.getPattern().isChartPattern());
        tickerData.put("direction", signal.getDirection().name());
        tickerData.put("riskRewardRatio", signal.getRiskRewardRatio());
        tickerData.put("confidence", signal.getConfidence());
        tickerData.put("signalQuality", signal.getSignalQuality());
        tickerData.put("urgency", signal.getUrgency());
        tickerData.put("reason", signal.getReason());
        tickerData.put("hasVolumeConfirmation", signal.isHasVolumeConfirmation());
        tickerData.put("riskAmount", Math.abs(signal.getEntryPrice() - signal.getStopLoss()));
        tickerData.put("rewardAmount", Math.abs(signal.getTarget() - signal.getEntryPrice()));
        tickerData.put("riskPercent", signal.getRiskPercent());
        tickerData.put("rewardPercent", signal.getRewardPercent());
        tickerData.put("isGlobal", true);

        alert.put("tickerData", tickerData);
        alert.put("_class", "hashita.data.entities.DailyAlertEnhanced");

        return alert;
    }

}