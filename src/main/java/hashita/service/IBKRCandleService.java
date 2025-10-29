package hashita.service;

import hashita.data.Candle;
import hashita.data.entities.CandleData;
import hashita.data.entities.StockData;
import hashita.repository.CandleDataRepository;
import hashita.repository.StockDataRepository;
import hashita.service.ibkr.IBKRClient;
import com.ib.client.Bar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to fetch and cache candles from IBKR
 *
 * This replaces CandleBuilderService for production use
 * Benefits:
 * - No timezone conversion bugs
 * - Accurate OHLCV from IBKR
 * - Fast (cached in MongoDB)
 * - Can re-run pattern detection without re-fetching
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IBKRCandleService {

    private final CandleDataRepository candleDataRepository;
    private final StockDataRepository stockDataRepository;
    private final IBKRClient ibkrClient;
    private final MongoTemplate mongoTemplate;  // ✅ NEW: For upsert operations

    /**
     * Get candles for a symbol/date/interval
     * - First checks MongoDB cache (unless forceRefresh=true)
     * - If not cached, checks if we have recent history
     * - Only fetches from IBKR if needed
     *
     * @param symbol Stock symbol
     * @param date Date in yyyy-MM-dd format
     * @param intervalMinutes Interval (1, 5, 15, 30, 60)
     * @param forceRefresh If true, skip cache and fetch fresh from IBKR
     * @return List of candles (includes 5 days of history)
     */
    public List<Candle> getCandles(String symbol, String date, int intervalMinutes, boolean forceRefresh) {
        log.debug("Getting candles for {} on {} ({}min, forceRefresh={})",
                symbol, date, intervalMinutes, forceRefresh);

        // Check cache first (unless force refresh)
        if (!forceRefresh) {
            Optional<CandleData> cached = candleDataRepository
                    .findBySymbolAndDateAndIntervalMinutes(symbol, date, intervalMinutes);

            if (cached.isPresent()) {
                log.debug("✅ Using cached candles: {} candles", cached.get().getCandleCount());
                return cached.get().getCandles();
            }

            // Cache miss - but check if we have recent history that includes this date
            List<Candle> existingCandles = findCandlesFromRecentCache(symbol, date, intervalMinutes);
            if (!existingCandles.isEmpty()) {
                log.info("✅ Found {} candles from recent cache (no IBKR fetch needed)", existingCandles.size());
                // Cache under this date for future lookups using UPSERT
                upsertCandles(symbol, date, intervalMinutes, existingCandles);
                return existingCandles;
            }
        } else {
            log.info("🔄 Force refresh requested - skipping cache");
        }

        // No cache hit OR force refresh - fetch from IBKR
        // Note: fetchFromIBKR() already caches the data via cachePerDate()
        log.info("⬇️ Fetching candles from IBKR for {} on {}", symbol, date);
        List<Candle> candles = fetchFromIBKR(symbol, date, intervalMinutes);

        return candles;
    }

    /**
     * Convenience method - calls getCandles with forceRefresh=false
     */
    public List<Candle> getCandles(String symbol, String date, int intervalMinutes) {
        return getCandles(symbol, date, intervalMinutes, false);
    }

    /**
     * Get candles from cache ONLY - never fetch from IBKR
     * Used for simulations and backtesting where we only want cached data
     *
     * @param symbol Stock symbol
     * @param date Date in yyyy-MM-dd format
     * @param intervalMinutes Interval (1, 5, 15, 30, 60)
     * @return List of candles from cache, or empty list if not cached
     */
    public List<Candle> getCandlesFromCacheOnly(String symbol, String date, int intervalMinutes) {
        log.debug("Getting CACHED candles for {} on {} ({}min)", symbol, date, intervalMinutes);

        // Check direct cache hit
        Optional<CandleData> cached = candleDataRepository
                .findBySymbolAndDateAndIntervalMinutes(symbol, date, intervalMinutes);

        if (cached.isPresent()) {
            log.debug("✅ Cache hit: {} candles", cached.get().getCandleCount());
            return cached.get().getCandles();
        }

        // Check if we can find from recent cache
        List<Candle> existingCandles = findCandlesFromRecentCache(symbol, date, intervalMinutes);
        if (!existingCandles.isEmpty()) {
            log.debug("✅ Found {} candles from recent cache", existingCandles.size());
            // Cache under this date for future lookups
            upsertCandles(symbol, date, intervalMinutes, existingCandles);
            return existingCandles;
        }

        // No cache - return empty (DO NOT fetch from IBKR!)
        log.warn("⚠️ No cached data for {} on {} - returning empty", symbol, date);
        return new ArrayList<>();
    }

    /**
     * Try to find candles from recently cached data
     * If we fetched data for nearby dates, we might already have the data we need
     */
    private List<Candle> findCandlesFromRecentCache(String symbol, String targetDate, int intervalMinutes) {
        try {
            LocalDate target = LocalDate.parse(targetDate);

            // Check the next 5 dates (they would have included our target date in their 5-day fetch)
            for (int i = 1; i <= 5; i++) {
                String nextDate = target.plusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE);

                Optional<CandleData> cached = candleDataRepository
                        .findBySymbolAndDateAndIntervalMinutes(symbol, nextDate, intervalMinutes);

                if (cached.isPresent()) {
                    // This cache entry has 5 days of data, filter to get our target date's candles
                    List<Candle> allCandles = cached.get().getCandles();

                    List<Candle> targetCandles = allCandles.stream()
                            .filter(c -> {
                                LocalDate candleDate = c.getTimestamp()
                                        .atZone(ZoneId.of("UTC"))
                                        .toLocalDate();
                                // Get all candles from target date backwards (for context)
                                return !candleDate.isAfter(target) &&
                                        !candleDate.isBefore(target.minusDays(4));
                            })
                            .collect(Collectors.toList());

                    if (!targetCandles.isEmpty()) {
                        log.debug("Found {} candles for {} in cache entry for {}",
                                targetCandles.size(), targetDate, nextDate);
                        return targetCandles;
                    }
                }
            }

            return new ArrayList<>();

        } catch (Exception e) {
            log.debug("Error searching recent cache: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get candles for pattern analysis
     * Returns candles for the requested date PLUS historical context (4 prev days)
     *
     * This loads from individual date caches and combines them
     *
     * @param symbol Stock symbol
     * @param date Target date for analysis
     * @param intervalMinutes Interval
     * @return All candles from (date-4 days) to date
     */
    public List<Candle> getCandlesWithContext(String symbol, String date, int intervalMinutes) {
        try {
            LocalDate targetDate = LocalDate.parse(date);
            List<Candle> allCandles = new ArrayList<>();

            // Load candles for target date and previous 4 days
            for (int i = 4; i >= 0; i--) {
                LocalDate loadDate = targetDate.minusDays(i);
                String loadDateStr = loadDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

                // Get candles for this date (will fetch if not cached)
                List<Candle> dateCandles = getCandles(symbol, loadDateStr, intervalMinutes, false);
                allCandles.addAll(dateCandles);
            }

            // Sort by timestamp
            allCandles.sort(Comparator.comparing(Candle::getTimestamp));

            log.debug("Loaded {} candles with context for {} on {}",
                    allCandles.size(), symbol, date);

            return allCandles;

        } catch (Exception e) {
            log.error("Error loading candles with context: {}", e.getMessage(), e);
            // Fallback to just the target date
            return getCandles(symbol, date, intervalMinutes, false);
        }
    }

    /**
     * Fetch and cache candles for all symbols on a specific date
     * This is what you'll call to prepare data for daily alerts
     *
     * @param date Date in yyyy-MM-dd format
     * @param intervalMinutes Interval (1, 5, 15, 30, 60)
     * @param forceRefresh If true, re-fetch even if cached
     * @return Number of symbols processed
     */
    public int fetchCandlesForDate(String date, int intervalMinutes, boolean forceRefresh) {
        log.info("📅 Fetching candles for all symbols on {} ({}min interval, forceRefresh={})",
                date, intervalMinutes, forceRefresh);

        // Get all symbols that have data for this date (from stock_daily collection)
        List<StockData> stockDataList = stockDataRepository.findByDate(date);

        if (stockDataList.isEmpty()) {
            log.warn("No symbols found for date: {}", date);
            return 0;
        }

        List<String> symbols = stockDataList.stream()
                .map(StockData::getStockInfo)
                .distinct()
                .collect(Collectors.toList());

        log.info("Found {} symbols for {}: {}", symbols.size(), date, symbols);

        int successCount = 0;
        int skipCount = 0;

        for (String symbol : symbols) {
            try {
                // Check if already cached (unless force refresh)
                if (!forceRefresh) {
                    boolean exists = candleDataRepository.existsBySymbolAndDateAndIntervalMinutes(
                            symbol, date, intervalMinutes);

                    if (exists) {
                        log.debug("Skipping {} - already cached", symbol);
                        skipCount++;
                        continue;
                    }
                }

                // Fetch from IBKR
                log.info("Fetching {} ({}/{})", symbol, successCount + 1, symbols.size());
                List<Candle> candles = getCandles(symbol, date, intervalMinutes, forceRefresh);

                if (!candles.isEmpty()) {
                    successCount++;
                    log.info("✅ Cached {} candles for {}", candles.size(), symbol);
                } else {
                    log.warn("❌ No candles returned for {}", symbol);
                }

                // Rate limiting: Sleep to avoid hitting IBKR API limits
                // IBKR allows 60 requests per 10 minutes = 1 per 10 seconds to be safe
                Thread.sleep(10000);

            } catch (InterruptedException e) {
                log.error("Interrupted while processing {}", symbol);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error fetching candles for {}: {}", symbol, e.getMessage(), e);
            }
        }

        log.info("🎯 Fetch complete: {} fetched, {} skipped, {} total",
                successCount, skipCount, symbols.size());

        return successCount;
    }

    /**
     * Convenience method - calls fetchCandlesForDate with forceRefresh=false
     */
    public int fetchCandlesForDate(String date, int intervalMinutes) {
        return fetchCandlesForDate(date, intervalMinutes, false);
    }

    /**
     * Fetch candles for multiple dates (batch processing for historical data)
     *
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @param intervalMinutes Interval
     * @return Total number of symbol-dates processed
     */
    public int fetchCandlesForDateRange(String startDate, String endDate, int intervalMinutes) {
        log.info("📅 Fetching candles from {} to {} ({}min interval)", startDate, endDate, intervalMinutes);

        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        int totalProcessed = 0;
        LocalDate current = start;

        while (!current.isAfter(end)) {
            String dateStr = current.format(DateTimeFormatter.ISO_LOCAL_DATE);

            // Skip weekends
            if (current.getDayOfWeek().getValue() >= 6) {
                log.debug("Skipping weekend: {}", dateStr);
                current = current.plusDays(1);
                continue;
            }

            log.info("\n========== Processing {} ==========", dateStr);
            int processed = fetchCandlesForDate(dateStr, intervalMinutes);
            totalProcessed += processed;

            current = current.plusDays(1);

            // Extra sleep between dates to be extra safe with API limits
            if (!current.isAfter(end)) {
                try {
                    log.info("Waiting 60 seconds before next date...");
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("\n🎉 COMPLETE: Processed {} symbol-dates from {} to {}",
                totalProcessed, startDate, endDate);

        return totalProcessed;
    }

    /**
     * Fetch candles from IBKR API and cache per-date
     */
    private List<Candle> fetchFromIBKR(String symbol, String date, int intervalMinutes) {
        try {
            log.info("Fetching historical bars from IBKR: symbol={}, date={}, interval={}min",
                    symbol, date, intervalMinutes);

            // Ensure connected
            if (!ibkrClient.isConnected()) {
                ibkrClient.connect();
            }

            // Check how many days we actually need to fetch
            int daysToFetch = calculateDaysToFetch(symbol, date, intervalMinutes);

            // Format end date/time for IBKR
            // IBKR requires explicit timezone: "yyyyMMdd HH:mm:ss TZ"
            String endDateTime = date.replace("-", "") + " 23:59:59 UTC";

            // Duration: Fetch only what we need
            String durationStr = daysToFetch + " D";

            log.info("Fetching {} days of data for {} (to have 5 days total context)",
                    daysToFetch, symbol);

            // Bar size setting
            String barSizeSetting = intervalMinutes + (intervalMinutes == 1 ? " min" : " mins");

            // Request historical bars
            List<Bar> bars = ibkrClient.getHistoricalBars(
                    symbol,
                    endDateTime,
                    durationStr,
                    barSizeSetting
            );

            if (bars.isEmpty()) {
                log.warn("No bars returned from IBKR for {}", symbol);
                return new ArrayList<>();
            }

            // Convert IBKR bars to our Candle objects
            List<Candle> allCandles = bars.stream()
                    .map(bar -> {
                        // IBKR returns time as epoch seconds (when formatDate=2)
                        // bar.time() returns a string like "1759843800"
                        long epochSeconds = Long.parseLong(bar.time());
                        Instant timestamp = Instant.ofEpochSecond(epochSeconds);

                        return Candle.builder()
                                .timestamp(timestamp)
                                .open(bar.open())
                                .high(bar.high())
                                .low(bar.low())
                                .close(bar.close())
                                .volume(bar.volume().value().longValue())
                                .intervalMinutes(intervalMinutes)
                                .build();
                    })
                    .collect(Collectors.toList());

            log.info("✅ Converted {} IBKR bars to candles for {}", allCandles.size(), symbol);

            // ✅ NEW: Cache each date separately
            cachePerDate(symbol, allCandles, intervalMinutes);

            // Return only the candles for the requested date + 4 days history
            LocalDate targetDate = LocalDate.parse(date);
            return allCandles.stream()
                    .filter(c -> {
                        LocalDate candleDate = c.getTimestamp().atZone(ZoneId.of("UTC")).toLocalDate();
                        return !candleDate.isAfter(targetDate) &&
                                !candleDate.isBefore(targetDate.minusDays(4));
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            // Check if it's an invalid symbol error (IBKR Error 200)
            if (e.getMessage() != null && e.getMessage().contains("Error 200")) {
                log.warn("❌ Invalid symbol or not found in IBKR: {} - {}", symbol, e.getMessage());
            } else {
                log.error("Error fetching from IBKR for {}: {}", symbol, e.getMessage(), e);
            }
            return new ArrayList<>();
        }
    }

    /**
     * Cache candles separately for each date
     * This prevents overwrite conflicts when force refreshing
     * Uses UPSERT to prevent duplicates
     */
    private void cachePerDate(String symbol, List<Candle> allCandles, int intervalMinutes) {
        // Group candles by date
        Map<LocalDate, List<Candle>> candlesByDate = allCandles.stream()
                .collect(Collectors.groupingBy(c ->
                        c.getTimestamp().atZone(ZoneId.of("UTC")).toLocalDate()
                ));

        // Cache each date separately using UPSERT
        for (Map.Entry<LocalDate, List<Candle>> entry : candlesByDate.entrySet()) {
            String dateStr = entry.getKey().format(DateTimeFormatter.ISO_LOCAL_DATE);
            List<Candle> dateCandles = entry.getValue();

            try {
                // Build the document
                CandleData candleData = CandleData.builder()
                        .symbol(symbol)
                        .date(dateStr)
                        .intervalMinutes(intervalMinutes)
                        .candles(dateCandles)
                        .source("IBKR")
                        .fetchedAt(System.currentTimeMillis())
                        .build();

                // UPSERT: Update if exists, insert if not
                // This prevents duplicates by using the unique index
                Query query = new Query(Criteria.where("symbol").is(symbol)
                        .and("date").is(dateStr)
                        .and("intervalMinutes").is(intervalMinutes));

                Update update = new Update()
                        .set("candles", dateCandles)
                        .set("source", "IBKR")
                        .set("fetchedAt", System.currentTimeMillis());

                mongoTemplate.upsert(query, update, CandleData.class);

                log.debug("💾 Upserted {} candles for {} on {}", dateCandles.size(), symbol, dateStr);

            } catch (Exception e) {
                log.error("Error upserting candles for {} on {}: {}", symbol, dateStr, e.getMessage());
            }
        }
    }

    /**
     * Calculate how many days to fetch from IBKR
     * If we have recent cache, we don't need to fetch all 5 days
     */
    private int calculateDaysToFetch(String symbol, String targetDate, int intervalMinutes) {
        try {
            LocalDate target = LocalDate.parse(targetDate);

            // Check previous 4 days - if we have them cached, we only need to fetch 1 day
            for (int daysBack = 1; daysBack <= 4; daysBack++) {
                String prevDate = target.minusDays(daysBack).format(DateTimeFormatter.ISO_LOCAL_DATE);

                if (candleDataRepository.existsBySymbolAndDateAndIntervalMinutes(
                        symbol, prevDate, intervalMinutes)) {
                    // We have recent data! Only fetch from that point forward
                    log.debug("Found cached data at {}, fetching only {} days", prevDate, daysBack);
                    return daysBack;
                }
            }

            // No recent cache, fetch full 5 days
            return 5;

        } catch (Exception e) {
            log.debug("Error calculating days to fetch: {}", e.getMessage());
            return 5; // Default to 5 days on error
        }
    }

    /**
     * Upsert candles to MongoDB (update if exists, insert if not)
     * This prevents duplicate key errors
     */
    private void upsertCandles(String symbol, String date, int intervalMinutes, List<Candle> candles) {
        try {
            Query query = new Query(Criteria.where("symbol").is(symbol)
                    .and("date").is(date)
                    .and("intervalMinutes").is(intervalMinutes));

            Update update = new Update()
                    .set("candles", candles)
                    .set("source", "IBKR")
                    .set("fetchedAt", System.currentTimeMillis());

            mongoTemplate.upsert(query, update, CandleData.class);
            log.debug("💾 Upserted {} candles for {} on {}", candles.size(), symbol, date);

        } catch (Exception e) {
            log.error("Error upserting candles for {} on {}: {}", symbol, date, e.getMessage());
        }
    }

    /**
     * Check if candles are cached
     */
    public boolean isCached(String symbol, String date, int intervalMinutes) {
        return candleDataRepository.existsBySymbolAndDateAndIntervalMinutes(
                symbol, date, intervalMinutes);
    }

    /**
     * Get cache statistics for a date
     */
    public CacheStats getCacheStats(String date) {
        List<StockData> allSymbols = stockDataRepository.findByDate(date);
        List<CandleData> cached = candleDataRepository.findByDate(date);

        return new CacheStats(
                allSymbols.size(),
                cached.size(),
                cached.stream().mapToInt(CandleData::getCandleCount).sum()
        );
    }

    /**
     * Clear cache for a specific date (useful for re-fetching)
     */
    public void clearCache(String date) {
        List<CandleData> cached = candleDataRepository.findByDate(date);
        cached.forEach(cd -> candleDataRepository.deleteById(cd.getId()));
        log.info("🗑️ Cleared {} cached entries for {}", cached.size(), date);
    }

    /**
     * Clear cache for a specific symbol/date
     */
    public void clearCache(String symbol, String date) {
        candleDataRepository.deleteBySymbolAndDate(symbol, date);
        log.info("🗑️ Cleared cache for {} on {}", symbol, date);
    }

    /**
     * Cache statistics
     */
    public static class CacheStats {
        public final int totalSymbols;
        public final int cachedSymbols;
        public final int totalCandles;

        public CacheStats(int totalSymbols, int cachedSymbols, int totalCandles) {
            this.totalSymbols = totalSymbols;
            this.cachedSymbols = cachedSymbols;
            this.totalCandles = totalCandles;
        }

        public double getCacheHitRate() {
            return totalSymbols > 0 ? (cachedSymbols * 100.0 / totalSymbols) : 0.0;
        }
    }
}