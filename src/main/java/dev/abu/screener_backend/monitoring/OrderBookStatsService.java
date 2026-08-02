package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.binance.orderbook.BookStats;
import dev.abu.screener_backend.binance.orderbook.OrderBook;
import dev.abu.screener_backend.binance.orderbook.OrderBookState;
import dev.abu.screener_backend.binance.orderbook.OrderBookStore;
import dev.abu.screener_backend.binance.websocket.Market;
import dev.abu.screener_backend.config.DisruptorProperties;
import dev.abu.screener_backend.config.MonitoringProperties;
import dev.abu.screener_backend.monitoring.OrderBookStatsCollector.BookRate;
import dev.abu.screener_backend.monitoring.OrderBookStatsCollector.CollectorSnapshot;
import dev.abu.screener_backend.monitoring.dto.BookSort;
import dev.abu.screener_backend.monitoring.dto.Histogram;
import dev.abu.screener_backend.monitoring.dto.HistogramDomain;
import dev.abu.screener_backend.monitoring.dto.OrderBookHistoryResponse;
import dev.abu.screener_backend.monitoring.dto.OrderBookHistoryResponse.HistoryPoint;
import dev.abu.screener_backend.monitoring.dto.OrderBookListResponse;
import dev.abu.screener_backend.monitoring.dto.OrderBookListResponse.BookRow;
import dev.abu.screener_backend.monitoring.dto.OrderBookStatsResponse;
import dev.abu.screener_backend.monitoring.dto.OrderBookStatsResponse.OutlierBook;
import dev.abu.screener_backend.monitoring.dto.OrderBookStatsResponse.OutlierReport;
import dev.abu.screener_backend.monitoring.dto.ShardLoad;
import dev.abu.screener_backend.monitoring.dto.SortOrder;
import dev.abu.screener_backend.monitoring.dto.StatBlock;
import dev.abu.screener_backend.monitoring.dto.StateFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the monitoring responses from published {@link BookStats} plus the collector's derived
 * rates.
 *
 * <p>Runs on the Tomcat request thread and is deliberately trivial: collect ~1 000 sizes into a
 * {@code double[]}, sort once, take a few linear passes — comfortably sub-millisecond. No caching,
 * no {@code @Transactional}, no database. Every value it reads is an immutable snapshot already
 * published by the owning consumer thread, so it cannot race with the hot path.
 */
@Service
@RequiredArgsConstructor
public class OrderBookStatsService {

    /** A shard is flagged stale once its books were last published this many intervals ago. */
    private static final int STALE_SHARD_INTERVALS = 3;

    private final OrderBookStore store;
    private final OrderBookStatsCollector collector;
    private final MonitoringProperties props;
    private final DisruptorProperties disruptorProps;

    // --- GET /api/monitoring/orderbook ---

    public OrderBookStatsResponse fleetStats(Market market, StateFilter state, int bins,
                                             int outliers, HistogramDomain domain) {
        MonitoringProperties.Orderbook cfg = props.orderbook();
        CollectorSnapshot derived = collector.snapshot();
        long now = System.currentTimeMillis();

        List<BookStats> population = population(market);
        List<BookStats> sample = population.stream().filter(s -> state.matches(s.state())).toList();

        int binCount = bins > 0 ? bins : cfg.defaultHistogramBins();
        int namedOutliers = Math.min(Math.max(outliers, 0), cfg.maxOutliersListed());

        // --- totals ---
        Map<OrderBookState, Integer> byState = new EnumMap<>(OrderBookState.class);
        for (OrderBookState s : OrderBookState.values()) byState.put(s, 0);
        Map<Market, Integer> byMarketCount = new EnumMap<>(Market.class);
        long totalLevels = 0;
        int synced = 0;
        int emptyBooks = 0;
        for (BookStats s : population) {
            byState.merge(s.state(), 1, Integer::sum);
            byMarketCount.merge(s.market(), 1, Integer::sum);
            totalLevels += s.size();
            if (s.state() == OrderBookState.SYNCED) {
                synced++;
                if (s.size() == 0) emptyBooks++;
            }
        }

        Runtime rt = Runtime.getRuntime();
        var totals = new OrderBookStatsResponse.Totals(
                population.size(),
                byState,
                byMarketCount,
                population.isEmpty() ? 0.0 : DescriptiveStats.round((double) synced / population.size()),
                totalLevels,
                derived.fleetUpdatesPerSecond(),
                derived.fleetLevelVisitsPerSecond(),
                derived.staleBooks(),
                emptyBooks,
                derived.resyncsLastHour(),
                rt.totalMemory() - rt.freeMemory(),
                rt.maxMemory());

        // --- size distribution ---
        double[] sizes = sample.stream().mapToDouble(BookStats::size).toArray();
        Arrays.sort(sizes);
        StatBlock overall = DescriptiveStats.ofSorted(sizes);
        Histogram histogram = DescriptiveStats.histogram(sizes, binCount, overall.iqr(), domain);

        Map<Market, StatBlock> byMarket = new EnumMap<>(Market.class);
        Map<Market, StatBlock> updateRateByMarket = new EnumMap<>(Market.class);
        for (Market m : Market.values()) {
            if (market != null && market != m) continue;
            List<BookStats> ofMarket = sample.stream().filter(s -> s.market() == m).toList();

            double[] marketSizes = ofMarket.stream().mapToDouble(BookStats::size).toArray();
            Arrays.sort(marketSizes);
            byMarket.put(m, DescriptiveStats.ofSorted(marketSizes));

            double[] marketRates = ofMarket.stream()
                    .mapToDouble(s -> derived.rate(OrderBookStatsCollector.key(s)).updatesPerSecond())
                    .toArray();
            Arrays.sort(marketRates);
            updateRateByMarket.put(m, DescriptiveStats.ofSorted(marketRates));
        }

        OutlierReport sizeOutliers = outlierReport(sample, overall, namedOutliers);

        // --- update rate distribution ---
        double[] rates = sample.stream()
                .mapToDouble(s -> derived.rate(OrderBookStatsCollector.key(s)).updatesPerSecond())
                .toArray();
        Arrays.sort(rates);
        StatBlock updateRate = DescriptiveStats.ofSorted(rates);

        return new OrderBookStatsResponse(
                Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC),
                sampleAgeMs(population, now),
                staleShards(now, cfg.publishIntervalMs()),
                totals,
                overall,
                byMarket,
                histogram,
                sizeOutliers,
                updateRate,
                updateRateByMarket,
                shardLoad(population, derived, now));
    }

    /**
     * Per-shard load over the (market-filtered) population, using the same
     * {@code Math.abs(symbol.hashCode()) % shardCount} mapping as
     * {@code DisruptorShardManager.getRingBuffer}.
     *
     * <p>Reflects whatever {@code market} filter the caller applied, exactly like {@code totals}.
     * For a true picture of consumer-thread balance, omit {@code market} — a shard's thread serves
     * both markets and half of it is not a load figure.
     */
    private List<ShardLoad> shardLoad(List<BookStats> population, CollectorSnapshot derived, long now) {
        int shardCount = Math.max(1, disruptorProps.shardCount());

        int[] books = new int[shardCount];
        long[] levels = new long[shardCount];
        double[] updates = new double[shardCount];
        double[] visits = new double[shardCount];
        String[] largest = new String[shardCount];
        int[] largestSize = new int[shardCount];
        long[] newestPublish = new long[shardCount];

        for (BookStats s : population) {
            int shard = Math.abs(s.symbol().hashCode()) % shardCount;
            double rate = derived.rate(OrderBookStatsCollector.key(s)).updatesPerSecond();

            books[shard]++;
            levels[shard] += s.size();
            updates[shard] += rate;
            visits[shard] += rate * s.size();
            if (s.size() > largestSize[shard]) {
                largestSize[shard] = s.size();
                largest[shard] = s.symbol() + "/" + s.market();
            }
            if (s.publishedAtMillis() > newestPublish[shard]) newestPublish[shard] = s.publishedAtMillis();
        }

        List<ShardLoad> out = new ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            out.add(new ShardLoad(
                    i,
                    books[i],
                    levels[i],
                    DescriptiveStats.round(updates[i]),
                    DescriptiveStats.round(visits[i]),
                    largest[i],
                    largestSize[i],
                    newestPublish[i] == 0L ? 0L : Math.max(0L, now - newestPublish[i])));
        }
        return out;
    }

    // --- GET /api/monitoring/orderbook/history ---

    /**
     * The collector's fleet trend ring, optionally trimmed to the most recent {@code minutes}.
     *
     * <p>Deliberately a separate endpoint: at its 2 880-point cap the ring is ~600 KB of JSON, far
     * more than everything in {@link #fleetStats} combined. A dashboard polling current fleet health
     * every few seconds should not carry a trend it redraws once a minute — pass {@code minutes} and
     * fetch only the window being drawn.
     *
     * <p>Always fleet-wide — the collector accumulates it on a schedule, with no request in scope, so
     * there is no {@code market} or {@code state} filter to honour.
     *
     * @param minutes trailing window; {@code 0} or less returns the whole ring
     */
    public OrderBookHistoryResponse history(int minutes) {
        MonitoringProperties.Orderbook cfg = props.orderbook();
        List<HistoryPoint> all = collector.snapshot().history();
        long now = System.currentTimeMillis();

        List<HistoryPoint> points = all;
        if (minutes > 0) {
            OffsetDateTime cutoff = Instant.ofEpochMilli(now - minutes * 60_000L).atOffset(ZoneOffset.UTC);
            int from = 0;
            while (from < all.size() && all.get(from).at().isBefore(cutoff)) from++;
            points = all.subList(from, all.size());
        }

        return new OrderBookHistoryResponse(
                Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC),
                cfg.historyIntervalMs(),
                cfg.historyWindowMs(),
                all.size(),
                points.size(),
                List.copyOf(points));
    }

    // --- GET /api/monitoring/orderbook/books ---

    public OrderBookListResponse books(Market market, StateFilter state, String symbol,
                                       BookSort sort, SortOrder order, int limit) {
        MonitoringProperties.Orderbook cfg = props.orderbook();
        CollectorSnapshot derived = collector.snapshot();
        long now = System.currentTimeMillis();

        String wanted = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();

        List<BookStats> population = population(market);
        List<BookRow> rows = new ArrayList<>();
        for (BookStats s : population) {
            if (!state.matches(s.state())) continue;
            if (wanted != null && !wanted.equals(s.symbol())) continue;
            BookRate rate = derived.rate(OrderBookStatsCollector.key(s));
            rows.add(new BookRow(
                    s.symbol(), s.market(), s.state(),
                    s.size(), s.bids(), s.asks(),
                    s.diffsApplied(), DescriptiveStats.round(rate.updatesPerSecond()),
                    rate.idleMs(), s.resyncs()));
        }

        rows.sort(comparator(sort, order));

        int effectiveLimit = Math.min(Math.max(limit, 1), cfg.maxBooksReturned());
        List<BookRow> page = rows.size() > effectiveLimit ? rows.subList(0, effectiveLimit) : rows;

        return new OrderBookListResponse(
                Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC),
                sampleAgeMs(population, now),
                rows.size(),
                page.size(),
                List.copyOf(page));
    }

    // --- helpers ---

    private List<BookStats> population(Market market) {
        List<BookStats> out = new ArrayList<>(store.size());
        for (OrderBook book : store.books()) {
            BookStats stats = book.getStats();
            if (market == null || stats.market() == market) out.add(stats);
        }
        return out;
    }

    /** Age of the <b>newest</b> publication across the population; 0 when nothing has published. */
    private static long sampleAgeMs(List<BookStats> population, long now) {
        long newest = 0L;
        for (BookStats s : population) {
            if (s.publishedAtMillis() > newest) newest = s.publishedAtMillis();
        }
        return newest == 0L ? 0L : Math.max(0L, now - newest);
    }

    /**
     * Shards whose books were last published longer ago than {@link #STALE_SHARD_INTERVALS} publish
     * intervals — i.e. shards whose consumer thread is not clearing the publish gate, typically
     * because it is receiving (almost) no events. Their statistics are frozen, and this is how that
     * is surfaced rather than hidden.
     *
     * <p>Ownership uses the same mapping as {@code DisruptorShardManager.getRingBuffer}.
     */
    private List<Integer> staleShards(long now, long publishIntervalMs) {
        int shardCount = disruptorProps.shardCount();
        long[] newestPerShard = new long[shardCount];
        boolean[] hasBooks = new boolean[shardCount];

        for (OrderBook book : store.books()) {
            int shard = Math.abs(book.getSymbol().hashCode()) % shardCount;
            hasBooks[shard] = true;
            long published = book.getStats().publishedAtMillis();
            if (published > newestPerShard[shard]) newestPerShard[shard] = published;
        }

        long threshold = publishIntervalMs * STALE_SHARD_INTERVALS;
        List<Integer> stale = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            if (hasBooks[i] && now - newestPerShard[i] > threshold) stale.add(i);
        }
        return stale;
    }

    private static OutlierReport outlierReport(List<BookStats> sample, StatBlock stats, int maxNamed) {
        if (stats.n() == 0 || stats.iqr() == null) {
            return new OutlierReport(null, null, null, null, 0, 0, 0, 0.0, 0, List.of(), List.of());
        }

        double iqr = stats.iqr();
        double lowerFence = stats.p25() - DescriptiveStats.OUTLIER_FACTOR * iqr;
        double upperFence = stats.p75() + DescriptiveStats.OUTLIER_FACTOR * iqr;
        double extremeLower = stats.p25() - DescriptiveStats.EXTREME_FACTOR * iqr;
        double extremeUpper = stats.p75() + DescriptiveStats.EXTREME_FACTOR * iqr;

        List<BookStats> low = new ArrayList<>();
        List<BookStats> high = new ArrayList<>();
        int extremeCount = 0;
        for (BookStats s : sample) {
            int size = s.size();
            if (size < lowerFence) low.add(s);
            else if (size > upperFence) high.add(s);
            if (size < extremeLower || size > extremeUpper) extremeCount++;
        }

        low.sort(Comparator.comparingInt(BookStats::size));                        // smallest first
        high.sort(Comparator.comparingInt(BookStats::size).reversed());            // largest first

        int count = low.size() + high.size();
        return new OutlierReport(
                DescriptiveStats.round(lowerFence),
                DescriptiveStats.round(upperFence),
                DescriptiveStats.round(extremeLower),
                DescriptiveStats.round(extremeUpper),
                count,
                low.size(),
                high.size(),
                DescriptiveStats.round((double) count / stats.n()),
                extremeCount,
                named(low, maxNamed),
                named(high, maxNamed));
    }

    private static List<OutlierBook> named(List<BookStats> books, int maxNamed) {
        return books.stream()
                .limit(maxNamed)
                .map(s -> new OutlierBook(s.symbol(), s.market(), s.size(), s.state()))
                .toList();
    }

    private static Comparator<BookRow> comparator(BookSort sort, SortOrder order) {
        Comparator<BookRow> c = switch (sort) {
            case SIZE -> Comparator.comparingInt(BookRow::size);
            case UPDATE_RATE -> Comparator.comparingDouble(BookRow::updatesPerSecond);
            case IDLE -> Comparator.comparingLong(BookRow::idleMs);
            case RESYNCS -> Comparator.comparingInt(BookRow::resyncs);
            case SYMBOL -> Comparator.comparing(BookRow::symbol);
        };
        // Stable, deterministic ties: symbol then market.
        c = c.thenComparing(BookRow::symbol).thenComparing(BookRow::market);
        return order == SortOrder.DESC ? c.reversed() : c;
    }
}
