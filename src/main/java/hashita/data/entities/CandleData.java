package hashita.data.entities;

import hashita.data.Candle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * Stores pre-aggregated candles from IBKR
 * This replaces the need to aggregate ticks from stock_daily collection
 */
@Document(collection = "candles_daily")
@CompoundIndexes({
        @CompoundIndex(name = "symbol_date_interval", def = "{'symbol': 1, 'date': 1, 'intervalMinutes': 1}", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandleData {

    @Id
    private String id;

    /**
     * Stock symbol (e.g., "MGN", "DVLT")
     */
    private String symbol;

    /**
     * Trading date in yyyy-MM-dd format (e.g., "2025-10-07")
     */
    private String date;

    /**
     * Candle interval in minutes (e.g., 1, 5, 15, 30, 60)
     */
    private int intervalMinutes;

    /**
     * List of candles for this symbol/date/interval
     * Each candle has timestamp (UTC), OHLCV
     */
    private List<Candle> candles;

    /**
     * Data source (e.g., "IBKR", "FINNHUB")
     */
    @Builder.Default
    private String source = "IBKR";

    /**
     * When this data was fetched/stored
     */
    private Long fetchedAt;

    /**
     * Total number of candles
     */
    public int getCandleCount() {
        return candles != null ? candles.size() : 0;
    }
}