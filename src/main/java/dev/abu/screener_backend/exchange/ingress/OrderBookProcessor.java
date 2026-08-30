package dev.abu.screener_backend.exchange.ingress;

import dev.abu.screener_backend.exchange.book.BookSlot;
import dev.abu.screener_backend.exchange.book.BookSlotTable;
import dev.abu.screener_backend.exchange.book.OrderBookResult;
import dev.abu.screener_backend.exchange.recovery.SnapshotFetchQueue;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Routes Disruptor events to the appropriate {@link BookSlot} and reacts to results.
 *
 * <p>This bean is injected into each {@link DepthEventHandler} and called once per ring buffer
 * event.
 *
 * <p>Slots are pre-populated at instrument registration, so this no longer branches on event type
 * to decide whether a book may be created, and no longer builds a lookup key per message.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderBookProcessor {

    private static final long MISSING_SLOT_LOG_INTERVAL_MS = 60_000;

    private final BookSlotTable slots;
    private final SnapshotFetchQueue snapshotFetchQueue;

    /**
     * Should be permanently zero. A non-zero value means an id was routed for which no slot was
     * ever published — i.e. the identity mapping is wrong.
     */
    private final AtomicLong missingSlots = new AtomicLong();
    private final AtomicLong missingSlotsLoggedAt = new AtomicLong();

    public @Nullable BookSlot process(DepthEvent event) {
        BookSlot slot = slots.get(event.instrumentId);
        if (slot == null) {
            noteMissingSlot(event.instrumentId);
            return null;
        }

        OrderBookResult result = (event.type == EventType.SNAPSHOT)
                ? slot.book().applySnapshot(event.rawJson)
                : slot.book().onDiff(event.rawJson);

        if (result == OrderBookResult.NEEDS_SNAPSHOT || result == OrderBookResult.NEEDS_RESYNC) {
            if (!snapshotFetchQueue.enqueue(slot)) {
                return slot;
            }

            slot.book().markSnapshotRequested();

            if (result == OrderBookResult.NEEDS_SNAPSHOT) {
                slot.book().onDiff(event.rawJson);
            }
        }

        return slot;
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
