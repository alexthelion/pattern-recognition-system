package hashita.service.ibkr;

import com.ib.client.*;
import hashita.config.IBKRProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * IBKR API Client with Real-Time Market Data Support
 */
@Component
@Slf4j
public class IBKRClient implements EWrapper {

    private final IBKRProperties properties;
    private EClientSocket clientSocket;
    private EReaderSignal signal;
    private volatile boolean connected = false;

    // Request tracking for historical data
    private int nextRequestId = 1000;
    private final Map<Integer, CompletableFuture<List<Bar>>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<Integer, List<Bar>> requestData = new ConcurrentHashMap<>();

    // Request tracking for market data
    private final Map<Integer, CompletableFuture<Double>> pendingPriceRequests = new ConcurrentHashMap<>();
    private final Map<String, MarketDataCache> priceCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 60_000; // 1 minute

    public IBKRClient(IBKRProperties properties) {
        this.properties = properties;
        this.signal = new EJavaSignal();
        this.clientSocket = new EClientSocket(this, signal);
    }

    /**
     * Connect to IBKR TWS or Gateway
     */
    public synchronized void connect() {
        if (connected) {
            log.debug("Already connected to IBKR");
            return;
        }

        log.info("Connecting to IBKR at {}:{} (clientId={})",
                properties.getHost(), properties.getPort(), properties.getClientId());

        try {
            clientSocket.eConnect(properties.getHost(), properties.getPort(), properties.getClientId());

            if (!clientSocket.isConnected()) {
                throw new RuntimeException("Failed to connect to IBKR");
            }

            // Start reader thread
            final EReader reader = new EReader(clientSocket, signal);
            reader.start();

            // Start message processing thread
            new Thread(() -> {
                while (clientSocket.isConnected()) {
                    signal.waitForSignal();
                    try {
                        reader.processMsgs();
                    } catch (Exception e) {
                        log.error("Error processing messages", e);
                    }
                }
            }).start();

            // Wait for connection to be established
            Thread.sleep(1000);

            connected = true;
            log.info("✅ Connected to IBKR");

            // Set market data type
            clientSocket.reqMarketDataType(properties.getMarketDataType());

        } catch (Exception e) {
            log.error("Failed to connect to IBKR", e);
            throw new RuntimeException("IBKR connection failed: " + e.getMessage(), e);
        }
    }

    /**
     * Disconnect from IBKR
     */
    public synchronized void disconnect() {
        if (!connected) {
            return;
        }

        log.info("Disconnecting from IBKR...");
        clientSocket.eDisconnect();
        connected = false;
        log.info("Disconnected from IBKR");
    }

    /**
     * Check if connected
     */
    public boolean isConnected() {
        return connected && clientSocket.isConnected();
    }

    /**
     * Request historical bars for a symbol
     */
    public List<Bar> getHistoricalBars(
            String symbol,
            String endDateTime,
            String durationStr,
            String barSizeSetting) throws Exception {

        if (!isConnected()) {
            connect();
        }

        // Create contract
        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType(Types.SecType.STK);
        contract.exchange(properties.getDefaultStockExchange());
        contract.currency(properties.getDefaultStockCurrency());

        // Generate request ID
        int reqId = nextRequestId++;

        // Create future for this request
        CompletableFuture<List<Bar>> future = new CompletableFuture<>();
        pendingRequests.put(reqId, future);
        requestData.put(reqId, new ArrayList<>());

        log.debug("Requesting historical data: symbol={}, endDate={}, duration={}, barSize={}, reqId={}",
                symbol, endDateTime, durationStr, barSizeSetting, reqId);

        // Request historical data
        clientSocket.reqHistoricalData(
                reqId,
                contract,
                endDateTime,
                durationStr,
                barSizeSetting,
                Types.WhatToShow.TRADES.name(),
                0,
                2,
                false,
                new ArrayList<>()
        );

        // Wait for response (timeout after 60 seconds)
        try {
            List<Bar> bars = future.get(60, TimeUnit.SECONDS);
            log.info("✅ Received {} bars for {}", bars.size(), symbol);
            return bars;

        } catch (Exception e) {
            log.error("Error getting historical bars for {}: {}", symbol, e.getMessage());
            throw e;

        } finally {
            // Cleanup
            pendingRequests.remove(reqId);
            requestData.remove(reqId);
        }
    }

    /**
     * Get real-time price for a symbol
     */
    public Double getRealTimePrice(String symbol) {
        try {
            // Check cache first
            MarketDataCache cached = priceCache.get(symbol);
            if (cached != null && !cached.isExpired()) {
                log.debug("✅ Using cached price for {}: ${}", symbol, cached.price);
                return cached.price;
            }

            if (!isConnected()) {
                connect();
            }

            log.info("📡 Requesting real-time price for {}...", symbol);

            // Create contract
            Contract contract = new Contract();
            contract.symbol(symbol);
            contract.secType(Types.SecType.STK);
            contract.exchange(properties.getDefaultStockExchange());
            contract.currency(properties.getDefaultStockCurrency());

            // Generate request ID
            int reqId = nextRequestId++;

            // Create future for this request
            CompletableFuture<Double> future = new CompletableFuture<>();
            pendingPriceRequests.put(reqId, future);

            log.debug("Requesting market data: symbol={}, reqId={}", symbol, reqId);

            // Request market data (snapshot mode)
            clientSocket.reqMktData(
                    reqId,
                    contract,
                    "",      // generic tick list
                    true,    // snapshot = true (one-time request)
                    false,   // regulatory snapshot
                    new ArrayList<>()
            );

            // Wait for response (timeout after 5 seconds)
            try {
                Double price = future.get(5, TimeUnit.SECONDS);

                if (price != null && price > 0) {
                    // Cache the price
                    priceCache.put(symbol, new MarketDataCache(price, System.currentTimeMillis()));
                    log.info("✅ Got real-time price for {}: ${}", symbol, price);
                    return price;
                } else {
                    log.warn("⚠️ Invalid price received for {}: {}", symbol, price);
                    return null;
                }

            } catch (TimeoutException e) {
                log.warn("⏱️ Timeout getting price for {}: took > 5 seconds", symbol);
                return null;
            } catch (Exception e) {
                log.error("Error waiting for price: {}", e.getMessage());
                return null;
            } finally {
                // Cleanup
                pendingPriceRequests.remove(reqId);
                clientSocket.cancelMktData(reqId);
            }

        } catch (Exception e) {
            log.error("❌ Error getting real-time price for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Clear price cache
     */
    public void clearPriceCache() {
        priceCache.clear();
        log.info("Cleared price cache");
    }

    /**
     * Clear cache for specific symbol
     */
    public void clearPriceCache(String symbol) {
        priceCache.remove(symbol);
        log.info("Cleared price cache for {}", symbol);
    }

    /**
     * Get client socket for advanced usage
     */
    public EClientSocket getEClientSocket() {
        return clientSocket;
    }

    // ==================== EWrapper Implementation ====================

    @Override
    public void historicalData(int reqId, Bar bar) {
        List<Bar> bars = requestData.get(reqId);
        if (bars != null) {
            bars.add(bar);
        }
    }

    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
        CompletableFuture<List<Bar>> future = pendingRequests.get(reqId);
        if (future != null) {
            List<Bar> bars = requestData.get(reqId);
            future.complete(bars != null ? bars : new ArrayList<>());
        }
    }

    @Override
    public void tickPrice(int tickerId, int field, double price, TickAttrib attrib) {
        CompletableFuture<Double> future = pendingPriceRequests.get(tickerId);

        if (future != null && !future.isDone()) {
            // Field 4 = LAST, Field 1 = BID, Field 2 = ASK
            if (field == 4 || field == 1 || field == 2) {
                log.debug("Received tick price: reqId={}, field={}, price={}", tickerId, field, price);

                if (price > 0) {
                    future.complete(price);
                }
            }
        }
    }

    @Override
    public void tickSnapshotEnd(int reqId) {
        log.debug("Snapshot complete for reqId={}", reqId);

        CompletableFuture<Double> future = pendingPriceRequests.get(reqId);
        if (future != null && !future.isDone()) {
            future.complete(null);
        }
    }

    @Override
    public void marketDataType(int i, int i1) {

    }

    @Override
    public void error(int id, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        log.error("🔴 IBKR Error [ReqId: {}, Code: {}, Msg: {}]", id, errorCode, errorMsg);

        // Warnings (don't fail)
        if (errorCode >= 2100 && errorCode < 2200) {
            return;
        }

        // "No data" errors (return empty, don't timeout)
        if (errorCode == 162 || errorCode == 200) {
            log.warn("  ⚠️ No data available for reqId {}", id);
            CompletableFuture<List<Bar>> future = pendingRequests.get(id);
            if (future != null && !future.isDone()) {
                future.complete(new ArrayList<>());  // Empty, not timeout!
            }
            return;
        }

        // Real errors
        CompletableFuture<List<Bar>> future = pendingRequests.get(id);
        if (future != null && !future.isDone()) {
            future.completeExceptionally(
                    new RuntimeException("IBKR Error " + errorCode + ": " + errorMsg));
        }
    }

    @Override
    public void connectionClosed() {
        log.warn("⚠️ IBKR connection closed");
        connected = false;
    }

    // Required EWrapper stub methods
    @Override public void tickSize(int tickerId, int field, Decimal size) {}
    @Override public void tickOptionComputation(int tickerId, int field, int tickAttrib, double impliedVol, double delta, double optPrice, double pvDividend, double gamma, double vega, double theta, double undPrice) {}
    @Override public void tickGeneric(int tickerId, int tickType, double value) {}
    @Override public void tickString(int tickerId, int tickType, String value) {}
    @Override public void tickEFP(int tickerId, int tickType, double basisPoints, String formattedBasisPoints, double impliedFuture, int holdDays, String futureLastTradeDate, double dividendImpact, double dividendsToLastTradeDate) {}
    @Override public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice, int permId, int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {}
    @Override public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {}
    @Override public void openOrderEnd() {}
    @Override public void updateAccountValue(String key, String value, String currency, String accountName) {}
    @Override public void updatePortfolio(Contract contract, Decimal position, double marketPrice, double marketValue, double averageCost, double unrealizedPNL, double realizedPNL, String accountName) {}
    @Override public void updateAccountTime(String timeStamp) {}
    @Override public void accountDownloadEnd(String accountName) {}
    @Override public void nextValidId(int orderId) {}
    @Override public void contractDetails(int reqId, ContractDetails contractDetails) {}
    @Override public void bondContractDetails(int reqId, ContractDetails contractDetails) {}
    @Override public void contractDetailsEnd(int reqId) {}
    @Override public void execDetails(int reqId, Contract contract, Execution execution) {}
    @Override public void execDetailsEnd(int reqId) {}
    @Override public void updateMktDepth(int tickerId, int position, int operation, int side, double price, Decimal size) {}
    @Override public void updateMktDepthL2(int tickerId, int position, String marketMaker, int operation, int side, double price, Decimal size, boolean isSmartDepth) {}
    @Override public void updateNewsBulletin(int msgId, int msgType, String message, String origExchange) {}
    @Override public void managedAccounts(String accountsList) {}
    @Override public void receiveFA(int faDataType, String xml) {}
    @Override public void scannerParameters(String xml) {}
    @Override public void scannerData(int reqId, int rank, ContractDetails contractDetails, String distance, String benchmark, String projection, String legsStr) {}
    @Override public void scannerDataEnd(int reqId) {}
    @Override public void realtimeBar(int reqId, long time, double open, double high, double low, double close, Decimal volume, Decimal wap, int count) {}
    @Override public void currentTime(long time) {}
    @Override public void fundamentalData(int reqId, String data) {}
    @Override public void deltaNeutralValidation(int reqId, DeltaNeutralContract deltaNeutralContract) {}
    @Override public void commissionReport(CommissionReport commissionReport) {}
    @Override public void position(String account, Contract contract, Decimal pos, double avgCost) {}
    @Override public void positionEnd() {}
    @Override public void accountSummary(int reqId, String account, String tag, String value, String currency) {}
    @Override public void accountSummaryEnd(int reqId) {}
    @Override public void verifyMessageAPI(String apiData) {}
    @Override public void verifyCompleted(boolean isSuccessful, String errorText) {}
    @Override public void verifyAndAuthMessageAPI(String apiData, String xyzChallenge) {}
    @Override public void verifyAndAuthCompleted(boolean isSuccessful, String errorText) {}
    @Override public void displayGroupList(int reqId, String groups) {}
    @Override public void displayGroupUpdated(int reqId, String contractInfo) {}
    @Override public void error(Exception e) { log.error("IBKR Error", e); }
    @Override public void error(String str) { log.error("IBKR Error: {}", str); }
    @Override public void connectAck() { log.info("IBKR Connection acknowledged"); }
    @Override public void positionMulti(int reqId, String account, String modelCode, Contract contract, Decimal pos, double avgCost) {}
    @Override public void positionMultiEnd(int reqId) {}
    @Override public void accountUpdateMulti(int reqId, String account, String modelCode, String key, String value, String currency) {}
    @Override public void accountUpdateMultiEnd(int reqId) {}

    @Override
    public void securityDefinitionOptionalParameter(int i, String s, int i1, String s1, String s2, Set<String> set, Set<Double> set1) {

    }

    @Override public void securityDefinitionOptionalParameterEnd(int reqId) {}
    @Override public void softDollarTiers(int reqId, SoftDollarTier[] tiers) {}
    @Override public void familyCodes(FamilyCode[] familyCodes) {}
    @Override public void symbolSamples(int reqId, ContractDescription[] contractDescriptions) {}
    @Override public void historicalDataUpdate(int reqId, Bar bar) {}

    @Override
    public void rerouteMktDataReq(int i, int i1, String s) {

    }

    @Override
    public void rerouteMktDepthReq(int i, int i1, String s) {

    }

    @Override
    public void marketRule(int i, PriceIncrement[] priceIncrements) {

    }

    @Override
    public void pnl(int i, double v, double v1, double v2) {

    }

    @Override
    public void pnlSingle(int i, Decimal decimal, double v, double v1, double v2, double v3) {

    }

    @Override public void mktDepthExchanges(DepthMktDataDescription[] depthMktDataDescriptions) {}
    @Override public void tickNews(int tickerId, long timeStamp, String providerCode, String articleId, String headline, String extraData) {}
    @Override public void smartComponents(int reqId, Map<Integer, Map.Entry<String, Character>> theMap) {}
    @Override public void tickReqParams(int tickerId, double minTick, String bboExchange, int snapshotPermissions) {}
    @Override public void newsProviders(NewsProvider[] newsProviders) {}
    @Override public void newsArticle(int requestId, int articleType, String articleText) {}
    @Override public void historicalNews(int requestId, String time, String providerCode, String articleId, String headline) {}
    @Override public void historicalNewsEnd(int requestId, boolean hasMore) {}
    @Override public void headTimestamp(int reqId, String headTimestamp) {}
    @Override public void histogramData(int reqId, List<HistogramEntry> items) {}
    @Override public void historicalTicks(int reqId, List<HistoricalTick> ticks, boolean done) {}
    @Override public void historicalTicksBidAsk(int reqId, List<HistoricalTickBidAsk> ticks, boolean done) {}
    @Override public void historicalTicksLast(int reqId, List<HistoricalTickLast> ticks, boolean done) {}
    @Override public void tickByTickAllLast(int reqId, int tickType, long time, double price, Decimal size, TickAttribLast tickAttribLast, String exchange, String specialConditions) {}
    @Override public void tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice, Decimal bidSize, Decimal askSize, TickAttribBidAsk tickAttribBidAsk) {}
    @Override public void tickByTickMidPoint(int reqId, long time, double midPoint) {}
    @Override public void orderBound(long orderId, int apiClientId, int apiOrderId) {}
    @Override public void completedOrder(Contract contract, Order order, OrderState orderState) {}
    @Override public void completedOrdersEnd() {}
    @Override public void replaceFAEnd(int reqId, String text) {}
    @Override public void wshMetaData(int reqId, String dataJson) {}
    @Override public void wshEventData(int reqId, String dataJson) {}
    @Override public void historicalSchedule(int reqId, String startDateTime, String endDateTime, String timeZone, List<HistoricalSession> sessions) {}
    @Override public void userInfo(int reqId, String whiteBrandingId) {}

    /**
     * Market data cache entry
     */
    private static class MarketDataCache {
        final double price;
        final long timestamp;

        MarketDataCache(double price, long timestamp) {
            this.price = price;
            this.timestamp = timestamp;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}