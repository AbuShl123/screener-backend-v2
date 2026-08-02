package dev.abu.screener_backend.monitoring.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response body for {@code GET /api/monitoring/orderbook/history} — the fleet-level trend ring.
 *
 * <p>Split out of {@code GET /api/monitoring/orderbook} deliberately. The ring is bounded
 * ({@code history-size} points at {@code history-interval-ms}, 24 hours by default) but at its cap it
 * is ~600 KB of JSON — far more than every statistic in the fleet response combined. A dashboard
 * polling "what does the fleet look like right now" should not pay for a trend it redraws once a
 * minute. Use {@code ?minutes=N} to fetch only the window you are about to draw.
 *
 * <p><b>The history is always fleet-wide.</b> It is accumulated by the collector on a fixed schedule
 * with no knowledge of a request, so unlike the fleet-statistics endpoint it honours no
 * {@code market} or {@code state} filter. Do not overlay it on a filtered stat block and expect the
 * two to agree.
 *
 * @param generatedAt     when the response was assembled
 * @param pointIntervalMs spacing between consecutive points
 *                        ({@code screener.monitoring.orderbook.history-interval-ms}). Note this is
 *                        <i>not</i> {@code sample-interval-ms}: the collector samples faster than it
 *                        records history
 * @param windowMs        the ring's full capacity in wall-clock time — points older than this are evicted
 * @param available       points currently retained in the ring
 * @param returned        points actually returned after the {@code minutes} window was applied
 * @param points          the points, oldest → newest
 */
public record OrderBookHistoryResponse(
        OffsetDateTime generatedAt,
        long pointIntervalMs,
        long windowMs,
        int available,
        int returned,
        List<HistoryPoint> points
) {

    /**
     * One fleet-level sample appended by the collector every {@code history-interval-ms}.
     *
     * @param at                    sample time
     * @param books                 total books
     * @param synced                books in SYNCED
     * @param totalLevels           Σ size
     * @param meanSize              mean size over SYNCED books; {@code null} before any book has synced
     * @param medianSize            median size over SYNCED books; {@code null} before any book has synced
     * @param updatesPerSecond      fleet diffs/second
     * @param levelVisitsPerSecond  Σ {@code size × updatesPerSecond} — the price levels the mid-price
     *                              sweep walks per second across the fleet. This, not message count,
     *                              is what the consumer threads actually spend their time on, and it
     *                              is the number to extrapolate when sizing for more exchanges
     * @param staleBooks            stale SYNCED books at that instant
     * @param usedHeapBytes         JVM used heap ({@code totalMemory - freeMemory}) at sample time.
     *                              Sawtooths with GC, so read the <i>lower envelope</i> across many
     *                              points as the live-set estimate — regressing it against
     *                              {@code totalLevels} gives measured bytes-per-level
     */
    public record HistoryPoint(
            OffsetDateTime at,
            int books,
            int synced,
            long totalLevels,
            Double meanSize,
            Double medianSize,
            double updatesPerSecond,
            double levelVisitsPerSecond,
            int staleBooks,
            long usedHeapBytes
    ) {}
}
