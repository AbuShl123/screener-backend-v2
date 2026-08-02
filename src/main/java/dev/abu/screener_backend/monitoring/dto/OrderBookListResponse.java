package dev.abu.screener_backend.monitoring.dto;

import dev.abu.screener_backend.binance.orderbook.OrderBookState;
import dev.abu.screener_backend.binance.websocket.Market;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response body for {@code GET /api/monitoring/orderbook/books} — the per-book table behind the
 * fleet statistics. This is the raw material for client-side plotting and the drill-down for
 * update-frequency questions.
 *
 * <p>The payload is bounded by book <i>count</i>, not by level count: the whole fleet at
 * {@code limit=2000} is ~1 000 rows ≈ 200 KB.
 *
 * @param generatedAt   when the response was assembled
 * @param sampleAgeMs   age of the newest shard publication
 * @param totalMatching rows matching the filters before {@code limit} was applied
 * @param returned      rows actually returned
 * @param books         the rows, ordered by {@code sort} / {@code order}
 */
public record OrderBookListResponse(
        OffsetDateTime generatedAt,
        long sampleAgeMs,
        int totalMatching,
        int returned,
        List<BookRow> books
) {

    /**
     * @param symbol           ticker symbol
     * @param market           SPOT or FUTURES
     * @param state            sync state at the last publish
     * @param size             {@code bids + asks}
     * @param bids             bid level count
     * @param asks             ask level count
     * @param updates          cumulative diffs applied since JVM start
     * @param updatesPerSecond EWMA-smoothed diffs/second
     * @param idleMs           time since {@code updates} was last <i>observed</i> to change.
     *                         Resolution is {@code sample-interval-ms}, not milliseconds — that is
     *                         why it is {@code idleMs} and not {@code lastUpdateAgeMs}
     * @param resyncs          cumulative resyncs since JVM start
     */
    public record BookRow(
            String symbol,
            Market market,
            OrderBookState state,
            int size,
            int bids,
            int asks,
            long updates,
            double updatesPerSecond,
            long idleMs,
            int resyncs
    ) {}
}
