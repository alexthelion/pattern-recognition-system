package hashita.controller;

import hashita.data.CandlePattern;
import hashita.data.PatternRecognitionResult;
import hashita.service.PatternAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST controller for candlestick pattern recognition
 */
@RestController
@RequestMapping("/api/patterns")
@RequiredArgsConstructor
@Slf4j
public class PatternRecognitionController {
    
    private final PatternAnalysisService patternAnalysisService;
    
    /**
     * Analyze patterns for a specific stock on a specific date
     * GET /api/patterns/analyze?symbol=AAPL&date=2025-04-01&interval=5
     */
    @GetMapping("/analyze")
    public ResponseEntity<PatternAnalysisResponse> analyzeStock(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(defaultValue = "5") int interval) {
        
        log.info("Analyzing patterns for {} on {} with {} minute interval", symbol, date, interval);
        
        String dateStr = date.toString();
        List<PatternRecognitionResult> patterns = 
                patternAnalysisService.analyzeStockForDate(symbol, dateStr, interval);
        
        PatternAnalysisService.PatternSummary summary = 
                patternAnalysisService.getPatternSummary(patterns);
        
        List<PatternRecognitionResult> strongestSignals = 
                patternAnalysisService.findStrongestSignals(patterns, 5);
        
        return ResponseEntity.ok(new PatternAnalysisResponse(
                symbol,
                dateStr,
                interval,
                patterns,
                summary,
                strongestSignals
        ));
    }
    
    /**
     * Analyze patterns for a stock over a date range
     * GET /api/patterns/analyze-range?symbol=AAPL&startDate=2025-04-01&endDate=2025-04-30&interval=5
     */
    @GetMapping("/analyze-range")
    public ResponseEntity<Map<String, List<PatternRecognitionResult>>> analyzeStockRange(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
            @RequestParam(defaultValue = "5") int interval) {
        
        log.info("Analyzing patterns for {} from {} to {} with {} minute interval",
                symbol, startDate, endDate, interval);
        
        Map<String, List<PatternRecognitionResult>> patternsByDate = 
                patternAnalysisService.analyzeStockForDateRange(
                        symbol, startDate.toString(), endDate.toString(), interval);
        
        return ResponseEntity.ok(patternsByDate);
    }
    
    /**
     * Analyze patterns for all stocks on a specific date
     * GET /api/patterns/analyze-all?date=2025-04-01&interval=5
     */
    @GetMapping("/analyze-all")
    public ResponseEntity<Map<String, List<PatternRecognitionResult>>> analyzeAllStocks(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(defaultValue = "5") int interval) {
        
        log.info("Analyzing patterns for all stocks on {} with {} minute interval", date, interval);
        
        Map<String, List<PatternRecognitionResult>> patternsBySymbol = 
                patternAnalysisService.analyzeAllStocksForDate(date.toString(), interval);
        
        return ResponseEntity.ok(patternsBySymbol);
    }
    
    /**
     * Get all bullish signals for a stock on a date
     * GET /api/patterns/bullish?symbol=AAPL&date=2025-04-01&interval=5
     */
    @GetMapping("/bullish")
    public ResponseEntity<List<PatternRecognitionResult>> getBullishPatterns(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "70") double minConfidence) {
        
        List<PatternRecognitionResult> patterns = 
                patternAnalysisService.analyzeStockForDate(symbol, date.toString(), interval);
        
        List<PatternRecognitionResult> bullishPatterns = patterns.stream()
                .filter(PatternRecognitionResult::isBullish)
                .filter(p -> p.getConfidence() >= minConfidence)
                .toList();
        
        return ResponseEntity.ok(bullishPatterns);
    }
    
    /**
     * Get all bearish signals for a stock on a date
     * GET /api/patterns/bearish?symbol=AAPL&date=2025-04-01&interval=5
     */
    @GetMapping("/bearish")
    public ResponseEntity<List<PatternRecognitionResult>> getBearishPatterns(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "70") double minConfidence) {
        
        List<PatternRecognitionResult> patterns = 
                patternAnalysisService.analyzeStockForDate(symbol, date.toString(), interval);
        
        List<PatternRecognitionResult> bearishPatterns = patterns.stream()
                .filter(PatternRecognitionResult::isBearish)
                .filter(p -> p.getConfidence() >= minConfidence)
                .toList();
        
        return ResponseEntity.ok(bearishPatterns);
    }
    
    /**
     * Get specific pattern type for a stock
     * GET /api/patterns/specific?symbol=AAPL&date=2025-04-01&pattern=BULLISH_ENGULFING&interval=5
     */
    @GetMapping("/specific")
    public ResponseEntity<List<PatternRecognitionResult>> getSpecificPattern(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam CandlePattern pattern,
            @RequestParam(defaultValue = "5") int interval) {
        
        List<PatternRecognitionResult> allPatterns = 
                patternAnalysisService.analyzeStockForDate(symbol, date.toString(), interval);
        
        List<PatternRecognitionResult> specificPatterns = 
                patternAnalysisService.filterByPattern(allPatterns, pattern);
        
        return ResponseEntity.ok(specificPatterns);
    }
    
    /**
     * Get summary of all patterns for a stock on a date
     * GET /api/patterns/summary?symbol=AAPL&date=2025-04-01&interval=5
     */
    @GetMapping("/summary")
    public ResponseEntity<PatternAnalysisService.PatternSummary> getPatternSummary(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(defaultValue = "5") int interval) {
        
        List<PatternRecognitionResult> patterns = 
                patternAnalysisService.analyzeStockForDate(symbol, date.toString(), interval);
        
        PatternAnalysisService.PatternSummary summary = 
                patternAnalysisService.getPatternSummary(patterns);
        
        return ResponseEntity.ok(summary);
    }
    
    /**
     * Get strongest signals (top N by confidence) for a stock
     * GET /api/patterns/strongest?symbol=AAPL&date=2025-04-01&interval=5&limit=5
     */
    @GetMapping("/strongest")
    public ResponseEntity<List<PatternRecognitionResult>> getStrongestSignals(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "5") int limit) {
        
        List<PatternRecognitionResult> patterns = 
                patternAnalysisService.analyzeStockForDate(symbol, date.toString(), interval);
        
        List<PatternRecognitionResult> strongestSignals = 
                patternAnalysisService.findStrongestSignals(patterns, limit);
        
        return ResponseEntity.ok(strongestSignals);
    }
    
    /**
     * Response DTO for pattern analysis
     */
    public record PatternAnalysisResponse(
            String symbol,
            String date,
            int intervalMinutes,
            List<PatternRecognitionResult> patterns,
            PatternAnalysisService.PatternSummary summary,
            List<PatternRecognitionResult> strongestSignals
    ) {}
}
