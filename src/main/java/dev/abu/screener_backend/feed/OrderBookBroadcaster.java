package dev.abu.screener_backend.feed;

import dev.abu.screener_backend.analysis.UserClassificationContext;
import dev.abu.screener_backend.analysis.UserFeedRegistry;
import dev.abu.screener_backend.ws.UserWebSocketSession;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs the 100ms drain loop and handles new-client snapshot delivery.
 * All broadcaster logic runs on a single @Scheduled thread — no concurrency inside this class.
 *
 * <h2>Per-user merge (Phase C)</h2>
 * A session may carry a {@link UserClassificationContext} (custom-rules user) or none
 * (default-rules user). Each tick:
 * <ul>
 *   <li>the global feed is drained once and its update bodies are built <b>keyed</b> by
 *       {@code "SYMBOL:MARKET"} so they can be filtered per custom session;</li>
 *   <li>each active context's personal feed is drained <b>once per context</b> (a context may
 *       back several sessions);</li>
 *   <li>a default session receives the global bodies (today's path); a custom session receives
 *       its personal bodies plus the global bodies for keys it has <b>not</b> configured.</li>
 * </ul>
 * This guarantees exactly one authoritative update per {@code (symbol, market)} per session per
 * tick: custom-tier data for configured keys, default-tier data for everything else.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBookBroadcaster {

    private final OrderBookFeedStore globalFeed;
    private final UserFeedRegistry userFeedRegistry;

    // CopyOnWriteArrayList so connect/disconnect (from another thread) needs no extra locking
    private final List<UserWebSocketSession> sessions = new CopyOnWriteArrayList<>();

    // Reused across every drain() call — safe because drain() runs on a single @Scheduled thread.
    // Each build method resets it at entry and captures the result via toString() before returning,
    // so injectSeq() can reuse the same buffer immediately after without risk of corruption.
    private final StringBuilder sb = new StringBuilder(4096);

    @Scheduled(fixedDelay = 100)
    public void drain() {
        if (sessions.isEmpty()) return;

        // Drain eagerly even if all sessions are NEED_SNAPSHOT: skipping would let DROP events
        // accumulate across cycles and reach READY sessions that already hold a current snapshot.
        Map<String, OrderBookUpdate> globalPending = globalFeed.drainPending();

        // Drain each active context's personal feed ONCE per tick (a context may back multiple
        // sessions — draining per session would lose data for the others).
        UserClassificationContext[] contexts = userFeedRegistry.activeContexts();
        Map<UserClassificationContext, Map<String, OrderBookUpdate>> ctxPending = null;
        if (contexts.length > 0) {
            ctxPending = new IdentityHashMap<>();
            for (UserClassificationContext ctx : contexts) {
                ctxPending.put(ctx, ctx.feedStore().drainPending());
            }
        }

        // Built lazily on the first session that needs them.
        Map<String, String> globalBodies = null;                                  // key -> JSON body
        Map<UserClassificationContext, Map<String, String>> ctxBodies = null;     // ctx -> (key -> body)

        for (UserWebSocketSession session : sessions) {
            if (!session.isRunning()) continue; // shutting down — @OnClose will remove it

            UserClassificationContext ctx = session.getContext(); // nullable

            if (session.getStatus() == UserWebSocketSession.Status.NEED_SNAPSHOT) {
                String body = (ctx == null)
                        ? buildSnapshotBody(globalFeed.getSnapshot())
                        : buildSnapshotBody(mergedSnapshot(ctx));
                session.resetSeq(); // broadcaster thread resets seq — keeps seqNumber single-threaded
                String seqMsg = injectSeq(body, session.getAndIncrementSeq());
                if (!session.enqueueBatch(List.of(seqMsg))) {
                    session.disconnect();
                } else {
                    session.setStatus(UserWebSocketSession.Status.READY);
                    // Do NOT also send pending updates — they're already reflected in the snapshot
                }
                continue;
            }

            // READY
            if (ctx == null) {
                if (globalPending.isEmpty()) continue;
                if (globalBodies == null) globalBodies = buildKeyedBodies(globalPending);
                List<String> batch = new ArrayList<>(globalBodies.size());
                for (String body : globalBodies.values()) {
                    batch.add(injectSeq(body, session.getAndIncrementSeq()));
                }
                if (!session.enqueueBatch(batch)) session.disconnect();
            } else {
                if (globalBodies == null && !globalPending.isEmpty()) {
                    globalBodies = buildKeyedBodies(globalPending);
                }
                if (ctxBodies == null) ctxBodies = new IdentityHashMap<>();
                Map<String, String> personalBodies = ctxBodies.get(ctx);
                if (personalBodies == null) {
                    // A user connecting mid-drain may appear in the sessions snapshot for a context
                    // that wasn't in activeContexts() when ctxPending was built — its personal feed
                    // hasn't been drained this tick. Treat as empty; it drains next tick (cold-start
                    // gap, an accepted Phase C limitation).
                    Map<String, OrderBookUpdate> ctxPend = (ctxPending == null) ? null : ctxPending.get(ctx);
                    personalBodies = (ctxPend == null) ? Map.of() : buildKeyedBodies(ctxPend);
                    ctxBodies.put(ctx, personalBodies);
                }

                Set<String> configured = ctx.rule().configuredKeys();
                List<String> batch = new ArrayList<>();
                // Personal feed — the user's configured keys with their custom tiers.
                for (String body : personalBodies.values()) {
                    batch.add(injectSeq(body, session.getAndIncrementSeq()));
                }
                // Global feed — but only keys this user has NOT configured.
                if (globalBodies != null) {
                    for (Map.Entry<String, String> e : globalBodies.entrySet()) {
                        if (!configured.contains(e.getKey())) {
                            batch.add(injectSeq(e.getValue(), session.getAndIncrementSeq()));
                        }
                    }
                }
                if (!batch.isEmpty() && !session.enqueueBatch(batch)) session.disconnect();
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        for (UserWebSocketSession session : sessions) {
            session.disconnect();
        }
    }

    /** Called from the WebSocket server when a client connects. */
    public void addSession(UserWebSocketSession session) {
        sessions.add(session);
    }

    /** Called from the WebSocket server when a client disconnects. */
    public void removeSession(UserWebSocketSession session) {
        sessions.remove(session);
    }

    // ---- Snapshot merge for a custom NEED_SNAPSHOT session ----

    /**
     * Builds the merged snapshot map for a custom-rules session: the global snapshot with the
     * user's configured keys removed, unioned with the user's personal snapshot (custom tiers).
     * Snapshots are rare (connect / explicit SNAPSHOT_REQUEST), so building a small per-session
     * map is acceptable.
     */
    private Map<String, OrderBookUpdate> mergedSnapshot(UserClassificationContext ctx) {
        Set<String> configured = ctx.rule().configuredKeys();
        Map<String, OrderBookUpdate> merged = new LinkedHashMap<>();
        for (Map.Entry<String, OrderBookUpdate> e : globalFeed.getSnapshot().entrySet()) {
            if (!configured.contains(e.getKey())) merged.put(e.getKey(), e.getValue());
        }
        merged.putAll(ctx.feedStore().getSnapshot());
        return merged;
    }

    // ---- JSON building — all methods write into the shared sb field ----

    /** Builds one JSON body per pending update, keyed by {@code "SYMBOL:MARKET"} for filtering. */
    private Map<String, String> buildKeyedBodies(Map<String, OrderBookUpdate> pending) {
        Map<String, String> bodies = new LinkedHashMap<>(Math.max(4, pending.size() * 2));
        for (Map.Entry<String, OrderBookUpdate> e : pending.entrySet()) {
            bodies.put(e.getKey(), buildUpdateBody(e.getValue()));
        }
        return bodies;
    }

    private String buildUpdateBody(OrderBookUpdate update) {
        sb.setLength(0);
        sb.append("{\"type\":\"").append(update.type().name()).append('"');
        sb.append(",\"symbol\":\"").append(update.symbol()).append('"');
        sb.append(",\"market\":\"").append(update.market().name()).append('"');
        if (update.type() != FeedEventType.DROP) {
            sb.append(",\"bids\":");
            appendLevels(update.bids());
            sb.append(",\"asks\":");
            appendLevels(update.asks());
        }
        sb.append('}');
        return sb.toString();
    }

    private String buildSnapshotBody(Map<String, OrderBookUpdate> snapshot) {
        sb.setLength(0);
        sb.append("{\"type\":\"SNAPSHOT\",\"data\":[");
        boolean first = true;
        for (OrderBookUpdate update : snapshot.values()) {
            if (!first) sb.append(',');
            appendTickerData(update);
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private void appendTickerData(OrderBookUpdate update) {
        sb.append("{\"symbol\":\"").append(update.symbol()).append('"');
        sb.append(",\"market\":\"").append(update.market().name()).append('"');
        sb.append(",\"bids\":");
        appendLevels(update.bids());
        sb.append(",\"asks\":");
        appendLevels(update.asks());
        sb.append('}');
    }

    private void appendLevels(ClassifiedLevel[] levels) {
        sb.append('[');
        boolean first = true;
        for (ClassifiedLevel level : levels) {
            if (level == null) break;
            if (!first) sb.append(',');
            sb.append("{\"price\":").append(level.price());
            sb.append(",\"quantity\":").append(level.quantity());
            sb.append(",\"tier\":").append(level.tier());
            sb.append(",\"firstSeenMillis\":").append(level.firstSeenMillis());
            sb.append(",\"distance\":").append(level.distance()); // fraction (0.05 = 5%); client renders as %
            sb.append('}');
            first = false;
        }
        sb.append(']');
    }

    // Injects seq as the first field: {"type":"...",...} → {"seq":N,"type":"...",...}
    // Uses the shared sb; safe because the body String was already captured before this call.
    private String injectSeq(String body, int seq) {
        sb.setLength(0);
        sb.append("{\"seq\":").append(seq).append(',').append(body, 1, body.length());
        return sb.toString();
    }
}
