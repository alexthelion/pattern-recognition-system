# Debug Commands for Pattern Recognition Service

## 🔍 Quick Diagnosis Commands

### 1. Check Raw Tick Data at 16:20
```bash
curl "http://localhost:8080/api/debug/ticks?symbol=RR&date=2025-10-01&startTime=16:15&endTime=16:30" | jq
```

**What to check:**
- Are there ticks in the database?
- What are the actual prices at 16:20?
- Do timestamps look correct?

---

### 2. Get All 5-Minute Candles
```bash
curl "http://localhost:8080/api/debug/candles?symbol=RR&date=2025-10-01&interval=5" | jq
```

**What to check:**
- Do the close prices match your chart?
- Are the timestamps correct?
- Look for the 16:20 candle specifically

---

### 3. Get Specific Time Window
```bash
curl "http://localhost:8080/api/debug/candles?symbol=RR&date=2025-10-01&interval=5&startTime=16:00&endTime=18:00" | jq
```

**What to check:**
- Candle at 16:20 should have close ~$4.25 (not $4.66!)
- Candle at 16:45 should have close ~$4.43
- Candle at 17:15 should have close ~$4.54

---

### 4. Check How a Specific Candle is Built
```bash
curl "http://localhost:8080/api/debug/candle-building?symbol=RR&date=2025-10-01&time=16:20&interval=5" | jq
```

**What this shows:**
- All ticks that went into the 16:20 candle
- Calculated OHLC values
- Whether the candle calculation is correct

---

### 5. Compare Service Data with Chart
```bash
curl -X POST http://localhost:8080/api/debug/compare \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "RR",
    "date": "2025-10-01",
    "interval": 5,
    "chartData": [
      {"time": "16:20", "price": 4.25},
      {"time": "16:45", "price": 4.43},
      {"time": "17:15", "price": 4.54},
      {"time": "17:30", "price": 4.65},
      {"time": "18:00", "price": 4.70}
    ]
  }' | jq
```

**What this shows:**
- Side-by-side comparison of chart vs service prices
- Differences highlighted
- Status: ✅ OK, ⚠️ WARNING, or ❌ MISMATCH

---

## 🎯 Step-by-Step Diagnosis

### Step 1: Check if data exists
```bash
curl "http://localhost:8080/api/debug/ticks?symbol=RR&date=2025-10-01&startTime=16:00&endTime=17:00" | jq '.tickCount'
```

If returns 0: No data in database ❌
If returns >0: Data exists, continue ✅

---

### Step 2: Check one candle in detail
```bash
curl "http://localhost:8080/api/debug/candle-building?symbol=RR&date=2025-10-01&time=16:20&interval=5" | jq
```

Look at:
```json
{
  "calculatedCandle": {
    "open": ?,
    "high": ?,
    "low": ?,
    "close": ?  ← Should be ~4.25, is it 4.66?
  }
}
```

---

### Step 3: Compare multiple points
```bash
curl "http://localhost:8080/api/debug/candles?symbol=RR&date=2025-10-01&interval=5&startTime=16:00&endTime=17:00" | jq '.candles[] | {timestampUTC, close}'
```

Expected output:
```json
{"timestampUTC": "2025-10-01 16:20:00", "close": 4.25}  ← NOT 4.66!
{"timestampUTC": "2025-10-01 16:25:00", "close": 4.28}
{"timestampUTC": "2025-10-01 16:30:00", "close": 4.35}
...
```

---

## 🔧 Common Issues to Check

### Issue 1: Timestamps Off by Hours
```bash
# Check first and last candle times
curl "http://localhost:8080/api/debug/candles?symbol=RR&date=2025-10-01&interval=5" | jq '{firstCandle, lastCandle}'
```

**Expected:**
```json
{
  "firstCandle": "2025-10-01T13:30:00Z",  ← Market open (9:30 AM ET)
  "lastCandle": "2025-10-01T20:00:00Z"    ← Market close (4:00 PM ET)
}
```

If times are way off: Timezone issue ❌

---

### Issue 2: Prices Don't Match Chart
```bash
# Use the compare endpoint
curl -X POST http://localhost:8080/api/debug/compare \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "RR",
    "date": "2025-10-01",
    "interval": 5,
    "chartData": [
      {"time": "16:20", "price": 4.25}
    ]
  }' | jq '.comparisons[0]'
```

**Expected:**
```json
{
  "chartTime": "16:20",
  "chartPrice": 4.25,
  "serviceClose": 4.25,  ← Should match!
  "difference": 0.0,
  "status": "✅ OK"
}
```

**If you see:**
```json
{
  "chartTime": "16:20",
  "chartPrice": 4.25,
  "serviceClose": 4.66,  ← WRONG!
  "difference": 0.41,
  "status": "❌ MISMATCH"
}
```

Then there's a data issue! ❌

---

### Issue 3: No Ticks in Database
```bash
curl "http://localhost:8080/api/debug/ticks?symbol=RR&date=2025-10-01&startTime=16:00&endTime=17:00" | jq '.tickCount'
```

If returns 0:
1. Check if data was imported
2. Check table name: `stock_data`
3. Check ticker symbol: `RR` (case sensitive?)
4. Check date format in database

---

## 📊 Interpreting Results

### Good Result ✅
```json
{
  "timestamp": "2025-10-01T16:20:00Z",
  "close": 4.25,  ← Matches chart
  "open": 4.23,
  "high": 4.27,
  "low": 4.22,
  "volume": 50000
}
```

### Bad Result ❌
```json
{
  "timestamp": "2025-10-01T16:20:00Z",
  "close": 4.66,  ← WRONG! Should be 4.25
  "open": 4.60,
  "high": 4.70,
  "low": 4.58
}
```

**This indicates:**
- Candle is showing prices from ~70 minutes later
- Time bucketing is wrong
- Or data is misaligned

---

## 🚨 Critical Tests

Run these three commands and share the output:

### Test A: Raw ticks at 16:20
```bash
curl "http://localhost:8080/api/debug/ticks?symbol=RR&date=2025-10-01&startTime=16:15&endTime=16:25" | jq
```

### Test B: Built candle at 16:20
```bash
curl "http://localhost:8080/api/debug/candle-building?symbol=RR&date=2025-10-01&time=16:20&interval=5" | jq
```

### Test C: Compare with chart
```bash
curl -X POST http://localhost:8080/api/debug/compare \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "RR",
    "date": "2025-10-01",
    "interval": 5,
    "chartData": [
      {"time": "16:20", "price": 4.25},
      {"time": "17:30", "price": 4.66}
    ]
  }' | jq
```

---

## 📝 What to Share

When you run the tests, share:

1. **Output of Test A** - Shows what's in the database
2. **Output of Test B** - Shows how candles are built
3. **Output of Test C** - Shows the mismatch

This will tell us:
- Is data in the database correct?
- Is candle building logic correct?
- Where exactly the bug is

---

## 🔧 Quick Fix Script

If you want to run all tests at once:

```bash
chmod +x debug-test.sh
./debug-test.sh
```

This will run all diagnostic tests and show you where the data diverges from the chart.
