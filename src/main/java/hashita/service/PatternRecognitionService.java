package hashita.service;

import hashita.data.Candle;
import hashita.data.CandlePattern;
import hashita.data.PatternRecognitionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to recognize candlestick patterns
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PatternRecognitionService {

    private final CandleBuilderService candleBuilderService;

    // Tolerance for comparing prices (0.5%)
    private static final double PRICE_TOLERANCE = 0.005;

    // Minimum candle range to consider (filter out tiny movements)
    private static final double MIN_CANDLE_RANGE = 0.05;

    /**
     * Scan a list of candles for all patterns
     *
     * @param candles List of candles to analyze
     * @param symbol Stock symbol
     * @return List of detected patterns
     */
    public List<PatternRecognitionResult> scanForPatterns(List<Candle> candles, String symbol) {

        if (candles == null || candles.size() < 3) {
            return Collections.emptyList();
        }

        List<PatternRecognitionResult> results = new ArrayList<>();

        // Calculate context metrics
        double avgBody = candleBuilderService.calculateAverageCandleBody(candles);
        double avgRange = candleBuilderService.calculateAverageCandleRange(candles);
        double avgVolume = calculateAverageVolume(candles);

        // Scan from oldest to newest, but need at least 3 candles for lookback
        for (int i = 2; i < candles.size(); i++) {

            // Single candle patterns (use current candle)
            results.addAll(detectSingleCandlePatterns(candles, i, symbol, avgBody, avgRange, avgVolume));

            // Two candle patterns (use previous and current)
            results.addAll(detectTwoCandlePatterns(candles, i, symbol, avgBody, avgRange, avgVolume));

            // Three candle patterns (use i-2, i-1, i)
            results.addAll(detectThreeCandlePatterns(candles, i, symbol, avgBody, avgRange, avgVolume));
        }

        return results;
    }

    /**
     * Detect single candle patterns
     */
    private List<PatternRecognitionResult> detectSingleCandlePatterns(
            List<Candle> candles, int index, String symbol,
            double avgBody, double avgRange, double avgVolume) {

        List<PatternRecognitionResult> results = new ArrayList<>();
        Candle current = candles.get(index);

        // ✅ Skip tiny candles (noise filter)
        if (!isSignificantCandle(current)) {
            return results; // Return empty list
        }

        // Determine trend context (look back 3-5 candles)
        boolean isInUptrend = isUptrend(candles, index, 5);
        boolean isInDowntrend = isDowntrend(candles, index, 5);

        // Hammer (appears after DOWNTREND)
        if (isInDowntrend) {
            PatternRecognitionResult hammer = detectHammer(current, symbol, avgRange);
            if (hammer != null) results.add(hammer);
        }

        // Hanging Man (appears after UPTREND) - same shape as hammer but different context!
        if (isInUptrend) {
            PatternRecognitionResult hangingMan = detectHangingMan(current, symbol, avgRange);
            if (hangingMan != null) results.add(hangingMan);
        }

        // Inverted Hammer (appears after DOWNTREND)
        if (isInDowntrend) {
            PatternRecognitionResult invertedHammer = detectInvertedHammer(current, symbol, avgRange);
            if (invertedHammer != null) results.add(invertedHammer);
        }

        // Shooting Star (appears after UPTREND) - same shape as inverted hammer!
        if (isInUptrend) {
            PatternRecognitionResult shootingStar = detectShootingStar(current, symbol, avgRange);
            if (shootingStar != null) results.add(shootingStar);
        }

        // Doji (context-independent)
        PatternRecognitionResult doji = detectDoji(current, symbol);
        if (doji != null) results.add(doji);

        // Dragonfly Doji (context-independent)
        PatternRecognitionResult dragonflyDoji = detectDragonflyDoji(current, symbol);
        if (dragonflyDoji != null) results.add(dragonflyDoji);

        // Gravestone Doji (context-independent)
        PatternRecognitionResult gravestoneDoji = detectGravestoneDoji(current, symbol);
        if (gravestoneDoji != null) results.add(gravestoneDoji);

        // Spinning Top
        PatternRecognitionResult spinningTop = detectSpinningTop(current, symbol);
        if (spinningTop != null) results.add(spinningTop);

        return results;
    }

    /**
     * Detect two candle patterns
     */
    private List<PatternRecognitionResult> detectTwoCandlePatterns(
            List<Candle> candles, int index, String symbol,
            double avgBody, double avgRange, double avgVolume) {

        List<PatternRecognitionResult> results = new ArrayList<>();

        if (index < 1) return results;

        Candle previous = candles.get(index - 1);
        Candle current = candles.get(index);

        // Bullish Engulfing
        PatternRecognitionResult bullishEngulfing = detectBullishEngulfing(previous, current, symbol, avgVolume);
        if (bullishEngulfing != null) results.add(bullishEngulfing);

        // Bearish Engulfing
        PatternRecognitionResult bearishEngulfing = detectBearishEngulfing(previous, current, symbol, avgVolume);
        if (bearishEngulfing != null) results.add(bearishEngulfing);

        // Piercing Line
        PatternRecognitionResult piercingLine = detectPiercingLine(previous, current, symbol, avgVolume);
        if (piercingLine != null) results.add(piercingLine);

        // Dark Cloud Cover
        PatternRecognitionResult darkCloudCover = detectDarkCloudCover(previous, current, symbol, avgVolume);
        if (darkCloudCover != null) results.add(darkCloudCover);

        // Bullish Harami
        PatternRecognitionResult bullishHarami = detectBullishHarami(previous, current, symbol);
        if (bullishHarami != null) results.add(bullishHarami);

        // Bearish Harami
        PatternRecognitionResult bearishHarami = detectBearishHarami(previous, current, symbol);
        if (bearishHarami != null) results.add(bearishHarami);

        // Tweezer Bottom
        PatternRecognitionResult tweezerBottom = detectTweezerBottom(previous, current, symbol);
        if (tweezerBottom != null) results.add(tweezerBottom);

        // Tweezer Top
        PatternRecognitionResult tweezerTop = detectTweezerTop(previous, current, symbol);
        if (tweezerTop != null) results.add(tweezerTop);

        return results;
    }

    /**
     * Detect three candle patterns
     */
    private List<PatternRecognitionResult> detectThreeCandlePatterns(
            List<Candle> candles, int index, String symbol,
            double avgBody, double avgRange, double avgVolume) {

        List<PatternRecognitionResult> results = new ArrayList<>();

        if (index < 2) return results;

        Candle first = candles.get(index - 2);
        Candle second = candles.get(index - 1);
        Candle third = candles.get(index);

        // Morning Star
        PatternRecognitionResult morningStar = detectMorningStar(first, second, third, symbol, avgVolume);
        if (morningStar != null) results.add(morningStar);

        // Evening Star
        PatternRecognitionResult eveningStar = detectEveningStar(first, second, third, symbol, avgVolume);
        if (eveningStar != null) results.add(eveningStar);

        // Three White Soldiers
        PatternRecognitionResult threeWhiteSoldiers = detectThreeWhiteSoldiers(first, second, third, symbol, avgVolume);
        if (threeWhiteSoldiers != null) results.add(threeWhiteSoldiers);

        // Three Black Crows
        PatternRecognitionResult threeBlackCrows = detectThreeBlackCrows(first, second, third, symbol, avgVolume);
        if (threeBlackCrows != null) results.add(threeBlackCrows);

        return results;
    }

    // ==================== BULLISH PATTERNS ====================

    /**
     * Detect Hammer pattern
     * Characteristics:
     * - Small body at upper end
     * - Long lower shadow (at least 2x body)
     * - Little or no upper shadow
     * - Appears in downtrend (bullish reversal)
     */
    private PatternRecognitionResult detectHammer(Candle candle, String symbol, double avgRange) {
        double bodySize = candle.getBodySize();
        double lowerShadow = candle.getLowerShadow();
        double upperShadow = candle.getUpperShadow();
        double range = candle.getRange();

        // Hammer criteria
        boolean hasSmallBody = candle.getBodyPercentage() < 30;
        boolean hasLongLowerShadow = lowerShadow >= (2 * bodySize);
        boolean hasShortUpperShadow = upperShadow < (bodySize * 0.3);
        boolean bodyAtTop = candle.getUpperShadowPercentage() < 20;

        if (hasSmallBody && hasLongLowerShadow && hasShortUpperShadow && bodyAtTop) {
            double confidence = calculateConfidence(70, candle.getVolume(), 0);

            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.HAMMER)
                    .symbol(symbol)
                    .timestamp(candle.getTimestamp())
                    .intervalMinutes(candle.getIntervalMinutes())
                    .candles(List.of(candle))
                    .confidence(confidence)
                    .description("Hammer pattern detected - potential bullish reversal")
                    .priceAtDetection(candle.getClose())
                    .supportLevel(candle.getLow())
                    .hasVolumeConfirmation(false)
                    .build();
        }

        return null;
    }

    /**
     * Detect Inverted Hammer pattern
     */
    private PatternRecognitionResult detectInvertedHammer(Candle candle, String symbol, double avgRange) {
        double bodySize = candle.getBodySize();
        double lowerShadow = candle.getLowerShadow();
        double upperShadow = candle.getUpperShadow();

        boolean hasSmallBody = candle.getBodyPercentage() < 30;
        boolean hasLongUpperShadow = upperShadow >= (2 * bodySize);
        boolean hasShortLowerShadow = lowerShadow < (bodySize * 0.3);
        boolean bodyAtBottom = candle.getLowerShadowPercentage() < 20;

        if (hasSmallBody && hasLongUpperShadow && hasShortLowerShadow && bodyAtBottom) {
            double confidence = calculateConfidence(70, candle.getVolume(), 0);

            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.INVERTED_HAMMER)
                    .symbol(symbol)
                    .timestamp(candle.getTimestamp())
                    .intervalMinutes(candle.getIntervalMinutes())
                    .candles(List.of(candle))
                    .confidence(confidence)
                    .description("Inverted Hammer pattern detected - potential bullish reversal")
                    .priceAtDetection(candle.getClose())
                    .supportLevel(candle.getLow())
                    .resistanceLevel(candle.getHigh())
                    .build();
        }

        return null;
    }

    /**
     * Detect Bullish Engulfing pattern
     */
    private PatternRecognitionResult detectBullishEngulfing(Candle prev, Candle curr,
                                                            String symbol, double avgVolume) {
        // Previous candle must be bearish, current must be bullish
        if (!prev.isBearish() || !curr.isBullish()) {
            return null;
        }

        // Current candle body must completely engulf previous candle body
        boolean engulfs = curr.getOpen() <= prev.getClose() && curr.getClose() >= prev.getOpen();

        // Current candle should have significant body
        boolean hasSignificantBody = curr.getBodyPercentage() > 50;

        if (engulfs && hasSignificantBody) {
            boolean volumeConfirmation = curr.getVolume() > avgVolume * 1.2;
            double confidence = calculateConfidence(80, curr.getVolume(), avgVolume);

            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.BULLISH_ENGULFING)
                    .symbol(symbol)
                    .timestamp(curr.getTimestamp())
                    .intervalMinutes(curr.getIntervalMinutes())
                    .candles(List.of(prev, curr))
                    .confidence(confidence)
                    .description("Bullish Engulfing pattern detected - strong reversal signal")
                    .priceAtDetection(curr.getClose())
                    .supportLevel(Math.min(prev.getLow(), curr.getLow()))
                    .averageVolume(avgVolume)
                    .hasVolumeConfirmation(volumeConfirmation)
                    .build();
        }

        return null;
    }

    /**
     * Detect Piercing Line pattern
     */
    private PatternRecognitionResult detectPiercingLine(Candle prev, Candle curr,
                                                        String symbol, double avgVolume) {
        if (!prev.isBearish() || !curr.isBullish()) {
            return null;
        }

        // Current opens below previous close
        boolean gapsDown = curr.getOpen() < prev.getClose();

        // Current closes above midpoint of previous candle
        double prevMidpoint = (prev.getOpen() + prev.getClose()) / 2;
        boolean closesAboveMidpoint = curr.getClose() > prevMidpoint;

        // But doesn't engulf completely
        boolean notEngulfing = curr.getClose() < prev.getOpen();

        if (gapsDown && closesAboveMidpoint && notEngulfing) {
            boolean volumeConfirmation = curr.getVolume() > avgVolume * 1.2;
            double confidence = calculateConfidence(75, curr.getVolume(), avgVolume);

            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.PIERCING_LINE)
                    .symbol(symbol)
                    .timestamp(curr.getTimestamp())
                    .intervalMinutes(curr.getIntervalMinutes())
                    .candles(List.of(prev, curr))
                    .confidence(confidence)
                    .description("Piercing Line pattern detected - bullish reversal")
                    .priceAtDetection(curr.getClose())
                    .supportLevel(curr.getLow())
                    .averageVolume(avgVolume)
                    .hasVolumeConfirmation(volumeConfirmation)
                    .build();
        }

        return null;
    }

    /**
     * Detect Morning Star pattern
     */
    private PatternRecognitionResult detectMorningStar(Candle first, Candle second, Candle third,
                                                       String symbol, double avgVolume) {
        // First candle: bearish with large body
        if (!first.isBearish() || first.getBodyPercentage() < 60) {
            return null;
        }

        // Second candle: small body (star), gaps down
        boolean hasSmallBody = second.hasSmallBody();
        boolean gapsDown = second.getHigh() < first.getClose();

        // Third candle: bullish with large body, closes well into first candle
        boolean thirdIsBullish = third.isBullish();
        boolean hasLargeBody = third.getBodyPercentage() > 60;
        double firstMidpoint = (first.getOpen() + first.getClose()) / 2;
        boolean closesIntoFirst = third.getClose() > firstMidpoint;

        if (hasSmallBody && gapsDown && thirdIsBullish && hasLargeBody && closesIntoFirst) {
            boolean volumeConfirmation = third.getVolume() > avgVolume * 1.3;
            double confidence = calculateConfidence(85, third.getVolume(), avgVolume);

            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.MORNING_STAR)
                    .symbol(symbol)
                    .timestamp(third.getTimestamp())
                    .intervalMinutes(third.getIntervalMinutes())
                    .candles(List.of(first, second, third))
                    .confidence(confidence)
                    .description("Morning Star pattern detected - strong bullish reversal signal")
                    .priceAtDetection(third.getClose())
                    .supportLevel(Math.min(second.getLow(), third.getLow()))
                    .averageVolume(avgVolume)
                    .hasVolumeConfirmation(volumeConfirmation)
                    .build();
        }

        return null;
    }

    /**
     * Detect Three White Soldiers pattern
     */
    private PatternRecognitionResult detectThreeWhiteSoldiers(Candle first, Candle second, Candle third,
                                                              String symbol, double avgVolume) {
        // All three must be bullish
        if (!first.isBullish() || !second.isBullish() || !third.isBullish()) {
            return null;
        }

        // Each candle should have a substantial body (RELAXED: 40% - many real patterns have smaller bodies)
        boolean allHaveLargeBodies = first.getBodyPercentage() > 40 &&
                second.getBodyPercentage() > 40 &&
                third.getBodyPercentage() > 40;

        // Each closes progressively higher
        boolean progressiveCloses = second.getClose() > first.getClose() &&
                third.getClose() > second.getClose();

        // Each opens within or near previous body (RELAXED: 5% instead of 2%)
        boolean properOpens = second.getOpen() >= first.getClose() * 0.95 &&
                second.getOpen() <= first.getClose() * 1.05 &&
                third.getOpen() >= second.getClose() * 0.95 &&
                third.getOpen() <= second.getClose() * 1.05;

        // Little or no upper shadows (RELAXED: 35% - allow more price testing)
        boolean smallShadows = first.getUpperShadowPercentage() < 35 &&
                second.getUpperShadowPercentage() < 35 &&
                third.getUpperShadowPercentage() < 35;

        if (allHaveLargeBodies && progressiveCloses && properOpens && smallShadows) {
            double avgVol = (first.getVolume() + second.getVolume() + third.getVolume()) / 3;
            boolean volumeConfirmation = avgVol > avgVolume * 1.2;
            double confidence = calculateConfidence(80, avgVol, avgVolume);  // Slightly lower confidence

            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.THREE_WHITE_SOLDIERS)
                    .symbol(symbol)
                    .timestamp(third.getTimestamp())
                    .intervalMinutes(third.getIntervalMinutes())
                    .candles(List.of(first, second, third))
                    .confidence(confidence)
                    .description("Three White Soldiers pattern detected - strong bullish continuation")
                    .priceAtDetection(third.getClose())
                    .supportLevel(first.getLow())
                    .averageVolume(avgVolume)
                    .hasVolumeConfirmation(volumeConfirmation)
                    .build();
        }

        return null;
    }

    /**
     * Detect Bullish Harami pattern
     */
    private PatternRecognitionResult detectBullishHarami(Candle prev, Candle curr, String symbol) {
        // Previous must be bearish with large body, current must be bullish with small body
        if (!prev.isBearish() || !curr.isBullish()) {
            return null;
        }

        boolean prevHasLargeBody = prev.getBodyPercentage() > 60;
        boolean currHasSmallBody = curr.hasSmallBody();

        // Current body must be contained within previous body
        boolean contained = curr.getOpen() >= prev.getClose() &&
                curr.getClose() <= prev.getOpen();

        if (prevHasLargeBody && currHasSmallBody && contained) {
            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.BULLISH_HARAMI)
                    .symbol(symbol)
                    .timestamp(curr.getTimestamp())
                    .intervalMinutes(curr.getIntervalMinutes())
                    .candles(List.of(prev, curr))
                    .confidence(70)
                    .description("Bullish Harami pattern detected - potential reversal signal")
                    .priceAtDetection(curr.getClose())
                    .supportLevel(prev.getLow())
                    .build();
        }

        return null;
    }

    /**
     * Detect Tweezer Bottom pattern
     */
    private PatternRecognitionResult detectTweezerBottom(Candle prev, Candle curr, String symbol) {
        // Both candles should have similar lows (within tolerance)
        boolean similarLows = Math.abs(prev.getLow() - curr.getLow()) / prev.getLow() < PRICE_TOLERANCE;

        // First bearish, second bullish
        boolean correctColors = prev.isBearish() && curr.isBullish();

        if (similarLows && correctColors) {
            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.TWEEZER_BOTTOM)
                    .symbol(symbol)
                    .timestamp(curr.getTimestamp())
                    .intervalMinutes(curr.getIntervalMinutes())
                    .candles(List.of(prev, curr))
                    .confidence(70)
                    .description("Tweezer Bottom pattern detected - support level confirmed")
                    .priceAtDetection(curr.getClose())
                    .supportLevel(Math.min(prev.getLow(), curr.getLow()))
                    .build();
        }

        return null;
    }

    /**
     * Detect Dragonfly Doji pattern
     */
    private PatternRecognitionResult detectDragonflyDoji(Candle candle, String symbol) {
        if (!candle.isDoji()) {
            return null;
        }

        // Long lower shadow, little to no upper shadow
        boolean hasLongLowerShadow = candle.getLowerShadowPercentage() > 60;
        boolean hasShortUpperShadow = candle.getUpperShadowPercentage() < 10;

        if (hasLongLowerShadow && hasShortUpperShadow) {
            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.DRAGONFLY_DOJI)
                    .symbol(symbol)
                    .timestamp(candle.getTimestamp())
                    .intervalMinutes(candle.getIntervalMinutes())
                    .candles(List.of(candle))
                    .confidence(75)
                    .description("Dragonfly Doji pattern detected - potential bullish reversal")
                    .priceAtDetection(candle.getClose())
                    .supportLevel(candle.getLow())
                    .build();
        }

        return null;
    }

    // ==================== BEARISH PATTERNS ====================

    /**
     * Detect Shooting Star pattern
     */
    private PatternRecognitionResult detectShootingStar(Candle candle, String symbol, double avgRange) {
        double bodySize = candle.getBodySize();
        double lowerShadow = candle.getLowerShadow();
        double upperShadow = candle.getUpperShadow();

        boolean hasSmallBody = candle.getBodyPercentage() < 30;
        boolean hasLongUpperShadow = upperShadow >= (2 * bodySize);
        boolean hasShortLowerShadow = lowerShadow < (bodySize * 0.3);
        boolean bodyAtBottom = candle.getLowerShadowPercentage() < 20;

        if (hasSmallBody && hasLongUpperShadow && hasShortLowerShadow && bodyAtBottom) {
            double confidence = calculateConfidence(70, candle.getVolume(), 0);

            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.SHOOTING_STAR)
                    .symbol(symbol)
                    .timestamp(candle.getTimestamp())
                    .intervalMinutes(candle.getIntervalMinutes())
                    .candles(List.of(candle))
                    .confidence(confidence)
                    .description("Shooting Star pattern detected - potential bearish reversal")
                    .priceAtDetection(candle.getClose())
                    .resistanceLevel(candle.getHigh())
                    .build();
        }

        return null;
    }

    /**
     * Detect Hanging Man pattern
     */
    private PatternRecognitionResult detectHangingMan(Candle candle, String symbol, double avgRange) {
        double bodySize = candle.getBodySize();
        double lowerShadow = candle.getLowerShadow();
        double upperShadow = candle.getUpperShadow();

        boolean hasSmallBody = candle.getBodyPercentage() < 30;
        boolean hasLongLowerShadow = lowerShadow >= (2 * bodySize);
        boolean hasShortUpperShadow = upperShadow < (bodySize * 0.3);
        boolean bodyAtTop = candle.getUpperShadowPercentage() < 20;

        // Hanging man appears after uptrend (bearish reversal)
        if (hasSmallBody && hasLongLowerShadow && hasShortUpperShadow && bodyAtTop) {
            double confidence = calculateConfidence(65, candle.getVolume(), 0);

            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.HANGING_MAN)
                    .symbol(symbol)
                    .timestamp(candle.getTimestamp())
                    .intervalMinutes(candle.getIntervalMinutes())
                    .candles(List.of(candle))
                    .confidence(confidence)
                    .description("Hanging Man pattern detected - potential bearish reversal")
                    .priceAtDetection(candle.getClose())
                    .supportLevel(candle.getLow())
                    .resistanceLevel(candle.getHigh())
                    .build();
        }

        return null;
    }

    /**
     * Detect Bearish Engulfing pattern
     */
    private PatternRecognitionResult detectBearishEngulfing(Candle prev, Candle curr,
                                                            String symbol, double avgVolume) {
        if (!prev.isBullish() || !curr.isBearish()) {
            return null;
        }

        boolean engulfs = curr.getOpen() >= prev.getClose() && curr.getClose() <= prev.getOpen();
        boolean hasSignificantBody = curr.getBodyPercentage() > 50;

        if (engulfs && hasSignificantBody) {
            boolean volumeConfirmation = curr.getVolume() > avgVolume * 1.2;
            double confidence = calculateConfidence(80, curr.getVolume(), avgVolume);

            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.BEARISH_ENGULFING)
                    .symbol(symbol)
                    .timestamp(curr.getTimestamp())
                    .intervalMinutes(curr.getIntervalMinutes())
                    .candles(List.of(prev, curr))
                    .confidence(confidence)
                    .description("Bearish Engulfing pattern detected - strong reversal signal")
                    .priceAtDetection(curr.getClose())
                    .resistanceLevel(Math.max(prev.getHigh(), curr.getHigh()))
                    .averageVolume(avgVolume)
                    .hasVolumeConfirmation(volumeConfirmation)
                    .build();
        }

        return null;
    }

    /**
     * Detect Dark Cloud Cover pattern
     */
    private PatternRecognitionResult detectDarkCloudCover(Candle prev, Candle curr,
                                                          String symbol, double avgVolume) {
        if (!prev.isBullish() || !curr.isBearish()) {
            return null;
        }

        boolean gapsUp = curr.getOpen() > prev.getClose();
        double prevMidpoint = (prev.getOpen() + prev.getClose()) / 2;
        boolean closesBelowMidpoint = curr.getClose() < prevMidpoint;
        boolean notEngulfing = curr.getClose() > prev.getOpen();

        if (gapsUp && closesBelowMidpoint && notEngulfing) {
            boolean volumeConfirmation = curr.getVolume() > avgVolume * 1.2;
            double confidence = calculateConfidence(75, curr.getVolume(), avgVolume);

            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.DARK_CLOUD_COVER)
                    .symbol(symbol)
                    .timestamp(curr.getTimestamp())
                    .intervalMinutes(curr.getIntervalMinutes())
                    .candles(List.of(prev, curr))
                    .confidence(confidence)
                    .description("Dark Cloud Cover pattern detected - bearish reversal")
                    .priceAtDetection(curr.getClose())
                    .resistanceLevel(curr.getHigh())
                    .averageVolume(avgVolume)
                    .hasVolumeConfirmation(volumeConfirmation)
                    .build();
        }

        return null;
    }

    /**
     * Detect Evening Star pattern
     */
    private PatternRecognitionResult detectEveningStar(Candle first, Candle second, Candle third,
                                                       String symbol, double avgVolume) {
        if (!first.isBullish() || first.getBodyPercentage() < 60) {
            return null;
        }

        boolean hasSmallBody = second.hasSmallBody();
        boolean gapsUp = second.getLow() > first.getClose();

        boolean thirdIsBearish = third.isBearish();
        boolean hasLargeBody = third.getBodyPercentage() > 60;
        double firstMidpoint = (first.getOpen() + first.getClose()) / 2;
        boolean closesIntoFirst = third.getClose() < firstMidpoint;

        if (hasSmallBody && gapsUp && thirdIsBearish && hasLargeBody && closesIntoFirst) {
            boolean volumeConfirmation = third.getVolume() > avgVolume * 1.3;
            double confidence = calculateConfidence(85, third.getVolume(), avgVolume);

            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.EVENING_STAR)
                    .symbol(symbol)
                    .timestamp(third.getTimestamp())
                    .intervalMinutes(third.getIntervalMinutes())
                    .candles(List.of(first, second, third))
                    .confidence(confidence)
                    .description("Evening Star pattern detected - strong bearish reversal signal")
                    .priceAtDetection(third.getClose())
                    .resistanceLevel(Math.max(second.getHigh(), third.getHigh()))
                    .averageVolume(avgVolume)
                    .hasVolumeConfirmation(volumeConfirmation)
                    .build();
        }

        return null;
    }

    /**
     * Detect Three Black Crows pattern
     */
    private PatternRecognitionResult detectThreeBlackCrows(Candle first, Candle second, Candle third,
                                                           String symbol, double avgVolume) {
        if (!first.isBearish() || !second.isBearish() || !third.isBearish()) {
            return null;
        }

        // RELAXED: 40% instead of 50%
        boolean allHaveLargeBodies = first.getBodyPercentage() > 40 &&
                second.getBodyPercentage() > 40 &&
                third.getBodyPercentage() > 40;

        boolean progressiveCloses = second.getClose() < first.getClose() &&
                third.getClose() < second.getClose();

        // RELAXED: 5% instead of 2%
        boolean properOpens = second.getOpen() <= first.getClose() * 1.05 &&
                second.getOpen() >= first.getClose() * 0.95 &&
                third.getOpen() <= second.getClose() * 1.05 &&
                third.getOpen() >= second.getClose() * 0.95;

        // RELAXED: 35% instead of 25%
        boolean smallShadows = first.getLowerShadowPercentage() < 35 &&
                second.getLowerShadowPercentage() < 35 &&
                third.getLowerShadowPercentage() < 35;

        if (allHaveLargeBodies && progressiveCloses && properOpens && smallShadows) {
            double avgVol = (first.getVolume() + second.getVolume() + third.getVolume()) / 3;
            boolean volumeConfirmation = avgVol > avgVolume * 1.2;
            double confidence = calculateConfidence(80, avgVol, avgVolume);

            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.THREE_BLACK_CROWS)
                    .symbol(symbol)
                    .timestamp(third.getTimestamp())
                    .intervalMinutes(third.getIntervalMinutes())
                    .candles(List.of(first, second, third))
                    .confidence(confidence)
                    .description("Three Black Crows pattern detected - strong bearish continuation")
                    .priceAtDetection(third.getClose())
                    .resistanceLevel(first.getHigh())
                    .averageVolume(avgVolume)
                    .hasVolumeConfirmation(volumeConfirmation)
                    .build();
        }

        return null;
    }

    /**
     * Detect Bearish Harami pattern
     */
    private PatternRecognitionResult detectBearishHarami(Candle prev, Candle curr, String symbol) {
        if (!prev.isBullish() || !curr.isBearish()) {
            return null;
        }

        boolean prevHasLargeBody = prev.getBodyPercentage() > 60;
        boolean currHasSmallBody = curr.hasSmallBody();

        boolean contained = curr.getOpen() <= prev.getClose() &&
                curr.getClose() >= prev.getOpen();

        if (prevHasLargeBody && currHasSmallBody && contained) {
            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.BEARISH_HARAMI)
                    .symbol(symbol)
                    .timestamp(curr.getTimestamp())
                    .intervalMinutes(curr.getIntervalMinutes())
                    .candles(List.of(prev, curr))
                    .confidence(70)
                    .description("Bearish Harami pattern detected - potential reversal signal")
                    .priceAtDetection(curr.getClose())
                    .resistanceLevel(prev.getHigh())
                    .build();
        }

        return null;
    }

    /**
     * Detect Tweezer Top pattern
     */
    private PatternRecognitionResult detectTweezerTop(Candle prev, Candle curr, String symbol) {
        boolean similarHighs = Math.abs(prev.getHigh() - curr.getHigh()) / prev.getHigh() < PRICE_TOLERANCE;
        boolean correctColors = prev.isBullish() && curr.isBearish();

        if (similarHighs && correctColors) {
            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.TWEEZER_TOP)
                    .symbol(symbol)
                    .timestamp(curr.getTimestamp())
                    .intervalMinutes(curr.getIntervalMinutes())
                    .candles(List.of(prev, curr))
                    .confidence(70)
                    .description("Tweezer Top pattern detected - resistance level confirmed")
                    .priceAtDetection(curr.getClose())
                    .resistanceLevel(Math.max(prev.getHigh(), curr.getHigh()))
                    .build();
        }

        return null;
    }

    /**
     * Detect Gravestone Doji pattern
     */
    private PatternRecognitionResult detectGravestoneDoji(Candle candle, String symbol) {
        if (!candle.isDoji()) {
            return null;
        }

        boolean hasLongUpperShadow = candle.getUpperShadowPercentage() > 60;
        boolean hasShortLowerShadow = candle.getLowerShadowPercentage() < 10;

        if (hasLongUpperShadow && hasShortLowerShadow) {
            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.GRAVESTONE_DOJI)
                    .symbol(symbol)
                    .timestamp(candle.getTimestamp())
                    .intervalMinutes(candle.getIntervalMinutes())
                    .candles(List.of(candle))
                    .confidence(75)
                    .description("Gravestone Doji pattern detected - potential bearish reversal")
                    .priceAtDetection(candle.getClose())
                    .resistanceLevel(candle.getHigh())
                    .build();
        }

        return null;
    }

    // ==================== NEUTRAL PATTERNS ====================

    /**
     * Detect Doji pattern
     */
    private PatternRecognitionResult detectDoji(Candle candle, String symbol) {
        if (candle.isDoji()) {
            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.DOJI)
                    .symbol(symbol)
                    .timestamp(candle.getTimestamp())
                    .intervalMinutes(candle.getIntervalMinutes())
                    .candles(List.of(candle))
                    .confidence(60)
                    .description("Doji pattern detected - indecision in the market")
                    .priceAtDetection(candle.getClose())
                    .build();
        }
        return null;
    }

    /**
     * Detect Spinning Top pattern
     */
    private PatternRecognitionResult detectSpinningTop(Candle candle, String symbol) {
        boolean hasSmallBody = candle.getBodyPercentage() > 10 && candle.getBodyPercentage() < 30;
        boolean hasSignificantShadows = candle.getUpperShadowPercentage() > 30 &&
                candle.getLowerShadowPercentage() > 30;

        if (hasSmallBody && hasSignificantShadows) {
            return PatternRecognitionResult.builder()
                    .pattern(CandlePattern.SPINNING_TOP)
                    .symbol(symbol)
                    .timestamp(candle.getTimestamp())
                    .intervalMinutes(candle.getIntervalMinutes())
                    .candles(List.of(candle))
                    .confidence(60)
                    .description("Spinning Top pattern detected - market indecision")
                    .priceAtDetection(candle.getClose())
                    .build();
        }

        return null;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Calculate confidence score based on volume and other factors
     */
    private double calculateConfidence(double baseConfidence, double currentVolume, double avgVolume) {
        if (avgVolume <= 0) {
            return baseConfidence;
        }

        double volumeRatio = currentVolume / avgVolume;

        // Increase confidence if volume is above average
        if (volumeRatio > 1.5) {
            return Math.min(baseConfidence + 10, 95);
        } else if (volumeRatio > 1.2) {
            return Math.min(baseConfidence + 5, 95);
        } else if (volumeRatio < 0.8) {
            return Math.max(baseConfidence - 10, 50);
        }

        return baseConfidence;
    }

    /**
     * Calculate average volume from a list of candles
     */
    private double calculateAverageVolume(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return 0.0;
        }

        return candles.stream()
                .mapToDouble(Candle::getVolume)
                .average()
                .orElse(0.0);
    }

    /**
     * Get only the most recent patterns (for real-time analysis)
     */
    public List<PatternRecognitionResult> getRecentPatterns(List<PatternRecognitionResult> allPatterns,
                                                            int limit) {
        return allPatterns.stream()
                .sorted(Comparator.comparing(PatternRecognitionResult::getTimestamp).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Filter patterns by type (bullish/bearish)
     */
    public List<PatternRecognitionResult> filterByType(List<PatternRecognitionResult> patterns,
                                                       CandlePattern.PatternType type) {
        return patterns.stream()
                .filter(p -> p.getPattern().getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Filter patterns by minimum confidence
     */
    public List<PatternRecognitionResult> filterByConfidence(List<PatternRecognitionResult> patterns,
                                                             double minConfidence) {
        return patterns.stream()
                .filter(p -> p.getConfidence() >= minConfidence)
                .collect(Collectors.toList());
    }

    /**
     * Check if price is in an uptrend
     * Look back at previous N candles and check if price is generally rising
     */
    private boolean isUptrend(List<Candle> candles, int currentIndex, int lookback) {
        if (currentIndex < lookback) {
            lookback = currentIndex;
        }

        if (lookback < 2) {
            return false; // Not enough data
        }

        int startIndex = currentIndex - lookback;
        Candle startCandle = candles.get(startIndex);
        Candle currentCandle = candles.get(currentIndex);

        // Simple uptrend check: current price > start price + at least 60% of candles are bullish
        boolean priceRising = currentCandle.getClose() > startCandle.getClose();

        long bullishCount = 0;
        for (int i = startIndex; i <= currentIndex; i++) {
            if (candles.get(i).isBullish()) {
                bullishCount++;
            }
        }

        double bullishPercentage = (double) bullishCount / (lookback + 1);

        return priceRising && bullishPercentage >= 0.6;
    }

    /**
     * Check if price is in a downtrend
     * Look back at previous N candles and check if price is generally falling
     */
    private boolean isDowntrend(List<Candle> candles, int currentIndex, int lookback) {
        if (currentIndex < lookback) {
            lookback = currentIndex;
        }

        if (lookback < 2) {
            return false; // Not enough data
        }

        int startIndex = currentIndex - lookback;
        Candle startCandle = candles.get(startIndex);
        Candle currentCandle = candles.get(currentIndex);

        // Simple downtrend check: current price < start price + at least 60% of candles are bearish
        boolean priceFalling = currentCandle.getClose() < startCandle.getClose();

        long bearishCount = 0;
        for (int i = startIndex; i <= currentIndex; i++) {
            if (candles.get(i).isBearish()) {
                bearishCount++;
            }
        }

        double bearishPercentage = (double) bearishCount / (lookback + 1);

        return priceFalling && bearishPercentage >= 0.6;
    }

    /**
     * Check if candle is significant enough to detect patterns
     * Filters out tiny movements (noise)
     */
    private boolean isSignificantCandle(Candle candle) {
        return candle.getRange() >= MIN_CANDLE_RANGE;
    }
}