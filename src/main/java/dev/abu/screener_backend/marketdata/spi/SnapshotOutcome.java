package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;

/**
 * Where a {@link SnapshotFetcher} reports each request's result. Implemented by core, so the
 * fetcher never sees the publisher, the book or its sync state. Thread-safe: reports may arrive
 * concurrently from HTTP threads.
 */
public interface SnapshotOutcome {

    /** The request succeeded; {@code body} is the raw response, parsed later by the sync strategy. */
    void delivered(InstrumentSlot slot, String body);

    /** The request failed, timed out, or was never sent. The book goes back to {@code PENDING}. */
    void failed(InstrumentSlot slot);
}
