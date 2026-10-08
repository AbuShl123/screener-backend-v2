package dev.abu.screener_backend.analysis.filter;

import dev.abu.screener_backend.analysis.OrderBookClassifier;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.book.PriceLevelEntry;

import java.util.Map;

/**
 * A per-venue condition a level must meet to be classified, on top of the rule's tier. Runs in
 * {@link OrderBookClassifier}'s top-K selection for tier-&ge;1 levels inside the visibility cap, before
 * the level takes a slot: a rejected level never displaces one that passes.
 *
 * <p>A filter does not depend on the rule, so the default pass and every user pass skip the same
 * levels. It runs on the shard's consumer thread for every candidate level: hot path, no allocation.
 */
@FunctionalInterface
public interface LevelFilter {

    /** For venues without filtering. */
    LevelFilter ACCEPT_ALL = new LevelFilter() {
        @Override
        public boolean accept(Map.Entry<Double, PriceLevelEntry> level, boolean isBid, OrderBook book, long nowMillis) {
            return true;
        }

        @Override
        public String toString() {
            return "ACCEPT_ALL";
        }
    };

    /**
     * @param level     the candidate, an entry of {@code book}'s bid or ask map
     * @param isBid     which side {@code level} is on
     * @param book      the level's book, for filters that compare against other levels
     * @param nowMillis wall-clock time, read once per {@link OrderBookClassifier#process} call
     * @return {@code true} to let the level compete for a top-K slot
     */
    boolean accept(Map.Entry<Double, PriceLevelEntry> level, boolean isBid, OrderBook book, long nowMillis);
}
