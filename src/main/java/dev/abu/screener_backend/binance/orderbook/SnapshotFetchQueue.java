package dev.abu.screener_backend.binance.orderbook;

import com.lmax.disruptor.RingBuffer;
import dev.abu.screener_backend.binance.api.BinanceRestClient;
import dev.abu.screener_backend.binance.disruptor.DepthEvent;
import dev.abu.screener_backend.binance.disruptor.DisruptorShardManager;
import dev.abu.screener_backend.binance.disruptor.EventType;
import dev.abu.screener_backend.binance.websocket.Market;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limited snapshot dispatcher.
 * <p>
 * Maintains two independent maps (spot, futures) keyed by symbol and drains them at a
 * configurable pace to stay within Binance API weight limits. Each dispatch cycle fires
 * all pending requests concurrently; an orderbook is removed from the map only when its
 * HTTP response (success or error) arrives. When a REST snapshot response arrives on the
 * Reactor/WebClient thread, it is published into the Disruptor ring buffer so the consumer
 * thread applies it in sequence with diffs — no direct orderbook writes from this thread.
 *
 * <p>The {@link DisruptorShardManager} dependency is {@link Lazy} to break the startup
 * circular dependency:
 * DisruptorShardManager → OrderBookProcessor → SnapshotFetchQueue → DisruptorShardManager.
 * The proxy is resolved on first use, which only happens after the context is fully started.
 */
@Slf4j
@Component
public class SnapshotFetchQueue {

    private final BinanceRestClient restClient;
    private final DisruptorShardManager shardManager;
    private final int spotMaxSize;
    private final int futuresMaxSize;

    private final ConcurrentHashMap<String, OrderBook> spotQueue    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OrderBook> futuresQueue = new ConcurrentHashMap<>();

    public SnapshotFetchQueue(BinanceRestClient restClient,
                              @Lazy DisruptorShardManager shardManager,
                              @Value("${screener.orderbook.spot-snapshot-queue-size}") int spotMaxSize,
                              @Value("${screener.orderbook.futures-snapshot-queue-size}") int futuresMaxSize) {
        this.restClient    = restClient;
        this.shardManager  = shardManager;
        this.spotMaxSize   = spotMaxSize;
        this.futuresMaxSize = futuresMaxSize;
    }

    /**
     * Enqueue an orderbook for snapshot fetching. Safe to call from the consumer thread.
     *
     * @return true if enqueued, false if the queue is at capacity
     */
    public boolean enqueue(OrderBook ob) {
        boolean isSpot = ob.market == Market.SPOT;
        ConcurrentHashMap<String, OrderBook> queue = isSpot ? spotQueue : futuresQueue;
        int maxSize = isSpot ? spotMaxSize : futuresMaxSize;
        if (queue.size() >= maxSize) return false;
        queue.put(ob.symbol, ob);
        return true;
    }

    @Scheduled(fixedRateString = "${screener.orderbook.spot-snapshot-dispatch-rate-ms}")
    public void dispatchSpot() {
        for (OrderBook ob : spotQueue.values()) {
            restClient.getSpot("/api/v3/depth?symbol=" + ob.symbol + "&limit=1000", String.class)
                    .delayElement(Duration.ofSeconds(5))
                    .subscribe(
                            rawJson -> {
                                spotQueue.remove(ob.symbol);
                                publishSnapshotEvent(ob, rawJson);
                            },
                            error -> {
                                spotQueue.remove(ob.symbol);
                                log.warn("Snapshot fetch failed for {}/SPOT: {}", ob.symbol, error.getMessage());
                                enqueue(ob);
                            }
                    );
        }
    }

    @Scheduled(fixedRateString = "${screener.orderbook.futures-snapshot-dispatch-rate-ms}")
    public void dispatchFutures() {
        for (OrderBook ob : futuresQueue.values()) {
            restClient.getFutures("/fapi/v1/depth?symbol=" + ob.symbol + "&limit=1000", String.class)
                    .delayElement(Duration.ofSeconds(5))
                    .subscribe(
                            rawJson -> {
                                futuresQueue.remove(ob.symbol);
                                publishSnapshotEvent(ob, rawJson);
                            },
                            error -> {
                                futuresQueue.remove(ob.symbol);
                                log.warn("Snapshot fetch failed for {}/FUTURES: {}", ob.symbol, error.getMessage());
                                enqueue(ob);
                            }
                    );
        }
    }

    /** Called from the Reactor/WebClient thread. Publishes into the ring buffer — never writes to OrderBook directly. */
    private void publishSnapshotEvent(OrderBook ob, String rawJson) {
        RingBuffer<DepthEvent> rb = shardManager.getRingBuffer(ob.symbol);
        long seq = rb.next();
        try {
            DepthEvent event = rb.get(seq);
            event.type    = EventType.SNAPSHOT;
            event.symbol  = ob.symbol;
            event.market  = ob.market;
            event.rawJson = rawJson;
        } finally {
            rb.publish(seq);
        }
    }
}
