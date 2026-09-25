package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.book.BookSlot;
import dev.abu.screener_backend.exchange.spi.RecoverySink;

/**
 * Stands in for {@code SnapshotFetchQueue}.
 *
 * <p>The refusal verdict is first-class rather than an afterthought: with
 * a snapshot queue capped at 10 per market against ~1400 books, a refused
 * {@code requestRecovery} is the <b>normal</b> startup path, not an edge case. A book that stops
 * re-asking after a refusal parks forever, so that behaviour needs pinning.
 *
 * <p>{@code requests} counts every call that reaches the sink, accepted or not. Several cases
 * assert it to protect the "at most one recovery request per event" invariant, which holds only
 * because {@code recover()} has exactly one call site.
 */
final class FakeRecoverySink implements RecoverySink {

    /** {@code false} models a snapshot queue at capacity. */
    boolean accepts = true;

    /** Calls that reached the sink, whatever the verdict. */
    int requests = 0;

    /** The slot handed to the most recent call, so tests can assert the book's state at that moment. */
    BookSlot lastSlot;

    @Override
    public boolean requestRecovery(BookSlot slot) {
        requests++;
        lastSlot = slot;
        return accepts;
    }
}
