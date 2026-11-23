package dev.abu.screener_backend.analysis.cluster;

/**
 * Per-instrument cluster state, held by {@link OrderClusterDetector} under the instrument's
 * {@code feedKey} and touched only by the owning shard thread. Created the first time the
 * instrument's book is synced with both sides, then reused for the life of the process.
 */
final class ClusterState {

    long lastRunMillis;
    /** Whether the sink currently holds an entry for this instrument. */
    boolean reported;

    final TrackedCluster[] bids;
    final TrackedCluster[] asks;

    ClusterState(int perSide) {
        bids = slots(perSide);
        asks = slots(perSide);
    }

    private static TrackedCluster[] slots(int n) {
        TrackedCluster[] out = new TrackedCluster[n];
        for (int i = 0; i < n; i++) out[i] = new TrackedCluster();
        return out;
    }

    boolean hasActive() {
        return anyActive(bids) || anyActive(asks);
    }

    private static boolean anyActive(TrackedCluster[] side) {
        for (TrackedCluster t : side) if (t.active) return true;
        return false;
    }
}
