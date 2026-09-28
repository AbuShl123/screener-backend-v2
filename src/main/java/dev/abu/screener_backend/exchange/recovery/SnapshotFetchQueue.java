package dev.abu.screener_backend.exchange.recovery;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.binance.BinanceRestClient;
import dev.abu.screener_backend.exchange.book.BookSlot;
import dev.abu.screener_backend.exchange.ingress.DepthEventPublisher;
import dev.abu.screener_backend.exchange.spi.RecoverySink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * <p>The {@link DepthEventPublisher} dependency is {@link Lazy} to break the startup
 * circular dependency:
 * BookSlotTable → SyncStrategyRegistry → VenueStrategyBinding beans (BinanceAdapterConfig) →
 * SnapshotFetchQueue → DepthEventPublisher → DisruptorShardManager → BookSlotTable.
 * The proxy is resolved on first use, which only happens after the context is fully started.
 */
@Slf4j
@Component
public class SnapshotFetchQueue implements RecoverySink {

    private final BinanceRestClient spotClient;
    private final BinanceRestClient futuresClient;
    private final DepthEventPublisher publisher;
    private final int spotMaxSize;
    private final int futuresMaxSize;

    private final ConcurrentHashMap<Integer, BookSlot> spotQueue    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, BookSlot> futuresQueue = new ConcurrentHashMap<>();

    public SnapshotFetchQueue(@Qualifier("binanceSpotRestClient") BinanceRestClient spotClient,
                               @Qualifier("binanceFuturesRestClient") BinanceRestClient futuresClient,
                               @Lazy DepthEventPublisher publisher) {
        this.spotClient      = spotClient;
        this.futuresClient   = futuresClient;
        this.publisher       = publisher;
        this.spotMaxSize     = 10;
        this.futuresMaxSize  = 10;
    }

    /**
     * Enqueue an orderbook for snapshot fetching. Safe to call from the consumer thread.
     *
     * @return true if enqueued, false if the queue is at capacity
     */
    @Override
    public boolean requestRecovery(BookSlot slot) {
        boolean isSpot = slot.instrument().venue() == Venue.BINANCE_SPOT;
        ConcurrentHashMap<Integer, BookSlot> queue = isSpot ? spotQueue : futuresQueue;
        int maxSize = isSpot ? spotMaxSize : futuresMaxSize;
        if (queue.size() >= maxSize) return false;
        queue.put(slot.instrument().id(), slot);
        return true;
    }

    @Scheduled(fixedRateString = "6000")
    public void dispatchSpot() {
        for (BookSlot slot : spotQueue.values()) {
            int id = slot.instrument().id();
            spotClient.depth(slot.instrument().nativeSymbol(), 1000)
                    .subscribe(
                            rawJson -> {
                                spotQueue.remove(id);
                                publisher.publishSnapshot(id, rawJson);
                            },
                            error -> {
                                spotQueue.remove(id);
                                log.warn("Snapshot fetch failed for {}: {}", slot.instrument().logName(), error.getMessage());
                                requestRecovery(slot);
                            }
                    );
        }
    }

    @Scheduled(fixedRateString = "6000")
    public void dispatchFutures() {
        for (BookSlot slot : futuresQueue.values()) {
            int id = slot.instrument().id();
            futuresClient.depth(slot.instrument().nativeSymbol(), 1000)
                    .subscribe(
                            rawJson -> {
                                futuresQueue.remove(id);
                                publisher.publishSnapshot(id, rawJson);
                            },
                            error -> {
                                futuresQueue.remove(id);
                                log.warn("Snapshot fetch failed for {}: {}", slot.instrument().logName(), error.getMessage());
                                requestRecovery(slot);
                            }
                    );
        }
    }
}
