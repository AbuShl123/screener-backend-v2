package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.binance.orderbook.OrderBook;
import dev.abu.screener_backend.binance.orderbook.OrderBookState;
import dev.abu.screener_backend.binance.orderbook.PriceLevelEntry;
import dev.abu.screener_backend.feed.ClassifiedLevel;
import dev.abu.screener_backend.feed.FeedEventType;
import dev.abu.screener_backend.feed.OrderBookFeedStore;
import dev.abu.screener_backend.feed.OrderBookUpdate;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Classifies the top price levels of every order book managed by one Disruptor shard
 * and pushes the resulting feed events into one or more {@link OrderBookFeedStore}s.
 *
 * <h2>Lifecycle and threading</h2>
 * One {@code OrderBookClassifier} instance is created per Disruptor shard by
 * {@link dev.abu.screener_backend.binance.disruptor.DisruptorShardManager} and is never shared
 * between shards. Because every order book is pinned to exactly one shard, and each shard has
 * exactly one consumer thread, all per-shard state inside this class is accessed by a single
 * thread. The only cross-thread field is {@link #activeUserContexts}, a {@code volatile} array
 * swapped atomically by the WebSocket connect/disconnect path; it is read once per
 * {@link #process(OrderBook)} call.
 *
 * <h2>Two-pass classification (Phase C)</h2>
 * Each {@code process(ob)} runs the classification state machine once per active context:
 * <ol>
 *   <li><b>Default pass</b> — always — against {@link #defaultStates}, {@link #defaultRule},
 *       and the global {@link #feedStore}.</li>
 *   <li><b>Per-user passes</b> — only for contexts that have this {@code (symbol, market)} key
 *       configured — against that context's own state map, override leaf rule, and personal feed
 *       store.</li>
 * </ol>
 * The shared per-context work lives in {@link #classifyOne}; the default and user passes differ
 * only in the {@code (state, rule, feedStore)} triple they operate on. When no custom users are
 * connected, the user loop body never executes, so the only added cost is one {@code volatile}
 * read and an emptiness check.
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
 * {@link #classifyOne} runs in two stages per side:
 * <ol>
 *   <li>{@link #selectTopK selectTopK} walks the {@link TreeMap} from best (closest to spread)
 *       outward and fills a pre-allocated top-K {@link SymbolState.Scratch} buffer, returning
 *       whether that side is visible (best slot has tier &ge; 1). Because both maps iterate in
 *       monotonically increasing distance order, it early-breaks once the buffer is full and the
 *       level is beyond {@link ClassificationRule#maxDistance}.</li>
 *   <li>{@link #applyNewOrders applyNewOrders} writes the selected entries into the persistent
 *       {@code workBids}/{@code workAsks} arrays, allocating a {@link ClassifiedLevel} only when
 *       a slot's value actually changed.</li>
 * </ol>
 * Both sides are <b>selected</b> before either is <b>applied</b>, because a visible ask side
 * forces us to still emit the (tier-0) bids and vice-versa. When neither side is visible the
 * book is LOW and the apply stage is skipped entirely — <b>no {@link ClassifiedLevel} is
 * allocated for the LOW majority of books</b>, which is the dominant GC win.
 */
public class OrderBookClassifier {

    public static final int TOP_LEVELS = 5;

    private static final UserClassificationContext[] EMPTY = new UserClassificationContext[0];

    // objects for default classification
    private final OrderBookFeedStore feedStore;
    private final DefaultClassificationRule defaultRule;
    private final Map<String, SymbolState> defaultStates = new HashMap<>();

    /// Swapped atomically (never mutated in place) by the WebSocket connect/disconnect path via
    /// DisruptorShardManager.setActiveUserContexts(...).
    @Setter
    private volatile UserClassificationContext[] activeUserContexts = EMPTY;

    public OrderBookClassifier(OrderBookFeedStore feedStore, DefaultClassificationRule defaultRule) {
        this.feedStore   = feedStore;
        this.defaultRule = defaultRule;
    }

    /// Entry point called by DepthEventHandler after every ring buffer event.
    public void process(OrderBook ob) {
        String key = ob.getSymbol() + ":" + ob.getMarket();
        boolean highLiquidity = defaultRule.isHighLiquidity(ob.getSymbol()); // computed ONCE per book

        // TODO: parallel classification for default and per-user rules

        // Pass 1 — default, always.
        SymbolState defaultState = defaultStates.computeIfAbsent(key, k -> new SymbolState());
        classifyOne(ob, key, defaultState, defaultRule, feedStore, highLiquidity);

        // Pass 2 — per user, only if any context is active.
        UserClassificationContext[] ctxs = activeUserContexts;
        for (UserClassificationContext ctx : ctxs) {
            if (ctx.rule().configuredKeys().contains(key)) {
                ThresholdClassificationRule rule = ctx.rule().ruleFor(key);
                SymbolState state = ctx.states().computeIfAbsent(key, k -> new SymbolState());
                classifyOne(ob, key, state, rule, ctx.feedStore(), highLiquidity);
            }
        }
    }

    /**
     * Runs the full activity state machine for one classification context (default or user)
     * against the passed-in {@code state}, {@code rule}, and {@code feedStore}. Behaviorally
     * identical to the pre-Phase-C single-pass {@code process()} body.
     */
    private void classifyOne(
            OrderBook ob,
            String key,
            SymbolState state,
            ClassificationRule rule,
            OrderBookFeedStore feedStore,
            boolean highLiquidity
    ) {
        if (ob.getState() != OrderBookState.SYNCED) {
            if (state.level == SymbolState.ActivityLevel.HIGH) {
                submitDropUpdate(feedStore, key, ob);
                state.level = SymbolState.ActivityLevel.LOW;
            }
            return;
        }

        TreeMap<Double, PriceLevelEntry> bids = ob.getBids();
        TreeMap<Double, PriceLevelEntry> asks = ob.getAsks();
        if (bids.isEmpty() || asks.isEmpty()) {
            if (state.level == SymbolState.ActivityLevel.HIGH) {
                submitDropUpdate(feedStore, key, ob);
                state.level = SymbolState.ActivityLevel.LOW;
            }
            return;
        }

        // Computing best K bids and asks
        boolean bidVisible = selectTopK(bids, state.bidScratch, rule, highLiquidity);
        boolean askVisible = selectTopK(asks, state.askScratch, rule, highLiquidity);

        // No visible tiers? Then no need to update working bids/asks in the state
        if (!bidVisible && !askVisible) {
            if (state.level == SymbolState.ActivityLevel.HIGH) {
                submitDropUpdate(feedStore, key, ob);
                state.level = SymbolState.ActivityLevel.LOW;
            }
            return; // LOW book: skip. No ClassifiedLevel allocation
        }

        boolean bidsChanged = applyNewOrders(state.bidScratch, state.workBids);
        boolean asksChanged = applyNewOrders(state.askScratch, state.workAsks);

        if (state.level == SymbolState.ActivityLevel.LOW) {
            submitAddUpdate(feedStore, key, ob, state);
            state.level = SymbolState.ActivityLevel.HIGH;
        } else if (bidsChanged || asksChanged) {
            submitModifyUpdate(feedStore, key, ob, state);
        }
    }

    /**
     * Iterates all entries in {@code levels} (best→worst by distance) and selects the top
     * {@value #TOP_LEVELS} by (tier DESC, notional DESC, distance ASC) into {@code s}.
     * Returns {@code true} if the side is visible, i.e. its best selected slot has tier &ge; 1.
     */
    private boolean selectTopK(
            TreeMap<Double, PriceLevelEntry> levels,
            SymbolState.Scratch s,
            ClassificationRule rule, boolean highLiquidity
    ) {
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
    private void tryInsert(
            SymbolState.Scratch s,
            Map.Entry<Double, PriceLevelEntry> entry,
            int tier, double notional, double dist
    ) {
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

    /// Returns true if (tierA, notionalA, distA) ranks higher than (tierB, notionalB, distB).
    private boolean isBetter(
            int tierA, double notionalA, double distA,
            int tierB, double notionalB, double distB
    ) {
        if (tierA != tierB)         return tierA     > tierB;
        if (notionalA != notionalB) return notionalA > notionalB;
        return distA < distB;
    }

    /**
     * Writes the selected scratch entries into {@code working} in-place, allocating a new
     * {@link ClassifiedLevel} only when a slot's value actually changed. Slots beyond
     * {@code s.topCount} are nulled. Returns {@code true} if any slot changed.
     */
    private boolean applyNewOrders(SymbolState.Scratch s, ClassifiedLevel[] working) {
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
                if (
                        existing == null
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

    private void submitDropUpdate(OrderBookFeedStore feedStore, String key, OrderBook ob) {
        feedStore.submit(key, new OrderBookUpdate(
                ob.getSymbol(), ob.getMarket(),
                FeedEventType.DROP,
                null, null));
    }

    private void submitAddUpdate(OrderBookFeedStore feedStore, String key, OrderBook ob, SymbolState state) {
        feedStore.submit(key, new OrderBookUpdate(
                ob.getSymbol(), ob.getMarket(),
                FeedEventType.ADD,
                state.workBids.clone(), state.workAsks.clone()));
    }

    private void submitModifyUpdate(OrderBookFeedStore feedStore, String key, OrderBook ob, SymbolState state) {
        feedStore.submit(key, new OrderBookUpdate(
                ob.getSymbol(), ob.getMarket(),
                FeedEventType.UPDATE,
                state.workBids.clone(), state.workAsks.clone()));
    }
}
