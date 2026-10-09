package dev.abu.screener_backend.marketdata.core.stream;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubscriptionIndexTest {

    private static final Instrument BTC = Instrument.of(4, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT");
    private static final Instrument ETH = Instrument.of(5, Venue.BINANCE_SPOT, "ETHUSDT", "ETH", "USDT");
    /** Five MEXC spot symbols are CJK; 龙虾 is 6 UTF-8 bytes but 2 chars. */
    private static final Instrument LOBSTER = Instrument.of(6, Venue.BINANCE_SPOT, "龙虾USDT", "龙虾", "USDT");

    @Test
    @DisplayName("resolves a [start, end) range inside a larger string")
    void resolvesRange() {
        SubscriptionIndex index = new SubscriptionIndex(List.of(BTC, ETH), Instrument::nativeSymbol);
        String msg = "xx\"s\":\"ETHUSDT\",yy";
        int start = msg.indexOf("ETH");

        assertEquals(5, index.resolve(msg, start, start + "ETHUSDT".length()));
    }

    @Test
    @DisplayName("a key this connection never subscribed resolves to -1")
    void missReturnsMinusOne() {
        SubscriptionIndex index = new SubscriptionIndex(List.of(BTC), Instrument::nativeSymbol);

        assertEquals(-1, index.resolve("ETHUSDT", 0, 7));
    }

    @Test
    @DisplayName("bytes: resolves an absolute [start, end) range, ignoring the buffer's position and leaving it unmoved")
    void resolvesByteRange() {
        SubscriptionIndex index = new SubscriptionIndex(List.of(BTC, ETH), Instrument::nativeSymbol);
        byte[] frame = "xx..ETHUSDTyy".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(frame);
        buf.position(1);

        assertEquals(5, index.resolve(buf, 4, 11));
        assertEquals(1, buf.position());
        assertEquals(frame.length, buf.limit());
    }

    @Test
    @DisplayName("bytes: decodes the key as UTF-8, so a CJK symbol resolves")
    void resolvesMultiByteUtf8() {
        SubscriptionIndex index = new SubscriptionIndex(List.of(BTC, LOBSTER), Instrument::nativeSymbol);
        byte[] key = "龙虾USDT".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(key.length + 2);
        buf.put(1, key);

        assertEquals(10, key.length);
        assertEquals(6, index.resolve(buf, 1, 1 + key.length));
    }

    @Test
    @DisplayName("bytes: a slice's arrayOffset is respected — indexes are relative to the slice, not the backing array")
    void resolvesInsideSlice() {
        SubscriptionIndex index = new SubscriptionIndex(List.of(BTC, ETH), Instrument::nativeSymbol);
        ByteBuffer slice = ByteBuffer.wrap("ETHUSDTBTCUSDT".getBytes(StandardCharsets.UTF_8)).slice(7, 7);

        assertEquals(7, slice.arrayOffset());
        assertEquals(4, index.resolve(slice, 0, 7));
    }

    @Test
    @DisplayName("bytes: a direct buffer resolves the same way")
    void resolvesDirectBuffer() {
        SubscriptionIndex index = new SubscriptionIndex(List.of(BTC, LOBSTER), Instrument::nativeSymbol);
        byte[] key = "龙虾USDT".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocateDirect(key.length).put(0, key);

        assertEquals(6, index.resolve(buf, 0, key.length));
    }

    @Test
    @DisplayName("bytes: a key this connection never subscribed resolves to -1")
    void byteMissReturnsMinusOne() {
        SubscriptionIndex index = new SubscriptionIndex(List.of(BTC), Instrument::nativeSymbol);

        assertEquals(-1, index.resolve(ByteBuffer.wrap("ETHUSDT".getBytes(StandardCharsets.UTF_8)), 0, 7));
    }

    @Test
    @DisplayName("two instruments sharing a routing key are rejected — one would receive the other's frames")
    void duplicateKeyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubscriptionIndex(List.of(BTC, ETH), i -> "same"));
    }
}
