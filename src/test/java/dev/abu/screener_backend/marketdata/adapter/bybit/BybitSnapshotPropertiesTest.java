package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.TestVenueProps;
import dev.abu.screener_backend.marketdata.Exchange;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.bybit.BybitSnapshotProperties.Cooldown;
import dev.abu.screener_backend.marketdata.adapter.bybit.BybitSnapshotProperties.MarketSnapshot;
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
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BybitSnapshotPropertiesTest {

    private static final Duration INTERVAL = Duration.ofMillis(100);
    private static final Cooldown COOLDOWN = new Cooldown(Duration.ofSeconds(10), Duration.ofMinutes(10));

    @Test
    @DisplayName("binds per-venue pacing and the exchange-level cooldown, next to core's keys")
    void binds() {
        BybitSnapshotProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "screener.exchanges.bybit.enabled", "true",                            // sibling keys ignored
                "screener.exchanges.bybit.snapshot-queue.max-batch-size", "10",
                "screener.exchanges.bybit.snapshot-cooldown.rate-limited", "PT10S",
                "screener.exchanges.bybit.snapshot-cooldown.banned", "PT10M",
                "screener.exchanges.bybit.venues.FUTURES.rest.base-url", "https://x",  // core's venue keys ignored
                "screener.exchanges.bybit.venues.FUTURES.snapshot.request-interval", "PT0.1S")))
                .bind("screener.exchanges.bybit", BybitSnapshotProperties.class)
                .get();

        assertEquals(new MarketSnapshot(INTERVAL), props.forMarket(Market.FUTURES));
        assertEquals(COOLDOWN, props.cooldown());
    }

    @Test
    @DisplayName("a missing snapshot or snapshot-cooldown block fails when asked for")
    void missingBlocks() {
        BybitSnapshotProperties empty = new BybitSnapshotProperties(null, null);

        assertThrows(IllegalStateException.class, () -> empty.forMarket(Market.SPOT));
        assertThrows(IllegalStateException.class, empty::cooldown);
    }

    @Test
    @DisplayName("a non-positive or missing request-interval is rejected")
    void badInterval() {
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new MarketSnapshot(null));
    }

    @Test
    @DisplayName("batch-timeout must cover a paced batch, not just one response: 10 × 100ms + 10s = 11s")
    void batchTimeoutMustCoverPacing() {
        MarketSnapshot pacing = new MarketSnapshot(INTERVAL);

        assertThrows(IllegalStateException.class, () -> BybitSnapshotProperties.requireBatchTimeoutCoversPacing(
                exchanges(Duration.ofSeconds(11)), Venue.BYBIT_FUTURES, pacing));
        assertDoesNotThrow(() -> BybitSnapshotProperties.requireBatchTimeoutCoversPacing(
                exchanges(Duration.ofSeconds(11).plusMillis(1)), Venue.BYBIT_FUTURES, pacing));
    }

    private static ExchangesProperties exchanges(Duration batchTimeout) {
        VenueProperties venue = TestVenueProps.venue().build(); // its REST response timeout is the 10 s above
        return new ExchangesProperties(Map.of(Exchange.BYBIT, new ExchangeProperties(true,
                Map.of(Market.FUTURES, venue), new SnapshotQueueProperties(10, Duration.ofMillis(250), batchTimeout))));
    }

    @Test
    @DisplayName("the shipped YAML binds for both venues, builds the cooldown, and its batch-timeout covers a paced batch")
    void shippedYaml() throws IOException {
        ClassPathResource resource = new ClassPathResource("application.yml");
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application.yml", resource).forEach(sources::addLast);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));

        BybitSnapshotProperties props = binder.bind("screener.exchanges.bybit", BybitSnapshotProperties.class).get();
        ExchangesProperties exchanges = binder.bind("screener", ExchangesProperties.class).get();

        assertEquals(COOLDOWN, props.cooldown());
        assertDoesNotThrow(() -> new BybitRestCooldown(props.cooldown().rateLimited(), props.cooldown().banned()));
        for (Venue venue : new Venue[]{Venue.BYBIT_SPOT, Venue.BYBIT_FUTURES}) {
            MarketSnapshot pacing = props.forMarket(venue.market());
            assertEquals(new MarketSnapshot(INTERVAL), pacing);
            assertDoesNotThrow(() -> BybitSnapshotProperties.requireBatchTimeoutCoversPacing(exchanges, venue, pacing));
        }
    }
}
