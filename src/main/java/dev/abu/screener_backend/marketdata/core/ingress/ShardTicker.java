package dev.abu.screener_backend.marketdata.core.ingress;

import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.RingBuffer;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publishes one {@link EventSource#TICK} event into every shard's ring on a fixed delay, so each
 * shard's consumer can act on the passing of time (today: {@code TradeDetector.closeIfIdle}). Owned
 * by {@link DisruptorShardManager}; plan: {@code .claude/plans/trade-mexc-plan.md} §2.6.
 *
 * <h3>Threading</h3>
 * One daemon thread, {@code shard-tick}. It <b>only publishes</b>: it never reads or writes a slot,
 * a book or a sweep. Every action a tick triggers runs on the shard's consumer thread, in ring order
 * with that shard's frames, so all per-instrument state keeps its single owner. The ticker is one
 * more producer on rings that are already {@code ProducerType.MULTI}.
 *
 * <p>It claims with {@code tryNext()}, never the blocking {@code next()}: a tick that does not fit a
 * full ring is skipped and counted ({@link PipelineMetrics#recordTickSkipped}), since the next one
 * follows and a late tick only delays a close. It runs with a fixed delay, not a fixed rate, so a
 * pause (a long GC) is not followed by a burst of catch-up ticks.
 */
@Slf4j
final class ShardTicker {

    private final RingBuffer<MarketEvent>[] rings;
    private final PipelineMetrics metrics;
    private ScheduledExecutorService executor;

    ShardTicker(RingBuffer<MarketEvent>[] rings, PipelineMetrics metrics) {
        this.rings = rings;
        this.metrics = metrics;
    }

    /** Starts ticking every {@code intervalMs}, first after one interval. */
    void start(long intervalMs) {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shard-tick");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tickSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    boolean running() {
        return executor != null && !executor.isShutdown();
    }

    void stop() {
        if (executor != null) executor.shutdownNow();
    }

    /** An exception escaping a scheduled task would cancel every later tick. */
    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("shard tick failed; the next one follows", e);
        }
    }

    /**
     * One tick into every shard's ring, without blocking. Writes every field: the ring slot is
     * reused, so a stale payload would leak through.
     */
    void tick() {
        for (RingBuffer<MarketEvent> ring : rings) {
            long seq;
            try {
                seq = ring.tryNext();
            } catch (InsufficientCapacityException e) {
                metrics.recordTickSkipped();
                continue;
            }
            try {
                MarketEvent event = ring.get(seq);
                event.source       = EventSource.TICK;
                event.stream       = null;
                event.instrumentId = -1;
                event.rawJson      = null;
                event.rawBytes     = null;
            } finally {
                ring.publish(seq);
            }
        }
    }
}
