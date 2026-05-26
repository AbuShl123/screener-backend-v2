package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.binance.orderbook.OrderBook;
import dev.abu.screener_backend.binance.orderbook.OrderBookState;
import dev.abu.screener_backend.binance.orderbook.PriceLevelEntry;
import dev.abu.screener_backend.feed.ClassifiedLevel;
import dev.abu.screener_backend.feed.FeedEventType;
import dev.abu.screener_backend.feed.OrderBookFeedStore;
import dev.abu.screener_backend.feed.OrderBookUpdate;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Classifies the top price levels of every order book managed by one Disruptor shard
 * and pushes the resulting feed events into {@link OrderBookFeedStore}.
 *
 * <h2>Lifecycle and threading</h2>
 * One {@code OrderBookClassifier} instance is created per Disruptor shard by
 * {@link dev.abu.screener_backend.binance.disruptor.DisruptorShardManager} and is never shared
 * between shards. Because every order book is pinned to exactly one shard, and each shard has
 * exactly one consumer thread, all state inside this class is accessed by a single thread.
 * No synchronization is needed anywhere in this class.
 *
 * <h2>Per-symbol activity state machine</h2>
 * Each {@code (symbol, market)} pair is tracked by a {@link SymbolState} that alternates
 * between two activity levels:
 * <ul>
 *   <li><b>LOW</b> — the order book has no tier-&ge;1 levels or is not yet synchronised.
 *       No feed entry exists for this symbol on the client side.</li>
 *   <li><b>HIGH</b> — at least one tier-&ge;1 level exists. The symbol is actively displayed.</li>
 * </ul>
 * Transitions:
 * <ul>
 *   <li>{@code LOW → HIGH}: emits an {@link FeedEventType#ADD ADD} event.</li>
 *   <li>{@code HIGH → LOW}: emits a {@link FeedEventType#DROP DROP} event.</li>
 *   <li>{@code HIGH → HIGH} (top-5 changed): emits an {@link FeedEventType#UPDATE UPDATE} event.</li>
 *   <li>{@code HIGH → HIGH} (top-5 unchanged): nothing emitted.</li>
 * </ul>
 *
 * <h2>Classification</h2>
 * Every level in the book is assigned a tier (0–4, where 4 is most important and 0 is
 * the default fall-through). The top {@value #TOP_LEVELS} levels are selected by
 * {@code (tier DESC, notional DESC, distance ASC)}. A symbol is visible only if at least
 * one of its top levels has tier &ge; 1; tier-0 levels fill remaining slots when fewer
 * than {@value #TOP_LEVELS} qualifying levels exist.
 *
 * <h2>GC-efficient working buffers</h2>
 * Each {@link SymbolState} holds pre-allocated working arrays ({@code workBids}/{@code workAsks})
 * and parallel primitive scratch arrays for the top-K selection loop. No heap allocation occurs
 * in the hot path except when a {@link ClassifiedLevel} slot value actually changes.
 * Working arrays are {@link Object#clone() cloned} before submission so the broadcaster holds
 * a stable snapshot without any locking.
 */
public class OrderBookClassifier {

    public static final int TOP_LEVELS = 5;

    /**
     * High-liquidity tickers use tighter notional/distance thresholds due to deeper books
     * and tighter spreads — standard thresholds would classify nearly everything as tier-4.
     */
    private static final Set<String> HIGH_LIQUIDITY_TICKERS = Set.of("BTCUSDT", "ETHUSDT", "SOLUSDT");

    private enum ActivityLevel { LOW, HIGH }

    private static class SymbolState {
        ActivityLevel level = ActivityLevel.LOW;
        ClassifiedLevel[] workBids = new ClassifiedLevel[TOP_LEVELS];
        ClassifiedLevel[] workAsks = new ClassifiedLevel[TOP_LEVELS];
        // Scratch space for top-K selection — reused every classify() call to avoid allocation.
        // Maintained sorted best→worst; reset (topCount=0) at the start of each call.
        int      topCount      = 0;
        double[] topPrices     = new double[TOP_LEVELS];
        double[] topQuantities = new double[TOP_LEVELS];
        long[]   topFirstSeen  = new long[TOP_LEVELS];
        int[]    topTiers      = new int[TOP_LEVELS];
        double[] topNotionals  = new double[TOP_LEVELS];
        double[] topDistances  = new double[TOP_LEVELS];
    }

    private final OrderBookFeedStore feedStore;
    private final Map<String, SymbolState> states = new HashMap<>();

    public OrderBookClassifier(OrderBookFeedStore feedStore) {
        this.feedStore = feedStore;
    }

    /** Entry point called by DepthEventHandler after every ring buffer event. */
    public void process(OrderBook ob) {
        String key = ob.getSymbol() + ":" + ob.getMarket();
        SymbolState state = states.computeIfAbsent(key, k -> new SymbolState());

        if (ob.getState() != OrderBookState.SYNCED) {
            if (state.level == ActivityLevel.HIGH) {
                submitDropUpdate(key, ob);
                state.level = ActivityLevel.LOW;
            }
            return;
        }

        TreeMap<Double, PriceLevelEntry> bids = ob.getBids();
        TreeMap<Double, PriceLevelEntry> asks = ob.getAsks();
        if (bids.isEmpty() || asks.isEmpty()) {
            if (state.level == ActivityLevel.HIGH) {
                submitDropUpdate(key, ob);
                state.level = ActivityLevel.LOW;
            }
            return;
        }

        boolean highLiquidity = HIGH_LIQUIDITY_TICKERS.contains(ob.getSymbol());

        boolean bidsChanged = classify(bids, state.workBids, state, highLiquidity);
        boolean asksChanged = classify(asks, state.workAsks, state, highLiquidity);
        boolean hasVisible  = hasVisibleLevel(state.workBids) || hasVisibleLevel(state.workAsks);

        if (hasVisible) {
            if (state.level == ActivityLevel.LOW) {
                submitAddUpdate(key, ob, state);
                state.level = ActivityLevel.HIGH;
            } else if (bidsChanged || asksChanged) {
                submitModifyUpdate(key, ob, state);
            }
        } else {
            if (state.level == ActivityLevel.HIGH) {
                submitDropUpdate(key, ob);
                state.level = ActivityLevel.LOW;
            }
        }
    }

    /**
     * Iterates all entries in {@code levels}, selects the top {@value #TOP_LEVELS} by
     * (tier DESC, notional DESC, distance ASC) into the scratch buffer, then writes them
     * into {@code working} in-place. Returns {@code true} if any slot changed.
     */
    private boolean classify(TreeMap<Double, PriceLevelEntry> levels, ClassifiedLevel[] working,
                             SymbolState state, boolean highLiquidity) {
        state.topCount = 0;
        double maxDist = highLiquidity ? 0.025 : 0.05;

        for (Map.Entry<Double, PriceLevelEntry> e : levels.entrySet()) {
            double price          = e.getKey();
            double quantity       = e.getValue().quantity;
            long   firstSeen      = e.getValue().firstSeenMillis;
            double notional       = price * quantity;
            double distance       = e.getValue().distance;

            // Distance increases monotonically as we iterate both TreeMaps (bids from best bid outward, asks from best ask outward)
            if (state.topCount == TOP_LEVELS && distance > maxDist) break;

            int tier = computeTier(notional, distance, highLiquidity);
            tryInsert(state, price, quantity, firstSeen, tier, notional, distance);
        }

        boolean changed = false;
        for (int i = 0; i < TOP_LEVELS; i++) {
            if (i < state.topCount) {
                double price     = state.topPrices[i];
                double quantity  = state.topQuantities[i];
                long   firstSeen = state.topFirstSeen[i];
                int    tier      = state.topTiers[i];
                double distance  = state.topDistances[i];

                ClassifiedLevel existing = working[i];

                if (existing == null
                        || existing.price()           != price
                        || existing.quantity()        != quantity
                        || existing.tier()            != tier
                        || existing.firstSeenMillis() != firstSeen
                        || existing.distance()        != distance
                ) {
                    working[i] = new ClassifiedLevel(price, quantity, tier, firstSeen, distance);
                    changed = true;
                }
            } else {
                if (working[i] != null) {
                    working[i] = null;
                    changed = true;
                }
            }
        }

        return changed;
    }

    /**
     * Inserts a candidate into the pre-sorted top-K scratch buffer (best→worst order).
     * When the buffer is full, the incoming level replaces the worst only if it is strictly
     * better. Elements are shifted in-place; no heap allocation occurs.
     */
    private void tryInsert(SymbolState s, double price, double qty, long firstSeen, int tier, double notional, double dist) {
        // Starting insertion position: end of populated range, or last slot if full
        int pos = s.topCount < TOP_LEVELS ? s.topCount : TOP_LEVELS - 1;

        // If full, only proceed when the new level beats the current worst (slot at pos)
        if (s.topCount == TOP_LEVELS && !isBetter(tier, notional, dist, s.topTiers[pos], s.topNotionals[pos], s.topDistances[pos])) {
            return;
        }

        // Shift elements right until the correct sorted position is found
        while (pos > 0 && isBetter(tier, notional, dist, s.topTiers[pos - 1], s.topNotionals[pos - 1], s.topDistances[pos - 1]))
        {
            s.topPrices[pos]     = s.topPrices[pos - 1];
            s.topQuantities[pos] = s.topQuantities[pos - 1];
            s.topFirstSeen[pos]  = s.topFirstSeen[pos - 1];
            s.topTiers[pos]      = s.topTiers[pos - 1];
            s.topNotionals[pos]  = s.topNotionals[pos - 1];
            s.topDistances[pos]  = s.topDistances[pos - 1];
            pos--;
        }

        s.topPrices[pos]     = price;
        s.topQuantities[pos] = qty;
        s.topFirstSeen[pos]  = firstSeen;
        s.topTiers[pos]      = tier;
        s.topNotionals[pos]  = notional;
        s.topDistances[pos]  = dist;

        if (s.topCount < TOP_LEVELS) s.topCount++;
    }

    /** Returns true if (tierA, notionalA, distA) ranks higher than (tierB, notionalB, distB). */
    private boolean isBetter(int tierA, double notionalA, double distA,
                              int tierB, double notionalB, double distB) {
        if (tierA != tierB)         return tierA     > tierB;
        if (notionalA != notionalB) return notionalA > notionalB;
        return distA < distB;
    }

    /**
     * Checks tiers 4→1 in order; returns the first (highest-numbered) tier whose both
     * notional and distance thresholds are satisfied. Returns 0 if none match.
     * Checking highest first means a $100M order at 0.1% distance resolves to tier-4
     * (not tier-1), because higher notional grants a wider distance window rather than
     * a better tier number.
     */
    private int computeTier(double notional, double distance, boolean highLiquidity) {
        if (highLiquidity) {
            if (notional >= 100_000_000 && distance <= 0.025)  return 4;
            if (notional >= 30_000_000  && distance <= 0.01)   return 3;
            if (notional >= 10_000_000  && distance <= 0.005)  return 2;
            if (notional >= 3_000_000   && distance <= 0.0025) return 1;
        } else {
            if (notional >= 10_000_000  && distance <= 0.05)   return 4;
            if (notional >= 1_000_000   && distance <= 0.02)   return 3;
            if (notional >= 500_000     && distance <= 0.01)   return 2;
            if (notional >= 300_000     && distance <= 0.005)  return 1;
        }
        return 0;
    }

    /** A symbol is visible only if at least one top level has tier ≥ 1. */
    private boolean hasVisibleLevel(ClassifiedLevel[] levels) {
        for (ClassifiedLevel level : levels) {
            if (level == null) break;
            if (level.tier() > 0) return true;
        }
        return false;
    }

    private void submitDropUpdate(String key, OrderBook ob) {
        feedStore.submit(key, new OrderBookUpdate(
                ob.getSymbol(), ob.getMarket(),
                FeedEventType.DROP,
                null, null));
    }

    private void submitAddUpdate(String key, OrderBook ob, SymbolState state) {
        feedStore.submit(key, new OrderBookUpdate(
                ob.getSymbol(), ob.getMarket(),
                FeedEventType.ADD,
                state.workBids.clone(), state.workAsks.clone()));
    }

    private void submitModifyUpdate(String key, OrderBook ob, SymbolState state) {
        feedStore.submit(key, new OrderBookUpdate(
                ob.getSymbol(), ob.getMarket(),
                FeedEventType.UPDATE,
                state.workBids.clone(), state.workAsks.clone()));
    }
}
