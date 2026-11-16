package dev.abu.screener_backend.config;

import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.StreamProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.marketdata.Exchange;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.StreamKey;
import dev.abu.screener_backend.marketdata.StreamKind;
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
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExchangesProperties#isEnabled} — the single chokepoint for the {@code enabled} switches, per
 * venue and per stream — the bind-time "an enabled venue has an enabled stream" check, the
 * exchange-level {@code snapshot-queue} validation, and the shipped {@code application.yml}.
 */
class ExchangesPropertiesTest {

    private static final StreamKey SPOT_DEPTH = new StreamKey(Venue.BINANCE_SPOT, StreamKind.DEPTH);

    static VenueProperties venueProps() {
        return venueProps(true);
    }

    static VenueProperties venueProps(boolean enabled) {
        return TestVenueProps.venue().enabled(enabled).build();
    }

    private static ExchangesProperties binance(boolean enabled, Market... markets) {
        Map<Market, VenueProperties> venues = new EnumMap<>(Market.class);
        for (Market m : markets) venues.put(m, venueProps());
        return binance(enabled, venues);
    }

    private static ExchangesProperties binance(boolean enabled, Map<Market, VenueProperties> venues) {
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
    @DisplayName("a venue's enabled: false turns off that venue only; its sibling stays on")
    void venueDisabled() {
        ExchangesProperties props = binance(true, Map.of(
                Market.SPOT, venueProps(true),
                Market.FUTURES, venueProps(false)));

        assertTrue(props.isEnabled(Venue.BINANCE_SPOT));
        assertFalse(props.isEnabled(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("the exchange's enabled: false overrides a venue's enabled: true")
    void exchangeSwitchIsMaster() {
        ExchangesProperties props = binance(false, Map.of(
                Market.SPOT, venueProps(true),
                Market.FUTURES, venueProps(true)));

        assertFalse(props.isEnabled(Venue.BINANCE_SPOT));
        assertFalse(props.isEnabled(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("venue and stream blocks without an enabled key bind as enabled")
    void missingVenueFlagDefaultsToEnabled() {
        Map<String, String> yaml = new HashMap<>();
        yaml.put("screener.exchanges.binance.enabled", "true");
        for (String market : List.of("SPOT", "FUTURES")) {
            String prefix = "screener.exchanges.binance.venues." + market + ".streams.DEPTH.";
            yaml.put(prefix + "stream-url", "wss://example");
            yaml.put(prefix + "subscribe-chunk-size", "100");
            yaml.put(prefix + "heartbeat-interval-seconds", "120");
        }
        yaml.put("screener.exchanges.binance.venues.FUTURES.enabled", "false");

        ExchangesProperties props = bind(yaml);

        assertTrue(props.venue(Venue.BINANCE_SPOT).enabled());
        assertTrue(props.isEnabled(Venue.BINANCE_SPOT));
        assertTrue(props.stream(SPOT_DEPTH).enabled());
        assertTrue(props.isEnabled(SPOT_DEPTH));
        assertEquals("wss://example", props.stream(SPOT_DEPTH).streamUrl());
        assertFalse(props.isEnabled(Venue.BINANCE_FUTURES), "an explicit enabled: false still binds");
    }

    @Test
    @DisplayName("a stream is on only when its venue is on, its block exists, and the block's enabled is true")
    void streamEnabled() {
        assertTrue(binance(true, Market.SPOT).isEnabled(SPOT_DEPTH));

        assertFalse(binance(false, Market.SPOT).isEnabled(SPOT_DEPTH), "exchange off");
        assertFalse(binance(true, Map.of(Market.SPOT, venueProps(false))).isEnabled(SPOT_DEPTH), "venue off");
        assertFalse(binance(true, Market.FUTURES).isEnabled(SPOT_DEPTH), "no venue block");

        VenueProperties noBlock = TestVenueProps.venue().enabled(false).noStreams().build();
        assertFalse(binance(true, Map.of(Market.SPOT, noBlock)).isEnabled(SPOT_DEPTH), "no stream block");

        // An enabled venue whose DEPTH stream is on and whose TRADE block is off: the stream switch
        // alone decides, and the sibling stream is unaffected.
        StreamKey spotTrade = new StreamKey(Venue.BINANCE_SPOT, StreamKind.TRADE);
        VenueProperties tradeOff = TestVenueProps.venue()
                .stream(StreamKind.TRADE, TestVenueProps.stream().enabled(false))
                .build();
        ExchangesProperties props = binance(true, Map.of(Market.SPOT, tradeOff));
        assertTrue(props.isEnabled(Venue.BINANCE_SPOT));
        assertFalse(props.isEnabled(spotTrade), "stream block enabled: false");
        assertFalse(props.stream(spotTrade).enabled());
        assertTrue(props.isEnabled(SPOT_DEPTH));
    }

    @Test
    @DisplayName("stream() throws on a missing stream block, like venue()")
    void missingStreamBlockThrows() {
        VenueProperties noStreams = TestVenueProps.venue().enabled(false).noStreams().build();
        ExchangesProperties props = binance(true, Map.of(Market.SPOT, noStreams));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> props.stream(SPOT_DEPTH));
        assertTrue(e.getMessage().contains("streams.DEPTH"), e.getMessage());
    }

    @Test
    @DisplayName("an enabled venue with no enabled stream fails, pointing at streams.DEPTH")
    void enabledVenueWithoutStreamFails() {
        VenueProperties noStreams = TestVenueProps.venue().noStreams().build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> binance(true, Map.of(Market.SPOT, noStreams)));
        assertTrue(e.getMessage().contains("screener.exchanges.binance.venues.SPOT.streams.DEPTH"), e.getMessage());

        VenueProperties allOff = TestVenueProps.venue().depth(TestVenueProps.stream().enabled(false)).build();
        assertThrows(IllegalArgumentException.class, () -> binance(true, Map.of(Market.SPOT, allOff)));

        // Off venues and off exchanges are not checked: they never stream.
        binance(true, Map.of(Market.SPOT, TestVenueProps.venue().enabled(false).noStreams().build()));
        binance(false, Map.of(Market.SPOT, noStreams));
    }

    @Test
    @DisplayName("a venue block still in the old flat shape fails startup instead of going dark")
    void staleFlatConfigFails() {
        Map<String, String> yaml = new HashMap<>();
        yaml.put("screener.exchanges.binance.enabled", "true");
        String prefix = "screener.exchanges.binance.venues.SPOT.";
        // A real venue block also carries enabled and rest, which are what make the venue bind at all.
        yaml.put(prefix + "enabled", "true");
        yaml.put(prefix + "rest.base-url", "https://example");
        yaml.put(prefix + "stream-url", "wss://example");
        yaml.put(prefix + "subscribe-chunk-size", "100");
        yaml.put(prefix + "heartbeat-interval-seconds", "120");

        Exception e = assertThrows(Exception.class, () -> bind(yaml));
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        assertTrue(root.getMessage().contains("streams.DEPTH"), root.getMessage());
    }

    private static ExchangesProperties bind(Map<String, String> yaml) {
        return new Binder(new MapConfigurationPropertySource(yaml))
                .bind("screener", ExchangesProperties.class)
                .get();
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
    @DisplayName("the shipped application.yml binds: Binance and Bybit on, MEXC configured but off by default")
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
        assertTrue(props.venue(Venue.MEXC_SPOT).enabled(), "only the MEXC master switch is off");
        assertTrue(props.venue(Venue.MEXC_FUTURES).enabled(), "only the MEXC master switch is off");

        assertEquals(1, depth(props, Venue.MEXC_FUTURES).subscribeChunkSize());
        assertEquals(30, depth(props, Venue.MEXC_SPOT).maxStreamsPerConnection());
        assertEquals(0.01, props.venue(Venue.MEXC_SPOT).maxVisibleDistance());

        assertTrue(props.isEnabled(Venue.BYBIT_SPOT));
        assertTrue(props.isEnabled(Venue.BYBIT_FUTURES));
        assertTrue(depth(props, Venue.BYBIT_SPOT).subscribeChunkSize() <= 10,
                "a Bybit spot subscribe frame takes at most 10 args");

        for (Venue venue : Venue.values()) {
            StreamProperties depth = depth(props, venue);
            assertTrue(depth.enabled(), venue + " DEPTH stream is on");
            assertTrue(depth.streamUrl() != null && depth.streamUrl().startsWith("wss://"), venue + " DEPTH stream-url");
            assertTrue(depth.maxConnections() >= depth.minConnections() && depth.minConnections() > 0,
                    venue + " DEPTH connection caps");
            assertTrue(props.isEnabled(new StreamKey(venue, StreamKind.DEPTH)) == props.isEnabled(venue),
                    venue + " DEPTH follows its venue's switch");
        }

        // TRADE: both Binance venues ship a block (their bindings read it), off by default.
        for (Venue venue : List.of(Venue.BINANCE_SPOT, Venue.BINANCE_FUTURES)) {
            StreamKey trade = new StreamKey(venue, StreamKind.TRADE);
            StreamProperties block = props.stream(trade);
            assertFalse(block.enabled(), venue + " TRADE is off by default");
            assertFalse(props.isEnabled(trade));
            assertTrue(block.streamUrl() != null && block.streamUrl().startsWith("wss://"), venue + " TRADE stream-url");
            assertTrue(block.maxConnections() >= block.minConnections() && block.minConnections() > 0,
                    venue + " TRADE connection caps");
        }
        // Binance futures routes by path, and a stream on the wrong one is acked and then silent.
        assertEquals("wss://fstream.binance.com/public/ws", depth(props, Venue.BINANCE_FUTURES).streamUrl());
        assertEquals("wss://fstream.binance.com/market/ws",
                props.stream(new StreamKey(Venue.BINANCE_FUTURES, StreamKind.TRADE)).streamUrl());
    }

    private static StreamProperties depth(ExchangesProperties props, Venue venue) {
        return props.stream(new StreamKey(venue, StreamKind.DEPTH));
    }
}
