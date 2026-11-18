package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MexcFuturesDealDecoderTest {

    /** {@code BTC_USDT} contract size. */
    private static final double CONTRACT_SIZE = 0.0001;

    /**
     * A captured shape ({@code mexc-trades-empirical.md} §2), newest deal first: a two-fill buy
     * sweep (ids 85, 86) at 47 ms, after a self-trade at 46 ms and a sell at 45 ms.
     */
    private static final String FRAME = """
            {"symbol":"BTC_USDT","data":[\
            {"p":64671.2,"v":300,"T":1,"O":1,"M":2,"t":1794983337847,"i":"17067813086","cts":"1794983337847"},\
            {"p":64670.8,"v":1250,"T":1,"O":3,"M":2,"t":1794983337847,"i":"17067813085","cts":"1794983337847"},\
            {"p":64670.5,"v":40,"T":2,"O":3,"M":1,"t":1794983337846,"i":"17067813084","cts":"1794983337846"},\
            {"p":64670.0,"v":2,"T":2,"O":2,"M":2,"t":1794983337845,"i":"17067813083","cts":"1794983337845"}],\
            "channel":"push.deal","ts":1794983337848}""";

    private final MexcFuturesDealDecoder decoder = new MexcFuturesDealDecoder();
    private final RecordingSink sink = new RecordingSink();
    private final InstrumentSlot slot = new InstrumentSlot(
            Instrument.of(0, Venue.MEXC_FUTURES, "BTC_USDT", "BTC", "USDT", CONTRACT_SIZE), null, null, null, decoder);

    @Test
    @DisplayName("a multi-deal frame is reported oldest first; v × contractSize; M=1 is filtered; no taker id")
    void multiDealFrame() {
        assertTrue(decoder.decode(slot, FRAME, sink));

        assertEquals(List.of(
                new Event(64670.0, 2 * CONTRACT_SIZE, false, 1794983337845L, 17067813083L),
                Event.FILTERED,
                new Event(64670.8, 1250 * CONTRACT_SIZE, true, 1794983337847L, 17067813085L),
                new Event(64671.2, 300 * CONTRACT_SIZE, true, 1794983337847L, 17067813086L)), sink.events);
    }

    @Test
    @DisplayName("a single-deal frame, the usual case")
    void singleDeal() {
        assertTrue(decoder.decode(slot, """
                {"symbol":"BTC_USDT","data":[{"p":64670.8,"v":1,"T":2,"O":1,"M":2,"t":1794983337848,\
                "i":"17067813087","cts":"1794983337848"}],"channel":"push.deal","ts":1794983337848}""", sink));

        assertEquals(List.of(new Event(64670.8, CONTRACT_SIZE, false, 1794983337848L, 17067813087L)), sink.events);
    }

    @Test
    @DisplayName("more deals than the scratch buffer's first size are all reported, in order")
    void manyDeals() {
        StringBuilder data = new StringBuilder();
        for (int i = 49; i >= 0; i--) {
            if (data.length() > 0) data.append(',');
            data.append("{\"p\":").append(100 + i).append(",\"v\":1,\"T\":1,\"M\":2,\"t\":1000,\"i\":\"")
                    .append(500 + i).append("\"}");
        }
        assertTrue(decoder.decode(slot, "{\"symbol\":\"BTC_USDT\",\"data\":[" + data + "]}", sink));

        assertEquals(50, sink.events.size());
        for (int i = 0; i < 50; i++) assertEquals(500 + i, sink.events.get(i).tradeId());
    }

    @Test
    @DisplayName("an empty data array decodes to nothing")
    void emptyData() {
        assertTrue(decoder.decode(slot, "{\"symbol\":\"BTC_USDT\",\"data\":[],\"channel\":\"push.deal\"}", sink));
        assertTrue(sink.events.isEmpty());
    }

    @ParameterizedTest(name = "missing {0}")
    @ValueSource(strings = {"p", "v", "T", "t", "i", "M"})
    @DisplayName("a deal missing a required field fails the frame, and nothing is reported")
    void missingRequiredField(String field) {
        Map<String, String> deal = deal();
        deal.remove(field);
        assertRejected(frame(json(deal), firstDeal()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"p=0", "p=-1", "p=\"64670.8\"", "v=0", "T=3", "M=0", "t=0", "i=\"17x\"",
            "i=17067813088", "i=\"\""})
    @DisplayName("a deal with an out-of-range or mistyped value fails the frame")
    void badValue(String assignment) {
        Map<String, String> deal = deal();
        deal.put(assignment.substring(0, assignment.indexOf('=')), assignment.substring(assignment.indexOf('=') + 1));
        assertRejected(frame(firstDeal(), json(deal)));
    }

    @Test
    @DisplayName("bad JSON, no data, or data that is not an array fails")
    void badFrames() {
        assertRejected("not json");
        assertRejected("{\"symbol\":\"BTC_USDT\",\"data\":[" + firstDeal());
        assertRejected("{\"symbol\":\"BTC_USDT\",\"channel\":\"push.deal\"}");
        assertRejected("{\"channel\":\"rs.sub.deal\",\"data\":\"success\",\"ts\":1794983318045}");
        assertRejected("[]");
    }

    @Test
    @DisplayName("maker fills on, no frame-end close (a sweep spans frames), binary frames refused")
    void flags() {
        assertTrue(decoder.reportsMakerFills());
        assertFalse(decoder.closesSweepAtFrameEnd());
        assertFalse(decoder.decode(slot, ByteBuffer.wrap(new byte[]{1}), sink));
    }

    // ---- helpers ----

    private static String firstDeal() {
        return json(deal());
    }

    /** A valid deal, field name to raw JSON value, in MEXC's field order. */
    private static Map<String, String> deal() {
        Map<String, String> deal = new LinkedHashMap<>();
        deal.put("p", "64670.8");
        deal.put("v", "1");
        deal.put("T", "1");
        deal.put("O", "1");
        deal.put("M", "2");
        deal.put("t", "1794983337849");
        deal.put("i", "\"17067813088\"");
        deal.put("cts", "\"1794983337849\"");
        return deal;
    }

    private static String json(Map<String, String> deal) {
        StringJoiner fields = new StringJoiner(",", "{", "}");
        deal.forEach((k, v) -> fields.add("\"" + k + "\":" + v));
        return fields.toString();
    }

    /** A push frame; {@code deals} newest first, as MEXC sends them. */
    private static String frame(String... deals) {
        return "{\"symbol\":\"BTC_USDT\",\"data\":[" + String.join(",", deals)
                + "],\"channel\":\"push.deal\",\"ts\":1794983337850}";
    }

    private void assertRejected(String frame) {
        sink.events.clear();
        assertFalse(decoder.decode(slot, frame, sink), frame);
        assertTrue(sink.events.isEmpty(), "a failed frame reports nothing: " + frame);
    }

    /** A reported trade ({@code firstTradeId == lastTradeId}, taker id {@code -1}), or {@link #FILTERED}. */
    private record Event(double price, double qty, boolean buy, long time, long tradeId) {
        static final Event FILTERED = new Event(0, 0, false, 0, 0);
    }

    private static final class RecordingSink implements TradeSink {
        final List<Event> events = new ArrayList<>();

        @Override
        public void onTrade(InstrumentSlot slot, double price, double qty, boolean aggressorBuy, long tradeTimeMs,
                            long firstTradeId, long lastTradeId, long takerOrderId) {
            assertEquals(firstTradeId, lastTradeId);
            assertEquals(-1, takerOrderId);
            events.add(new Event(price, qty, aggressorBuy, tradeTimeMs, firstTradeId));
        }

        @Override
        public void onFiltered(InstrumentSlot slot) {
            events.add(Event.FILTERED);
        }
    }
}
