package hashita.data.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

@Document("daily_alerts_enhanced")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyAlertEnhanced {

    @Id
    private String id;

    @Indexed
    @Builder.Default
    private String date = LocalDate.now().toString();

    @Builder.Default
    private Instant timestamp = Instant.now();

    private String source;

    @Indexed(unique = true)
    private String tickerData;
}