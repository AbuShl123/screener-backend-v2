package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.analysis.UserFeedRegistry;
import dev.abu.screener_backend.analysis.UserFeedRegistry.UserPresence;
import dev.abu.screener_backend.binance.orderbook.OrderBook;
import dev.abu.screener_backend.binance.orderbook.OrderBookState;
import dev.abu.screener_backend.binance.orderbook.OrderBookStore;
import dev.abu.screener_backend.binance.orderbook.PriceLevelEntry;
import dev.abu.screener_backend.binance.websocket.Market;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 * </ul>
 *
 * <p>All endpoints are instantaneous, in-memory reads with no persistence and no history. They are
 * intended for development and operational verification. Future work (per the project plan) will add
 * persisted usage metrics — e.g. active-connection counts over time and last-access timestamps —
 * alongside these live views.
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
