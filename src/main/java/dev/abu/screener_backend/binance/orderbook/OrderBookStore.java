package dev.abu.screener_backend.binance.orderbook;

import dev.abu.screener_backend.binance.websocket.Market;
import dev.abu.screener_backend.config.OrderbookProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all active {@link OrderBook} instances, keyed by {@code "SYMBOL:MARKET"}.
 *
 * {@link #getOrCreate} is called by consumer threads. Because each symbol always hashes to
 * the same shard, {@code computeIfAbsent} is never called concurrently for the same key.
 * {@link ConcurrentHashMap} is used to allow safe reads from other threads (e.g. the
 * SnapshotFetchQueue scheduler).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBookStore {

    private final OrderbookProperties props;

    private final ConcurrentHashMap<String, OrderBook> books = new ConcurrentHashMap<>();

    public OrderBook getOrCreate(String symbol, Market market) {
        return books.computeIfAbsent(
                key(symbol, market),
                k -> new OrderBook(symbol, market, props.priceFilterThreshold()));
    }

    public OrderBook get(String symbol, Market market) {
        return books.get(key(symbol, market));
    }

    public int size() {
        return books.size();
    }

    @Scheduled(fixedDelayString = "${screener.orderbook.sync-log-rate-ms:30000}")
    public void logSyncCount() {
        int spot = 0, futures = 0;
        for (OrderBook ob : books.values()) {
            if (ob.getState() == OrderBookState.SYNCED) {
                if (ob.getMarket() == Market.SPOT) spot++;
                else futures++;
            }
        }
        log.info("sync count: spot={} fut={}", spot, futures);
    }

    private static String key(String symbol, Market market) {
        return symbol + ":" + market.name();
    }
}
