package hashita.data;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record TickData(String time, Double price) {

    // IMPORTANT: Tick data timestamps are stored in Israel timezone
    private static final ZoneId ISRAEL_ZONE = ZoneId.of("Asia/Jerusalem");
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @JsonIgnore
    public Instant getParsedTimestamp() {
        // Parse the timestamp as Israel local time, then convert to UTC
        return LocalDateTime.parse(time, FORMATTER)
                .atZone(ISRAEL_ZONE)  // ✅ FIXED: Use Israel timezone
                .toInstant();
    }
}