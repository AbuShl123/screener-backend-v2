package dev.abu.screener_backend.monitoring.dto;

import dev.abu.screener_backend.binance.orderbook.OrderBookState;
import dev.abu.screener_backend.binance.websocket.Market;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Response body for {@code GET /api/monitoring/orderbook} — descriptive statistics over the whole
 * local orderbook fleet.
 *
 * <h3>Three caveats to read before interpreting the numbers</h3>
 * <ol>
 *   <li><b>{@code overall} pools two populations.</b> Spot and futures books are fed by different
 *       streams at different cadences (1 s vs 500 ms) with different depth characteristics, so the
 *       pooled sample mixes two clusters whose medians differ by roughly 3×. {@code byMarket} is
 *       returned unconditionally and is the block to trust. Note however that splitting does
 *       <i>not</i> remove the skew: each market is separately, heavily right-skewed, because
 *       liquidity across symbols is itself heavy-tailed.</li>
 *   <li><b>Size is bounded by the price filter, not by snapshot depth.</b> The REST snapshot that
 *       seeds a book carries at most 1 000 levels per side, but that is only the seed — live diffs
 *       insert new price levels indefinitely, and the only thing that removes one is the mid-price
 *       sweep discarding it outside ±{@code screener.orderbook.price-filter-threshold}. Books
 *       therefore routinely exceed 2 000 levels (the largest observed was ~13 900) and grow until
 *       the band saturates. There is no upper censoring and no manufactured left skew.</li>
 *   <li><b>The mean and standard deviation are close to useless here.</b> With skewness above 10, σ
 *       is inflated several-fold by a handful of books — compare {@code stdDev} against
 *       {@code mad × 1.4826} and the gap is typically 3–5×. Read {@code median} and {@code iqr};
 *       treat {@code mean ± stdDev} as meaningless.</li>
 * </ol>
 *
 * <p>"Size" always means {@code bids + asks} — never one side.
 *
 * @param generatedAt       when the response was assembled
 * @param sampleAgeMs       age of the newest shard publication; statistics can be at most roughly
 *                          this stale (see {@code shardsStale} for the pessimistic case)
 * @param shardsStale       shard indexes whose books were last published longer ago than 3× the
 *                          publish interval — their numbers are frozen, typically because the shard
 *                          is receiving (almost) no events. Empty is the healthy case
 * @param totals            fleet counts and health
 * @param overall           stat block over book size across the whole sample
 * @param byMarket          the same stat block per market — the honest version, see caveat 1
 * @param histogram         server-computed histogram of book size; bucketed over a bounded domain
 *                          with an explicit overflow, see {@link HistogramDomain}
 * @param sizeOutliers      Tukey-fence outlier report over book size
 * @param updateRate        stat block over per-book {@code updatesPerSecond}, pooled
 * @param updateRateByMarket the same per market. <b>Prefer this to {@code updateRate}</b>: spot and
 *                          futures have different hard ceilings (1/s and 2/s), so the pooled block
 *                          is a mixture of two differently-truncated populations and its
 *                          {@code shape} in particular means nothing
 * @param byShard           per-shard load — the only view of whether the hash-based ticker→shard
 *                          mapping is balancing actual work, see {@link ShardLoad}
 */
public record OrderBookStatsResponse(
        OffsetDateTime generatedAt,
        long sampleAgeMs,
        List<Integer> shardsStale,
        Totals totals,
        StatBlock overall,
        Map<Market, StatBlock> byMarket,
        Histogram histogram,
        OutlierReport sizeOutliers,
        StatBlock updateRate,
        Map<Market, StatBlock> updateRateByMarket,
        List<ShardLoad> byShard
) {

    /**
     * Fleet-wide counts. State counts cover <b>all</b> books regardless of the {@code state} filter,
     * which only decides which books enter the size sample.
     *
     * @param books                books in the (market-filtered) fleet
     * @param byState              count per sync state
     * @param byMarket             count per market
     * @param syncedRatio          {@code SYNCED / books}
     * @param totalLevels          Σ size over every book — the driver of the pipeline's memory
     *                             footprint, and the number to extrapolate when adding exchanges
     * @param updatesPerSecond     diffs applied per second across the fleet (EWMA-smoothed)
     * @param levelVisitsPerSecond Σ {@code size × updatesPerSecond} across the fleet. <b>The
     *                             pipeline's real unit of work.</b> Every applied diff makes the
     *                             mid-price sweep walk that book's entire level set, so cost scales
     *                             with this product — not with message count, which understates the
     *                             load on liquid symbols by orders of magnitude
     * @param staleBooks           SYNCED books whose {@code idleMs} exceeds {@code stale-threshold-ms}.
     *                             Calibrate that threshold against real traffic before reading this
     *                             as a fault count — quiet symbols legitimately go minutes without a diff
     * @param emptyBooks           SYNCED books holding zero levels
     * @param resyncsLastHour      resyncs observed across the fleet in the last hour
     * @param usedHeapBytes        JVM used heap ({@code totalMemory - freeMemory}) at request time.
     *                             A single reading sits somewhere on the GC sawtooth and means
     *                             little on its own — for a live-set estimate take the lower envelope
     *                             of the same field across the history ring and regress it against
     *                             {@code totalLevels}
     * @param maxHeapBytes         {@code -Xmx}; the headroom {@code usedHeapBytes} is consuming
     */
    public record Totals(
            int books,
            Map<OrderBookState, Integer> byState,
            Map<Market, Integer> byMarket,
            double syncedRatio,
            long totalLevels,
            double updatesPerSecond,
            double levelVisitsPerSecond,
            int staleBooks,
            int emptyBooks,
            long resyncsLastHour,
            long usedHeapBytes,
            long maxHeapBytes
    ) {}

    /**
     * Tukey-fence outlier report over book size.
     *
     * @param lowerFence        {@code p25 - 1.5·iqr}
     * @param upperFence        {@code p75 + 1.5·iqr}
     * @param extremeLowerFence {@code p25 - 3·iqr}
     * @param extremeUpperFence {@code p75 + 3·iqr}
     * @param count             books outside the plain fences
     * @param lowCount          of those, below {@code lowerFence}
     * @param highCount         of those, above {@code upperFence}
     * @param fraction          {@code count / n}
     * @param extremeCount      books outside the extreme fences
     * @param low               the named low offenders, smallest first (capped by {@code outliers})
     * @param high              the named high offenders, largest first (capped by {@code outliers})
     */
    public record OutlierReport(
            Double lowerFence,
            Double upperFence,
            Double extremeLowerFence,
            Double extremeUpperFence,
            int count,
            int lowCount,
            int highCount,
            double fraction,
            int extremeCount,
            List<OutlierBook> low,
            List<OutlierBook> high
    ) {}

    /** One named outlier. */
    public record OutlierBook(String symbol, Market market, int size, OrderBookState state) {}
}
