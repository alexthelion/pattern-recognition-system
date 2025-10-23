package hashita.repository;

import hashita.data.entities.TickerVolume;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TickerVolumeRepository extends MongoRepository<TickerVolume, String> {
    
    Optional<TickerVolume> findByStockInfoAndDate(String stockInfo, String date);
    
    List<TickerVolume> findByStockInfo(String stockInfo);
    
    List<TickerVolume> findByStockInfoAndDateBetween(String stockInfo, String startDate, String endDate);
    
    List<TickerVolume> findByDate(String date);
    
    List<TickerVolume> findByDateBetween(String startDate, String endDate);
}
