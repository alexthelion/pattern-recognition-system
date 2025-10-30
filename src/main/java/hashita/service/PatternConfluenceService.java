package hashita.service;

import hashita.data.CandlePattern;
import hashita.service.EntrySignalService.EntrySignal;
import hashita.service.EntrySignalService.SignalDirection;
import hashita.service.EntrySignalService.SignalUrgency;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to detect and enhance pattern confluence
 * When multiple patterns occur at the same time/price, they confirm each other
 */
@Service
@Slf4j
public class PatternConfluenceService {

    /**
     * Group patterns that occur close together in time and price
     * Returns enhanced signals with confluence bonuses
     */
    public List<EntrySignal> detectConfluence(List<EntrySignal> signals) {
        if (signals == null || signals.size() < 2) {
            return signals; // No confluence possible
        }

        log.debug("🔍 Checking {} signals for confluence", signals.size());

        List<EntrySignal> result = new ArrayList<>();
        Set<EntrySignal> processed = new HashSet<>();

        for (int i = 0; i < signals.size(); i++) {
            if (processed.contains(signals.get(i))) {
                continue;
            }

            EntrySignal primary = signals.get(i);
            List<EntrySignal> confluent = new ArrayList<>();
            confluent.add(primary);

            // Find other patterns close in time/price
            for (int j = i + 1; j < signals.size(); j++) {
                EntrySignal other = signals.get(j);

                if (processed.contains(other)) {
                    continue;
                }

                if (isConfluent(primary, other)) {
                    confluent.add(other);
                    processed.add(other);
                    log.debug("✅ Found confluence: {} + {}",
                            primary.getPattern(), other.getPattern());
                }
            }

            processed.add(primary);

            // If multiple patterns found, create enhanced signal
            if (confluent.size() > 1) {
                log.info("🎯 Creating confluence signal from {} patterns at ${}",
                        confluent.size(), primary.getEntryPrice());
                result.add(createConfluenceSignal(confluent));
            } else {
                // ✅ ADD: Mark single pattern as non-confluence
                EntrySignal single = EntrySignal.builder()
                        // Copy all fields from primary
                        .symbol(primary.getSymbol())
                        .pattern(primary.getPattern())
                        .timestamp(primary.getTimestamp())
                        .entryPrice(primary.getEntryPrice())
                        .stopLoss(primary.getStopLoss())
                        .target(primary.getTarget())
                        .riskAmount(primary.getRiskAmount())
                        .rewardAmount(primary.getRewardAmount())
                        .riskRewardRatio(primary.getRiskRewardRatio())
                        .confidence(primary.getConfidence())
                        .hasVolumeConfirmation(primary.isHasVolumeConfirmation())
                        .signalQuality(primary.getSignalQuality())
                        .urgency(primary.getUrgency())
                        .direction(primary.getDirection())
                        .reason(primary.getReason())
                        .volume(primary.getVolume())
                        .averageVolume(primary.getAverageVolume())
                        .volumeRatio(primary.getVolumeRatio())
                        // ✅ NEW: Set confluence fields to null/false for single patterns
                        .confluenceCount(null)
                        .confluentPatterns(null)
                        .isConfluence(false)
                        .build();
                result.add(single);
            }
        }

        log.info("✅ Confluence detection complete: {} signals → {} enhanced signals",
                signals.size(), result.size());

        return result;
    }

    /**
     * Check if two signals are confluent (same time/price/direction)
     */
    private boolean isConfluent(EntrySignal s1, EntrySignal s2) {
        // Must be same direction
        if (!s1.getDirection().equals(s2.getDirection())) {
            log.debug("❌ Different directions: {} vs {}",
                    s1.getDirection(), s2.getDirection());
            return false;
        }

        // Within 5 minutes
        Duration timeDiff = Duration.between(s1.getTimestamp(), s2.getTimestamp());
        if (Math.abs(timeDiff.toMinutes()) > 5) {
            log.debug("❌ Too far apart in time: {} minutes",
                    Math.abs(timeDiff.toMinutes()));
            return false;
        }

        // Within 2% price difference
        double priceDiff = Math.abs(s1.getEntryPrice() - s2.getEntryPrice()) / s1.getEntryPrice();
        if (priceDiff > 0.02) {
            log.debug("❌ Too far apart in price: {:.2f}%", priceDiff * 100);
            return false;
        }

        return true;
    }

    /**
     * Create enhanced signal from multiple confluent patterns
     */
    private EntrySignal createConfluenceSignal(List<EntrySignal> patterns) {
        // Use the highest quality signal as base
        EntrySignal best = patterns.stream()
                .max(Comparator.comparing(EntrySignal::getSignalQuality))
                .orElse(patterns.get(0));

        // Calculate confluence bonus
        int confluenceCount = patterns.size();
        double qualityBonus = calculateConfluenceBonus(patterns);
        double newQuality = Math.min(100, best.getSignalQuality() + qualityBonus);

        // Combine pattern names
        String patternNames = patterns.stream()
                .map(s -> s.getPattern().name())
                .distinct()
                .collect(Collectors.joining(" + "));

        // ✅ NEW: Build list of pattern names for metadata
        List<String> patternNamesList = patterns.stream()
                .map(s -> s.getPattern().name())
                .distinct()
                .collect(Collectors.toList());

        // Check if we have volume confirmation from ANY pattern
        boolean hasVolumeConfirmation = patterns.stream()
                .anyMatch(EntrySignal::isHasVolumeConfirmation);

        // Get best volume ratio
        double volumeRatio = patterns.stream()
                .filter(s -> s.getVolumeRatio() != null)
                .mapToDouble(EntrySignal::getVolumeRatio)
                .max()
                .orElse(1.0);

        // Build enhanced reason
        String reason = buildConfluenceReason(patterns, confluenceCount,
                best.getEntryPrice(), best.getConfidence(), hasVolumeConfirmation);

        // Calculate optimal stop loss and target
        double stopLoss = calculateOptimalStopLoss(patterns, best.getDirection());
        double target = calculateOptimalTarget(patterns, best.getDirection());
        double riskAmount = Math.abs(best.getEntryPrice() - stopLoss);
        double rewardAmount = Math.abs(target - best.getEntryPrice());
        double rrRatio = riskAmount > 0 ? rewardAmount / riskAmount : 0;

        return EntrySignal.builder()
                .symbol(best.getSymbol())
                .pattern(best.getPattern()) // Use best pattern as primary
                .timestamp(best.getTimestamp())
                .entryPrice(best.getEntryPrice())
                .stopLoss(stopLoss)
                .target(target)
                .riskAmount(riskAmount)
                .rewardAmount(rewardAmount)
                .riskRewardRatio(rrRatio)
                .confidence(Math.min(100, best.getConfidence() + (confluenceCount * 2)))
                .hasVolumeConfirmation(hasVolumeConfirmation)
                .signalQuality(newQuality)
                .urgency(determineConfluenceUrgency(newQuality))
                .direction(best.getDirection())
                .reason(reason)
                .volume(best.getVolume())
                .averageVolume(best.getAverageVolume())
                .volumeRatio(volumeRatio)
                .confluenceCount(confluenceCount)
                .confluentPatterns(patternNamesList)
                .isConfluence(true)
                .build();
    }

    /**
     * Build detailed reason text for confluence signal
     */
    private String buildConfluenceReason(List<EntrySignal> patterns, int count,
                                         double price, double confidence,
                                         boolean hasVolume) {
        // Main pattern names
        String patternNames = patterns.stream()
                .map(s -> s.getPattern().name().replace("_", " "))
                .distinct()
                .collect(Collectors.joining(" + "));

        // Build main reason
        StringBuilder reason = new StringBuilder();
        reason.append(String.format("%s (CONFLUENCE: %d patterns) at $%.2f (%.0f%% conf)",
                patternNames, count, price, confidence));

        // Add volume if present
        if (hasVolume) {
            reason.append(" + VOLUME");
        }

        // Add pattern details
        reason.append("\n📊 Patterns:");
        for (EntrySignal signal : patterns.stream()
                .sorted(Comparator.comparing(EntrySignal::getSignalQuality).reversed())
                .collect(Collectors.toList())) {
            reason.append(String.format("\n  • %s (%.0f%% quality)",
                    signal.getPattern().name().replace("_", " "),
                    signal.getSignalQuality()));
        }

        return reason.toString();
    }

    /**
     * Calculate quality bonus based on pattern confluence
     */
    private double calculateConfluenceBonus(List<EntrySignal> patterns) {
        int count = patterns.size();

        // Check if there's a mix of chart and candlestick patterns
        boolean hasChartPattern = patterns.stream()
                .anyMatch(s -> s.getPattern().isChartPattern());
        boolean hasCandlestickPattern = patterns.stream()
                .anyMatch(s -> !s.getPattern().isChartPattern());

        double bonus = 0;

        // Base bonus for multiple patterns
        if (count == 2) {
            bonus = 10; // Two patterns
        } else if (count == 3) {
            bonus = 15; // Three patterns (rare!)
        } else if (count >= 4) {
            bonus = 20; // Four+ patterns (very rare!)
        }

        // Extra bonus if mix of chart and candlestick (different timeframes)
        if (hasChartPattern && hasCandlestickPattern) {
            bonus += 5;
            log.debug("✅ Multi-timeframe confirmation: +5 bonus");
        }

        // Check for strong patterns in the mix
        long strongPatterns = patterns.stream()
                .filter(s -> isStrongPattern(s.getPattern()))
                .count();

        if (strongPatterns >= 2) {
            bonus += 5;
            log.debug("✅ Multiple strong patterns: +5 bonus");
        }

        log.info("📈 Confluence bonus: +{} points ({} patterns)", bonus, count);
        return bonus;
    }

    /**
     * Calculate optimal stop loss from multiple patterns
     * For LONG: Use highest (tightest) stop loss
     * For SHORT: Use lowest (tightest) stop loss
     */
    private double calculateOptimalStopLoss(List<EntrySignal> patterns, SignalDirection direction) {
        if (direction == SignalDirection.LONG) {
            // For LONG: Higher stop loss = tighter (less risk)
            return patterns.stream()
                    .mapToDouble(EntrySignal::getStopLoss)
                    .max()
                    .orElse(patterns.get(0).getStopLoss());
        } else {
            // For SHORT: Lower stop loss = tighter (less risk)
            return patterns.stream()
                    .mapToDouble(EntrySignal::getStopLoss)
                    .min()
                    .orElse(patterns.get(0).getStopLoss());
        }
    }

    /**
     * Calculate optimal target from multiple patterns
     * Use the highest target for LONG, lowest for SHORT
     */
    private double calculateOptimalTarget(List<EntrySignal> patterns, SignalDirection direction) {
        if (direction == SignalDirection.LONG) {
            // For LONG: Higher target = more reward
            return patterns.stream()
                    .mapToDouble(EntrySignal::getTarget)
                    .max()
                    .orElse(patterns.get(0).getTarget());
        } else {
            // For SHORT: Lower target = more reward
            return patterns.stream()
                    .mapToDouble(EntrySignal::getTarget)
                    .min()
                    .orElse(patterns.get(0).getTarget());
        }
    }

    /**
     * Determine urgency based on confluence quality
     */
    private SignalUrgency determineConfluenceUrgency(double quality) {
        if (quality >= 95) return SignalUrgency.IMMEDIATE;
        if (quality >= 85) return SignalUrgency.HIGH;
        if (quality >= 75) return SignalUrgency.MODERATE;
        return SignalUrgency.LOW;
    }

    /**
     * Check if pattern is considered "strong"
     */
    private boolean isStrongPattern(CandlePattern pattern) {
        return Set.of(
                CandlePattern.FALLING_WEDGE,
                CandlePattern.RISING_WEDGE,
                CandlePattern.BULLISH_ENGULFING,
                CandlePattern.BEARISH_ENGULFING,
                CandlePattern.MORNING_STAR,
                CandlePattern.EVENING_STAR,
                CandlePattern.DOUBLE_BOTTOM,
                CandlePattern.DOUBLE_TOP,
                CandlePattern.ASCENDING_TRIANGLE,
                CandlePattern.DESCENDING_TRIANGLE
        ).contains(pattern);
    }
}