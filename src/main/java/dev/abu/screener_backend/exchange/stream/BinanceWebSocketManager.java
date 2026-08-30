package dev.abu.screener_backend.exchange.stream;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.exchange.Instrument;
import dev.abu.screener_backend.exchange.InstrumentUniverseChangedEvent;
import dev.abu.screener_backend.exchange.Venue;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceWebSocketManager {

    private final ExchangesProperties exchanges;
    private final WebSocketProperties wsProps;
    private final RawDepthMessageHandler handler;

    private BinanceConnectionPool spotPool;
    private BinanceConnectionPool futuresPool;
    private volatile boolean initialized = false;

    /**
     * Runs synchronously on the discovery thread, after slots have been published — so every id
     * these subscriptions can produce already has a book slot behind it.
     */
    @EventListener
    public void onUniverseChanged(InstrumentUniverseChangedEvent event) {
        if (!initialized) {
            initialized = true;
            startPools(event.getAdded());
        } else {
            log.info("Instrument universe change received — dynamic re-subscription not yet implemented");
        }
    }

    private void startPools(List<Instrument> instruments) {
        List<Instrument> spot = byVenue(instruments, Venue.BINANCE_SPOT);
        List<Instrument> futures = byVenue(instruments, Venue.BINANCE_FUTURES);

        spotPool = new BinanceConnectionPool(
                Venue.BINANCE_SPOT, exchanges.venue(Venue.BINANCE_SPOT), wsProps, handler);
        futuresPool = new BinanceConnectionPool(
                Venue.BINANCE_FUTURES, exchanges.venue(Venue.BINANCE_FUTURES), wsProps, handler);

        spotPool.start(spot);
        futuresPool.start(futures);

        log.info("WebSocket pools started — spot: {} instruments, futures: {} instruments",
                spot.size(), futures.size());
    }

    private static List<Instrument> byVenue(List<Instrument> instruments, Venue venue) {
        return instruments.stream().filter(i -> i.venue() == venue).toList();
    }

    @PreDestroy
    public void shutdown() {
        if (spotPool != null) spotPool.shutdown();
        if (futuresPool != null) futuresPool.shutdown();
    }
}
