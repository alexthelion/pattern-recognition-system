package hashita.controller;

import hashita.service.EntrySignalService;
import hashita.service.EntrySignalService.EntrySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/entry-signals")
@RequiredArgsConstructor
@Slf4j
public class EntrySignalController {

    private final EntrySignalService entrySignalService;

    @GetMapping
    public ResponseEntity<?> getEntrySignals(
            @RequestParam String symbol,
            @RequestParam String date,
            @RequestParam(defaultValue = "15") int interval) {

        List<EntrySignal> signals = entrySignalService.findEntrySignals(symbol, date, interval);

        // ✅ SORT BY TIMESTAMP (chronological order)
        signals.sort(Comparator.comparing(EntrySignal::getTimestamp));

        return ResponseEntity.ok(Map.of(
                "symbol", symbol,
                "date", date,
                "intervalMinutes", interval,
                "signals", signals,
                "count", signals.size()
        ));
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scanMultipleStocks(@RequestBody Map<String, Object> request) {

        @SuppressWarnings("unchecked")
        List<String> symbols = (List<String>) request.get("symbols");
        String date = (String) request.get("date");
        Integer interval = (Integer) request.getOrDefault("interval", 15);

        Map<String, List<EntrySignal>> signals = entrySignalService.scanMultipleStocks(
                symbols, date, interval
        );

        // ✅ SORT EACH STOCK'S SIGNALS BY TIMESTAMP
        signals.values().forEach(stockSignals ->
                stockSignals.sort(Comparator.comparing(EntrySignal::getTimestamp))
        );

        return ResponseEntity.ok(Map.of(
                "scannedSymbols", symbols,
                "date", date,
                "intervalMinutes", interval,
                "signalsBySymbol", signals,
                "stocksWithSignals", signals.size()
        ));
    }

    @PostMapping("/best")
    public ResponseEntity<?> getBestOpportunities(@RequestBody Map<String, Object> request) {

        @SuppressWarnings("unchecked")
        List<String> symbols = (List<String>) request.get("symbols");
        String date = (String) request.get("date");
        Integer interval = (Integer) request.getOrDefault("interval", 15);
        Integer maxResults = (Integer) request.getOrDefault("maxResults", 10);

        List<EntrySignal> bestSignals = entrySignalService.findBestOpportunities(
                symbols, date, interval, maxResults
        );

        // Note: Keep best signals sorted by quality, not time

        return ResponseEntity.ok(Map.of(
                "stocksScanned", symbols.size(),
                "date", date,
                "intervalMinutes", interval,
                "bestOpportunities", bestSignals,
                "count", bestSignals.size()
        ));
    }
}