package hashita.controller;

import hashita.data.Candle;
import hashita.service.IBKRCandleService;
import hashita.service.IBKRCandleService.CacheStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller for fetching and managing IBKR candle data
 */
@RestController
@RequestMapping("/api/candles")
@RequiredArgsConstructor
@Slf4j
public class CandleManagementController {

    private final IBKRCandleService ibkrCandleService;

    /**
     * Fetch and cache candles for all symbols on a specific date
     *
     * POST /api/candles/fetch-date
     * {
     *   "date": "2025-10-07",
     *   "intervalMinutes": 5
     * }
     *
     * Response:
     * {
     *   "date": "2025-10-07",
     *   "intervalMinutes": 5,
     *   "symbolsProcessed": 25,
     *   "message": "Fetched candles for 25 symbols"
     * }
     */
    @PostMapping("/fetch-date")
    public ResponseEntity<?> fetchCandlesForDate(@RequestBody Map<String, Object> request) {
        try {
            String date = (String) request.get("date");
            Integer intervalMinutes = (Integer) request.getOrDefault("intervalMinutes", 5);

            log.info("🔄 Starting candle fetch for {} ({}min interval)", date, intervalMinutes);

            int processed = ibkrCandleService.fetchCandlesForDate(date, intervalMinutes);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("date", date);
            response.put("intervalMinutes", intervalMinutes);
            response.put("symbolsProcessed", processed);
            response.put("message", "Fetched candles for " + processed + " symbols");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching candles", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Fetch and cache candles for a date range (batch processing)
     *
     * POST /api/candles/fetch-range
     * {
     *   "startDate": "2025-03-01",
     *   "endDate": "2025-10-07",
     *   "intervalMinutes": 5
     * }
     *
     * ⚠️ This can take a LONG time! Use with caution.
     * Recommended: Start with small date ranges (1 week at a time)
     */
    @PostMapping("/fetch-range")
    public ResponseEntity<?> fetchCandlesForDateRange(@RequestBody Map<String, Object> request) {
        try {
            String startDate = (String) request.get("startDate");
            String endDate = (String) request.get("endDate");
            Integer intervalMinutes = (Integer) request.getOrDefault("intervalMinutes", 5);

            log.info("🔄 Starting batch candle fetch from {} to {} ({}min interval)",
                    startDate, endDate, intervalMinutes);

            int totalProcessed = ibkrCandleService.fetchCandlesForDateRange(
                    startDate, endDate, intervalMinutes);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("startDate", startDate);
            response.put("endDate", endDate);
            response.put("intervalMinutes", intervalMinutes);
            response.put("totalProcessed", totalProcessed);
            response.put("message", "Batch fetch complete: " + totalProcessed + " symbol-dates processed");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error in batch fetch", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get candles for a specific symbol/date
     * (Will fetch from IBKR if not cached)
     *
     * GET /api/candles?symbol=MGN&date=2025-10-07&interval=5
     */
    @GetMapping
    public ResponseEntity<?> getCandles(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval) {

        try {
            List<Candle> candles = ibkrCandleService.getCandles(symbol, date, interval);

            return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "intervalMinutes", interval,
                    "candleCount", candles.size(),
                    "candles", candles
            ));

        } catch (Exception e) {
            log.error("Error getting candles", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Check cache status for a date
     *
     * GET /api/candles/cache-stats?date=2025-10-07
     */
    @GetMapping("/cache-stats")
    public ResponseEntity<?> getCacheStats(@RequestParam String date) {
        try {
            CacheStats stats = ibkrCandleService.getCacheStats(date);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("date", date);
            response.put("totalSymbols", stats.totalSymbols);
            response.put("cachedSymbols", stats.cachedSymbols);
            response.put("totalCandles", stats.totalCandles);
            response.put("cacheHitRate", String.format("%.1f%%", stats.getCacheHitRate()));
            response.put("status", stats.cachedSymbols == stats.totalSymbols ? "COMPLETE" : "PARTIAL");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error getting cache stats", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Check if candles are cached for a specific symbol/date
     *
     * GET /api/candles/is-cached?symbol=MGN&date=2025-10-07&interval=5
     */
    @GetMapping("/is-cached")
    public ResponseEntity<?> isCached(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval) {

        boolean cached = ibkrCandleService.isCached(symbol, date, interval);

        return ResponseEntity.ok(Map.of(
                "symbol", symbol,
                "date", date,
                "intervalMinutes", interval,
                "cached", cached
        ));
    }

    /**
     * Clear cache for a specific date
     *
     * DELETE /api/candles/cache?date=2025-10-07
     */
    @DeleteMapping("/cache")
    public ResponseEntity<?> clearCache(
            @RequestParam String date,
            @RequestParam(required = false) String symbol) {

        try {
            if (symbol != null) {
                ibkrCandleService.clearCache(symbol, date);
                return ResponseEntity.ok(Map.of(
                        "message", "Cache cleared for " + symbol + " on " + date
                ));
            } else {
                ibkrCandleService.clearCache(date);
                return ResponseEntity.ok(Map.of(
                        "message", "Cache cleared for all symbols on " + date
                ));
            }

        } catch (Exception e) {
            log.error("Error clearing cache", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}