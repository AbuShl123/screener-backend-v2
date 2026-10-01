package dev.abu.screener_backend.feed;

import dev.abu.screener_backend.analysis.UserClassificationContext;
import dev.abu.screener_backend.analysis.UserFeedRegistry;
import dev.abu.screener_backend.exchange.Instrument;
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
 *   <li>the global feed is drained once and each update body is built alongside its
 *       instrument's {@code ruleKey} ({@code "BASEQUOTE:MARKET"}) so it can be filtered per custom
 *       session;</li>
 *   <li>each active context's personal feed is drained <b>once per context</b> (a context may
 *       back several sessions);</li>
 *   <li>a default session receives the global bodies (today's path); a custom session receives
 *       its personal bodies plus the global bodies whose {@code ruleKey} it has <b>not</b>
 *       configured.</li>
 * </ul>
 * This guarantees exactly one authoritative update per instrument per session per tick:
 * custom-tier data for configured keys, default-tier data for everything else. Rules are
 * exchange-independent, so a {@code BTCUSDT:SPOT} rule takes {@code BTCUSDT} spot off the global
 * feed on <b>every</b> exchange and replaces it with the personal feed's per-exchange entries.
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

    /** A drain slower than this has eaten most of its 100ms budget and is worth a line of its own. */
    private static final long SLOW_DRAIN_NANOS = 50_000_000L;

    // Written only by the @Scheduled drain thread, read by the health logger's thread. Volatile
    // rather than plain because these are sampled ~10x/second, not per depth message, so the fence
    // costs nothing worth counting.
    private volatile long maxDrainNanos;
    private volatile long slowDrains;

    /**
     * Times one drain and reports the outliers.
     *
     * <p>The loop has a 100ms budget and does per-session JSON building inside it. If it overruns,
     * delivery — not ingest — is the bottleneck, and nothing else in the pipeline would distinguish
     * those two: both look like a client seeing stale levels.
     */
    @Scheduled(fixedDelay = 100)
    public void drain() {
        if (sessions.isEmpty()) return;
        long startNanos = System.nanoTime();
        try {
            drainOnce();
        } finally {
            long elapsed = System.nanoTime() - startNanos;
            if (elapsed > maxDrainNanos) maxDrainNanos = elapsed;
            if (elapsed > SLOW_DRAIN_NANOS) {
                slowDrains++;
                log.warn("drain took {}ms for {} session(s) — over half the 100ms budget",
                        elapsed / 1_000_000, sessions.size());
            }
        }
    }

    /**
     * Longest drain since the last call, in milliseconds, and the number of drains that blew the
     * threshold. Reading resets both, so exactly one reader (the health logger) may call it.
     */
    public long[] takeDrainStats() {
        long[] out = { maxDrainNanos / 1_000_000, slowDrains };
        maxDrainNanos = 0;
        slowDrains = 0;
        return out;
    }

    private void drainOnce() {

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
        List<KeyedBody> globalBodies = null;
        Map<UserClassificationContext, List<KeyedBody>> ctxBodies = null;

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
                for (KeyedBody kb : globalBodies) {
                    batch.add(injectSeq(kb.body(), session.getAndIncrementSeq()));
                }
                if (!session.enqueueBatch(batch)) session.disconnect();
            } else {
                if (globalBodies == null && !globalPending.isEmpty()) {
                    globalBodies = buildKeyedBodies(globalPending);
                }
                if (ctxBodies == null) ctxBodies = new IdentityHashMap<>();
                List<KeyedBody> personalBodies = ctxBodies.get(ctx);
                if (personalBodies == null) {
                    // A user connecting mid-drain may appear in the sessions snapshot for a context
                    // that wasn't in activeContexts() when ctxPending was built — its personal feed
                    // hasn't been drained this tick. Treat as empty; it drains next tick (cold-start
                    // gap, an accepted Phase C limitation).
                    Map<String, OrderBookUpdate> ctxPend = (ctxPending == null) ? null : ctxPending.get(ctx);
                    personalBodies = (ctxPend == null) ? List.of() : buildKeyedBodies(ctxPend);
                    ctxBodies.put(ctx, personalBodies);
                }

                Set<String> configured = ctx.rule().configuredKeys();
                List<String> batch = new ArrayList<>();
                // Personal feed — the user's configured keys with their custom tiers.
                for (KeyedBody kb : personalBodies) {
                    batch.add(injectSeq(kb.body(), session.getAndIncrementSeq()));
                }
                // Global feed — but only rule keys this user has NOT configured.
                if (globalBodies != null) {
                    for (KeyedBody kb : globalBodies) {
                        if (!configured.contains(kb.ruleKey())) {
                            batch.add(injectSeq(kb.body(), session.getAndIncrementSeq()));
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
     * Builds the merged snapshot map for a custom-rules session: the global snapshot with every
     * instrument whose {@code ruleKey} the user configured removed, unioned with the user's personal
     * snapshot (custom tiers). Both maps are keyed by {@code feedKey}, so the same symbol on two
     * exchanges stays two entries.
     * Snapshots are rare (connect / explicit SNAPSHOT_REQUEST), so building a small per-session
     * map is acceptable.
     */
    private Map<String, OrderBookUpdate> mergedSnapshot(UserClassificationContext ctx) {
        Set<String> configured = ctx.rule().configuredKeys();
        Map<String, OrderBookUpdate> merged = new LinkedHashMap<>();
        for (Map.Entry<String, OrderBookUpdate> e : globalFeed.getSnapshot().entrySet()) {
            if (!configured.contains(e.getValue().instrument().ruleKey())) merged.put(e.getKey(), e.getValue());
        }
        merged.putAll(ctx.feedStore().getSnapshot());
        return merged;
    }

    // ---- JSON building — all methods write into the shared sb field ----

    /** A built JSON body paired with its instrument's {@code ruleKey}, for custom-session filtering. */
    private record KeyedBody(String ruleKey, String body) {}

    /** Builds one JSON body per pending update. The result is only iterated, never looked up. */
    private List<KeyedBody> buildKeyedBodies(Map<String, OrderBookUpdate> pending) {
        List<KeyedBody> bodies = new ArrayList<>(pending.size());
        for (OrderBookUpdate update : pending.values()) {
            bodies.add(new KeyedBody(update.instrument().ruleKey(), buildUpdateBody(update)));
        }
        return bodies;
    }

    private String buildUpdateBody(OrderBookUpdate update) {
        sb.setLength(0);
        sb.append("{\"type\":\"").append(update.type().name()).append("\",");
        appendIdentity(update.instrument());
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
        sb.append('{');
        appendIdentity(update.instrument());
        sb.append(",\"bids\":");
        appendLevels(update.bids());
        sb.append(",\"asks\":");
        appendLevels(update.asks());
        sb.append('}');
    }

    /**
     * Appends {@code "exchange":…,"symbol":…,"market":…} with no surrounding separators. Every
     * message that names an instrument (ADD, UPDATE, DROP and each SNAPSHOT entry) goes through
     * here. {@code symbol} is the normalized {@code BASEQUOTE} form, matching the rule API.
     */
    private void appendIdentity(Instrument inst) {
        sb.append("\"exchange\":\"").append(inst.exchange().name()).append('"');
        sb.append(",\"symbol\":\"").append(inst.symbol()).append('"');
        sb.append(",\"market\":\"").append(inst.market().name()).append('"');
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
