package dev.abu.screener_backend.exchange.book;

import dev.abu.screener_backend.exchange.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static dev.abu.screener_backend.exchange.book.SyncTestSupport.Harness;
import static dev.abu.screener_backend.exchange.book.SyncTestSupport.diff;
import static dev.abu.screener_backend.exchange.book.SyncTestSupport.levels;
import static dev.abu.screener_backend.exchange.book.SyncTestSupport.lvl;
import static dev.abu.screener_backend.exchange.book.SyncTestSupport.snapshot;
import static dev.abu.screener_backend.exchange.book.SyncTestSupport.synced;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storage semantics of {@link OrderBook}, independent of any venue's sequencing rules.
 *
 * <p>These are the invariants the classifier silently depends on and that nothing currently
 * asserts. They are also the half of {@code OrderBook} that survives the SPI extraction unchanged,
 * so unlike {@link OrderBookSyncCharacterizationTest} these assertions port to commit C's
 * {@code applyLevel} / {@code clearLevels} surface with no change in meaning — only in how the
 * levels get in.
 *
 * <p>Levels are driven through real diffs on a SYNCED book, because today the only way into the
 * {@code TreeMap}s is the parser.
 */
class OrderBookTest {

    private static final Venue FUTURES = Venue.BINANCE_FUTURES;

    @Test
    @DisplayName("a zero quantity removes the level")
    void zeroQuantityRemovesLevel() {
        Harness h = synced(FUTURES, levels(lvl(99, 1), lvl(98, 1)), levels(lvl(101, 1)));
        assertEquals(2, h.book.getBids().size());

        h.wsMsg(diff(FUTURES, 121, 130, 120, levels(lvl(98, 0)), ""));

        assertFalse(h.book.getBids().containsKey(98.0));
        assertEquals(1, h.book.getBids().size());
    }

    @Test
    @DisplayName("re-quoting a level updates quantity in place and preserves firstSeenMillis")
    void repeatedLevelUpdatesInPlaceAndKeepsFirstSeen() {
        Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));

        PriceLevelEntry before = h.book.getBids().get(99.0);
        long firstSeen = before.firstSeenMillis;

        h.wsMsg(diff(FUTURES, 121, 130, 120, levels(lvl(99, 7.5)), ""));

        PriceLevelEntry after = h.book.getBids().get(99.0);
        // Identity matters: level lifetime is what the classifier ranks on, so an update must not
        // replace the entry.
        assertSame(before, after);
        assertEquals(7.5, after.quantity);
        assertEquals(firstSeen, after.firstSeenMillis);
    }

    @Test
    @DisplayName("distance is stored as a fraction of mid-price, not a percentage")
    void distanceIsAFractionOfMidPrice() {
        // A wide band so the 5% levels survive the sweep and can be inspected.
        Harness h = synced(FUTURES, 0.5, levels(lvl(95, 1)), levels(lvl(105, 1)));

        // mid = (95 + 105) / 2 = 100, so both levels sit 5% away.
        assertEquals(0.05, h.book.getBids().get(95.0).distance, 1e-9);
        assertEquals(0.05, h.book.getAsks().get(105.0).distance, 1e-9);
    }

    @Test
    @DisplayName("levels outside the filter band are swept from both sides")
    void farLevelsAreSweptFromBothSides() {
        // FILTER is 0.1 and mid is 100, so the band is [90, 110].
        Harness h = synced(FUTURES,
                levels(lvl(99, 1), lvl(85, 1)),
                levels(lvl(101, 1), lvl(115, 1)));

        assertFalse(h.book.getBids().containsKey(85.0));
        assertFalse(h.book.getAsks().containsKey(115.0));
        assertTrue(h.book.getBids().containsKey(99.0));
        assertTrue(h.book.getAsks().containsKey(101.0));
        assertEquals(1, h.book.getBids().size());
        assertEquals(1, h.book.getAsks().size());
    }

    @Test
    @DisplayName("applying a snapshot replaces the book's previous contents")
    void snapshotReplacesPreviousLevels() {
        Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));
        assertTrue(h.book.getBids().containsKey(99.0));

        // Desync, then take a fresh snapshot at a different price level entirely.
        h.wsMsg(diff(FUTURES, 500, 510, 499, levels(lvl(99, 2)), ""));
        assertEquals(OrderBookState.SNAPSHOT_REQUESTED, h.book.getState());
        h.wsMsg(diff(FUTURES, 600, 610, 599, "", ""));
        h.wsMsg(diff(FUTURES, 611, 620, 610, "", ""));
        h.restMsg(snapshot(605, levels(lvl(90, 1)), levels(lvl(110, 1))));

        assertEquals(OrderBookState.SYNCED, h.book.getState());
        assertFalse(h.book.getBids().containsKey(99.0), "stale levels must not survive a snapshot");
        assertFalse(h.book.getAsks().containsKey(101.0));
        assertTrue(h.book.getBids().containsKey(90.0));
        assertTrue(h.book.getAsks().containsKey(110.0));
    }

    @Test
    @DisplayName("snapshot levels with zero quantity are not loaded")
    void zeroQuantitySnapshotLevelsAreSkipped() {
        Harness h = synced(FUTURES, levels(lvl(99, 1), lvl(98, 0)), levels(lvl(101, 1)));

        assertEquals(OrderBookState.SYNCED, h.book.getState());
        assertFalse(h.book.getBids().containsKey(98.0));
        assertEquals(1, h.book.getBids().size());
    }
}
