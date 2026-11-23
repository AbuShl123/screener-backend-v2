package dev.abu.screener_backend.feed.cluster;

import dev.abu.screener_backend.analysis.cluster.OrderCluster;
import dev.abu.screener_backend.config.ClusterDetectionProperties;
import dev.abu.screener_backend.feed.Envelope;
import dev.abu.screener_backend.feed.FeedChannel;
import dev.abu.screener_backend.ws.UserWebSocketSession;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Layered order clusters ({@code websocket-feed-api.md} §3.9): one {@code CLUSTER} upsert per
 * instrument whose clusters changed this tick, {@code "data": null} when they are gone, and one
 * entry per instrument with clusters in every {@code SNAPSHOT}.
 *
 * <p>One global stream: there are no per-user cluster rules, so every session gets the same bodies
 * and there is no {@code ruleKey} filter. Entitlement is enforced at {@code @OnOpen}.
 *
 * <p>Clusters are state, so a snapshot entry may be newer than the last drain: the next tick then
 * repeats it as an upsert, or removes a cluster the snapshot no longer had. Both are harmless
 * (§3.7).
 *
 * <p>With {@code screener.clusters.delivery-enabled: false} (shadow mode) the store is still
 * drained every tick, so nothing builds up, but nothing is sent.
 *
 * <p>All methods run on the broadcaster thread, so per-tick state is plain fields.
 */
@Component
@Order(2)
public class ClusterChannel implements FeedChannel {

    /** Wire {@code type} of every message this channel sends. */
    static final String TYPE = "CLUSTER";
    private static final double DISTANCE_SCALE = 1e4;
    private static final double NOTIONAL_SCALE = 100;

    private final ClusterFeedStore store;
    private final boolean deliveryEnabled;

    private final List<ClusterUpdate> changed = new ArrayList<>();
    private final List<String> tickBodies = new ArrayList<>();
    private final StringBuilder sb = new StringBuilder(4096);

    public ClusterChannel(ClusterFeedStore store, ClusterDetectionProperties props) {
        this.store = store;
        this.deliveryEnabled = props.deliveryEnabled();
    }

    @Override
    public void drain() {
        changed.clear();
        tickBodies.clear();
        store.drainChanged(changed);
        if (deliveryEnabled) {
            for (ClusterUpdate update : changed) tickBodies.add(buildBody(update));
        }
        changed.clear(); // don't keep clusters reachable between ticks
    }

    @Override
    public void collectUpdates(UserWebSocketSession session, List<String> out) {
        out.addAll(tickBodies);
    }

    @Override
    public void collectSnapshot(UserWebSocketSession session, List<String> out) {
        if (!deliveryEnabled) return;
        for (ClusterUpdate update : store.current().values()) out.add(buildBody(update));
    }

    /** One {@code CLUSTER} envelope; live bodies and snapshot entries are byte-identical. */
    private String buildBody(ClusterUpdate update) {
        sb.setLength(0);
        Envelope.head(sb, TYPE, update.instrument());
        if (update.removed()) {
            sb.append("null");
        } else {
            sb.append("{\"bidClusters\":");
            appendClusters(update.bidClusters());
            sb.append(",\"askClusters\":");
            appendClusters(update.askClusters());
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    private void appendClusters(List<OrderCluster> clusters) {
        sb.append('[');
        for (int i = 0; i < clusters.size(); i++) {
            if (i > 0) sb.append(',');
            appendCluster(clusters.get(i));
        }
        sb.append(']');
    }

    /** Named fields (clusters are rare); the ladder is compact {@code [price, quantity]} pairs, ascending. */
    private void appendCluster(OrderCluster c) {
        sb.append("{\"priceFrom\":").append(c.priceFrom());
        sb.append(",\"priceTo\":").append(c.priceTo());
        sb.append(",\"levelCount\":").append(c.levelCount());
        sb.append(",\"totalNotional\":").append(Math.round(c.totalNotional() * NOTIONAL_SCALE) / NOTIONAL_SCALE);
        sb.append(",\"totalQuantity\":").append(c.totalQuantity());
        sb.append(",\"distance\":").append(Math.round(c.distance() * DISTANCE_SCALE) / DISTANCE_SCALE);
        sb.append(",\"firstSeenMillis\":").append(c.firstSeenMillis());
        sb.append(",\"levels\":[");
        double[] prices = c.prices();
        double[] quantities = c.quantities();
        for (int i = 0; i < prices.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(prices[i]).append(',').append(quantities[i]).append(']');
        }
        sb.append("]}");
    }
}
