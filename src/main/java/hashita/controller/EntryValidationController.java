package hashita.controller;

import hashita.data.entities.StockData;
import hashita.repository.StockDataRepository;
import hashita.service.EntrySignalService;
import hashita.service.EntrySignalService.EntrySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Compare entry prices from StockData with pattern-detected signals
 */
@RestController
@RequestMapping("/api/entry-validation")
@RequiredArgsConstructor
@Slf4j
public class EntryValidationController {

    private final StockDataRepository stockDataRepository;
    private final EntrySignalService entrySignalService;

    /**
     * Validate entry prices for all stocks on a date
     * GET /api/entry-validation/validate?date=2025-10-01&interval=5
     */
    @GetMapping("/validate")
    public ResponseEntity<?> validateEntryPrices(
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval) {

        log.info("Validating entry prices for date: {}", date);

        // Get all stocks for this date
        List<StockData> allStocks = stockDataRepository.findByDate(date);

        if (allStocks.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "date", date,
                    "message", "No stock data found for this date"
            ));
        }

        log.info("Found {} stocks for {}", allStocks.size(), date);

        List<Map<String, Object>> validations = new ArrayList<>();
        int hasPatterns = 0;
        int noPatterns = 0;
        int matchedPrice = 0;

        for (StockData stock : allStocks) {
            String symbol = stock.getStockInfo();
            double entryPrice = stock.getEntryPrice();

            // Get pattern signals for this stock
            List<EntrySignal> signals = entrySignalService.findEntrySignals(symbol, date, interval);

            // ✅ SORT SIGNALS BY TIMESTAMP (chronological order)
            if (!signals.isEmpty()) {
                signals.sort(Comparator.comparing(EntrySignal::getTimestamp));
            }

            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put("symbol", symbol);
            validation.put("entryPrice", entryPrice);
            validation.put("date", date);

            if (signals.isEmpty()) {
                validation.put("status", "NO_PATTERNS");
                validation.put("message", "No patterns detected");
                noPatterns++;
            } else {
                hasPatterns++;

                // Find signal with closest price to entry
                EntrySignal closestSignal = signals.stream()
                        .min(Comparator.comparingDouble(s ->
                                Math.abs(s.getEntryPrice() - entryPrice)))
                        .orElse(signals.get(0));

                double priceDiff = closestSignal.getEntryPrice() - entryPrice;
                double percentDiff = (priceDiff / entryPrice) * 100;

                validation.put("status", "HAS_PATTERNS");
                validation.put("patternCount", signals.size());
                validation.put("closestPattern", Map.of(
                        "pattern", closestSignal.getPattern().name(),
                        "signalPrice", closestSignal.getEntryPrice(),
                        "priceDifference", String.format("%.2f", priceDiff),
                        "percentDiff", String.format("%.2f%%", percentDiff),
                        "confidence", closestSignal.getConfidence(),
                        "signalQuality", closestSignal.getSignalQuality(),
                        "direction", closestSignal.getDirection(),
                        "riskRewardRatio", closestSignal.getRiskRewardRatio()
                ));

                // Check if price is close (within 5%)
                if (Math.abs(percentDiff) < 5.0) {
                    validation.put("priceMatch", "GOOD");
                    matchedPrice++;
                } else if (Math.abs(percentDiff) < 10.0) {
                    validation.put("priceMatch", "FAIR");
                } else {
                    validation.put("priceMatch", "POOR");
                }

                // List all patterns found
                List<String> allPatterns = signals.stream()
                        .map(s -> s.getPattern().name())
                        .collect(Collectors.toList());
                validation.put("allPatterns", allPatterns);
            }

            validations.add(validation);
        }

        // Calculate statistics
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("totalStocks", allStocks.size());
        statistics.put("stocksWithPatterns", hasPatterns);
        statistics.put("stocksWithoutPatterns", noPatterns);
        statistics.put("goodPriceMatches", matchedPrice);
        statistics.put("patternDetectionRate",
                String.format("%.1f%%", (hasPatterns * 100.0 / allStocks.size())));
        statistics.put("priceMatchRate",
                hasPatterns > 0 ? String.format("%.1f%%", (matchedPrice * 100.0 / hasPatterns)) : "N/A");

        return ResponseEntity.ok(Map.of(
                "date", date,
                "interval", interval,
                "statistics", statistics,
                "validations", validations
        ));
    }

    /**
     * Validate a specific stock
     * GET /api/entry-validation/validate-stock?symbol=RR&date=2025-10-01&interval=5
     */
    @GetMapping("/validate-stock")
    public ResponseEntity<?> validateStock(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval) {

        log.info("Validating entry price for {} on {}", symbol, date);

        // Get stock data
        Optional<StockData> stockDataOpt = stockDataRepository.findByStockInfoAndDate(symbol, date);

        if (stockDataOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "message", "No stock data found"
            ));
        }

        StockData stock = stockDataOpt.get();
        double entryPrice = stock.getEntryPrice();

        // Get pattern signals
        List<EntrySignal> signals = entrySignalService.findEntrySignals(symbol, date, interval);

        // ✅ SORT SIGNALS BY TIMESTAMP (chronological order)
        if (!signals.isEmpty()) {
            signals.sort(Comparator.comparing(EntrySignal::getTimestamp));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("date", date);
        result.put("entryPrice", entryPrice);
        result.put("tickCount", stock.getStocksPrices().size());

        if (signals.isEmpty()) {
            result.put("status", "NO_PATTERNS");
            result.put("message", "No patterns detected for this stock");
        } else {
            result.put("status", "PATTERNS_FOUND");
            result.put("patternCount", signals.size());

            // Show all signals
            List<Map<String, Object>> signalDetails = signals.stream()
                    .map(signal -> {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("pattern", signal.getPattern().name());
                        details.put("entryPrice", signal.getEntryPrice());
                        details.put("priceDiff", signal.getEntryPrice() - entryPrice);
                        details.put("percentDiff",
                                String.format("%.2f%%", ((signal.getEntryPrice() - entryPrice) / entryPrice) * 100));
                        details.put("confidence", signal.getConfidence());
                        details.put("signalQuality", signal.getSignalQuality());
                        details.put("direction", signal.getDirection());
                        details.put("stopLoss", signal.getStopLoss());
                        details.put("target", signal.getTarget());
                        details.put("riskRewardRatio", signal.getRiskRewardRatio());
                        details.put("timestampIsrael", signal.getTimestampIsrael());
                        return details;
                    })
                    .collect(Collectors.toList());

            result.put("signals", signalDetails);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get stocks with no pattern detection
     * GET /api/entry-validation/no-patterns?date=2025-10-01&interval=5
     */
    @GetMapping("/no-patterns")
    public ResponseEntity<?> getStocksWithoutPatterns(
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval) {

        List<StockData> allStocks = stockDataRepository.findByDate(date);

        List<Map<String, Object>> noPatternStocks = new ArrayList<>();

        for (StockData stock : allStocks) {
            String symbol = stock.getStockInfo();
            List<EntrySignal> signals = entrySignalService.findEntrySignals(symbol, date, interval);

            if (signals.isEmpty()) {
                noPatternStocks.add(Map.of(
                        "symbol", symbol,
                        "entryPrice", stock.getEntryPrice(),
                        "tickCount", stock.getStocksPrices().size()
                ));
            }
        }

        return ResponseEntity.ok(Map.of(
                "date", date,
                "interval", interval,
                "totalStocks", allStocks.size(),
                "stocksWithoutPatterns", noPatternStocks.size(),
                "stocks", noPatternStocks
        ));
    }

    /**
     * Get best matches (entry price close to pattern signal)
     * GET /api/entry-validation/best-matches?date=2025-10-01&interval=5
     */
    @GetMapping("/best-matches")
    public ResponseEntity<?> getBestMatches(
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "5") int limit) {

        List<StockData> allStocks = stockDataRepository.findByDate(date);

        List<Map<String, Object>> matches = new ArrayList<>();

        for (StockData stock : allStocks) {
            String symbol = stock.getStockInfo();
            double entryPrice = stock.getEntryPrice();

            List<EntrySignal> signals = entrySignalService.findEntrySignals(symbol, date, interval);

            if (!signals.isEmpty()) {
                EntrySignal closestSignal = signals.stream()
                        .min(Comparator.comparingDouble(s ->
                                Math.abs(s.getEntryPrice() - entryPrice)))
                        .orElse(signals.get(0));

                double percentDiff = Math.abs(((closestSignal.getEntryPrice() - entryPrice) / entryPrice) * 100);

                matches.add(Map.of(
                        "symbol", symbol,
                        "entryPrice", entryPrice,
                        "pattern", closestSignal.getPattern().name(),
                        "signalPrice", closestSignal.getEntryPrice(),
                        "percentDiff", percentDiff,
                        "confidence", closestSignal.getConfidence(),
                        "signalQuality", closestSignal.getSignalQuality()
                ));
            }
        }

        // Sort by best match (lowest percent difference)
        matches.sort(Comparator.comparingDouble(m -> (Double) m.get("percentDiff")));

        return ResponseEntity.ok(Map.of(
                "date", date,
                "interval", interval,
                "bestMatches", matches.stream().limit(limit).collect(Collectors.toList())
        ));
    }
}