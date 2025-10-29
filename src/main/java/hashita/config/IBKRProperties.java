package hashita.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "ibkr")
@Data
public class IBKRProperties {

    private String host = "127.0.0.1";
    private int port = 4001;
    private int clientId = 1001;
    private int marketDataType = 1; // 1=real-time, 3=delayed
    private String defaultStockExchange = "SMART";
    private String defaultStockCurrency = "USD";
    private String defaultCryptoExchange = "PAXOS";
    private String defaultCryptoCurrency = "USD";
}