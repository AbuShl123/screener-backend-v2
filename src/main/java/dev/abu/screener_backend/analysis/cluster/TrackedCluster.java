package dev.abu.screener_backend.analysis.cluster;

/**
 * One cluster the detector is following on one side of one instrument, from first sight until it
 * is gone. Mutable and reused: a slot in {@link ClusterState}, touched only by the owning shard
 * thread.
 *
 * <p>A tracked cluster is <i>pending</i> until it has lived {@code min-lifetime-millis}, then
 * <i>announced</i>: {@link #published} holds what the sink was last given.
 */
final class TrackedCluster {

    boolean active;
    long firstSeenMillis;

    // ---- as of the latest detection run ----
    double low;
    double high;
    double notional;
    /** Candidate index in this run's {@link SideScan}, valid only during the run that set it. */
    int candidate;
    boolean matched;

    // ---- as last announced ----
    OrderCluster published;
    /** Set during a run when {@link #published} must be rebuilt from this run's candidate. */
    boolean republish;

    boolean announced() {
        return published != null;
    }

    void start(long now, double low, double high, double notional, int candidate) {
        active = true;
        firstSeenMillis = now;
        published = null;
        republish = false;
        update(low, high, notional, candidate);
    }

    void update(double low, double high, double notional, int candidate) {
        this.low = low;
        this.high = high;
        this.notional = notional;
        this.candidate = candidate;
        this.matched = true;
    }

    void clear() {
        active = false;
        published = null;
        republish = false;
    }

    /** Overlap with {@code [lo, hi]} as a fraction of the shorter range; 0 when disjoint. */
    double overlap(double lo, double hi) {
        double shorter = Math.min(high - low, hi - lo);
        double common = Math.min(high, hi) - Math.max(low, lo);
        if (common < 0) return 0;
        if (shorter <= 0) return 1; // degenerate ranges touch: treat as the same place
        return common / shorter;
    }
}
