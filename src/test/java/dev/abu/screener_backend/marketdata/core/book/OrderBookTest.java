package dev.abu.screener_backend.marketdata.core.book;

import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.book.OrderBookState;
import dev.abu.screener_backend.marketdata.core.book.PriceLevelEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storage semantics of {@link OrderBook} — the half that is genuinely universal across venues.
 *
 * <p>{@code OrderBook} is now pure storage: no venue, no sequence cursor, no diff buffer, no JSON.
 * So unlike the old version of this file, these cases drive {@code applyLevel} / {@code clearLevels}
 * / {@code computeDistance} directly instead of smuggling levels in through Binance diffs. Nothing
 * here should ever need a {@code Venue} again — if a case does, it belongs in
 * {@code adapter/binance/BinanceDepthSyncStrategyTest}.
 *
 * <p>These are the invariants the classifier silently depends on.
 */
class OrderBookTest {

    /** Matches {@code screener.orderbook.price-filter-threshold} — a fraction, not a percentage. */
    private static final double FILTER = 0.1;

    private static final long T0 = 1_700_000_000_000L;

    @Test
    @DisplayName("a zero quantity removes the level")
    void zeroQuantityRemovesLevel() {
        OrderBook book = new OrderBook(FILTER);
        book.applyLevel(true, 99.0, 1.0, T0);
        book.applyLevel(true, 98.0, 1.0, T0);
        assertEquals(2, book.getBids().size());

        book.applyLevel(true, 98.0, 0.0, T0);

        assertFalse(book.getBids().containsKey(98.0));
        assertEquals(1, book.getBids().size());
    }

    @Test
    @DisplayName("a zero quantity for an unknown price is a no-op, not an insertion")
    void zeroQuantityForUnknownPriceIsANoOp() {
        OrderBook book = new OrderBook(FILTER);

        // Binance sends deletions for levels that were already outside our filter band and hence
        // never stored, so this happens constantly in the live flow.
        book.applyLevel(false, 12345.0, 0.0, T0);

        assertTrue(book.getAsks().isEmpty());
    }

    @Test
    @DisplayName("re-quoting a level updates quantity in place and preserves firstSeenMillis")
    void repeatedLevelUpdatesInPlaceAndKeepsFirstSeen() {
        OrderBook book = new OrderBook(FILTER);
        book.applyLevel(true, 99.0, 1.0, T0);
        PriceLevelEntry before = book.getBids().get(99.0);

        book.applyLevel(true, 99.0, 7.5, T0 + 5_000);

        PriceLevelEntry after = book.getBids().get(99.0);
        // Identity matters: level lifetime is what the classifier ranks on, so an update must not
        // replace the entry — and firstSeenMillis is final for exactly that reason.
        assertSame(before, after);
        assertEquals(7.5, after.quantity);
        assertEquals(T0, after.firstSeenMillis);
    }

    @Test
    @DisplayName("bids are ordered best-first and asks are ordered best-first")
    void sidesAreOrderedBestFirst() {
        OrderBook book = new OrderBook(FILTER);
        book.applyLevel(true, 97.0, 1.0, T0);
        book.applyLevel(true, 99.0, 1.0, T0);
        book.applyLevel(true, 98.0, 1.0, T0);
        book.applyLevel(false, 103.0, 1.0, T0);
        book.applyLevel(false, 101.0, 1.0, T0);
        book.applyLevel(false, 102.0, 1.0, T0);

        // computeDistance reads firstKey() on both sides as the best bid / best ask, so the
        // comparators are load-bearing, not cosmetic.
        assertEquals(99.0, book.getBids().firstKey());
        assertEquals(101.0, book.getAsks().firstKey());
    }

    @Test
    @DisplayName("distance is stored as a fraction of mid-price, not a percentage")
    void distanceIsAFractionOfMidPrice() {
        // A wide band so the 5% levels survive the sweep and can be inspected.
        OrderBook book = new OrderBook(0.5);
        book.applyLevel(true, 95.0, 1.0, T0);
        book.applyLevel(false, 105.0, 1.0, T0);

        book.computeDistance();

        // mid = (95 + 105) / 2 = 100, so both levels sit 5% away.
        assertEquals(0.05, book.getBids().get(95.0).distance, 1e-9);
        assertEquals(0.05, book.getAsks().get(105.0).distance, 1e-9);
    }

    @Test
    @DisplayName("levels outside the filter band are swept from both sides")
    void farLevelsAreSweptFromBothSides() {
        OrderBook book = new OrderBook(FILTER);
        book.applyLevel(true, 99.0, 1.0, T0);
        book.applyLevel(true, 85.0, 1.0, T0);
        book.applyLevel(false, 101.0, 1.0, T0);
        book.applyLevel(false, 115.0, 1.0, T0);

        // mid = 100, so the band is [90, 110].
        book.computeDistance();

        assertFalse(book.getBids().containsKey(85.0));
        assertFalse(book.getAsks().containsKey(115.0));
        assertTrue(book.getBids().containsKey(99.0));
        assertTrue(book.getAsks().containsKey(101.0));
        assertEquals(1, book.getBids().size());
        assertEquals(1, book.getAsks().size());
    }

    @Test
    @DisplayName("computeDistance is a no-op when either side is empty")
    void computeDistanceIsANoOpOnAOneSidedBook() {
        OrderBook book = new OrderBook(FILTER);
        book.applyLevel(true, 99.0, 1.0, T0);

        // There is no mid-price to sweep against; the guard must not divide by an absent best ask
        // or wipe the side that does have levels.
        book.computeDistance();

        assertEquals(1, book.getBids().size());
        assertEquals(0.0, book.getBids().get(99.0).distance);
    }

    @Test
    @DisplayName("clearLevels empties both sides and leaves the book reusable")
    void clearLevelsEmptiesBothSides() {
        OrderBook book = new OrderBook(FILTER);
        book.applyLevel(true, 99.0, 1.0, T0);
        book.applyLevel(false, 101.0, 1.0, T0);

        book.clearLevels();

        assertTrue(book.getBids().isEmpty());
        assertTrue(book.getAsks().isEmpty());

        // recover() clears then re-fills from the next snapshot, so the maps must stay usable.
        book.applyLevel(true, 90.0, 2.0, T0 + 1);
        assertEquals(2.0, book.getBids().get(90.0).quantity);
    }

    @Test
    @DisplayName("a new book starts PENDING and the state transitions are named")
    void stateLifecycle() {
        OrderBook book = new OrderBook(FILTER);
        assertEquals(OrderBookState.PENDING, book.getState());

        book.markRecovering();
        assertEquals(OrderBookState.RECOVERING, book.getState());

        book.markSynced();
        assertEquals(OrderBookState.SYNCED, book.getState());

        book.markPending();
        assertEquals(OrderBookState.PENDING, book.getState());
    }
}
