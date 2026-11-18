package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeThresholdsTest {

    private final TradeThresholds thresholds = new TradeThresholds(new TradeAlertProperties(30_000,
            Map.of(Venue.MEXC_SPOT, 10_000.0, Venue.MEXC_FUTURES, 20_000.0),
            Map.of(Venue.BINANCE_FUTURES, Map.of("BTCUSDT", 1_000_000.0, "ETHUSDT", 400_000.0),
                    Venue.MEXC_FUTURES, Map.of("BTC_USDT", 750_000.0)),
            10, 10, null, 250));

    @Test
    @DisplayName("an instrument without an override gets the default")
    void defaultApplies() {
        assertEquals(30_000, thresholds.minNotional(instrument(Venue.BINANCE_FUTURES, "SOLUSDT")));
    }

    @Test
    @DisplayName("an override applies to its venue and native symbol")
    void overrideApplies() {
        assertEquals(1_000_000, thresholds.minNotional(instrument(Venue.BINANCE_FUTURES, "BTCUSDT")));
        assertEquals(400_000, thresholds.minNotional(instrument(Venue.BINANCE_FUTURES, "ETHUSDT")));
    }

    @Test
    @DisplayName("an override for one venue does not leak into the same symbol on another")
    void overrideDoesNotLeak() {
        assertEquals(30_000, thresholds.minNotional(instrument(Venue.BINANCE_SPOT, "BTCUSDT")));
        assertEquals(30_000, thresholds.minNotional(instrument(Venue.BYBIT_FUTURES, "BTCUSDT")));
    }

    @Test
    @DisplayName("lookup order: a symbol override beats the venue default, which beats the global default")
    void lookupOrder() {
        assertEquals(750_000, thresholds.minNotional(instrument(Venue.MEXC_FUTURES, "BTC_USDT")));
        assertEquals(20_000, thresholds.minNotional(instrument(Venue.MEXC_FUTURES, "ETH_USDT")));
        assertEquals(10_000, thresholds.minNotional(instrument(Venue.MEXC_SPOT, "BTCUSDT")));
        assertEquals(30_000, thresholds.minNotional(instrument(Venue.BINANCE_SPOT, "SOLUSDT")));
    }

    @Test
    @DisplayName("a venue default for one venue does not apply to another")
    void venueDefaultDoesNotLeak() {
        assertEquals(30_000, thresholds.minNotional(instrument(Venue.BYBIT_SPOT, "BTCUSDT")));
        assertEquals(30_000, thresholds.minNotional(instrument(Venue.BINANCE_FUTURES, "SOLUSDT")));
    }

    @Test
    @DisplayName("no overrides at all: every instrument gets the default")
    void noOverrides() {
        TradeThresholds plain = new TradeThresholds(new TradeAlertProperties(5_000, null, null, 10, 10, null, 250));

        assertEquals(5_000, plain.minNotional(instrument(Venue.BINANCE_FUTURES, "BTCUSDT")));
    }

    private static Instrument instrument(Venue venue, String symbol) {
        return Instrument.of(0, venue, symbol, symbol.replace("USDT", ""), "USDT");
    }
}
