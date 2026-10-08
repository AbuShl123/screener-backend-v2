package dev.abu.screener_backend.analysis.filter;

import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.book.PriceLevelEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinAgeFilterTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final double PRICE = 99_500.0;

    private final MinAgeFilter filter = new MinAgeFilter(Duration.ofSeconds(30));
    private final OrderBook book = new OrderBook(0.1, 2.0);

    private boolean accepts(long nowMillis) {
        Map.Entry<Double, PriceLevelEntry> level = Map.entry(PRICE, book.getBids().get(PRICE));
        return filter.accept(level, true, book, nowMillis);
    }

    @Test
    @DisplayName("a level younger than the minimum age is rejected, one at or past it is accepted")
    void youngRejectedOldAccepted() {
        book.applyLevel(true, PRICE, 10.0, T0);

        assertFalse(accepts(T0));
        assertFalse(accepts(T0 + 29_999));
        assertTrue(accepts(T0 + 30_000));
        assertTrue(accepts(T0 + 600_000));
    }

    @Test
    @DisplayName("a level whose age was reset by a 2x growth is rejected again until it re-ages")
    void resetByGrowthRejectedAgain() {
        book.applyLevel(true, PRICE, 10.0, T0);
        assertTrue(accepts(T0 + 40_000));

        book.applyLevel(true, PRICE, 20.0, T0 + 40_000); // doubled: a new order at this price

        assertFalse(accepts(T0 + 50_000));
        assertTrue(accepts(T0 + 70_000));
    }

    @Test
    @DisplayName("a level shrinking keeps its age")
    void shrinkKeepsAge() {
        book.applyLevel(true, PRICE, 10.0, T0);
        book.applyLevel(true, PRICE, 4.0, T0 + 20_000);

        assertTrue(accepts(T0 + 30_000));
    }

    @Test
    @DisplayName("a non-positive minimum age is rejected")
    void nonPositiveMinAge() {
        assertThrows(IllegalArgumentException.class, () -> new MinAgeFilter(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new MinAgeFilter(-1));
    }
}
