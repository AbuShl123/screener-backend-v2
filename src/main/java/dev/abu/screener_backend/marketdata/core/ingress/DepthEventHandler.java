package dev.abu.screener_backend.marketdata.core.ingress;

import com.lmax.disruptor.EventHandler;
import dev.abu.screener_backend.analysis.OrderBookClassifier;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.book.BookSlotTable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

// Consumer for an assigned shard in LMAX Disruptor
@Slf4j
public class DepthEventHandler implements EventHandler<DepthEvent> {

    private static final long MISSING_SLOT_LOG_INTERVAL_MS = 60_000;
    private final AtomicLong missingSlots = new AtomicLong();
    private final AtomicLong missingSlotsLoggedAt = new AtomicLong();

    @Getter
    private final int shardIndex;
    private final BookSlotTable slots;
    private final OrderBookClassifier classificationModule;

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

    public DepthEventHandler(int shardIndex, BookSlotTable slots, OrderBookClassifier classificationModule) {
        this.shardIndex = shardIndex;
        this.slots = slots;
        this.classificationModule = classificationModule;
    }

    /** Monotonic total. Subtract two samples for a rate. */
    public long processed() {
        return processed;
    }

    @Override
    public void onEvent(DepthEvent event, long sequence, boolean endOfBatch) {
        processed++;
        BookSlot slot = slots.get(event.instrumentId);

        // should never happen
        if (slot == null) {
            noteMissingSlot(event.instrumentId);
            event.clear();
            return;
        }

        // manage local orderbook of this event
        slot.strategy().onEvent(slot, event);

        // run default & per-user classification
        classificationModule.process(slot.instrument(), slot.book());

        // free
        event.clear();
    }

    private void noteMissingSlot(int instrumentId) {
        long total = missingSlots.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = missingSlotsLoggedAt.get();
        if (now - last >= MISSING_SLOT_LOG_INTERVAL_MS && missingSlotsLoggedAt.compareAndSet(last, now)) {
            log.warn("No book slot for instrument id {} — event dropped ({} total)", instrumentId, total);
        }
    }
}
