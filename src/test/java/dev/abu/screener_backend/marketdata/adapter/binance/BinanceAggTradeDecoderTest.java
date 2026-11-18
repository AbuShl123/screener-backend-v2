package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frames are the ones recorded in {@code trade-streams-transport-plan.md} §1.3. */
class BinanceAggTradeDecoderTest {

    private static final String SPOT = """
            {"e":"aggTrade","E":1794857964130,"s":"BTCUSDT","a":4120467472,"p":"64826.00000000",\
            "q":"0.02711000","f":6870090619,"l":6870090619,"T":1794857964129,"m":true,"M":true}""";

    private static final String FUTURES = """
            {"e":"aggTrade","E":1794860803575,"a":3528412961,"s":"BTCUSDT","p":"65079.50","q":"0.006",\
            "nq":"0.006","f":8285985083,"l":8285985083,"T":1794860803450,"m":true,"st":1}""";

    private final BinanceAggTradeDecoder decoder = new BinanceAggTradeDecoder();
    private final RecordingSink sink = new RecordingSink();
    private final InstrumentSlot spotSlot = slot(Venue.BINANCE_SPOT);
    private final InstrumentSlot futuresSlot = slot(Venue.BINANCE_FUTURES);

    @Test
    @DisplayName("a spot frame decodes to its price, qty, trade time (T, not E) and ids")
    void spotFrame() {
        assertTrue(decoder.decode(spotSlot, SPOT, sink));

        Trade t = sink.only();
        assertSame(spotSlot, t.slot());
        assertEquals(64826.0, t.price());
        assertEquals(0.02711, t.qty());
        assertEquals(1794857964129L, t.tradeTimeMs());
        assertEquals(6870090619L, t.firstTradeId());
        assertEquals(6870090619L, t.lastTradeId());
        assertEquals(-1, t.takerOrderId(), "Binance publishes no taker-order id");
    }

    @Test
    @DisplayName("an aggTrade already sums a taker order's fills at one price: no maker fills, no frame-end close, no binary frames")
    void spiDefaults() {
        assertFalse(decoder.reportsMakerFills());
        assertFalse(decoder.closesSweepAtFrameEnd());
        assertFalse(decoder.decode(spotSlot, ByteBuffer.wrap(SPOT.getBytes(StandardCharsets.UTF_8)), sink));
        assertTrue(sink.trades.isEmpty());
    }

    @Test
    @DisplayName("a futures frame (a before s, extra nq and st) decodes the same way")
    void futuresFrame() {
        assertTrue(decoder.decode(futuresSlot, FUTURES, sink));

        Trade t = sink.only();
        assertSame(futuresSlot, t.slot());
        assertEquals(65079.5, t.price());
        assertEquals(0.006, t.qty());
        assertEquals(1794860803450L, t.tradeTimeMs());
        assertEquals(8285985083L, t.firstTradeId());
        assertEquals(8285985083L, t.lastTradeId());
    }

    @Test
    @DisplayName("m:true (buyer is maker) is a taker sell; m:false is a taker buy")
    void sideFromMakerFlag() {
        decoder.decode(spotSlot, SPOT, sink);
        decoder.decode(spotSlot, SPOT.replace("\"m\":true", "\"m\":false"), sink);

        assertFalse(sink.trades.get(0).aggressorBuy());
        assertTrue(sink.trades.get(1).aggressorBuy());
    }

    @Test
    @DisplayName("side comes from m, never from spot's M")
    void spotUpperMIsIgnored() {
        decoder.decode(spotSlot, SPOT.replace("\"M\":true", "\"M\":false"), sink);

        assertFalse(sink.only().aggressorBuy(), "m is still true, so the taker sold");
    }

    @Test
    @DisplayName("qty is q, not futures nq")
    void quantityIsQNotNq() {
        decoder.decode(futuresSlot, FUTURES.replace("\"nq\":\"0.006\"", "\"nq\":\"0.004\""), sink);

        assertEquals(0.006, sink.only().qty());
    }

    @Test
    @DisplayName("fields in a different order decode to the same trade")
    void fieldOrderDoesNotMatter() {
        String reordered = """
                {"m":true,"T":1794857964129,"l":6870090619,"f":6870090619,"q":"0.02711000",\
                "p":"64826.00000000","a":4120467472,"s":"BTCUSDT","E":1794857964130,"e":"aggTrade","M":true}""";

        decoder.decode(spotSlot, SPOT, sink);
        decoder.decode(spotSlot, reordered, sink);

        assertEquals(sink.trades.get(0), sink.trades.get(1));
    }

    @Test
    @DisplayName("a multi-fill aggTrade carries its first and last trade ids")
    void multiFillIds() {
        decoder.decode(spotSlot, SPOT.replace("\"l\":6870090619", "\"l\":6870090624"), sink);

        Trade t = sink.only();
        assertEquals(6870090619L, t.firstTradeId());
        assertEquals(6870090624L, t.lastTradeId());
    }

    @Test
    @DisplayName("a frame without f and l still decodes, with ids -1")
    void missingIdsDecodeAsMinusOne() {
        String noIds = SPOT.replace("\"f\":6870090619,\"l\":6870090619,", "");

        assertTrue(decoder.decode(spotSlot, noIds, sink));
        assertEquals(-1, sink.only().firstTradeId());
        assertEquals(-1, sink.only().lastTradeId());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\"p\":\"64826.00000000\",", "\"q\":\"0.02711000\",", "\"T\":1794857964129,", "\"m\":true,"})
    @DisplayName("a frame missing a required field returns false and reports nothing")
    void missingRequiredField(String field) {
        String frame = SPOT.replace(field, "");

        assertFalse(decoder.decode(spotSlot, frame, sink));
        assertTrue(sink.trades.isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "\"p\":\"abc\"",           // not a number
            "\"p\":64826.0",           // a JSON number, not Binance's string
            "\"p\":\"0.00000000\"",    // zero price
            "\"p\":\"NaN\"",           // fastdoubleparser accepts it as text
            "\"p\":\"-1\"",            // negative
    })
    @DisplayName("a price that is not a positive decimal string returns false and reports nothing")
    void invalidPrice(String price) {
        String frame = SPOT.replace("\"p\":\"64826.00000000\"", price);

        assertFalse(decoder.decode(spotSlot, frame, sink));
        assertTrue(sink.trades.isEmpty());
    }

    @Test
    @DisplayName("a zero quantity returns false and reports nothing")
    void zeroQuantity() {
        assertFalse(decoder.decode(spotSlot, SPOT.replace("\"q\":\"0.02711000\"", "\"q\":\"0\""), sink));
        assertTrue(sink.trades.isEmpty());
    }

    @Test
    @DisplayName("a non-boolean m returns false and reports nothing")
    void nonBooleanMaker() {
        assertFalse(decoder.decode(spotSlot, SPOT.replace("\"m\":true", "\"m\":\"true\""), sink));
        assertTrue(sink.trades.isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", "[]", "not json", "{\"p\":\"1\""})
    @DisplayName("a frame that is not a complete JSON object returns false and reports nothing")
    void malformedFrame(String frame) {
        assertFalse(decoder.decode(spotSlot, frame, sink));
        assertTrue(sink.trades.isEmpty());
    }

    // --- Support -------------------------------------------------------------------------------

    private static InstrumentSlot slot(Venue venue) {
        return new InstrumentSlot(Instrument.of(venue.ordinal(), venue, "BTCUSDT", "BTC", "USDT"), null, null, null);
    }

    private record Trade(InstrumentSlot slot, double price, double qty, boolean aggressorBuy,
                         long tradeTimeMs, long firstTradeId, long lastTradeId, long takerOrderId) { }

    private static final class RecordingSink implements TradeSink {
        final List<Trade> trades = new ArrayList<>();

        @Override
        public void onTrade(InstrumentSlot slot, double price, double qty, boolean aggressorBuy,
                            long tradeTimeMs, long firstTradeId, long lastTradeId, long takerOrderId) {
            trades.add(new Trade(slot, price, qty, aggressorBuy, tradeTimeMs, firstTradeId, lastTradeId, takerOrderId));
        }

        @Override
        public void onFiltered(InstrumentSlot slot) {
            throw new AssertionError("Binance filters nothing");
        }

        Trade only() {
            assertEquals(1, trades.size(), "exactly one trade per Binance frame");
            return trades.get(0);
        }
    }
}
