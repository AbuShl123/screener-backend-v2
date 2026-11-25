package dev.abu.screener_backend.analysis.cluster;

import dev.abu.screener_backend.marketdata.Instrument;

import java.util.List;

/**
 * Where {@link OrderClusterDetector} reports an instrument's announced clusters. Clusters are
 * state, not events: each call carries the instrument's complete current set and replaces whatever
 * was reported before.
 *
 * <p>Called on a Disruptor shard thread, only when the announced set changed. Implementations must
 * be thread-safe across shards (every shard reports into the same sink) and must not block.
 */
public interface ClusterSink {

    /**
     * The instrument's current announced clusters; at least one list is non-empty. Each list is
     * immutable and ordered nearest to the mid first.
     */
    void onClusters(Instrument instrument, List<OrderCluster> bidClusters, List<OrderCluster> askClusters);

    /** The instrument no longer has any announced cluster. */
    void onClustersRemoved(Instrument instrument);
}
