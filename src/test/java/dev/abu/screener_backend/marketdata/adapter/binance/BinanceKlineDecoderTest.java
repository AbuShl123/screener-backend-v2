package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.KlineSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinanceKlineDecoderTest {

    /** A live futures frame (fstream /market/ws, 2026-11-26), spaces inside "k" as Binance sends them. */
    private static final String FRAME = "{\"e\":\"kline\",\"E\":1795689455628,\"s\":\"BTCUSDT\",\"k\":{\"t\":1795689420000, "
            + "\"T\":1795689479999, \"s\":\"BTCUSDT\", \"i\":\"1m\", \"f\":8344468254, \"L\":8344472903, "
            + "\"o\":\"60750.30\", \"c\":\"60659.80\", \"h\":\"60760.00\", \"l\":\"60654.40\", \"v\":\"302.461\", "
            + "\"n\":4650, \"x\":false, \"q\":\"18359538.73310\", \"V\":\"91.371\", \"Q\":\"5546870.93100\", \"B\":\"0\"}}";

    private final BinanceKlineDecoder decoder = new BinanceKlineDecoder();
    private final InstrumentSlot slot = new InstrumentSlot(
            Instrument.of(0, Venue.BINANCE_FUTURES, "BTCUSDT", "BTC", "USDT"), null, null, null);
    private final List<double[]> calls = new ArrayList<>();
    private final KlineSink sink = (s, openTime, high, low, close, quoteVolume, eventTime) ->
            calls.add(new double[]{openTime, high, low, close, quoteVolume, eventTime});

    @Test
    @DisplayName("a kline frame reports open time, h, l, c, quote volume q (not taker Q) and event time E")
    void decodesLiveFrame() {
        assertTrue(decoder.decode(slot, FRAME, sink));

        assertEquals(1, calls.size());
        double[] c = calls.getFirst();
        assertEquals(1795689420000L, (long) c[0]);
        assertEquals(60760.00, c[1]);
        assertEquals(60654.40, c[2]);
        assertEquals(60659.80, c[3]);
        assertEquals(18359538.73310, c[4]);
        assertEquals(1795689455628L, (long) c[5]);
    }

    @Test
    @DisplayName("field order does not matter")
    void anyFieldOrder() {
        String frame = "{\"k\":{\"q\":\"5\",\"c\":\"2\",\"l\":\"1\",\"h\":\"3\",\"t\":60000},\"s\":\"BTCUSDT\",\"E\":61000}";
        assertTrue(decoder.decode(slot, frame, sink));
        double[] c = calls.getFirst();
        assertEquals(60000, (long) c[0]);
        assertEquals(3, c[1]);
        assertEquals(5, c[4]);
        assertEquals(61000, (long) c[5]);
    }

    @Test
    @DisplayName("a frame missing a required field reports nothing")
    void missingField() {
        assertFalse(decoder.decode(slot, FRAME.replace("\"q\":\"18359538.73310\", ", ""), sink));
        assertFalse(decoder.decode(slot, FRAME.replace("\"E\":1795689455628,", ""), sink));
        assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("bad numbers, low above high and malformed JSON are rejected")
    void rejectsBadValues() {
        assertFalse(decoder.decode(slot, FRAME.replace("\"l\":\"60654.40\"", "\"l\":\"0\""), sink));
        assertFalse(decoder.decode(slot, FRAME.replace("\"c\":\"60659.80\"", "\"c\":\"NaN\""), sink));
        assertFalse(decoder.decode(slot, FRAME.replace("\"l\":\"60654.40\"", "\"l\":\"70000\""), sink));
        assertFalse(decoder.decode(slot, FRAME.replace("\"h\":\"60760.00\"", "\"h\":60760.00"), sink));
        assertFalse(decoder.decode(slot, "{\"e\":\"kline\",", sink));
        assertTrue(calls.isEmpty());
    }
}
