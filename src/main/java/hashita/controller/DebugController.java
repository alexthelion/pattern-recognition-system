package hashita.controller;

import hashita.data.Candle;
import hashita.data.TickData;
import hashita.data.entities.StockData;
import hashita.repository.StockDataRepository;
import hashita.service.CandleBuilderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
@Slf4j
public class DebugController {
    
    private final StockDataRepository stockDataRepository;
    private final CandleBuilderService candleBuilderService;
    
    /**
     * Get raw tick data for a specific date
     */
    @GetMapping("/ticks")
    public ResponseEntity<?> getRawTicks(
            @RequestParam String symbol,
            @RequestParam String date) {
        
        try {
            Optional<StockData> stockDataOpt = stockDataRepository.findByStockInfoAndDate(symbol, date);
            
            if (stockDataOpt.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "tickCount", 0,
                    "message", "No data found",
                    "ticks", Collections.emptyList()
                ));
            }
            
            StockData stockData = stockDataOpt.get();
            List<TickData> ticks = stockData.getStocksPrices();
            
            if (ticks == null || ticks.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "tickCount", 0,
                    "message", "No tick data",
                    "ticks", Collections.emptyList()
                ));
            }
            
            List<Map<String, Object>> tickDetails = ticks.stream()
                .limit(200)
                .map(tick -> {
                    Map<String, Object> t = new HashMap<>();
                    t.put("time", tick.time());
                    t.put("price", tick.price());
                    try {
                        t.put("timestamp", tick.getParsedTimestamp().toString());
                    } catch (Exception e) {
                        t.put("timestamp", "parse_error");
                    }
                    return t;
                })
                .collect(Collectors.toList());
            
            double minPrice = ticks.stream().mapToDouble(TickData::price).min().orElse(0);
            double maxPrice = ticks.stream().mapToDouble(TickData::price).max().orElse(0);
            double avgPrice = ticks.stream().mapToDouble(TickData::price).average().orElse(0);
            
            Map<String, Object> response = new HashMap<>();
            response.put("symbol", symbol);
            response.put("date", date);
            response.put("tickCount", ticks.size());
            response.put("entryPrice", stockData.getEntryPrice());
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("minPrice", minPrice);
            stats.put("maxPrice", maxPrice);
            stats.put("avgPrice", avgPrice);
            response.put("statistics", stats);
            
            response.put("ticks", tickDetails);
            if (ticks.size() > 200) {
                response.put("note", "Showing first 200 of " + ticks.size() + " ticks");
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Get built candles
     */
    @GetMapping("/candles")
    public ResponseEntity<?> getCandles(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam(defaultValue = "5") int interval) {
        
        try {
            // Get stock data
            Optional<StockData> stockDataOpt = stockDataRepository.findByStockInfoAndDate(symbol, date);
            
            if (stockDataOpt.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "interval", interval,
                    "candleCount", 0,
                    "message", "No stock data found"
                ));
            }
            
            StockData stockData = stockDataOpt.get();
            
            // Build candles (without volume data for now - just pass empty list)
            List<Candle> candles = candleBuilderService.buildCandles(
                stockData.getStocksPrices(),
                Collections.emptyList(),  // No volume data for debug
                interval
            );
            
            if (candles.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "interval", interval,
                    "candleCount", 0,
                    "message", "No candles built"
                ));
            }
            
            List<Map<String, Object>> candleDetails = candles.stream()
                .map(candle -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("timestamp", candle.getTimestamp().toString());
                    details.put("timestampUTC", candle.getTimestamp().atZone(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    details.put("open", candle.getOpen());
                    details.put("high", candle.getHigh());
                    details.put("low", candle.getLow());
                    details.put("close", candle.getClose());
                    details.put("volume", candle.getVolume());
                    return details;
                })
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(Map.of(
                "symbol", symbol,
                "date", date,
                "intervalMinutes", interval,
                "candleCount", candles.size(),
                "candles", candleDetails
            ));
            
        } catch (Exception e) {
            log.error("Error: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Compare with chart
     */
    @PostMapping("/compare")
    public ResponseEntity<?> compareWithChart(@RequestBody Map<String, Object> request) {
        try {
            String symbol = (String) request.get("symbol");
            String date = (String) request.get("date");
            Integer interval = (Integer) request.getOrDefault("interval", 5);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chartData = (List<Map<String, Object>>) request.get("chartData");

            // Get stock data
            Optional<StockData> stockDataOpt = stockDataRepository.findByStockInfoAndDate(symbol, date);

            if (stockDataOpt.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "error", "No stock data found for " + symbol + " on " + date
                ));
            }

            // Build candles
            List<Candle> candles = candleBuilderService.buildCandles(
                    stockDataOpt.get().getStocksPrices(),
                    Collections.emptyList(),
                    interval
            );

            List<Map<String, Object>> comparisons = new ArrayList<>();

            for (Map<String, Object> chartPoint : chartData) {
                String timeStr = (String) chartPoint.get("time");
                Object priceObj = chartPoint.get("price");
                double chartPrice = priceObj instanceof Integer
                        ? ((Integer) priceObj).doubleValue()
                        : (Double) priceObj;

                // Parse chart time as Israel timezone (UTC+3), then convert to UTC
                Instant timestamp = LocalDateTime.parse(date + "T" + timeStr + ":00",
                                DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        .atZone(ZoneId.of("Asia/Jerusalem"))
                        .toInstant();

                Optional<Candle> closestCandle = candles.stream()
                        .filter(c -> Math.abs(c.getTimestamp().toEpochMilli() - timestamp.toEpochMilli()) < 5 * 60 * 1000)
                        .min(Comparator.comparingLong(c ->
                                Math.abs(c.getTimestamp().toEpochMilli() - timestamp.toEpochMilli())));

                Map<String, Object> comparison = new LinkedHashMap<>();
                comparison.put("chartTime", timeStr);
                comparison.put("chartPrice", chartPrice);

                if (closestCandle.isPresent()) {
                    Candle candle = closestCandle.get();
                    comparison.put("serviceTime", candle.getTimestamp().atZone(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ofPattern("HH:mm")));
                    comparison.put("serviceClose", candle.getClose());
                    comparison.put("serviceOpen", candle.getOpen());
                    comparison.put("serviceHigh", candle.getHigh());
                    comparison.put("serviceLow", candle.getLow());

                    double diff = candle.getClose() - chartPrice;
                    comparison.put("difference", String.format("%.2f", diff));
                    comparison.put("percentDiff", String.format("%.2f%%", (diff / chartPrice) * 100));

                    if (Math.abs(diff) > 0.10) {
                        comparison.put("status", "MISMATCH");
                    } else if (Math.abs(diff) > 0.05) {
                        comparison.put("status", "WARNING");
                    } else {
                        comparison.put("status", "OK");
                    }
                } else {
                    comparison.put("status", "NO_CANDLE");
                }

                comparisons.add(comparison);
            }

            return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "date", date,
                    "interval", interval,
                    "comparisons", comparisons
            ));

        } catch (Exception e) {
            log.error("Error: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }
}
