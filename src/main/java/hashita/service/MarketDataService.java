package hashita.service;

import hashita.service.ibkr.IBKRClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Service to get real-time market prices using IBKRClient
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataService {

    private final IBKRClient ibkrClient;

    /**
     * Get real-time price for a symbol
     *
     * @param symbol Stock symbol
     * @return Current price, or null if unavailable
     */
    public Double getRealTimePrice(String symbol) {
        try {
            return ibkrClient.getRealTimePrice(symbol);
        } catch (Exception e) {
            log.error("Error getting real-time price for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Get real-time prices for multiple symbols in parallel
     *
     * @param symbols List of symbols
     * @return Map of symbol -> price
     */
    public Map<String, Double> getRealTimePrices(List<String> symbols) {
        Map<String, Double> prices = new ConcurrentHashMap<>();

        if (symbols == null || symbols.isEmpty()) {
            return prices;
        }

        // Limit parallelism to 5 to avoid overwhelming IBKR
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(5, symbols.size()));

        try {
            List<CompletableFuture<Void>> futures = symbols.stream()
                    .map(symbol -> CompletableFuture.runAsync(() -> {
                        Double price = getRealTimePrice(symbol);
                        if (price != null) {
                            prices.put(symbol, price);
                        }
                    }, executor))
                    .collect(java.util.stream.Collectors.toList());

            // Wait for all to complete (with timeout)
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("Error fetching multiple prices: {}", e.getMessage());
        } finally {
            executor.shutdown();
        }

        return prices;
    }

    /**
     * Clear price cache
     */
    public void clearCache() {
        ibkrClient.clearPriceCache();
    }

    /**
     * Clear cache for specific symbol
     */
    public void clearCache(String symbol) {
        ibkrClient.clearPriceCache(symbol);
    }
}