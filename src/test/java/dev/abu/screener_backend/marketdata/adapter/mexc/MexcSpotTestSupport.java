package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEvent;
import dev.abu.screener_backend.marketdata.core.ingress.EventType;
import dev.abu.screener_backend.marketdata.spi.FakeRecoverySink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.DEPTHS_ASKS;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.DEPTHS_BIDS;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.DEPTHS_FROM_VERSION;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.DEPTHS_TO_VERSION;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.ITEM_PRICE;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.ITEM_QUANTITY;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.WRAPPER_AGGRE_DEPTHS;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.WRAPPER_CHANNEL;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.WRAPPER_SYMBOL;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Fixtures for MEXC spot: Protobuf frames built field by field in the wire order MEXC sends
 * ({@code external-docs/mexc/mexc-spot-depth-empirical.md} §1), JSON snapshot bodies, a harness
 * driving a real {@link BookSlot} through {@link MexcSpotSyncStrategy}, and the Phase 0 captures
 * under {@code src/test/resources/mexc/spot/}.
 *
 * <p>The frames are encoded by a tiny writer here rather than by {@code protobuf-java}, which the
 * project deliberately does not depend on. Writer and reader could agree on a wrong encoding, so
 * {@code MexcSpotFrameReaderTest} also reads the real captured frames.
 */
final class MexcSpotTestSupport {

    /** Matches {@code screener.orderbook.price-filter-threshold} — a fraction, not a percentage. */
    static final double FILTER = 0.1;

    static final Venue VENUE = Venue.MEXC_SPOT;

    static final String SYMBOL = "BTCUSDT";
    static final String EVENT_TYPE = "spot@public.aggre.depth.v3.api.pb@100ms";

    private MexcSpotTestSupport() {
    }

    // --- Harness -------------------------------------------------------------------------------

    /** One book, one strategy, one fake sink. No dispatch logic of its own. */
    static final class Harness {

        final BookSlot slot;
        final FakeRecoverySink sink = new FakeRecoverySink();
        final PipelineMetrics metrics = new PipelineMetrics();
        private final DepthEvent event = new DepthEvent();

        Harness() {
            this(FILTER);
        }

        /** @param filter the book's price filter, as a fraction of mid */
        Harness(double filter) {
            MexcSpotSyncStrategy strategy = new MexcSpotSyncStrategy(sink, metrics);
            this.slot = new BookSlot(
                    Instrument.of(1, VENUE, SYMBOL, "BTC", "USDT"),
                    new OrderBook(filter),
                    strategy,
                    strategy.newContext());
        }

        OrderBook book() {
            return slot.book();
        }

        MexcSpotSyncContext ctx() {
            return (MexcSpotSyncContext) slot.ctx();
        }

        int bufferSize() {
            return ctx().diffBuffer.size();
        }

        int requests() {
            return sink.requests;
        }

        /** A fresh buffer per frame, as java-websocket delivers them. */
        void wsMsg(byte[] frame) {
            wsMsg(ByteBuffer.wrap(frame));
        }

        void wsMsg(ByteBuffer frame) {
            event.type = EventType.WS_MSG;
            event.instrumentId = slot.instrument().id();
            event.rawBytes = frame;
            fire();
        }

        void restMsg(String rawJson) {
            event.type = EventType.REST_MSG;
            event.instrumentId = slot.instrument().id();
            event.rawJson = rawJson;
            fire();
        }

        void restFailed() {
            event.type = EventType.REST_FAILED;
            event.instrumentId = slot.instrument().id();
            fire();
        }

        /** Reuses one event instance, exactly as the ring buffer does. */
        private void fire() {
            slot.strategy().onEvent(slot, event);
            event.clear();
        }
    }

    /**
     * Drives a fresh book to {@code SYNCED} holding exactly the given snapshot levels.
     *
     * <p>Pushes {@code [100, 110]} and {@code [111, 120]} are buffered, then a snapshot at
     * {@code lastUpdateId = 105} lands inside the first push's range — the straddling case Phase 0
     * saw on most BTC snapshots. Both pushes are accepted, so the cursor ends at {@code 120} and
     * live pushes must continue from {@code fromVersion = 121}.
     */
    static Harness synced(String snapshotBids, String snapshotAsks) {
        Harness h = new Harness();
        h.wsMsg(push(100, 110, List.of(), List.of()));
        h.wsMsg(push(111, 120, List.of(), List.of()));
        h.restMsg(snapshot(105, snapshotBids, snapshotAsks));
        return h;
    }

    // --- Protobuf frames -----------------------------------------------------------------------

    /** One depth item. Price and quantity are the exact strings on the wire. */
    record Level(String price, String quantity) {
    }

    /** A level written the way pushes write numbers: trimmed, no trailing zeros. */
    static Level lvl(double price, double quantity) {
        return new Level(trimmed(price), trimmed(quantity));
    }

    static Level lvl(String price, String quantity) {
        return new Level(price, quantity);
    }

    /** A push for {@link #SYMBOL}. */
    static byte[] push(long from, long to, List<Level> bids, List<Level> asks) {
        return push(SYMBOL, from, to, bids, asks);
    }

    /** A push in MEXC's wire order: {@code channel, symbol, sendTime, body}. */
    static byte[] push(String symbol, long from, long to, List<Level> bids, List<Level> asks) {
        return new Proto()
                .string(WRAPPER_CHANNEL, EVENT_TYPE + "@" + symbol)
                .string(WRAPPER_SYMBOL, symbol)
                .varint(6, 1791556844300L)
                .message(WRAPPER_AGGRE_DEPTHS, body(from, to, bids, asks))
                .toByteArray();
    }

    /**
     * A body in MEXC's wire order: all asks, all bids, {@code eventType}, the versions,
     * {@code lastOrderCreateTime}. An empty side is absent, as delivered.
     */
    static Proto body(long from, long to, List<Level> bids, List<Level> asks) {
        Proto body = new Proto();
        for (Level ask : asks) {
            body.message(DEPTHS_ASKS, item(ask));
        }
        for (Level bid : bids) {
            body.message(DEPTHS_BIDS, item(bid));
        }
        return body
                .string(3, EVENT_TYPE)
                .string(DEPTHS_FROM_VERSION, Long.toString(from))
                .string(DEPTHS_TO_VERSION, Long.toString(to))
                .varint(6, 1791556844251L);
    }

    static Proto item(Level level) {
        return new Proto()
                .string(ITEM_PRICE, level.price())
                .string(ITEM_QUANTITY, level.quantity());
    }

    /** A minimal Protobuf writer — just enough wire format to build test frames, valid or not. */
    static final class Proto {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Proto varint(int field, long value) {
            tag(field, 0);
            return rawVarint(value);
        }

        Proto fixed64(int field, long value) {
            tag(field, 1);
            for (int i = 0; i < 8; i++) {
                out.write((int) (value >>> (8 * i)));
            }
            return this;
        }

        Proto fixed32(int field, int value) {
            tag(field, 5);
            for (int i = 0; i < 4; i++) {
                out.write(value >>> (8 * i));
            }
            return this;
        }

        Proto string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        Proto message(int field, Proto message) {
            return bytes(field, message.toByteArray());
        }

        Proto bytes(int field, byte[] value) {
            tag(field, 2);
            rawVarint(value.length);
            out.writeBytes(value);
            return this;
        }

        Proto tag(int field, int wireType) {
            return rawVarint(((long) field << 3) | wireType);
        }

        Proto raw(int... bytes) {
            for (int b : bytes) {
                out.write(b);
            }
            return this;
        }

        Proto rawVarint(long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
            return this;
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    // --- JSON snapshots ------------------------------------------------------------------------

    /** A {@code GET /api/v3/depth} body in the key order MEXC sends. */
    static String snapshot(long lastUpdateId, String bids, String asks) {
        return "{\"lastUpdateId\":" + lastUpdateId
                + ",\"bids\":[" + bids + "]"
                + ",\"asks\":[" + asks + "]"
                + ",\"timestamp\":1791556844300}";
    }

    /** One {@code ["price","qty"]} row — strings, padded to tick precision as snapshots are. */
    static String row(String price, String quantity) {
        return "[\"" + price + "\",\"" + quantity + "\"]";
    }

    static String row(double price, double quantity) {
        return row(trimmed(price), trimmed(quantity));
    }

    static String rows(String... rows) {
        StringJoiner joiner = new StringJoiner(",");
        for (String row : rows) {
            joiner.add(row);
        }
        return joiner.toString();
    }

    private static String trimmed(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // --- Phase 0 captures ----------------------------------------------------------------------

    /**
     * A file under {@code src/test/resources/mexc/spot/}. Skips the calling test if it is missing,
     * so a checkout without the captures still builds.
     */
    static byte[] fixture(String name) {
        try (InputStream in = MexcSpotTestSupport.class.getResourceAsStream("/mexc/spot/" + name)) {
            assumeTrue(in != null, "Phase 0 fixture missing: src/test/resources/mexc/spot/" + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String fixtureText(String name) {
        return new String(fixture(name), StandardCharsets.UTF_8);
    }

    /** A {@code .frames} file: {@code [4-byte big-endian length][frame bytes]}, repeated. */
    static List<byte[]> fixtureFrames(String name) {
        ByteBuffer all = ByteBuffer.wrap(fixture(name));
        List<byte[]> frames = new ArrayList<>();
        while (all.hasRemaining()) {
            byte[] frame = new byte[all.getInt()];
            all.get(frame);
            frames.add(frame);
        }
        return frames;
    }
}
