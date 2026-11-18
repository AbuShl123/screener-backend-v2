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
            Map.of(Venue.BINANCE_FUTURES, Map.of("BTCUSDT", 1_000_000.0, "ETHUSDT", 400_000.0)), 10, 10));

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
    @DisplayName("no overrides at all: every instrument gets the default")
    void noOverrides() {
        TradeThresholds plain = new TradeThresholds(new TradeAlertProperties(5_000, null, 10, 10));

        assertEquals(5_000, plain.minNotional(instrument(Venue.BINANCE_FUTURES, "BTCUSDT")));
    }

    private static Instrument instrument(Venue venue, String symbol) {
        return Instrument.of(0, venue, symbol, symbol.replace("USDT", ""), "USDT");
    }
}
