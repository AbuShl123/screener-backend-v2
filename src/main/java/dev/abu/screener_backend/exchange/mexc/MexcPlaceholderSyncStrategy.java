package dev.abu.screener_backend.exchange.mexc;

import dev.abu.screener_backend.exchange.book.BookSlot;
import dev.abu.screener_backend.exchange.ingress.DepthEvent;
import dev.abu.screener_backend.exchange.spi.BookSyncContext;
import dev.abu.screener_backend.exchange.spi.DepthSyncStrategy;

/**
 * Stand-in for {@link MexcFuturesSyncStrategy} until MEXC has a snapshot queue to recover through
 * (MEXC plan, Phase 4b) — the real strategy needs a {@code RecoverySink}. Drops every event, so
 * MEXC books stay {@code PENDING} and never reach classification.
 *
 * <p>Exists because {@code BookSlotTable.allocate} resolves a strategy for every discovered
 * instrument and fails for an unbound venue. It never requests recovery, so it never sees a
 * {@code REST_MSG} or {@code REST_FAILED} event.
 */
class MexcPlaceholderSyncStrategy implements DepthSyncStrategy {

    @Override
    public BookSyncContext newContext() {
        return new BookSyncContext() {};
    }

    @Override
    public void onEvent(BookSlot slot, DepthEvent event) {
        // Intentionally empty — see the class javadoc.
    }
}
