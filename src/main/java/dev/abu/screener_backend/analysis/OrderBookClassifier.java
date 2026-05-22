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
 *   <li><b>LOW</b> — the order book either has no classifiable levels or is not yet synchronised.
 *       No feed entry exists for this symbol on the client side.</li>
 *   <li><b>HIGH</b> — at least one visible price level exists. The symbol is actively displayed
 *       on the client side.</li>
 * </ul>
 * Transitions:
 * <ul>
 *   <li>{@code LOW → HIGH}: emits an {@link FeedEventType#ADD ADD} event with the current top levels.</li>
 *   <li>{@code HIGH → LOW}: emits a {@link FeedEventType#DROP DROP} event; the client removes the symbol.</li>
 *   <li>{@code HIGH → HIGH} (levels changed): emits an {@link FeedEventType#UPDATE UPDATE} event.</li>
 *   <li>{@code HIGH → HIGH} (levels unchanged): nothing is emitted — no redundant network traffic.</li>
 * </ul>
 * A book that is not in state {@link dev.abu.screener_backend.binance.orderbook.OrderBookState#SYNCED SYNCED}
 * is treated as having no classifiable levels. If the book was HIGH, a DROP is emitted immediately
 * and the state returns to LOW. Once the book re-synchronises, the next classification cycle
 * emits ADD unconditionally (because the activity level is LOW at that point), so the client
 * always sees the correct state even if the book re-syncs with exactly the same top levels as before.
 *
 * <h2>Classification</h2>
 * {@link #classify(TreeMap, ClassifiedLevel[])} assigns each of the top {@value #TOP_LEVELS}
 * price levels a tier (1 = highest importance, 4 = lowest displayed). A level is <em>visible</em>
 * if its tier is &le; 4. Only the top {@value #TOP_LEVELS} levels per side are ever evaluated;
 * deeper levels are ignored.
 *
 * <h2>GC-efficient working buffers</h2>
 * Each {@link SymbolState} holds two pre-allocated {@code ClassifiedLevel[}{@value #TOP_LEVELS}{@code ]}
 * arrays ({@code workBids} and {@code workAsks}) that are reused across every classification cycle.
 * {@link #classify(TreeMap, ClassifiedLevel[])} fills these arrays in-place and allocates a new
 * {@link ClassifiedLevel} instance only when a slot's value (price, quantity, tier, or age) actually
 * changed. Trailing slots beyond the real level count are nulled.
 * <p>
 * When a feed event must be submitted, the working array is {@link Object#clone() cloned} before
 * being passed to {@link OrderBookFeedStore}. This gives the broadcaster a stable snapshot that
 * will not be mutated by the next classification cycle, without requiring any locking.
 */
public class OrderBookClassifier {

    public static final int TOP_LEVELS = 5;

    private enum ActivityLevel { LOW, HIGH }

    private static class SymbolState {
        ActivityLevel level = ActivityLevel.LOW;
        // Pre-allocated working buffers reused every cycle. classify() fills these in-place;
        // arrays are .clone()'d before submission so the broadcaster always holds a stable snapshot.
        ClassifiedLevel[] workBids = new ClassifiedLevel[TOP_LEVELS];
        ClassifiedLevel[] workAsks = new ClassifiedLevel[TOP_LEVELS];
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

        boolean bidsChanged = classify(ob.getBids(), state.workBids);
        boolean asksChanged = classify(ob.getAsks(), state.workAsks);
        boolean hasVisible = hasVisibleLevel(state.workBids) || hasVisibleLevel(state.workAsks);

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
     * Fills {@code working} in-place from the top-{@code TOP_LEVELS} entries of {@code levels}.
     * A new {@link ClassifiedLevel} is allocated only when the slot value actually changes.
     * Trailing slots beyond the actual level count are nulled.
     * Returns {@code true} if any slot changed.
     * <p>
     * STUB: all levels are assigned tier-4. Real proximity/notional thresholds to be implemented in Phase 4.
     */
    private boolean classify(TreeMap<Double, PriceLevelEntry> levels, ClassifiedLevel[] working) {
        boolean changed = false;
        int i = 0;
        for (Map.Entry<Double, PriceLevelEntry> e : levels.entrySet()) {
            if (i == TOP_LEVELS) break;
            double price = e.getKey();
            double quantity = e.getValue().quantity;
            long firstSeenMillis = e.getValue().firstSeenMillis;
            int tier = 4; // stub — replace with computed tier in Phase 4
            ClassifiedLevel existing = working[i];
            if (existing == null
                    || existing.price() != price
                    || existing.quantity() != quantity
                    || existing.tier() != tier
                    || existing.firstSeenMillis() != firstSeenMillis) {
                working[i] = new ClassifiedLevel(price, quantity, tier, firstSeenMillis);
                changed = true;
            }
            i++;
        }
        while (i < TOP_LEVELS) {
            if (working[i] != null) {
                working[i] = null;
                changed = true;
            }
            i++;
        }
        return changed;
    }

    private boolean hasVisibleLevel(ClassifiedLevel[] levels) {
        for (ClassifiedLevel level : levels) {
            if (level == null) break;
            if (level.tier() <= 4) return true;
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
