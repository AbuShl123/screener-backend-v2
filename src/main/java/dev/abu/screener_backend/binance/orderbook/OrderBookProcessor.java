package dev.abu.screener_backend.binance.orderbook;

import dev.abu.screener_backend.binance.disruptor.DepthEvent;
import dev.abu.screener_backend.binance.disruptor.EventType;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Routes Disruptor events to the appropriate {@link OrderBook} and reacts to results.
 *
 * This bean is injected into each {@link dev.abu.screener_backend.binance.disruptor.DepthEventHandler}
 * and called once per ring buffer event.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderBookProcessor {

    private final OrderBookStore store;
    private final SnapshotFetchQueue snapshotFetchQueue;

    public @Nullable OrderBook process(DepthEvent event) {
        // SNAPSHOT events: orderbook must already exist (created when the first diff arrived).
        // DIFF events: create lazily on first arrival for this symbol + market.
        OrderBook ob = (event.type == EventType.SNAPSHOT)
                ? store.get(event.symbol, event.market)
                : store.getOrCreate(event.symbol, event.market);

        if (ob == null) {
            return null;
        }

        OrderBookResult result = (event.type == EventType.SNAPSHOT)
                ? ob.applySnapshot(event.rawJson)
                : ob.onDiff(event.rawJson);

        if (result == OrderBookResult.NEEDS_SNAPSHOT || result == OrderBookResult.NEEDS_RESYNC) {
            if (!snapshotFetchQueue.enqueue(ob)) {
                return ob;
            }

            ob.markSnapshotRequested();

            if (result == OrderBookResult.NEEDS_SNAPSHOT) {
                ob.onDiff(event.rawJson);
            }
        }

        return ob;
    }
}
