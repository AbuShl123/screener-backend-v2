package dev.abu.screener_backend.config;

import dev.abu.screener_backend.marketdata.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeAlertPropertiesTest {

    @Test
    @DisplayName("the shipped application.yml binds the slice 0 thresholds (trade-detection-plan §10.2)")
    void shippedYamlBinds() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        TradeAlertProperties props = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources))
                .bind("screener.trade-alerts", TradeAlertProperties.class)
                .get();

        assertEquals(30_000, props.defaultMinNotional());
        assertEquals(100, props.recentCapacity());
        assertEquals(100, props.replaySize());
        assertEquals(Set.of(Venue.BINANCE_SPOT, Venue.BINANCE_FUTURES), props.minNotional().keySet());

        // Symbol keys keep their case: overrides are matched against Instrument.nativeSymbol.
        Map<String, Double> spot = props.minNotional().get(Venue.BINANCE_SPOT);
        assertEquals(7, spot.size(), String.valueOf(spot));
        assertEquals(500_000, spot.get("BTCUSDT"));
        assertEquals(50_000, spot.get("ZECUSDT"));

        Map<String, Double> futures = props.minNotional().get(Venue.BINANCE_FUTURES);
        assertEquals(28, futures.size(), String.valueOf(futures));
        assertEquals(5_000_000, futures.get("BTCUSDT"));
        assertEquals(3_000_000, futures.get("ETHUSDT"));
        // A key starting with a digit binds unchanged.
        assertEquals(150_000, futures.get("1000PEPEUSDT"));
        assertEquals(50_000, futures.get("WLDUSDT"));

        // Every override raises the floor; none is at or below the default.
        props.minNotional().values().forEach(symbols -> symbols.forEach((symbol, value) ->
                assertTrue(value > props.defaultMinNotional(), symbol + "=" + value)));

        // The idle close (trade-mexc-plan §2.6): MEXC futures only, so the tick runs.
        assertEquals(Map.of(Venue.MEXC_FUTURES, 1500L), props.sweepIdleCloseMs());
        assertEquals(250, props.tickIntervalMs());
        assertTrue(props.idleCloseEnabled());
    }

    @Test
    @DisplayName("a missing min-notional block binds as no overrides")
    void noOverrides() {
        TradeAlertProperties props = bind(Map.of(
                "screener.trade-alerts.default-min-notional", "30000",
                "screener.trade-alerts.recent-capacity", "10",
                "screener.trade-alerts.replay-size", "5",
                "screener.trade-alerts.tick-interval-ms", "250"));

        assertEquals(30_000, props.defaultMinNotional());
        assertEquals(5, props.replaySize());
        assertEquals(Map.of(), props.minNotional());
    }

    @Test
    @DisplayName("a missing sweep-idle-close-ms block binds as no idle close, so the tick does not run")
    void noIdleClose() {
        TradeAlertProperties props = bind(Map.of(
                "screener.trade-alerts.default-min-notional", "30000",
                "screener.trade-alerts.recent-capacity", "10",
                "screener.trade-alerts.replay-size", "5",
                "screener.trade-alerts.tick-interval-ms", "250"));

        assertEquals(Map.of(), props.sweepIdleCloseMs());
        assertFalse(props.idleCloseEnabled());
    }

    @Test
    @DisplayName("sweep-idle-close-ms binds per venue")
    void idleClosePerVenue() {
        TradeAlertProperties props = bind(Map.of(
                "screener.trade-alerts.default-min-notional", "30000",
                "screener.trade-alerts.recent-capacity", "10",
                "screener.trade-alerts.replay-size", "5",
                "screener.trade-alerts.tick-interval-ms", "100",
                "screener.trade-alerts.sweep-idle-close-ms.MEXC_FUTURES", "1500",
                "screener.trade-alerts.sweep-idle-close-ms.BINANCE_SPOT", "2000"));

        assertEquals(Map.of(Venue.MEXC_FUTURES, 1500L, Venue.BINANCE_SPOT, 2000L), props.sweepIdleCloseMs());
        assertEquals(100, props.tickIntervalMs());
        assertTrue(props.idleCloseEnabled());
    }

    @Test
    @DisplayName("a missing or non-positive default, capacity, replay size, override, idle close or tick fails startup")
    void invalidValuesFail() {
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(0, null, 10, 10, null, 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(Double.NaN, null, 10, 10, null, 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000, null, 0, 10, null, 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000, null, 10, 0, null, 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000,
                Map.of(Venue.BINANCE_SPOT, Map.of("BTCUSDT", -1.0)), 10, 10, null, 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000, null, 10, 10,
                Map.of(Venue.MEXC_FUTURES, 0L), 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000, null, 10, 10, null, 0));
        // A missing tick-interval-ms binds 0 and fails.
        assertThrows(Exception.class, () -> bind(Map.of(
                "screener.trade-alerts.default-min-notional", "30000",
                "screener.trade-alerts.recent-capacity", "10",
                "screener.trade-alerts.replay-size", "10")));
        // The block left out entirely binds 0 and fails rather than alerting on every trade.
        assertThrows(Exception.class, () -> bind(Map.of(
                "screener.trade-alerts.recent-capacity", "10",
                "screener.trade-alerts.replay-size", "10",
                "screener.trade-alerts.tick-interval-ms", "250")));
        // A missing replay-size binds 0 and fails too.
        assertThrows(Exception.class, () -> bind(Map.of(
                "screener.trade-alerts.default-min-notional", "30000",
                "screener.trade-alerts.recent-capacity", "10",
                "screener.trade-alerts.tick-interval-ms", "250")));
    }

    private static TradeAlertProperties bind(Map<String, String> yaml) {
        return new Binder(new MapConfigurationPropertySource(yaml))
                .bind("screener.trade-alerts", TradeAlertProperties.class)
                .get();
    }
}
