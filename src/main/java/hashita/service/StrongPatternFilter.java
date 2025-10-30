package hashita.service;

import hashita.data.CandlePattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Filter that only allows high-probability patterns
 *
 * ✅ UPDATED: Added chart patterns to TIER 1
 *
 * Philosophy: Trade less, win more
 */
@Service
@Slf4j
public class StrongPatternFilter {

    // ✅ TIER 1: Chart patterns + Strong reversal patterns (allow always)
    private static final Set<CandlePattern> TIER_1_PATTERNS = Set.of(
            // ✅ Chart patterns (STRONGEST!)
            CandlePattern.FALLING_WEDGE,
            CandlePattern.RISING_WEDGE,
            CandlePattern.BULL_FLAG,
            CandlePattern.BEAR_FLAG,
            CandlePattern.ASCENDING_TRIANGLE,
            CandlePattern.DESCENDING_TRIANGLE,
            CandlePattern.DOUBLE_BOTTOM,
            CandlePattern.DOUBLE_TOP,

            // Strong candlestick patterns
            CandlePattern.BULLISH_ENGULFING,
            CandlePattern.BEARISH_ENGULFING,
            CandlePattern.MORNING_STAR,
            CandlePattern.EVENING_STAR,
            CandlePattern.THREE_WHITE_SOLDIERS,
            CandlePattern.THREE_BLACK_CROWS
    );

    // ✅ TIER 2: Good patterns (allow with high quality only)
    private static final Set<CandlePattern> TIER_2_PATTERNS = Set.of(
            CandlePattern.HAMMER,
            CandlePattern.SHOOTING_STAR,
            CandlePattern.PIERCING_LINE,
            CandlePattern.DARK_CLOUD_COVER
    );

    // ❌ TIER 3: Weak patterns (require VERY high quality)
    private static final Set<CandlePattern> TIER_3_PATTERNS = Set.of(
            CandlePattern.INVERTED_HAMMER,
            CandlePattern.HANGING_MAN,
            CandlePattern.BULLISH_HARAMI,
            CandlePattern.BEARISH_HARAMI,
            CandlePattern.TWEEZER_BOTTOM,
            CandlePattern.TWEEZER_TOP
    );

    /**
     * Should we allow this pattern?
     *
     * @param pattern The candlestick pattern
     * @param signalQuality The calculated signal quality (0-100)
     * @param hasVolume Whether pattern has volume confirmation
     * @return true if pattern meets minimum standards
     */
    public boolean isStrongPattern(CandlePattern pattern,
                                   double signalQuality,
                                   boolean hasVolume) {

        // TIER 1: Strong patterns - allow if quality >= 70
        if (TIER_1_PATTERNS.contains(pattern)) {
            if (signalQuality >= 70) {
                log.debug("✅ TIER 1 pattern {} with quality {}", pattern, signalQuality);
                return true;
            }
            log.debug("❌ TIER 1 pattern {} rejected (quality {} < 70)", pattern, signalQuality);
            return false;
        }

        // TIER 2: Good patterns - require quality >= 75 + volume
        if (TIER_2_PATTERNS.contains(pattern)) {
            if (signalQuality >= 75 && hasVolume) {
                log.debug("✅ TIER 2 pattern {} with quality {} + volume", pattern, signalQuality);
                return true;
            }
            log.debug("❌ TIER 2 pattern {} rejected (quality {} or no volume)",
                    pattern, signalQuality);
            return false;
        }

        // TIER 3: Weak patterns - require quality >= 85 + volume
        if (TIER_3_PATTERNS.contains(pattern)) {
            if (signalQuality >= 85 && hasVolume) {
                log.debug("✅ TIER 3 pattern {} accepted (exceptional quality {})",
                        pattern, signalQuality);
                return true;
            }
            log.debug("❌ TIER 3 pattern {} rejected (weak pattern, need 85+ quality)",
                    pattern);
            return false;
        }

        // Unknown pattern - reject
        log.warn("❌ Unknown pattern {}", pattern);
        return false;
    }

    /**
     * Get the tier of a pattern (for display/logging)
     */
    public int getPatternTier(CandlePattern pattern) {
        if (TIER_1_PATTERNS.contains(pattern)) return 1;
        if (TIER_2_PATTERNS.contains(pattern)) return 2;
        if (TIER_3_PATTERNS.contains(pattern)) return 3;
        return 4; // Unknown/weak
    }

    /**
     * Get required quality for this pattern
     */
    public double getRequiredQuality(CandlePattern pattern, boolean hasVolume) {
        if (TIER_1_PATTERNS.contains(pattern)) {
            return 70.0;
        } else if (TIER_2_PATTERNS.contains(pattern)) {
            return hasVolume ? 75.0 : 100.0; // Impossible without volume
        } else if (TIER_3_PATTERNS.contains(pattern)) {
            return hasVolume ? 85.0 : 100.0; // Impossible without volume
        }
        return 100.0; // Unknown patterns not allowed
    }
}