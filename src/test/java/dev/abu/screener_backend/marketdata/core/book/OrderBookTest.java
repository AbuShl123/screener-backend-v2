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

    /** Matches {@code screener.orderbook.age-reset-growth}. */
    private static final double GROWTH = 2.0;

    private static final long T0 = 1_700_000_000_000L;

    @Test
    @DisplayName("a zero quantity removes the level")
    void zeroQuantityRemovesLevel() {
        OrderBook book = new OrderBook(FILTER, GROWTH);
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
        OrderBook book = new OrderBook(FILTER, GROWTH);

        // Binance sends deletions for levels that were already outside our filter band and hence
        // never stored, so this happens constantly in the live flow.
        book.applyLevel(false, 12345.0, 0.0, T0);

        assertTrue(book.getAsks().isEmpty());
    }

    @Test
    @DisplayName("re-quoting a level updates quantity in place and preserves firstSeenMillis")
    void repeatedLevelUpdatesInPlaceAndKeepsFirstSeen() {
        OrderBook book = new OrderBook(FILTER, GROWTH);
        book.applyLevel(true, 99.0, 1.0, T0);
        PriceLevelEntry before = book.getBids().get(99.0);

        book.applyLevel(true, 99.0, 1.5, T0 + 5_000);

        PriceLevelEntry after = book.getBids().get(99.0);
        // Identity matters: an update must not replace the entry. Growth below 2x keeps the age.
        assertSame(before, after);
        assertEquals(1.5, after.quantity);
        assertEquals(T0, after.firstSeenMillis);
    }

    @Test
    @DisplayName("a large order placed on top of a small resting one resets firstSeenMillis")
    void growthOnTopOfRestingOrderResetsAge() {
        OrderBook book = new OrderBook(FILTER, GROWTH);
        book.applyLevel(true, 99.0, 20_000, T0);

        book.applyLevel(true, 99.0, 300_000, T0 + 5_000);

        assertEquals(T0 + 5_000, book.getBids().get(99.0).firstSeenMillis);
    }

    @Test
    @DisplayName("pulling to the resting size and re-placing resets firstSeenMillis again")
    void pullAndReplaceResetsAge() {
        OrderBook book = new OrderBook(FILTER, GROWTH);
        book.applyLevel(false, 101.0, 20_000, T0);
        book.applyLevel(false, 101.0, 300_000, T0 + 1_000);  // reset, low is now 300k

        book.applyLevel(false, 101.0, 20_000, T0 + 2_000);   // pull: shrink, no reset, low = 20k
        assertEquals(T0 + 1_000, book.getAsks().get(101.0).firstSeenMillis);

        book.applyLevel(false, 101.0, 300_000, T0 + 3_000);  // re-place: 15x the low
        assertEquals(T0 + 3_000, book.getAsks().get(101.0).firstSeenMillis);
    }

    @Test
    @DisplayName("growth in small steps resets once the quantity reaches 2x the low")
    void stepwiseGrowthResetsAge() {
        OrderBook book = new OrderBook(FILTER, GROWTH);
        book.applyLevel(true, 99.0, 20_000, T0);
        book.applyLevel(true, 99.0, 30_000, T0 + 1_000);
        book.applyLevel(true, 99.0, 39_000, T0 + 2_000);
        assertEquals(T0, book.getBids().get(99.0).firstSeenMillis);

        book.applyLevel(true, 99.0, 40_000, T0 + 3_000);    // exactly 2x the low of 20k
        assertEquals(T0 + 3_000, book.getBids().get(99.0).firstSeenMillis);

        // ...and keeps growing from the new low: 300k is far past 2x of 40k.
        book.applyLevel(true, 99.0, 300_000, T0 + 4_000);
        assertEquals(T0 + 4_000, book.getBids().get(99.0).firstSeenMillis);
    }

    @Test
    @DisplayName("a wall eaten by fills keeps its firstSeenMillis")
    void shrinkingNeverResetsAge() {
        OrderBook book = new OrderBook(FILTER, GROWTH);
        book.applyLevel(true, 99.0, 1_000_000, T0);

        book.applyLevel(true, 99.0, 700_000, T0 + 1_000);
        book.applyLevel(true, 99.0, 400_000, T0 + 2_000);

        PriceLevelEntry entry = book.getBids().get(99.0);
        assertEquals(400_000, entry.quantity);
        assertEquals(T0, entry.firstSeenMillis);
    }

    @Test
    @DisplayName("normal +-20% changes never reset firstSeenMillis")
    void smallChangesNeverResetAge() {
        OrderBook book = new OrderBook(FILTER, GROWTH);
        book.applyLevel(false, 101.0, 100_000, T0);

        book.applyLevel(false, 101.0, 120_000, T0 + 1_000);
        book.applyLevel(false, 101.0, 80_000, T0 + 2_000);
        book.applyLevel(false, 101.0, 96_000, T0 + 3_000);
        book.applyLevel(false, 101.0, 115_000, T0 + 4_000);

        assertEquals(T0, book.getAsks().get(101.0).firstSeenMillis);
    }

    @Test
    @DisplayName("growth is measured from the lowest quantity since the reset, not the previous one")
    void growthIsMeasuredFromTheLow() {
        OrderBook book = new OrderBook(FILTER, GROWTH);
        book.applyLevel(true, 99.0, 100_000, T0);

        book.applyLevel(true, 99.0, 60_000, T0 + 1_000);    // drop, low = 60k
        book.applyLevel(true, 99.0, 90_000, T0 + 2_000);    // slight growth, 1.5x the low
        book.applyLevel(true, 99.0, 40_000, T0 + 3_000);    // drop further, low = 40k
        book.applyLevel(true, 99.0, 70_000, T0 + 4_000);    // 1.75x the low: no reset
        assertEquals(T0, book.getBids().get(99.0).firstSeenMillis);

        // 80k is below the original 100k and below 2x every previous quantity except the low.
        book.applyLevel(true, 99.0, 80_000, T0 + 5_000);
        assertEquals(T0 + 5_000, book.getBids().get(99.0).firstSeenMillis);
    }

    @Test
    @DisplayName("bids are ordered best-first and asks are ordered best-first")
    void sidesAreOrderedBestFirst() {
        OrderBook book = new OrderBook(FILTER, GROWTH);
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
        OrderBook book = new OrderBook(0.5, GROWTH);
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
        OrderBook book = new OrderBook(FILTER, GROWTH);
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
        OrderBook book = new OrderBook(FILTER, GROWTH);
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
        OrderBook book = new OrderBook(FILTER, GROWTH);
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
        OrderBook book = new OrderBook(FILTER, GROWTH);
        assertEquals(OrderBookState.PENDING, book.getState());

        book.markRecovering();
        assertEquals(OrderBookState.RECOVERING, book.getState());

        book.markSynced();
        assertEquals(OrderBookState.SYNCED, book.getState());

        book.markPending();
        assertEquals(OrderBookState.PENDING, book.getState());
    }
}
