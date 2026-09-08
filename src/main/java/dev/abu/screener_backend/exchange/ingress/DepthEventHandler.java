package dev.abu.screener_backend.exchange.ingress;

import com.lmax.disruptor.EventHandler;
import dev.abu.screener_backend.analysis.OrderBookClassifier;
import dev.abu.screener_backend.exchange.book.BookSlot;
import dev.abu.screener_backend.exchange.book.BookSlotTable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

// Consumer for an assigned shard in LMAX Disruptor
@Slf4j
@AllArgsConstructor
public class DepthEventHandler implements EventHandler<DepthEvent> {

    private static final long MISSING_SLOT_LOG_INTERVAL_MS = 60_000;
    private final AtomicLong missingSlots = new AtomicLong();
    private final AtomicLong missingSlotsLoggedAt = new AtomicLong();

    @Getter
    private final int shardIndex;
    private final BookSlotTable slots;
    private final OrderBookClassifier classificationModule;

    @Override
    public void onEvent(DepthEvent event, long sequence, boolean endOfBatch) {
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
