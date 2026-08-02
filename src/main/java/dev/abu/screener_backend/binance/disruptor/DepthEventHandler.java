package dev.abu.screener_backend.binance.disruptor;

import com.lmax.disruptor.EventHandler;
import dev.abu.screener_backend.analysis.OrderBookClassifier;
import dev.abu.screener_backend.binance.orderbook.OrderBook;
import dev.abu.screener_backend.binance.orderbook.OrderBookProcessor;
import dev.abu.screener_backend.binance.orderbook.ShardStatsPublisher;
import lombok.Getter;

// Consumer for an assigned shard in LMAX Disruptor
public class DepthEventHandler implements EventHandler<DepthEvent> {

    /**
     * Consult the wall clock only every 256th event.
     *
     * <p>Not a {@code screener.*} tunable — it is an implementation detail of the publish gate, and
     * it only has to be small enough that a healthy shard clears it several times per publish
     * interval. With 1 000+ books at 1–2 diffs/s each a shard sees ≥ 500 events/s, which holds the
     * 1 s cadence with margin. A shard too quiet to clear the mask is already an incident, and is
     * reported as one via {@code shardsStale} in the monitoring response.
     *
     * <p><b>Why not {@code endOfBatch}.</b> Disruptor sets {@code endOfBatch} on the last event
     * available at poll time, so whenever the consumer keeps up with its producers — the steady
     * state this design targets — every poll returns a single event and {@code endOfBatch} is true
     * on <i>every</i> message. A clock read behind that gate is not amortised at all; it degrades to
     * one {@code currentTimeMillis()} per message precisely when throughput is highest.
     */
    private static final int CLOCK_CHECK_MASK = 0xFF;

    @Getter
    private final int shardIndex;
    private final OrderBookProcessor obSyncMachine;
    private final OrderBookClassifier classificationModule;
    private final ShardStatsPublisher statsPublisher;
    private final long publishIntervalMs;

    /** Plain fields — confined to this handler's single consumer thread. */
    private int eventsSeen;
    private long lastStatsPublishMillis;

    public DepthEventHandler(int shardIndex,
                             OrderBookProcessor obSyncMachine,
                             OrderBookClassifier classificationModule,
                             ShardStatsPublisher statsPublisher,
                             long publishIntervalMs) {
        this.shardIndex = shardIndex;
        this.obSyncMachine = obSyncMachine;
        this.classificationModule = classificationModule;
        this.statsPublisher = statsPublisher;
        this.publishIntervalMs = publishIntervalMs;
    }

    @Override
    public void onEvent(DepthEvent event, long sequence, boolean endOfBatch) {
        // manage local orderbook of this event
        OrderBook ob = obSyncMachine.process(event);

        // run default & per-user classification
        if (ob != null) classificationModule.process(ob);

        // free
        event.clear();

        // republish this shard's monitoring stats on a slow, time-based cadence
        maybePublishStats();
    }

    private void maybePublishStats() {
        if ((++eventsSeen & CLOCK_CHECK_MASK) != 0) return;
        long now = System.currentTimeMillis();
        if (now - lastStatsPublishMillis >= publishIntervalMs) {
            lastStatsPublishMillis = now;
            statsPublisher.publishShard(shardIndex, now);
        }
    }
}
