package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.analysis.rule.ClassificationRuleService;
import dev.abu.screener_backend.analysis.rule.RuleUpdatedEvent;
import dev.abu.screener_backend.binance.disruptor.DisruptorShardManager;
import dev.abu.screener_backend.feed.OrderBookFeedStore;
import dev.abu.screener_backend.ws.UserWebSocketSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Source of truth for the set of active {@link UserClassificationContext}s across all shards
 * (Phase C) and for the set of connected sessions per user. Owns the per-user context lifecycle:
 * a user may open several sessions (tabs), all sharing one context; the context is built once and
 * discarded only when the user's last session closes. {@link #sessionsByUser} is the single
 * source of truth for "connected" — the old refcount is simply the session list's size.
 *
 * <p>Live rule propagation: {@link #onRuleUpdated} listens for {@link RuleUpdatedEvent} (fired
 * after a rule write commits) and rebuilds the affected user's context in place, retargeting all
 * of that user's connected sessions and queueing a fresh snapshot for each.
 *
 * <h2>Threading</h2>
 * All mutation happens inside a single {@code synchronized} block. Connect/disconnect/rule-update
 * run on Tomcat threads and are rare, so lock cost is irrelevant. The published {@link #active}
 * array is {@code volatile} and never mutated in place — it is rebuilt and swapped atomically, so
 * the shard classifiers and the broadcaster read it without locking.
 *
 * <p><b>Write ordering invariant:</b> whenever a session is retargeted, {@code setContext} (write
 * A) must precede {@code setStatus(NEED_SNAPSHOT)} (write B). The broadcaster's volatile read of
 * the status establishes a happens-before edge back to write A, so a consumed {@code
 * NEED_SNAPSHOT} is always served from the new context.
 */
@Component
@RequiredArgsConstructor
public class UserFeedRegistry {

    private static final UserClassificationContext[] EMPTY = new UserClassificationContext[0];

    private final ClassificationRuleService ruleService;
    private final DisruptorShardManager shardManager;

    private final Map<UUID, UserClassificationContext> contexts = new HashMap<>();
    private final Map<UUID, List<UserWebSocketSession>> sessionsByUser = new HashMap<>();

    // Snapshot read by the broadcaster each tick and pushed to every shard classifier.
    private volatile UserClassificationContext[] active = EMPTY;

    /**
     * Registers {@code session} for {@code userId} and sets the user's context on it — {@code
     * null} stays when the user has no custom rules (a default-only session). Registration and
     * context assignment happen under one lock acquisition so a concurrent {@link #onRuleUpdated}
     * either sees the session in the list (and retargets it) or runs before it (and the connect
     * picks up the already-rebuilt context) — the session can never be left holding a context
     * that is no longer in {@link #active}.
     */
    public synchronized void onUserConnect(UUID userId, UserWebSocketSession session) {
        sessionsByUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(session);

        UserClassificationContext existing = contexts.get(userId);
        if (existing != null) {
            session.setContext(existing); // REUSE — no DB reload, no re-push
            return;
        }

        Optional<UserClassificationRules> rules = ruleService.buildRuntimeRule(userId);
        if (rules.isEmpty()) {
            return; // no rules → default-only session, context stays null
        }

        UserClassificationContext ctx = new UserClassificationContext(
                userId, rules.get(), new OrderBookFeedStore(), new ConcurrentHashMap<>());
        contexts.put(userId, ctx);
        session.setContext(ctx);
        rebuildActiveArray();
        shardManager.setActiveUserContexts(active);
    }

    /**
     * Deregisters {@code session}; the context is discarded (dereferenced → GC) when the user's
     * last session closes. Idempotent — Tomcat may fire both @OnClose and @OnError for one
     * connection; the second call finds the session already removed and bails out.
     */
    public synchronized void onUserDisconnect(UUID userId, UserWebSocketSession session) {
        List<UserWebSocketSession> list = sessionsByUser.get(userId);
        if (list == null || !list.remove(session)) {
            return; // unknown session, or @OnClose/@OnError double-fire — idempotent no-op
        }
        if (!list.isEmpty()) {
            return; // user still has other sessions — context survives
        }
        sessionsByUser.remove(userId);
        if (contexts.remove(userId) != null) {
            rebuildActiveArray();
            shardManager.setActiveUserContexts(active);
        }
    }

    /**
     * Live rule propagation. Fires on the HTTP request thread after the rule write's transaction
     * commits, so {@code buildRuntimeRule} sees the committed data. Rebuilds the user's context
     * from scratch (fresh feed store, empty symbol-state map), retargets every connected session,
     * and queues a fresh snapshot for each — see the ".claude/plans/classification-rule-live-update.md" Cases A/B/C.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRuleUpdated(RuleUpdatedEvent event) {
        UUID userId = event.userId();

        // DB read outside the lock — do not hold synchronized (which also serialises WebSocket
        // connect/disconnect) across I/O. State is re-read inside the lock before mutating.
        Optional<UserClassificationRules> newRules = ruleService.buildRuntimeRule(userId);

        synchronized (this) {
            List<UserWebSocketSession> sessions = sessionsByUser.getOrDefault(userId, List.of());
            UserClassificationContext existing = contexts.get(userId);

            if (newRules.isEmpty()) {
                if (existing == null) {
                    return; // no rules and no context — nothing to do
                }
                // Case C: deleted all rules while connected — sessions revert to the global feed.
                contexts.remove(userId);
                for (UserWebSocketSession s : sessions) {
                    s.setContext(null);                                     // volatile write A
                    s.setStatus(UserWebSocketSession.Status.NEED_SNAPSHOT); // volatile write B
                }
            } else {
                if (existing == null && sessions.isEmpty()) {
                    return; // Case B with no sessions — rules load at next connect
                }
                // Case A (existing != null) or Case B (existing == null, sessions present):
                // build a fresh context and swap it in.
                UserClassificationContext ctx = new UserClassificationContext(
                        userId, newRules.get(), new OrderBookFeedStore(), new ConcurrentHashMap<>());
                contexts.put(userId, ctx);
                for (UserWebSocketSession s : sessions) {
                    s.setContext(ctx);                                      // volatile write A
                    s.setStatus(UserWebSocketSession.Status.NEED_SNAPSHOT); // volatile write B
                }
            }
            rebuildActiveArray();
            shardManager.setActiveUserContexts(active);
        }
    }

    /** The current active-context snapshot, read by the broadcaster each drain tick. */
    public UserClassificationContext[] activeContexts() {
        return active;
    }

    /**
     * A point-in-time snapshot of who is currently connected. Captured under the same lock that
     * guards connect/disconnect, so the result is internally consistent (no half-applied
     * connect/disconnect). {@code custom} is {@code true} when the user has an active
     * classification context (custom rules), {@code false} for a default-only session.
     *
     * @return one entry per connected user; never {@code null}
     */
    public synchronized List<UserPresence> presenceSnapshot() {
        List<UserPresence> result = new ArrayList<>(sessionsByUser.size());
        for (Map.Entry<UUID, List<UserWebSocketSession>> e : sessionsByUser.entrySet()) {
            result.add(new UserPresence(e.getKey(), e.getValue().size(), contexts.containsKey(e.getKey())));
        }
        return result;
    }

    /**
     * One connected user's live presence.
     *
     * @param userId   the user's id
     * @param sessions number of open WebSocket sessions (browser tabs/clients) for this user
     * @param custom   whether this user has an active custom-rule classification context
     */
    public record UserPresence(UUID userId, int sessions, boolean custom) {}

    private void rebuildActiveArray() {
        active = contexts.values().toArray(new UserClassificationContext[0]);
    }
}
