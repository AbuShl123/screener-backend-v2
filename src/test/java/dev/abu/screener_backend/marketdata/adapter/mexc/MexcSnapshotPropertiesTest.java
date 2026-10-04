package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcAdapterConfig;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcSnapshotProperties;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcSnapshotProperties.MarketSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MexcSnapshotPropertiesTest {

    private static final Duration INTERVAL = Duration.ofMillis(250);
    private static final Duration THROTTLE = Duration.ofSeconds(3);
    private static final Duration WAF = Duration.ofSeconds(90);

    @Test
    @DisplayName("the snapshot block binds from the venue block, next to core's keys")
    void binds() {
        MexcSnapshotProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "screener.exchanges.mexc.enabled", "true",                            // sibling keys ignored
                "screener.exchanges.mexc.snapshot-queue.max-batch-size", "12",
                "screener.exchanges.mexc.venues.FUTURES.rest.base-url", "https://x",  // core's venue keys ignored
                "screener.exchanges.mexc.venues.FUTURES.snapshot.depth-limit", "1500",
                "screener.exchanges.mexc.venues.FUTURES.snapshot.request-interval", "PT0.25S",
                "screener.exchanges.mexc.venues.FUTURES.snapshot.throttle-cooldown", "PT3S",
                "screener.exchanges.mexc.venues.FUTURES.snapshot.waf-cooldown", "PT90S")))
                .bind("screener.exchanges.mexc", MexcSnapshotProperties.class)
                .get();

        assertEquals(new MarketSnapshot(1500, INTERVAL, THROTTLE, WAF), props.forMarket(Market.FUTURES));
    }

    @Test
    @DisplayName("a market without a snapshot block fails when asked for")
    void missingBlock() {
        assertThrows(IllegalStateException.class, () -> new MexcSnapshotProperties(null).forMarket(Market.FUTURES));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1501})
    @DisplayName("depth-limit outside [1, 1500] is rejected")
    void badDepthLimit(int depthLimit) {
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot(depthLimit, INTERVAL, THROTTLE, WAF));
    }

    @Test
    @DisplayName("non-positive or missing durations are rejected")
    void badDurations() {
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot(1500, Duration.ZERO, THROTTLE, WAF));
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot(1500, INTERVAL, null, WAF));
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot(1500, INTERVAL, THROTTLE, Duration.ofSeconds(-1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-local.yml"})
    @DisplayName("the shipped YAML binds, and its batch-timeout covers a paced batch")
    void shippedYaml(String file) throws IOException {
        ClassPathResource resource = new ClassPathResource(file);
        // application-local.yml is gitignored: present on a dev box, absent on a fresh checkout.
        assumeTrue(resource.exists(), file + " not on the classpath");
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load(file, resource).forEach(sources::addLast);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));

        MarketSnapshot snapshot = binder.bind("screener.exchanges.mexc", MexcSnapshotProperties.class).get()
                .forMarket(Market.FUTURES);
        ExchangesProperties exchanges = binder.bind("screener", ExchangesProperties.class).get();

        assertEquals(new MarketSnapshot(1500, INTERVAL, THROTTLE, WAF), snapshot);
        assertDoesNotThrow(() -> MexcAdapterConfig.requireBatchTimeoutCoversPacing(exchanges, Venue.MEXC_FUTURES, snapshot));
    }
}
