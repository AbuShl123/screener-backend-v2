package dev.abu.screener_backend.feed.cluster;

import dev.abu.screener_backend.analysis.cluster.ClusterSink;
import dev.abu.screener_backend.analysis.cluster.OrderCluster;
import dev.abu.screener_backend.marketdata.Instrument;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Current order clusters per instrument, written by every shard's {@code OrderClusterDetector}
 * (it is their {@link ClusterSink}) and drained by {@link ClusterChannel} on the broadcaster
 * thread. Keyed by {@code feedKey}, so the same symbol on two exchanges holds two entries.
 *
 * <p>Clusters are state, so the store keeps only the latest per instrument: {@link #current} is
 * the snapshot, and a dirty set says which instruments changed since the last drain. Several
 * changes inside one tick coalesce to the latest; a cluster that appears and goes within one tick
 * is delivered as a removal, which the contract makes harmless (a removal of an unknown
 * instrument is a no-op on the client).
 *
 * <h2>No lost change</h2>
 * A writer updates {@link #current} first, then marks the key dirty. The drain removes a key from
 * the dirty set first, then reads {@link #current}. Whichever way the two interleave, the drain
 * either reads the new state or leaves the key dirty for the next tick; at worst a client gets one
 * redundant upsert. (A swapped pending map, as the depth store uses, could lose a write that lands
 * in the old map after it was iterated; clusters change rarely, so a lost change would stay stale
 * until the next one.)
 */
@Component
public class ClusterFeedStore implements ClusterSink {

    private final ConcurrentHashMap<String, ClusterUpdate> current = new ConcurrentHashMap<>();
    /** Every instrument ever reported, so a removal can still name its instrument. */
    private final ConcurrentHashMap<String, Instrument> instruments = new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    @Override
    public void onClusters(Instrument instrument, List<OrderCluster> bidClusters, List<OrderCluster> askClusters) {
        String key = instrument.feedKey();
        instruments.putIfAbsent(key, instrument);
        current.put(key, new ClusterUpdate(instrument, bidClusters, askClusters));
        dirty.add(key);
    }

    @Override
    public void onClustersRemoved(Instrument instrument) {
        String key = instrument.feedKey();
        instruments.putIfAbsent(key, instrument);
        current.remove(key);
        dirty.add(key);
    }

    /**
     * Appends one entry per instrument that changed since the previous drain: its current state, or
     * a removal. Broadcaster thread only (one drainer).
     */
    void drainChanged(Collection<ClusterUpdate> out) {
        for (String key : dirty) {
            dirty.remove(key);
            ClusterUpdate now = current.get(key);
            out.add(now != null ? now : ClusterUpdate.removal(instruments.get(key)));
        }
    }

    /** Instruments that have clusters right now, by {@code feedKey}. A live, unmodifiable view. */
    public Map<String, ClusterUpdate> current() {
        return Collections.unmodifiableMap(current);
    }
}
