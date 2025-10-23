package hashita.service;

import hashita.data.Candle;
import hashita.data.TickData;
import hashita.data.entities.TickerVolume;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to build candles from tick-by-tick data
 * Handles timezone conversion between Israel (stock data) and New York (volume data)
 */
@Service
@Slf4j
public class CandleBuilderService {
    
    private static final ZoneId ISRAEL_ZONE = ZoneId.of("Asia/Jerusalem");
    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    
    /**
     * Build candles from tick data with volume information
     * 
     * NOTE: Stock data timestamps are in Israel timezone
     *       Volume data timestamps are in New York timezone
     *
     * @param tickData List of price ticks (Israel time)
     * @param intervalVolumes List of volume data per interval (NY time)
     * @param intervalMinutes Interval size (1, 5, 15, etc.)
     * @return List of candles with timestamps in UTC
     */
    public List<Candle> buildCandles(List<TickData> tickData, 
                                     List<TickerVolume.IntervalVolume> intervalVolumes,
                                     int intervalMinutes) {
        
        if (tickData == null || tickData.isEmpty()) {
            log.warn("No tick data provided");
            return Collections.emptyList();
        }
        
        log.debug("Building candles from {} ticks with {} minute interval", 
                 tickData.size(), intervalMinutes);
        
        // Group ticks by interval (convert Israel time to UTC)
        Map<Instant, List<TickData>> ticksByInterval = groupTicksByInterval(tickData, intervalMinutes);
        
        // Create volume map (convert NY time to UTC)
        Map<Instant, Double> volumeMap = createVolumeMap(intervalVolumes);
        
        // Build candles
        List<Candle> candles = new ArrayList<>();
        
        for (Map.Entry<Instant, List<TickData>> entry : ticksByInterval.entrySet()) {
            Instant intervalStartUTC = entry.getKey();
            List<TickData> intervalTicks = entry.getValue();
            
            if (intervalTicks.isEmpty()) {
                continue;
            }
            
            // Sort ticks by timestamp
            intervalTicks.sort(Comparator.comparing(TickData::getParsedTimestamp));
            
            double open = intervalTicks.get(0).price();
            double close = intervalTicks.get(intervalTicks.size() - 1).price();
            double high = intervalTicks.stream()
                    .mapToDouble(TickData::price)
                    .max()
                    .orElse(0.0);
            double low = intervalTicks.stream()
                    .mapToDouble(TickData::price)
                    .min()
                    .orElse(0.0);
            
            // Get volume for this interval (in UTC)
            double volume = volumeMap.getOrDefault(intervalStartUTC, 0.0);
            
            if (volume == 0.0) {
                log.debug("No volume found for interval starting at {}", intervalStartUTC);
            }
            
            Candle candle = Candle.builder()
                    .timestamp(intervalStartUTC)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .intervalMinutes(intervalMinutes)
                    .build();
            
            candles.add(candle);
        }
        
        // Sort candles by timestamp
        candles.sort(Comparator.comparing(Candle::getTimestamp));
        
        log.info("Built {} candles with {} minute interval", candles.size(), intervalMinutes);
        return candles;
    }
    
    /**
     * Group tick data by interval
     * Converts Israel time to UTC
     */
    private Map<Instant, List<TickData>> groupTicksByInterval(List<TickData> tickData, int intervalMinutes) {
        return tickData.stream()
                .collect(Collectors.groupingBy(
                        tick -> {
                            // Tick timestamps are in Israel time, convert to UTC
                            Instant tickInstant = tick.getParsedTimestamp();
                            return truncateToInterval(tickInstant, intervalMinutes);
                        },
                        TreeMap::new,
                        Collectors.toList()
                ));
    }
    
    /**
     * Truncate timestamp to the start of the interval (in UTC)
     */
    private Instant truncateToInterval(Instant timestamp, int intervalMinutes) {
        long epochSeconds = timestamp.getEpochSecond();
        long intervalSeconds = intervalMinutes * 60L;
        long truncatedSeconds = (epochSeconds / intervalSeconds) * intervalSeconds;
        return Instant.ofEpochSecond(truncatedSeconds);
    }
    
    /**
     * Create a map of interval start time (UTC) to volume
     * Converts NY time to UTC for matching with tick data
     */
    private Map<Instant, Double> createVolumeMap(List<TickerVolume.IntervalVolume> intervalVolumes) {
        if (intervalVolumes == null) {
            return Collections.emptyMap();
        }
        
        Map<Instant, Double> volumeMap = new HashMap<>();
        
        for (TickerVolume.IntervalVolume iv : intervalVolumes) {
            // Volume timestamps are in epoch seconds (NY time)
            Instant nyTime = Instant.ofEpochSecond(iv.getStartEpochSec());
            
            // Convert NY time to UTC (they're already stored as epoch, so they're in UTC)
            // The epoch seconds are absolute time points
            Instant utcTime = nyTime;
            
            // Truncate to interval to ensure matching
            // (in case volume intervals don't align perfectly)
            Instant truncatedUTC = truncateToInterval(utcTime, iv.getIntervalMinutes());
            
            volumeMap.put(truncatedUTC, iv.getVolume());
        }
        
        log.debug("Created volume map with {} entries", volumeMap.size());
        return volumeMap;
    }
    
    /**
     * Calculate average candle body size
     */
    public double calculateAverageCandleBody(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return 0.0;
        }
        
        return candles.stream()
                .mapToDouble(Candle::getBodySize)
                .average()
                .orElse(0.0);
    }
    
    /**
     * Calculate average candle range
     */
    public double calculateAverageCandleRange(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return 0.0;
        }
        
        return candles.stream()
                .mapToDouble(Candle::getRange)
                .average()
                .orElse(0.0);
    }
    
    /**
     * Calculate average volume
     */
    public double calculateAverageVolume(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return 0.0;
        }
        
        return candles.stream()
                .mapToDouble(Candle::getVolume)
                .filter(v -> v > 0) // Exclude candles with no volume data
                .average()
                .orElse(0.0);
    }
}
