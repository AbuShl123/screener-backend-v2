package dev.abu.screener_backend.binance.orderbook;

import com.lmax.disruptor.RingBuffer;
import dev.abu.screener_backend.binance.api.BinanceRestClient;
import dev.abu.screener_backend.binance.disruptor.DepthEvent;
import dev.abu.screener_backend.binance.disruptor.DisruptorShardManager;
import dev.abu.screener_backend.binance.disruptor.EventType;
import dev.abu.screener_backend.exchange.Venue;
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
 * Maintains two independent maps (spot, futures) keyed by instrument id and drains them at a
 * configurable pace to stay within Binance API weight limits. Each dispatch cycle fires
 * all pending requests concurrently; a slot is removed from the map only when its
 * HTTP response (success or error) arrives. When a REST snapshot response arrives on the
 * Reactor/WebClient thread, it is published into the Disruptor ring buffer so the consumer
 * thread applies it in sequence with diffs — no direct orderbook writes from this thread.
 *
 * <p>Keying by instrument id rather than symbol also removes a collision that spot and futures
 * {@code BTCUSDT} would otherwise have had once both markets became independent instruments.
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

    private final ConcurrentHashMap<Integer, BookSlot> spotQueue    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, BookSlot> futuresQueue = new ConcurrentHashMap<>();

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
    public boolean enqueue(BookSlot slot) {
        boolean isSpot = slot.instrument().venue() == Venue.BINANCE_SPOT;
        ConcurrentHashMap<Integer, BookSlot> queue = isSpot ? spotQueue : futuresQueue;
        int maxSize = isSpot ? spotMaxSize : futuresMaxSize;
        if (queue.size() >= maxSize) return false;
        queue.put(slot.instrument().id(), slot);
        return true;
    }

    @Scheduled(fixedRateString = "${screener.orderbook.spot-snapshot-dispatch-rate-ms}")
    public void dispatchSpot() {
        for (BookSlot slot : spotQueue.values()) {
            int id = slot.instrument().id();
            restClient.getSpot("/api/v3/depth?symbol=" + slot.instrument().nativeSymbol() + "&limit=1000", String.class)
                    .delayElement(Duration.ofSeconds(5))
                    .subscribe(
                            rawJson -> {
                                spotQueue.remove(id);
                                publishSnapshotEvent(slot, rawJson);
                            },
                            error -> {
                                spotQueue.remove(id);
                                log.warn("Snapshot fetch failed for {}: {}", slot.instrument().logName(), error.getMessage());
                                enqueue(slot);
                            }
                    );
        }
    }

    @Scheduled(fixedRateString = "${screener.orderbook.futures-snapshot-dispatch-rate-ms}")
    public void dispatchFutures() {
        for (BookSlot slot : futuresQueue.values()) {
            int id = slot.instrument().id();
            restClient.getFutures("/fapi/v1/depth?symbol=" + slot.instrument().nativeSymbol() + "&limit=1000", String.class)
                    .delayElement(Duration.ofSeconds(5))
                    .subscribe(
                            rawJson -> {
                                futuresQueue.remove(id);
                                publishSnapshotEvent(slot, rawJson);
                            },
                            error -> {
                                futuresQueue.remove(id);
                                log.warn("Snapshot fetch failed for {}: {}", slot.instrument().logName(), error.getMessage());
                                enqueue(slot);
                            }
                    );
        }
    }

    /** Called from the Reactor/WebClient thread. Publishes into the ring buffer — never writes to OrderBook directly. */
    private void publishSnapshotEvent(BookSlot slot, String rawJson) {
        int id = slot.instrument().id();
        RingBuffer<DepthEvent> rb = shardManager.getRingBuffer(id);
        long seq = rb.next();
        try {
            DepthEvent event = rb.get(seq);
            event.type         = EventType.SNAPSHOT;
            event.instrumentId = id;
            event.rawJson      = rawJson;
        } finally {
            rb.publish(seq);
        }
    }
}
