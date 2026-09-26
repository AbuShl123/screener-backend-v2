package dev.abu.screener_backend.config;

import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.exchange.Exchange;
import dev.abu.screener_backend.exchange.Market;
import dev.abu.screener_backend.exchange.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ExchangesProperties#isEnabled} — the single chokepoint for the {@code enabled} switch. */
class ExchangesPropertiesTest {

    static VenueProperties venueProps() {
        return new VenueProperties("wss://example", "https://example", "{symbol}@depth", 1024, 1, 1000, 1, 1, 100, 120);
    }

    private static ExchangesProperties binance(boolean enabled, Market... markets) {
        Map<Market, VenueProperties> venues = new EnumMap<>(Market.class);
        for (Market m : markets) venues.put(m, venueProps());
        return new ExchangesProperties(Map.of(Exchange.BINANCE, new ExchangeProperties(enabled, venues)));
    }

    @Test
    @DisplayName("an enabled exchange with a venue block is enabled")
    void enabled() {
        ExchangesProperties props = binance(true, Market.SPOT, Market.FUTURES);

        assertTrue(props.isEnabled(Venue.BINANCE_SPOT));
        assertTrue(props.isEnabled(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("enabled: false disables every venue of the exchange")
    void disabled() {
        ExchangesProperties props = binance(false, Market.SPOT, Market.FUTURES);

        assertFalse(props.isEnabled(Venue.BINANCE_SPOT));
        assertFalse(props.isEnabled(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("a missing exchange block means disabled, not an exception")
    void missingExchangeBlock() {
        assertFalse(new ExchangesProperties(Map.of()).isEnabled(Venue.BINANCE_SPOT));
        assertFalse(new ExchangesProperties(null).isEnabled(Venue.BINANCE_SPOT));
    }

    @Test
    @DisplayName("a venue with no transport block is disabled even when its exchange is enabled")
    void missingVenueBlock() {
        ExchangesProperties props = binance(true, Market.SPOT);

        assertTrue(props.isEnabled(Venue.BINANCE_SPOT));
        assertFalse(props.isEnabled(Venue.BINANCE_FUTURES));
    }
}
