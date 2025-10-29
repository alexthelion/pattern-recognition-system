package hashita.service.ibkr;

import com.ib.client.*;
import hashita.config.IBKRProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * IBKR API Client for fetching historical bar data
 *
 * This wraps the Interactive Brokers API and provides a simple interface
 * for requesting historical candle data.
 */
@Component
@Slf4j
public class IBKRClient implements EWrapper {

    private final IBKRProperties properties;
    private EClientSocket clientSocket;
    private EReaderSignal signal;
    private volatile boolean connected = false;

    // Request tracking
    private int nextRequestId = 1000;
    private final Map<Integer, CompletableFuture<List<Bar>>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<Integer, List<Bar>> requestData = new ConcurrentHashMap<>();

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

        log.info("Disconnecting from IBKR");
        clientSocket.eDisconnect();
        connected = false;
    }

    /**
     * Check if connected
     */
    public boolean isConnected() {
        return connected && clientSocket.isConnected();
    }

    /**
     * Request historical bars for a symbol
     *
     * @param symbol Stock symbol (e.g., "MGN", "AAPL")
     * @param endDateTime End date/time in "yyyyMMdd HH:mm:ss" format or "yyyyMMdd" for EOD
     * @param durationStr Duration string (e.g., "1 D", "1 W", "1 M")
     * @param barSizeSetting Bar size (e.g., "1 min", "5 mins", "1 hour")
     * @return List of bars
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
                reqId,                          // request ID
                contract,                       // contract
                endDateTime,                    // end date/time
                durationStr,                    // duration
                barSizeSetting,                 // bar size
                Types.WhatToShow.TRADES.name(), // what to show
                0,                              // use RTH (regular trading hours)
                2,                              // format date (2 = UTC seconds)
                false,                          // keep up to date
                new ArrayList<>()               // chart options
        );

        // Wait for response (timeout after 30 seconds)
        try {
            List<Bar> bars = future.get(30, TimeUnit.SECONDS);
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

    // ========================================
    // EWrapper callback implementations
    // ========================================

    @Override
    public void historicalData(int reqId, Bar bar) {
        // Add bar to request data
        List<Bar> bars = requestData.get(reqId);
        if (bars != null) {
            bars.add(bar);
            log.debug("Received bar for reqId {}: time={}, O={}, H={}, L={}, C={}, V={}",
                    reqId, bar.time(), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
        }
    }

    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
        log.debug("Historical data complete for reqId {}: {} to {}", reqId, startDateStr, endDateStr);

        // Complete the future with the collected bars
        CompletableFuture<List<Bar>> future = pendingRequests.get(reqId);
        List<Bar> bars = requestData.get(reqId);

        if (future != null && bars != null) {
            future.complete(new ArrayList<>(bars));
        }
    }

    @Override
    public void error(int id, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        if (errorCode == 2104 || errorCode == 2106 || errorCode == 2158 || errorCode == 2174) {
            // Informational messages/warnings, not errors
            // 2174 = timezone warning (we're now using UTC explicitly)
            log.debug("IBKR message {}: {}", errorCode, errorMsg);
            return;
        }

        log.error("IBKR Error - reqId: {}, code: {}, msg: {}", id, errorCode, errorMsg);

        // Complete future with error if it's a pending request
        CompletableFuture<List<Bar>> future = pendingRequests.get(id);
        if (future != null) {
            future.completeExceptionally(new RuntimeException(
                    "IBKR Error " + errorCode + ": " + errorMsg));
        }
    }

    @Override
    public void connectionClosed() {
        log.warn("IBKR connection closed");
        connected = false;
    }

    @Override
    public void connectAck() {
        log.info("IBKR connection acknowledged");
        connected = true;
    }

    // ========================================
    // Required EWrapper methods (mostly unused)
    // ========================================

    @Override public void tickPrice(int tickerId, int field, double price, TickAttrib attrib) {}
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
    @Override public void tickSnapshotEnd(int reqId) {}
    @Override public void marketDataType(int reqId, int marketDataType) {}
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
    @Override public void error(Exception e) { log.error("IBKR error", e); }
    @Override public void error(String str) { log.error("IBKR error: {}", str); }
    @Override public void positionMulti(int reqId, String account, String modelCode, Contract contract, Decimal pos, double avgCost) {}
    @Override public void positionMultiEnd(int reqId) {}
    @Override public void accountUpdateMulti(int reqId, String account, String modelCode, String key, String value, String currency) {}
    @Override public void accountUpdateMultiEnd(int reqId) {}
    @Override public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, java.util.Set<String> expirations, java.util.Set<Double> strikes) {}
    @Override public void securityDefinitionOptionalParameterEnd(int reqId) {}
    @Override public void softDollarTiers(int reqId, SoftDollarTier[] tiers) {}
    @Override public void familyCodes(FamilyCode[] familyCodes) {}
    @Override public void symbolSamples(int reqId, ContractDescription[] contractDescriptions) {}
    @Override public void historicalDataUpdate(int reqId, Bar bar) {}
    @Override public void mktDepthExchanges(DepthMktDataDescription[] depthMktDataDescriptions) {}
    @Override public void tickNews(int tickerId, long timeStamp, String providerCode, String articleId, String headline, String extraData) {}
    @Override public void smartComponents(int reqId, Map<Integer, Map.Entry<String, Character>> theMap) {}
    @Override public void tickReqParams(int tickerId, double minTick, String bboExchange, int snapshotPermissions) {}
    @Override public void newsProviders(NewsProvider[] newsProviders) {}
    @Override public void newsArticle(int requestId, int articleType, String articleText) {}
    @Override public void historicalNews(int requestId, String time, String providerCode, String articleId, String headline) {}
    @Override public void historicalNewsEnd(int requestId, boolean hasMore) {}
    @Override public void headTimestamp(int reqId, String headTimestamp) {}
    @Override public void histogramData(int reqId, java.util.List<HistogramEntry> items) {}
    @Override public void rerouteMktDataReq(int reqId, int conId, String exchange) {}
    @Override public void rerouteMktDepthReq(int reqId, int conId, String exchange) {}
    @Override public void marketRule(int marketRuleId, PriceIncrement[] priceIncrements) {}
    @Override public void pnl(int reqId, double dailyPnL, double unrealizedPnL, double realizedPnL) {}
    @Override public void pnlSingle(int reqId, Decimal pos, double dailyPnL, double unrealizedPnL, double realizedPnL, double value) {}
    @Override public void historicalTicks(int reqId, java.util.List<HistoricalTick> ticks, boolean done) {}
    @Override public void historicalTicksBidAsk(int reqId, java.util.List<HistoricalTickBidAsk> ticks, boolean done) {}
    @Override public void historicalTicksLast(int reqId, java.util.List<HistoricalTickLast> ticks, boolean done) {}
    @Override public void tickByTickAllLast(int reqId, int tickType, long time, double price, Decimal size, TickAttribLast tickAttribLast, String exchange, String specialConditions) {}
    @Override public void tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice, Decimal bidSize, Decimal askSize, TickAttribBidAsk tickAttribBidAsk) {}
    @Override public void tickByTickMidPoint(int reqId, long time, double midPoint) {}
    @Override public void orderBound(long orderId, int apiClientId, int apiOrderId) {}
    @Override public void completedOrder(Contract contract, Order order, OrderState orderState) {}
    @Override public void completedOrdersEnd() {}
    @Override public void replaceFAEnd(int reqId, String text) {}
    @Override public void wshMetaData(int reqId, String dataJson) {}
    @Override public void wshEventData(int reqId, String dataJson) {}
    @Override public void historicalSchedule(int reqId, String startDateTime, String endDateTime, String timeZone, java.util.List<HistoricalSession> sessions) {}
    @Override public void userInfo(int reqId, String whiteBrandingId) {}
}