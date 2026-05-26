package dev.abu.screener_backend.binance.websocket;

import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.ticker.Ticker;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
public class BinanceConnectionPool {

    private final Market market;
    private final WebSocketProperties props;
    private final RawDepthMessageHandler handler;

    private final List<BinanceStreamClient> clients = new ArrayList<>();
    private final ScheduledExecutorService reconnectScheduler;

    public BinanceConnectionPool(Market market, WebSocketProperties props, RawDepthMessageHandler handler) {
        this.market = market;
        this.props = props;
        this.handler = handler;
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "reconnect-" + market.name().toLowerCase())
        );
    }

    public void start(Collection<Ticker> tickers) {
        List<String> symbols = tickers.stream().map(Ticker::symbol).toList();
        int connectionCount = market == Market.SPOT ? props.connectionCountSpot() : props.connectionCountFutures();

        log.info("[{}] Starting {} connection(s) for {} tickers", market, connectionCount, symbols.size());

        String baseUrl = market == Market.SPOT ? props.spotStreamUrl() : props.futuresStreamUrl();

        for (int i = 0; i < connectionCount; i++) {
            int from = i * symbols.size() / connectionCount;
            int to = (i + 1) * symbols.size() / connectionCount;
            List<String> batch = symbols.subList(from, to);

            try {
                URI uri = new URI(baseUrl);
                BinanceStreamClient client = new BinanceStreamClient(uri, market, batch, handler, reconnectScheduler, props);
                client.connect();
                clients.add(client);
            } catch (URISyntaxException e) {
                log.error("[{}] Invalid WebSocket URL: {}", market, baseUrl, e);
            }
        }
    }

    public void shutdown() {
        clients.forEach(BinanceStreamClient::shutdown);
        reconnectScheduler.shutdownNow();
        log.info("[{}] Connection pool shut down", market);
    }
}
