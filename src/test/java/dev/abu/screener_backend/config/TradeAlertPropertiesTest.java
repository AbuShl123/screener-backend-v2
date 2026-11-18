package dev.abu.screener_backend.config;

import dev.abu.screener_backend.marketdata.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
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
    @DisplayName("the shipped application.yml and trade-thresholds.yml, loaded together, bind every threshold")
    void shippedYamlBinds() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        // The thresholds come only from the import: application.yml no longer carries any.
        loader.load("application.yml", new ClassPathResource("application.yml")).forEach(sources::addLast);
        loader.load("trade-thresholds.yml", new ClassPathResource("trade-thresholds.yml")).forEach(sources::addLast);
        TradeAlertProperties props = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources))
                .bind("screener.trade-alerts", TradeAlertProperties.class)
                .get();

        assertEquals(30_000, props.defaultMinNotional());
        assertEquals(100, props.recentCapacity());
        assertEquals(100, props.replaySize());
        assertEquals(Map.of(Venue.MEXC_SPOT, 10_000.0), props.venueDefaultMinNotional());
        assertEquals(Set.of(Venue.BINANCE_SPOT, Venue.BINANCE_FUTURES, Venue.MEXC_FUTURES),
                props.minNotional().keySet());

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

        // MEXC futures native symbols carry an underscore, which binds unchanged.
        Map<String, Double> mexcFutures = props.minNotional().get(Venue.MEXC_FUTURES);
        assertEquals(5, mexcFutures.size(), String.valueOf(mexcFutures));
        assertEquals(750_000, mexcFutures.get("BTC_USDT"));
        assertEquals(150_000, mexcFutures.get("XRP_USDT"));

        // Every override raises the floor; none is at or below its venue's default.
        props.minNotional().forEach((venue, symbols) -> symbols.forEach((symbol, value) ->
                assertTrue(value > props.venueDefaultMinNotional().getOrDefault(venue, props.defaultMinNotional()),
                        venue + " " + symbol + "=" + value)));

        // The idle close (trade-mexc-plan §2.6): MEXC futures only, so the tick runs.
        assertEquals(Map.of(Venue.MEXC_FUTURES, 1500L), props.sweepIdleCloseMs());
        assertEquals(250, props.tickIntervalMs());
        assertTrue(props.idleCloseEnabled());
    }

    @Test
    @DisplayName("application.yml's spring.config.import pulls trade-thresholds.yml into the context")
    void importResolvesInContext() {
        // application.yml sets web-application-type: servlet; a command-line argument outranks it.
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(PropertiesOnly.class)
                .logStartupInfo(false)
                .run("--spring.main.web-application-type=none")) {
            TradeAlertProperties props = ctx.getBean(TradeAlertProperties.class);
            assertEquals(30_000, props.defaultMinNotional());
            assertEquals(10_000, props.venueDefaultMinNotional().get(Venue.MEXC_SPOT));
            assertEquals(5_000_000, props.minNotional().get(Venue.BINANCE_FUTURES).get("BTCUSDT"));
            assertEquals(750_000, props.minNotional().get(Venue.MEXC_FUTURES).get("BTC_USDT"));
            assertEquals(250, props.tickIntervalMs());
        }
    }

    /** Binds only {@link TradeAlertProperties}: no auto-configuration, so no datasource is needed. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TradeAlertProperties.class)
    static class PropertiesOnly {
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
        assertEquals(Map.of(), props.venueDefaultMinNotional());
    }

    @Test
    @DisplayName("venue-default-min-notional binds per venue")
    void venueDefaultPerVenue() {
        TradeAlertProperties props = bind(Map.of(
                "screener.trade-alerts.default-min-notional", "30000",
                "screener.trade-alerts.venue-default-min-notional.MEXC_SPOT", "10000",
                "screener.trade-alerts.venue-default-min-notional.BYBIT_SPOT", "20000",
                "screener.trade-alerts.recent-capacity", "10",
                "screener.trade-alerts.replay-size", "5",
                "screener.trade-alerts.tick-interval-ms", "250"));

        assertEquals(Map.of(Venue.MEXC_SPOT, 10_000.0, Venue.BYBIT_SPOT, 20_000.0), props.venueDefaultMinNotional());
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
    @DisplayName("a missing or non-positive default, venue default, capacity, replay size, override, idle close or tick fails startup")
    void invalidValuesFail() {
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(0, null, null, 10, 10, null, 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(Double.NaN, null, null, 10, 10, null, 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000, null, null, 0, 10, null, 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000, null, null, 10, 0, null, 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000, null,
                Map.of(Venue.BINANCE_SPOT, Map.of("BTCUSDT", -1.0)), 10, 10, null, 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000, null, null, 10, 10,
                Map.of(Venue.MEXC_FUTURES, 0L), 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000, null, null, 10, 10, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000,
                Map.of(Venue.MEXC_SPOT, 0.0), null, 10, 10, null, 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000,
                Map.of(Venue.MEXC_SPOT, -5.0), null, 10, 10, null, 250));
        assertThrows(IllegalArgumentException.class, () -> new TradeAlertProperties(30_000,
                Map.of(Venue.MEXC_SPOT, Double.NaN), null, 10, 10, null, 250));
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
