package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.Proto;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotDealsDecoder.DEALS_ITEM;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotDealsDecoder.DEAL_PRICE;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotDealsDecoder.DEAL_QUANTITY;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotDealsDecoder.DEAL_SEQUENCE;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotDealsDecoder.DEAL_TIME;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotDealsDecoder.DEAL_TRADE_ID;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotDealsDecoder.DEAL_TRADE_TYPE;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotDealsDecoder.WRAPPER_AGGRE_DEALS;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.WRAPPER_CHANNEL;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.WRAPPER_SYMBOL;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.fixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MexcSpotDealsDecoderTest {

    private static final String CHANNEL = "spot@public.aggre.deals.v3.api.pb@100ms";
    private static final long T = 1_794_989_507_429L;
    private static final long TAKER = 751_649_775_916_392_448L;

    private final MexcSpotDealsDecoder decoder = new MexcSpotDealsDecoder();
    private final RecordingSink sink = new RecordingSink();
    private final InstrumentSlot slot = new InstrumentSlot(
            Instrument.of(0, Venue.MEXC_SPOT, "BTCUSDT", "BTC", "USDT"), null, null, null, decoder);

    // ---- captured frame ----

    /**
     * BTCUSDT at 08:11:47 UTC on 2026-11-18, also in the replay fixture: five {@code X0_X0} deals,
     * then one $96K taker sell filling 12 resting orders over 7 price levels.
     */
    @Test
    @DisplayName("a captured frame: X0_X0 deals are filtered, real fills carry price, qty, side, time, trade id and taker id")
    void capturedFrame() {
        ByteBuffer frame = ByteBuffer.wrap(fixture("deals-btcusdt.bin"));

        assertTrue(decoder.decode(slot, frame, sink));

        assertEquals(17, sink.events.size());
        for (int i = 0; i < 5; i++) assertEquals(Event.FILTERED, sink.events.get(i), "X0_X0 deal " + i);
        List<Event> fills = sink.events.subList(5, 17);
        assertEquals(new Event(64601.88, 0.3999688, false, T, 86_905_087_064L, 86_905_087_064L, TAKER), fills.getFirst());
        assertEquals(new Event(64600.5, 0.00148323, false, T, 86_905_087_065L, 86_905_087_065L, TAKER), fills.get(1));
        assertEquals(new Event(64597.59, 1.05025752, false, T, 86_905_087_075L, 86_905_087_075L, TAKER), fills.getLast());
        for (int i = 0; i < fills.size(); i++) {
            Event e = fills.get(i);
            assertEquals(TAKER, e.takerOrderId(), "one taker order");
            assertEquals(86_905_087_064L + i, e.firstTradeId(), "field 6, in wire order");
            assertFalse(e.buy());
        }
        assertEquals(7, fills.stream().mapToDouble(Event::price).distinct().count(), "7 levels, 12 fills");
    }

    @Test
    @DisplayName("decoding leaves the frame's position and limit alone")
    void positionAndLimitUnchanged() {
        byte[] bytes = fixture("deals-btcusdt.bin");
        byte[] padded = new byte[bytes.length + 20];
        Arrays.fill(padded, (byte) '9');
        System.arraycopy(bytes, 0, padded, 13, bytes.length);
        ByteBuffer frame = ByteBuffer.wrap(padded, 13, bytes.length);

        assertTrue(decoder.decode(slot, frame, sink));

        assertEquals(13, frame.position());
        assertEquals(13 + bytes.length, frame.limit());
        assertEquals(17, sink.events.size());
    }

    @Test
    @DisplayName("a slice with a non-zero arrayOffset decodes the same")
    void nonZeroArrayOffset() {
        byte[] bytes = frame(realFill("10.5", "2", 1, "11X1_22X1", 7L));
        byte[] padded = new byte[bytes.length + 20];
        System.arraycopy(bytes, 0, padded, 13, bytes.length);
        ByteBuffer frame = ByteBuffer.wrap(padded, 13, bytes.length).slice();
        assertEquals(13, frame.arrayOffset());

        assertTrue(decoder.decode(slot, frame, sink));

        assertEquals(List.of(new Event(10.5, 2, true, T, 7, 7, 11)), sink.events);
    }

    // ---- synthetic frames ----

    @Test
    @DisplayName("tradeType 1 is a buy, 2 a sell; fills of different taker orders carry their own ids")
    void sidesAndTakers() {
        assertTrue(decoder.decode(slot, ByteBuffer.wrap(frame(
                realFill("100", "1", 1, "500X1_900X1", 41L),
                realFill("99", "3", 2, "501X1_901X2", 42L))), sink));

        assertEquals(List.of(new Event(100, 1, true, T, 41, 41, 500), new Event(99, 3, false, T, 42, 42, 501)),
                sink.events);
    }

    @Test
    @DisplayName("only X0_X0 is filtered: a fill with one side numbered 0 is still a real fill")
    void onlyBothZeroIsSynthetic() {
        assertTrue(decoder.decode(slot, ByteBuffer.wrap(frame(
                realFill("100", "1", 1, "500X0_900X1", 41L),
                deal("100", "1", 1, "600X0_601X0", null))), sink));

        assertEquals(List.of(new Event(100, 1, true, T, 41, 41, 500), Event.FILTERED), sink.events);
    }

    @Test
    @DisplayName("an empty body decodes to nothing")
    void emptyBody() {
        assertTrue(decoder.decode(slot, ByteBuffer.wrap(frame()), sink));
        assertTrue(sink.events.isEmpty());
    }

    @Test
    @DisplayName("a real fill without field 6 fails the frame, and nothing before it is reported")
    void realFillWithoutSequence() {
        assertRejected(frame(
                deal("100", "1", 1, "600X0_601X0", null),
                realFill("100", "1", 1, "500X1_900X1", 41L),
                deal("101", "1", 1, "500X2_901X1", null)));
    }

    @Test
    @DisplayName("a malformed tradeId fails the frame")
    void malformedTradeId() {
        assertRejected(frame(realFill("100", "1", 1, "500X1", 41L)));
        assertRejected(frame(realFill("100", "1", 1, "50a0X1_900X1", 41L)));
        assertRejected(frame(realFill("100", "1", 1, "X1_900X1", 41L)));
        assertRejected(frame(realFill("100", "1", 1, "500X1_900X", 41L)));
        assertRejected(frame(realFill("100", "1", 1, "99999999999999999999X1_900X1", 41L)), "taker id past long");
    }

    @Test
    @DisplayName("a deal without price, quantity, tradeType or time, or with a bad value, fails the frame")
    void badDeals() {
        assertRejected(frame(item(null, "1", 1, "500X1_900X1", 41L, T)));
        assertRejected(frame(item("100", null, 1, "500X1_900X1", 41L, T)));
        assertRejected(frame(item("100", "1", null, "500X1_900X1", 41L, T)));
        assertRejected(frame(item("100", "1", 1, "500X1_900X1", 41L, null)));
        assertRejected(frame(item("100", "1", 1, null, 41L, T)));
        assertRejected(frame(realFill("100", "1", 3, "500X1_900X1", 41L)), "tradeType 3");
        assertRejected(frame(realFill("0", "1", 1, "500X1_900X1", 41L)), "zero price");
        assertRejected(frame(realFill("100", "NaN", 1, "500X1_900X1", 41L)), "NaN quantity");
        assertRejected(frame(realFill("1x0", "1", 1, "500X1_900X1", 41L)), "not a number");
    }

    @Test
    @DisplayName("a truncated frame fails, and reports nothing")
    void truncated() {
        byte[] whole = frame(realFill("100", "1", 1, "500X1_900X1", 41L), realFill("99", "1", 1, "500X2_901X1", 42L));
        for (int cut = 1; cut < 12; cut++) {
            assertRejected(Arrays.copyOf(whole, whole.length - cut), "cut " + cut);
        }
    }

    @Test
    @DisplayName("a frame without the deals body (314) fails")
    void noBody() {
        assertRejected(new Proto().string(WRAPPER_CHANNEL, CHANNEL + "@BTCUSDT").string(WRAPPER_SYMBOL, "BTCUSDT")
                .toByteArray());
    }

    @Test
    @DisplayName("text frames are refused; maker fills and frame-end close are on")
    void flagsAndText() {
        assertFalse(decoder.decode(slot, "{}", sink));
        assertTrue(sink.events.isEmpty());
        assertTrue(decoder.reportsMakerFills());
        assertTrue(decoder.closesSweepAtFrameEnd());
    }

    // ---- helpers ----

    private void assertRejected(byte[] frame) {
        assertRejected(frame, "");
    }

    private void assertRejected(byte[] frame, String why) {
        sink.events.clear();
        assertFalse(decoder.decode(slot, ByteBuffer.wrap(frame), sink), why);
        assertTrue(sink.events.isEmpty(), "a failed frame reports nothing " + why);
    }

    /** A deals push in MEXC's wire order: {@code channel, symbol, sendTime, body}. */
    private static byte[] frame(Proto... deals) {
        Proto body = new Proto();
        for (Proto deal : deals) body.message(DEALS_ITEM, deal);
        body.string(2, CHANNEL);
        return new Proto()
                .string(WRAPPER_CHANNEL, CHANNEL + "@BTCUSDT")
                .string(WRAPPER_SYMBOL, "BTCUSDT")
                .varint(6, T + 40)
                .message(WRAPPER_AGGRE_DEALS, body)
                .toByteArray();
    }

    private static Proto realFill(String price, String qty, int tradeType, String tradeId, long sequence) {
        return deal(price, qty, tradeType, tradeId, sequence);
    }

    private static Proto deal(String price, String qty, int tradeType, String tradeId, Long sequence) {
        return item(price, qty, tradeType, tradeId, sequence, T);
    }

    /** One deal item, fields in MEXC's order; a {@code null} field is left out. */
    private static Proto item(String price, String qty, Integer tradeType, String tradeId, Long sequence, Long time) {
        Proto p = new Proto();
        if (price != null) p.string(DEAL_PRICE, price);
        if (qty != null) p.string(DEAL_QUANTITY, qty);
        if (tradeType != null) p.varint(DEAL_TRADE_TYPE, tradeType);
        if (time != null) p.varint(DEAL_TIME, time);
        if (tradeId != null) p.string(DEAL_TRADE_ID, tradeId);
        if (sequence != null) p.varint(DEAL_SEQUENCE, sequence);
        return p;
    }

    /** A reported trade, or {@link #FILTERED}. */
    private record Event(double price, double qty, boolean buy, long time, long firstTradeId, long lastTradeId,
                         long takerOrderId) {
        static final Event FILTERED = new Event(0, 0, false, 0, 0, 0, 0);
    }

    private static final class RecordingSink implements TradeSink {
        final List<Event> events = new ArrayList<>();

        @Override
        public void onTrade(InstrumentSlot slot, double price, double qty, boolean aggressorBuy, long tradeTimeMs,
                            long firstTradeId, long lastTradeId, long takerOrderId) {
            events.add(new Event(price, qty, aggressorBuy, tradeTimeMs, firstTradeId, lastTradeId, takerOrderId));
        }

        @Override
        public void onFiltered(InstrumentSlot slot) {
            events.add(Event.FILTERED);
        }
    }
}
