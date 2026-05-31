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
 * <h2>Select / apply split and the LOW-skip</h2>
 * {@link #process(OrderBook)} runs in two stages per side:
 * <ol>
 *   <li>{@link #selectTopK selectTopK} walks the {@link TreeMap} from best (closest to spread)
 *       outward and fills a pre-allocated top-K {@link Scratch} buffer, returning whether that
 *       side is visible (best slot has tier &ge; 1). Because both maps iterate in monotonically
 *       increasing distance order, it early-breaks once the buffer is full and the level is
 *       beyond {@link ClassificationRule#maxDistance}.</li>
 *   <li>{@link #applyNewOrders applyNewOrders} writes the selected entries into the persistent
 *       {@code workBids}/{@code workAsks} arrays, allocating a {@link ClassifiedLevel} only when
 *       a slot's value actually changed.</li>
 * </ol>
 * Both sides are <b>selected</b> before either is <b>applied</b>, because a visible ask side
 * forces us to still emit the (tier-0) bids and vice-versa. When neither side is visible the
 * book is LOW and the apply stage is skipped entirely — <b>no {@link ClassifiedLevel} is
 * allocated for the LOW majority of books</b>, which is the dominant GC win. Stale work-array
 * contents on a LOW cycle are never read: LOW cycles emit nothing, and the next {@code LOW → HIGH}
 * transition fully reconstructs the arrays via {@code applyNewOrders} before the ADD is submitted.
 *
 * <h2>GC-efficient working buffers</h2>
 * Each {@link SymbolState} holds two pre-allocated working arrays ({@code workBids}/{@code workAsks})
 * and two reusable {@link Scratch} buffers (one per side), each carrying a {@code Map.Entry}
 * reference array plus a parallel {@code int[]} of tiers. Holding {@code TreeMap} entry references
 * across the select→apply stage is safe: all work happens synchronously on the single shard
 * consumer thread within one {@code process()} call, so no diff mutates the entries underneath.
 * No heap allocation occurs in the hot path except when a {@link ClassifiedLevel} slot value
 * actually changes. Working arrays are {@link Object#clone() cloned} before submission so the
 * broadcaster holds a stable snapshot without any locking.
 */
public class OrderBookClassifier {

    public static final int TOP_LEVELS = 5;

    private enum ActivityLevel { LOW, HIGH }

    /**
     * Reusable top-K selection scratch. Maintained sorted best→worst by
     * (tier DESC, notional DESC, distance ASC); reset ({@code topCount = 0}) at the start of
     * each {@link #selectTopK} call. Holds {@code Map.Entry} references (price, quantity,
     * firstSeen and distance are all read back through the entry) plus the rule-computed tier.
     * No heap allocation in steady state — the arrays are pre-allocated and overwritten in place.
     */
    private static class Scratch {
        int topCount = 0;
        @SuppressWarnings("unchecked")
        Map.Entry<Double, PriceLevelEntry>[] topEntries = new Map.Entry[TOP_LEVELS];
        int[] topTiers = new int[TOP_LEVELS];
    }

    private static class SymbolState {
        ActivityLevel level = ActivityLevel.LOW;
        ClassifiedLevel[] workBids = new ClassifiedLevel[TOP_LEVELS];
        ClassifiedLevel[] workAsks = new ClassifiedLevel[TOP_LEVELS];

        // Two independent top-K scratch buffers so both sides can be selected before either is
        // applied (needed for the LOW-skip in process()).
        final Scratch bidScratch = new Scratch();
        final Scratch askScratch = new Scratch();
    }

    private final OrderBookFeedStore feedStore;
    private final DefaultClassificationRule defaultRule;
    private final Map<String, SymbolState> states = new HashMap<>();

    public OrderBookClassifier(OrderBookFeedStore feedStore, DefaultClassificationRule defaultRule) {
        this.feedStore   = feedStore;
        this.defaultRule = defaultRule;
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

        boolean highLiquidity = defaultRule.isHighLiquidity(ob.getSymbol());

        // Select both sides first — visibility is a whole-book decision (a visible ask side
        // still requires emitting the tier-0 bids, and vice-versa).
        boolean bidVisible = selectTopK(bids, state.bidScratch, defaultRule, highLiquidity);
        boolean askVisible = selectTopK(asks, state.askScratch, defaultRule, highLiquidity);

        if (!bidVisible && !askVisible) {
            if (state.level == ActivityLevel.HIGH) {
                submitDropUpdate(key, ob);
                state.level = ActivityLevel.LOW;
            }
            return; // LOW book: skip the apply loop — no ClassifiedLevel allocation
        }

        boolean bidsChanged = applyNewOrders(state.bidScratch, state.workBids);
        boolean asksChanged = applyNewOrders(state.askScratch, state.workAsks);

        if (state.level == ActivityLevel.LOW) {
            submitAddUpdate(key, ob, state);
            state.level = ActivityLevel.HIGH;
        } else if (bidsChanged || asksChanged) {
            submitModifyUpdate(key, ob, state);
        }
    }

    /**
     * Iterates all entries in {@code levels} (best→worst by distance) and selects the top
     * {@value #TOP_LEVELS} by (tier DESC, notional DESC, distance ASC) into {@code s}.
     * Returns {@code true} if the side is visible, i.e. its best selected slot has tier &ge; 1.
     */
    private boolean selectTopK(TreeMap<Double, PriceLevelEntry> levels, Scratch s, ClassificationRule rule, boolean highLiquidity) {
        s.topCount = 0;
        double maxDist = rule.maxDistance(highLiquidity);

        for (Map.Entry<Double, PriceLevelEntry> e : levels.entrySet()) {
            double distance = e.getValue().distance;
            // Distance increases monotonically as we iterate both TreeMaps (bids from best bid
            // outward, asks from best ask outward). Once the buffer is full, nothing further can
            // place; everything beyond maxDist is tier-0.
            if (s.topCount == TOP_LEVELS && distance > maxDist) break;

            double notional = e.getKey() * e.getValue().quantity;
            int tier = rule.computeTier(notional, distance, highLiquidity);
            tryInsert(s, e, tier, notional, distance);
        }

        return s.topCount > 0 && s.topTiers[0] >= 1; // visible iff best slot is tier ≥ 1
    }

    /**
     * Inserts a candidate into the pre-sorted top-K scratch buffer (best→worst order).
     * When the buffer is full, the incoming level replaces the worst only if it is strictly
     * better. Elements are shifted in-place; no heap allocation occurs. The incumbent's
     * notional/distance for the comparison are read back from the entry already in each slot.
     */
    private void tryInsert(Scratch s, Map.Entry<Double, PriceLevelEntry> entry, int tier, double notional, double dist) {
        // Starting insertion position: end of populated range, or last slot if full
        int pos = s.topCount < TOP_LEVELS ? s.topCount : TOP_LEVELS - 1;

        // If full, only proceed when the new level beats the current worst (slot at pos)
        if (
                s.topCount == TOP_LEVELS
                && !isBetter(tier, notional, dist, s.topTiers[pos], notionalOf(s.topEntries[pos]), distOf(s.topEntries[pos]))
        ) {
            return;
        }

        // Shift elements right until the correct sorted position is found
        while (
                pos > 0
                && isBetter(tier, notional, dist, s.topTiers[pos - 1], notionalOf(s.topEntries[pos - 1]), distOf(s.topEntries[pos - 1]))
        ) {
            s.topEntries[pos] = s.topEntries[pos - 1];
            s.topTiers[pos]   = s.topTiers[pos - 1];
            pos--;
        }

        s.topEntries[pos] = entry;
        s.topTiers[pos]   = tier;

        if (s.topCount < TOP_LEVELS) s.topCount++;
    }

    private static double notionalOf(Map.Entry<Double, PriceLevelEntry> e) {
        return e.getKey() * e.getValue().quantity;
    }

    private static double distOf(Map.Entry<Double, PriceLevelEntry> e) {
        return e.getValue().distance;
    }

    /** Returns true if (tierA, notionalA, distA) ranks higher than (tierB, notionalB, distB). */
    private boolean isBetter(int tierA, double notionalA, double distA,
                              int tierB, double notionalB, double distB) {
        if (tierA != tierB)         return tierA     > tierB;
        if (notionalA != notionalB) return notionalA > notionalB;
        return distA < distB;
    }

    /**
     * Writes the selected scratch entries into {@code working} in-place, allocating a new
     * {@link ClassifiedLevel} only when a slot's value actually changed. Slots beyond
     * {@code s.topCount} are nulled. Returns {@code true} if any slot changed.
     */
    private boolean applyNewOrders(Scratch s, ClassifiedLevel[] working) {
        boolean changed = false;
        for (int i = 0; i < TOP_LEVELS; i++) {
            if (i < s.topCount) {
                Map.Entry<Double, PriceLevelEntry> e = s.topEntries[i];
                double price     = e.getKey();
                double quantity  = e.getValue().quantity;
                long   firstSeen = e.getValue().firstSeenMillis;
                double distance  = e.getValue().distance;
                int    tier      = s.topTiers[i];

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
            } else if (working[i] != null) {
                working[i] = null;
                changed = true;
            }
        }
        return changed;
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
