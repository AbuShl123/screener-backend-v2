package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.TestVenueProps;
import dev.abu.screener_backend.marketdata.Exchange;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.bybit.BybitRestCooldown.Kind;
import dev.abu.screener_backend.marketdata.adapter.bybit.BybitSnapshotProperties.Cooldown;
import dev.abu.screener_backend.marketdata.adapter.bybit.BybitSnapshotProperties.MarketSnapshot;
import dev.abu.screener_backend.marketdata.adapter.bybit.BybitSnapshotProperties.VenueBlock;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.MarketEventPublisher;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.core.recovery.SnapshotQueueFactory;
import dev.abu.screener_backend.marketdata.core.recovery.SnapshotRequestQueue;
import dev.abu.screener_backend.marketdata.core.rest.ExchangeWebClientFactory;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;
import dev.abu.screener_backend.marketdata.spi.FakeRecoverySink;
import dev.abu.screener_backend.marketdata.spi.InstrumentSource;
import dev.abu.screener_backend.marketdata.spi.StreamProtocolRegistry;
import dev.abu.screener_backend.marketdata.spi.SyncStrategyRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Bybit adapter's bindings, and what core's registries make of them. An unbound venue would
 * otherwise surface far from its cause: as a startup failure in a core registry, or as a
 * {@code null} strategy on a consumer thread.
 */
class BybitAdapterConfigTest {

    private static final VenueProperties SPOT = venue(10);
    private static final VenueProperties FUTURES = venue(200);
    private static final SnapshotQueueProperties QUEUE =
            new SnapshotQueueProperties(10, Duration.ofMillis(250), Duration.ofSeconds(20));
    private static final Duration INTERVAL = Duration.ofMillis(100);
    private static final BybitSnapshotProperties SNAPSHOT = new BybitSnapshotProperties(Map.of(
            Market.SPOT, new VenueBlock(new MarketSnapshot(INTERVAL)),
            Market.FUTURES, new VenueBlock(new MarketSnapshot(INTERVAL))),
            new Cooldown(Duration.ofSeconds(10), Duration.ofMinutes(10)));

    private static VenueProperties venue(int chunkSize) {
        return TestVenueProps.venue().depth(TestVenueProps.stream()
                .maxStreamsPerConnection(300).connections(2, 8).chunk(chunkSize).heartbeatSeconds(20)).build();
    }

    private static ExchangesProperties exchanges(SnapshotQueueProperties queue) {
        return new ExchangesProperties(Map.of(Exchange.BYBIT,
                new ExchangeProperties(true, Map.of(Market.SPOT, SPOT, Market.FUTURES, FUTURES), queue)));
    }

    // --- Strategies, streams, discovery --------------------------------------------------------

    @Test
    @DisplayName("both Bybit venues resolve to their own BybitSyncStrategy, with a context per book")
    void bothStrategiesBound() {
        BybitAdapterConfig config = new BybitAdapterConfig();
        FakeRecoverySink sink = new FakeRecoverySink();
        PipelineMetrics metrics = new PipelineMetrics();
        SyncStrategyRegistry registry = new SyncStrategyRegistry(List.of(
                config.bybitSpotStrategyBinding(sink, metrics),
                config.bybitFuturesStrategyBinding(sink, metrics)));

        DepthSyncStrategy spot = registry.forVenue(Venue.BYBIT_SPOT);
        DepthSyncStrategy futures = registry.forVenue(Venue.BYBIT_FUTURES);

        assertInstanceOf(BybitSyncStrategy.class, spot);
        assertInstanceOf(BybitSyncStrategy.class, futures);
        assertNotSame(spot, futures, "one strategy instance per venue");
        assertNotSame(spot.newContext(), spot.newContext(), "newContext must be per book");
    }

    @Test
    @DisplayName("with Bybit enabled, the stream registry needs and accepts both bindings, one protocol per venue")
    void bothStreamsBound() {
        ExchangesProperties exchanges = exchanges(null);
        BybitAdapterConfig config = new BybitAdapterConfig();

        StreamProtocolRegistry registry = new StreamProtocolRegistry(List.of(
                config.bybitSpotStreamBinding(exchanges), config.bybitFuturesStreamBinding(exchanges)), exchanges);

        assertInstanceOf(BybitStreamProtocol.class, registry.forVenue(Venue.BYBIT_SPOT));
        assertInstanceOf(BybitStreamProtocol.class, registry.forVenue(Venue.BYBIT_FUTURES));
        assertNotSame(registry.forVenue(Venue.BYBIT_SPOT), registry.forVenue(Venue.BYBIT_FUTURES));
        // Spot is enabled by the same switch: a futures-only binding set fails at startup.
        assertThrows(IllegalStateException.class, () -> new StreamProtocolRegistry(
                List.of(config.bybitFuturesStreamBinding(exchanges)), exchanges));
    }

    @Test
    @DisplayName("a spot subscribe-chunk-size over Bybit's 10-arg limit fails at startup")
    void oversizedSpotChunkThrows() {
        VenueProperties spot = venue(11);
        ExchangesProperties exchanges = new ExchangesProperties(Map.of(Exchange.BYBIT,
                new ExchangeProperties(true, Map.of(Market.SPOT, spot, Market.FUTURES, FUTURES), null)));

        assertThrows(IllegalArgumentException.class, () -> new BybitAdapterConfig().bybitSpotStreamBinding(exchanges));
    }

    @Test
    @DisplayName("one instrument source claims both Bybit venues")
    void sourceClaimsBothVenues() {
        InstrumentSource source = new BybitAdapterConfig().bybitInstrumentSource(
                new BybitRestClient(Venue.BYBIT_SPOT, null), new BybitRestClient(Venue.BYBIT_FUTURES, null));

        assertEquals(Set.of(Venue.BYBIT_SPOT, Venue.BYBIT_FUTURES), source.venues());
    }

    // --- Snapshot recovery wiring --------------------------------------------------------------

    private static final MarketEventPublisher NO_OP_PUBLISHER = new MarketEventPublisher() {
        @Override public void publishFrame(StreamKind stream, int instrumentId, String payload) { }
        @Override public void publishFrame(StreamKind stream, int instrumentId, ByteBuffer payload) { }
        @Override public void publishSnapshot(int instrumentId, String payload) { }
        @Override public void publishSnapshotFailure(int instrumentId) { }
    };

    private final List<SnapshotQueueFactory> factories = new ArrayList<>();

    @AfterEach
    void stopQueues() {
        factories.forEach(SnapshotQueueFactory::shutdown);
    }

    private SnapshotQueueFactory queues(ExchangesProperties exchanges) {
        SnapshotQueueFactory factory = new SnapshotQueueFactory(NO_OP_PUBLISHER, new PipelineMetrics(), exchanges);
        factories.add(factory);
        return factory;
    }

    private static BybitRestCooldown cooldown() {
        return new BybitAdapterConfig().bybitRestCooldown(SNAPSHOT);
    }

    private static SnapshotRequestQueue spotQueue(SnapshotQueueFactory queues, ExchangesProperties exchanges,
                                                  BybitRestCooldown cooldown, BybitSnapshotProperties snapshot) {
        return new BybitAdapterConfig().bybitSpotSnapshotQueue(queues,
                new BybitRestClient(Venue.BYBIT_SPOT, null), cooldown, snapshot, exchanges);
    }

    private static SnapshotRequestQueue futuresQueue(SnapshotQueueFactory queues, ExchangesProperties exchanges,
                                                     BybitRestCooldown cooldown, BybitSnapshotProperties snapshot) {
        return new BybitAdapterConfig().bybitFuturesSnapshotQueue(queues,
                new BybitRestClient(Venue.BYBIT_FUTURES, null), cooldown, snapshot, exchanges);
    }

    @Test
    @DisplayName("each Bybit venue recovers through its own snapshot queue")
    void eachVenueHasItsOwnQueue() {
        ExchangesProperties exchanges = exchanges(QUEUE);
        SnapshotQueueFactory queues = queues(exchanges);
        BybitRestCooldown cooldown = cooldown();

        SnapshotRequestQueue spot = spotQueue(queues, exchanges, cooldown, SNAPSHOT);
        SnapshotRequestQueue futures = futuresQueue(queues, exchanges, cooldown, SNAPSHOT);

        assertEquals(Venue.BYBIT_SPOT, spot.venue());
        assertEquals(Venue.BYBIT_FUTURES, futures.venue());
        assertNotSame(spot, futures);
    }

    @Test
    @DisplayName("a pushback on one venue stops both queues: the cooldown is shared, as Bybit's limit is per IP")
    void cooldownIsShared() {
        // A one-hour flush interval keeps the queue from ever ticking, so an accepted request is
        // never actually sent.
        ExchangesProperties exchanges = exchanges(
                new SnapshotQueueProperties(10, Duration.ofHours(1), Duration.ofHours(2)));
        SnapshotQueueFactory queues = queues(exchanges);
        BybitRestCooldown cooldown = cooldown();
        SnapshotRequestQueue spot = spotQueue(queues, exchanges, cooldown, SNAPSHOT);
        SnapshotRequestQueue futures = futuresQueue(queues, exchanges, cooldown, SNAPSHOT);

        assertTrue(spot.requestRecovery(slot(Venue.BYBIT_SPOT, 0)), "an open queue accepts before any pushback");

        cooldown.start(Kind.RATE_LIMITED, System.currentTimeMillis(), "[BYBIT_SPOT] test");

        assertFalse(spot.requestRecovery(slot(Venue.BYBIT_SPOT, 1)));
        assertFalse(futures.requestRecovery(slot(Venue.BYBIT_FUTURES, 2)), "the other venue pauses too");
    }

    private static InstrumentSlot slot(Venue venue, int id) {
        return new InstrumentSlot(Instrument.of(id, venue, "BTCUSDT", "BTC", "USDT"), new OrderBook(0.1), null, null);
    }

    @Test
    @DisplayName("Bybit without a snapshot-queue block fails at startup")
    void missingSnapshotQueueBlockThrows() {
        ExchangesProperties exchanges = exchanges(null);

        assertThrows(IllegalStateException.class,
                () -> futuresQueue(queues(exchanges), exchanges, cooldown(), SNAPSHOT));
    }

    @Test
    @DisplayName("a Bybit venue without its snapshot block fails at startup")
    void missingVenueSnapshotBlockThrows() {
        ExchangesProperties exchanges = exchanges(QUEUE);
        BybitSnapshotProperties spotOnly = new BybitSnapshotProperties(
                Map.of(Market.SPOT, new VenueBlock(new MarketSnapshot(INTERVAL))), SNAPSHOT.cooldown());

        assertThrows(IllegalStateException.class,
                () -> futuresQueue(queues(exchanges), exchanges, cooldown(), spotOnly));
    }

    @Test
    @DisplayName("Bybit without a snapshot-cooldown block fails at startup")
    void missingCooldownBlockThrows() {
        assertThrows(IllegalStateException.class,
                () -> new BybitAdapterConfig().bybitRestCooldown(new BybitSnapshotProperties(SNAPSHOT.venues(), null)));
    }

    @Test
    @DisplayName("batch-timeout must cover a paced batch, not just one response: 10 × 100ms + 10s = 11s")
    void batchTimeoutMustCoverPacing() {
        ExchangesProperties tooShort = exchanges(new SnapshotQueueProperties(10, Duration.ofMillis(250), Duration.ofSeconds(11)));
        ExchangesProperties enough = exchanges(
                new SnapshotQueueProperties(10, Duration.ofMillis(250), Duration.ofSeconds(11).plusMillis(1)));

        assertThrows(IllegalStateException.class, () -> spotQueue(queues(tooShort), tooShort, cooldown(), SNAPSHOT));
        assertEquals(Venue.BYBIT_SPOT, spotQueue(queues(enough), enough, cooldown(), SNAPSHOT).venue());
    }

    // --- As Spring wires it --------------------------------------------------------------------

    @Test
    @DisplayName("in a Spring context, every bean resolves: both venues bound, one source, one shared cooldown")
    void springContextBindsBothVenues() {
        ExchangesProperties exchanges = exchanges(QUEUE);
        new ApplicationContextRunner()
                .withBean(ExchangesProperties.class, () -> exchanges)
                .withBean(PipelineMetrics.class)
                .withBean(ExchangeWebClientFactory.class)
                .withBean(SnapshotQueueFactory.class,
                        () -> new SnapshotQueueFactory(NO_OP_PUBLISHER, new PipelineMetrics(), exchanges))
                .withBean(SyncStrategyRegistry.class)
                .withBean(StreamProtocolRegistry.class)
                .withUserConfiguration(BybitAdapterConfig.class)
                .withPropertyValues(
                        "screener.exchanges.bybit.snapshot-cooldown.rate-limited=PT10S",
                        "screener.exchanges.bybit.snapshot-cooldown.banned=PT10M",
                        "screener.exchanges.bybit.venues.SPOT.snapshot.request-interval=PT0.1S",
                        "screener.exchanges.bybit.venues.FUTURES.snapshot.request-interval=PT0.1S")
                .run(context -> {
                    assertNull(context.getStartupFailure(), "the context must start");
                    SyncStrategyRegistry strategies = context.getBean(SyncStrategyRegistry.class);
                    StreamProtocolRegistry streams = context.getBean(StreamProtocolRegistry.class);
                    for (Venue venue : List.of(Venue.BYBIT_SPOT, Venue.BYBIT_FUTURES)) {
                        assertInstanceOf(BybitSyncStrategy.class, strategies.forVenue(venue));
                        assertInstanceOf(BybitStreamProtocol.class, streams.forVenue(venue));
                    }

                    // Each same-typed bean went to the right venue.
                    assertEquals(Venue.BYBIT_SPOT, context.getBean("bybitSpotRestClient", BybitRestClient.class).venue());
                    assertEquals(Venue.BYBIT_FUTURES, context.getBean("bybitFuturesRestClient", BybitRestClient.class).venue());
                    assertEquals(Venue.BYBIT_SPOT, context.getBean("bybitSpotSnapshotQueue", SnapshotRequestQueue.class).venue());
                    assertEquals(Venue.BYBIT_FUTURES,
                            context.getBean("bybitFuturesSnapshotQueue", SnapshotRequestQueue.class).venue());

                    assertEquals(Set.of(Venue.BYBIT_SPOT, Venue.BYBIT_FUTURES),
                            context.getBean(InstrumentSource.class).venues());
                    assertEquals(1, context.getBeansOfType(BybitRestCooldown.class).size(), "one cooldown for both venues");
                });
    }

    @Test
    @DisplayName("the shipped YAML builds every Bybit stream binding and snapshot queue")
    void shippedYaml() throws IOException {
        ClassPathResource resource = new ClassPathResource("application.yml");
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application.yml", resource).forEach(sources::addLast);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        ExchangesProperties exchanges = binder.bind("screener", ExchangesProperties.class).get();
        BybitSnapshotProperties snapshot = binder.bind("screener.exchanges.bybit", BybitSnapshotProperties.class).get();
        BybitAdapterConfig config = new BybitAdapterConfig();
        SnapshotQueueFactory queues = queues(exchanges);
        BybitRestCooldown cooldown = config.bybitRestCooldown(snapshot);

        // The stream protocol rejects a chunk size that breaks a server limit; the queue rejects a
        // batch-timeout that cannot cover a paced batch.
        assertDoesNotThrow(() -> config.bybitSpotStreamBinding(exchanges));
        assertDoesNotThrow(() -> config.bybitFuturesStreamBinding(exchanges));
        assertEquals(Venue.BYBIT_SPOT, spotQueue(queues, exchanges, cooldown, snapshot).venue());
        assertEquals(Venue.BYBIT_FUTURES, futuresQueue(queues, exchanges, cooldown, snapshot).venue());
    }
}
