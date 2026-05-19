package dev.abu.screener_backend.binance.websocket;

import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.ticker.Ticker;
import dev.abu.screener_backend.ticker.TickersRefreshedEvent;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceWebSocketManager {

    private final WebSocketProperties props;
    private final RawDepthMessageHandler handler;

    private BinanceConnectionPool spotPool;
    private BinanceConnectionPool futuresPool;
    private volatile boolean initialized = false;

    @EventListener
    public void onTickersRefreshed(TickersRefreshedEvent event) {
        if (!initialized) {
            initialized = true;
            startPools(event.getTickers().values());
        } else {
            log.info("Ticker refresh received — dynamic re-subscription not yet implemented");
        }
    }

    private void startPools(Collection<Ticker> tickers) {
        List<Ticker> spotTickers = tickers.stream().filter(Ticker::hasSpot).toList();
        List<Ticker> futuresTickers = new ArrayList<>(tickers);

        spotPool = new BinanceConnectionPool(Market.SPOT, props, handler);
        futuresPool = new BinanceConnectionPool(Market.FUTURES, props, handler);

        spotPool.start(spotTickers);
        futuresPool.start(futuresTickers);

        log.info("WebSocket pools started — spot: {} tickers, futures: {} tickers",
                spotTickers.size(), futuresTickers.size());
    }

    @PreDestroy
    public void shutdown() {
        if (spotPool != null) spotPool.shutdown();
        if (futuresPool != null) futuresPool.shutdown();
    }
}
