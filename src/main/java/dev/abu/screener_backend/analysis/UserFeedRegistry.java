package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.analysis.rule.ClassificationRuleService;
import dev.abu.screener_backend.binance.disruptor.DisruptorShardManager;
import dev.abu.screener_backend.feed.OrderBookFeedStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Source of truth for the set of active {@link UserClassificationContext}s across all shards
 * (Phase C). Owns the per-user context lifecycle, <b>refcounted by {@code userId}</b>: a user
 * may open several sessions (tabs), all sharing one context; the context is built once and
 * discarded only when the user's last session closes.
 *
 * <h2>Threading</h2>
 * All mutation happens inside a single {@code synchronized} block. Connect/disconnect run on
 * Tomcat threads and are rare, so lock cost is irrelevant. The published {@link #active} array is
 * {@code volatile} and never mutated in place — it is rebuilt and swapped atomically, so the
 * shard classifiers and the broadcaster read it without locking.
 */
@Component
@RequiredArgsConstructor
public class UserFeedRegistry {

    private static final UserClassificationContext[] EMPTY = new UserClassificationContext[0];

    private final ClassificationRuleService ruleService;
    private final DisruptorShardManager shardManager;

    private final Map<UUID, UserClassificationContext> contexts = new HashMap<>();
    private final Map<UUID, Integer> refCounts = new HashMap<>();

    // Snapshot read by the broadcaster each tick and pushed to every shard classifier.
    private volatile UserClassificationContext[] active = EMPTY;

    /**
     * Registers a session for {@code userId}, returning the user's context or {@code null} if the
     * user has no custom rules (a default-only session, no context). A second session for an
     * already-connected user <b>reuses</b> the loaded context — no DB reload, no rebuild.
     */
    public synchronized UserClassificationContext onUserConnect(UUID userId) {
        UserClassificationContext existing = contexts.get(userId);
        if (existing != null) {
            refCounts.merge(userId, 1, Integer::sum); // REUSE
            return existing;
        }

        Optional<UserClassificationRules> rules = ruleService.buildRuntimeRule(userId);
        if (rules.isEmpty()) {
            return null; // no rules → default-only session, no context
        }

        UserClassificationContext ctx = new UserClassificationContext(userId, rules.get(), new OrderBookFeedStore(), new ConcurrentHashMap<>());
        contexts.put(userId, ctx);
        refCounts.put(userId, 1);
        rebuildActiveArray();
        shardManager.setActiveUserContexts(active);
        return ctx;
    }

    /**
     * Deregisters one session for {@code userId}. The context is discarded (dereferenced → GC)
     * only when the user's last session closes. Must be called <b>exactly once per session</b>
     * (the WebSocket endpoint guards this against the @OnClose/@OnError double-fire). A call for a
     * user with no live context (e.g. a default-only session) is a no-op.
     */
    public synchronized void onUserDisconnect(UUID userId) {
        Integer count = refCounts.get(userId);
        if (count == null) {
            return; // default-only session or already cleaned up
        }
        if (count <= 1) {
            contexts.remove(userId);
            refCounts.remove(userId);
            rebuildActiveArray();
            shardManager.setActiveUserContexts(active);
        } else {
            refCounts.put(userId, count - 1);
        }
    }

    /** The current active-context snapshot, read by the broadcaster each drain tick. */
    public UserClassificationContext[] activeContexts() {
        return active;
    }

    private void rebuildActiveArray() {
        active = contexts.values().toArray(new UserClassificationContext[0]);
    }
}
