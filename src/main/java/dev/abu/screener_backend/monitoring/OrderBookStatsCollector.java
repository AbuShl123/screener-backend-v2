package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.binance.orderbook.BookStats;
import dev.abu.screener_backend.binance.orderbook.OrderBook;
import dev.abu.screener_backend.binance.orderbook.OrderBookState;
import dev.abu.screener_backend.binance.orderbook.OrderBookStore;
import dev.abu.screener_backend.config.MonitoringProperties;
import dev.abu.screener_backend.monitoring.dto.OrderBookHistoryResponse.HistoryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the consumer-published {@link BookStats} counters into <i>rates</i> and <i>staleness</i>.
 *
 * <p>{@code diffsApplied} is a monotonic counter; a rate needs two observations. This bean is that
 * second observation: it samples every book on a fixed schedule, differences each counter against
 * its own previous value, and republishes the derived numbers as one immutable snapshot.
 *
 * <p>Ordinary Spring code — the hot-path rules do <b>not</b> apply here. It never touches an
 * orderbook's maps; everything it reads is an already-published immutable record, so it cannot race
 * with the consumers.
 *
 * <h3>Why staleness is measured this way</h3>
 * A per-diff timestamp on the hot path would cost a {@code System.currentTimeMillis()} on every
 * message. Instead {@code idleMs} counts the time since {@code diffsApplied} was last <i>observed</i>
 * to change: a book whose counter did not move between two samples was idle for that interval. Its
 * resolution is therefore {@code sample-interval-ms} (default 5 s), which resolves the 30 s stale
 * threshold six times over. That is precisely why the field is named {@code idleMs} and not
 * {@code lastUpdateAgeMs} — do not rename it back without first restoring a per-diff timestamp.
 *
 * <h3>Publication discipline</h3>
 * The mutable working state ({@code previous}, the history and resync rings) is confined to the
 * scheduler thread. Readers see only {@link #snapshot()}, a single {@code volatile} reference
 * replaced wholesale each tick — no locks on the read path.
 */
@Component
@RequiredArgsConstructor
public class OrderBookStatsCollector {

    private static final long ONE_HOUR_MS = 3_600_000L;

    private final OrderBookStore store;
    private final MonitoringProperties props;

    /** Replaced wholesale each tick. */
    private volatile CollectorSnapshot snapshot = CollectorSnapshot.empty();

    // --- scheduler-thread-confined working state ---
    private Map<String, Observation> previous = Map.of();
    private final Deque<HistoryPoint> history = new ArrayDeque<>();
    private final Deque<long[]> resyncRing = new ArrayDeque<>();   // {atMillis, cumulativeFleetResyncs}
    private long lastHistoryAtMillis = 0L;

    /**
     * Immutable copy of {@link #history}, rebuilt <b>only</b> when a point is actually appended.
     *
     * <p>The collector ticks every {@code sample-interval-ms} but records history every
     * {@code history-interval-ms}, so most ticks leave the ring untouched. Copying a 2 880-element
     * list on every one of those ticks would be pure garbage; reusing this reference costs nothing
     * and is safe because {@code HistoryPoint} and the copy itself are immutable.
     */
    private List<HistoryPoint> publishedHistory = List.of();

    /** The latest derived sample. Safe from any thread. */
    public CollectorSnapshot snapshot() {
        return snapshot;
    }

    @Scheduled(fixedRateString = "${screener.monitoring.orderbook.sample-interval-ms}")
    public void sample() {
        MonitoringProperties.Orderbook cfg = props.orderbook();
        long now = System.currentTimeMillis();

        Map<String, Observation> current = new HashMap<>();
        Map<String, BookRate> rates = new HashMap<>();

        double fleetUpdatesPerSecond = 0.0;
        double fleetLevelVisitsPerSecond = 0.0;
        int staleBooks = 0;
        int synced = 0;
        long totalLevels = 0;
        long cumulativeResyncs = 0;
        int bookCount = 0;

        double[] syncedSizes = new double[store.size() + 8];
        int syncedSizeCount = 0;

        for (OrderBook book : store.books()) {
            BookStats stats = book.getStats();
            String key = key(stats);
            bookCount++;
            totalLevels += stats.size();
            cumulativeResyncs += stats.resyncs();

            Observation prev = previous.get(key);
            double rate;
            long idleMs;
            if (prev == null) {
                // First time we see this book: no baseline, so no rate and no accrued idle time yet.
                rate = 0.0;
                idleMs = 0L;
            } else {
                long dt = Math.max(1L, now - prev.atMillis());
                long delta = stats.diffsApplied() - prev.diffsApplied();
                if (delta < 0) delta = 0;                      // defensive; the counter is monotonic
                double instant = delta * 1000.0 / dt;
                double alpha = ewmaAlpha(dt, cfg.rateHalfLifeMs());
                rate = alpha * instant + (1 - alpha) * prev.rate();
                idleMs = delta > 0 ? 0L : prev.idleMs() + dt;
            }

            current.put(key, new Observation(now, stats.diffsApplied(), rate, idleMs));
            rates.put(key, new BookRate(rate, idleMs));
            fleetUpdatesPerSecond += rate;
            // The mid-price sweep walks every level of a book on every applied diff, so the fleet's
            // real work is Σ(size × rate) — not the message count.
            fleetLevelVisitsPerSecond += rate * stats.size();

            if (stats.state() == OrderBookState.SYNCED) {
                synced++;
                if (syncedSizeCount < syncedSizes.length) {
                    syncedSizes[syncedSizeCount++] = stats.size();
                }
                if (idleMs > cfg.staleThresholdMs()) staleBooks++;
            }
        }

        previous = current;

        long resyncsLastHour = trackResyncs(now, cumulativeResyncs, cfg.historySize());

        // History is recorded on its own, slower cadence — see MonitoringProperties.Orderbook.
        // Everything below the gate (an n-element sort, a list copy) is skipped on the ticks in
        // between, which at the default settings is five ticks out of every six.
        if (lastHistoryAtMillis == 0L || now - lastHistoryAtMillis >= cfg.historyIntervalMs()) {
            lastHistoryAtMillis = now;

            double[] sizes = Arrays.copyOf(syncedSizes, syncedSizeCount);
            Arrays.sort(sizes);
            Double meanSize = null;
            Double medianSize = null;
            if (syncedSizeCount > 0) {
                double sum = 0.0;
                for (double s : sizes) sum += s;
                meanSize = DescriptiveStats.round(sum / syncedSizeCount);
                medianSize = DescriptiveStats.round(DescriptiveStats.percentileSorted(sizes, 0.50));
            }

            Runtime rt = Runtime.getRuntime();
            long usedHeapBytes = rt.totalMemory() - rt.freeMemory();

            history.addLast(new HistoryPoint(
                    Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC),
                    bookCount, synced, totalLevels, meanSize, medianSize,
                    DescriptiveStats.round(fleetUpdatesPerSecond),
                    DescriptiveStats.round(fleetLevelVisitsPerSecond),
                    staleBooks, usedHeapBytes));
            while (history.size() > Math.max(1, cfg.historySize())) {
                history.removeFirst();
            }
            publishedHistory = List.copyOf(history);
        }

        snapshot = new CollectorSnapshot(
                now,
                Map.copyOf(rates),
                DescriptiveStats.round(fleetUpdatesPerSecond),
                DescriptiveStats.round(fleetLevelVisitsPerSecond),
                staleBooks,
                resyncsLastHour,
                publishedHistory);
    }

    /**
     * Append the fleet's cumulative resync count and return how many resyncs happened in the last
     * hour. Falls back to "since the oldest retained sample" while the ring is still filling.
     */
    private long trackResyncs(long now, long cumulativeResyncs, int historySize) {
        resyncRing.addLast(new long[]{now, cumulativeResyncs});
        while (resyncRing.size() > 1 && resyncRing.peekFirst()[0] < now - ONE_HOUR_MS) {
            resyncRing.removeFirst();
        }
        while (resyncRing.size() > Math.max(2, historySize)) {
            resyncRing.removeFirst();
        }
        return cumulativeResyncs - resyncRing.peekFirst()[1];
    }

    /** EWMA weight for an elapsed interval given a half-life: {@code 1 - 2^(-dt/halfLife)}. */
    private static double ewmaAlpha(long dtMillis, long halfLifeMillis) {
        if (halfLifeMillis <= 0) return 1.0;
        return 1.0 - Math.exp(-Math.log(2) * dtMillis / halfLifeMillis);
    }

    static String key(BookStats stats) {
        return stats.symbol() + ":" + stats.market().name();
    }

    /** Scheduler-thread-only baseline for one book. */
    private record Observation(long atMillis, long diffsApplied, double rate, long idleMs) {}

    /**
     * Derived per-book numbers.
     *
     * @param updatesPerSecond EWMA-smoothed diffs/second
     * @param idleMs           time since {@code diffsApplied} was last observed to change; resolution
     *                         is {@code sample-interval-ms}
     */
    public record BookRate(double updatesPerSecond, long idleMs) {

        public static final BookRate ZERO = new BookRate(0.0, 0L);
    }

    /**
     * One coherent derived sample of the whole fleet.
     *
     * @param sampledAtMillis      when the sample was taken; {@code 0} before the first tick
     * @param rates                per-book rates, keyed {@code "SYMBOL:MARKET"}
     * @param fleetUpdatesPerSecond Σ per-book rates
     * @param fleetLevelVisitsPerSecond Σ {@code size × rate} — price levels swept per second
     * @param staleBooks           SYNCED books idle longer than {@code stale-threshold-ms}
     * @param resyncsLastHour      fleet resyncs in the last hour
     * @param history              bounded fleet trend ring, oldest → newest; refreshed only on the
     *                             {@code history-interval-ms} cadence, not every tick
     */
    public record CollectorSnapshot(
            long sampledAtMillis,
            Map<String, BookRate> rates,
            double fleetUpdatesPerSecond,
            double fleetLevelVisitsPerSecond,
            int staleBooks,
            long resyncsLastHour,
            List<HistoryPoint> history
    ) {

        static CollectorSnapshot empty() {
            return new CollectorSnapshot(0L, Map.of(), 0.0, 0.0, 0, 0L, List.of());
        }

        public BookRate rate(String key) {
            return rates.getOrDefault(key, BookRate.ZERO);
        }
    }
}
