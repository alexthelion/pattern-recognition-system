package hashita.repository;

import hashita.data.entities.DailyAlertEnhanced;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for DailyAlert entities
 */
@Repository
public interface DailyAlertEnhancedRepository extends MongoRepository<DailyAlertEnhanced, String> {

    /**
     * Find all alerts for a specific date
     */
    List<DailyAlertEnhanced> findByDate(String date);

    /**
     * Delete all alerts for a specific date
     */
    void deleteByDate(String date);

    /**
     * Count alerts for a specific date
     */
    long countByDate(String date);
}