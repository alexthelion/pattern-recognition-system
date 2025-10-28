package hashita.service;

import hashita.data.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * FIXED VERSION: More responsive trend detection
 *
 * Changes from original:
 * 1. Shorter medium-term lookback (30 vs 50)
 * 2. More weight on recent price action
 * 3. Debug logging to track decisions
 * 4. ADX capped and validated
 */
@Service
@Slf4j
public class TrendAnalysisService {

    // Set to true for debugging
    private static final boolean DEBUG_MODE = true;

    public enum TrendDirection {
        UPTREND,
        DOWNTREND,
        NEUTRAL
    }

    /**
     * Determine trend using multiple timeframes
     */
    public TrendDirection determineTrend(List<Candle> candles) {
        if (candles.size() < 30) {
            return TrendDirection.NEUTRAL;
        }

        if (DEBUG_MODE) {
            log.info("╔═══════════════════════════════════════════════╗");
            log.info("║         TREND ANALYSIS DEBUG                  ║");
            log.info("╠═══════════════════════════════════════════════╣");
            log.info("║ Total candles: {}", candles.size());
            log.info("║ First: ${} at {}",
                    String.format("%.2f", candles.get(0).getClose()),
                    candles.get(0).getTimestamp().toString().substring(11, 16));
            log.info("║ Last:  ${} at {}",
                    String.format("%.2f", candles.get(candles.size()-1).getClose()),
                    candles.get(candles.size()-1).getTimestamp().toString().substring(11, 16));

            double priceChange = ((candles.get(candles.size()-1).getClose() -
                    candles.get(0).getClose()) /
                    candles.get(0).getClose()) * 100;
            log.info("║ Overall change: {}%", String.format("%.2f", priceChange));
            log.info("╚═══════════════════════════════════════════════╝");
        }

        // Use shorter lookback to be more responsive
        TrendDirection shortTermTrend = determineShortTermTrend(candles, 20);
        TrendDirection mediumTermTrend = determineMediumTermTrend(candles, 30); // Was 50!

        if (DEBUG_MODE) {
            log.info("Short-term (20 bars ~100min): {}", shortTermTrend);
            log.info("Medium-term (30 bars ~150min): {}", mediumTermTrend);
        }

        // If both agree, high confidence
        if (shortTermTrend == mediumTermTrend) {
            if (DEBUG_MODE) {
                log.info("✅ Both timeframes agree: {}", shortTermTrend);
            }
            return shortTermTrend;
        }

        // FIX: Prioritize short-term (more responsive)
        // Original code trusted medium-term, but that can be stale
        if (DEBUG_MODE) {
            log.info("⚠️ Timeframes disagree! Using short-term (more recent): {}", shortTermTrend);
        }
        return shortTermTrend;
    }

    /**
     * Short term trend (last 20 candles = ~100 minutes for 5-min bars)
     */
    private TrendDirection determineShortTermTrend(List<Candle> candles, int lookback) {
        if (candles.size() < lookback) {
            return TrendDirection.NEUTRAL;
        }

        int size = candles.size();
        List<Candle> recent = candles.subList(size - lookback, size);

        // Compare first half vs second half
        int midpoint = lookback / 2;
        double firstHalfAvg = recent.subList(0, midpoint).stream()
                .mapToDouble(Candle::getClose)
                .average()
                .orElse(0);

        double secondHalfAvg = recent.subList(midpoint, lookback).stream()
                .mapToDouble(Candle::getClose)
                .average()
                .orElse(0);

        double percentChange = ((secondHalfAvg - firstHalfAvg) / firstHalfAvg) * 100;

        if (DEBUG_MODE) {
            log.info("  Short-term: ${} → ${} = {}%",
                    String.format("%.2f", firstHalfAvg),
                    String.format("%.2f", secondHalfAvg),
                    String.format("%.2f", percentChange));
        }

        // Need significant change to call it a trend
        if (percentChange > 1.5) {
            return TrendDirection.UPTREND;
        } else if (percentChange < -1.5) {
            return TrendDirection.DOWNTREND;
        } else {
            return TrendDirection.NEUTRAL;
        }
    }

    /**
     * Medium term trend (REDUCED to 30 candles = ~150 min for 5-min bars)
     * More reliable than short, but now more responsive than before
     */
    private TrendDirection determineMediumTermTrend(List<Candle> candles, int lookback) {
        if (candles.size() < lookback) {
            // If we don't have enough data, fall back to all available candles
            lookback = candles.size();
        }

        int size = candles.size();
        List<Candle> recent = candles.subList(size - lookback, size);

        // FIX: Weight recent data more heavily
        // Instead of early third vs late third,
        // Compare first quarter vs last quarter
        int quarterSize = lookback / 4;

        double earlyQuarterAvg = recent.subList(0, quarterSize).stream()
                .mapToDouble(Candle::getClose)
                .average()
                .orElse(0);

        double lateQuarterAvg = recent.subList(lookback - quarterSize, lookback).stream()
                .mapToDouble(Candle::getClose)
                .average()
                .orElse(0);

        double percentChange = ((lateQuarterAvg - earlyQuarterAvg) / earlyQuarterAvg) * 100;

        if (DEBUG_MODE) {
            log.info("  Medium-term: ${} → ${} = {}%",
                    String.format("%.2f", earlyQuarterAvg),
                    String.format("%.2f", lateQuarterAvg),
                    String.format("%.2f", percentChange));
        }

        // Medium term threshold (keep at 3%)
        if (percentChange > 3.0) {
            return TrendDirection.UPTREND;
        } else if (percentChange < -3.0) {
            return TrendDirection.DOWNTREND;
        } else {
            return TrendDirection.NEUTRAL;
        }
    }

    /**
     * Calculate Simple Moving Average
     */
    private double calculateSMA(List<Candle> candles, int period) {
        if (candles.isEmpty()) return 0;

        int count = Math.min(period, candles.size());
        double sum = 0;

        for (int i = candles.size() - count; i < candles.size(); i++) {
            sum += candles.get(i).getClose();
        }

        return sum / count;
    }

    /**
     * Calculate ADX (Average Directional Index) - FIXED VERSION
     * Returns value 0-100
     * < 20: Weak/choppy market
     * 20-25: Developing trend
     * > 25: Strong trend
     */
    public double calculateADX(List<Candle> candles) {
        if (candles.size() < 15) {
            return 0;
        }

        int period = 14;
        double smoothing = period;

        // Calculate +DM, -DM, and TR for each candle
        double plusDMSum = 0;
        double minusDMSum = 0;
        double trSum = 0;

        for (int i = candles.size() - period; i < candles.size(); i++) {
            if (i == 0) continue;

            Candle current = candles.get(i);
            Candle previous = candles.get(i - 1);

            // Calculate directional movement
            double highDiff = current.getHigh() - previous.getHigh();
            double lowDiff = previous.getLow() - current.getLow();

            double plusDM = (highDiff > lowDiff && highDiff > 0) ? highDiff : 0;
            double minusDM = (lowDiff > highDiff && lowDiff > 0) ? lowDiff : 0;

            // Calculate true range
            double tr = Math.max(
                    current.getRange(),
                    Math.max(
                            Math.abs(current.getHigh() - previous.getClose()),
                            Math.abs(current.getLow() - previous.getClose())
                    )
            );

            plusDMSum += plusDM;
            minusDMSum += minusDM;
            trSum += tr;
        }

        // Calculate smoothed values
        double smoothPlusDM = plusDMSum / smoothing;
        double smoothMinusDM = minusDMSum / smoothing;
        double smoothTR = trSum / smoothing;

        if (smoothTR == 0) {
            log.warn("⚠️ ADX: TR is zero! Returning 0");
            return 0;
        }

        // Calculate directional indicators
        double plusDI = 100 * smoothPlusDM / smoothTR;
        double minusDI = 100 * smoothMinusDM / smoothTR;

        // Calculate DX
        double diSum = plusDI + minusDI;
        if (diSum == 0) {
            log.warn("⚠️ ADX: DI sum is zero! Returning 0");
            return 0;
        }

        double dx = 100 * Math.abs(plusDI - minusDI) / diSum;

        // FIXES:
        // 1. Cap at 95 (99-100 is impossible with real data)
        dx = Math.min(95, dx);

        // 2. Add validation logging
        if (DEBUG_MODE) {
            log.info("  ADX: +DI={}, -DI={}, DX={}",
                    String.format("%.2f", plusDI),
                    String.format("%.2f", minusDI),
                    String.format("%.2f", dx));
        }

        // 3. Warn on suspicious values
        if (dx > 90) {
            log.warn("⚠️ ADX {} is unusually high! Check data quality.", String.format("%.1f", dx));
        }
        if (plusDI == 0 || minusDI == 0) {
            log.warn("⚠️ One DI is zero (+DI={}, -DI={}) - unusual!",
                    String.format("%.2f", plusDI), String.format("%.2f", minusDI));
        }

        return dx;
    }

    /**
     * Check if market is choppy (low ADX)
     */
    public boolean isChoppyMarket(List<Candle> candles) {
        double adx = calculateADX(candles);
        return adx < 20;
    }

    /**
     * Check if market has strong trend (high ADX)
     */
    public boolean isStrongTrend(List<Candle> candles) {
        double adx = calculateADX(candles);
        return adx > 25;
    }
}