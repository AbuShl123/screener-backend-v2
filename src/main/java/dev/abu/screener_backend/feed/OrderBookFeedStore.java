package dev.abu.screener_backend.feed;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared data structure between the classifier (consumer threads) and the broadcaster (sender thread).
 * All access goes through the three public methods — internal maps are never exposed directly.
 */
@Component
public class OrderBookFeedStore {

    private final ConcurrentHashMap<String, OrderBookUpdate> snapshotMap = new ConcurrentHashMap<>();
    private final AtomicReference<ConcurrentHashMap<String, OrderBookUpdate>> pendingRef
            = new AtomicReference<>(new ConcurrentHashMap<>());

    /**
     * Called by the classifier (consumer thread) after every classification cycle.
     * Coalesces multiple writes within the same 100ms window to a single net result per ticker,
     * then syncs snapshotMap.
     */
    public void submit(String key, OrderBookUpdate update) {
        pendingRef.get().merge(key, update, this::coalesce);
        if (update.type() == FeedEventType.DROP) {
            snapshotMap.remove(key);
        } else {
            snapshotMap.put(key, update);
        }
    }

    /**
     * Called by the broadcaster (sender thread) when a new client connects.
     * Returns an unmodifiable view of the current active snapshot.
     */
    public Map<String, OrderBookUpdate> getSnapshot() {
        return Collections.unmodifiableMap(snapshotMap);
    }

    /**
     * Called by the broadcaster (sender thread) in the 100ms drain loop.
     * Atomically swaps pendingRef with a fresh empty map and returns the old map.
     */
    public Map<String, OrderBookUpdate> drainPending() {
        return pendingRef.getAndSet(new ConcurrentHashMap<>());
    }

    /**
     * Coalescing table — determines the net effect of two events for the same ticker
     * within one 100ms window. Returning {@code null} causes {@link java.util.concurrent.ConcurrentHashMap#merge} to remove the key.
     *
     * <table border="1">
     *   <caption>Event coalescing rules</caption>
     *   <tr><th>Existing</th><th>Incoming</th><th>Result</th></tr>
     *   <tr><td>ADD</td>    <td>UPDATE</td>  <td>UPDATE — client treats UPDATE as ADD for unknown symbols</td></tr>
     *   <tr><td>ADD</td>    <td>DROP</td>    <td>remove key ({@code null})</td></tr>
     *   <tr><td>UPDATE</td> <td>UPDATE</td>  <td>UPDATE (new data)</td></tr>
     *   <tr><td>UPDATE</td> <td>DROP</td>    <td>DROP</td></tr>
     *   <tr><td>DROP</td>   <td>ADD</td>     <td>ADD</td></tr>
     *   <tr><td>DROP</td>   <td>DROP</td>    <td>DROP</td></tr>
     * </table>
     */
    private OrderBookUpdate coalesce(OrderBookUpdate existing, OrderBookUpdate incoming) {
        return switch (existing.type()) {
            case ADD -> switch (incoming.type()) {
                case ADD, UPDATE -> incoming;
                case DROP -> null; // ADD + DROP = never happened
            };
            case UPDATE -> switch (incoming.type()) {
                case ADD, UPDATE -> incoming;
                case DROP -> incoming; // UPDATE + DROP = DROP
            };
            case DROP -> incoming;
        };
    }
}
