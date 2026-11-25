package dev.abu.screener_backend.analysis.cluster;

import dev.abu.screener_backend.analysis.DefaultClassificationRule;
import dev.abu.screener_backend.config.ClusterDetectionProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrderClusterDetector} on synthetic books. The thick-book tests are the ones that matter
 * most: a deep, uniform major book must stay silent, or the detector is worthless.
 */
public class OrderClusterDetectorTest {

    private static final long T0 = 1_700_000_000_000L;

    /** The brief's proposed defaults (§8), with delivery left on: it does not matter to the detector. */
    public static ClusterDetectionProperties props() {
        return new ClusterDetectionProperties(true, true, 750, 0.05, 8, 0.4, 3.0, 3.0, 0.8, 3000, 0.15, 3, 0.5, 200,
                new ClusterDetectionProperties.MinTotalNotional(2_000_000, 300_000));
    }

    private static final Instrument ARB = Instrument.of(0, Venue.BINANCE_SPOT, "ARBUSDT", "ARB", "USDT");
    private static final Instrument BTC = Instrument.of(1, Venue.BINANCE_FUTURES, "BTCUSDT", "BTC", "USDT");
    private static final Instrument DOGE = Instrument.of(2, Venue.BINANCE_FUTURES, "DOGEUSDT", "DOGE", "USDT");
    private static final Instrument XYZ = Instrument.of(3, Venue.BYBIT_FUTURES, "XYZUSDT", "XYZ", "USDT");

    private final AtomicLong clock = new AtomicLong(T0);
    private final RecordingSink sink = new RecordingSink();
    private final PipelineMetrics metrics = new PipelineMetrics();
    private final OrderClusterDetector detector = new OrderClusterDetector(
            props(), new DefaultClassificationRule(), sink, metrics, clock::get);

    // ---- book fixtures -------------------------------------------------------------------------

    /** Prices are {@code ticks / scale}, so every price is the same exact double every time. */
    private static double px(int ticks, double scale) {
        return ticks / scale;
    }

    /** Puts a level of {@code notional} quote currency at {@code price}. */
    private static void level(OrderBook ob, boolean bid, double price, double notional) {
        ob.applyLevel(bid, price, notional / price, T0);
    }

    private static OrderBook synced(OrderBook ob) {
        ob.computeDistance();
        ob.markSynced();
        return ob;
    }

    /** Small background notionals between 1K and 3K, deterministic. */
    private static double small(int i) {
        return 1_000 + (i * 37 % 11) * 200;
    }

    /** Cluster notionals between 100K and 120K, deterministic. */
    private static double arbLevel(int i) {
        return 100_000 + (i * 7 % 21) * 1_000;
    }

    /**
     * The brief's ARB/USDT case: bids 0.0760..0.0741 small, then 41 levels 0.0740..0.0700 at
     * $100–120K each, then small again down to 0.0690. Asks 0.0761..0.0800 small.
     */
    private static OrderBook arbBook() {
        OrderBook ob = new OrderBook(0.1);
        for (int t = 760; t >= 741; t--) level(ob, true, px(t, 10_000), small(t));
        for (int t = 740; t >= 700; t--) level(ob, true, px(t, 10_000), arbLevel(t));
        for (int t = 699; t >= 690; t--) level(ob, true, px(t, 10_000), small(t));
        for (int t = 761; t <= 800; t++) level(ob, false, px(t, 10_000), small(t));
        return synced(ob);
    }

    private static double arbTotal() {
        double sum = 0;
        for (int t = 740; t >= 700; t--) sum += arbLevel(t);
        return sum;
    }

    /**
     * A thick major-like book: {@code levels} per side around 100,000, each step 1–5 ticks of 0.1,
     * each level's notional drawn from [lo, hi]. With {@code lo == hi} the book is perfectly uniform.
     */
    private static OrderBook thickBook(long seed, int levels, double lo, double hi) {
        Random rnd = new Random(seed);
        OrderBook ob = new OrderBook(0.1);
        int bidTicks = 999_990; // 99,999.0
        int askTicks = 1_000_010;
        for (int i = 0; i < levels; i++) {
            level(ob, true, px(bidTicks, 10), lo + rnd.nextDouble() * (hi - lo));
            level(ob, false, px(askTicks, 10), lo + rnd.nextDouble() * (hi - lo));
            bidTicks -= 1 + rnd.nextInt(5);
            askTicks += 1 + rnd.nextInt(5);
        }
        return synced(ob);
    }

    /** Runs detection at {@code T0 + offsetMillis}. */
    private void runAt(long offsetMillis, Instrument inst, OrderBook ob) {
        clock.set(T0 + offsetMillis);
        detector.process(inst, ob);
    }

    /** Runs detection once a second, at 0s..{@code seconds}s. */
    private void runEverySecond(int seconds, Instrument inst, OrderBook ob) {
        for (int s = 0; s <= seconds; s++) runAt(s * 1000L, inst, ob);
    }

    // ---- thick books: must not fire ------------------------------------------------------------

    @Nested
    @DisplayName("thick books")
    class ThickBooks {

        @Test
        @DisplayName("a thick BTC-like book of $0.5–2M levels produces no cluster")
        void thickRandomMajorBookIsSilent() {
            OrderBook ob = thickBook(42, 1000, 500_000, 2_000_000);
            runEverySecond(10, BTC, ob);

            assertEquals(0, sink.calls.size(), "no emission: " + sink.calls);
            assertEquals(0, detector.activeClusters());
            assertTrue(detector.runs() >= 10, "detection actually ran");
        }

        @Test
        @DisplayName("a perfectly uniform $1M book produces no cluster, even as one endless run")
        void uniformMajorBookIsSilent() {
            OrderBook ob = thickBook(7, 1000, 1_000_000, 1_000_000);
            runEverySecond(10, BTC, ob);

            assertEquals(0, sink.calls.size());
        }

        @Test
        @DisplayName("the same thick book on a non-major stays silent: the stand-out test, not the threshold, keeps it quiet")
        void thickBookOnNonMajorIsSilent() {
            OrderBook ob = thickBook(42, 1000, 500_000, 2_000_000);
            runEverySecond(10, DOGE, ob);

            assertEquals(0, sink.calls.size());
        }

        @Test
        @DisplayName("many thick books with different seeds never fire")
        void manySeedsNeverFire() {
            for (long seed = 1; seed <= 25; seed++) {
                OrderBook ob = thickBook(seed, 600, 300_000, 2_500_000);
                Instrument inst = Instrument.of(100 + (int) seed, Venue.BYBIT_FUTURES, "S" + seed + "USDT", "S" + seed, "USDT");
                for (int s = 0; s <= 5; s++) runAt(s * 1000L, inst, ob);
            }
            assertEquals(0, sink.calls.size(), "no emission: " + sink.calls);
        }

        @Test
        @DisplayName("a real wall inside a thick book still fires: 12 levels at $8M in a $1M book")
        void standoutBandInsideThickBookFires() {
            OrderBook ob = thickBook(3, 1000, 800_000, 1_200_000);
            // overwrite 12 consecutive bid levels about 0.5% below mid with $8M each
            List<Double> bidPrices = new ArrayList<>(ob.getBids().keySet());
            for (int i = 100; i < 112; i++) level(ob, true, bidPrices.get(i), 8_000_000);
            synced(ob);
            runEverySecond(4, BTC, ob);

            assertEquals(1, sink.calls.size());
            Call call = sink.calls.getFirst();
            assertEquals(1, call.bids.size());
            assertEquals(0, call.asks.size());
            OrderCluster c = call.bids.getFirst();
            assertEquals(12, c.levelCount());
            assertEquals(bidPrices.get(111), c.priceFrom());
            assertEquals(bidPrices.get(100), c.priceTo());
            assertEquals(96_000_000, c.totalNotional(), 1);
        }
    }

    // ---- the ARB ladder ------------------------------------------------------------------------

    @Test
    @DisplayName("the ARB ladder is detected with its bounds, level count, total and ascending levels")
    void arbLadderIsDetected() {
        OrderBook ob = arbBook();
        runEverySecond(3, ARB, ob);

        assertEquals(1, sink.calls.size());
        Call call = sink.calls.getFirst();
        assertEquals(ARB, call.instrument);
        assertEquals(0, call.asks.size());
        assertEquals(1, call.bids.size());

        OrderCluster c = call.bids.getFirst();
        assertEquals(0.0700, c.priceFrom());
        assertEquals(0.0740, c.priceTo());
        assertEquals(41, c.levelCount());
        assertEquals(arbTotal(), c.totalNotional(), 1e-3);
        assertEquals(T0, c.firstSeenMillis(), "first detected at the first run");
        double mid = (0.0760 + 0.0761) / 2;
        assertEquals((mid - 0.0740) / mid, c.distance(), 1e-12, "distance of the nearest edge");

        assertEquals(41, c.prices().length);
        assertEquals(41, c.quantities().length);
        assertEquals(0.0700, c.prices()[0]);
        assertEquals(0.0740, c.prices()[40]);
        double qtySum = 0;
        for (int i = 0; i < 41; i++) {
            if (i > 0) assertTrue(c.prices()[i] > c.prices()[i - 1], "ascending");
            qtySum += c.quantities()[i];
        }
        assertEquals(qtySum, c.totalQuantity(), 1e-6);
        assertEquals(1, detector.activeClusters());
    }

    @Test
    @DisplayName("an ask-side ladder is detected the same way, nearest edge first")
    void askLadderIsDetected() {
        OrderBook ob = new OrderBook(0.1);
        for (int t = 1000; t >= 960; t--) level(ob, true, px(t, 1000), small(t));
        for (int t = 1001; t <= 1030; t++) level(ob, false, px(t, 1000), small(t));
        for (int t = 1031; t <= 1040; t++) level(ob, false, px(t, 1000), 60_000);
        for (int t = 1041; t <= 1060; t++) level(ob, false, px(t, 1000), small(t));
        synced(ob);
        runEverySecond(3, XYZ, ob);

        assertEquals(1, sink.calls.size());
        OrderCluster c = sink.calls.getFirst().asks.getFirst();
        assertEquals(1.031, c.priceFrom());
        assertEquals(1.040, c.priceTo());
        assertEquals(10, c.levelCount());
        assertEquals(600_000, c.totalNotional(), 1e-6);
    }

    // ---- gaps ----------------------------------------------------------------------------------

    /**
     * Bids near 1.0 with a tick of 0.0001: 30 small levels, a run of {@code first} levels at $50K,
     * {@code gapTicks} empty ticks, a run of {@code second} levels at $50K, then 41 small levels.
     */
    private static OrderBook gapBook(int first, int gapTicks, int second) {
        OrderBook ob = new OrderBook(0.1);
        int t = 10_000;
        for (int i = 0; i < 30; i++, t--) level(ob, true, px(t, 10_000), small(i));
        for (int i = 0; i < first; i++, t--) level(ob, true, px(t, 10_000), 50_000);
        t -= gapTicks;
        for (int i = 0; i < second; i++, t--) level(ob, true, px(t, 10_000), 50_000);
        for (int i = 0; i < 41; i++, t--) level(ob, true, px(t, 10_000), small(i + 3));
        for (int a = 10_001; a <= 10_060; a++) level(ob, false, px(a, 10_000), small(a));
        return synced(ob);
    }

    @Test
    @DisplayName("a wide gap splits one ladder into two clusters")
    void wideGapSplitsIntoTwoClusters() {
        runEverySecond(3, XYZ, gapBook(10, 10, 10));

        assertEquals(1, sink.calls.size());
        List<OrderCluster> bids = sink.calls.getFirst().bids;
        assertEquals(2, bids.size());
        assertEquals(10, bids.get(0).levelCount());
        assertEquals(10, bids.get(1).levelCount());
        assertTrue(bids.get(0).distance() < bids.get(1).distance(), "nearest cluster first");
        assertEquals(px(9_970, 10_000), bids.get(0).priceTo());
        assertEquals(px(9_961, 10_000), bids.get(0).priceFrom());
        assertEquals(px(9_950, 10_000), bids.get(1).priceTo());
        assertEquals(px(9_941, 10_000), bids.get(1).priceFrom());
    }

    @Test
    @DisplayName("a wide gap leaves two runs of 6, neither reaching min-levels: nothing")
    void wideGapLeavesTwoShortRuns() {
        runEverySecond(3, XYZ, gapBook(6, 10, 6));

        assertEquals(0, sink.calls.size());
    }

    @Test
    @DisplayName("one missing tick does not break the run")
    void oneMissingTickKeepsTheRun() {
        runEverySecond(3, XYZ, gapBook(6, 1, 6));

        assertEquals(1, sink.calls.size());
        OrderCluster c = sink.calls.getFirst().bids.getFirst();
        assertEquals(12, c.levelCount());
        assertEquals(600_000, c.totalNotional(), 1e-6);
    }

    // ---- hysteresis, lifetime, updates, removal ------------------------------------------------

    /** XYZ bids: 30 small, then 10 levels of {@code each}, then 30 small. Asks small. */
    private static OrderBook tenLevelBook(double each) {
        OrderBook ob = new OrderBook(0.1);
        int t = 10_000;
        for (int i = 0; i < 30; i++, t--) level(ob, true, px(t, 10_000), small(i));
        for (int i = 0; i < 10; i++, t--) level(ob, true, px(t, 10_000), each);
        for (int i = 0; i < 30; i++, t--) level(ob, true, px(t, 10_000), small(i + 5));
        for (int a = 10_001; a <= 10_060; a++) level(ob, false, px(a, 10_000), small(a));
        return synced(ob);
    }

    /** Rewrites the 10 cluster levels of {@link #tenLevelBook} to {@code each}. */
    private static void setClusterLevels(OrderBook ob, double each) {
        for (int t = 9_970; t > 9_960; t--) level(ob, true, px(t, 10_000), each);
        ob.computeDistance();
    }

    @Test
    @DisplayName("a cluster oscillating around the enter threshold emits once")
    void oscillationAroundThresholdEmitsOnce() {
        OrderBook ob = tenLevelBook(31_000);  // total 310K ≥ 300K enter
        for (int s = 0; s <= 12; s++) {
            setClusterLevels(ob, s % 2 == 0 ? 31_000 : 28_000); // 280K: below enter, above exit (240K)
            runAt(s * 1000L, XYZ, ob);
        }

        assertEquals(1, sink.calls.size(), "one emission, no flicker: " + sink.calls);
        assertEquals(0, sink.removals.size());
    }

    @Test
    @DisplayName("a cluster that dips below the exit threshold before its lifetime is never announced")
    void flickerBelowExitIsNeverAnnounced() {
        OrderBook ob = tenLevelBook(31_000);
        for (int s = 0; s <= 12; s++) {
            setClusterLevels(ob, s % 2 == 0 ? 31_000 : 20_000); // 200K < exit
            runAt(s * 1000L, XYZ, ob);
        }

        assertEquals(0, sink.calls.size());
        assertEquals(0, sink.removals.size());
    }

    @Test
    @DisplayName("a cluster is announced only after min-lifetime-millis")
    void announcedAfterLifetime() {
        OrderBook ob = tenLevelBook(50_000);
        runAt(0, XYZ, ob);
        runAt(1000, XYZ, ob);
        runAt(2200, XYZ, ob); // 2.2 s old: still pending (3000 is a full throttle window later)
        assertEquals(0, sink.calls.size());

        runAt(3000, XYZ, ob);
        assertEquals(1, sink.calls.size());
    }

    @Test
    @DisplayName("a total moving more than update-threshold re-emits; a smaller move does not")
    void updateThreshold() {
        OrderBook ob = tenLevelBook(50_000);
        runEverySecond(3, XYZ, ob);
        assertEquals(1, sink.calls.size());

        setClusterLevels(ob, 55_000); // +10%
        runAt(4000, XYZ, ob);
        assertEquals(1, sink.calls.size());

        setClusterLevels(ob, 60_000); // +20% from the emitted total
        runAt(5000, XYZ, ob);
        assertEquals(2, sink.calls.size());
        assertEquals(600_000, sink.calls.get(1).bids.getFirst().totalNotional(), 1e-6);
        assertEquals(T0, sink.calls.get(1).bids.getFirst().firstSeenMillis(), "same cluster, same first-seen");
    }

    @Test
    @DisplayName("a pulled cluster is removed, and the active count returns to zero")
    void pulledClusterIsRemoved() {
        OrderBook ob = tenLevelBook(50_000);
        runEverySecond(3, XYZ, ob);
        assertEquals(1, detector.activeClusters());

        setClusterLevels(ob, 0); // qty 0 removes the levels
        runAt(4000, XYZ, ob);

        assertEquals(List.of(XYZ), sink.removals);
        assertEquals(0, detector.activeClusters());
        assertEquals(0, metrics.activeClusters());
    }

    @Test
    @DisplayName("a book that leaves SYNCED drops its announced clusters at once, without waiting for the throttle")
    void unsyncDropsClusters() {
        OrderBook ob = tenLevelBook(50_000);
        runEverySecond(3, XYZ, ob);
        assertEquals(1, sink.calls.size());

        ob.markPending();
        runAt(3001, XYZ, ob);

        assertEquals(List.of(XYZ), sink.removals);
        assertEquals(0, detector.activeClusters());
    }

    // ---- nothing to do: no output and no allocation --------------------------------------------

    @Test
    @DisplayName("an unsynced book, an empty side and a sub-min-levels cluster produce nothing")
    void nothingToDetect() {
        OrderBook unsynced = arbBook();
        unsynced.markPending();
        OrderBook noAsks = new OrderBook(0.1);
        for (int t = 740; t >= 700; t--) level(noAsks, true, px(t, 10_000), arbLevel(t));
        synced(noAsks);
        OrderBook shortRun = gapBook(7, 0, 0);

        runEverySecond(5, ARB, unsynced);
        runEverySecond(5, DOGE, noAsks);
        runEverySecond(5, XYZ, shortRun);

        assertEquals(0, sink.calls.size());
        assertEquals(0, sink.removals.size());
    }

    @Test
    @DisplayName("an unsynced book and an empty side allocate nothing")
    void unsyncedAndEmptyAllocateNothing() {
        OrderBook unsynced = arbBook();
        unsynced.markPending();
        OrderBook noAsks = new OrderBook(0.1);
        for (int t = 740; t >= 700; t--) level(noAsks, true, px(t, 10_000), arbLevel(t));
        synced(noAsks);

        Runnable calls = () -> {
            for (int i = 0; i < 10_000; i++) {
                clock.addAndGet(1000);
                detector.process(ARB, unsynced);
                detector.process(DOGE, noAsks);
            }
        };
        calls.run(); // warm up
        long bytes = allocatedBy(calls);

        assertTrue(bytes < 4_096, "allocated " + bytes + " bytes over 20,000 calls");
    }

    @Test
    @DisplayName("a detection run over a book with nothing to report allocates at most the TreeMap iterators")
    void quietRunAllocatesAlmostNothing() {
        OrderBook ob = thickBook(42, 1000, 500_000, 2_000_000);
        int runs = 5_000;
        Runnable calls = () -> {
            for (int i = 0; i < runs; i++) {
                clock.addAndGet(1000); // every call is a real detection run
                detector.process(BTC, ob);
            }
        };
        calls.run(); // warm up, and grow the scratch arrays once
        long bytes = allocatedBy(calls);

        // Two TreeMap entry iterators per run (one per side) are the only allocation, and C2 often
        // removes even those. Anything above ~100 bytes a run means the scan itself allocates.
        assertTrue(bytes / runs <= 100, "allocated " + bytes / runs + " bytes per run");
        assertEquals(0, sink.calls.size());
    }

    private static long allocatedBy(Runnable r) {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        r.run();
        return mx.getThreadAllocatedBytes(tid) - before;
    }

    // ---- throttle and metrics ------------------------------------------------------------------

    @Test
    @DisplayName("a burst of 100 diffs inside one throttle window runs detection once")
    void throttle() {
        OrderBook ob = arbBook();
        for (int i = 0; i < 100; i++) runAt(i * 7L, ARB, ob); // 0..693 ms, all inside 750 ms

        assertEquals(1, detector.runs());
        assertEquals(1, metrics.clusterRuns());

        runAt(750, ARB, ob);
        assertEquals(2, detector.runs());
    }

    @Test
    @DisplayName("PipelineMetrics reports detection runs, time per run and the active cluster count")
    void metricsAreReported() {
        OrderBook ob = arbBook();
        runEverySecond(3, ARB, ob);

        assertEquals(4, metrics.clusterRuns());
        assertTrue(metrics.clusterRunNanos() > 0);
        assertTrue(metrics.takeClusterRunMaxNanos() > 0);
        assertEquals(0, metrics.takeClusterRunMaxNanos(), "max resets when taken");
        assertEquals(1, metrics.activeClusters());
        assertEquals(1, metrics.clusterEmits());
    }

    @Test
    @DisplayName("at most max-clusters-per-side clusters are kept, the largest by total")
    void maxClustersPerSide() {
        OrderBook ob = new OrderBook(0.1);
        int t = 10_000;
        for (int i = 0; i < 60; i++, t--) level(ob, true, px(t, 10_000), small(i));
        double[] sizes = {40_000, 60_000, 50_000, 70_000};
        for (double size : sizes) {
            for (int i = 0; i < 10; i++, t--) level(ob, true, px(t, 10_000), size);
            for (int i = 0; i < 25; i++, t--) level(ob, true, px(t, 10_000), small(i)); // separator
        }
        for (int a = 10_001; a <= 10_060; a++) level(ob, false, px(a, 10_000), small(a));
        synced(ob);
        runEverySecond(3, XYZ, ob);

        assertEquals(1, sink.calls.size());
        List<OrderCluster> bids = sink.calls.getFirst().bids;
        assertEquals(3, bids.size());
        List<Double> totals = bids.stream().map(OrderCluster::totalNotional).sorted().toList();
        assertEquals(List.of(500_000.0, 600_000.0, 700_000.0), totals);
    }

    // ---- review findings -----------------------------------------------------------------------

    @Test
    @DisplayName("a ladder spaced 4 ticks apart in a 1-tick book is still one cluster (first step judged on similarity)")
    void coarselySpacedLadderIsDetected() {
        OrderBook ob = new OrderBook(0.1);
        int t = 10_000;
        for (int i = 0; i < 30; i++, t--) level(ob, true, px(t, 10_000), small(i));
        for (int i = 0; i < 15; i++, t -= 4) level(ob, true, px(t, 10_000), 50_000);
        for (int i = 0; i < 30; i++, t--) level(ob, true, px(t, 10_000), small(i + 2));
        for (int a = 10_001; a <= 10_060; a++) level(ob, false, px(a, 10_000), small(a));
        synced(ob);
        runEverySecond(3, XYZ, ob);

        assertEquals(1, sink.calls.size());
        assertEquals(15, sink.calls.getFirst().bids.getFirst().levelCount());
    }

    @Test
    @DisplayName("when a pending and an announced cluster merge, the announced one survives with its first-seen time")
    void mergeKeepsTheAnnouncedCluster() {
        // A: 10 levels at 9970..9961, separator 9960, B: 10 levels at 9959..9950
        OrderBook ob = new OrderBook(0.1);
        int t = 10_000;
        for (int i = 0; i < 30; i++, t--) level(ob, true, px(t, 10_000), small(i));
        for (int i = 0; i < 10; i++, t--) level(ob, true, px(t, 10_000), 50_000); // A
        level(ob, true, px(t--, 10_000), small(1));                                // separator 9960
        for (int i = 0; i < 10; i++, t--) level(ob, true, px(t, 10_000), 50_000); // B
        for (int i = 0; i < 30; i++, t--) level(ob, true, px(t, 10_000), small(i + 4));
        for (int a = 10_001; a <= 10_060; a++) level(ob, false, px(a, 10_000), small(a));
        synced(ob);

        runAt(0, XYZ, ob);                                   // A -> slot 0, B -> slot 1, both pending
        for (int k = 9970; k > 9960; k--) level(ob, true, px(k, 10_000), small(k)); // A pulled
        ob.computeDistance();
        for (int s = 1; s <= 3; s++) runAt(s * 1000L, XYZ, ob); // B announced at 3 s, in slot 1
        assertEquals(1, sink.calls.size());
        for (int k = 9970; k > 9960; k--) level(ob, true, px(k, 10_000), 50_000); // A back: slot 0, pending
        ob.computeDistance();
        runAt(4000, XYZ, ob);
        level(ob, true, px(9960, 10_000), 50_000);            // separator fills: A and B merge
        ob.computeDistance();
        runAt(5000, XYZ, ob);

        assertEquals(0, sink.removals.size(), "the announced wall was never pulled");
        OrderCluster merged = sink.calls.getLast().bids.getFirst();
        assertEquals(21, merged.levelCount());
        assertEquals(T0, merged.firstSeenMillis(), "B's first-seen time (both appeared at 0 s) is kept");
        assertEquals(sink.calls.getFirst().bids.getFirst().firstSeenMillis(), merged.firstSeenMillis());
        assertEquals(1, detector.activeClusters());
    }

    @Test
    @DisplayName("a book with a run below min-levels allocates at most the TreeMap iterators per run")
    void belowMinLevelsAllocatesAlmostNothing() {
        OrderBook ob = gapBook(7, 0, 0);
        int runs = 5_000;
        Runnable calls = () -> {
            for (int i = 0; i < runs; i++) {
                clock.addAndGet(1000);
                detector.process(XYZ, ob);
            }
        };
        calls.run();
        long bytes = allocatedBy(calls);

        assertTrue(bytes / runs <= 100, "allocated " + bytes / runs + " bytes per run");
        assertEquals(0, sink.calls.size());
    }

    @Test
    @DisplayName("a deep uniform side (5,000 levels in one run) scans in well under a millisecond")
    void deepUniformBookIsLinear() {
        OrderBook ob = thickBook(11, 5_000, 1_000_000, 1_100_000);
        for (int i = 0; i < 200; i++) { clock.addAndGet(1000); detector.process(BTC, ob); } // warm up
        int runs = 200;
        long started = System.nanoTime();
        for (int i = 0; i < runs; i++) { clock.addAndGet(1000); detector.process(BTC, ob); }
        long perRunMicros = (System.nanoTime() - started) / runs / 1000;

        // both sides; quadratic insertion took ~6 ms here, a running median ~0.3 ms
        assertTrue(perRunMicros < 2_000, perRunMicros + " us per run");
    }

    @Test
    @DisplayName("a stray similar level 1.35% in front of a ladder is not pulled into it")
    void strayLevelInFrontOfLadderIsDropped() {
        OrderBook ob = new OrderBook(0.1);
        for (int t = 775; t >= 751; t--) level(ob, true, px(t, 10_000), small(t));
        level(ob, true, px(750, 10_000), 110_000);                       // the stray
        for (int t = 740; t >= 700; t--) level(ob, true, px(t, 10_000), arbLevel(t));
        for (int t = 776; t <= 815; t++) level(ob, false, px(t, 10_000), small(t));
        synced(ob);
        runEverySecond(3, ARB, ob);

        assertEquals(1, sink.calls.size());
        OrderCluster c = sink.calls.getFirst().bids.getFirst();
        assertEquals(0.0740, c.priceTo());
        assertEquals(41, c.levelCount());
    }

    @Test
    @DisplayName("a run that only grazes an announced cluster cannot take it from the run that continues it")
    void grazingRunDoesNotStealAnnouncedCluster() {
        ClusterDetectionProperties p = props();
        ClusterDetectionProperties lenient = new ClusterDetectionProperties(true, true, p.throttleMillis(),
                p.maxDistance(), p.minLevels(), p.similarityTolerance(), p.gapFactor(), 0.1, p.exitFactor(),
                p.minLifetimeMillis(), p.updateThreshold(), p.maxClustersPerSide(), p.sameClusterOverlap(),
                p.maxClusterLevels(), p.minTotalNotional()); // stand-out is not what this test is about
        OrderClusterDetector d = new OrderClusterDetector(lenient, new DefaultClassificationRule(), sink, metrics, clock::get);

        // A: 0.0740..0.0720 at $110K, announced. Later B: 0.0750..0.0742 at $300K, pending.
        OrderBook ob = new OrderBook(0.1);
        for (int t = 760; t >= 751; t--) level(ob, true, px(t, 10_000), small(t));
        for (int t = 740; t >= 720; t--) level(ob, true, px(t, 10_000), 110_000);
        for (int t = 761; t <= 800; t++) level(ob, false, px(t, 10_000), small(t));
        synced(ob);
        for (int s = 0; s <= 3; s++) { clock.set(T0 + s * 1000L); d.process(ARB, ob); }
        assertEquals(1, sink.calls.size(), "A announced");
        for (int t = 750; t >= 742; t--) level(ob, true, px(t, 10_000), 300_000);
        ob.computeDistance();
        clock.set(T0 + 3_800); d.process(ARB, ob); // B tracked, pending

        // B grows over the gap and A's upper part: X = 0.0750..0.0729 overlaps A by 0.55 and B by 1;
        // A's lower part Y = 0.0728..0.0720 overlaps A by 1. A must continue as Y, not be taken by X.
        for (int t = 741; t >= 729; t--) level(ob, true, px(t, 10_000), 300_000);
        ob.computeDistance();
        clock.set(T0 + 4_600); d.process(ARB, ob);

        assertEquals(0, sink.removals.size());
        List<OrderCluster> bids = sink.calls.getLast().bids;
        OrderCluster a = bids.stream().filter(c -> c.priceFrom() == 0.0720).findFirst().orElseThrow();
        assertEquals(0.0728, a.priceTo());
        assertEquals(T0, a.firstSeenMillis(), "still the announced A");
    }

    @Test
    @DisplayName("discard drops the clusters but keeps the throttle")
    void discardKeepsThrottle() {
        OrderBook ob = arbBook();
        runEverySecond(3, ARB, ob);
        assertEquals(1, detector.activeClusters());

        detector.discard(ARB);
        assertEquals(List.of(ARB), sink.removals);
        assertEquals(0, detector.activeClusters());

        long runs = detector.runs();
        runAt(3_100, ARB, ob); // inside the throttle window of the 3 s run
        assertEquals(runs, detector.runs());
    }

    @Test
    @DisplayName("MEXC is skipped for now: no run, no cluster, on either venue")
    void mexcIsIgnored() {
        OrderBook ob = arbBook();
        for (Venue venue : List.of(Venue.MEXC_SPOT, Venue.MEXC_FUTURES)) {
            runEverySecond(10, Instrument.of(4, venue, "ARB_USDT", "ARB", "USDT"), ob);
        }

        assertEquals(0, detector.runs());
        assertEquals(0, sink.calls.size());
        assertEquals(0, detector.activeClusters());
    }

    // ---- recording sink ------------------------------------------------------------------------

    record Call(Instrument instrument, List<OrderCluster> bids, List<OrderCluster> asks) {
    }

    static final class RecordingSink implements ClusterSink {
        final List<Call> calls = new ArrayList<>();
        final List<Instrument> removals = new ArrayList<>();

        @Override
        public void onClusters(Instrument instrument, List<OrderCluster> bidClusters, List<OrderCluster> askClusters) {
            assertNotNull(bidClusters);
            assertNotNull(askClusters);
            calls.add(new Call(instrument, bidClusters, askClusters));
        }

        @Override
        public void onClustersRemoved(Instrument instrument) {
            removals.add(instrument);
        }
    }
}
