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
                    .build();
        }

        return enhancedSignal;
    }

    /**
     * Apply trend and ADX filters - Returns NEW signal
     */
    private EntrySignal applyTrendFilter(EntrySignal baseSignal,
                                         PatternRecognitionResult pattern,
                                         TrendDirection trend,
                                         double adx,
                                         List<Candle> allCandles) {

        double qualityMultiplier = 1.0;
        double newConfidence = baseSignal.getConfidence();

        // Build reason with proper confidence (will update later if boosted)
        String originalReason = baseSignal.getReason();

        // Check trend alignment
        boolean isBullishPattern = pattern.isBullish();
        boolean isBearishPattern = pattern.isBearish();

        StringBuilder reasonBuilder = new StringBuilder();

        if (isBullishPattern && trend == TrendDirection.DOWNTREND) {
            // BULLISH pattern in DOWNTREND = BAD
            log.warn("⚠️ COUNTER-TREND: Bullish pattern {} in DOWNTREND",
                    pattern.getPattern());
            qualityMultiplier *= 0.4; // Reduce quality by 60%
            reasonBuilder.append(" ⚠️ COUNTER-TREND (in downtrend)");

        } else if (isBearishPattern && trend == TrendDirection.UPTREND) {
            // BEARISH pattern in UPTREND = BAD
            log.warn("⚠️ COUNTER-TREND: Bearish pattern {} in UPTREND",
                    pattern.getPattern());
            qualityMultiplier *= 0.4;
            reasonBuilder.append(" ⚠️ COUNTER-TREND (in uptrend)");

        } else if ((isBullishPattern && trend == TrendDirection.UPTREND) ||
                (isBearishPattern && trend == TrendDirection.DOWNTREND)) {
            // WITH the trend = GOOD
            log.info("✅ WITH-TREND: {} pattern {} in {}",
                    isBullishPattern ? "Bullish" : "Bearish",
                    pattern.getPattern(),
                    trend);
            qualityMultiplier *= 1.2; // Boost quality by 20%
            reasonBuilder.append(" ✅ WITH TREND");
            newConfidence = Math.min(95, newConfidence + 10);
        } else {
            // NEUTRAL trend
            log.info("➖ NEUTRAL trend for {}", pattern.getPattern());
            reasonBuilder.append(" (neutral trend)");
        }

        // Check ADX (trend strength)
        if (adx < 20) {
            // Choppy market - AVOID
            log.warn("⚠️ CHOPPY MARKET: ADX={} for {}", adx, pattern.getPattern());
            qualityMultiplier *= 0.6; // Reduce quality by 40%
            reasonBuilder.append(String.format(" ⚠️ CHOPPY (ADX: %.1f)", adx));

        } else if (adx > 25) {
            // Strong trend - GOOD
            log.info("✅ STRONG TREND: ADX={} for {}", adx, pattern.getPattern());
            qualityMultiplier *= 1.15; // Boost quality by 15%
            reasonBuilder.append(String.format(" ✅ STRONG TREND (ADX: %.1f)", adx));
        }

        // ADD VARIABILITY: Adjust quality based on context
        // 1. Early vs late in trend
        if (allCandles.size() > 10) {
            double earlyPrice = allCandles.get(Math.max(0, allCandles.size() - 50)).getClose();
            double currentPrice = baseSignal.getEntryPrice();
            double priceMovement = ((currentPrice - earlyPrice) / earlyPrice) * 100;

            if (isBearishPattern) {
                if (priceMovement < -15) {
                    // Already down 15%+ - very late
                    qualityMultiplier *= 0.90;
                    reasonBuilder.append(" ⚠️ LATE");
                } else if (priceMovement < -3 && priceMovement > -8) {
                    // Down 3-8% - sweet spot
                    qualityMultiplier *= 1.05;
                    reasonBuilder.append(" 🎯 GOOD ENTRY");
                } else if (priceMovement > -2) {
                    // Early in trend
                    qualityMultiplier *= 1.02;
                }
            }

            if (isBullishPattern) {
                if (priceMovement > 15) {
                    // Already up 15%+ - late
                    qualityMultiplier *= 0.90;
                    reasonBuilder.append(" ⚠️ LATE");
                } else if (priceMovement > 3 && priceMovement < 8) {
                    // Up 3-8% - sweet spot
                    qualityMultiplier *= 1.05;
                    reasonBuilder.append(" 🎯 GOOD ENTRY");
                } else if (priceMovement < 2) {
                    // Early
                    qualityMultiplier *= 1.02;
                }
            }
        }

        // 2. Risk/reward quality
        double rrRatio = baseSignal.getRiskRewardRatio();
        if (rrRatio >= 4.0) {
            qualityMultiplier *= 1.08;
        } else if (rrRatio < 2.5) {
            qualityMultiplier *= 0.95;
        }

        // 3. Tight stop = better entry
        double riskPercent = baseSignal.getRiskPercent();
        if (riskPercent < 3.0) {
            qualityMultiplier *= 1.03;
        } else if (riskPercent > 10.0) {
            qualityMultiplier *= 0.92;
        }

        // Calculate new quality
        double newQuality = baseSignal.getSignalQuality() * qualityMultiplier;
        newQuality = Math.max(0, Math.min(100, newQuality));

        // Build complete reason string with UPDATED confidence
        String updatedReason = String.format("%s at $%.2f (%.0f%% conf)%s%s",
                pattern.getPattern().name().replace("_", " "),
                pattern.getPriceAtDetection(),
                newConfidence,  // Use UPDATED confidence
                pattern.isHasVolumeConfirmation() ? " + VOLUME" : "",
                reasonBuilder.toString());

        // Create NEW signal with updated values
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