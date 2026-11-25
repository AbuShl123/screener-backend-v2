package dev.abu.screener_backend.marketdata.core.ingress;

import com.lmax.disruptor.EventHandler;
import dev.abu.screener_backend.analysis.OrderBookClassifier;
import dev.abu.screener_backend.analysis.cluster.OrderClusterDetector;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlotTable;
import dev.abu.screener_backend.marketdata.core.trade.TradeDetector;
import dev.abu.screener_backend.marketdata.spi.TradeDecoder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

// Consumer for an assigned shard in LMAX Disruptor
@Slf4j
public class MarketEventHandler implements EventHandler<MarketEvent> {

    private static final long MISSING_SLOT_LOG_INTERVAL_MS = 60_000;
    private final AtomicLong missingSlots = new AtomicLong();
    private final AtomicLong missingSlotsLoggedAt = new AtomicLong();

    private static final long CLUSTER_FAILURE_LOG_INTERVAL_MS = 60_000;
    private final AtomicLong clusterFailuresLoggedAt = new AtomicLong();

    private static final long UNHANDLED_KIND_LOG_INTERVAL_MS = 60_000;
    private final AtomicLong unhandledKinds = new AtomicLong();
    private final AtomicLong unhandledKindsLoggedAt = new AtomicLong();

    @Getter
    private final int shardIndex;
    /** Step between this shard's instrument ids: {@code id & (shardCount - 1) == shardIndex}. */
    private final int shardCount;
    private final InstrumentSlotTable slots;
    private final OrderBookClassifier classificationModule;
    /** {@code null} when {@code screener.clusters.enabled} is off. */
    private final OrderClusterDetector clusterDetector;
    private final TradeDetector tradeDetector;
    private final PipelineMetrics metrics;

    /**
     * Events consumed by this shard since startup.
     *
     * <p>Written only by this shard's consumer thread — one handler per shard, so there is exactly
     * one writer and no sharing between shards. Read racily from the health logger's scheduler
     * thread: a plain {@code long} keeps the hot path free of the {@code volatile} store's fence,
     * and a sample that is a few milliseconds stale cannot change a per-30s rate. Aligned 64-bit
     * accesses do not tear, and the reader does not spin on it, so there is nothing to hoist.
     */
    private long processed;

    /**
     * @param shardCount    the number of shards, a power of two; with {@code shardIndex}, it says which
     *                      instrument ids this shard owns, for the tick's walk
     * @param tradeDetector the shared core detector; one instance serves every shard. The decoder
     *                      reports into it as a {@code TradeSink}, and the handler calls its
     *                      frame-end and idle closes, which are core's, not part of the decoder SPI.
     */
    public MarketEventHandler(int shardIndex, int shardCount, InstrumentSlotTable slots,
                              OrderBookClassifier classificationModule, TradeDetector tradeDetector,
                              PipelineMetrics metrics) {
        this(shardIndex, shardCount, slots, classificationModule, null, tradeDetector, metrics);
    }

    /**
     * @param clusterDetector this shard's own order-cluster detector, or {@code null} when cluster
     *                        detection is disabled
     */
    public MarketEventHandler(int shardIndex, int shardCount, InstrumentSlotTable slots,
                              OrderBookClassifier classificationModule, OrderClusterDetector clusterDetector,
                              TradeDetector tradeDetector, PipelineMetrics metrics) {
        this.shardIndex = shardIndex;
        this.shardCount = shardCount;
        this.slots = slots;
        this.classificationModule = classificationModule;
        this.clusterDetector = clusterDetector;
        this.tradeDetector = tradeDetector;
        this.metrics = metrics;
    }

    /** Monotonic total of instrument events; shard ticks are not counted. Subtract two samples for a rate. */
    public long processed() {
        return processed;
    }

    /** Events dropped because no arm or decoder handled their kind. For tests. */
    long unhandledKinds() {
        return unhandledKinds.get();
    }

    @Override
    public void onEvent(MarketEvent event, long sequence, boolean endOfBatch) {
        // A tick has no instrument and no stream kind: handled before the slot lookup and the switch.
        if (event.source == EventSource.TICK) {
            onTick();
            event.clear();
            return;
        }
        processed++;
        InstrumentSlot slot = slots.get(event.instrumentId);

        // should never happen
        if (slot == null) {
            noteMissingSlot(event.instrumentId);
            event.clear();
            return;
        }

        switch (event.stream) {
            case DEPTH -> onDepth(slot, event);
            case TRADE -> onTrade(slot, event);
            // A kind that is bound and streaming but has no arm here: count it so it cannot go dark
            // silently. Unreachable while every kind has an arm; the TRADE arm also lands in
            // noteUnhandledKind when the venue has no decoder.
            default -> noteUnhandledKind(event.stream);
        }

        // free
        event.clear();
    }

    private void onDepth(InstrumentSlot slot, MarketEvent event) {
        // manage local orderbook of this event
        slot.syncStrategy().onEvent(slot, event);

        // run default & per-user classification
        classificationModule.process(slot.instrument(), slot.book());

        // layered order clusters: throttled per instrument, a separate walk from the classifier's
        if (clusterDetector != null) {
            detectClusters(slot);
        }
    }

    /**
     * Runs cluster detection, containing any failure: an exception escaping {@code onEvent} would
     * halt this shard's consumer (the Disruptor's default fatal handler), and with it every book on
     * the shard, for an optional feature. A failure is counted, logged at most once a minute, and
     * the instrument's cluster state is dropped so the next run starts clean.
     */
    private void detectClusters(InstrumentSlot slot) {
        try {
            clusterDetector.process(slot.instrument(), slot.book());
        } catch (RuntimeException e) {
            metrics.recordClusterFailure();
            long now = System.currentTimeMillis();
            long last = clusterFailuresLoggedAt.get();
            if (now - last >= CLUSTER_FAILURE_LOG_INTERVAL_MS && clusterFailuresLoggedAt.compareAndSet(last, now)) {
                log.warn("Cluster detection failed for {} ({} failures in total); its cluster state was dropped",
                        slot.instrument().logName(), metrics.clusterFailures(), e);
            }
            try {
                clusterDetector.discard(slot.instrument());
            } catch (RuntimeException again) {
                // a failing sink must not halt the shard either; the first failure is already counted
                log.debug("Dropping the cluster state of {} failed too", slot.instrument().logName(), again);
            }
        }
    }

    /**
     * Decodes the frame and hands its trades to the core detector, then tells the detector the frame
     * is over, whether it decoded or not. Independent of the book's sync state: a trade is processed
     * whether or not the book is {@code SYNCED}.
     *
     * <p>The payload picks the overload: a binary frame ({@code rawBytes}, MEXC spot) or a text one.
     */
    private void onTrade(InstrumentSlot slot, MarketEvent event) {
        TradeDecoder decoder = slot.tradeDecoder();
        if (decoder == null) {
            // A TRADE stream enabled for a venue whose adapter binds no decoder.
            noteUnhandledKind(event.stream);
            return;
        }
        boolean decoded = event.rawBytes != null
                ? decoder.decode(slot, event.rawBytes, tradeDetector)
                : decoder.decode(slot, event.rawJson, tradeDetector);
        if (!decoded) {
            metrics.recordTradeParseFailure(slot.instrument().venue());
        }
        tradeDetector.onFrameEnd(slot);
    }

    /**
     * The shard tick: visits every instrument this shard owns (ids {@code shardIndex},
     * {@code shardIndex + shardCount}, …, the same ids {@code id & (shardCount - 1)} routes here) and
     * lets the detector close an idle sweep. A few thousand checks per tick at most, nearly all of
     * them a null or a closed sweep.
     */
    private void onTick() {
        InstrumentSlot[] all = slots.snapshot();
        for (int id = shardIndex; id < all.length; id += shardCount) {
            InstrumentSlot slot = all[id];
            if (slot != null) tradeDetector.closeIfIdle(slot);
        }
    }

    private void noteMissingSlot(int instrumentId) {
        long total = missingSlots.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = missingSlotsLoggedAt.get();
        if (now - last >= MISSING_SLOT_LOG_INTERVAL_MS && missingSlotsLoggedAt.compareAndSet(last, now)) {
            log.warn("No instrument slot for instrument id {} — event dropped ({} total)", instrumentId, total);
        }
    }

    private void noteUnhandledKind(StreamKind kind) {
        long total = unhandledKinds.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = unhandledKindsLoggedAt.get();
        if (now - last >= UNHANDLED_KIND_LOG_INTERVAL_MS && unhandledKindsLoggedAt.compareAndSet(last, now)) {
            log.warn("No handler for stream kind {} (no arm, or no decoder bound for the venue) — event dropped ({} total on shard {})",
                    kind, total, shardIndex);
        }
    }
}
