# API Response Fields Documentation

## 📊 Complete Field Reference for All Endpoints

---

## Real-Time Pattern Detection

### GET /api/realtime/patterns

**Response Structure:**
```json
{
  "symbol": "AAPL",
  "currentPrice": 175.45,
  "priceIsRealTime": true,
  "priceAgeMinutes": 0,
  "latestCandlePrice": 175.23,
  "latestCandleTime": "2025-10-30T14:30:00Z",
  "candleAgeMinutes": 5,
  "priceChangeFromPattern": 0.22,
  "priceChangePercent": 0.13,
  "priceWarning": null,
  "requestedDatetime": "2025-10-30T14:35:00Z",
  "latestPrice": 175.45,
  "minQuality": 70,
  "direction": "LONG",
  "patternType": "ALL",
  "filtersApplied": true,
  "totalPatterns": 2,
  "processingTimeMs": 245,
  "patterns": [...]
}
```

#### Top-Level Fields:

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `symbol` | String | Stock symbol | "AAPL" |
| `currentPrice` | Double | Current market price (real-time or candle) | 175.45 |
| `priceIsRealTime` | Boolean | **True** = From IBKR live data<br>**False** = From last candle (fallback) | true |
| `priceAgeMinutes` | Integer | Age of current price in minutes<br>**0** = Live<br>**>0** = Stale | 0 |
| `latestCandlePrice` | Double | Close price of most recent candle | 175.23 |
| `latestCandleTime` | ISO DateTime | Timestamp of latest candle | "2025-10-30T14:30:00Z" |
| `candleAgeMinutes` | Integer | How many minutes ago the latest candle formed | 5 |
| `priceChangeFromPattern` | Double | Price change from first pattern's entry to current<br>**Positive** = Profit<br>**Negative** = Loss | 0.22 |
| `priceChangePercent` | Double | Percentage change from first pattern's entry<br>**Positive** = Profit %<br>**Negative** = Loss % | 0.13 |
| `priceWarning` | String (nullable) | Warning message if price is stale<br>**null** = No warning<br>**String** = "Price is X minutes old..." | null |
| `requestedDatetime` | ISO DateTime | The time requested (usually now) | "2025-10-30T14:35:00Z" |
| `latestPrice` | Double | **Deprecated** - Same as `currentPrice` (for backward compatibility) | 175.45 |
| `minQuality` | Integer | Minimum quality filter applied (0-100) | 70 |
| `direction` | String | Trade direction filter<br>**LONG** = Bullish only<br>**SHORT** = Bearish only<br>**ALL** = Both | "LONG" |
| `patternType` | String | Pattern type filter<br>**ALL** = All patterns<br>**CHART_ONLY** = Chart patterns<br>**CANDLESTICK_ONLY** = Candlestick patterns<br>**STRONG_ONLY** = High-confidence only | "ALL" |
| `filtersApplied` | Boolean | Whether quality filters were applied<br>**true** = Filtered<br>**false** = Raw patterns | true |
| `totalPatterns` | Integer | Number of patterns found | 2 |
| `processingTimeMs` | Integer | Processing time in milliseconds | 245 |
| `patterns` | Array | Array of pattern objects (see below) | [...] |

---

### Pattern Object Fields:

**Pattern Object in `patterns` array:**
```json
{
  "symbol": "AAPL",
  "pattern": "FALLING_WEDGE",
  "reason": "FALLING WEDGE + DOUBLE BOTTOM (CONFLUENCE: 2 patterns) at $175.23 (85% conf)\n📊 Patterns:\n  • FALLING WEDGE (75% quality)\n  • DOUBLE BOTTOM (70% quality)",
  "confidence": 85.5,
  "signalQuality": 78.2,
  "entryPrice": 175.23,
  "target": 182.50,
  "stopLoss": 172.10,
  "riskPercent": 1.79,
  "rewardPercent": 4.15,
  "riskRewardRatio": 2.32,
  "direction": "LONG",
  "timestamp": "2025-10-30T14:30:00Z",
  "timestampIsrael": "2025-10-30 16:30:00",
  "ageMinutes": 5,
  "urgency": "MODERATE",
  "volume": 1250000,
  "avgVolume": 950000,
  "volumeRatio": 1.32,
  "hasVolumeConfirmation": true,
  "isChartPattern": true,
  "isConfluence": true,
  "confluenceCount": 2,
  "confluentPatterns": ["FALLING_WEDGE", "DOUBLE_BOTTOM"],
  "isFresh": true
}
```

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `symbol` | String | Stock symbol | "AAPL" |
| `pattern` | String | Primary pattern type (enum name) | "FALLING_WEDGE" |
| `reason` | String | Human-readable explanation with all details | "FALLING WEDGE + DOUBLE BOTTOM..." |
| `confidence` | Double | Pattern detection confidence (0-100)<br>**80-100** = Excellent<br>**70-79** = Good<br>**60-69** = Moderate | 85.5 |
| `signalQuality` | Double | Overall trade quality score (0-100)<br>Combines confidence, R:R, volume, trend | 78.2 |
| `entryPrice` | Double | **Calculated entry price** using typical price formula<br>`(High + Low + Close) / 3`<br>**Not from wick!** | 175.23 |
| `target` | Double | Target price (take profit)<br>Calculated from pattern projection | 182.50 |
| `stopLoss` | Double | Stop loss price<br>Below support (LONG) or above resistance (SHORT) | 172.10 |
| `riskPercent` | Double | Risk percentage from entry to stop<br>`(Entry - StopLoss) / Entry * 100` | 1.79 |
| `rewardPercent` | Double | Reward percentage from entry to target<br>`(Target - Entry) / Entry * 100` | 4.15 |
| `riskRewardRatio` | Double | Risk/Reward ratio<br>`RewardPercent / RiskPercent`<br>**Minimum 2.0** for good trades | 2.32 |
| `direction` | String | Trade direction<br>**LONG** = Bullish (buy)<br>**SHORT** = Bearish (sell/short) | "LONG" |
| `timestamp` | ISO DateTime | When pattern formed (UTC) | "2025-10-30T14:30:00Z" |
| `timestampIsrael` | String | Pattern time in Israel timezone (for you!) | "2025-10-30 16:30:00" |
| `ageMinutes` | Integer | How many minutes ago pattern formed<br>**<10** = Fresh<br>**10-30** = Moderate<br>**>30** = Old | 5 |
| `urgency` | String | Entry urgency classification<br>**URGENT** = Enter now (age <5min)<br>**MODERATE** = Consider entry (5-15min)<br>**WATCH** = Too old, watch for re-entry (>15min) | "MODERATE" |
| `volume` | Integer | Volume on pattern candle | 1250000 |
| `avgVolume` | Integer | Average volume (20-period) | 950000 |
| `volumeRatio` | Double | Volume ratio vs average<br>`Volume / AvgVolume`<br>**>1.5** = High volume (good!)<br>**<0.8** = Low volume (weak) | 1.32 |
| `hasVolumeConfirmation` | Boolean | Whether pattern has volume confirmation<br>**true** = Volume > 1.5x average<br>**false** = Normal/low volume | true |
| `isChartPattern` | Boolean | **true** = Chart pattern (Wedge, Flag, etc)<br>**false** = Candlestick pattern (Engulfing, etc) | true |
| `isConfluence` | Boolean | **true** = Multiple patterns at same level<br>**false** = Single pattern | true |
| `confluenceCount` | Integer | Number of confluent patterns (if confluence)<br>**2+** = Strong confluence<br>**null/0** = No confluence | 2 |
| `confluentPatterns` | Array[String] | List of all patterns in confluence | ["FALLING_WEDGE", "DOUBLE_BOTTOM"] |
| `isFresh` | Boolean | Whether pattern is fresh enough to trade<br>**true** = Age < (interval * 2)<br>**false** = Too old | true |

---

### GET /api/realtime/latest

**Response Structure:**
```json
{
  "symbol": "AAPL",
  "currentPrice": 175.45,
  "priceIsRealTime": true,
  "priceAgeMinutes": 0,
  "priceWarning": null,
  "unrealizedPnL": 0.22,
  "unrealizedPnLPercent": 0.13,
  "latestPrice": 175.45,
  "latestPattern": {...},
  "isFresh": true,
  "ageMinutes": 5,
  "recommendation": "CONSIDER_ENTRY"
}
```

#### Additional Fields (beyond /patterns):

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `unrealizedPnL` | Double | Profit/Loss from entry to current price<br>**Positive** = Profit<br>**Negative** = Loss<br>**Null** = No price data | 0.22 |
| `unrealizedPnLPercent` | Double | P&L as percentage<br>`(CurrentPrice - EntryPrice) / EntryPrice * 100` | 0.13 |
| `latestPattern` | Object | The most recent pattern object (same fields as above) | {...} |
| `isFresh` | Boolean | Whether latest pattern is fresh<br>**true** = Age < (interval * 2)<br>**false** = Too old to enter | true |
| `ageMinutes` | Integer | Age of latest pattern in minutes | 5 |
| `recommendation` | String | Trading recommendation<br>**CONSIDER_ENTRY** = Fresh, good to enter<br>**PATTERN_TOO_OLD** = Too old, wait for new pattern | "CONSIDER_ENTRY" |

---

### POST /api/realtime/scan

**Request:**
```json
{
  "symbols": ["AAPL", "TSLA", "NVDA"],
  "minQuality": 70,
  "direction": "LONG",
  "patternType": "STRONG_ONLY"
}
```

**Response Structure:**
```json
{
  "scannedSymbols": 3,
  "patternsFound": 5,
  "processingTimeMs": 850,
  "results": [
    {
      "symbol": "AAPL",
      "currentPrice": 175.45,
      "priceIsRealTime": true,
      "patterns": [...],
      "totalPatterns": 2,
      "topQuality": 85.5,
      "hasConfluence": true
    },
    {
      "symbol": "TSLA",
      "currentPrice": 245.67,
      "priceIsRealTime": true,
      "patterns": [...],
      "totalPatterns": 1,
      "topQuality": 72.3,
      "hasConfluence": false
    }
  ]
}
```

#### Scan Response Fields:

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `scannedSymbols` | Integer | Number of symbols scanned | 3 |
| `patternsFound` | Integer | Total patterns found across all symbols | 5 |
| `processingTimeMs` | Integer | Total processing time | 850 |
| `results` | Array | Array of per-symbol results | [...] |

#### Per-Symbol Result Fields:

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `symbol` | String | Stock symbol | "AAPL" |
| `currentPrice` | Double | Current market price | 175.45 |
| `priceIsRealTime` | Boolean | Whether price is live | true |
| `patterns` | Array | Array of patterns for this symbol | [...] |
| `totalPatterns` | Integer | Number of patterns found | 2 |
| `topQuality` | Double | Highest quality score among patterns | 85.5 |
| `hasConfluence` | Boolean | Whether any patterns have confluence | true |

---

## Historical Alert Simulation

### POST /api/alerts/simulate

**Request:**
```json
{
  "date": "2025-10-15",
  "symbols": ["AAPL", "TSLA", "NVDA"],
  "minQuality": 70,
  "direction": "LONG"
}
```

**Response Structure:**
```json
{
  "date": "2025-10-15",
  "totalSymbols": 3,
  "alertsFound": 5,
  "processingTimeMs": 1250,
  "tickers": [
    {
      "symbol": "AAPL",
      "entryPrice": 175.23,
      "target": 182.50,
      "stopLoss": 172.10,
      "pattern": "FALLING_WEDGE",
      "confidence": 85.5,
      "signalQuality": 78.2,
      "riskRewardRatio": 2.32,
      "direction": "LONG",
      "timestamp": "2025-10-15T14:30:00Z",
      "isConfluence": true,
      "confluenceCount": 2,
      "confluentPatterns": ["FALLING_WEDGE", "DOUBLE_BOTTOM"],
      "reason": "FALLING WEDGE + DOUBLE BOTTOM..."
    }
  ]
}
```

#### Alert Simulation Fields:

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `date` | String | Date simulated (ISO format) | "2025-10-15" |
| `totalSymbols` | Integer | Number of symbols checked | 3 |
| `alertsFound` | Integer | Number of quality patterns found | 5 |
| `processingTimeMs` | Integer | Processing time | 1250 |
| `tickers` | Array | Array of alert objects (same as pattern objects above) | [...] |

---

## Price Information Fields

### Understanding Price Fields:

#### 1. **Real-Time Price** (During Market Hours with IBKR):
```json
{
  "currentPrice": 175.45,        // From IBKR (live!)
  "priceIsRealTime": true,       // ✅ Live data
  "priceAgeMinutes": 0,          // ✅ Fresh
  "latestCandlePrice": 175.23,   // Last 5-min candle
  "candleAgeMinutes": 5          // 5 minutes ago
}
```

#### 2. **Fallback Price** (IBKR Unavailable or After-Hours):
```json
{
  "currentPrice": 175.23,        // From last candle
  "priceIsRealTime": false,      // ⚠️ Not live
  "priceAgeMinutes": 16,         // ⚠️ 16 minutes old
  "priceWarning": "Price is 16 minutes old. Real-time price unavailable.",
  "latestCandlePrice": 175.23,   // Same as current
  "candleAgeMinutes": 16
}
```

---

## Pattern Type Enums

### CandlePattern Types:

| Pattern | Type | Direction | Description |
|---------|------|-----------|-------------|
| `FALLING_WEDGE` | Chart | Bullish | Narrowing downtrend, breakout up |
| `RISING_WEDGE` | Chart | Bearish | Narrowing uptrend, breakdown |
| `BULL_FLAG` | Chart | Bullish | Consolidation after uptrend |
| `BEAR_FLAG` | Chart | Bearish | Consolidation after downtrend |
| `DOUBLE_BOTTOM` | Chart | Bullish | Two lows at same level |
| `DOUBLE_TOP` | Chart | Bearish | Two highs at same level |
| `HEAD_AND_SHOULDERS` | Chart | Bearish | Three peaks, middle highest |
| `INVERSE_HEAD_AND_SHOULDERS` | Chart | Bullish | Three valleys, middle lowest |
| `ASCENDING_TRIANGLE` | Chart | Bullish | Flat resistance, rising support |
| `DESCENDING_TRIANGLE` | Chart | Bearish | Flat support, falling resistance |
| `SYMMETRICAL_TRIANGLE` | Chart | Neutral | Converging trendlines |
| `BULLISH_ENGULFING` | Candlestick | Bullish | Green engulfs previous red |
| `BEARISH_ENGULFING` | Candlestick | Bearish | Red engulfs previous green |
| `HAMMER` | Candlestick | Bullish | Small body, long lower wick |
| `SHOOTING_STAR` | Candlestick | Bearish | Small body, long upper wick |
| `MORNING_STAR` | Candlestick | Bullish | Three-candle reversal |
| `EVENING_STAR` | Candlestick | Bearish | Three-candle reversal |
| `DOJI` | Candlestick | Neutral | Equal open/close |
| `DRAGONFLY_DOJI` | Candlestick | Bullish | Long lower wick |
| `GRAVESTONE_DOJI` | Candlestick | Bearish | Long upper wick |
| `PIERCING_PATTERN` | Candlestick | Bullish | Green closes above red midpoint |

---

## Urgency Classification

| Urgency | Age | Recommendation |
|---------|-----|----------------|
| `URGENT` | 0-5 min | Enter immediately, pattern very fresh |
| `MODERATE` | 5-15 min | Consider entry, pattern still good |
| `WATCH` | 15+ min | Too old, wait for new pattern or re-entry |

---

## Signal Quality Ranges

| Range | Classification | Description |
|-------|---------------|-------------|
| 80-100 | **Excellent** | Rare, high probability, all factors aligned |
| 70-79 | **Good** | Worth taking, good R:R and confidence |
| 60-69 | **Moderate** | Requires confirmation, risky |
| 0-59 | **Poor** | Filtered out (unless `applyFilters=false`) |

---

## Risk/Reward Ratio

**Formula:** `RiskRewardRatio = RewardPercent / RiskPercent`

| Ratio | Quality | Description |
|-------|---------|-------------|
| 3.0+ | **Excellent** | Triple your risk, rare |
| 2.0-2.9 | **Good** | Double your risk, standard minimum |
| 1.5-1.9 | **Moderate** | Marginal, not recommended |
| <1.5 | **Poor** | Risk > reward, never trade |

---

## Volume Confirmation

**Volume Ratio:** `volumeRatio = volume / avgVolume`

| Ratio | Classification | Description |
|-------|---------------|-------------|
| >2.0 | **Very High** | Strong confirmation, institutional activity |
| 1.5-2.0 | **High** | Good confirmation, above average |
| 1.0-1.5 | **Normal** | Average volume, neutral |
| 0.5-1.0 | **Low** | Below average, weak confirmation |
| <0.5 | **Very Low** | Poor confirmation, avoid |

`hasVolumeConfirmation = true` when `volumeRatio >= 1.5`

---

## Entry Price Calculation

**Traditional (WRONG):**
```java
entryPrice = candle.getClose();  // Can be from wick!
```

**Hashita (CORRECT):**
```java
entryPrice = (candle.getHigh() + candle.getLow() + candle.getClose()) / 3.0;
```

**Example:**
```
Candle: O=$175.00, H=$176.50, L=$174.80, C=$176.30

Traditional: $176.30 (close, near spike)
Hashita:     $175.87 (typical price, realistic)
Improvement: 0.6% more conservative
```

---

## Timestamp Formats

### UTC (Standard):
```json
"timestamp": "2025-10-30T14:30:00Z"
```
- ISO 8601 format
- Z suffix = UTC timezone
- Used for all system operations

### Israel Timezone (For Display):
```json
"timestampIsrael": "2025-10-30 16:30:00"
```
- Local Israel time (UTC+2 or UTC+3)
- Human-readable format
- For display only

---

## Error Responses

### Invalid Input:
```json
{
  "error": "INVALID_INPUT",
  "message": "Symbol must be 1-5 uppercase letters",
  "symbol": "INVALID123"
}
```

### Internal Error:
```json
{
  "error": "INTERNAL_ERROR",
  "message": "Failed to get patterns",
  "symbol": "AAPL"
}
```

### No Patterns Found:
```json
{
  "symbol": "AAPL",
  "message": "No patterns found",
  "totalPatterns": 0,
  "patterns": []
}
```

### Insufficient Data:
```json
{
  "symbol": "AAPL",
  "message": "Insufficient data (need 10+ candles)",
  "patterns": []
}
```

---

## Summary

### Key Price Fields:
- `currentPrice` - The most important! (real-time or fallback)
- `priceIsRealTime` - Is it live? (true/false)
- `priceAgeMinutes` - How fresh? (0 = live, >0 = stale)

### Key Pattern Fields:
- `entryPrice` - Where to enter (typical price, not wick!)
- `target` - Where to take profit
- `stopLoss` - Where to cut losses
- `riskRewardRatio` - Risk/reward (minimum 2.0)

### Key Quality Fields:
- `signalQuality` - Overall score (70+ = tradeable)
- `confidence` - Pattern confidence (70+ = good)
- `volumeRatio` - Volume strength (1.5+ = confirmed)

### Key Timing Fields:
- `ageMinutes` - Pattern freshness (<10 = fresh)
- `urgency` - Entry urgency (URGENT/MODERATE/WATCH)
- `isFresh` - Ready to trade? (true/false)

### Key Confluence Fields:
- `isConfluence` - Multiple patterns? (true/false)
- `confluenceCount` - How many? (2+ = strong)
- `confluentPatterns` - Which patterns? (array)

---

**All fields are now fully documented!** 🎯