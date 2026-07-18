package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.analysis.UserFeedRegistry;
import dev.abu.screener_backend.analysis.UserFeedRegistry.UserPresence;
import dev.abu.screener_backend.binance.orderbook.OrderBook;
import dev.abu.screener_backend.binance.orderbook.OrderBookState;
import dev.abu.screener_backend.binance.orderbook.OrderBookStore;
import dev.abu.screener_backend.binance.orderbook.PriceLevelEntry;
import dev.abu.screener_backend.binance.websocket.Market;
import dev.abu.screener_backend.monitoring.dto.UsageReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Operational / monitoring endpoints used to inspect and debug the running screener.
 *
 * <p>This controller groups the read-only "how is the screener doing right now" endpoints under
 * {@code /api/monitoring}:
 * <ul>
 *   <li>{@code GET /api/monitoring/presence} — live WebSocket presence (who is connected right now)</li>
 *   <li>{@code GET /api/monitoring/orderbook} — the current state of a single local orderbook</li>
 *   <li>{@code GET /api/monitoring/usage} — persisted connection activity aggregated into time-slices</li>
 * </ul>
 *
 * <p>{@code /presence} and {@code /orderbook} are instantaneous, in-memory reads with no persistence
 * and no history. {@code /usage} is the persisted counterpart — it aggregates the append-only
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
    private final OrderBookStore store;
    private final ConnectionUsageService connectionUsageService;

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
     * A bare {@code GET /api/monitoring/usage} reports today (in {@code zone}) in 30-minute slices.
     *
     * @param start inclusive calendar date in {@code zone}; omitted → today in {@code zone}
     * @param end   inclusive calendar date in {@code zone}; omitted → today in {@code zone}
     * @param slice ISO-8601 slice duration ({@code >= PT1M}), e.g. {@code PT30M}, {@code PT1H}, {@code P7D}
     * @param zone  IANA zone id — places slice boundaries at local :00/:30/midnight and labels the output
     */
    @GetMapping("/usage")
    public UsageReportResponse getUsage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue = "PT30M") String slice,
            @RequestParam(defaultValue = "Asia/Tashkent") String zone) {
        return connectionUsageService.report(start, end, slice, zone);
    }

    /**
     * Debug endpoint — returns the current state of a local orderbook.
     *
     * <p>Example: {@code GET /api/monitoring/orderbook?symbol=BTCUSDT&market=FUTURES}
     *
     * <p>Reads are best-effort: bids/asks may be slightly stale if a consumer write
     * is concurrent, which is acceptable for debugging purposes.
     *
     * @param symbol ticker symbol (case-insensitive)
     * @param market SPOT or FUTURES
     * @return 200 with orderbook snapshot, or 404 if no book exists for the pair
     */
    @GetMapping("/orderbook")
    public ResponseEntity<OrderBookResponse> getOrderBook(
            @RequestParam String symbol,
            @RequestParam Market market) {

        OrderBook book = store.get(symbol.toUpperCase(), market);
        if (book == null) {
            return ResponseEntity.notFound().build();
        }

        long now = System.currentTimeMillis();
        TreeMap<Double, PriceLevelEntry> bids = book.snapshotBids();
        TreeMap<Double, PriceLevelEntry> asks = book.snapshotAsks();

        List<LevelView> bidList = bids.entrySet().stream()
                .map(e -> new LevelView(e.getKey(), e.getValue().quantity, e.getValue().distance, now - e.getValue().firstSeenMillis))
                .toList();
        List<LevelView> askList = asks.entrySet().stream()
                .map(e -> new LevelView(e.getKey(), e.getValue().quantity, e.getValue().distance, now - e.getValue().firstSeenMillis))
                .toList();

        return ResponseEntity.ok(new OrderBookResponse(
                symbol.toUpperCase(), market, book.getState(),
                bidList.size(), askList.size(),
                bidList, askList
        ));
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

    public record LevelView(double price, double qty, double distance, long lifetimeMs) {}

    public record OrderBookResponse(
            String symbol,
            Market market,
            OrderBookState state,
            int bidCount,
            int askCount,
            List<LevelView> bids,
            List<LevelView> asks
    ) {}
}
