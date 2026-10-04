package dev.abu.screener_backend.marketdata.core.recovery;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEventPublisher;
import dev.abu.screener_backend.marketdata.spi.SnapshotFetcher;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Builds one {@link SnapshotRequestQueue} per REST-recovering venue and ticks them all. Each queue
 * is shaped by its exchange's {@code screener.exchanges.<exchange>.snapshot-queue} block.
 *
 * <p><b>Why the publisher is {@link Lazy}.</b> This breaks the startup cycle
 * {@code BookSlotTable → SyncStrategyRegistry → VenueStrategyBinding beans (adapter config) →
 * SnapshotQueueFactory → DepthEventPublisher → DisruptorShardManager → BookSlotTable}, in exactly
 * one place in core, and adapters never see the publisher. The proxy resolves on the first
 * publish, which needs an accepted request, which needs a diff — long after the context started.
 *
 * <p><b>Why its own thread.</b> Boot's shared {@code @Scheduled} scheduler is one thread shared
 * with the 100ms feed drain and the ticker refresh, which can block for up to
 * {@code screener.discovery.source-timeout}; that would stall recovery on every venue. A tick only
 * drains a small array and starts non-blocking requests, so one thread serves every queue.
 */
@Slf4j
@Component
public class SnapshotQueueFactory {

    private final DepthEventPublisher publisher;
    private final PipelineMetrics metrics;
    private final ExchangesProperties exchanges;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("snapshot-queue").daemon().factory());

    public SnapshotQueueFactory(@Lazy DepthEventPublisher publisher, PipelineMetrics metrics,
                                ExchangesProperties exchanges) {
        this.publisher = publisher;
        this.metrics = metrics;
        this.exchanges = exchanges;
    }

    /**
     * @throws IllegalStateException if the venue's exchange has no {@code snapshot-queue} block, or
     *                               its {@code batch-timeout} does not exceed the venue's
     *                               {@code rest.response-timeout}
     */
    public SnapshotRequestQueue create(Venue venue, SnapshotFetcher fetcher) {
        SnapshotQueueProperties props = queueProperties(venue);
        SnapshotRequestQueue queue = new SnapshotRequestQueue(venue, fetcher, props, publisher, metrics);
        long interval = props.flushInterval().toNanos();
        scheduler.scheduleWithFixedDelay(() -> tick(queue), interval, interval, TimeUnit.NANOSECONDS);
        log.info("[{}] snapshot queue started: max-batch-size={}, flush-interval={}, batch-timeout={}",
                venue, props.maxBatchSize(), props.flushInterval(), props.batchTimeout());
        return queue;
    }

    private SnapshotQueueProperties queueProperties(Venue venue) {
        String prefix = "screener.exchanges." + venue.exchange().name().toLowerCase();
        SnapshotQueueProperties props = exchanges.exchange(venue.exchange()).snapshotQueue();
        if (props == null) {
            throw new IllegalStateException("Venue " + venue + " recovers from REST snapshots but "
                    + prefix + ".snapshot-queue is missing");
        }
        // A batch-timeout at or under the per-request timeout would seal batches whose requests are
        // still legitimately running, failing books that were about to sync.
        RestProperties rest = exchanges.venue(venue).rest();
        if (rest != null && rest.responseTimeout() != null
                && props.batchTimeout().compareTo(rest.responseTimeout()) <= 0) {
            throw new IllegalStateException(prefix + ".snapshot-queue.batch-timeout (" + props.batchTimeout()
                    + ") must exceed " + prefix + ".venues." + venue.market() + ".rest.response-timeout ("
                    + rest.responseTimeout() + ")");
        }
        return props;
    }

    /** An exception escaping a periodic task cancels it for good — and with it, the venue's recovery. */
    private static void tick(SnapshotRequestQueue queue) {
        try {
            queue.tick();
        } catch (Throwable t) {
            log.error("[{}] snapshot queue tick failed", queue.venue(), t);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
