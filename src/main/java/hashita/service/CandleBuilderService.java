package hashita.service;

import hashita.data.Candle;
import hashita.data.TickData;
import hashita.data.entities.TickerVolume;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ✅ FIXED VERSION: Proper timezone handling
 *
 * CRITICAL TIMEZONE RULES:
 * - Tick data (stocksPrices): Timestamps in ISRAEL time (Asia/Jerusalem, UTC+2/+3)
 * - Volume data (ticker_volume): Epoch seconds in NY time (America/New_York, UTC-5/-4)
 * - Alert data: UTC
 * - ALL OUTPUT CANDLES: UTC timestamps
 */
@Service
@Slf4j
public class CandleBuilderService {

    private static final ZoneId ISRAEL_ZONE = ZoneId.of("Asia/Jerusalem");
    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter TICK_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<Candle> buildCandles(List<TickData> tickData,
                                     List<TickerVolume.IntervalVolume> intervalVolumes,
                                     int intervalMinutes) {

        if (tickData == null || tickData.isEmpty()) {
            log.warn("No tick data provided");
            return Collections.emptyList();
        }

        log.info("Building candles from {} ticks with {} minute interval",
                tickData.size(), intervalMinutes);

        // ✅ Group ticks by interval (Israel time → UTC)
        Map<Instant, List<TickData>> ticksByInterval = groupTicksByInterval(tickData, intervalMinutes);

        // ✅ Create volume map (NY time → UTC)
        Map<Instant, Double> volumeMap = createVolumeMap(intervalVolumes, intervalMinutes);

        // Build candles
        List<Candle> candles = new ArrayList<>();

        for (Map.Entry<Instant, List<TickData>> entry : ticksByInterval.entrySet()) {
            Instant intervalStartUTC = entry.getKey();
            List<TickData> intervalTicks = entry.getValue();

            if (intervalTicks.isEmpty()) continue;

            // Sort by time
            intervalTicks.sort(Comparator.comparing(tick -> parseIsraelTimeToUTC(tick.time())));

            double open = intervalTicks.get(0).price();
            double close = intervalTicks.get(intervalTicks.size() - 1).price();
            double high = intervalTicks.stream().mapToDouble(TickData::price).max().orElse(0.0);
            double low = intervalTicks.stream().mapToDouble(TickData::price).min().orElse(0.0);

            // Get volume for this UTC interval
            double volume = volumeMap.getOrDefault(intervalStartUTC, 0.0);

            candles.add(Candle.builder()
                    .timestamp(intervalStartUTC)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .intervalMinutes(intervalMinutes)
                    .build());
        }

        candles.sort(Comparator.comparing(Candle::getTimestamp));

        log.info("Built {} candles", candles.size());
        return candles;
    }

    /**
     * ✅ Parse Israel time string "2025-10-27 14:00:00" → UTC Instant
     */
    private Instant parseIsraelTimeToUTC(String israelTimeStr) {
        LocalDateTime israelLocal = LocalDateTime.parse(israelTimeStr, TICK_TIME_FORMATTER);
        return israelLocal.atZone(ISRAEL_ZONE).toInstant();
    }

    /**
     * ✅ Group ticks by interval, converting Israel → UTC
     */
    private Map<Instant, List<TickData>> groupTicksByInterval(List<TickData> tickData, int intervalMinutes) {
        return tickData.stream()
                .collect(Collectors.groupingBy(
                        tick -> truncateToInterval(parseIsraelTimeToUTC(tick.time()), intervalMinutes),
                        TreeMap::new,
                        Collectors.toList()
                ));
    }

    private Instant truncateToInterval(Instant timestamp, int intervalMinutes) {
        long epochSeconds = timestamp.getEpochSecond();
        long intervalSeconds = intervalMinutes * 60L;
        return Instant.ofEpochSecond((epochSeconds / intervalSeconds) * intervalSeconds);
    }

    /**
     * ✅ FIXED: Create volume map, converting NY time → UTC
     *
     * Volume epoch seconds are in NY time, so we need to:
     * 1. Treat the epoch as NY time
     * 2. Convert to UTC
     */
    private Map<Instant, Double> createVolumeMap(List<TickerVolume.IntervalVolume> intervalVolumes, int intervalMinutes) {
        if (intervalVolumes == null || intervalVolumes.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Instant, Double> volumeMap = new HashMap<>();

        for (TickerVolume.IntervalVolume iv : intervalVolumes) {
            // Epoch represents NY time, convert to UTC
            Instant nyTimeAsUTC = Instant.ofEpochSecond(iv.getStartEpochSec());
            ZonedDateTime nyZoned = nyTimeAsUTC.atZone(ZoneId.of("UTC")).withZoneSameLocal(NY_ZONE);
            Instant actualUTC = nyZoned.toInstant();

            Instant truncated = truncateToInterval(actualUTC, intervalMinutes);
            volumeMap.put(truncated, iv.getVolume());
        }

        log.debug("Created volume map with {} entries", volumeMap.size());
        return volumeMap;
    }

    public double calculateAverageCandleBody(List<Candle> candles) {
        return candles == null || candles.isEmpty() ? 0.0 :
                candles.stream().mapToDouble(Candle::getBodySize).average().orElse(0.0);
    }

    public double calculateAverageCandleRange(List<Candle> candles) {
        return candles == null || candles.isEmpty() ? 0.0 :
                candles.stream().mapToDouble(Candle::getRange).average().orElse(0.0);
    }

    public double calculateAverageVolume(List<Candle> candles) {
        return candles == null || candles.isEmpty() ? 0.0 :
                candles.stream().mapToDouble(Candle::getVolume).filter(v -> v > 0).average().orElse(0.0);
    }
}