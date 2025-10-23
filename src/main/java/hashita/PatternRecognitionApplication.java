package hashita;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Pattern Recognition Application
 * 
 * Detects bullish and bearish candlestick patterns from tick-by-tick data
 * 
 * Features:
 * - 17+ candlestick pattern detection
 * - Handles timezone differences (Israel stock data, NY volume data)
 * - REST API for pattern queries
 * - Confidence scoring with volume confirmation
 * - Multiple timeframe support
 */
@SpringBootApplication
@EnableMongoRepositories
public class PatternRecognitionApplication {

    public static void main(String[] args) {
        SpringApplication.run(PatternRecognitionApplication.class, args);
        System.out.println("\n" +
                "╔══════════════════════════════════════════════════════╗\n" +
                "║   Pattern Recognition Service - STARTED             ║\n" +
                "║                                                      ║\n" +
                "║   🎯 Detects 17+ candlestick patterns               ║\n" +
                "║   📊 API: http://localhost:8080/api/patterns        ║\n" +
                "║   🌍 Handles IL→UTC→NY timezone conversion          ║\n" +
                "║                                                      ║\n" +
                "║   Quick Test:                                        ║\n" +
                "║   curl \"http://localhost:8080/api/patterns/\\       ║\n" +
                "║         analyze?symbol=AAPL&date=2025-04-01\"       ║\n" +
                "╚══════════════════════════════════════════════════════╝\n");
    }
}
