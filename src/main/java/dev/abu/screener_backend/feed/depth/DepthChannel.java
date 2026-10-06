package dev.abu.screener_backend.feed.depth;

import dev.abu.screener_backend.analysis.UserClassificationContext;
import dev.abu.screener_backend.analysis.UserFeedRegistry;
import dev.abu.screener_backend.feed.FeedChannel;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.ws.UserWebSocketSession;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classified order-book levels: the global feed merged with each custom-rules user's personal feed.
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
 *   <li>a default session receives the global bodies; a custom session receives its personal
 *       bodies plus the global bodies whose {@code ruleKey} it has <b>not</b> configured.</li>
 * </ul>
 * This guarantees exactly one authoritative update per instrument per session per tick:
 * custom-tier data for configured keys, default-tier data for everything else. Rules are
 * exchange-independent, so a {@code BTCUSDT:SPOT} rule takes {@code BTCUSDT} spot off the global
 * feed on <b>every</b> exchange and replaces it with the personal feed's per-exchange entries.
 *
 * <p>Snapshot entries read the stores' live snapshot maps, which may be newer than the last drain.
 * That is harmless: a depth message is an upsert, so re-sending it next tick changes nothing.
 */
@Component
@Order(0)
@RequiredArgsConstructor
public class DepthChannel implements FeedChannel {

    private final OrderBookFeedStore globalFeed;
    private final UserFeedRegistry userFeedRegistry;

    // Reused across every call — safe because all FeedChannel methods run on the broadcaster's
    // single @Scheduled thread. Each build method resets it at entry and captures the result via
    // toString() before returning.
    private final StringBuilder sb = new StringBuilder(4096);

    // ---- Per-tick state: written by drain(), read by the collect calls of the same tick ----

    private List<KeyedBody> globalBodies = List.of();
    private final Map<UserClassificationContext, Map<String, OrderBookUpdate>> ctxPending = new IdentityHashMap<>();
    /** Personal bodies, built lazily on the first session of each context this tick. */
    private final Map<UserClassificationContext, List<KeyedBody>> ctxBodies = new IdentityHashMap<>();

    @Override
    public void drain() {
        // Global bodies are built eagerly: with no sessions the pending map is small and the build
        // cheap, and the channel isn't told whether any session needs them.
        globalBodies = buildKeyedBodies(globalFeed.drainPending());

        // Drain each active context's personal feed ONCE per tick (a context may back multiple
        // sessions — draining per session would lose data for the others). A context with no
        // session this tick is still drained, but its bodies are never built.
        ctxPending.clear();
        ctxBodies.clear();
        for (UserClassificationContext ctx : userFeedRegistry.activeContexts()) {
            ctxPending.put(ctx, ctx.feedStore().drainPending());
        }
    }

    @Override
    public void collectUpdates(UserWebSocketSession session, List<String> out) {
        UserClassificationContext ctx = session.getContext(); // nullable
        if (ctx == null) {
            for (KeyedBody kb : globalBodies) out.add(kb.body()); // the tick's shared Strings
            return;
        }

        List<KeyedBody> personalBodies = ctxBodies.get(ctx);
        if (personalBodies == null) {
            // A user connecting mid-drain may appear in the sessions snapshot for a context that
            // wasn't in activeContexts() when ctxPending was built — its personal feed hasn't been
            // drained this tick. Treat as empty; it drains next tick (cold-start gap, an accepted
            // Phase C limitation).
            Map<String, OrderBookUpdate> ctxPend = ctxPending.get(ctx);
            personalBodies = (ctxPend == null) ? List.of() : buildKeyedBodies(ctxPend);
            ctxBodies.put(ctx, personalBodies);
        }

        Set<String> configured = ctx.rule().configuredKeys();
        // Personal feed — the user's configured keys with their custom tiers.
        for (KeyedBody kb : personalBodies) {
            out.add(kb.body());
        }
        // Global feed — but only rule keys this user has NOT configured.
        for (KeyedBody kb : globalBodies) {
            if (!configured.contains(kb.ruleKey())) out.add(kb.body());
        }
    }

    @Override
    public void collectSnapshot(UserWebSocketSession session, List<String> out) {
        UserClassificationContext ctx = session.getContext(); // nullable
        Map<String, OrderBookUpdate> snapshot = (ctx == null) ? globalFeed.getSnapshot() : mergedSnapshot(ctx);
        for (OrderBookUpdate update : snapshot.values()) {
            out.add(buildSnapshotEntry(update));
        }
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
        if (pending.isEmpty()) return List.of();
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

    private String buildSnapshotEntry(OrderBookUpdate update) {
        sb.setLength(0);
        sb.append('{');
        appendIdentity(update.instrument());
        sb.append(",\"bids\":");
        appendLevels(update.bids());
        sb.append(",\"asks\":");
        appendLevels(update.asks());
        sb.append('}');
        return sb.toString();
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
}
