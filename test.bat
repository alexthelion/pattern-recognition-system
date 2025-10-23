@echo off
REM Debug Script for Pattern Recognition Service - Windows Version

set BASE_URL=http://localhost:8060
set SYMBOL=RR
set DATE=2025-10-01

echo ==================================================
echo Pattern Recognition Service - Data Debug Script
echo ==================================================
echo.
echo Symbol: %SYMBOL%
echo Date: %DATE%
echo.

echo Test 1: Raw Ticks at 16:20
echo --------------------------------------------------
curl "%BASE_URL%/api/debug/ticks?symbol=%SYMBOL%&date=%DATE%"
echo.
echo.

echo Test 2: 5-Minute Candles
echo --------------------------------------------------
curl "%BASE_URL%/api/debug/candles?symbol=%SYMBOL%&date=%DATE%&interval=5"
echo.
echo.

echo Test 3: Compare with Chart Data
echo --------------------------------------------------
curl -X POST "%BASE_URL%/api/debug/compare" -H "Content-Type: application/json" -d "{\"symbol\":\"%SYMBOL%\",\"date\":\"%DATE%\",\"interval\":5,\"chartData\":[{\"time\":\"16:20\",\"price\":4.25},{\"time\":\"16:45\",\"price\":4.43},{\"time\":\"17:15\",\"price\":4.54},{\"time\":\"17:30\",\"price\":4.65}]}"
echo.
echo.

echo ==================================================
echo Debug Tests Complete
echo ==================================================
echo.
echo What to look for:
echo   1. Are tick prices reasonable?
echo   2. Do candle close prices match the chart?
echo   3. Do comparisons show OK status?
echo.
echo If prices are wrong:
echo   - Check database timestamps
echo   - Check timezone handling
echo   - Check candle building logic
echo.

pause