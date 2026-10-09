package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.NO_RANGE;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.WRAPPER_AGGRE_DEPTHS;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.WRAPPER_CHANNEL;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.WRAPPER_SYMBOL;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.applyLevels;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.bodyRange;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.end;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.fromVersion;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.start;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.symbolRange;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.toVersion;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.EVENT_TYPE;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.FILTER;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.Proto;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.VENUE;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.body;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.fixture;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.item;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.lvl;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.push;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MEXC spot Protobuf wire reader, against hand-built frames and the Phase 0 captures
 * ({@code external-docs/mexc/mexc-spot-depth-empirical.md}).
 */
class MexcSpotFrameReaderTest {

    @Nested
    @DisplayName("routing key")
    class Routing {

        @Test
        @DisplayName("the wrapper's symbol resolves through the SubscriptionIndex")
        void symbolResolves() {
            ByteBuffer frame = ByteBuffer.wrap(push("ETHUSDT", 1, 2, List.of(lvl(99, 1)), List.of()));

            assertEquals(42, resolve(frame, index("BTCUSDT", "ETHUSDT")));
        }

        @Test
        @DisplayName("a CJK symbol is read as UTF-8")
        void cjkSymbolResolves() {
            ByteBuffer frame = ByteBuffer.wrap(push("龙虾USDT", 1, 2, List.of(lvl(1.5, 1)), List.of()));

            assertEquals(42, resolve(frame, index("BTCUSDT", "龙虾USDT")));
        }

        @Test
        @DisplayName("an unsubscribed symbol misses")
        void unknownSymbolMisses() {
            ByteBuffer frame = ByteBuffer.wrap(push("SOLUSDT", 1, 2, List.of(), List.of(lvl(101, 1))));

            assertEquals(-1, resolve(frame, index("BTCUSDT", "ETHUSDT")));
        }

        @Test
        @DisplayName("without symbol, the channel's suffix after the last @ is the key")
        void channelSuffixFallback() {
            byte[] frame = new Proto()
                    .string(WRAPPER_CHANNEL, EVENT_TYPE + "@ETHUSDT")
                    .message(WRAPPER_AGGRE_DEPTHS, body(1, 2, List.of(lvl(99, 1)), List.of()))
                    .toByteArray();

            assertEquals(42, resolve(ByteBuffer.wrap(frame), index("BTCUSDT", "ETHUSDT")));
        }

        @Test
        @DisplayName("a frame with neither symbol nor channel has no routing key")
        void noKey() {
            byte[] frame = new Proto()
                    .varint(6, 1791556844300L)
                    .message(WRAPPER_AGGRE_DEPTHS, body(1, 2, List.of(lvl(99, 1)), List.of()))
                    .toByteArray();

            assertEquals(NO_RANGE, symbolRange(ByteBuffer.wrap(frame)));
            assertEquals(NO_RANGE, symbolRange(ByteBuffer.wrap(new byte[0])));
        }

        @Test
        @DisplayName("unknown fields of every wire type before the symbol are skipped")
        void unknownFieldsBeforeSymbolAreSkipped() {
            byte[] frame = new Proto()
                    .varint(99, Long.MAX_VALUE)                 // 9-byte varint
                    .fixed64(98, 7L)
                    .fixed32(97, 7)
                    .string(96, "noise@NOTME")
                    .string(WRAPPER_SYMBOL, "ETHUSDT")
                    .toByteArray();

            assertEquals(42, resolve(ByteBuffer.wrap(frame), index("BTCUSDT", "ETHUSDT")));
        }

        @Test
        @DisplayName("routing leaves position and limit alone")
        void routingDoesNotMoveTheBuffer() {
            ByteBuffer frame = ByteBuffer.wrap(push("ETHUSDT", 1, 2, List.of(lvl(99, 1)), List.of()));
            int position = frame.position();
            int limit = frame.limit();

            resolve(frame, index("ETHUSDT"));

            assertEquals(position, frame.position());
            assertEquals(limit, frame.limit());
        }
    }

    @Nested
    @DisplayName("versions")
    class Versions {

        @Test
        @DisplayName("fromVersion and toVersion are read from after the levels")
        void versionsAfterLevels() {
            ByteBuffer frame = ByteBuffer.wrap(push(83321028320L, 83321028630L,
                    List.of(lvl(99, 1), lvl(98, 2)), List.of(lvl(101, 1))));

            long body = bodyRange(frame);

            assertEquals(83321028320L, fromVersion(frame, body));
            assertEquals(83321028630L, toVersion(frame, body));
        }

        @Test
        @DisplayName("versions are matched by field number, not position")
        void versionsInAnyOrder() {
            byte[] frame = new Proto()
                    .message(WRAPPER_AGGRE_DEPTHS, new Proto()
                            .string(5, "120")
                            .string(4, "111")
                            .message(2, item(lvl(99, 1))))
                    .string(WRAPPER_SYMBOL, "BTCUSDT")
                    .toByteArray();
            ByteBuffer buf = ByteBuffer.wrap(frame);

            long body = bodyRange(buf);

            assertEquals(111, fromVersion(buf, body));
            assertEquals(120, toVersion(buf, body));
        }

        @Test
        @DisplayName("a frame without the body throws")
        void missingBodyThrows() {
            byte[] frame = new Proto().string(WRAPPER_SYMBOL, "BTCUSDT").varint(6, 1).toByteArray();

            assertThrows(IllegalStateException.class, () -> bodyRange(ByteBuffer.wrap(frame)));
        }

        @Test
        @DisplayName("a body without toVersion throws")
        void missingVersionThrows() {
            byte[] frame = new Proto()
                    .message(WRAPPER_AGGRE_DEPTHS, new Proto().string(4, "111").message(2, item(lvl(99, 1))))
                    .toByteArray();
            ByteBuffer buf = ByteBuffer.wrap(frame);
            long body = bodyRange(buf);

            assertEquals(111, fromVersion(buf, body));
            assertThrows(IllegalStateException.class, () -> toVersion(buf, body));
        }

        @Test
        @DisplayName("a version that is not plain digits throws")
        void nonDigitVersionThrows() {
            for (String bad : List.of("", "12a", "-5", "1.0", "1234567890123456789")) {
                byte[] frame = new Proto()
                        .message(WRAPPER_AGGRE_DEPTHS, new Proto().string(4, bad).string(5, "1"))
                        .toByteArray();
                ByteBuffer buf = ByteBuffer.wrap(frame);
                long body = bodyRange(buf);

                assertThrows(IllegalStateException.class, () -> fromVersion(buf, body), bad);
            }
        }
    }

    @Nested
    @DisplayName("levels")
    class Levels {

        @Test
        @DisplayName("asks and bids land on their sides; quantity 0 removes the level")
        void levelsApply() {
            OrderBook book = new OrderBook(FILTER);
            book.applyLevel(true, 98, 5, 0);

            apply(push(1, 2, List.of(lvl(99, 2.5), lvl(98, 0)), List.of(lvl(101, 1.25))), book);

            assertEquals(2.5, book.getBids().get(99.0).quantity);
            assertFalse(book.getBids().containsKey(98.0), "quantity 0 must remove the level");
            assertEquals(1.25, book.getAsks().get(101.0).quantity);
            assertEquals(1, book.getBids().size());
            assertEquals(1, book.getAsks().size());
        }

        @Test
        @DisplayName("an absent side applies nothing to that side")
        void emptySide() {
            OrderBook book = new OrderBook(FILTER);

            apply(push(1, 2, List.of(lvl(99, 1)), List.of()), book);
            apply(push(3, 4, List.of(), List.of(lvl(101, 1))), book);

            assertEquals(1, book.getBids().size());
            assertEquals(1, book.getAsks().size());
        }

        @Test
        @DisplayName("trimmed and padded number text lands on one key")
        void numberFormattingSharesAKey() {
            OrderBook book = new OrderBook(FILTER);

            apply(push(1, 2, List.of(lvl("375.80", "1.500")), List.of()), book);
            apply(push(3, 4, List.of(lvl("375.8", "2")), List.of()), book);

            assertEquals(1, book.getBids().size());
            assertEquals(2.0, book.getBids().get(375.8).quantity);
        }

        @Test
        @DisplayName("unknown fields in the wrapper, the body and the items are skipped")
        void unknownFieldsAreSkipped() {
            byte[] frame = new Proto()
                    .string(WRAPPER_CHANNEL, EVENT_TYPE + "@BTCUSDT")
                    .fixed64(50, 1L)
                    .string(WRAPPER_SYMBOL, "BTCUSDT")
                    .message(WRAPPER_AGGRE_DEPTHS, new Proto()
                            .message(1, new Proto().varint(3, 1791556844251L).string(1, "101").fixed32(4, 9).string(2, "4"))
                            .varint(9, 12345)
                            .message(2, new Proto().string(2, "3").string(7, "x").string(1, "99"))
                            .string(10, "future field")
                            .string(4, "11")
                            .string(5, "12"))
                    .varint(51, 2)
                    .toByteArray();
            ByteBuffer buf = ByteBuffer.wrap(frame);
            OrderBook book = new OrderBook(FILTER);

            long body = bodyRange(buf);
            applyLevels(buf, body, book, 0);

            assertEquals(11, fromVersion(buf, body));
            assertEquals(12, toVersion(buf, body));
            assertEquals(4.0, book.getAsks().get(101.0).quantity);
            assertEquals(3.0, book.getBids().get(99.0).quantity);
        }

        @Test
        @DisplayName("an item without a quantity throws")
        void itemWithoutQuantityThrows() {
            byte[] frame = new Proto()
                    .message(WRAPPER_AGGRE_DEPTHS, new Proto()
                            .message(2, new Proto().string(1, "99"))
                            .string(4, "1").string(5, "2"))
                    .toByteArray();

            assertThrows(IllegalStateException.class, () -> apply(frame, new OrderBook(FILTER)));
        }

        @Test
        @DisplayName("an unparseable price throws")
        void badPriceThrows() {
            byte[] frame = push(1, 2, List.of(lvl("oops", "1")), List.of());

            assertThrows(NumberFormatException.class, () -> apply(frame, new OrderBook(FILTER)));
        }

        @Test
        @DisplayName("a direct buffer is refused")
        void directBufferRefused() {
            byte[] bytes = push(1, 2, List.of(lvl(99, 1)), List.of());
            ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
            long body = bodyRange(direct);

            assertThrows(IllegalArgumentException.class, () -> applyLevels(direct, body, new OrderBook(FILTER), 0));
        }
    }

    @Nested
    @DisplayName("buffer indexes")
    class Indexes {

        @Test
        @DisplayName("a frame at a non-zero position is read between position and limit")
        void nonZeroPosition() {
            byte[] frame = push("ETHUSDT", 7, 8, List.of(lvl(99, 1)), List.of(lvl(101, 2)));
            byte[] padded = pad(frame, 13, 9);
            ByteBuffer buf = ByteBuffer.wrap(padded, 13, frame.length);

            assertRead(buf);
        }

        @Test
        @DisplayName("a slice with a non-zero arrayOffset is read correctly")
        void nonZeroArrayOffset() {
            byte[] frame = push("ETHUSDT", 7, 8, List.of(lvl(99, 1)), List.of(lvl(101, 2)));
            byte[] padded = pad(frame, 13, 9);
            ByteBuffer buf = ByteBuffer.wrap(padded, 13, frame.length).slice();
            assertEquals(13, buf.arrayOffset());

            assertRead(buf);
        }

        private void assertRead(ByteBuffer buf) {
            int position = buf.position();
            int limit = buf.limit();
            OrderBook book = new OrderBook(FILTER);

            long symbol = symbolRange(buf);
            long body = bodyRange(buf);
            applyLevels(buf, body, book, 0);

            assertEquals("ETHUSDT", text(buf, symbol));
            assertEquals(7, fromVersion(buf, body));
            assertEquals(8, toVersion(buf, body));
            assertEquals(1.0, book.getBids().get(99.0).quantity);
            assertEquals(2.0, book.getAsks().get(101.0).quantity);
            assertEquals(position, buf.position(), "position must not move");
            assertEquals(limit, buf.limit(), "limit must not move");
        }

        /** {@code before} and {@code after} bytes of '9' around the frame, which a stray read would hit. */
        private byte[] pad(byte[] frame, int before, int after) {
            byte[] padded = new byte[before + frame.length + after];
            Arrays.fill(padded, (byte) '9');
            System.arraycopy(frame, 0, padded, before, frame.length);
            return padded;
        }
    }

    @Nested
    @DisplayName("malformed wire")
    class Malformed {

        @Test
        @DisplayName("a truncated varint throws")
        void truncatedVarint() {
            byte[] frame = new Proto().raw(0x80).toByteArray();

            assertThrows(IllegalStateException.class, () -> bodyRange(ByteBuffer.wrap(frame)));
            assertThrows(IllegalStateException.class, () -> symbolRange(ByteBuffer.wrap(frame)));
        }

        @Test
        @DisplayName("a varint longer than five bytes for a tag or a length throws")
        void overlongVarint() {
            byte[] frame = new Proto().raw(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01).toByteArray();

            assertThrows(IllegalStateException.class, () -> bodyRange(ByteBuffer.wrap(frame)));
        }

        @Test
        @DisplayName("a length running past the frame throws")
        void lengthPastEnd() {
            byte[] frame = new Proto().tag(WRAPPER_SYMBOL, 2).rawVarint(50).raw('B', 'T', 'C').toByteArray();

            assertThrows(IllegalStateException.class, () -> symbolRange(ByteBuffer.wrap(frame)));
        }

        @Test
        @DisplayName("an item running past its body throws, even when the frame has the bytes")
        void itemPastBody() {
            // The body claims 3 bytes; its item claims 5. The trailing field supplies the bytes.
            byte[] frame = new Proto()
                    .tag(WRAPPER_AGGRE_DEPTHS, 2).rawVarint(3)
                    .tag(2, 2).rawVarint(5).raw(0x0A)
                    .string(WRAPPER_SYMBOL, "BTCUSDT")
                    .toByteArray();
            ByteBuffer buf = ByteBuffer.wrap(frame);
            long body = bodyRange(buf);

            assertThrows(IllegalStateException.class, () -> applyLevels(buf, body, new OrderBook(FILTER), 0));
        }

        @Test
        @DisplayName("a group wire type throws")
        void groupWireType() {
            byte[] frame = new Proto().tag(7, 3).string(WRAPPER_SYMBOL, "BTCUSDT").toByteArray();

            assertThrows(IllegalStateException.class, () -> symbolRange(ByteBuffer.wrap(frame)));
        }

        @Test
        @DisplayName("a truncated fixed-width field throws")
        void truncatedFixed() {
            byte[] frame = new Proto().tag(7, 1).raw(1, 2, 3).toByteArray();

            assertThrows(IllegalStateException.class, () -> bodyRange(ByteBuffer.wrap(frame)));
        }
    }

    @Nested
    @DisplayName("Phase 0 captures")
    class Captures {

        @Test
        @DisplayName("the test encoder writes field 313 as the two-byte tag seen on the wire (ca 13)")
        void encoderMatchesWire() {
            byte[] tag = new Proto().tag(WRAPPER_AGGRE_DEPTHS, 2).toByteArray();

            assertArrayEquals(new byte[]{(byte) 0xCA, 0x13}, tag);
        }

        @Test
        @DisplayName("push-btcusdt.bin: symbol, versions, 13 asks + 30 bids including deletes")
        void btcPush() {
            ByteBuffer frame = ByteBuffer.wrap(fixture("push-btcusdt.bin"));
            CountingBook book = new CountingBook();

            long body = bodyRange(frame);
            applyLevels(frame, body, book, 0);

            assertEquals("BTCUSDT", text(frame, symbolRange(frame)));
            assertEquals(83332997012L, fromVersion(frame, body));
            assertEquals(83332997062L, toVersion(frame, body));
            assertEquals(13, book.asks);
            assertEquals(30, book.bids);
            assertTrue(book.zeroes > 0, "the capture carries quantity-0 deletes");
            assertTrue(book.deleted(true, 82567.45), "the bid at 82567.45 is a delete");
        }

        @Test
        @DisplayName("push-paidusdt-bids-only.bin: eight bids, no asks field")
        void bidsOnlyPush() {
            ByteBuffer frame = ByteBuffer.wrap(fixture("push-paidusdt-bids-only.bin"));
            CountingBook book = new CountingBook();

            long body = bodyRange(frame);
            applyLevels(frame, body, book, 0);

            assertEquals("PAIDUSDT", text(frame, symbolRange(frame)));
            assertEquals(281293249L, fromVersion(frame, body));
            assertEquals(281293259L, toVersion(frame, body));
            assertEquals(0, book.asks);
            assertEquals(8, book.bids);
        }

        @Test
        @DisplayName("push-ionqonusdt-asks-only.bin: 47 asks, no bids field")
        void asksOnlyPush() {
            ByteBuffer frame = ByteBuffer.wrap(fixture("push-ionqonusdt-asks-only.bin"));
            CountingBook book = new CountingBook();

            long body = bodyRange(frame);
            applyLevels(frame, body, book, 0);

            assertEquals("IONQONUSDT", text(frame, symbolRange(frame)));
            assertEquals(256003640L, fromVersion(frame, body));
            assertEquals(256003687L, toVersion(frame, body));
            assertEquals(47, book.asks);
            assertEquals(0, book.bids);
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Counts what the reader hands the book, deletes included. */
    private static final class CountingBook extends OrderBook {
        int asks;
        int bids;
        int zeroes;
        private final Set<String> deletes = new HashSet<>();

        CountingBook() {
            super(FILTER);
        }

        @Override
        public void applyLevel(boolean isBid, double price, double qty, long millis) {
            if (isBid) bids++; else asks++;
            if (qty == 0.0) {
                zeroes++;
                deletes.add(isBid + ":" + price);
            }
            super.applyLevel(isBid, price, qty, millis);
        }

        boolean deleted(boolean isBid, double price) {
            return deletes.contains(isBid + ":" + price);
        }
    }

    private static void apply(byte[] frame, OrderBook book) {
        ByteBuffer buf = ByteBuffer.wrap(frame);
        applyLevels(buf, bodyRange(buf), book, 0);
    }

    private static int resolve(ByteBuffer frame, SubscriptionIndex index) {
        long range = symbolRange(frame);
        return index.resolve(frame, start(range), end(range));
    }

    /** {@code ETHUSDT} (or whichever symbol is listed last) gets id 42, every other one a low id. */
    private static SubscriptionIndex index(String... symbols) {
        List<Instrument> instruments = new ArrayList<>();
        for (int i = 0; i < symbols.length; i++) {
            int id = i == symbols.length - 1 ? 42 : i;
            instruments.add(Instrument.of(id, VENUE, symbols[i], symbols[i].replace("USDT", ""), "USDT"));
        }
        return new SubscriptionIndex(instruments, Instrument::nativeSymbol);
    }

    private static String text(ByteBuffer buf, long range) {
        byte[] bytes = new byte[end(range) - start(range)];
        buf.get(start(range), bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
