package hashita.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a candlestick with OHLCV data
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Candle {
    
    private Instant timestamp;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private int intervalMinutes;
    
    public boolean isBullish() {
        return close > open;
    }
    
    public boolean isBearish() {
        return close < open;
    }
    
    public boolean isDoji() {
        double body = Math.abs(close - open);
        double range = high - low;
        return range > 0 && (body / range) < 0.1;
    }
    
    public double getBodySize() {
        return Math.abs(close - open);
    }
    
    public double getUpperShadow() {
        return high - Math.max(open, close);
    }
    
    public double getLowerShadow() {
        return Math.min(open, close) - low;
    }
    
    public double getRange() {
        return high - low;
    }
    
    public double getBodyPercentage() {
        double range = getRange();
        return range > 0 ? (getBodySize() / range) * 100 : 0;
    }
    
    public double getUpperShadowPercentage() {
        double range = getRange();
        return range > 0 ? (getUpperShadow() / range) * 100 : 0;
    }
    
    public double getLowerShadowPercentage() {
        double range = getRange();
        return range > 0 ? (getLowerShadow() / range) * 100 : 0;
    }
    
    public boolean hasSmallBody() {
        return getBodyPercentage() < 30;
    }
    
    public boolean hasLargeBody() {
        return getBodyPercentage() > 70;
    }
}
