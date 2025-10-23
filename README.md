# Pattern Recognition Service

**Pure pattern detection service** - Detects bullish and bearish candlestick patterns. No trading logic included.

## 🎯 What This Does

- ✅ Detects 17+ candlestick patterns (Bullish Engulfing, Morning Star, Hammer, etc.)
- ✅ Handles timezone differences (Israel stock data → NY volume data)
- ✅ Provides REST API for pattern queries
- ✅ Returns confidence scores (50-95%)
- ✅ Volume confirmation support
- ✅ Multiple timeframes (1min, 5min, 15min, etc.)

## ⚠️ Important: Timezone Handling

Your data has timestamps in different timezones:

| Data Type | Timezone | Example |
|-----------|----------|---------|
| Stock Prices (tick data) | **Israel** (Asia/Jerusalem) | 14:30:00 Israel Time |
| Volume Data | **New York** (America/New_York) | stored as epoch seconds |
| Candle Output | **UTC** | All returned candles use UTC |

### How It Works

```
Stock Data (Israel Time)          Volume Data (NY Time)
        ↓                                 ↓
    Convert to UTC                   Convert to UTC
        ↓                                 ↓
        └────────── Match by UTC ──────────┘
                         ↓
                Build Candles (UTC timestamps)
                         ↓
                Detect Patterns
```

The `CandleBuilderService` automatically handles all timezone conversions.

## 📦 Quick Start

### Prerequisites
- Java 17+
- Maven 3.6+
- MongoDB with your data in:
  - `stock_daily` collection (Israel time)
  - `ticker_volumes` collection (NY time)

### Installation

```bash
# 1. Extract
unzip pattern-recognition-service.zip
cd pattern-recognition-service

# 2. Configure MongoDB
# Edit src/main/resources/application.properties
spring.data.mongodb.uri=mongodb://localhost:27017/myappdb

# 3. Build
mvn clean install

# 4. Run
mvn spring-boot:run
```

## 🔧 API Endpoints

### 1. Analyze Single Stock
```bash
GET /api/patterns/analyze?symbol=AAPL&date=2025-04-01&interval=5

curl "http://localhost:8080/api/patterns/analyze?symbol=AAPL&date=2025-04-01&interval=5"
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
      "confidence": 85.0,
      "description": "Bullish Engulfing pattern - strong reversal signal",
      "priceAtDetection": 175.50,
      "supportLevel": 174.20,
      "hasVolumeConfirmation": true,
      "signal": "BUY"
    }
  ],
  "summary": {
    "totalPatterns": 12,
    "bullishPatterns": 8,
    "bearishPatterns": 3,
    "neutralPatterns": 1
  }
}
```

### 2. Get Only Bullish Patterns
```bash
GET /api/patterns/bullish?symbol=AAPL&date=2025-04-01&interval=5&minConfidence=75

curl "http://localhost:8080/api/patterns/bullish?symbol=AAPL&date=2025-04-01&interval=5&minConfidence=75"
```

### 3. Get Only Bearish Patterns
```bash
GET /api/patterns/bearish?symbol=AAPL&date=2025-04-01&interval=5&minConfidence=75
```

### 4. Analyze Date Range
```bash
GET /api/patterns/analyze-range?symbol=AAPL&startDate=2025-04-01&endDate=2025-04-30&interval=5
```

### 5. Analyze All Stocks
```bash
GET /api/patterns/analyze-all?date=2025-04-01&interval=5
```

### 6. Get Pattern Summary
```bash
GET /api/patterns/summary?symbol=AAPL&date=2025-04-01&interval=5
```

### 7. Get Strongest Signals
```bash
GET /api/patterns/strongest?symbol=AAPL&date=2025-04-01&interval=5&limit=5
```

### 8. Get Specific Pattern
```bash
GET /api/patterns/specific?symbol=AAPL&date=2025-04-01&pattern=BULLISH_ENGULFING&interval=5
```

## 📊 Supported Patterns

### Bullish (9 patterns)
- Bullish Engulfing
- Hammer  
- Inverted Hammer
- Piercing Line
- Morning Star
- Three White Soldiers
- Tweezer Bottom
- Bullish Harami
- Dragonfly Doji

### Bearish (9 patterns)
- Bearish Engulfing
- Shooting Star
- Hanging Man
- Dark Cloud Cover
- Evening Star
- Three Black Crows
- Tweezer Top
- Bearish Harami
- Gravestone Doji

### Neutral (2 patterns)
- Doji
- Spinning Top

## 💡 Usage Example

```java
@Service
@RequiredArgsConstructor
public class MyService {
    
    private final PatternAnalysisService patternService;
    
    public void checkPatterns() {
        // Analyze AAPL on April 1st with 5-minute candles
        List<PatternRecognitionResult> patterns = 
            patternService.analyzeStockForDate("AAPL", "2025-04-01", 5);
        
        // Filter for high-confidence bullish patterns
        List<PatternRecognitionResult> bullishSignals = patterns.stream()
            .filter(PatternRecognitionResult::isBullish)
            .filter(p -> p.getConfidence() >= 80)
            .filter(PatternRecognitionResult::isHasVolumeConfirmation)
            .toList();
        
        // Process signals
        bullishSignals.forEach(signal -> {
            System.out.println("Found " + signal.getPattern() + 
                             " at " + signal.getPriceAtDetection() +
                             " with " + signal.getConfidence() + "% confidence");
        });
    }
}
```

## 🌍 Timezone Details

### Stock Data (TickData)
- Stored with timestamps in Israel time
- Format: `"yyyy-MM-dd HH:mm:ss"`
- Example: `"2025-04-01 14:30:00"` (Israel time)

### Volume Data (TickerVolume.IntervalVolume)
- Stored as epoch seconds
- Represents New York time
- Fields: `startEpochSec`, `endEpochSec`

### Conversion Process
```java
// 1. Parse Israel time → Instant (UTC)
Instant israelTime = LocalDateTime.parse("2025-04-01 14:30:00")
    .atZone(ZoneId.of("Asia/Jerusalem"))
    .toInstant();  // Converts to UTC

// 2. Volume epoch → Instant (UTC)
Instant nyTime = Instant.ofEpochSecond(startEpochSec);  // Already UTC

// 3. Match by UTC time
// Both are now in UTC and can be matched
```

## 📁 Project Structure

```
pattern-recognition-service/
├── pom.xml
├── README.md
└── src/main/
    ├── java/hashita/
    │   ├── PatternRecognitionApplication.java
    │   ├── controller/
    │   │   └── PatternRecognitionController.java
    │   ├── service/
    │   │   ├── CandleBuilderService.java        # Timezone handling
    │   │   ├── PatternRecognitionService.java   # Pattern detection
    │   │   └── PatternAnalysisService.java      # Orchestration
    │   ├── repository/
    │   │   ├── StockDataRepository.java
    │   │   └── TickerVolumeRepository.java
    │   └── data/
    │       ├── Candle.java
    │       ├── CandlePattern.java
    │       ├── PatternRecognitionResult.java
    │       ├── TickData.java
    │       └── entities/
    │           ├── StockData.java
    │           └── TickerVolume.java
    └── resources/
        └── application.properties
```

## ⚙️ Configuration

Edit `src/main/resources/application.properties`:

```properties
# MongoDB Connection
spring.data.mongodb.uri=mongodb://localhost:27017/myappdb
spring.data.mongodb.database=myappdb

# Server Port
server.port=8080

# Logging
logging.level.hashita=DEBUG
```

## 🔍 Data Requirements

### MongoDB Collections

**stock_daily:**
```javascript
{
  "_id": "...",
  "stockInfo": "AAPL",
  "date": "2025-04-01",
  "entryPrice": 175.0,
  "stocksPrices": [
    { "time": "2025-04-01 09:30:00", "price": 175.10 },  // Israel time
    { "time": "2025-04-01 09:35:00", "price": 175.25 },
    ...
  ]
}
```

**ticker_volumes:**
```javascript
{
  "_id": "...",
  "stockInfo": "AAPL",
  "date": "2025-04-01",
  "intervalVolumes": [
    {
      "intervalStart": "09:30:00",          // NY time string
      "intervalEnd": "09:35:00",            // NY time string
      "startEpochSec": 1711974600,          // Epoch seconds
      "endEpochSec": 1711974900,
      "startEpochMillis": 1711974600000,
      "endEpochMillis": 1711974900000,
      "volume": 125000,
      "intervalMinutes": 5
    },
    ...
  ]
}
```

## 🐛 Troubleshooting

### Issue: No patterns detected
**Solution:** 
- Check if data exists for the date
- Verify interval matches your volume data (1, 5, or 15 min)
- Ensure at least 3 candles are built

### Issue: Volume is zero
**Solution:**
- Check `ticker_volumes` collection has data for the date
- Verify `intervalMinutes` field matches your query
- Check timezone alignment

### Issue: Timestamps don't match
**Solution:**
- Stock data should be in Israel time format: `"yyyy-MM-dd HH:mm:ss"`
- Volume data should have epoch seconds
- The service automatically converts both to UTC

## ✅ Testing

```bash
# Test with your data
curl "http://localhost:8080/api/patterns/analyze?symbol=AAPL&date=2025-04-01&interval=5" | jq

# Check for bullish patterns only
curl "http://localhost:8080/api/patterns/bullish?symbol=AAPL&date=2025-04-01&interval=5&minConfidence=75" | jq

# Get summary
curl "http://localhost:8080/api/patterns/summary?symbol=AAPL&date=2025-04-01&interval=5" | jq
```

## 📈 Expected Output

```json
{
  "pattern": "BULLISH_ENGULFING",
  "symbol": "AAPL",
  "timestamp": "2025-04-01T11:30:00Z",    // UTC
  "intervalMinutes": 5,
  "candles": [...],
  "confidence": 85.0,
  "description": "Bullish Engulfing pattern detected",
  "priceAtDetection": 175.50,
  "supportLevel": 174.20,
  "resistanceLevel": null,
  "averageVolume": 100000,
  "hasVolumeConfirmation": true,
  "signal": "BUY"
}
```

## 🎯 Key Features

1. **No Trading Logic** - Pure pattern detection only
2. **Timezone Aware** - Handles IL→UTC→NY automatically
3. **REST API** - Easy integration from any language
4. **Confidence Scores** - 50-95% with volume confirmation
5. **Volume Validation** - Patterns confirmed by volume get higher scores
6. **Multiple Timeframes** - 1min, 5min, 15min support
7. **Batch Processing** - Analyze multiple stocks/dates

## 📝 Notes

- All candle timestamps returned in **UTC**
- Stock prices in **Israel time** (automatically converted)
- Volumes in **NY time** (automatically converted)
- Pattern detection uses standard technical analysis rules
- Confidence scores based on volume confirmation

---

**Version**: 1.0.0  
**Java**: 17+  
**Spring Boot**: 3.2.0

For questions or issues, check the logs at `DEBUG` level.
