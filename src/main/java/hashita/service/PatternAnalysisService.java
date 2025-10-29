package hashita.service;

import hashita.data.Candle;
import hashita.data.CandlePattern;
import hashita.data.PatternRecognitionResult;
import hashita.data.entities.StockData;
import hashita.repository.StockDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ✅ UPDATED: Now uses IBKR candles instead of building from ticks
 *
 * Service for analyzing stock patterns
 * This is the main entry point for pattern detection
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PatternAnalysisService {

    private final StockDataRepository stockDataRepository;
    private final IBKRCandleService ibkrCandleService;
    private final PatternRecognitionService patternRecognitionService;

    /**
     * Analyze a stock for patterns on a specific date
     *
     * ✅ NEW: Uses IBKR candles instead of CandleBuilderService
     *
     * @param symbol Stock symbol
     * @param date Date in yyyy-MM-dd format
     * @param intervalMinutes Candle interval (1, 5, 15, 30, 60)
     * @return List of detected patterns
     */
    public List<PatternRecognitionResult> analyzeStockForDate(
            String symbol,
            String date,
            int intervalMinutes) {

        log.debug("Analyzing {} for {} ({}min interval)", symbol, date, intervalMinutes);

        // Verify stock data exists for this date
        Optional<StockData> stockDataOpt = stockDataRepository.findByStockInfoAndDate(symbol, date);

        if (stockDataOpt.isEmpty()) {
            log.warn("No stock data found for {} on {}", symbol, date);
            return new ArrayList<>();
        }

        // ✅ Get candles from CACHE ONLY (never fetch from IBKR during analysis)
        List<Candle> candles = ibkrCandleService.getCandlesFromCacheOnly(symbol, date, intervalMinutes);

        if (candles.isEmpty()) {
            log.warn("No candles available for {} on {}", symbol, date);
            return new ArrayList<>();
        }

        if (candles.size() < 10) {
            log.warn("Insufficient candles for {}: {} (need at least 10)", symbol, candles.size());
            return new ArrayList<>();
        }

        log.debug("Analyzing {} candles for {}", candles.size(), symbol);

        // Run pattern detection on all candles (for context)
        List<PatternRecognitionResult> allPatterns =
                patternRecognitionService.scanForPatterns(candles, symbol);

        // ✅ FILTER: Only return patterns from the requested date!
        LocalDate targetDate = LocalDate.parse(date);
        List<PatternRecognitionResult> patternsForDate = allPatterns.stream()
                .filter(pattern -> {
                    LocalDate patternDate = pattern.getTimestamp()
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate();
                    return patternDate.equals(targetDate);
                })
                .collect(Collectors.toList());

        log.info("Found {} patterns for {} on {} (out of {} total from context)",
                patternsForDate.size(), symbol, date, allPatterns.size());

        return patternsForDate;
    }

    /**
     * Analyze multiple stocks for a specific date
     *
     * @param date Date in yyyy-MM-dd format
     * @param intervalMinutes Candle interval
     * @return Map of symbol → patterns
     */
    public java.util.Map<String, List<PatternRecognitionResult>> analyzeAllStocksForDate(
            String date,
            int intervalMinutes) {

        log.info("Analyzing all stocks for {} ({}min interval)", date, intervalMinutes);

        // Get all symbols for this date
        List<StockData> stockDataList = stockDataRepository.findByDate(date);

        if (stockDataList.isEmpty()) {
            log.warn("No stocks found for {}", date);
            return java.util.Collections.emptyMap();
        }

        List<String> symbols = stockDataList.stream()
                .map(StockData::getStockInfo)
                .distinct()
                .collect(java.util.stream.Collectors.toList());

        log.info("Analyzing {} symbols", symbols.size());

        java.util.Map<String, List<PatternRecognitionResult>> results = new java.util.LinkedHashMap<>();

        for (String symbol : symbols) {
            try {
                List<PatternRecognitionResult> patterns = analyzeStockForDate(symbol, date, intervalMinutes);

                if (!patterns.isEmpty()) {
                    results.put(symbol, patterns);
                }

            } catch (Exception e) {
                log.error("Error analyzing {}: {}", symbol, e.getMessage(), e);
            }
        }

        log.info("Found patterns in {}/{} symbols", results.size(), symbols.size());

        return results;
    }

    /**
     * Get summary statistics for a date
     */
    public AnalysisSummary getAnalysisSummary(String date, int intervalMinutes) {
        java.util.Map<String, List<PatternRecognitionResult>> allPatterns =
                analyzeAllStocksForDate(date, intervalMinutes);

        int totalPatterns = allPatterns.values().stream()
                .mapToInt(List::size)
                .sum();

        java.util.Map<CandlePattern, Long> patternCounts = allPatterns.values().stream()
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.groupingBy(
                        PatternRecognitionResult::getPattern,
                        java.util.stream.Collectors.counting()
                ));

        return new AnalysisSummary(
                date,
                allPatterns.size(),
                totalPatterns,
                patternCounts
        );
    }

    /**
     * Analysis summary
     */
    public static class AnalysisSummary {
        public final String date;
        public final int symbolsWithPatterns;
        public final int totalPatterns;
        public final java.util.Map<CandlePattern, Long> patternCounts;

        public AnalysisSummary(String date, int symbolsWithPatterns, int totalPatterns,
                               java.util.Map<CandlePattern, Long> patternCounts) {
            this.date = date;
            this.symbolsWithPatterns = symbolsWithPatterns;
            this.totalPatterns = totalPatterns;
            this.patternCounts = patternCounts;
        }
    }
}