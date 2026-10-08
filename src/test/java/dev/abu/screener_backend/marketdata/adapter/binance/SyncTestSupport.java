package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceDepthSyncStrategy;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceFuturesSyncStrategy;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceSpotSyncStrategy;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceSyncContext;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEvent;
import dev.abu.screener_backend.marketdata.core.ingress.EventType;
import dev.abu.screener_backend.marketdata.spi.FakeRecoverySink;

import java.util.StringJoiner;

/**
 * Fixtures for driving a real {@link BookSlot} through the Binance sync strategies.
 *
 * <p>Lives in the {@code binance} package on purpose: {@link BinanceSyncContext}'s
 * {@code diffBuffer}, {@code lastUpdateId} and {@code syncPointFound} are package-private, so the
 * assertions that used to need reflection on {@code OrderBook.diffBuffer} are now plain field
 * reads.
 *
 * <p>JSON is built as strings deliberately. The point is to exercise the real {@code JsonParser} +
 * {@code JavaDoubleParser} path <em>including Binance's field ordering</em> — {@code U}, {@code u}
 * and {@code pu} precede {@code b} and {@code a}, which is exactly what the {@code check()}
 * parser hand-off contract depends on.
 */
final class SyncTestSupport {

    /** Matches {@code screener.orderbook.price-filter-threshold} — a fraction, not a percentage. */
    static final double FILTER = 0.1;

    private SyncTestSupport() {
    }

    // --- Harness -------------------------------------------------------------------------------

    /**
     * One book, one strategy, one fake sink.
     *
     * <p>Unlike its predecessor this harness contains <b>no</b> dispatch logic. The state machine
     * that used to live in {@code OrderBookProcessor} is now inside
     * {@link BinanceDepthSyncStrategy#onEvent}, so the harness only builds an event and calls it.
     * If this class ever grows a state transition again, it has stopped testing the real thing.
     */
    static final class Harness {

        final BookSlot slot;
        final FakeRecoverySink sink = new FakeRecoverySink();
        /** Real instance, not a mock — resyncs() doubles as an independent check on recover() calls. */
        final PipelineMetrics metrics = new PipelineMetrics();
        private final DepthEvent event = new DepthEvent();

        Harness(Venue venue, double filterThreshold) {
            BinanceDepthSyncStrategy strategy = venue == Venue.BINANCE_SPOT
                    ? new BinanceSpotSyncStrategy(sink, metrics)
                    : new BinanceFuturesSyncStrategy(sink, metrics);
            this.slot = new BookSlot(
                    Instrument.of(1, venue, "BTCUSDT", "BTC", "USDT"),
                    new OrderBook(filterThreshold, 2.0),
                    strategy,
                    strategy.newContext());
        }

        OrderBook book() {
            return slot.book();
        }

        BinanceSyncContext ctx() {
            return (BinanceSyncContext) slot.ctx();
        }

        int bufferSize() {
            return ctx().diffBuffer.size();
        }

        int requests() {
            return sink.requests;
        }

        /** Feed a stream frame — the {@code WS_MSG} lane. */
        void wsMsg(String rawJson) {
            fire(EventType.WS_MSG, rawJson);
        }

        /** Feed a REST snapshot body — the {@code REST_MSG} lane. */
        void restMsg(String rawJson) {
            fire(EventType.REST_MSG, rawJson);
        }

        /** Report the book's snapshot request as failed — the {@code REST_FAILED} lane, no body. */
        void restFailed() {
            fire(EventType.REST_FAILED, null);
        }

        /** Reuses one event instance, exactly as the ring buffer does. */
        private void fire(EventType type, String rawJson) {
            event.type = type;
            event.instrumentId = slot.instrument().id();
            event.rawJson = rawJson;
            slot.strategy().onEvent(slot, event);
            event.clear();
        }
    }

    /**
     * Drives a fresh book to {@code SYNCED} holding exactly the given snapshot levels.
     *
     * <p>Two diffs are buffered, then a snapshot at {@code lastUpdateId = 105} lands and the buffer
     * drains through the normal {@code check()} path. Both diffs are accepted on both venues, so
     * the cursor ends at {@code u = 120} and live diffs must continue from {@code U = 121 /
     * pu = 120}.
     */
    static Harness synced(Venue venue, String snapshotBids, String snapshotAsks) {
        return synced(venue, FILTER, snapshotBids, snapshotAsks);
    }

    static Harness synced(Venue venue, double filterThreshold, String snapshotBids, String snapshotAsks) {
        Harness h = new Harness(venue, filterThreshold);
        h.wsMsg(diff(venue, 100, 110, 99, "", ""));    // PENDING → RECOVERING, buffered
        h.wsMsg(diff(venue, 111, 120, 110, "", ""));   // buffered
        h.restMsg(snapshot(105, snapshotBids, snapshotAsks));
        return h;
    }

    // --- JSON builders -------------------------------------------------------------------------

    /**
     * A {@code depthUpdate} frame. {@code pu} is emitted only for futures — Binance spot frames do
     * not carry it, and the spot sequence rule must not depend on it.
     */
    static String diff(Venue venue, long firstUpdateId, long lastUpdateId, long prevUpdateId,
                       String bids, String asks) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"e\":\"depthUpdate\",\"E\":1700000000000,\"s\":\"BTCUSDT\"");
        sb.append(",\"U\":").append(firstUpdateId);
        sb.append(",\"u\":").append(lastUpdateId);
        if (venue == Venue.BINANCE_FUTURES) {
            sb.append(",\"pu\":").append(prevUpdateId);
        }
        sb.append(",\"b\":[").append(bids).append("]");
        sb.append(",\"a\":[").append(asks).append("]}");
        return sb.toString();
    }

    /** A REST depth snapshot body. */
    static String snapshot(long lastUpdateId, String bids, String asks) {
        return "{\"lastUpdateId\":" + lastUpdateId
                + ",\"bids\":[" + bids + "]"
                + ",\"asks\":[" + asks + "]}";
    }

    /** One {@code ["price","qty"]} pair — quoted, as Binance sends them. */
    static String lvl(double price, double qty) {
        return "[\"" + price + "\",\"" + qty + "\"]";
    }

    static String levels(String... levels) {
        StringJoiner joiner = new StringJoiner(",");
        for (String level : levels) {
            joiner.add(level);
        }
        return joiner.toString();
    }
}
