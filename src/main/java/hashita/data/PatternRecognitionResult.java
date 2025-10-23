package hashita.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Represents a detected candlestick pattern
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PatternRecognitionResult {
    
    private CandlePattern pattern;
    private String symbol;
    private Instant timestamp;
    private int intervalMinutes;
    
    // The candles involved in the pattern (ordered from oldest to newest)
    private List<Candle> candles;
    
    // Confidence score (0-100)
    private double confidence;
    
    // Additional context
    private String description;
    
    // Price levels
    private double priceAtDetection;
    private double supportLevel;
    private double resistanceLevel;
    
    // Volume context
    private double averageVolume;
    private boolean hasVolumeConfirmation;
    
    public boolean isBullish() {
        return pattern.isBullish();
    }
    
    public boolean isBearish() {
        return pattern.isBearish();
    }
    
    public String getSignal() {
        if (isBullish()) {
            return "BUY";
        } else if (isBearish()) {
            return "SELL";
        }
        return "NEUTRAL";
    }
}
