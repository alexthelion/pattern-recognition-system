package hashita.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import hashita.data.Candle;
import hashita.data.PatternRecognitionResult;
import hashita.data.entities.DailyAlertEnhanced;
import hashita.data.entities.StockData;
import hashita.repository.DailyAlertEnhancedRepository;
import hashita.repository.StockDataRepository;
import hashita.service.*;
import hashita.service.EntrySignalService.EntrySignal;
import hashita.repository.TickerVolumeRepository;
import hashita.data.entities.TickerVolume;
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
import java.util.stream.Stream;

/**
 * Simulation endpoint: Generate bullish alerts from stock data
 * Like getting alerts from your service instead of Twitter
 */
@RestController
@RequestMapping("/api/simulate")
@Slf4j
public class AlertSimulationController {

    @Autowired
    private StockDataRepository stockDataRepository;

    @Autowired
    private PatternAnalysisService patternAnalysisService;

    @Autowired
    private EntrySignalService entrySignalService;

    @Autowired
    private hashita.service.EnhancedEntrySignalService enhancedEntrySignalService;

    @Autowired
    private DailyAlertEnhancedRepository dailyAlertEnhancedRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IBKRCandleService ibkrCandleService;

    /**
     * Simulate bullish alerts for a given date
     * Returns alerts in MongoDB DailyAlertEnhanced format
     */
    @GetMapping("/alerts/bullish")
    public ResponseEntity<?> simulateBullishAlerts(
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality,
            @RequestParam(defaultValue = "10") int maxAlertsPerSymbol) {

        return simulateAlertsByDirection(date, interval, minQuality, maxAlertsPerSymbol, "LONG");
    }

    /**
     * Simulate bearish alerts for a given date
     * Returns alerts in MongoDB DailyAlertEnhanced format
     */
    @GetMapping("/alerts/bearish")
    public ResponseEntity<?> simulateBearishAlerts(
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality,
            @RequestParam(defaultValue = "10") int maxAlertsPerSymbol) {

        return simulateAlertsByDirection(date, interval, minQuality, maxAlertsPerSymbol, "SHORT");
    }

    /**
     * Simulate all alerts (both bullish and bearish) for a given date
     * Returns alerts in MongoDB DailyAlertEnhanced format
     */
    @GetMapping("/alerts/all")
    public ResponseEntity<?> simulateAllAlerts(
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality,
            @RequestParam(defaultValue = "10") int maxAlertsPerSymbol) {

        return simulateAlertsByDirection(date, interval, minQuality, maxAlertsPerSymbol, "ALL");
    }

    /**
     * Simulate alerts for a given direction
     */
    private ResponseEntity<?> simulateAlertsByDirection(
            String date, int interval, int minQuality, int maxAlertsPerSymbol, String direction) {

        try {
            LocalDate targetDate = LocalDate.parse(date);
            log.info("🎬 Simulating {} alerts for date: {}", direction, targetDate);

            // Get all unique symbols that have data for this date
            List<String> symbols = getSymbolsForDate(targetDate);
            log.info("Found {} symbols with data on {}", symbols.size(), targetDate);

            long startTime = System.currentTimeMillis();

            // ✅ PARALLEL PROCESSING - Process all symbols at once!
            List<Map<String, Object>> allAlerts = symbols.parallelStream()
                    .flatMap(symbol -> {
                        try {
                            log.debug("🔍 Processing symbol: {}", symbol);

                            List<Map<String, Object>> symbolAlerts = generateAlertsForSymbol(
                                    symbol, date, interval, minQuality, maxAlertsPerSymbol, direction
                            );

                            if (!symbolAlerts.isEmpty()) {
                                log.info("  ✅ {}: {} signals", symbol, symbolAlerts.size());
                            }

                            return symbolAlerts.stream();

                        } catch (Exception e) {
                            log.error("  ❌ {}: Error - {}", symbol, e.getMessage());
                            return Stream.empty();
                        }
                    })
                    .collect(Collectors.toList());

            long duration = System.currentTimeMillis() - startTime;

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

                // If same time, sort by quality descending
                Double qualityA = (Double) tickerDataA.get("signalQuality");
                Double qualityB = (Double) tickerDataB.get("signalQuality");
                return Double.compare(qualityB, qualityA);
            });

            log.info("🎉 COMPLETE: {} {} signals from {} symbols in {}ms",
                    allAlerts.size(), direction, symbols.size(), duration);

            return ResponseEntity.ok(Map.of(
                    "date", date,
                    "symbolsProcessed", symbols.size(),
                    "totalAlerts", allAlerts.size(),
                    "minQuality", minQuality,
                    "maxAlertsPerSymbol", maxAlertsPerSymbol,
                    "processingTimeMs", duration,  // ✅ NEW
                    "alerts", allAlerts,
                    "direction", direction
            ));

        } catch (Exception e) {
            log.error("Error simulating alerts for {}: {}", direction, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Generate alerts for a single symbol
     */
    private List<Map<String, Object>> generateAlertsForSymbol(
            String symbol, String date, int interval, int minQuality, int maxAlerts, String direction) {

        // Get patterns for this symbol
        List<PatternRecognitionResult> patterns =
                patternAnalysisService.analyzeStockForDate(symbol, date, interval);

        log.debug("    Step 1: {} patterns found", patterns.size());

        if (patterns.isEmpty()) {
            return Collections.emptyList();
        }

        // Get ALL candles for the day (will filter per pattern)
        List<Candle> allCandles = ibkrCandleService.getCandlesFromCacheOnly(symbol, date, interval);

        if (allCandles.isEmpty()) {
            log.warn("    No candles found for {}, skipping enhanced filters", symbol);
            return Collections.emptyList();
        }

        // Generate signals with ENHANCED filters (same as /entry-enhanced endpoint)
        List<EntrySignal> signals = patterns.stream()
                .map(pattern -> {
                    // Get base signal
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
                .filter(s -> {
                    // Filter by direction
                    if ("ALL".equals(direction)) {
                        return true;  // Include both LONG and SHORT
                    }
                    boolean matches = direction.equals(s.getDirection().name());
                    log.trace("    Pattern {}: direction={}, matches={}",
                            s.getPattern(), s.getDirection().name(), matches);
                    return matches;
                })
                .filter(s -> s.getSignalQuality() >= minQuality)
                .sorted(Comparator.comparing(EntrySignal::getTimestamp))
                .limit(maxAlerts)
                .collect(Collectors.toList());

        log.debug("    Step 2: {} {} signals after enhanced filters (minQuality: {})",
                signals.size(), direction, minQuality);

        // Convert to MongoDB alert format
        return signals.stream()
                .map(signal -> createAlertDocument(symbol, signal, date))
                .collect(Collectors.toList());
    }

    /**
     * Create alert document in MongoDB DailyAlertEnhanced format
     */
    private Map<String, Object> createAlertDocument(String symbol, EntrySignal signal, String date) {
        Map<String, Object> alert = new LinkedHashMap<>();

        // Generate ObjectId-like string
        alert.put("_id", generateObjectId());

        // Date
        alert.put("date", date);

        // Timestamp (ISO format)
        alert.put("timestamp", signal.getTimestamp().toString());

        // Source
        alert.put("source", "Simulated Alert");

        // Ticker data (full data for API response)
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

        alert.put("tickerData", tickerData);

        // Class name
        alert.put("_class", "hashita.data.entities.DailyAlertEnhanced");

        return alert;
    }

    /**
     * Get all symbols that have data for a given date
     */
    private List<String> getSymbolsForDate(LocalDate date) {
        String dateStr = date.toString(); // Convert to yyyy-MM-dd format

        List<StockData> data = stockDataRepository.findByDate(dateStr);

        return data.stream()
                .map(StockData::getStockInfo)  // FIX: Use getStockInfo instead of getSymbol
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Generate summary statistics
     */
    private Map<String, Object> generateSummary(List<Map<String, Object>> alerts) {
        Map<String, Object> summary = new LinkedHashMap<>();

        // Group by symbol
        Map<String, Long> bySymbol = alerts.stream()
                .collect(Collectors.groupingBy(
                        a -> (String) ((Map) a.get("tickerData")).get("symbol"),
                        Collectors.counting()
                ));

        // Group by pattern
        Map<String, Long> byPattern = alerts.stream()
                .collect(Collectors.groupingBy(
                        a -> String.valueOf(((Map) a.get("tickerData")).get("pattern")),  // FIX: Use String.valueOf for enum
                        Collectors.counting()
                ));

        // Group by urgency
        Map<String, Long> byUrgency = alerts.stream()
                .collect(Collectors.groupingBy(
                        a -> String.valueOf(((Map) a.get("tickerData")).get("urgency")),  // FIX: Use String.valueOf for enum
                        Collectors.counting()
                ));

        // Average quality
        double avgQuality = alerts.stream()
                .mapToDouble(a -> (Double) ((Map) a.get("tickerData")).get("signalQuality"))
                .average()
                .orElse(0);

        // Quality ranges
        long quality90Plus = alerts.stream()
                .filter(a -> (Double) ((Map) a.get("tickerData")).get("signalQuality") >= 90)
                .count();

        long quality80to90 = alerts.stream()
                .filter(a -> {
                    double q = (Double) ((Map) a.get("tickerData")).get("signalQuality");
                    return q >= 80 && q < 90;
                })
                .count();

        long quality75to80 = alerts.stream()
                .filter(a -> {
                    double q = (Double) ((Map) a.get("tickerData")).get("signalQuality");
                    return q >= 75 && q < 80;
                })
                .count();

        summary.put("bySymbol", bySymbol);
        summary.put("byPattern", byPattern);
        summary.put("byUrgency", byUrgency);
        summary.put("averageQuality", String.format("%.2f", avgQuality));
        summary.put("qualityDistribution", Map.of(
                "90-100%", quality90Plus,
                "80-89%", quality80to90,
                "75-79%", quality75to80
        ));

        // Top symbols
        List<Map.Entry<String, Long>> topSymbols = bySymbol.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .collect(Collectors.toList());

        summary.put("topSymbols", topSymbols.stream()
                .map(e -> Map.of("symbol", e.getKey(), "alerts", e.getValue()))
                .collect(Collectors.toList()));

        return summary;
    }

    /**
     * Generate MongoDB ObjectId-like string
     */
    private String generateObjectId() {
        // Simple hex string generation (not true ObjectId but looks similar)
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /**
     * Get alert for specific symbol and time
     */
    @GetMapping("/alerts/symbol")
    public ResponseEntity<?> getAlertForSymbol(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam(required = false) String time,  // HH:mm format
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality) {

        try {
            List<Map<String, Object>> alerts = generateAlertsForSymbol(
                    symbol, date, interval, minQuality, 20, "LONG"
            );

            // Filter by time if specified
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
            @RequestParam String time,  // From Twitter alert
            @RequestParam double twitterPrice,  // Entry price from Twitter
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "30") int minQuality) {

        try {
            log.info("🔍 Comparing Twitter alert vs System for {} at {} on {}",
                    symbol, time, date);

            // Get our system's alerts around that time
            List<Map<String, Object>> systemAlerts = generateAlertsForSymbol(
                    symbol, date, interval, minQuality, 50, "LONG"
            );

            // Find closest alert by time
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

            // Twitter alert info
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

                // Would we have caught this?
                boolean wouldCatch = minTimeDiff <= 15;  // Within 15 minutes
                comparison.put("wouldCatchThis", wouldCatch);

                if (wouldCatch) {
                    comparison.put("verdict", "✅ System would have caught this!");
                } else {
                    comparison.put("verdict", "⚠️ System might have missed this (time diff: " + minTimeDiff + " min)");
                }

            } else {
                comparison.put("systemAlert", null);
                comparison.put("verdict", "❌ System had NO bullish signals for this symbol on this date");
                comparison.put("allSystemAlerts", systemAlerts.size());
            }

            // Show all system alerts for context
            comparison.put("allSystemAlertsForSymbol", systemAlerts);

            return ResponseEntity.ok(comparison);

        } catch (Exception e) {
            log.error("Error comparing alerts", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Convert time string to minutes
     */
    private int timeToMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    /**
     * Persist simulated alerts to MongoDB - BULLISH ONLY
     *
     * Generates bullish alerts from stock data and saves to daily_alerts_enhanced collection.
     * Duplicates are skipped (based on unique index on tickerData).
     *
     * @param date Date in yyyy-MM-dd format (e.g., "2025-10-06")
     * @param interval Candle interval in minutes (default: 5)
     * @param minQuality Minimum signal quality 0-100 (default: 75)
     * @param maxAlertsPerSymbol Max alerts per symbol (default: 10)
     * @return Save results with counts
     *
     * Example request:
     * POST /api/simulate/alerts/persist?date=2025-10-06&minQuality=90
     *
     * Example response:
     * {
     *   "success": true,
     *   "date": "2025-10-06",
     *   "savedCount": 25,
     *   "skippedCount": 3,
     *   "message": "Successfully persisted 25 alerts for 2025-10-06 (skipped 3 duplicates)"
     * }
     */
    @PostMapping("/alerts/persist")
    @Transactional
    public ResponseEntity<?> persistAlerts(
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality,
            @RequestParam(defaultValue = "10") int maxAlertsPerSymbol) {

        long startTime = System.currentTimeMillis();

        try {
            log.info("🗄️ Persisting alerts for date: {}", date);

            // Step 1: Generate alerts (same as /alerts/bullish)
            LocalDate targetDate = LocalDate.parse(date);
            List<String> symbols = getSymbolsForDate(targetDate);

            List<Map<String, Object>> allAlertMaps = new ArrayList<>();
            for (String symbol : symbols) {
                try {
                    List<Map<String, Object>> symbolAlerts = generateAlertsForSymbol(
                            symbol, date, interval, minQuality, maxAlertsPerSymbol, "LONG"
                    );
                    allAlertMaps.addAll(symbolAlerts);
                } catch (Exception e) {
                    log.warn("  ⚠️ {}: Error - {}", symbol, e.getMessage());
                }
            }

            log.info("Generated {} alerts to persist", allAlertMaps.size());

            // Delete existing alerts for this date
            long deletedCount = dailyAlertEnhancedRepository.countByDate(date);
            if (deletedCount > 0) {
                dailyAlertEnhancedRepository.deleteByDate(date);
                log.info("Deleted {} existing alerts for {}", deletedCount, date);
            }

            // Convert Maps to DailyAlertEnhanced entities
            List<DailyAlertEnhanced> alertEntities = allAlertMaps.stream()
                    .map(this::mapToEntity)
                    .collect(Collectors.toList());

            // Save alerts one by one, ignoring duplicates
            int savedCount = 0;
            int skippedCount = 0;

            for (DailyAlertEnhanced alert : alertEntities) {
                try {
                    dailyAlertEnhancedRepository.save(alert);
                    savedCount++;
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    // Ignore duplicate key errors (unique index on tickerData)
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

            log.info("⏱️ Execution time: {}s ({}ms)", executionTimeSec, executionTimeMs);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "date", date,
                    "savedCount", savedCount,
                    "skippedCount", skippedCount,
                    "executionTimeMs", executionTimeMs,
                    "executionTimeSec", executionTimeSec,
                    "message", String.format("Successfully persisted %d alerts for %s (skipped %d duplicates) in %ds",
                            savedCount, date, skippedCount, executionTimeSec)
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
     * Convert Map to DailyAlertEnhanced entity
     * Creates simplified tickerData for MongoDB (4-6 fields only)
     */
    private DailyAlertEnhanced mapToEntity(Map<String, Object> alertMap) {
        DailyAlertEnhanced alert = new DailyAlertEnhanced();

        // Basic fields
        alert.setId((String) alertMap.get("_id"));
        alert.setDate((String) alertMap.get("date"));

        // Parse timestamp string to Instant
        String timestampStr = (String) alertMap.get("timestamp");
        alert.setTimestamp(Instant.parse(timestampStr));

        alert.setSource((String) alertMap.get("source"));

        // Get full tickerData from alert map
        @SuppressWarnings("unchecked")
        Map<String, Object> fullTickerData = (Map<String, Object>) alertMap.get("tickerData");

        // Set signalQuality as separate field
        Object qualityObj = fullTickerData.get("signalQuality");
        if (qualityObj instanceof Number) {
            alert.setSignalQuality(((Number) qualityObj).doubleValue());
        }

        // Create simplified tickerData for MongoDB (matching StockInfoPayload)
        Map<String, Object> simplifiedTickerData = new LinkedHashMap<>();
        simplifiedTickerData.put("symbol", fullTickerData.get("symbol"));
        simplifiedTickerData.put("entryPrice", fullTickerData.get("entryPrice"));
        simplifiedTickerData.put("candleTime", 5.0);  // Always 5.0 for 5-minute interval
        simplifiedTickerData.put("isGlobal", fullTickerData.get("isGlobal"));

        // Add pattern and timestamp to ensure uniqueness (for unique index)
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
     * Persist simulated alerts for a date range to MongoDB - BULLISH ONLY
     *
     * Generates bullish alerts from stock data for multiple dates and saves to daily_alerts_enhanced collection.
     * Duplicates are skipped (based on unique index on tickerData).
     *
     * @param startDate Start date in yyyy-MM-dd format (e.g., "2025-10-01")
     * @param endDate End date in yyyy-MM-dd format (e.g., "2025-10-31")
     * @param interval Candle interval in minutes (default: 5)
     * @param minQuality Minimum signal quality 0-100 (default: 75)
     * @param maxAlertsPerSymbol Max alerts per symbol per day (default: 10)
     * @return Save results with counts per date
     *
     * Example request:
     * POST /api/simulate/alerts/persist/range?startDate=2025-10-01&endDate=2025-10-31&minQuality=90
     *
     * Example response:
     * {
     *   "success": true,
     *   "startDate": "2025-10-01",
     *   "endDate": "2025-10-31",
     *   "datesProcessed": 31,
     *   "totalSaved": 487,
     *   "totalSkipped": 23,
     *   "results": [
     *     {
     *       "date": "2025-10-01",
     *       "savedCount": 15,
     *       "skippedCount": 1
     *     }
     *   ]
     * }
     */
    @PostMapping("/alerts/persist/range")
    @Transactional
    public ResponseEntity<?> persistAlertsDateRange(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "75") int minQuality,
            @RequestParam(defaultValue = "10") int maxAlertsPerSymbol) {

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

            log.info("🗄️ Persisting alerts for date range: {} to {}", startDate, endDate);

            List<Map<String, Object>> dateResults = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger totalSaved = new AtomicInteger(0);
            AtomicInteger totalSkipped = new AtomicInteger(0);
            AtomicInteger datesProcessed = new AtomicInteger(0);

            // Generate list of dates to process
            List<LocalDate> dates = new ArrayList<>();
            LocalDate currentDate = start;
            while (!currentDate.isAfter(end)) {
                dates.add(currentDate);
                currentDate = currentDate.plusDays(1);
            }

            log.info("Processing {} dates in parallel...", dates.size());

            // Process dates in parallel
            dates.parallelStream().forEach(date -> {
                String dateStr = date.toString();
                log.info("📅 Processing date: {}", dateStr);

                try {
                    // Get symbols for this date
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

                    // Generate alerts for this date
                    List<Map<String, Object>> allAlertMaps = new ArrayList<>();
                    for (String symbol : symbols) {
                        try {
                            List<Map<String, Object>> symbolAlerts = generateAlertsForSymbol(
                                    symbol, dateStr, interval, minQuality, maxAlertsPerSymbol, "LONG"
                            );
                            allAlertMaps.addAll(symbolAlerts);
                        } catch (Exception e) {
                            log.warn("  ⚠️ {}: Error - {}", symbol, e.getMessage());
                        }
                    }

                    // Delete existing alerts for this date
                    long deletedCount = dailyAlertEnhancedRepository.countByDate(dateStr);
                    if (deletedCount > 0) {
                        dailyAlertEnhancedRepository.deleteByDate(dateStr);
                        log.info("  Deleted {} existing alerts for {}", deletedCount, dateStr);
                    }

                    // Convert and save alerts
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

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "startDate", startDate,
                    "endDate", endDate,
                    "datesProcessed", datesProcessed.get(),
                    "totalSaved", totalSaved.get(),
                    "totalSkipped", totalSkipped.get(),
                    "executionTimeMs", executionTimeMs,
                    "executionTimeSec", executionTimeSec,
                    "results", dateResults,
                    "message", String.format("Successfully processed %d dates: %d saved, %d skipped in %ds",
                            datesProcessed.get(), totalSaved.get(), totalSkipped.get(), executionTimeSec)
            ));

        } catch (Exception e) {
            log.error("Error persisting date range", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "success", false,
                            "error", e.getMessage()
                    ));
        }
    }
}