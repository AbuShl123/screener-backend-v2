package dev.abu.screener_backend.analysis.filter;

import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.book.PriceLevelEntry;

import java.time.Duration;
import java.util.Map;

/**
 * Rejects a level until its current order has sat at its price for {@code minAgeMillis}.
 *
 * <p>The age is {@link PriceLevelEntry#firstSeenMillis}, which {@link OrderBook#applyLevel} resets
 * when the quantity grows enough to be a new order. MM quotes follow mid by cancelling and
 * re-placing, so they rarely reach the minimum age at one price.
 *
 * <p>Classification runs on depth events, not on a timer: a level that reaches the minimum age
 * appears on its book's next diff.
 *
 * @param minAgeMillis how long a level must have been at its price, in milliseconds
 */
public record MinAgeFilter(long minAgeMillis) implements LevelFilter {

    public MinAgeFilter {
        if (minAgeMillis <= 0) throw new IllegalArgumentException("minAgeMillis must be > 0, got: " + minAgeMillis);
    }

    public MinAgeFilter(Duration minAge) {
        this(minAge.toMillis());
    }

    @Override
    public boolean accept(Map.Entry<Double, PriceLevelEntry> level, boolean isBid, OrderBook book, long nowMillis) {
        return nowMillis - level.getValue().firstSeenMillis >= minAgeMillis;
    }
}
