package hashita.service;

import hashita.data.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ✅ DEBUGGABLE VERSION: Detects CHART PATTERNS with extensive logging
 *
 * This version logs WHY patterns fail detection
 */
@Service
@Slf4j
public class ChartPatternDetector {

    /**
     * Detect falling wedge (BULLISH BREAKOUT)
     *
     * ✅ RELAXED THRESHOLDS for better detection
     */
    public boolean isFallingWedge(List<Candle> candles) {
        log.debug("🔍 Checking FALLING WEDGE with {} candles", candles.size());

        if (candles.size() < 20) {
            log.debug("   ❌ Not enough candles: {} < 20", candles.size());
            return false;
        }

        List<SwingPoint> swings = findSwingPoints(candles);
        log.debug("   Found {} swing points", swings.size());

        if (swings.size() < 4) {
            log.debug("   ❌ Not enough swing points: {} < 4", swings.size());
            return false;
        }

        List<SwingPoint> highs = new ArrayList<>();
        List<SwingPoint> lows = new ArrayList<>();

        for (SwingPoint swing : swings) {
            if (swing.isHigh) {
                highs.add(swing);
            } else {
                lows.add(swing);
            }
        }

        log.debug("   Swing highs: {}, Swing lows: {}", highs.size(), lows.size());

        if (highs.size() < 2 || lows.size() < 2) {
            log.debug("   ❌ Not enough highs ({}) or lows ({})", highs.size(), lows.size());
            return false;
        }

        double highSlope = calculateSlope(highs);
        double lowSlope = calculateSlope(lows);

        log.debug("   High slope: {}, Low slope: {}",
                String.format("%.6f", highSlope),
                String.format("%.6f", lowSlope));

        // ✅ RELAXED: Allow slight uptrend in lows (was: both must be negative)
        boolean bothDowntrend = highSlope < 0 && lowSlope < 0.001; // Allow slight up
        boolean converging = Math.abs(lowSlope) < Math.abs(highSlope);
        boolean nearResistance = isNearUpperTrendline(candles, highs);

        log.debug("   Both downtrend: {}, Converging: {}, Near resistance: {}",
                bothDowntrend, converging, nearResistance);

        if (bothDowntrend && converging && nearResistance) {
            log.info("✅ FALLING WEDGE DETECTED!");
            return true;
        }

        log.debug("   ❌ Pattern not confirmed");
        return false;
    }

    /**
     * Detect bull flag (CONTINUATION PATTERN)
     *
     * ✅ RELAXED THRESHOLDS
     */
    public boolean isBullFlag(List<Candle> candles) {
        log.debug("🔍 Checking BULL FLAG with {} candles", candles.size());

        if (candles.size() < 15) {
            log.debug("   ❌ Not enough candles: {} < 15", candles.size());
            return false;
        }

        // ✅ RELAXED: Reduced pole gain requirement from 5% to 3%
        int poleLength = 5;
        double poleGain = calculatePriceChange(candles, candles.size() - 15, poleLength);

        log.debug("   Pole gain: {}%", String.format("%.2f", poleGain));

        if (poleGain < 3.0) {
            log.debug("   ❌ Pole gain too small: {}% < 3%", String.format("%.2f", poleGain));
            return false;
        }

        int flagLength = 10;
        List<Candle> flagCandles = candles.subList(candles.size() - flagLength, candles.size());

        double flagRange = calculateRangePercent(flagCandles);
        log.debug("   Flag range: {}%", String.format("%.2f", flagRange));

        // ✅ RELAXED: Increased flag range from 3% to 5%
        if (flagRange > 5.0) {
            log.debug("   ❌ Flag range too wide: {}% > 5%", String.format("%.2f", flagRange));
            return false;
        }

        double flagSlope = calculateOverallSlope(flagCandles);
        log.debug("   Flag slope: {}%", String.format("%.2f", flagSlope));

        // ✅ RELAXED: Allow steeper pullback (-4% to +1%)
        if (flagSlope < -4.0 || flagSlope > 1.0) {
            log.debug("   ❌ Flag slope out of range: {}%", String.format("%.2f", flagSlope));
            return false;
        }

        Candle lastCandle = flagCandles.get(flagCandles.size() - 1);
        double flagHigh = flagCandles.stream().mapToDouble(Candle::getHigh).max().orElse(0);

        // ✅ RELAXED: Reduced breakout threshold from 98% to 95%
        boolean nearBreakout = lastCandle.getClose() > (flagHigh * 0.95);

        log.debug("   Near breakout: {} (last={}, flagHigh={})",
                nearBreakout,
                String.format("%.2f", lastCandle.getClose()),
                String.format("%.2f", flagHigh));

        if (nearBreakout) {
            log.info("✅ BULL FLAG DETECTED!");
            return true;
        }

        log.debug("   ❌ Not near breakout");
        return false;
    }

    /**
     * Detect ascending triangle (BULLISH)
     *
     * ✅ RELAXED THRESHOLDS
     */
    public boolean isAscendingTriangle(List<Candle> candles) {
        log.debug("🔍 Checking ASCENDING TRIANGLE with {} candles", candles.size());

        if (candles.size() < 20) {
            log.debug("   ❌ Not enough candles: {} < 20", candles.size());
            return false;
        }

        List<SwingPoint> swings = findSwingPoints(candles);
        log.debug("   Found {} swing points", swings.size());

        if (swings.size() < 4) {
            log.debug("   ❌ Not enough swing points: {} < 4", swings.size());
            return false;
        }

        List<SwingPoint> highs = new ArrayList<>();
        List<SwingPoint> lows = new ArrayList<>();

        for (SwingPoint swing : swings) {
            if (swing.isHigh) highs.add(swing);
            else lows.add(swing);
        }

        log.debug("   Swing highs: {}, Swing lows: {}", highs.size(), lows.size());

        if (highs.size() < 2 || lows.size() < 2) {
            log.debug("   ❌ Not enough highs ({}) or lows ({})", highs.size(), lows.size());
            return false;
        }

        double highSlope = calculateSlope(highs);
        double lowSlope = calculateSlope(lows);

        log.debug("   High slope: {}, Low slope: {}",
                String.format("%.6f", highSlope),
                String.format("%.6f", lowSlope));

        // ✅ RELAXED: Increased tolerance for flat resistance
        boolean flatResistance = Math.abs(highSlope) < 0.002; // Was 0.0005
        boolean risingSupport = lowSlope > 0.0005; // Was 0.001
        boolean nearResistance = isNearUpperTrendline(candles, highs);

        log.debug("   Flat resistance: {}, Rising support: {}, Near resistance: {}",
                flatResistance, risingSupport, nearResistance);

        if (flatResistance && risingSupport && nearResistance) {
            log.info("✅ ASCENDING TRIANGLE DETECTED!");
            return true;
        }

        log.debug("   ❌ Pattern not confirmed");
        return false;
    }

    /**
     * Detect double bottom (BULLISH REVERSAL)
     *
     * ✅ RELAXED THRESHOLDS
     */
    public boolean isDoubleBottom(List<Candle> candles) {
        log.debug("🔍 Checking DOUBLE BOTTOM with {} candles", candles.size());

        if (candles.size() < 15) {
            log.debug("   ❌ Not enough candles: {} < 15", candles.size());
            return false;
        }

        List<SwingPoint> lows = findSwingPoints(candles).stream()
                .filter(s -> !s.isHigh)
                .toList();

        log.debug("   Found {} swing lows", lows.size());

        if (lows.size() < 2) {
            log.debug("   ❌ Not enough swing lows: {} < 2", lows.size());
            return false;
        }

        SwingPoint low1 = lows.get(lows.size() - 2);
        SwingPoint low2 = lows.get(lows.size() - 1);

        // ✅ RELAXED: Increased price tolerance from 2% to 3%
        double priceDiff = Math.abs(low1.price - low2.price) / low1.price * 100;

        log.debug("   Low1: ${} at index {}, Low2: ${} at index {}, Diff: {}%",
                String.format("%.2f", low1.price), low1.index,
                String.format("%.2f", low2.price), low2.index,
                String.format("%.2f", priceDiff));

        if (priceDiff > 3.0) {
            log.debug("   ❌ Price difference too large: {}% > 3%", String.format("%.2f", priceDiff));
            return false;
        }

        int candlesBetween = low2.index - low1.index;
        log.debug("   Candles between lows: {}", candlesBetween);

        if (candlesBetween < 5) {
            log.debug("   ❌ Lows too close: {} < 5 candles", candlesBetween);
            return false;
        }

        double neckline = 0;
        for (int i = low1.index; i < low2.index; i++) {
            neckline = Math.max(neckline, candles.get(i).getHigh());
        }

        Candle lastCandle = candles.get(candles.size() - 1);

        // ✅ RELAXED: Reduced neckline threshold from 99% to 96%
        boolean breakingOut = lastCandle.getClose() > (neckline * 0.96);

        log.debug("   Neckline: ${}, Last close: ${}, Breaking out: {}",
                String.format("%.2f", neckline),
                String.format("%.2f", lastCandle.getClose()),
                breakingOut);

        if (breakingOut) {
            log.info("✅ DOUBLE BOTTOM DETECTED!");
            return true;
        }

        log.debug("   ❌ Not breaking out");
        return false;
    }

    // ============================================
    // HELPER METHODS
    // ============================================

    private List<SwingPoint> findSwingPoints(List<Candle> candles) {
        List<SwingPoint> swings = new ArrayList<>();
        int lookback = 3;

        for (int i = lookback; i < candles.size() - lookback; i++) {
            Candle current = candles.get(i);

            // Check if swing high
            boolean isSwingHigh = true;
            for (int j = i - lookback; j <= i + lookback; j++) {
                if (j != i && candles.get(j).getHigh() >= current.getHigh()) {
                    isSwingHigh = false;
                    break;
                }
            }

            if (isSwingHigh) {
                swings.add(new SwingPoint(i, current.getHigh(), true));
            }

            // Check if swing low
            boolean isSwingLow = true;
            for (int j = i - lookback; j <= i + lookback; j++) {
                if (j != i && candles.get(j).getLow() <= current.getLow()) {
                    isSwingLow = false;
                    break;
                }
            }

            if (isSwingLow) {
                swings.add(new SwingPoint(i, current.getLow(), false));
            }
        }

        return swings;
    }

    private double calculateSlope(List<SwingPoint> points) {
        if (points.size() < 2) return 0;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        int n = points.size();

        for (SwingPoint point : points) {
            sumX += point.index;
            sumY += point.price;
            sumXY += point.index * point.price;
            sumX2 += point.index * point.index;
        }

        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        return slope;
    }

    private boolean isNearUpperTrendline(List<Candle> candles, List<SwingPoint> highs) {
        if (highs.isEmpty()) return false;

        Candle lastCandle = candles.get(candles.size() - 1);
        double lastHigh = highs.get(highs.size() - 1).price;

        // ✅ RELAXED: Reduced from 98% to 95%
        return lastCandle.getClose() > (lastHigh * 0.95);
    }

    private double calculatePriceChange(List<Candle> candles, int startIdx, int length) {
        if (startIdx < 0 || startIdx + length >= candles.size()) return 0;

        double startPrice = candles.get(startIdx).getClose();
        double endPrice = candles.get(startIdx + length).getClose();

        return ((endPrice - startPrice) / startPrice) * 100;
    }

    private double calculateRangePercent(List<Candle> candles) {
        double high = candles.stream().mapToDouble(Candle::getHigh).max().orElse(0);
        double low = candles.stream().mapToDouble(Candle::getLow).min().orElse(0);

        return ((high - low) / low) * 100;
    }

    private double calculateOverallSlope(List<Candle> candles) {
        if (candles.isEmpty()) return 0;

        double startPrice = candles.get(0).getClose();
        double endPrice = candles.get(candles.size() - 1).getClose();

        return ((endPrice - startPrice) / startPrice) * 100;
    }

    private static class SwingPoint {
        int index;
        double price;
        boolean isHigh;

        SwingPoint(int index, double price, boolean isHigh) {
            this.index = index;
            this.price = price;
            this.isHigh = isHigh;
        }
    }
}