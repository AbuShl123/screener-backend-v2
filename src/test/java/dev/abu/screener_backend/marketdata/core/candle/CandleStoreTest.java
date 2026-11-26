package dev.abu.screener_backend.marketdata.core.candle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandleStoreTest {

    private static final long M = 60_000;
    private static final long T0 = 1_795_689_420_000L;

    private final CandleStore store = new CandleStore(3, M);

    @Test
    @DisplayName("pushes for the same open time update the forming candle; nothing is closed yet")
    void sameCandleUpdates() {
        assertFalse(store.hasForming());
        assertEquals(CandleStore.UPDATED, store.update(T0, 10, 9, 9.5, 100));
        assertEquals(CandleStore.UPDATED, store.update(T0, 11, 9, 10.5, 150));

        assertTrue(store.hasForming());
        assertEquals(T0, store.formingOpenTime());
        assertEquals(11, store.formingHigh());
        assertEquals(10.5, store.formingClose());
        assertEquals(150, store.formingQuoteVolume());
        assertEquals(0, store.filled());
    }

    @Test
    @DisplayName("the next open time closes the forming candle with its last values; index 0 is the newest")
    void rolloverOnNextOpenTime() {
        store.update(T0, 11, 9, 10, 150);
        assertEquals(CandleStore.ROLLED, store.update(T0 + M, 12, 10, 11, 20));
        assertEquals(CandleStore.ROLLED, store.update(T0 + 2 * M, 13, 11, 12, 30));

        assertEquals(2, store.filled());
        assertEquals(12, store.closedHigh(0));
        assertEquals(10, store.closedLow(0));
        assertEquals(20, store.closedQuoteVolume(0));
        assertEquals(11, store.closedHigh(1));
        assertEquals(150, store.closedQuoteVolume(1));
        assertEquals(13, store.formingHigh());
    }

    @Test
    @DisplayName("the ring keeps the newest `capacity` candles")
    void ringWraps() {
        for (int i = 0; i < 6; i++) store.update(T0 + i * M, 1, 1, 1, i);

        assertEquals(3, store.filled());
        assertEquals(4, store.closedQuoteVolume(0));
        assertEquals(3, store.closedQuoteVolume(1));
        assertEquals(2, store.closedQuoteVolume(2));
        assertThrows(IndexOutOfBoundsException.class, () -> store.closedQuoteVolume(3));
    }

    @Test
    @DisplayName("a push for an older candle is ignored")
    void stalePushIgnored() {
        store.update(T0 + M, 5, 4, 4.5, 10);
        assertEquals(CandleStore.STALE, store.update(T0, 99, 1, 50, 999));
        assertEquals(5, store.formingHigh());
        assertEquals(T0 + M, store.formingOpenTime());
    }

    @Test
    @DisplayName("a skipped minute restarts the closed history, so no window spans the hole")
    void gapResetsHistory() {
        store.update(T0, 1, 1, 1, 1);
        store.update(T0 + M, 1, 1, 1, 2);
        store.update(T0 + 2 * M, 1, 1, 1, 3);
        assertEquals(2, store.filled());

        assertEquals(CandleStore.GAP, store.update(T0 + 5 * M, 2, 2, 2, 4));
        assertEquals(0, store.filled());
        assertEquals(T0 + 5 * M, store.formingOpenTime());

        assertEquals(CandleStore.ROLLED, store.update(T0 + 6 * M, 2, 2, 2, 5));
        assertEquals(1, store.filled());
        assertEquals(4, store.closedQuoteVolume(0));
    }
}
