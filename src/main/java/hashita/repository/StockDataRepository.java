package hashita.repository;

import hashita.data.entities.StockData;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockDataRepository extends MongoRepository<StockData, String> {
    
    Optional<StockData> findByStockInfoAndDate(String stockInfo, String date);
    
    List<StockData> findByStockInfo(String stockInfo);
    
    List<StockData> findByStockInfoAndDateBetween(String stockInfo, String startDate, String endDate);
    
    List<StockData> findByDate(String date);
    
    List<StockData> findByDateBetween(String startDate, String endDate);
}
