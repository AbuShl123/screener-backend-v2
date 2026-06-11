package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.binance.orderbook.PriceLevelEntry;
import dev.abu.screener_backend.feed.ClassifiedLevel;

import java.util.Map;

import static dev.abu.screener_backend.analysis.OrderBookClassifier.TOP_LEVELS;

/**
 * Per-{@code (symbol, market)} activity state for one classification context (the global default
 * or one user). Extracted out of {@link OrderBookClassifier} (Phase C) so a
 * {@link UserClassificationContext} can declare its own {@code Map<String, SymbolState>}; the
 * behavior is unchanged from when it was a private inner class.
 *
 * <h2>Threading</h2>
 * A given key is pinned to exactly one Disruptor shard (hash-stable), so each {@code SymbolState}
 * instance is only ever touched by that one shard's consumer thread — no synchronization is
 * needed on the fields here. (The <em>map</em> holding these states may be a
 * {@code ConcurrentHashMap} when a context is shared across shards; that concurrency lives on the
 * map structure, not on individual {@code SymbolState} values.)
 */
public class SymbolState {

    enum ActivityLevel { LOW, HIGH }

    /**
     * Reusable top-K selection scratch. Maintained sorted best&rarr;worst by
     * (tier DESC, notional DESC, distance ASC); reset ({@code topCount = 0}) at the start of
     * each selection pass. Holds {@code Map.Entry} references (price, quantity, firstSeen and
     * distance are all read back through the entry) plus the rule-computed tier. No heap
     * allocation in steady state — the arrays are pre-allocated and overwritten in place.
     */
    static class Scratch {
        int topCount = 0;
        @SuppressWarnings("unchecked")
        Map.Entry<Double, PriceLevelEntry>[] topEntries = new Map.Entry[TOP_LEVELS];
        int[] topTiers = new int[TOP_LEVELS];
    }

    ActivityLevel level = ActivityLevel.LOW;
    ClassifiedLevel[] workBids = new ClassifiedLevel[TOP_LEVELS];
    ClassifiedLevel[] workAsks = new ClassifiedLevel[TOP_LEVELS];

    // Two independent top-K scratch buffers so both sides can be selected before either is
    // applied (needed for the LOW-skip in OrderBookClassifier).
    final Scratch bidScratch = new Scratch();
    final Scratch askScratch = new Scratch();
}
