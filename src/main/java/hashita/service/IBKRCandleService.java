package hashita.service;

import hashita.data.Candle;
import hashita.data.entities.CandleData;
import hashita.data.entities.StockData;
import hashita.repository.CandleDataRepository;
import hashita.repository.StockDataRepository;
import hashita.service.ibkr.IBKRClient;
import com.ib.client.Bar;
import com.google.common.util.concurrent.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * ✅ FIXED: Service to fetch and cache candles from IBKR
 *
 * CRITICAL RULES:
 * 1. getCandlesFromCacheOnly() - NEVER calls IBKR (for simulations/analysis)
 * 2. getCandles() - May call IBKR if cache miss (for realtime only)
 * 3. fetchFromIBKR() - Direct IBKR call (for CandleManagementController)
 *
 * Thread-safe with proper synchronization
 * Rate-limited to prevent IBKR throttling
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IBKRCandleService {

    private final CandleDataRepository candleDataRepository;
    private final StockDataRepository stockDataRepository;
    private final IBKRClient ibkrClient;
    private final MongoTemplate mongoTemplate;

    // ✅ FIX: Thread-safe locks per symbol to prevent race conditions
    private final ConcurrentHashMap<String, Lock> symbolLocks = new ConcurrentHashMap<>();

    // ✅ FIX: Rate limiter to prevent IBKR API abuse (2 requests per second)
    private final RateLimiter rateLimiter = RateLimiter.create(2.0);

    /**
     * ✅ CACHE-ONLY MODE (for simulations/analysis)
     *
     * Get candles from cache ONLY - NEVER fetches from IBKR
     * Used by:
     * - AlertSimulationController
     * - PatternAnalysisService
     * - PatternRecognitionController
     *
     * Returns empty list if not cached
     */
    public List<Candle> getCandlesFromCacheOnly(String symbol, String date, int intervalMinutes) {
        log.debug("📦 Getting CACHED candles for {} on {} ({}min)", symbol, date, intervalMinutes);

        // Check direct cache hit
        Optional<CandleData> cached = candleDataRepository
                .findBySymbolAndDateAndIntervalMinutes(symbol, date, intervalMinutes);

        if (cached.isPresent()) {
            log.debug("✅ Cache HIT: {} candles", cached.get().getCandleCount());
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
        log.warn("⚠️ Cache MISS for {} on {} - returning empty (use /fetch-date to populate)",
                symbol, date);
        return new ArrayList<>();
    }

    /**
     * ✅ REALTIME MODE (for live trading)
     *
     * Get candles - checks cache first, may fetch from IBKR on cache miss
     * Used by:
     * - RealtimePatternController only
     *
     * Thread-safe with per-symbol locking to prevent duplicate IBKR calls
     */
    public List<Candle> getCandles(String symbol, String date, int intervalMinutes) {
        return getCandles(symbol, date, intervalMinutes, false);
    }

    /**
     * ✅ REALTIME MODE with force refresh option
     */
    public List<Candle> getCandles(String symbol, String date, int intervalMinutes, boolean forceRefresh) {
        // ✅ FIX: Lock per symbol+date to prevent race conditions
        String lockKey = symbol + ":" + date + ":" + intervalMinutes;
        Lock lock = symbolLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());

        lock.lock();
        try {
            log.debug("🔄 Getting candles for {} on {} ({}min, forceRefresh={})",
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
                    log.info("✅ Found {} candles from recent cache (no IBKR fetch needed)",
                            existingCandles.size());
                    // Cache under this date for future lookups
                    upsertCandles(symbol, date, intervalMinutes, existingCandles);
                    return existingCandles;
                }
            } else {
                log.info("🔄 Force refresh requested - skipping cache");
            }

            // ✅ FIX: Rate limit IBKR calls
            log.info("⬇️ Fetching candles from IBKR for {} on {} (rate-limited)", symbol, date);
            rateLimiter.acquire(); // Wait if needed

            List<Candle> candles = fetchFromIBKR(symbol, date, intervalMinutes);
            return candles;

        } finally {
            lock.unlock();
        }
    }

    /**
     * Get candles for pattern analysis with 4 days of historical context
     *
     * ✅ CACHE-ONLY by default
     * Only fetches if allowFetch=true (for realtime controller)
     */
    public List<Candle> getCandlesWithContext(String symbol, String date, int intervalMinutes) {
        return getCandlesWithContext(symbol, date, intervalMinutes, false);
    }

    /**
     * ✅ NEW: Version with fetch control
     */
    public List<Candle> getCandlesWithContext(String symbol, String date, int intervalMinutes,
                                              boolean allowFetch) {
        try {
            LocalDate targetDate = LocalDate.parse(date);
            List<Candle> allCandles = new ArrayList<>();

            // Get 4 days of history + target date
            for (int daysBack = 4; daysBack >= 0; daysBack--) {
                String checkDate = targetDate.minusDays(daysBack)
                        .format(DateTimeFormatter.ISO_LOCAL_DATE);

                List<Candle> dayCandles;

                if (allowFetch) {
                    // Realtime mode - may fetch from IBKR
                    dayCandles = getCandles(symbol, checkDate, intervalMinutes);
                } else {
                    // Cache-only mode - no IBKR calls
                    dayCandles = getCandlesFromCacheOnly(symbol, checkDate, intervalMinutes);
                }

                if (!dayCandles.isEmpty()) {
                    allCandles.addAll(dayCandles);
                }
            }

            if (allCandles.isEmpty()) {
                log.warn("No candles found for {} (checked {} to {})",
                        symbol, targetDate.minusDays(4), targetDate);
                return new ArrayList<>();
            }

            // Sort by timestamp and remove duplicates
            allCandles = allCandles.stream()
                    .sorted(Comparator.comparing(Candle::getTimestamp))
                    .distinct()
                    .collect(Collectors.toList());

            log.debug("Retrieved {} total candles for {} with context", allCandles.size(), symbol);
            return allCandles;

        } catch (Exception e) {
            log.error("Error getting candles with context for {}: {}", symbol, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * ✅ BATCH FETCH MODE (for CandleManagementController)
     *
     * Fetch and cache candles for all symbols on a specific date
     * This is the ONLY place where we proactively call IBKR for multiple symbols
     */
    public int fetchCandlesForDate(String date, int intervalMinutes) {
        log.info("📊 Starting batch candle fetch for {} ({}min interval)", date, intervalMinutes);

        // Get all symbols that have stock data for this date
        List<StockData> stockDataList = stockDataRepository.findByDate(date);

        if (stockDataList.isEmpty()) {
            log.warn("No stock data found for {}", date);
            return 0;
        }

        List<String> symbols = stockDataList.stream()
                .map(StockData::getStockInfo)
                .distinct()
                .collect(Collectors.toList());

        log.info("Found {} symbols to process", symbols.size());

        int processed = 0;
        int errors = 0;

        for (String symbol : symbols) {
            try {
                // ✅ FIX: Rate limit each request
                rateLimiter.acquire();

                log.info("  Processing {}/{}: {}", processed + 1, symbols.size(), symbol);

                // Fetch and cache (fetchFromIBKR already handles caching)
                List<Candle> candles = fetchFromIBKR(symbol, date, intervalMinutes);

                if (!candles.isEmpty()) {
                    log.info("    ✅ Cached {} candles for {}", candles.size(), symbol);
                    processed++;
                } else {
                    log.warn("    ⚠️ No candles returned for {}", symbol);
                }

                // ✅ FIX: Small delay between symbols to be nice to IBKR
                Thread.sleep(100);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while processing {}", symbol);
                break;
            } catch (Exception e) {
                errors++;
                log.error("  ❌ Error fetching {}: {}", symbol, e.getMessage());
            }
        }

        log.info("✅ Batch fetch complete: {}/{} symbols processed, {} errors",
                processed, symbols.size(), errors);

        return processed;
    }

    /**
     * ✅ BATCH FETCH MODE with date range
     *
     * Fetch candles for multiple dates
     * ⚠️ Use with caution - can make many IBKR API calls
     */
    public int fetchCandlesForDateRange(String startDate, String endDate, int intervalMinutes) {
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        // ✅ FIX: Safety limit to prevent abuse
        long daysBetween = ChronoUnit.DAYS.between(start, end);
        if (daysBetween > 90) {
            throw new IllegalArgumentException(
                    "Date range too large. Maximum 90 days allowed. Requested: " + daysBetween + " days");
        }

        log.info("📅 Starting batch fetch for date range: {} to {} ({} days)",
                startDate, endDate, daysBetween);

        LocalDate currentDate = start;
        int totalProcessed = 0;

        while (!currentDate.isAfter(end)) {
            String dateStr = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

            try {
                log.info("📆 Processing date: {}", dateStr);
                int processed = fetchCandlesForDate(dateStr, intervalMinutes);
                totalProcessed += processed;

                // ✅ FIX: Delay between dates to prevent rate limiting
                if (!currentDate.equals(end)) {
                    Thread.sleep(2000); // 2 second pause between dates
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted during date range fetch");
                break;
            } catch (Exception e) {
                log.error("Error processing date {}: {}", dateStr, e.getMessage());
            }

            currentDate = currentDate.plusDays(1);
        }

        log.info("✅ Date range fetch complete: {} total symbols processed", totalProcessed);
        return totalProcessed;
    }

    /**
     * ✅ DIRECT IBKR FETCH
     *
     * Fetch candles directly from IBKR API
     * Private - only called by controlled methods above
     */
    private List<Candle> fetchFromIBKR(String symbol, String date, int intervalMinutes) {
        try {
            log.debug("🌐 Calling IBKR API for {} on {}", symbol, date);

            LocalDate targetDate = LocalDate.parse(date);

            // Calculate how many days to fetch (optimize if we have recent data)
            int daysToFetch = calculateDaysToFetch(symbol, date, intervalMinutes);

            String durationStr = daysToFetch + " D";

            log.debug("  Fetching {} days of data", daysToFetch);

            // Ensure IBKR is connected
            if (!ibkrClient.isConnected()) {
                ibkrClient.connect();
            }

            // Format end date/time for IBKR (yyyyMMdd HH:mm:ss TZ)
            String endDateTime = targetDate.format(DateTimeFormatter.BASIC_ISO_DATE) + " 23:59:59 UTC";

            // Bar size setting
            String barSizeSetting = intervalMinutes + (intervalMinutes == 1 ? " min" : " mins");

            // Call IBKR (correct method signature)
            List<Bar> bars = ibkrClient.getHistoricalBars(
                    symbol,
                    endDateTime,
                    durationStr,
                    barSizeSetting
            );

            if (bars == null || bars.isEmpty()) {
                log.warn("⚠️ IBKR returned no data for {}", symbol);
                return new ArrayList<>();
            }

            log.info("✅ IBKR returned {} bars for {}", bars.size(), symbol);

            // Convert to Candle objects
            List<Candle> allCandles = bars.stream()
                    .filter(bar -> bar.time() != null && !bar.time().isEmpty())
                    .map(bar -> {
                        // IBKR returns time as epoch seconds
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

            // ✅ Cache each date separately (thread-safe upsert)
            cachePerDate(symbol, allCandles, intervalMinutes);

            // Return only the candles for the requested date + 4 days history
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
                log.error("❌ Error fetching from IBKR for {}: {}", symbol, e.getMessage(), e);
            }
            return new ArrayList<>();
        }
    }

    /**
     * Try to find candles from recently cached data
     */
    private List<Candle> findCandlesFromRecentCache(String symbol, String targetDate, int intervalMinutes) {
        try {
            LocalDate target = LocalDate.parse(targetDate);

            // Check the next 5 dates
            for (int i = 1; i <= 5; i++) {
                String nextDate = target.plusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE);

                Optional<CandleData> cached = candleDataRepository
                        .findBySymbolAndDateAndIntervalMinutes(symbol, nextDate, intervalMinutes);

                if (cached.isPresent()) {
                    List<Candle> allCandles = cached.get().getCandles();

                    List<Candle> targetCandles = allCandles.stream()
                            .filter(c -> {
                                LocalDate candleDate = c.getTimestamp()
                                        .atZone(ZoneId.of("UTC"))
                                        .toLocalDate();
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
     * Cache candles separately for each date
     * ✅ Thread-safe with upsert to prevent duplicates
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
                upsertCandles(symbol, dateStr, intervalMinutes, dateCandles);
                log.debug("💾 Cached {} candles for {} on {}", dateCandles.size(), symbol, dateStr);

            } catch (Exception e) {
                log.error("Error caching candles for {} on {}: {}", symbol, dateStr, e.getMessage());
            }
        }
    }

    /**
     * Calculate how many days to fetch from IBKR
     */
    private int calculateDaysToFetch(String symbol, String targetDate, int intervalMinutes) {
        try {
            LocalDate target = LocalDate.parse(targetDate);

            // Check previous 4 days - if we have them cached, we only need to fetch 1 day
            for (int daysBack = 1; daysBack <= 4; daysBack++) {
                String prevDate = target.minusDays(daysBack).format(DateTimeFormatter.ISO_LOCAL_DATE);

                if (candleDataRepository.existsBySymbolAndDateAndIntervalMinutes(
                        symbol, prevDate, intervalMinutes)) {
                    log.debug("Found cached data at {}, fetching only {} days", prevDate, daysBack);
                    return daysBack;
                }
            }

            // No recent cache, fetch full 5 days
            return 5;

        } catch (Exception e) {
            log.debug("Error calculating days to fetch: {}", e.getMessage());
            return 5;
        }
    }

    /**
     * ✅ Thread-safe upsert to prevent duplicate key errors
     */
    private void upsertCandles(String symbol, String date, int intervalMinutes, List<Candle> candles) {
        try {
            Query query = new Query(Criteria.where("symbol").is(symbol)
                    .and("date").is(date)
                    .and("intervalMinutes").is(intervalMinutes));

            Update update = new Update()
                    .set("candles", candles)
                    .set("candleCount", candles.size())
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
     * Clear cache for a specific date
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