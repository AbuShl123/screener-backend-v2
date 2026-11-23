package dev.abu.screener_backend.feed.cluster;

import dev.abu.screener_backend.analysis.cluster.OrderCluster;
import dev.abu.screener_backend.marketdata.Instrument;

import java.util.List;

/**
 * One instrument's cluster state as the feed delivers it: its current announced clusters, or a
 * removal ({@link #removed()}: both lists {@code null}). Immutable.
 *
 * @param bidClusters nearest to the mid first; {@code null} for a removal
 * @param askClusters nearest to the mid first; {@code null} for a removal
 */
public record ClusterUpdate(Instrument instrument, List<OrderCluster> bidClusters, List<OrderCluster> askClusters) {

    static ClusterUpdate removal(Instrument instrument) {
        return new ClusterUpdate(instrument, null, null);
    }

    public boolean removed() {
        return bidClusters == null;
    }
}
