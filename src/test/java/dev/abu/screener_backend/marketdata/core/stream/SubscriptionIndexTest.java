package dev.abu.screener_backend.marketdata.core.stream;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubscriptionIndexTest {

    private static final Instrument BTC = Instrument.of(4, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT");
    private static final Instrument ETH = Instrument.of(5, Venue.BINANCE_SPOT, "ETHUSDT", "ETH", "USDT");

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
    @DisplayName("two instruments sharing a routing key are rejected — one would receive the other's frames")
    void duplicateKeyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubscriptionIndex(List.of(BTC, ETH), i -> "same"));
    }
}
