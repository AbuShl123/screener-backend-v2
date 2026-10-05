package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEvent;

/**
 * Keeps one venue's books in sync. Called on the book's shard consumer thread for every event.
 *
 * <h3>Contract for strategies that recover through {@code SnapshotRequestQueue}</h3>
 * Every request the queue accepts gets exactly one outcome event: {@code REST_MSG} with the body,
 * or {@code REST_FAILED} with none. A strategy must handle {@code REST_FAILED} the same way the
 * Binance one does — on a {@code RECOVERING} book, reset the sync context and mark the book
 * {@code PENDING} (no re-request, no resync); on any other state, drop it. Ignoring it strands the
 * book in {@code RECOVERING} with nothing in flight. A {@code PENDING} book always has empty
 * levels and an empty context.
 */
public interface DepthSyncStrategy {

    BookSyncContext newContext();

    void onEvent(BookSlot slot, DepthEvent event);

}
