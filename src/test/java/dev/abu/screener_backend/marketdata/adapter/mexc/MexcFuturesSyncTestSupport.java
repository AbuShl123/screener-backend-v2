package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcFuturesSyncStrategy;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcFuturesSyncContext;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEvent;
import dev.abu.screener_backend.marketdata.core.ingress.EventType;
import dev.abu.screener_backend.marketdata.spi.FakeRecoverySink;

import java.util.StringJoiner;

/**
 * Fixtures for driving a real {@link BookSlot} through {@link MexcFuturesSyncStrategy}.
 *
 * <p>JSON is built as strings so the real {@code JsonParser} + {@code JavaDoubleParser} path runs
 * <em>with MEXC's field order as delivered</em>: {@code channel} last at the top level, and
 * {@code end} / {@code begin} / {@code version} after the levels inside {@code data}
 * ({@code external-docs/mexc/mexc-depth-versioning-empirical.md}).
 */
final class MexcFuturesSyncTestSupport {

    /** Matches {@code screener.orderbook.price-filter-threshold} — a fraction, not a percentage. */
    static final double FILTER = 0.1;

    static final Venue VENUE = Venue.MEXC_FUTURES;

    private MexcFuturesSyncTestSupport() {
    }

    // --- Harness -------------------------------------------------------------------------------

    /** One book, one strategy, one fake sink. No dispatch logic of its own. */
    static final class Harness {

        final BookSlot slot;
        final FakeRecoverySink sink = new FakeRecoverySink();
        final PipelineMetrics metrics = new PipelineMetrics();
        private final DepthEvent event = new DepthEvent();

        Harness() {
            this(1.0);
        }

        /** @param contractSize the instrument's quantity multiplier */
        Harness(double contractSize) {
            MexcFuturesSyncStrategy strategy = new MexcFuturesSyncStrategy(sink, metrics);
            this.slot = new BookSlot(
                    Instrument.of(1, VENUE, "BTC_USDT", "BTC", "USDT", contractSize),
                    new OrderBook(FILTER),
                    strategy,
                    strategy.newContext());
        }

        OrderBook book() {
            return slot.book();
        }

        MexcFuturesSyncContext ctx() {
            return (MexcFuturesSyncContext) slot.ctx();
        }

        int bufferSize() {
            return ctx().diffBuffer.size();
        }

        int requests() {
            return sink.requests;
        }

        void wsMsg(String rawJson) {
            fire(EventType.WS_MSG, rawJson);
        }

        void restMsg(String rawJson) {
            fire(EventType.REST_MSG, rawJson);
        }

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
     * <p>Pushes {@code [100, 110]} and {@code [111, 120]} are buffered, then a snapshot at
     * {@code version = 105} lands — inside the first push's range, the straddling case V1 saw on a
     * third of snapshots. Both pushes are accepted, so the cursor ends at {@code 120} and live
     * pushes must continue from {@code begin = 121}.
     */
    static Harness synced(String snapshotBids, String snapshotAsks) {
        Harness h = new Harness();
        h.wsMsg(push(100, 110, "", ""));    // PENDING → RECOVERING, buffered
        h.wsMsg(push(111, 120, "", ""));    // buffered
        h.restMsg(snapshot(105, snapshotBids, snapshotAsks));
        return h;
    }

    // --- JSON builders -------------------------------------------------------------------------

    /** A {@code push.depth} frame in the field order MEXC actually sends; {@code version == end}. */
    static String push(long begin, long end, String bids, String asks) {
        return "{\"symbol\":\"BTC_USDT\""
                + ",\"data\":{\"cts\":1791040915686"
                + ",\"asks\":[" + asks + "]"
                + ",\"bids\":[" + bids + "]"
                + ",\"end\":" + end
                + ",\"begin\":" + begin
                + ",\"version\":" + end + "}"
                + ",\"channel\":\"push.depth\",\"ts\":1791040915689}";
    }

    /** A {@code push.depth} frame in the order MEXC's docs show: {@code channel} first, sequence before levels. */
    static String pushDocumentedOrder(long begin, long end, String bids, String asks) {
        return "{\"channel\":\"push.depth\""
                + ",\"data\":{\"begin\":" + begin + ",\"end\":" + end + ",\"version\":" + end
                + ",\"asks\":[" + asks + "]"
                + ",\"bids\":[" + bids + "]}"
                + ",\"symbol\":\"BTC_USDT\",\"ts\":1791040915689}";
    }

    /** A {@code GET /api/v1/contract/depth/{symbol}} body, envelope included. */
    static String snapshot(long version, String bids, String asks) {
        return "{\"success\":true,\"code\":0,\"data\":{\"cts\":null"
                + ",\"asks\":[" + asks + "]"
                + ",\"bids\":[" + bids + "]"
                + ",\"version\":" + version
                + ",\"timestamp\":1791040915000}}";
    }

    /** One {@code [price, vol, orderCount]} row — JSON numbers, as MEXC sends them. */
    static String lvl(double price, double vol) {
        return "[" + price + "," + vol + ",3]";
    }

    static String levels(String... levels) {
        StringJoiner joiner = new StringJoiner(",");
        for (String level : levels) {
            joiner.add(level);
        }
        return joiner.toString();
    }
}
