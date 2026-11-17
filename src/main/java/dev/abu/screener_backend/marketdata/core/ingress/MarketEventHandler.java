package dev.abu.screener_backend.marketdata.core.ingress;

import com.lmax.disruptor.EventHandler;
import dev.abu.screener_backend.analysis.OrderBookClassifier;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlotTable;
import dev.abu.screener_backend.marketdata.spi.TradeDecoder;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

// Consumer for an assigned shard in LMAX Disruptor
@Slf4j
public class MarketEventHandler implements EventHandler<MarketEvent> {

    private static final long MISSING_SLOT_LOG_INTERVAL_MS = 60_000;
    private final AtomicLong missingSlots = new AtomicLong();
    private final AtomicLong missingSlotsLoggedAt = new AtomicLong();

    private static final long UNHANDLED_KIND_LOG_INTERVAL_MS = 60_000;
    private final AtomicLong unhandledKinds = new AtomicLong();
    private final AtomicLong unhandledKindsLoggedAt = new AtomicLong();

    @Getter
    private final int shardIndex;
    private final InstrumentSlotTable slots;
    private final OrderBookClassifier classificationModule;
    private final TradeSink tradeSink;
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
     * @param tradeSink the shared core {@code TradeDetector}; one instance serves every shard
     */
    public MarketEventHandler(int shardIndex, InstrumentSlotTable slots, OrderBookClassifier classificationModule,
                              TradeSink tradeSink, PipelineMetrics metrics) {
        this.shardIndex = shardIndex;
        this.slots = slots;
        this.classificationModule = classificationModule;
        this.tradeSink = tradeSink;
        this.metrics = metrics;
    }

    /** Monotonic total. Subtract two samples for a rate. */
    public long processed() {
        return processed;
    }

    /** Events dropped because no arm or decoder handled their kind. For tests. */
    long unhandledKinds() {
        return unhandledKinds.get();
    }

    @Override
    public void onEvent(MarketEvent event, long sequence, boolean endOfBatch) {
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
    }

    /**
     * Decodes the frame and hands its trades to the core sink. Independent of the book's sync
     * state: a trade is processed whether or not the book is {@code SYNCED}.
     */
    private void onTrade(InstrumentSlot slot, MarketEvent event) {
        TradeDecoder decoder = slot.tradeDecoder();
        if (decoder == null) {
            // A TRADE stream enabled for a venue whose adapter binds no decoder.
            noteUnhandledKind(event.stream);
            return;
        }
        if (!decoder.decode(slot, event.rawJson, tradeSink)) {
            metrics.recordTradeParseFailure(slot.instrument().venue());
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
