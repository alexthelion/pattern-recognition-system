package hashita.data.entities;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Document("ticker_volumes")
@CompoundIndexes({
        @CompoundIndex(name = "symbol_date_unique", def = "{'stockInfo': 1, 'date': 1}", unique = true)
})
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TickerVolume {
    @Id
    private String id;

    @Indexed
    private String stockInfo;

    @Indexed
    private String date;

    private List<IntervalVolume> intervalVolumes;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class IntervalVolume {
        /** Interval start time in "HH:mm:ss" format (exchange timezone) */
        private String intervalStart;

        /** Interval end time in "HH:mm:ss" format (exchange timezone) */
        private String intervalEnd;

        /** Interval start timestamp in epoch seconds */
        private long startEpochSec;

        /** Interval end timestamp in epoch seconds */
        private long endEpochSec;

        /** Interval start timestamp in epoch milliseconds */
        private long startEpochMillis;

        /** Interval end timestamp in epoch milliseconds */
        private long endEpochMillis;

        /** Total volume for this interval */
        private double volume;

        /** Length of interval in minutes (1 or 5) */
        private int intervalMinutes;
    }
}