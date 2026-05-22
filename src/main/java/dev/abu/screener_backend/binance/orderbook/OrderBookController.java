package dev.abu.screener_backend.binance.orderbook;

import dev.abu.screener_backend.binance.websocket.Market;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class OrderBookController {

    private final OrderBookStore store;

    /**
     * Debug endpoint — returns the current state of a local orderbook.
     *
     * <p>Example: {@code GET /api/screener/orderbook?symbol=BTCUSDT&market=FUTURES}
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
