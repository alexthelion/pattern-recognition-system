#!/bin/bash

# Debug Script for Pattern Recognition Service
# This script helps identify data issues by comparing service data with chart data

BASE_URL="http://localhost:8060"
SYMBOL="RR"
DATE="2025-10-01"

echo "=================================================="
echo "Pattern Recognition Service - Data Debug Script"
echo "=================================================="
echo ""
echo "Symbol: $SYMBOL"
echo "Date: $DATE"
echo ""

# Test 1: Get raw tick data around 16:20
echo "📊 TEST 1: Raw Ticks at 16:20"
echo "--------------------------------------------------"
curl -s "${BASE_URL}/api/debug/ticks?symbol=${SYMBOL}&date=${DATE}&startTime=16:15&endTime=16:30" | jq '.'
echo ""
echo ""

# Test 2: Get 5-minute candles
echo "📊 TEST 2: 5-Minute Candles (16:00-17:00)"
echo "--------------------------------------------------"
curl -s "${BASE_URL}/api/debug/candles?symbol=${SYMBOL}&date=${DATE}&interval=5&startTime=16:00&endTime=17:00" | jq '.'
echo ""
echo ""

# Test 3: Check specific candle at 16:20
echo "📊 TEST 3: Candle Building Details at 16:20"
echo "--------------------------------------------------"
curl -s "${BASE_URL}/api/debug/candle-building?symbol=${SYMBOL}&date=${DATE}&time=16:20&interval=5" | jq '.'
echo ""
echo ""

# Test 4: Compare with chart data
echo "📊 TEST 4: Compare Service Data with Chart"
echo "--------------------------------------------------"
curl -s -X POST "${BASE_URL}/api/debug/compare" \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "RR",
    "date": "2025-10-01",
    "interval": 5,
    "chartData": [
      {"time": "16:20", "price": 4.25},
      {"time": "16:45", "price": 4.43},
      {"time": "17:15", "price": 4.54},
      {"time": "17:30", "price": 4.65}
    ]
  }' | jq '.'
echo ""
echo ""

# Test 5: Get all entry signals (original endpoint)
echo "📊 TEST 5: Entry Signals (For Comparison)"
echo "--------------------------------------------------"
curl -s "${BASE_URL}/api/entry-signals?symbol=${SYMBOL}&date=${DATE}&interval=5" | jq '.signals[] | {timestamp, pattern, entryPrice, signalQuality}'
echo ""
echo ""

echo "=================================================="
echo "Debug Tests Complete"
echo "=================================================="
echo ""
echo "What to look for:"
echo "  1. Are tick timestamps correct?"
echo "  2. Do candle close prices match the chart?"
echo "  3. Is there a time offset in the data?"
echo "  4. Are prices reasonable for the time?"
echo ""
echo "If prices are wrong:"
echo "  - Check database timestamps"
echo "  - Check timezone handling"
echo "  - Check candle building logic"
echo ""
