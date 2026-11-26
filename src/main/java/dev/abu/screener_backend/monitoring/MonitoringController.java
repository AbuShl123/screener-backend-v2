package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.analysis.UserFeedRegistry;
import dev.abu.screener_backend.analysis.UserFeedRegistry.UserPresence;
import dev.abu.screener_backend.analysis.cluster.OrderCluster;
import dev.abu.screener_backend.feed.cluster.ClusterFeedStore;
import dev.abu.screener_backend.feed.cluster.ClusterUpdate;
import dev.abu.screener_backend.marketdata.InstrumentRegistry;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlotTable;
import dev.abu.screener_backend.marketdata.core.book.OrderBookState;
import dev.abu.screener_backend.marketdata.core.spike.SpikeAlert;
import dev.abu.screener_backend.marketdata.core.spike.SpikeAlertStore;
import dev.abu.screener_backend.marketdata.core.spike.SpikeKind;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlert;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlertStore;
import dev.abu.screener_backend.marketdata.core.trade.TradeAlertStore.SymbolCount;
import dev.abu.screener_backend.monitoring.dto.UsageReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Operational / monitoring endpoints used to inspect and debug the running screener.
 *
 * <p>This controller groups the read-only "how is the screener doing right now" endpoints under
 * {@code /api/monitoring}:
 * <ul>
 *   <li>{@code GET /api/monitoring/presence} — live WebSocket presence (who is connected right now)</li>
 *   <li>{@code GET /api/monitoring/orderbook} — the current state of a single local orderbook</li>
 *   <li>{@code GET /api/monitoring/usage} — persisted connection activity aggregated into time-slices</li>
 *   <li>{@code GET /api/monitoring/trade-alerts} and {@code /trade-alerts/summary} — recent
 *       large-trade alerts and the alert count per symbol since startup, for threshold tuning</li>
 *   <li>{@code GET /api/monitoring/clusters} — the order clusters detected right now, whether or not
 *       they are delivered ({@code screener.clusters.delivery-enabled}), for threshold tuning</li>
 *   <li>{@code GET /api/monitoring/spike-alerts} and {@code /spike-alerts/summary} — recent price and
 *       volume spikes, whether or not they are delivered ({@code screener.spike-alerts.delivery-enabled}),
 *       and the count per symbol and kind since startup, for threshold tuning</li>
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
    private final InstrumentRegistry instrumentRegistry;
    private final InstrumentSlotTable slots;
    private final ConnectionUsageService connectionUsageService;
    private final TradeAlertStore tradeAlertStore;
    private final ClusterFeedStore clusterFeedStore;
    private final SpikeAlertStore spikeAlertStore;

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
     * Debug endpoint — returns the current state of a local orderbook.
     *
     * <p>Example: {@code GET /api/monitoring/orderbook?symbol=BTCUSDT&market=FUTURES}
     *
     * <p>Reads are best-effort: bids/asks may be slightly stale if a consumer write
     * is concurrent, which is acceptable for debugging purposes. Note the pre-existing hazard that
     * {@code snapshotBids()} copies a {@code TreeMap} a consumer thread may be mutating, so this
     * endpoint can throw {@code ConcurrentModificationException}; fixing it belongs with a proper
     * storage accessor seam on {@code OrderBook}, not here.
     *
     * @param symbol ticker symbol (case-insensitive)
     * @param market SPOT or FUTURES
     * @return 200 with orderbook snapshot, or 404 if no book exists for the pair
     */
    @GetMapping("/orderbook")
    public ResponseEntity<String> getOrderBook(
            @RequestParam String symbol,
            @RequestParam Market market) {
        return ResponseEntity.ok("Endpoint abandoned for now");
    }

    /**
     * The most recent large-trade alerts, newest first. In memory only: the store keeps the last
     * {@code screener.trade-alerts.recent-capacity} alerts since startup.
     *
     * <p>Example: {@code GET /api/monitoring/trade-alerts?venue=BINANCE_FUTURES&symbol=ETHUSDT&limit=20}
     *
     * @param venue  only this venue; omitted → every venue
     * @param symbol only this native symbol, case-insensitive; omitted → every symbol
     * @param limit  at most this many alerts, clamped to {@code [1, recent-capacity]}
     */
    @GetMapping("/trade-alerts")
    public List<TradeAlertView> getTradeAlerts(
            @RequestParam(required = false) Venue venue,
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "100") int limit) {
        int clamped = Math.clamp(limit, 1, tradeAlertStore.capacity());
        String filter = symbol == null || symbol.isBlank() ? null : symbol.trim();
        return tradeAlertStore.recent(venue, filter, clamped).stream().map(TradeAlertView::of).toList();
    }

    /**
     * Alert count per venue and native symbol since startup, most alerts first: which symbols fire
     * too often at the current thresholds and need a {@code min-notional} override.
     */
    @GetMapping("/trade-alerts/summary")
    public List<SymbolCount> getTradeAlertSummary() {
        return tradeAlertStore.counts();
    }

    /**
     * The most recent price and volume spikes, newest first. In memory only: the store keeps the last
     * {@code screener.spike-alerts.recent-capacity} alerts since startup, in shadow mode too.
     *
     * <p>Example: {@code GET /api/monitoring/spike-alerts?kind=VOLUME&symbol=PEPEUSDT&limit=20}
     *
     * @param venue  only this venue; omitted → every venue
     * @param symbol only this native symbol, case-insensitive; omitted → every symbol
     * @param kind   {@code PRICE} or {@code VOLUME}; omitted → both
     * @param limit  at most this many alerts, clamped to {@code [1, recent-capacity]}
     */
    @GetMapping("/spike-alerts")
    public List<SpikeAlertView> getSpikeAlerts(
            @RequestParam(required = false) Venue venue,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) SpikeKind kind,
            @RequestParam(defaultValue = "100") int limit) {
        int clamped = Math.clamp(limit, 1, spikeAlertStore.capacity());
        String filter = symbol == null || symbol.isBlank() ? null : symbol.trim();
        return spikeAlertStore.recent(venue, filter, kind, clamped).stream().map(SpikeAlertView::of).toList();
    }

    /**
     * Spike count per venue, native symbol and kind since startup, most alerts first: which symbols
     * fire too often at the current thresholds.
     */
    @GetMapping("/spike-alerts/summary")
    public List<SpikeAlertStore.SymbolCount> getSpikeAlertSummary() {
        return spikeAlertStore.counts();
    }

    /**
     * The order clusters detected right now, the instruments with the largest cluster first. In
     * memory only; detected clusters are listed in shadow mode too, which is what this is for.
     *
     * <p>Example: {@code GET /api/monitoring/clusters?venue=BINANCE_SPOT&symbol=ARBUSDT}
     *
     * @param venue  only this venue; omitted → every venue
     * @param symbol only this native symbol, case-insensitive; omitted → every symbol
     */
    @GetMapping("/clusters")
    public List<ClusterEntryView> getClusters(
            @RequestParam(required = false) Venue venue,
            @RequestParam(required = false) String symbol) {
        String filter = symbol == null || symbol.isBlank() ? null : symbol.trim();
        return clusterFeedStore.current().values().stream()
                .filter(u -> venue == null || u.instrument().venue() == venue)
                .filter(u -> filter == null || u.instrument().nativeSymbol().equalsIgnoreCase(filter))
                .map(ClusterEntryView::of)
                .sorted(Comparator.comparingDouble(ClusterEntryView::largestNotional).reversed())
                .toList();
    }

    /**
     * One instrument's clusters as {@code /clusters} shows them.
     *
     * @param largestNotional the largest cluster total on either side, the sort key
     */
    public record ClusterEntryView(
            Venue venue,
            String symbol,
            double largestNotional,
            List<ClusterView> bidClusters,
            List<ClusterView> askClusters
    ) {
        static ClusterEntryView of(ClusterUpdate u) {
            List<ClusterView> bids = u.bidClusters().stream().map(ClusterView::of).toList();
            List<ClusterView> asks = u.askClusters().stream().map(ClusterView::of).toList();
            double largest = 0;
            for (ClusterView c : bids) largest = Math.max(largest, c.totalNotional());
            for (ClusterView c : asks) largest = Math.max(largest, c.totalNotional());
            return new ClusterEntryView(u.instrument().venue(), u.instrument().nativeSymbol(), largest, bids, asks);
        }
    }

    /**
     * One cluster: bounds, size, fractional distance of the nearest edge from mid, when the detector
     * first saw it, and the ladder as {@code [price, quantity]} pairs in ascending price.
     */
    public record ClusterView(
            double priceFrom,
            double priceTo,
            int levelCount,
            double totalNotional,
            double totalQuantity,
            double distance,
            Instant firstSeen,
            List<double[]> levels
    ) {
        static ClusterView of(OrderCluster c) {
            List<double[]> levels = new ArrayList<>(c.levelCount());
            for (int i = 0; i < c.prices().length; i++) levels.add(new double[]{c.prices()[i], c.quantities()[i]});
            return new ClusterView(c.priceFrom(), c.priceTo(), c.levelCount(), c.totalNotional(), c.totalQuantity(),
                    c.distance(), Instant.ofEpochMilli(c.firstSeenMillis()), levels);
        }
    }

    /**
     * One alert as {@code /trade-alerts} shows it: the instrument reduced to venue and native symbol
     * (its dense id stays in the process), the side spelled out, and both times readable.
     *
     * @param id            {@link TradeAlert#id()}: stable across restarts, the key to dedupe by
     * @param side          {@code BUY} if the taker bought, {@code SELL} if the taker sold
     * @param threshold     the minimum notional the alert was judged against
     * @param fills         fills folded into the sweep
     * @param levels        price levels the sweep took; fewer than {@code fills} where a venue reports
     *                      one fill per resting order (MEXC)
     * @param coalescedOnly no single price level reached the threshold; the alert exists because the
     *                      sweep's levels were summed
     * @param tradeTime     venue trade time
     * @param detectedAt    local time the alert was raised; minus {@code tradeTime} is the detection
     *                      lag, including the wait for the sweep to close
     */
    public record TradeAlertView(
            String id,
            Venue venue,
            String symbol,
            String side,
            double notional,
            double threshold,
            double qty,
            double vwap,
            double firstPrice,
            double lastPrice,
            int fills,
            int levels,
            boolean coalescedOnly,
            long firstTradeId,
            long lastTradeId,
            Instant tradeTime,
            Instant detectedAt
    ) {
        static TradeAlertView of(TradeAlert a) {
            return new TradeAlertView(a.id(), a.instrument().venue(), a.instrument().nativeSymbol(),
                    a.aggressorBuy() ? "BUY" : "SELL", a.notional(), a.minNotional(), a.qty(), a.vwap(),
                    a.firstPrice(), a.lastPrice(), a.fills(), a.levels(), a.coalescedOnly(), a.firstTradeId(),
                    a.lastTradeId(), Instant.ofEpochMilli(a.tradeTimeMs()), Instant.ofEpochMilli(a.detectedAtMs()));
        }
    }

    /**
     * One spike as {@code /spike-alerts} shows it.
     *
     * @param id         {@link SpikeAlert#id()}, the key the feed's clients dedupe by
     * @param pct        price: signed move in percent. Volume: increase in percent, {@code null} when
     *                   the previous window traded nothing
     * @param from       price: the window's low (pump) or high (dump). Volume: the previous window's
     *                   quote volume
     * @param to         price: the current price. Volume: the current window's quote volume
     * @param time       venue event time of the candle push that raised it
     * @param detectedAt local time the alert was raised
     */
    public record SpikeAlertView(
            String id,
            Venue venue,
            String symbol,
            SpikeKind kind,
            Double pct,
            double from,
            double to,
            int windowMinutes,
            Instant time,
            Instant detectedAt
    ) {
        static SpikeAlertView of(SpikeAlert a) {
            return new SpikeAlertView(a.id(), a.instrument().venue(), a.instrument().nativeSymbol(), a.kind(),
                    Double.isFinite(a.pct()) ? a.pct() : null, a.from(), a.to(), a.windowMinutes(),
                    Instant.ofEpochMilli(a.timeMs()), Instant.ofEpochMilli(a.detectedAtMs()));
        }
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
