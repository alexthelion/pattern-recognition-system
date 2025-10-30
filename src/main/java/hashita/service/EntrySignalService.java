package hashita.service;

import hashita.data.Candle;
import hashita.data.CandlePattern;
import hashita.data.PatternRecognitionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service that automatically identifies WHEN TO ENTER stocks based on patterns
 *
 * Returns actionable entry signals with:
 * - Entry price
 * - Stop loss
 * - Target
 * - Risk/Reward ratio
 * - Signal quality score
 * - Urgency level
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EntrySignalService {

    private final PatternAnalysisService patternAnalysisService;

    // Configuration
    private static final double MIN_CONFIDENCE = 75.0;
    private static final double MIN_RR_RATIO = 2.0;  // Minimum 2:1 risk/reward
    private static final double MAX_RISK_PERCENT = 20.0; // Max 20% risk
    private static final double MIN_CANDLE_RANGE = 0.05; // Minimum candle size (ignore tiny moves)

    /**
     * Find entry signals for a single stock
     */
    public List<EntrySignal> findEntrySignals(String symbol, String date, int intervalMinutes) {
        log.info("Scanning for entry signals: {} on {}", symbol, date);

        List<PatternRecognitionResult> patterns =
                patternAnalysisService.analyzeStockForDate(symbol, date, intervalMinutes);

        if (patterns.isEmpty()) {
            return Collections.emptyList();
        }

        return patterns.stream()
                .map(this::evaluatePattern)
                .filter(Objects::nonNull)
                .filter(this::isValidSignal)
                .sorted(Comparator.comparing(EntrySignal::getSignalQuality).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Find entry signals for multiple stocks
     */
    public Map<String, List<EntrySignal>> scanMultipleStocks(
            List<String> symbols, String date, int intervalMinutes) {

        Map<String, List<EntrySignal>> signals = new LinkedHashMap<>();

        for (String symbol : symbols) {
            List<EntrySignal> stockSignals = findEntrySignals(symbol, date, intervalMinutes);
            if (!stockSignals.isEmpty()) {
                signals.put(symbol, stockSignals);
            }
        }

        return signals;
    }

    /**
     * Get BEST entry opportunities across all stocks
     */
    public List<EntrySignal> findBestOpportunities(
            List<String> symbols, String date, int intervalMinutes, int maxResults) {

        return scanMultipleStocks(symbols, date, intervalMinutes).values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparing(EntrySignal::getSignalQuality).reversed())
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * Evaluate pattern and create entry signal
     */
    public EntrySignal evaluatePattern(PatternRecognitionResult pattern) {
        try {
            Candle patternCandle = pattern.getCandles().get(pattern.getCandles().size() - 1);

            double entryPrice = (patternCandle.getHigh() + patternCandle.getLow() + patternCandle.getClose()) / 3.0;
            double stopLoss = calculateStopLoss(pattern, patternCandle);
            double target = calculateTarget(pattern, patternCandle, entryPrice, stopLoss);

            double riskAmount = Math.abs(entryPrice - stopLoss);
            double rewardAmount = Math.abs(target - entryPrice);
            double riskRewardRatio = rewardAmount / riskAmount;

            double signalQuality = calculateSignalQuality(pattern, riskRewardRatio);
            SignalUrgency urgency = determineUrgency(pattern, signalQuality);

            double currentVolume = patternCandle.getVolume();
            double avgVolume = pattern.getAverageVolume();
            double volumeRatio = avgVolume > 0 ? currentVolume / avgVolume : 1.0;

            return EntrySignal.builder()
                    .symbol(pattern.getSymbol())
                    .pattern(pattern.getPattern())
                    .timestamp(pattern.getTimestamp())
                    .entryPrice(entryPrice)
                    .stopLoss(stopLoss)
                    .target(target)
                    .riskAmount(riskAmount)
                    .rewardAmount(rewardAmount)
                    .riskRewardRatio(riskRewardRatio)
                    .confidence(pattern.getConfidence())
                    .hasVolumeConfirmation(pattern.isHasVolumeConfirmation())
                    .signalQuality(signalQuality)
                    .urgency(urgency)
                    .direction(pattern.isBullish() ? SignalDirection.LONG : SignalDirection.SHORT)
                    .reason(buildReason(pattern))
                    .volume(currentVolume)
                    .averageVolume(avgVolume)
                    .volumeRatio(volumeRatio)
                    .build();

        } catch (Exception e) {
            log.error("Error evaluating pattern: {}", e.getMessage());
            return null;
        }
    }

    private double calculateStopLoss(PatternRecognitionResult pattern, Candle patternCandle) {
        if (pattern.isBullish()) {
            double support = pattern.getSupportLevel() > 0
                    ? pattern.getSupportLevel()
                    : patternCandle.getLow();
            return support * 0.99; // 1% buffer below support
        } else {
            double resistance = pattern.getResistanceLevel() > 0
                    ? pattern.getResistanceLevel()
                    : patternCandle.getHigh();
            return resistance * 1.01; // 1% buffer above resistance
        }
    }

    private double calculateTarget(PatternRecognitionResult pattern, Candle patternCandle,
                                   double entryPrice, double stopLoss) {
        double riskAmount = Math.abs(entryPrice - stopLoss);
        double rewardMultiplier = 3.0; // Default 3:1

        if (pattern.getConfidence() >= 85 && pattern.isHasVolumeConfirmation()) {
            rewardMultiplier = 4.0; // Strong signals get 4:1
        }

        if (pattern.isBullish()) {
            return entryPrice + (riskAmount * rewardMultiplier);
        } else {
            return entryPrice - (riskAmount * rewardMultiplier);
        }
    }

    private double calculateSignalQuality(PatternRecognitionResult pattern, double rrRatio) {
        double score = 0;

        // Confidence (max 40 points)
        score += pattern.getConfidence() * 0.4;

        // Volume (20 points)
        if (pattern.isHasVolumeConfirmation()) {
            score += 20;
        }

        // Risk/Reward (max 20 points)
        if (rrRatio >= 4.0) score += 20;
        else if (rrRatio >= 3.0) score += 15;
        else if (rrRatio >= 2.0) score += 10;

        // Pattern strength (max 20 points)
        score += getPatternStrength(pattern.getPattern());

        return Math.min(score, 100);
    }

    private double getPatternStrength(CandlePattern pattern) {
        return switch (pattern) {
            // ✅ TIER 1: Chart Patterns (STRONGEST - 25 points)
            case FALLING_WEDGE, RISING_WEDGE -> 25;

            // ✅ TIER 2: Multi-candle formations (VERY STRONG - 22 points)
            case BULL_FLAG, BEAR_FLAG,
                 ASCENDING_TRIANGLE, DESCENDING_TRIANGLE,
                 DOUBLE_BOTTOM, DOUBLE_TOP -> 22;

            // TIER 3: Strong reversal patterns (20 points)
            case BULLISH_ENGULFING, BEARISH_ENGULFING,
                 MORNING_STAR, EVENING_STAR -> 20;

            // TIER 4: Good patterns (15 points)
            case HAMMER, SHOOTING_STAR,
                 THREE_WHITE_SOLDIERS, THREE_BLACK_CROWS -> 15;

            // TIER 5: Moderate patterns (10 points)
            case INVERTED_HAMMER, HANGING_MAN,
                 PIERCING_LINE, DARK_CLOUD_COVER -> 10;

            // TIER 6: Weak patterns (5 points)
            case TWEEZER_BOTTOM, TWEEZER_TOP,
                 BULLISH_HARAMI, BEARISH_HARAMI -> 5;

            // TIER 7: Very weak (2 points)
            default -> 2;
        };
    }

    private SignalUrgency determineUrgency(PatternRecognitionResult pattern, double quality) {
        if (quality >= 85 && pattern.isHasVolumeConfirmation()) {
            return SignalUrgency.IMMEDIATE;
        } else if (quality >= 75) {
            return SignalUrgency.HIGH;
        } else if (quality >= 60) {
            return SignalUrgency.MODERATE;
        } else {
            return SignalUrgency.LOW;
        }
    }

    private boolean isValidSignal(EntrySignal signal) {
        // Filter by confidence
        if (signal.getConfidence() < MIN_CONFIDENCE) return false;

        // Filter by risk/reward ratio
        if (signal.getRiskRewardRatio() < MIN_RR_RATIO) return false;

        // Filter by maximum risk percentage
        double riskPercent = (signal.getRiskAmount() / signal.getEntryPrice()) * 100;
        if (riskPercent > MAX_RISK_PERCENT) return false;

        // ✅ NEW: Filter out tiny candles (noise)
        // If the risk amount is too small, the pattern is probably not significant
        if (signal.getRiskAmount() < MIN_CANDLE_RANGE) {
            return false;
        }

        return true;
    }

    private String buildReason(PatternRecognitionResult pattern) {
        return String.format("%s at $%.2f (%.0f%% conf)%s",
                pattern.getPattern().name().replace("_", " "),
                pattern.getPriceAtDetection(),
                pattern.getConfidence(),
                pattern.isHasVolumeConfirmation() ? " + VOLUME" : "");
    }

    @lombok.Data
    @lombok.Builder
    public static class EntrySignal {
        private String symbol;
        private CandlePattern pattern;
        private Instant timestamp;
        private double entryPrice;
        private double stopLoss;
        private double target;
        private double riskAmount;
        private double rewardAmount;
        private double riskRewardRatio;
        private double confidence;
        private boolean hasVolumeConfirmation;
        private double signalQuality;
        private SignalUrgency urgency;
        private SignalDirection direction;
        private String reason;
        private Double volume;           // Volume at pattern detection
        private Double averageVolume;    // Average volume for context
        private Double volumeRatio;      // Current volume / Average volume (e.g., 1.5 = 50% above avg)
        private Integer confluenceCount;           // Number of patterns merged (null if single pattern)
        private List<String> confluentPatterns;    // List of pattern names that were merged
        private Boolean isConfluence;              // True if this is a confluence signal

        public double getRiskPercent() {
            return (riskAmount / entryPrice) * 100;
        }

        public double getRewardPercent() {
            return (rewardAmount / entryPrice) * 100;
        }

        // ✅ ADD THIS METHOD - Shows timestamp in Israel timezone
        @com.fasterxml.jackson.annotation.JsonProperty("timestampIsrael")
        public String getTimestampIsrael() {
            return timestamp.atZone(java.time.ZoneId.of("Asia/Jerusalem"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }

    // ✅ ADD THESE ENUMS (they're missing from your file)
    public enum SignalUrgency {
        IMMEDIATE, HIGH, MODERATE, LOW
    }

    public enum SignalDirection {
        LONG, SHORT
    }
}