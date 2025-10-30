package hashita.service;

import hashita.service.EntrySignalService.EntrySignal;
import hashita.data.PatternRecognitionResult;
import hashita.data.Candle;
import hashita.service.TrendAnalysisService.TrendDirection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class EnhancedEntrySignalService {

    @Autowired
    private TrendAnalysisService trendAnalysisService;

    // Track recent signals to avoid conflicts
    private final Map<String, RecentSignal> recentSignals = new ConcurrentHashMap<>();

    /**
     * Evaluate pattern with trend and ADX filters
     *
     * @param pattern The detected pattern
     * @param allCandles ALL candles up to this point (for trend calculation)
     * @param baseSignal The base signal from EntrySignalService
     * @return Enhanced signal with trend context
     */
    public EntrySignal evaluateWithFilters(PatternRecognitionResult pattern,
                                           List<Candle> allCandles,
                                           EntrySignal baseSignal) {

        // 1. Determine trend
        TrendDirection trend = trendAnalysisService.determineTrend(allCandles);

        // 2. Calculate ADX
        double adx = trendAnalysisService.calculateADX(allCandles);

        // 3. Apply filters and CREATE NEW SIGNAL (don't modify original)
        EntrySignal enhancedSignal = applyTrendFilter(baseSignal, pattern, trend, adx, allCandles);

        // 4. Check for conflicting recent signals
        if (isConflictingSignal(enhancedSignal)) {
            log.warn("⚠️ Conflicting signal detected for {} - reducing quality",
                    enhancedSignal.getSymbol());

            // Create new signal with reduced quality
            enhancedSignal = EntrySignal.builder()
                    .symbol(enhancedSignal.getSymbol())
                    .pattern(enhancedSignal.getPattern())
                    .timestamp(enhancedSignal.getTimestamp())
                    .entryPrice(enhancedSignal.getEntryPrice())
                    .stopLoss(enhancedSignal.getStopLoss())
                    .target(enhancedSignal.getTarget())
                    .riskAmount(enhancedSignal.getRiskAmount())
                    .rewardAmount(enhancedSignal.getRewardAmount())
                    .riskRewardRatio(enhancedSignal.getRiskRewardRatio())
                    .confidence(enhancedSignal.getConfidence())
                    .hasVolumeConfirmation(enhancedSignal.isHasVolumeConfirmation())
                    .signalQuality(enhancedSignal.getSignalQuality() * 0.5) // Reduce quality
                    .urgency(enhancedSignal.getUrgency())
                    .direction(enhancedSignal.getDirection())
                    .reason(enhancedSignal.getReason() + " ⚠️ CONFLICTING")
                    // ✅ ADD THESE THREE LINES:
                    .volume(enhancedSignal.getVolume())
                    .averageVolume(enhancedSignal.getAverageVolume())
                    .volumeRatio(enhancedSignal.getVolumeRatio())
                    .build();
        }

        return enhancedSignal;
    }
    /**
     * Apply trend and ADX filters - Returns NEW signal
     */
    // ADD TO EnhancedEntrySignalService.java

    /**
     * IMPROVED: Better detection of late entries and pullbacks
     */
    private EntrySignal applyTrendFilter(EntrySignal baseSignal,
                                         PatternRecognitionResult pattern,
                                         TrendDirection trend,
                                         double adx,
                                         List<Candle> allCandles) {

        double qualityMultiplier = 1.0;
        double newConfidence = baseSignal.getConfidence();
        StringBuilder reasonBuilder = new StringBuilder();

        // ... existing trend and ADX checks ...

        // ✅ IMPROVED LATE DETECTION
        if (allCandles.size() > 20) {
            Candle currentCandle = allCandles.get(allCandles.size() - 1);
            double currentPrice = currentCandle.getClose();

            // Look at recent high/low (last 30 candles = ~2.5 hours)
            int lookback = Math.min(30, allCandles.size());
            List<Candle> recentCandles = allCandles.subList(allCandles.size() - lookback, allCandles.size());

            double recentHigh = recentCandles.stream()
                    .mapToDouble(Candle::getHigh)
                    .max()
                    .orElse(currentPrice);

            double recentLow = recentCandles.stream()
                    .mapToDouble(Candle::getLow)
                    .min()
                    .orElse(currentPrice);

            // Calculate where we are in the recent range
            double rangePercent = ((currentPrice - recentLow) / (recentHigh - recentLow)) * 100;

            // For BULLISH patterns
            if (pattern.isBullish()) {

                // Check if we're buying near the TOP of recent range (BAD!)
                if (rangePercent > 85) {
                    // Buying at top of range - LATE!
                    log.warn("⚠️ LATE ENTRY: Buying at {}% of recent range for {}",
                            String.format("%.1f", rangePercent), pattern.getSymbol());
                    qualityMultiplier *= 0.60;  // 40% penalty
                    reasonBuilder.append(" ⚠️ LATE (buying at top)");

                } else if (rangePercent < 30) {
                    // Buying near bottom of range - EARLY! (good)
                    log.info("✅ GOOD ENTRY: Buying at {}% of recent range",
                            String.format("%.1f", rangePercent));
                    qualityMultiplier *= 1.10;
                    reasonBuilder.append(" ✅ GOOD ENTRY");

                } else if (rangePercent > 70) {
                    // Buying in upper part of range - caution
                    qualityMultiplier *= 0.85;
                    reasonBuilder.append(" ⚠️ Mid-range entry");
                }

                // ✅ NEW: Check for pullback after big move
                // If recent high was > 5% above current, and we're < 2% from high = late
                double pullbackFromHigh = ((recentHigh - currentPrice) / recentHigh) * 100;
                double moveSize = ((recentHigh - recentLow) / recentLow) * 100;

                if (moveSize > 5 && pullbackFromHigh < 2) {
                    // Just off the highs after a big move = late!
                    log.warn("⚠️ LATE: Just off high after {}% move",
                            String.format("%.1f", moveSize));
                    qualityMultiplier *= 0.70;
                    reasonBuilder.append(" ⚠️ After big move");
                }
            }

            // For BEARISH patterns (inverse logic)
            if (pattern.isBearish()) {
                if (rangePercent < 15) {
                    // Shorting at bottom - LATE!
                    qualityMultiplier *= 0.60;
                    reasonBuilder.append(" ⚠️ LATE (shorting at bottom)");

                } else if (rangePercent > 70) {
                    // Shorting near top - EARLY! (good)
                    qualityMultiplier *= 1.10;
                    reasonBuilder.append(" ✅ GOOD ENTRY");

                } else if (rangePercent < 30) {
                    // Shorting in lower part - caution
                    qualityMultiplier *= 0.85;
                    reasonBuilder.append(" ⚠️ Mid-range entry");
                }
            }
        }

        // ✅ NEW: Volume context check
        if (allCandles.size() > 10) {
            Candle currentCandle = allCandles.get(allCandles.size() - 1);

            // Calculate average volume of last 10 candles
            double avgVolume = allCandles.subList(allCandles.size() - 10, allCandles.size())
                    .stream()
                    .mapToDouble(Candle::getVolume)
                    .average()
                    .orElse(0);

            // Compare current candle's volume to average
            if (avgVolume > 0) {
                double volRatio = currentCandle.getVolume() / avgVolume;

                if (volRatio < 0.5) {
                    // Very low volume - suspicious!
                    log.warn("⚠️ LOW VOLUME: Only {}% of average",
                            String.format("%.0f", volRatio * 100));
                    qualityMultiplier *= 0.85;
                    reasonBuilder.append(" ⚠️ Low volume");

                } else if (volRatio > 1.5) {
                    // High volume - good!
                    qualityMultiplier *= 1.05;
                    reasonBuilder.append(" ✅ Strong volume");
                }
            }
        }

        // ... rest of existing code (RR ratio, risk %, etc.) ...

        // Calculate new quality
        double newQuality = baseSignal.getSignalQuality() * qualityMultiplier;
        newQuality = Math.max(0, Math.min(100, newQuality));

        // Build complete reason
        String updatedReason = String.format("%s at $%.2f (%.0f%% conf)%s%s",
                pattern.getPattern().name().replace("_", " "),
                pattern.getPriceAtDetection(),
                newConfidence,
                pattern.isHasVolumeConfirmation() ? " + VOLUME" : "",
                reasonBuilder.toString());

        // ✅ FIX: Copy ALL fields including volume
        return EntrySignal.builder()
                .symbol(baseSignal.getSymbol())
                .pattern(baseSignal.getPattern())
                .timestamp(baseSignal.getTimestamp())
                .entryPrice(baseSignal.getEntryPrice())
                .stopLoss(baseSignal.getStopLoss())
                .target(baseSignal.getTarget())
                .riskAmount(baseSignal.getRiskAmount())
                .rewardAmount(baseSignal.getRewardAmount())
                .riskRewardRatio(baseSignal.getRiskRewardRatio())
                .confidence(newConfidence)
                .hasVolumeConfirmation(baseSignal.isHasVolumeConfirmation())
                .signalQuality(newQuality)
                .urgency(baseSignal.getUrgency())
                .direction(baseSignal.getDirection())
                .reason(updatedReason)
                // ✅ ADD THESE THREE LINES:
                .volume(baseSignal.getVolume())
                .averageVolume(baseSignal.getAverageVolume())
                .volumeRatio(baseSignal.getVolumeRatio())
                .build();
    }

    /**
     * Check if this signal conflicts with a recent signal
     */
    private boolean isConflictingSignal(EntrySignal newSignal) {
        String symbol = newSignal.getSymbol();
        RecentSignal recent = recentSignals.get(symbol);

        if (recent == null) {
            // No recent signal - save this one
            recentSignals.put(symbol, new RecentSignal(newSignal));
            return false;
        }

        // Check if opposite direction within 30 minutes
        // Convert Instant to LocalDateTime for comparison
        LocalDateTime recentTime = LocalDateTime.ofInstant(
                recent.timestamp, java.time.ZoneId.systemDefault());
        LocalDateTime newTime = LocalDateTime.ofInstant(
                newSignal.getTimestamp(), java.time.ZoneId.systemDefault());

        Duration timeSince = Duration.between(recentTime, newTime);

        if (timeSince.toMinutes() < 30 &&
                !recent.direction.equals(newSignal.getDirection().name())) {

            log.warn("⚠️ Conflicting signal: {} {} at {} (prev: {} at {})",
                    newSignal.getSymbol(),
                    newSignal.getDirection(),
                    newTime,
                    recent.direction,
                    recentTime);

            return true; // CONFLICTING
        }

        // Update most recent signal
        recentSignals.put(symbol, new RecentSignal(newSignal));
        return false;
    }

    /**
     * Clear old signals (call this daily)
     */
    public void clearOldSignals() {
        recentSignals.clear();
        log.info("Cleared recent signals cache");
    }

    /**
     * Internal class to track recent signals
     */
    private static class RecentSignal {
        Instant timestamp;
        String direction;  // Store as String, not enum

        RecentSignal(EntrySignal signal) {
            this.timestamp = signal.getTimestamp();
            this.direction = signal.getDirection().name(); // Convert enum to String
        }
    }
}