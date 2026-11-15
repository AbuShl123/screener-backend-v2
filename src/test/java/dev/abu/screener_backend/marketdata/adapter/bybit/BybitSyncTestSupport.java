package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.book.PriceLevelEntry;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEvent;
import dev.abu.screener_backend.marketdata.core.ingress.EventType;
import dev.abu.screener_backend.marketdata.spi.FakeRecoverySink;

import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Fixtures for driving a real {@link BookSlot} through {@link BybitSyncStrategy}.
 *
 * <p>JSON is built as strings in the field orders Bybit actually sends
 * ({@code external-docs/bybit/bybit-api-contracts.md} §5.2): linear {@code topic, type, ts, data, cts},
 * spot {@code topic, ts, type, data, cts}, and {@code u} / {@code seq} after the levels inside
 * {@code data}.
 */
final class BybitSyncTestSupport {

    /** Matches {@code screener.orderbook.price-filter-threshold} — a fraction, not a percentage. */
    static final double FILTER = 0.1;

    static final Venue VENUE = Venue.BYBIT_FUTURES;

    private BybitSyncTestSupport() {
    }

    // --- Harness -------------------------------------------------------------------------------

    /** One book, one strategy, one fake sink. No dispatch logic of its own. */
    static final class Harness {

        final BookSlot slot;
        final FakeRecoverySink sink = new FakeRecoverySink();
        final PipelineMetrics metrics = new PipelineMetrics();
        private final DepthEvent event = new DepthEvent();

        Harness() {
            this(VENUE, FILTER);
        }

        Harness(Venue venue, double filter) {
            BybitSyncStrategy strategy = new BybitSyncStrategy(sink, metrics);
            this.slot = new BookSlot(
                    Instrument.of(1, venue, "ARBUSDT", "ARB", "USDT"),
                    new OrderBook(filter),
                    strategy,
                    strategy.newContext());
        }

        OrderBook book() {
            return slot.book();
        }

        BybitSyncContext ctx() {
            return (BybitSyncContext) slot.ctx();
        }

        BybitSyncStrategy strategy() {
            return (BybitSyncStrategy) slot.strategy();
        }

        int bufferSize() {
            return ctx().diffBuffer.size();
        }

        int requests() {
            return sink.requests;
        }

        long resyncs() {
            return metrics.resyncs(slot.instrument().venue());
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

    /** A fresh book driven to {@code SYNCED} by an in-stream snapshot at {@code u}, as on every subscribe. */
    static Harness synced(long u, String bids, String asks) {
        Harness h = new Harness();
        h.wsMsg(snapshot(u, bids, asks));
        return h;
    }

    /** {@code price → quantity}, for comparing whole books. */
    static Map<Double, Double> quantities(TreeMap<Double, PriceLevelEntry> side) {
        Map<Double, Double> out = new TreeMap<>();
        side.forEach((price, entry) -> out.put(price, entry.quantity));
        return out;
    }

    // --- JSON builders -------------------------------------------------------------------------

    /** A linear {@code orderbook.1000} snapshot frame: {@code topic, type, ts, data, cts}. */
    static String snapshot(long u, String bids, String asks) {
        return linearFrame("snapshot", u, bids, asks);
    }

    /** A linear {@code orderbook.1000} delta frame. */
    static String delta(long u, String bids, String asks) {
        return linearFrame("delta", u, bids, asks);
    }

    /** A spot frame: {@code ts} before {@code type}. */
    static String spotFrame(String type, long u, String bids, String asks) {
        return "{\"topic\":\"orderbook.1000.ARBUSDT\",\"ts\":1794750825551,\"type\":\"" + type + "\""
                + ",\"data\":" + data(u, bids, asks) + ",\"cts\":1794750825540}";
    }

    private static String linearFrame(String type, long u, String bids, String asks) {
        return "{\"topic\":\"orderbook.1000.ARBUSDT\",\"type\":\"" + type + "\",\"ts\":1794750825225"
                + ",\"data\":" + data(u, bids, asks) + ",\"cts\":1794750825221}";
    }

    private static String data(long u, String bids, String asks) {
        return "{\"s\":\"ARBUSDT\",\"b\":[" + bids + "],\"a\":[" + asks + "],\"u\":" + u
                + ",\"seq\":" + (366400859999L + u * 7) + "}";
    }

    /** A {@code GET /v5/market/orderbook} body, envelope included, asks before bids as Bybit sends. */
    static String rest(long u, String bids, String asks) {
        return "{\"retCode\":0,\"retMsg\":\"OK\",\"result\":{\"s\":\"ARBUSDT\",\"a\":[" + asks + "],\"b\":["
                + bids + "],\"ts\":1794750873425,\"u\":" + u + ",\"seq\":366400926248,\"cts\":1794750873402}"
                + ",\"retExtInfo\":{},\"time\":1794750873600}";
    }

    /** One {@code ["price","size"]} row — strings, as Bybit sends them. */
    static String lvl(String price, String size) {
        return "[\"" + price + "\",\"" + size + "\"]";
    }

    static String levels(String... levels) {
        StringJoiner joiner = new StringJoiner(",");
        for (String level : levels) {
            joiner.add(level);
        }
        return joiner.toString();
    }
}
