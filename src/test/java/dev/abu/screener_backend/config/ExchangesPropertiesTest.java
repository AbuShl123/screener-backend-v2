package dev.abu.screener_backend.config;

import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.marketdata.Exchange;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExchangesProperties#isEnabled} — the single chokepoint for the {@code enabled} switch — the
 * exchange-level {@code snapshot-queue} validation, and the shipped {@code application.yml}.
 */
class ExchangesPropertiesTest {

    static VenueProperties venueProps() {
        RestProperties rest = new RestProperties("https://example", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));
        return new VenueProperties("wss://example", rest, "{symbol}@depth", 1024, 1, 1, 100, 120, null);
    }

    private static ExchangesProperties binance(boolean enabled, Market... markets) {
        Map<Market, VenueProperties> venues = new EnumMap<>(Market.class);
        for (Market m : markets) venues.put(m, venueProps());
        return new ExchangesProperties(Map.of(Exchange.BINANCE, new ExchangeProperties(enabled, venues, null)));
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

    @Test
    @DisplayName("a snapshot-queue block with a non-positive batch size or duration is rejected")
    void badSnapshotQueueBlock() {
        Duration ok = Duration.ofMillis(250);
        new SnapshotQueueProperties(10, ok, Duration.ofSeconds(30));

        assertThrows(IllegalArgumentException.class, () -> new SnapshotQueueProperties(0, ok, ok));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotQueueProperties(10, Duration.ZERO, ok));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotQueueProperties(10, ok, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotQueueProperties(10, null, ok));
    }

    @Test
    @DisplayName("the shipped application.yml binds: Binance on, both MEXC venues configured but off by default")
    void shippedYamlBinds() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        // Resolves ${MEXC_ENABLED:false} etc. to their defaults — no environment is attached.
        ExchangesProperties props = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources))
                .bind("screener", ExchangesProperties.class)
                .get();

        assertEquals(0.01, props.venue(Venue.MEXC_FUTURES).maxVisibleDistance());
        assertNull(props.venue(Venue.BINANCE_FUTURES).maxVisibleDistance());
        assertTrue(props.isEnabled(Venue.BINANCE_SPOT));
        assertTrue(props.isEnabled(Venue.BINANCE_FUTURES));
        assertFalse(props.isEnabled(Venue.MEXC_FUTURES));
        assertFalse(props.isEnabled(Venue.MEXC_SPOT));

        VenueProperties mexc = props.venue(Venue.MEXC_FUTURES);
        assertEquals("BTC_USDT", mexc.streamTopic("BTC_USDT"));
        assertEquals(1, mexc.subscribeChunkSize());

        VenueProperties mexcSpot = props.venue(Venue.MEXC_SPOT);
        assertEquals("spot@public.aggre.depth.v3.api.pb@100ms@BTCUSDT", mexcSpot.streamTopic("BTCUSDT"));
        assertEquals(30, mexcSpot.maxStreamsPerConnection());
        assertEquals(0.01, mexcSpot.maxVisibleDistance());
    }
}
