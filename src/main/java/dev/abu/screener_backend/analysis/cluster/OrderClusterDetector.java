package dev.abu.screener_backend.analysis.cluster;

import dev.abu.screener_backend.analysis.DefaultClassificationRule;
import dev.abu.screener_backend.config.ClusterDetectionProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.book.OrderBookState;
import dev.abu.screener_backend.marketdata.core.book.PriceLevelEntry;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Detects layered order clusters: one large order split across many consecutive price levels,
 * each too small for the classifier's tiers, together one wall. Brief:
 * {@code .claude/plans/team-plans/US3-implementation-brief.md}; design notes:
 * {@code order-cluster-plan.md}.
 *
 * <h2>Lifecycle and threading</h2>
 * One instance per Disruptor shard, built by {@code DisruptorShardManager} like the
 * {@link dev.abu.screener_backend.analysis.OrderBookClassifier}, and called from the depth arm of
 * {@code MarketEventHandler} right after classification. Every field, including the per-instrument
 * {@link ClusterState} map keyed by {@code feedKey}, is touched only by that shard's consumer
 * thread; the book's {@code TreeMap}s are read nowhere else. The only shared objects are the
 * {@link ClusterSink} and {@link PipelineMetrics}, both thread-safe.
 *
 * <h2>Per event</h2>
 * <ul>
 *   <li>A book that is not {@code SYNCED}, or has an empty side, drops its clusters at once (a
 *       removal is reported if any were announced). No state is created for it.</li>
 *   <li>Otherwise detection runs at most once per {@code throttle-millis} per instrument. Clusters
 *       move slowly, so this caps the cost whatever the diff rate.</li>
 * </ul>
 *
 * <h2>Per detection run</h2>
 * {@link SideScan} finds each side's candidate runs. Each candidate is matched to a tracked
 * cluster of the same side by price-range overlap (at least half of the shorter range): a match
 * is the same cluster, updated, and must only stay above the <b>exit</b> threshold; an unmatched
 * candidate must reach the <b>enter</b> threshold to be tracked at all. A tracked cluster is
 * announced once it has lived {@code min-lifetime-millis}, and re-announced only when its total
 * moved by more than {@code update-threshold} or its price bounds changed. A tracked cluster no
 * candidate matched is gone. When anything announced changed, the sink receives the instrument's
 * whole announced set (clusters are state, not events).
 *
 * <h2>Allocation</h2>
 * Nothing while scanning or when nothing changed (beyond the {@code TreeMap} iterators the
 * classifier also uses). An {@link OrderCluster}, with its ladder copied from the scan, is
 * allocated only when it is announced or re-announced.
 */
public class OrderClusterDetector {

    /** Two runs are the same cluster when they share at least this fraction of the shorter range. */
    private static final double SAME_CLUSTER_OVERLAP = 0.5;

    private final DefaultClassificationRule liquidity;
    private final ClusterSink sink;
    private final PipelineMetrics metrics;
    private final LongSupplier clock;

    private final long throttleMillis;
    private final double exitFactor;
    private final long minLifetimeMillis;
    private final double updateThreshold;
    private final int perSide;
    private final ClusterDetectionProperties.MinTotalNotional minTotal;

    private final Map<String, ClusterState> states = new HashMap<>();
    private final SideScan bidScan;
    private final SideScan askScan;
    /** Per candidate of the side being reconciled: unmatched and above the enter threshold. */
    private final boolean[] isNew;

    private long runs;
    private int activeClusters;

    /**
     * @param liquidity the default rule, for its majors/everything-else split only
     * @param clock     wall-clock millis; {@code System::currentTimeMillis} in production
     */
    public OrderClusterDetector(ClusterDetectionProperties props, DefaultClassificationRule liquidity,
                                ClusterSink sink, PipelineMetrics metrics, LongSupplier clock) {
        this.liquidity = liquidity;
        this.sink = sink;
        this.metrics = metrics;
        this.clock = clock;
        this.throttleMillis = props.throttleMillis();
        this.exitFactor = props.exitFactor();
        this.minLifetimeMillis = props.minLifetimeMillis();
        this.updateThreshold = props.updateThreshold();
        this.perSide = props.maxClustersPerSide();
        this.minTotal = props.minTotalNotional();
        this.bidScan = new SideScan(props);
        this.askScan = new SideScan(props);
        this.isNew = new boolean[perSide];
    }

    /** Entry point, called by the depth arm of MarketEventHandler after every depth event. */
    public void process(Instrument inst, OrderBook ob) {
        ClusterState st = states.get(inst.feedKey());
        TreeMap<Double, PriceLevelEntry> bids = ob.getBids();
        TreeMap<Double, PriceLevelEntry> asks = ob.getAsks();
        if (ob.getState() != OrderBookState.SYNCED || bids.isEmpty() || asks.isEmpty()) {
            if (st != null && st.hasActive()) dropAll(inst, st);
            return;
        }

        long now = clock.getAsLong();
        if (st == null) {
            st = new ClusterState(perSide);
            states.put(inst.feedKey(), st);
        } else if (now - st.lastRunMillis < throttleMillis) {
            return;
        }
        st.lastRunMillis = now;

        long started = System.nanoTime();
        double enter = minTotal.forClass(liquidity.isHighLiquidity(inst.symbol()));
        double exit = enter * exitFactor;
        bidScan.scan(bids, exit);
        askScan.scan(asks, exit);
        boolean changed = reconcile(st.bids, bidScan, now, enter);
        changed |= reconcile(st.asks, askScan, now, enter);
        if (changed) publish(inst, st);

        runs++;
        metrics.recordClusterRun(System.nanoTime() - started);
    }

    /** Detection runs on this shard since startup. For tests. */
    long runs() {
        return runs;
    }

    /** Announced clusters on this shard right now. */
    public int activeClusters() {
        return activeClusters;
    }

    /**
     * Matches this run's candidates of one side to its tracked clusters, ages them, and decides
     * what must be (re-)announced. Returns whether the side's announced set changed.
     */
    private boolean reconcile(TrackedCluster[] tracked, SideScan scan, long now, double enter) {
        for (TrackedCluster t : tracked) t.matched = false;

        for (int c = 0; c < scan.candidateCount; c++) {
            TrackedCluster match = bestMatch(tracked, scan.candLow[c], scan.candHigh[c]);
            if (match != null) {
                match.update(scan.candLow[c], scan.candHigh[c], scan.candNotional[c], c);
                isNew[c] = false;
            } else {
                isNew[c] = scan.candNotional[c] >= enter;
            }
        }

        boolean changed = false;
        for (TrackedCluster t : tracked) {
            if (t.active && !t.matched) {
                if (t.announced()) {
                    changed = true;
                    retire();
                }
                t.clear();
            }
        }
        for (int c = 0; c < scan.candidateCount; c++) {
            if (isNew[c]) freeSlot(tracked).start(now, scan.candLow[c], scan.candHigh[c], scan.candNotional[c], c);
        }

        for (TrackedCluster t : tracked) {
            if (!t.active) continue;
            if (!t.announced()) {
                t.republish = now - t.firstSeenMillis >= minLifetimeMillis;
            } else {
                OrderCluster last = t.published;
                t.republish = Math.abs(t.notional - last.totalNotional()) > updateThreshold * last.totalNotional()
                        || t.low != last.priceFrom()
                        || t.high != last.priceTo();
            }
            changed |= t.republish;
        }
        return changed;
    }

    private TrackedCluster bestMatch(TrackedCluster[] tracked, double low, double high) {
        TrackedCluster best = null;
        double bestOverlap = 0;
        for (TrackedCluster t : tracked) {
            if (!t.active || t.matched) continue;
            double overlap = t.overlap(low, high);
            if (overlap >= SAME_CLUSTER_OVERLAP && overlap > bestOverlap) {
                best = t;
                bestOverlap = overlap;
            }
        }
        return best;
    }

    /**
     * An inactive slot. One always exists: active slots are matched candidates, and matched plus
     * new candidates never exceed {@code max-clusters-per-side}.
     */
    private static TrackedCluster freeSlot(TrackedCluster[] tracked) {
        for (TrackedCluster t : tracked) if (!t.active) return t;
        throw new IllegalStateException("no free cluster slot");
    }

    /** Reports the instrument's whole announced set, building what must be (re-)announced. */
    private void publish(Instrument inst, ClusterState st) {
        List<OrderCluster> bids = announced(st.bids, bidScan);
        List<OrderCluster> asks = announced(st.asks, askScan);
        if (bids.isEmpty() && asks.isEmpty()) {
            if (st.reported) sink.onClustersRemoved(inst);
            st.reported = false;
        } else {
            sink.onClusters(inst, bids, asks);
            st.reported = true;
        }
    }

    /** One side's announced clusters, nearest to the mid first. Allocates: called only on change. */
    private List<OrderCluster> announced(TrackedCluster[] tracked, SideScan scan) {
        OrderCluster[] out = new OrderCluster[tracked.length];
        int n = 0;
        for (TrackedCluster t : tracked) {
            if (!t.active) continue;
            if (t.republish) {
                if (!t.announced()) {
                    activeClusters++;
                    metrics.adjustActiveClusters(1);
                }
                t.published = build(t, scan);
                t.republish = false;
                metrics.recordClusterEmit();
            }
            if (t.published == null) continue;
            int pos = n++;
            while (pos > 0 && out[pos - 1].distance() > t.published.distance()) {
                out[pos] = out[pos - 1];
                pos--;
            }
            out[pos] = t.published;
        }
        return n == 0 ? List.of() : List.of(Arrays.copyOf(out, n));
    }

    private static OrderCluster build(TrackedCluster t, SideScan scan) {
        int c = t.candidate;
        int levels = scan.candLevels[c];
        double[] prices = new double[levels];
        double[] quantities = new double[levels];
        scan.copyLadder(c, prices, quantities);
        return new OrderCluster(scan.candLow[c], scan.candHigh[c], levels, scan.candNotional[c],
                scan.candQuantity[c], scan.candDistance[c], t.firstSeenMillis, prices, quantities);
    }

    private void dropAll(Instrument inst, ClusterState st) {
        dropSide(st.bids);
        dropSide(st.asks);
        if (st.reported) {
            sink.onClustersRemoved(inst);
            st.reported = false;
        }
    }

    private void dropSide(TrackedCluster[] tracked) {
        for (TrackedCluster t : tracked) {
            if (t.active && t.announced()) retire();
            t.clear();
        }
    }

    private void retire() {
        activeClusters--;
        metrics.adjustActiveClusters(-1);
    }
}
