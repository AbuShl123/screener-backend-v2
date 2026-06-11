package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.feed.OrderBookFeedStore;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-user classification "session" bundle (Phase C). One instance exists per connected user
 * who has at least one custom rule; it is shared by all of that user's concurrent sessions and
 * discarded when the user's last session closes (see {@link UserFeedRegistry}). Users with no
 * rules get no context and consume the global feed directly — zero extra hot-path overhead.
 *
 * <h2>Threading</h2>
 * One context is shared across every Disruptor shard, because a user's configured symbols hash
 * across shards. {@link #states} is therefore a {@link ConcurrentHashMap} — multiple shard threads
 * insert into it (one per configured symbol). Each {@link SymbolState} <em>value</em> is still
 * single-threaded, since its key is pinned to a single shard. {@link #feedStore} is a plain
 * {@code new} instance of the dependency-free {@link OrderBookFeedStore}, reusing its existing
 * multi-writer / single-drainer coalescing.
 */
public final class UserClassificationContext {

    private final UUID userId;
    private final UserClassificationRules rule;
    private final OrderBookFeedStore feedStore;
    private final ConcurrentHashMap<String, SymbolState> states;

    public UserClassificationContext(UUID userId,
                                     UserClassificationRules rule,
                                     OrderBookFeedStore feedStore,
                                     ConcurrentHashMap<String, SymbolState> states) {
        this.userId = userId;
        this.rule = rule;
        this.feedStore = feedStore;
        this.states = states;
    }

    public UUID userId() {
        return userId;
    }

    public UserClassificationRules rule() {
        return rule;
    }

    public OrderBookFeedStore feedStore() {
        return feedStore;
    }

    /** Package-private — only the classifier (same package) touches the per-symbol state map. */
    ConcurrentHashMap<String, SymbolState> states() {
        return states;
    }
}
