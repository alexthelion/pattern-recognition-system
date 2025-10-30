package hashita.data;

/**
 * Enum representing various candlestick and chart patterns
 *
 * ✅ UPDATED: Added multi-candle chart patterns
 */
public enum CandlePattern {

    // ===== SINGLE CANDLESTICK PATTERNS =====

    // Bullish Patterns
    BULLISH_ENGULFING("Bullish Engulfing", PatternType.BULLISH, 2),
    HAMMER("Hammer", PatternType.BULLISH, 1),
    INVERTED_HAMMER("Inverted Hammer", PatternType.BULLISH, 1),
    PIERCING_LINE("Piercing Line", PatternType.BULLISH, 2),
    MORNING_STAR("Morning Star", PatternType.BULLISH, 3),
    THREE_WHITE_SOLDIERS("Three White Soldiers", PatternType.BULLISH, 3),
    TWEEZER_BOTTOM("Tweezer Bottom", PatternType.BULLISH, 2),
    BULLISH_HARAMI("Bullish Harami", PatternType.BULLISH, 2),
    DRAGONFLY_DOJI("Dragonfly Doji", PatternType.BULLISH, 1),

    // Bearish Patterns
    BEARISH_ENGULFING("Bearish Engulfing", PatternType.BEARISH, 2),
    SHOOTING_STAR("Shooting Star", PatternType.BEARISH, 1),
    HANGING_MAN("Hanging Man", PatternType.BEARISH, 1),
    DARK_CLOUD_COVER("Dark Cloud Cover", PatternType.BEARISH, 2),
    EVENING_STAR("Evening Star", PatternType.BEARISH, 3),
    THREE_BLACK_CROWS("Three Black Crows", PatternType.BEARISH, 3),
    TWEEZER_TOP("Tweezer Top", PatternType.BEARISH, 2),
    BEARISH_HARAMI("Bearish Harami", PatternType.BEARISH, 2),
    GRAVESTONE_DOJI("Gravestone Doji", PatternType.BEARISH, 1),

    // Neutral/Continuation Patterns
    DOJI("Doji", PatternType.NEUTRAL, 1),
    SPINNING_TOP("Spinning Top", PatternType.NEUTRAL, 1),

    // ===== CHART PATTERNS (MULTI-CANDLE FORMATIONS) =====

    // Wedges (Reversal Patterns)
    FALLING_WEDGE("Falling Wedge", PatternType.BULLISH, 20),
    RISING_WEDGE("Rising Wedge", PatternType.BEARISH, 20),

    // Flags (Continuation Patterns)
    BULL_FLAG("Bull Flag", PatternType.BULLISH, 15),
    BEAR_FLAG("Bear Flag", PatternType.BEARISH, 15),

    // Triangles
    ASCENDING_TRIANGLE("Ascending Triangle", PatternType.BULLISH, 20),
    DESCENDING_TRIANGLE("Descending Triangle", PatternType.BEARISH, 20),

    // Double Patterns
    DOUBLE_BOTTOM("Double Bottom", PatternType.BULLISH, 15),
    DOUBLE_TOP("Double Top", PatternType.BEARISH, 15);

    private final String displayName;
    private final PatternType type;
    private final int requiredCandles;

    CandlePattern(String displayName, PatternType type, int requiredCandles) {
        this.displayName = displayName;
        this.type = type;
        this.requiredCandles = requiredCandles;
    }

    public String getDisplayName() {
        return displayName;
    }

    public PatternType getType() {
        return type;
    }

    public int getRequiredCandles() {
        return requiredCandles;
    }

    public boolean isBullish() {
        return type == PatternType.BULLISH;
    }

    public boolean isBearish() {
        return type == PatternType.BEARISH;
    }

    public boolean isNeutral() {
        return type == PatternType.NEUTRAL;
    }

    /**
     * ✅ NEW: Check if this is a chart pattern (vs single candlestick)
     */
    public boolean isChartPattern() {
        return requiredCandles >= 15;
    }

    public enum PatternType {
        BULLISH,
        BEARISH,
        NEUTRAL
    }
}