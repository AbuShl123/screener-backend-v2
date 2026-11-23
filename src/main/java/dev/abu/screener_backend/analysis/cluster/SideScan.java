package dev.abu.screener_backend.analysis.cluster;

import dev.abu.screener_backend.config.ClusterDetectionProperties;
import dev.abu.screener_backend.marketdata.core.book.PriceLevelEntry;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Run building for one side of one book: the scan's scratch, reused for every instrument of the
 * shard. Owned by one {@link OrderClusterDetector}, so touched only by its shard thread.
 *
 * <h2>The scan</h2>
 * <ol>
 *   <li>Copy every level within {@code maxDistance} of the mid into primitive arrays, nearest
 *       first (the <i>window</i>). Measure the side's median level notional inside it.</li>
 *   <li>Walk the window, building runs: a level joins the current run while its notional is within
 *       {@code similarityTolerance} of the run's median notional, its relative price step is at
 *       most {@code gapFactor} times the run's median step, and the run has fewer than
 *       {@code maxClusterLevels} levels. Otherwise the run closes and a new one starts at that
 *       level. The second level of a run is judged on similarity alone: with one level there is no
 *       run step to compare with yet, and the book's typical step says nothing about a ladder
 *       spaced more coarsely than the book. The third level repairs that: when the first step is
 *       more than {@code gapFactor} times the step to the third, the first level was a stray in
 *       front of the run and is dropped from it.</li>
 *   <li>A run still open at the window edge has started inside the window, so it may continue past
 *       it: levels are pulled from the same iterator until the run breaks. Only a run that already
 *       stands out is extended; a thick book's endless uniform run is not, which keeps the walk of a
 *       deep book bounded by the window.</li>
 *   <li>A closed run is a <i>candidate</i> when it has at least {@code minLevels} levels, starts in
 *       the window, its mean notional is at least {@code standoutRatio} times the side's median, and
 *       its total reaches the caller's floor (the exit threshold: the caller applies the enter
 *       threshold to runs that match no tracked cluster). The largest {@code maxClustersPerSide}
 *       candidates by total are kept.</li>
 * </ol>
 *
 * <h2>Allocation</h2>
 * None in steady state: all arrays are fields, grown (doubling) only when a book is larger than any
 * seen before. The run's medians are {@link RunningMedian}s, O(log n) per level and O(1) to read,
 * so a scan is O(n log n) even when a thick book is one long run; the side median uses an in-place
 * selection on a scratch copy. The only per-scan object is the {@code TreeMap} entry iterator, as in
 * the classifier.
 *
 * <p>Medians are upper medians ({@code sorted[n / 2]}), here and for the side.
 */
final class SideScan {

    private static final int INITIAL_CAPACITY = 256;

    private final double maxDistance;
    private final int minLevels;
    private final double similarityTolerance;
    private final double gapFactor;
    private final double standoutRatio;
    private final int maxClusterLevels;
    private final int maxCandidates;

    // ---- the scanned levels, nearest to the mid first ----
    double[] prices = new double[INITIAL_CAPACITY];
    double[] quantities = new double[INITIAL_CAPACITY];
    private double[] notionals = new double[INITIAL_CAPACITY];
    private double[] distances = new double[INITIAL_CAPACITY];
    private double[] work = new double[INITIAL_CAPACITY];
    int count;
    private int windowCount;

    private double sideMedianNotional;
    private double floor;

    // ---- the open run ----
    private int runStart;
    private int runCount;
    private double runNotional;
    private double runQuantity;
    private final RunningMedian runNotionals = new RunningMedian(INITIAL_CAPACITY);
    private final RunningMedian runSteps = new RunningMedian(INITIAL_CAPACITY);

    // ---- candidates, sorted by total descending ----
    int candidateCount;
    final int[] candStart;
    final int[] candLevels;
    final double[] candNotional;
    final double[] candQuantity;
    final double[] candLow;
    final double[] candHigh;
    final double[] candDistance;

    SideScan(ClusterDetectionProperties props) {
        this.maxDistance = props.maxDistance();
        this.minLevels = props.minLevels();
        this.similarityTolerance = props.similarityTolerance();
        this.gapFactor = props.gapFactor();
        this.standoutRatio = props.standoutRatio();
        this.maxClusterLevels = props.maxClusterLevels();
        this.maxCandidates = props.maxClustersPerSide();
        candStart = new int[maxCandidates];
        candLevels = new int[maxCandidates];
        candNotional = new double[maxCandidates];
        candQuantity = new double[maxCandidates];
        candLow = new double[maxCandidates];
        candHigh = new double[maxCandidates];
        candDistance = new double[maxCandidates];
    }

    /**
     * Scans one side and leaves its candidates in the {@code cand*} arrays.
     *
     * @param levels the side's live map, best price first; read on the owning shard thread only
     * @param floor  the smallest total a candidate may have (the exit threshold)
     */
    void scan(TreeMap<Double, PriceLevelEntry> levels, double floor) {
        this.floor = floor;
        count = 0;
        candidateCount = 0;
        runCount = 0;

        Iterator<Map.Entry<Double, PriceLevelEntry>> it = levels.entrySet().iterator();
        Map.Entry<Double, PriceLevelEntry> beyond = null;
        while (it.hasNext()) {
            Map.Entry<Double, PriceLevelEntry> e = it.next();
            if (e.getValue().distance > maxDistance) {
                beyond = e; // first level past the window; the extension starts here
                break;
            }
            append(e);
        }
        windowCount = count;
        if (windowCount == 0) return;
        measureSide();

        for (int i = 0; i < windowCount; i++) offer(i);

        // Extend a run that started in the window and already stands out past the window edge.
        while (beyond != null && runCount > 0 && standsOut()) {
            append(beyond);
            if (!offer(count - 1)) break; // the run closed; whatever starts beyond the window is no candidate
            beyond = it.hasNext() ? it.next() : null;
        }
        closeRun();
    }

    private void append(Map.Entry<Double, PriceLevelEntry> e) {
        if (count == prices.length) grow();
        double price = e.getKey();
        PriceLevelEntry level = e.getValue();
        prices[count] = price;
        quantities[count] = level.quantity;
        notionals[count] = price * level.quantity;
        distances[count] = level.distance;
        count++;
    }

    private void measureSide() {
        System.arraycopy(notionals, 0, work, 0, windowCount);
        sideMedianNotional = select(work, windowCount, windowCount / 2);
    }

    /** Relative price step from level {@code i - 1} to level {@code i}. */
    private double step(int i) {
        double prev = prices[i - 1];
        return Math.abs(prices[i] - prev) / prev;
    }

    /**
     * Offers level {@code i} to the open run. Returns {@code true} if it joined; otherwise the run
     * is closed (and judged) and a new one starts at {@code i}.
     */
    private boolean offer(int i) {
        if (runCount == 0) {
            startRun(i);
            return true;
        }
        double step = step(i);
        if (runCount == 2 && gapFactor * step < runSteps.median()) {
            // The second level joined without a gap check; the third shows the run's real step, and
            // the first step was a gap: the first level was a stray in front of the run. Drop it.
            startRun(i - 1);
        }
        double median = runNotionals.median();
        boolean joins = runCount < maxClusterLevels
                && Math.abs(notionals[i] - median) <= similarityTolerance * median
                && (runCount == 1 || step <= gapFactor * runSteps.median());
        if (joins) {
            runSteps.add(step);
            runNotionals.add(notionals[i]);
            runCount++;
            runNotional += notionals[i];
            runQuantity += quantities[i];
            return true;
        }
        closeRun();
        startRun(i);
        return false;
    }

    private void startRun(int i) {
        runStart = i;
        runCount = 1;
        runNotional = notionals[i];
        runQuantity = quantities[i];
        runNotionals.clear();
        runSteps.clear();
        runNotionals.add(notionals[i]);
    }

    private boolean standsOut() {
        return runNotional / runCount >= standoutRatio * sideMedianNotional;
    }

    /** Judges the open run and, if it qualifies, keeps it among the top candidates. */
    private void closeRun() {
        int n = runCount;
        runCount = 0;
        if (n < minLevels || runStart >= windowCount || runNotional < floor) return;
        if (runNotional / n < standoutRatio * sideMedianNotional) return;
        insertCandidate(runStart, n, runNotional, runQuantity);
    }

    private void insertCandidate(int start, int levels, double notional, double quantity) {
        int pos = candidateCount < maxCandidates ? candidateCount : maxCandidates - 1;
        if (candidateCount == maxCandidates && notional <= candNotional[pos]) return;
        while (pos > 0 && notional > candNotional[pos - 1]) {
            copyCandidate(pos - 1, pos);
            pos--;
        }
        double a = prices[start];
        double b = prices[start + levels - 1];
        candStart[pos] = start;
        candLevels[pos] = levels;
        candNotional[pos] = notional;
        candQuantity[pos] = quantity;
        candLow[pos] = Math.min(a, b);
        candHigh[pos] = Math.max(a, b);
        candDistance[pos] = distances[start];
        if (candidateCount < maxCandidates) candidateCount++;
    }

    private void copyCandidate(int from, int to) {
        candStart[to] = candStart[from];
        candLevels[to] = candLevels[from];
        candNotional[to] = candNotional[from];
        candQuantity[to] = candQuantity[from];
        candLow[to] = candLow[from];
        candHigh[to] = candHigh[from];
        candDistance[to] = candDistance[from];
    }

    /**
     * Copies candidate {@code c}'s ladder in ascending price order. Allocates: called only when a
     * cluster is emitted.
     */
    void copyLadder(int c, double[] outPrices, double[] outQuantities) {
        int start = candStart[c];
        int n = candLevels[c];
        boolean ascending = n < 2 || prices[start] < prices[start + 1];
        for (int k = 0; k < n; k++) {
            int src = ascending ? start + k : start + n - 1 - k;
            outPrices[k] = prices[src];
            outQuantities[k] = quantities[src];
        }
    }

    /**
     * The {@code k}-th smallest of {@code a[0..n)}, reordering {@code a} in place (Wirth's
     * selection; copes with long runs of equal values, which uniform books are full of).
     */
    static double select(double[] a, int n, int k) {
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            double x = a[k];
            int i = lo;
            int j = hi;
            do {
                while (a[i] < x) i++;
                while (x < a[j]) j--;
                if (i <= j) {
                    double t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            } while (i <= j);
            if (j < k) lo = i;
            if (k < i) hi = j;
        }
        return a[k];
    }

    private void grow() {
        int cap = prices.length * 2;
        prices = Arrays.copyOf(prices, cap);
        quantities = Arrays.copyOf(quantities, cap);
        notionals = Arrays.copyOf(notionals, cap);
        distances = Arrays.copyOf(distances, cap);
        work = Arrays.copyOf(work, cap);
    }
}
