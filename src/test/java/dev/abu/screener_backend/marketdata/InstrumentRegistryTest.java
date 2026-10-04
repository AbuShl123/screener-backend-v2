package dev.abu.screener_backend.marketdata;

import dev.abu.screener_backend.marketdata.spi.InstrumentCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentRegistryTest {

    @Test
    @DisplayName("isTracked matches the normalized symbol on any exchange, per market")
    void isTrackedAcrossExchanges() {
        InstrumentRegistry registry = new InstrumentRegistry();
        registry.register(Venue.BINANCE_SPOT, "ETHUSDT", "ETH", "USDT", 1.0);
        registry.register(Venue.MEXC_FUTURES, "BTC_USDT", "BTC", "USDT", 0.0001);

        assertTrue(registry.isTracked("BTCUSDT", Market.FUTURES));   // MEXC only
        assertTrue(registry.isTracked("ETHUSDT", Market.SPOT));      // Binance only
        assertFalse(registry.isTracked("BTCUSDT", Market.SPOT));
        assertFalse(registry.isTracked("ETHUSDT", Market.FUTURES));
        assertFalse(registry.isTracked("BTC_USDT", Market.FUTURES)); // native spelling is not a symbol
    }

    @Test
    @DisplayName("re-registering keeps the original instrument, multiplier included")
    void reRegisterKeepsMultiplier() {
        InstrumentRegistry registry = new InstrumentRegistry();
        Instrument first = registry.register(Venue.MEXC_FUTURES, "BTC_USDT", "BTC", "USDT", 0.0001);
        Instrument again = registry.register(Venue.MEXC_FUTURES, "BTC_USDT", "BTC", "USDT", 0.001);

        assertSame(first, again);
        assertEquals(0.0001, again.quantityMultiplier());
    }

    @Test
    @DisplayName("a candidate defaults to multiplier 1.0 and rejects a non-positive or non-finite one")
    void candidateMultiplier() {
        assertEquals(1.0, new InstrumentCandidate("BTCUSDT", "BTC", "USDT").quantityMultiplier());

        assertThrows(IllegalArgumentException.class, () -> new InstrumentCandidate("X_USDT", "X", "USDT", 0));
        assertThrows(IllegalArgumentException.class, () -> new InstrumentCandidate("X_USDT", "X", "USDT", -1));
        assertThrows(IllegalArgumentException.class, () -> new InstrumentCandidate("X_USDT", "X", "USDT", Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new InstrumentCandidate("X_USDT", "X", "USDT", Double.POSITIVE_INFINITY));
    }
}
