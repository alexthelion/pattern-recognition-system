package hashita.data.entities;

import hashita.data.TickData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Document("stock_daily")
@CompoundIndexes({
        @CompoundIndex(name = "symbol_date_unique", def = "{'stockInfo': 1, 'date': 1}", unique = true)
})
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StockData {
    @Id
    private String id;
    @Indexed
    private String stockInfo;
    @Indexed
    private String date;
    private double entryPrice;
    private List<TickData> stocksPrices;
    private List<Double> stockVolumes;
}
