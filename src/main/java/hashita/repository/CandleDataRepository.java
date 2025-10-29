package hashita.repository;

import hashita.data.entities.CandleData;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CandleDataRepository extends MongoRepository<CandleData, String> {

    /**
     * Find candles for a specific symbol, date, and interval
     */
    Optional<CandleData> findBySymbolAndDateAndIntervalMinutes(String symbol, String date, int intervalMinutes);

    /**
     * Find all candles for a symbol (all dates)
     */
    List<CandleData> findBySymbol(String symbol);

    /**
     * Find all candles for a specific date (all symbols)
     */
    List<CandleData> findByDate(String date);

    /**
     * Find all candles for a symbol within a date range
     */
    List<CandleData> findBySymbolAndDateBetween(String symbol, String startDate, String endDate);

    /**
     * Check if candles exist for symbol/date/interval
     */
    boolean existsBySymbolAndDateAndIntervalMinutes(String symbol, String date, int intervalMinutes);

    /**
     * Delete candles for a specific symbol/date
     */
    void deleteBySymbolAndDate(String symbol, String date);
}