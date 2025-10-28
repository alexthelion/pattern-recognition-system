package hashita.service;

import hashita.data.Candle;
import hashita.data.CandlePattern;
import hashita.data.PatternRecognitionResult;
import hashita.data.entities.StockData;
import hashita.data.entities.TickerVolume;
import hashita.repository.StockDataRepository;
import hashita.repository.TickerVolumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to orchestrate pattern recognition on historical stock data
 * Pure pattern detection - no trading logic
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PatternAnalysisService {

    private final StockDataRepository stockDataRepository;
    private final TickerVolumeRepository tickerVolumeRepository;
    private final CandleBuilderService candleBuilderService;
    private final PatternRecognitionService patternRecognitionService;

    /**
     * Analyze patterns for a specific stock on a specific date
     *
     * @param symbol Stock symbol
     * @param date Date in yyyy-MM-dd format
     * @param intervalMinutes Candle interval (1, 5, 15, etc.)
     * @return List of detected patterns
     */
    public List<PatternRecognitionResult> analyzeStockForDate(String symbol, String date, int intervalMinutes) {
        log.info("Analyzing patterns for {} on {} with {} minute interval", symbol, date, intervalMinutes);

        // Fetch stock data
        Optional<StockData> stockDataOpt = stockDataRepository.findByStockInfoAndDate(symbol, date);
        if (stockDataOpt.isEmpty()) {
            log.warn("No stock data found for {} on {}", symbol, date);
            return Collections.emptyList();
        }

        StockData stockData = stockDataOpt.get();

        // Fetch volume data (OPTIONAL - proceed without it if not available)
        Optional<TickerVolume> volumeDataOpt = tickerVolumeRepository.findByStockInfoAndDate(symbol, date);

        List<TickerVolume.IntervalVolume> filteredVolumes;
        if (volumeDataOpt.isEmpty()) {
            log.info("No volume data found for {} on {} - proceeding without volume confirmation", symbol, date);
            filteredVolumes = Collections.emptyList();
        } else {
            TickerVolume volumeData = volumeDataOpt.get();
            filteredVolumes = volumeData.getIntervalVolumes().stream()
                    .filter(iv -> iv.getIntervalMinutes() == intervalMinutes)
                    .collect(Collectors.toList());
        }

        // Build candles (with or without volume)
        List<Candle> candles = candleBuilderService.buildCandles(
                stockData.getStocksPrices(),
                filteredVolumes,
                intervalMinutes
        );

        if (candles.isEmpty()) {
            log.warn("No candles built for {} on {}", symbol, date);
            return Collections.emptyList();
        }

        log.info("Built {} candles for {} on {}", candles.size(), symbol, date);

        // Scan for patterns
        List<PatternRecognitionResult> patterns = patternRecognitionService.scanForPatterns(candles, symbol);

        log.info("Found {} patterns for {} on {}", patterns.size(), symbol, date);

        return patterns;
    }

    /**
     * Analyze patterns for a stock over a date range
     *
     * @param symbol Stock symbol
     * @param startDate Start date (yyyy-MM-dd)
     * @param endDate End date (yyyy-MM-dd)
     * @param intervalMinutes Candle interval
     * @return Map of date to patterns
     */
    public Map<String, List<PatternRecognitionResult>> analyzeStockForDateRange(
            String symbol, String startDate, String endDate, int intervalMinutes) {

        log.info("Analyzing patterns for {} from {} to {} with {} minute interval",
                symbol, startDate, endDate, intervalMinutes);

        // Fetch all stock data for date range
        List<StockData> stockDataList = stockDataRepository
                .findByStockInfoAndDateBetween(symbol, startDate, endDate);

        // Fetch all volume data for date range (OPTIONAL)
        List<TickerVolume> volumeDataList = tickerVolumeRepository
                .findByStockInfoAndDateBetween(symbol, startDate, endDate);

        // Create volume map for quick lookup
        Map<String, TickerVolume> volumeMap = volumeDataList.stream()
                .collect(Collectors.toMap(TickerVolume::getDate, tv -> tv));

        Map<String, List<PatternRecognitionResult>> patternsByDate = new LinkedHashMap<>();

        for (StockData stockData : stockDataList) {
            String date = stockData.getDate();
            TickerVolume volumeData = volumeMap.get(date);

            // Get volume if available, empty list otherwise
            List<TickerVolume.IntervalVolume> filteredVolumes;
            if (volumeData == null) {
                log.debug("No volume data for {} on {} - proceeding without volume", symbol, date);
                filteredVolumes = Collections.emptyList();
            } else {
                filteredVolumes = volumeData.getIntervalVolumes().stream()
                        .filter(iv -> iv.getIntervalMinutes() == intervalMinutes)
                        .collect(Collectors.toList());
            }

            // Build candles
            List<Candle> candles = candleBuilderService.buildCandles(
                    stockData.getStocksPrices(),
                    filteredVolumes,
                    intervalMinutes
            );

            if (!candles.isEmpty()) {
                // Scan for patterns
                List<PatternRecognitionResult> patterns =
                        patternRecognitionService.scanForPatterns(candles, symbol);

                if (!patterns.isEmpty()) {
                    patternsByDate.put(date, patterns);
                }
            }
        }

        log.info("Found patterns on {} days for {}", patternsByDate.size(), symbol);

        return patternsByDate;
    }

    /**
     * Analyze patterns for all stocks on a specific date
     *
     * @param date Date (yyyy-MM-dd)
     * @param intervalMinutes Candle interval
     * @return Map of symbol to patterns
     */
    public Map<String, List<PatternRecognitionResult>> analyzeAllStocksForDate(
            String date, int intervalMinutes) {

        log.info("Analyzing patterns for all stocks on {} with {} minute interval", date, intervalMinutes);

        // Fetch all stock data for date
        List<StockData> stockDataList = stockDataRepository.findByDate(date);

        // Fetch all volume data for date (OPTIONAL)
        List<TickerVolume> volumeDataList = tickerVolumeRepository.findByDate(date);

        // Create volume map for quick lookup
        Map<String, TickerVolume> volumeMap = volumeDataList.stream()
                .collect(Collectors.toMap(TickerVolume::getStockInfo, tv -> tv));

        Map<String, List<PatternRecognitionResult>> patternsBySymbol = new LinkedHashMap<>();

        for (StockData stockData : stockDataList) {
            String symbol = stockData.getStockInfo();
            TickerVolume volumeData = volumeMap.get(symbol);

            // Get volume if available, empty list otherwise
            List<TickerVolume.IntervalVolume> filteredVolumes;
            if (volumeData == null) {
                log.debug("No volume data for {} on {} - proceeding without volume", symbol, date);
                filteredVolumes = Collections.emptyList();
            } else {
                filteredVolumes = volumeData.getIntervalVolumes().stream()
                        .filter(iv -> iv.getIntervalMinutes() == intervalMinutes)
                        .collect(Collectors.toList());
            }

            // Build candles
            List<Candle> candles = candleBuilderService.buildCandles(
                    stockData.getStocksPrices(),
                    filteredVolumes,
                    intervalMinutes
            );

            if (!candles.isEmpty()) {
                // Scan for patterns
                List<PatternRecognitionResult> patterns =
                        patternRecognitionService.scanForPatterns(candles, symbol);

                if (!patterns.isEmpty()) {
                    patternsBySymbol.put(symbol, patterns);
                }
            }
        }

        log.info("Found patterns for {} stocks on {}", patternsBySymbol.size(), date);

        return patternsBySymbol;
    }

    /**
     * Get summary statistics for detected patterns
     */
    public PatternSummary getPatternSummary(List<PatternRecognitionResult> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return new PatternSummary(0, 0, 0, 0, Collections.emptyMap());
        }

        long totalPatterns = patterns.size();
        long bullishCount = patterns.stream().filter(PatternRecognitionResult::isBullish).count();
        long bearishCount = patterns.stream().filter(PatternRecognitionResult::isBearish).count();
        long neutralCount = totalPatterns - bullishCount - bearishCount;

        Map<CandlePattern, Long> patternCounts = patterns.stream()
                .collect(Collectors.groupingBy(
                        PatternRecognitionResult::getPattern,
                        Collectors.counting()
                ));

        return new PatternSummary(totalPatterns, bullishCount, bearishCount, neutralCount, patternCounts);
    }

    /**
     * Find the strongest signals (highest confidence patterns)
     */
    public List<PatternRecognitionResult> findStrongestSignals(
            List<PatternRecognitionResult> patterns, int limit) {

        return patterns.stream()
                .sorted(Comparator.comparing(PatternRecognitionResult::getConfidence).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Filter patterns by specific pattern type
     */
    public List<PatternRecognitionResult> filterByPattern(
            List<PatternRecognitionResult> patterns, CandlePattern pattern) {

        return patterns.stream()
                .filter(p -> p.getPattern() == pattern)
                .collect(Collectors.toList());
    }

    /**
     * Filter patterns by type (bullish/bearish/neutral)
     */
    public List<PatternRecognitionResult> filterByType(
            List<PatternRecognitionResult> patterns, CandlePattern.PatternType type) {

        return patterns.stream()
                .filter(p -> p.getPattern().getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Filter patterns by minimum confidence
     */
    public List<PatternRecognitionResult> filterByConfidence(
            List<PatternRecognitionResult> patterns, double minConfidence) {

        return patterns.stream()
                .filter(p -> p.getConfidence() >= minConfidence)
                .collect(Collectors.toList());
    }

    /**
     * Summary statistics for patterns
     */
    public record PatternSummary(
            long totalPatterns,
            long bullishPatterns,
            long bearishPatterns,
            long neutralPatterns,
            Map<CandlePattern, Long> patternCounts
    ) {}
}