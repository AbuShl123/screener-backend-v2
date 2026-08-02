package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.analysis.UserFeedRegistry;
import dev.abu.screener_backend.analysis.UserFeedRegistry.UserPresence;
import dev.abu.screener_backend.binance.websocket.Market;
import dev.abu.screener_backend.monitoring.dto.BookSort;
import dev.abu.screener_backend.monitoring.dto.HistogramDomain;
import dev.abu.screener_backend.monitoring.dto.OrderBookHistoryResponse;
import dev.abu.screener_backend.monitoring.dto.OrderBookListResponse;
import dev.abu.screener_backend.monitoring.dto.OrderBookStatsResponse;
import dev.abu.screener_backend.monitoring.dto.SortOrder;
import dev.abu.screener_backend.monitoring.dto.StateFilter;
import dev.abu.screener_backend.monitoring.dto.UsageReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Operational / monitoring endpoints used to inspect and debug the running screener.
 *
 * <p>This controller groups the read-only "how is the screener doing right now" endpoints under
 * {@code /api/monitoring}:
 * <ul>
 *   <li>{@code GET /api/monitoring/presence} — live WebSocket presence (who is connected right now)</li>
 *   <li>{@code GET /api/monitoring/orderbook} — descriptive statistics over the whole orderbook fleet</li>
 *   <li>{@code GET /api/monitoring/orderbook/history} — the fleet-level trend ring behind them</li>
 *   <li>{@code GET /api/monitoring/orderbook/books} — the per-book table behind those statistics</li>
 *   <li>{@code GET /api/monitoring/usage} — persisted connection activity aggregated into time-slices</li>
 * </ul>
 *
 * <p>{@code /presence} and the {@code /orderbook} family are in-memory reads with no persistence.
 * The orderbook endpoints never touch a live orderbook: they read immutable {@code BookStats}
 * snapshots published by the owning Disruptor consumer thread, which is why they carry
 * {@code sampleAgeMs}. {@code /usage} is the persisted counterpart — it aggregates the append-only
 * {@code connection_events} log (written on each successful, entitled WebSocket open) into
 * distinct-user counts per time-slice over a date range.
 *
 * <p>ADMIN-only: {@code /api/monitoring/**} is gated by {@code hasRole("ADMIN")} in
 * {@code SecurityConfig}. A Bearer JWT carrying the {@code ROLE_ADMIN} authority is required; any
 * other authenticated user receives {@code 403}.
 */
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final UserFeedRegistry userFeedRegistry;
    private final ConnectionUsageService connectionUsageService;
    private final OrderBookStatsService orderBookStatsService;

    /**
     * Returns the set of currently-connected users with their open session counts.
     *
     * <p>Reads the in-memory session registry ({@link UserFeedRegistry}); no database access and no
     * persistence. This reflects the instant the request is served only — it carries no history. For
     * connection frequency / historical usage a separate persisted module would be required.
     *
     * @return online user count, total session count, and the per-user breakdown
     */
    @GetMapping("/presence")
    public PresenceResponse getPresence() {
        List<UserPresence> users = userFeedRegistry.presenceSnapshot();
        int totalSessions = users.stream().mapToInt(UserPresence::sessions).sum();
        List<UserPresence> sorted = users.stream()
                .sorted(Comparator.comparingInt(UserPresence::sessions).reversed())
                .toList();
        return new PresenceResponse(sorted.size(), totalSessions, sorted);
    }

    /**
     * Persisted usage report — aggregates {@code connection_events} into distinct-user counts per
     * time-slice over an inclusive date range. Distinct from live {@code /presence}: this reads history.
     *
     * <p>Example: {@code GET /api/monitoring/usage?start=2026-07-18&end=2026-07-18&slice=PT30M&zone=Asia/Tashkent}.
     * A bare {@code GET /api/monitoring/usage} reports today (in {@code zone}) in 1-hour slices.
     *
     * @param start inclusive calendar date in {@code zone}; omitted → today in {@code zone}
     * @param end   inclusive calendar date in {@code zone}; omitted → today in {@code zone}
     * @param slice ISO-8601 slice duration ({@code >= PT1M}), e.g. {@code PT30M}, {@code PT1H}, {@code P7D};
     *              or the calendar literals {@code P1M} / {@code P1Y} for true calendar-month / -year
     *              buckets (months and years aren't fixed-length, so a duration can't express them)
     * @param zone  IANA zone id — places slice boundaries at local :00/:30/midnight and labels the output
     */
    @GetMapping("/usage")
    public UsageReportResponse getUsage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue = "PT1H") String slice,
            @RequestParam(defaultValue = "Asia/Tashkent") String zone) {
        return connectionUsageService.report(start, end, slice, zone);
    }

    /**
     * Descriptive statistics over the whole local orderbook fleet — distribution of book sizes, sync
     * health, update frequency, staleness, and histogram-ready buckets.
     *
     * <p>Example: {@code GET /api/monitoring/orderbook?market=FUTURES&state=ALL&bins=30}
     *
     * <p>"Size" always means {@code bids + asks}, never one side. The response carries the same stat
     * block three times — {@code overall} plus {@code byMarket.SPOT} / {@code byMarket.FUTURES} —
     * because spot and futures books are fed at different cadences with different depth
     * characteristics and their medians differ by roughly 3×. Trust {@code byMarket}; and read
     * {@code median}/{@code iqr} rather than {@code mean}/{@code stdDev}, which a skewness above 10
     * renders close to meaningless.
     *
     * @param market restrict the whole population to one market; omitted = all books. Omit it to
     *               read {@code byShard}, which is only a load figure when both markets are counted
     * @param state  which books enter the size sample. Default {@code SYNCED}, because a
     *               {@code PENDING} book holds zero levels and would drag every statistic toward
     *               zero. State <i>counts</i> in the response always cover all books
     * @param bins   histogram bin count; {@code 0} = choose by Freedman–Diaconis. Clamped to [5, 100]
     * @param outliers max named outliers per tail ({@code 0} = counts only), capped by
     *               {@code screener.monitoring.orderbook.max-outliers-listed}
     * @param domain how far right the histogram buckets reach — {@code P99} (default), {@code FENCE}
     *               or {@code FULL}. Book size is heavy-tailed enough that {@code FULL} spends most
     *               of its bins on empty space; anything past the bound is reported as
     *               {@code histogram.overflowCount} rather than dropped
     */
    @GetMapping("/orderbook")
    public OrderBookStatsResponse getOrderBookStats(
            @RequestParam(required = false) Market market,
            @RequestParam(defaultValue = "SYNCED") StateFilter state,
            @RequestParam(defaultValue = "0") int bins,
            @RequestParam(defaultValue = "10") int outliers,
            @RequestParam(defaultValue = "P99") HistogramDomain domain) {
        return orderBookStatsService.fleetStats(market, state, bins, outliers, domain);
    }

    /**
     * The per-book table behind {@code /orderbook} — every matching book's size, update rate, idle
     * time and resync count. This is the raw material for client-side plotting and the drill-down
     * for update-frequency questions.
     *
     * <p>Example: {@code GET /api/monitoring/orderbook/books?sort=IDLE&order=DESC&limit=50}. The
     * whole fleet is available with {@code limit=2000&sort=SYMBOL}: the payload is bounded by book
     * count (~1 000 rows ≈ 200 KB), not by level count.
     *
     * @param market restrict to one market; omitted = all books
     * @param state  {@code ALL} or a specific sync state; default {@code SYNCED}
     * @param symbol exact symbol filter (case-insensitive); omitted = all symbols
     * @param sort   sort key — {@code SIZE} (default), {@code UPDATE_RATE}, {@code IDLE},
     *               {@code RESYNCS}, {@code SYMBOL}
     * @param order  {@code DESC} (default) or {@code ASC}
     * @param limit  max rows, clamped to {@code screener.monitoring.orderbook.max-books-returned}
     */
    /**
     * The fleet-level trend ring — one point every
     * {@code screener.monitoring.orderbook.history-interval-ms} (30 s by default), bounded to
     * {@code history-size} points (2 880 = 24 hours).
     *
     * <p>Example: {@code GET /api/monitoring/orderbook/history?minutes=15}
     *
     * <p>Note the points are spaced at {@code history-interval-ms}, <b>not</b> the faster
     * {@code sample-interval-ms} the collector uses to derive rates and staleness. The response
     * reports the real spacing as {@code pointIntervalMs}.
     *
     * <p>This lives apart from {@code /orderbook} on purpose: at its cap the ring is ~600 KB of JSON,
     * far more than every statistic in the fleet response combined. Fetch only the window a chart
     * actually needs.
     *
     * <p>The history is <b>always fleet-wide</b> — the collector accumulates it on a schedule with no
     * request in scope, so it honours no {@code market} or {@code state} filter. Don't overlay it on
     * a filtered stat block and expect the two to agree.
     *
     * @param minutes trailing window to return; {@code 0} or less returns the whole retained ring
     */
    @GetMapping("/orderbook/history")
    public OrderBookHistoryResponse getOrderBookHistory(
            @RequestParam(defaultValue = "60") int minutes) {
        return orderBookStatsService.history(minutes);
    }

    @GetMapping("/orderbook/books")
    public OrderBookListResponse getOrderBooks(
            @RequestParam(required = false) Market market,
            @RequestParam(defaultValue = "SYNCED") StateFilter state,
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "SIZE") BookSort sort,
            @RequestParam(defaultValue = "DESC") SortOrder order,
            @RequestParam(defaultValue = "200") int limit) {
        return orderBookStatsService.books(market, state, symbol, sort, order, limit);
    }

    /**
     * Response body for {@code GET /api/monitoring/presence}.
     *
     * @param onlineUsers   number of distinct connected users
     * @param totalSessions total open WebSocket sessions across all users
     * @param users         per-user breakdown, sorted by session count descending
     */
    public record PresenceResponse(
            int onlineUsers,
            int totalSessions,
            List<UserPresence> users
    ) {}
}
