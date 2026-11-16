package dev.abu.screener_backend.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamKeyTest {

    @Test
    @DisplayName("index() is unique and dense over every (venue, kind)")
    void indexIsDense() {
        Set<Integer> seen = new HashSet<>();
        for (Venue venue : Venue.values()) {
            for (StreamKind kind : StreamKind.values()) {
                int index = new StreamKey(venue, kind).index();
                assertTrue(index >= 0 && index < StreamKey.COUNT, "index " + index + " out of range");
                assertTrue(seen.add(index), "duplicate index " + index);
            }
        }
        assertEquals(StreamKey.COUNT, seen.size());
        assertEquals(Venue.values().length * StreamKind.values().length, StreamKey.COUNT);
    }

    @Test
    @DisplayName("logName() is VENUE/KIND, precomputed")
    void logName() {
        StreamKey key = new StreamKey(Venue.BINANCE_FUTURES, StreamKind.DEPTH);

        assertEquals("BINANCE_FUTURES/DEPTH", key.logName());
        assertEquals("BINANCE_FUTURES/DEPTH", key.toString());
        assertSame(key.logName(), new StreamKey(Venue.BINANCE_FUTURES, StreamKind.DEPTH).logName());
    }

    @Test
    @DisplayName("keys are value-equal and reject nulls")
    void valueSemantics() {
        assertEquals(new StreamKey(Venue.MEXC_SPOT, StreamKind.DEPTH), new StreamKey(Venue.MEXC_SPOT, StreamKind.DEPTH));
        assertThrows(NullPointerException.class, () -> new StreamKey(null, StreamKind.DEPTH));
        assertThrows(NullPointerException.class, () -> new StreamKey(Venue.MEXC_SPOT, null));
    }
}
