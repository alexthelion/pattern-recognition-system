# Candlestick Pattern Recognition System - User Guide

## Overview
This system provides comprehensive candlestick pattern recognition for your stock trading algorithm. It analyzes tick-by-tick data stored in MongoDB and identifies bullish, bearish, and neutral patterns to generate trading signals.

## Architecture

### Components
1. **Data Models**
   - `Candle.java` - OHLCV candlestick data structure
   - `CandlePattern.java` - Enum of all supported patterns
   - `PatternRecognitionResult.java` - Pattern detection result with confidence

2. **Services**
   - `CandleBuilderService.java` - Converts tick data to candles
   - `PatternRecognitionService.java` - Detects all candlestick patterns
   - `PatternAnalysisService.java` - Orchestrates pattern analysis

3. **Data Access**
   - `StockDataRepository.java` - Access to stock_daily collection
   - `TickerVolumeRepository.java` - Access to ticker_volumes collection

4. **REST API**
   - `PatternRecognitionController.java` - RESTful endpoints

## Supported Patterns

### Bullish Patterns (8)
1. **Bullish Engulfing** - Strong reversal signal
2. **Hammer** - Potential bottom formation
3. **Inverted Hammer** - Bullish reversal after downtrend
4. **Piercing Line** - Bullish reversal
5. **Morning Star** - Three-candle bullish reversal
6. **Three White Soldiers** - Strong bullish continuation
7. **Tweezer Bottom** - Support confirmation
8. **Bullish Harami** - Potential reversal
9. **Dragonfly Doji** - Bullish indecision at support

### Bearish Patterns (8)
1. **Bearish Engulfing** - Strong reversal signal
2. **Shooting Star** - Potential top formation
3. **Hanging Man** - Bearish reversal after uptrend
4. **Dark Cloud Cover** - Bearish reversal
5. **Evening Star** - Three-candle bearish reversal
6. **Three Black Crows** - Strong bearish continuation
7. **Tweezer Top** - Resistance confirmation
8. **Bearish Harami** - Potential reversal
9. **Gravestone Doji** - Bearish indecision at resistance

### Neutral Patterns (2)
1. **Doji** - Market indecision
2. **Spinning Top** - Uncertainty

## Installation

### 1. Add to your Spring Boot project
Copy all files to your project structure:
```
hashita/
├── data/
│   ├── Candle.java
│   ├── CandlePattern.java
│   ├── PatternRecognitionResult.java
│   └── entities/
│       ├── StockData.java (existing)
│       └── TickerVolume.java (existing)
├── service/
│   ├── CandleBuilderService.java
│   ├── PatternRecognitionService.java
│   └── PatternAnalysisService.java
├── repository/
│   ├── StockDataRepository.java
│   └── TickerVolumeRepository.java
└── controller/
    └── PatternRecognitionController.java
```

### 2. Dependencies (add to pom.xml if needed)
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
</dependency>
```

### 3. MongoDB Configuration
Ensure your application.properties has MongoDB configuration:
```properties
spring.data.mongodb.uri=mongodb://localhost:27017/myappdb
```

## API Usage Examples

### 1. Analyze a Single Stock on a Specific Date
```bash
GET http://localhost:8080/api/patterns/analyze?symbol=AAPL&date=2025-04-01&interval=5
```

**Response:**
```json
{
  "symbol": "AAPL",
  "date": "2025-04-01",
  "intervalMinutes": 5,
  "patterns": [
    {
      "pattern": "BULLISH_ENGULFING",
      "symbol": "AAPL",
      "timestamp": "2025-04-01T14:30:00Z",
      "intervalMinutes": 5,
      "candles": [...],
      "confidence": 85.0,
      "description": "Bullish Engulfing pattern detected - strong reversal signal",
      "priceAtDetection": 175.50,
      "supportLevel": 174.20,
      "averageVolume": 1500000,
      "hasVolumeConfirmation": true,
      "signal": "BUY"
    }
  ],
  "summary": {
    "totalPatterns": 12,
    "bullishPatterns": 8,
    "bearishPatterns": 3,
    "neutralPatterns": 1,
    "patternCounts": {
      "BULLISH_ENGULFING": 2,
      "HAMMER": 3,
      "MORNING_STAR": 1
    }
  },
  "strongestSignals": [...]
}
```

### 2. Get Only Bullish Signals
```bash
GET http://localhost:8080/api/patterns/bullish?symbol=AAPL&date=2025-04-01&interval=5&minConfidence=75
```

### 3. Get Only Bearish Signals
```bash
GET http://localhost:8080/api/patterns/bearish?symbol=AAPL&date=2025-04-01&interval=5&minConfidence=75
```

### 4. Analyze Date Range
```bash
GET http://localhost:8080/api/patterns/analyze-range?symbol=AAPL&startDate=2025-04-01&endDate=2025-04-30&interval=5
```

### 5. Analyze All Stocks on a Date
```bash
GET http://localhost:8080/api/patterns/analyze-all?date=2025-04-01&interval=5
```

### 6. Get Pattern Summary
```bash
GET http://localhost:8080/api/patterns/summary?symbol=AAPL&date=2025-04-01&interval=5
```

### 7. Get Strongest Signals
```bash
GET http://localhost:8080/api/patterns/strongest?symbol=AAPL&date=2025-04-01&interval=5&limit=5
```

## Integration with Your Trading System

### Option 1: Service Integration
```java
@Service
@RequiredArgsConstructor
public class TradingDecisionService {
    
    private final PatternAnalysisService patternAnalysisService;
    
    public void analyzeAndTrade(String symbol, String date) {
        // Get patterns
        List<PatternRecognitionResult> patterns = 
            patternAnalysisService.analyzeStockForDate(symbol, date, 5);
        
        // Filter high confidence bullish patterns
        List<PatternRecognitionResult> buySignals = patterns.stream()
            .filter(PatternRecognitionResult::isBullish)
            .filter(p -> p.getConfidence() >= 80)
            .filter(PatternRecognitionResult::isHasVolumeConfirmation)
            .toList();
        
        // Make trading decision
        for (PatternRecognitionResult signal : buySignals) {
            if (signal.getPattern() == CandlePattern.BULLISH_ENGULFING ||
                signal.getPattern() == CandlePattern.MORNING_STAR) {
                // Strong buy signal - place order
                placeOrder(symbol, signal);
            }
        }
    }
    
    private void placeOrder(String symbol, PatternRecognitionResult signal) {
        // Your existing order placement logic
        // Use signal.getPriceAtDetection() as entry price
        // Use signal.getSupportLevel() for stop loss
    }
}
```

### Option 2: Scheduled Analysis
```java
@Component
@RequiredArgsConstructor
public class PatternScanner {
    
    private final PatternAnalysisService patternAnalysisService;
    private final AlertService alertService;
    
    @Scheduled(cron = "0 */5 * * * *") // Every 5 minutes
    public void scanForPatterns() {
        String today = LocalDate.now().toString();
        
        // Get all stocks with data today
        Map<String, List<PatternRecognitionResult>> patterns = 
            patternAnalysisService.analyzeAllStocksForDate(today, 5);
        
        // Find strong signals
        patterns.forEach((symbol, patternList) -> {
            List<PatternRecognitionResult> strongSignals = 
                patternAnalysisService.findStrongestSignals(patternList, 3);
            
            // Send alerts for strong signals
            strongSignals.forEach(signal -> 
                alertService.sendAlert(symbol, signal));
        });
    }
}
```

### Option 3: Real-time Integration
```java
@Service
@RequiredArgsConstructor
public class RealtimePatternDetector {
    
    private final CandleBuilderService candleBuilderService;
    private final PatternRecognitionService patternRecognitionService;
    
    // Call this when new tick data arrives
    public void onNewTickData(String symbol, TickData newTick) {
        // Get recent ticks (last N periods)
        List<TickData> recentTicks = getRecentTicks(symbol, 20);
        
        // Build candles
        List<Candle> candles = candleBuilderService.buildCandles(
            recentTicks, 
            getRecentVolumes(symbol),
            5
        );
        
        // Scan for patterns
        List<PatternRecognitionResult> patterns = 
            patternRecognitionService.scanForPatterns(candles, symbol);
        
        // Check for new patterns
        if (!patterns.isEmpty()) {
            PatternRecognitionResult latest = patterns.get(patterns.size() - 1);
            if (latest.getConfidence() >= 75) {
                // Act on the pattern
                handlePattern(symbol, latest);
            }
        }
    }
}
```

## Advanced Features

### Custom Pattern Filtering
```java
// Get only high-confidence volume-confirmed bullish patterns
List<PatternRecognitionResult> highConfidenceSignals = patterns.stream()
    .filter(PatternRecognitionResult::isBullish)
    .filter(p -> p.getConfidence() >= 80)
    .filter(PatternRecognitionResult::isHasVolumeConfirmation)
    .filter(p -> p.getPattern() == CandlePattern.BULLISH_ENGULFING ||
                 p.getPattern() == CandlePattern.MORNING_STAR ||
                 p.getPattern() == CandlePattern.THREE_WHITE_SOLDIERS)
    .toList();
```

### Pattern-Based Entry/Exit Strategy
```java
public TradingStrategy createStrategyFromPattern(PatternRecognitionResult pattern) {
    return TradingStrategy.builder()
        .entryPrice(pattern.getPriceAtDetection())
        .stopLoss(pattern.getSupportLevel())
        .takeProfit(calculateTakeProfit(pattern))
        .signal(pattern.getSignal())
        .confidence(pattern.getConfidence())
        .build();
}

private double calculateTakeProfit(PatternRecognitionResult pattern) {
    double entryPrice = pattern.getPriceAtDetection();
    double supportLevel = pattern.getSupportLevel();
    double riskAmount = entryPrice - supportLevel;
    
    // Use 2:1 risk-reward ratio
    return entryPrice + (riskAmount * 2);
}
```

## Configuration Options

### Candle Intervals
The system supports any interval in minutes:
- 1 minute (for high-frequency trading)
- 5 minutes (default, good balance)
- 15 minutes
- 30 minutes
- 60 minutes (1 hour)

### Confidence Thresholds
Pattern confidence ranges from 50-95:
- 50-65: Low confidence
- 65-75: Medium confidence
- 75-85: High confidence
- 85-95: Very high confidence

### Volume Confirmation
Patterns with volume confirmation (current volume > 1.2x average) get higher confidence scores.

## Performance Considerations

### Optimization Tips
1. **Use appropriate intervals**: 5-minute candles are optimal for most day trading
2. **Filter by confidence**: Only act on patterns with >75% confidence
3. **Consider volume**: Patterns with volume confirmation are more reliable
4. **Combine patterns**: Multiple patterns on same timeframe increase probability
5. **Cache results**: Cache pattern analysis results for frequently queried dates

### Database Indexes
Ensure these indexes exist in MongoDB:
```javascript
// stock_daily collection
db.stock_daily.createIndex({ "stockInfo": 1, "date": 1 }, { unique: true })
db.stock_daily.createIndex({ "date": 1 })

// ticker_volumes collection
db.ticker_volumes.createIndex({ "stockInfo": 1, "date": 1 }, { unique: true })
db.ticker_volumes.createIndex({ "date": 1 })
```

## Testing

### Unit Test Example
```java
@SpringBootTest
class PatternRecognitionServiceTest {
    
    @Autowired
    private PatternRecognitionService patternRecognitionService;
    
    @Test
    void testBullishEngulfingDetection() {
        // Create test candles
        Candle bearishCandle = Candle.builder()
            .open(100).high(102).low(98).close(99)
            .volume(1000).build();
            
        Candle bullishCandle = Candle.builder()
            .open(98).high(105).low(97).close(104)
            .volume(1500).build();
        
        List<Candle> candles = List.of(bearishCandle, bullishCandle);
        
        // Detect patterns
        List<PatternRecognitionResult> patterns = 
            patternRecognitionService.scanForPatterns(candles, "TEST");
        
        // Verify bullish engulfing is detected
        assertTrue(patterns.stream()
            .anyMatch(p -> p.getPattern() == CandlePattern.BULLISH_ENGULFING));
    }
}
```

## Troubleshooting

### Common Issues

1. **No patterns detected**
   - Check if data exists for the requested date
   - Verify interval matches your volume data
   - Ensure at least 3 candles are available

2. **Low confidence scores**
   - Pattern is weak or incomplete
   - Volume confirmation is missing
   - Consider using longer intervals

3. **Too many patterns**
   - Increase confidence threshold
   - Filter by specific pattern types
   - Use only volume-confirmed patterns

## Support and Customization

To add custom patterns:
1. Add pattern to `CandlePattern` enum
2. Create detection method in `PatternRecognitionService`
3. Add to appropriate scanning method (single/two/three candle)

To modify confidence calculation:
- Adjust the `calculateConfidence()` method
- Consider additional factors (trend, support/resistance levels)

## License
This system is part of your proprietary trading system.
