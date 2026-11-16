package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.TestVenueProps;
import dev.abu.screener_backend.marketdata.Exchange;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.StreamKey;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.*;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.MarketEventPublisher;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcSnapshotProperties.MarketSnapshot;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcSnapshotProperties.VenueBlock;
import dev.abu.screener_backend.marketdata.core.recovery.SnapshotQueueFactory;
import dev.abu.screener_backend.marketdata.core.recovery.SnapshotRequestQueue;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;
import dev.abu.screener_backend.marketdata.spi.FakeRecoverySink;
import dev.abu.screener_backend.marketdata.spi.StreamProtocolRegistry;
import dev.abu.screener_backend.marketdata.spi.SyncStrategyRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The MEXC adapter's bindings, and what core's registries make of them. */
class MexcAdapterConfigTest {

    /** {@link TestVenueProps#REST}'s 10 s response timeout is what {@link #batchTimeoutMustCoverPacing} counts on. */
    private static final VenueProperties FUTURES = TestVenueProps.venue().depth(TestVenueProps.stream()
            .maxStreamsPerConnection(300).connections(1, 8).chunk(1).heartbeatSeconds(15)).build();
    private static final VenueProperties SPOT = TestVenueProps.venue().depth(TestVenueProps.stream()
            .maxStreamsPerConnection(30).connections(1, 30).chunk(30).heartbeatSeconds(20)).build();
    private static final SnapshotQueueProperties QUEUE =
            new SnapshotQueueProperties(12, Duration.ofMillis(250), Duration.ofSeconds(20));
    private static final MexcSnapshotProperties SNAPSHOT = new MexcSnapshotProperties(Map.of(
            Market.FUTURES, new VenueBlock(new MarketSnapshot(1500, Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofSeconds(90))),
            Market.SPOT, new VenueBlock(new MarketSnapshot(2000, Duration.ofMillis(100), Duration.ofSeconds(3), Duration.ofSeconds(90)))));

    private static ExchangesProperties exchanges(SnapshotQueueProperties queue) {
        return new ExchangesProperties(Map.of(Exchange.MEXC,
                new ExchangeProperties(true, Map.of(Market.FUTURES, FUTURES, Market.SPOT, SPOT), queue)));
    }

    @Test
    @DisplayName("MEXC futures is bound to its real sync strategy, so InstrumentSlotTable can allocate its books")
    void futuresStrategyBound() {
        SyncStrategyRegistry registry = new SyncStrategyRegistry(List.of(
                new MexcAdapterConfig().mexcFuturesStrategyBinding(new FakeRecoverySink(), new PipelineMetrics())));

        DepthSyncStrategy strategy = registry.forVenue(Venue.MEXC_FUTURES);

        assertInstanceOf(MexcFuturesSyncStrategy.class, strategy);
        assertNotSame(strategy.newContext(), strategy.newContext(), "newContext must be per book");
        assertEquals(-1, ((MexcFuturesSyncContext) strategy.newContext()).lastVersion, "a fresh context has no sync point");
    }

    @Test
    @DisplayName("MEXC spot is bound to its own sync strategy, with a context per book")
    void spotStrategyBound() {
        SyncStrategyRegistry registry = new SyncStrategyRegistry(List.of(
                new MexcAdapterConfig().mexcSpotStrategyBinding(new FakeRecoverySink(), new PipelineMetrics())));

        DepthSyncStrategy strategy = registry.forVenue(Venue.MEXC_SPOT);

        assertInstanceOf(MexcSpotSyncStrategy.class, strategy);
        assertNotSame(strategy.newContext(), strategy.newContext(), "newContext must be per book");
    }

    @Test
    @DisplayName("with MEXC enabled, the stream registry needs and accepts both bindings, one protocol per venue")
    void bothStreamsBound() {
        ExchangesProperties exchanges = exchanges(null);
        MexcAdapterConfig config = new MexcAdapterConfig();

        StreamProtocolRegistry registry = new StreamProtocolRegistry(List.of(
                config.mexcSpotStreamBinding(exchanges), config.mexcFuturesStreamBinding(exchanges)), exchanges);

        assertInstanceOf(MexcSpotStreamProtocol.class, registry.forStream(depth(Venue.MEXC_SPOT)));
        assertInstanceOf(MexcFuturesStreamProtocol.class, registry.forStream(depth(Venue.MEXC_FUTURES)));
        // Spot is enabled by the same switch: a futures-only binding set fails at startup.
        assertThrows(IllegalStateException.class, () -> new StreamProtocolRegistry(
                List.of(config.mexcFuturesStreamBinding(exchanges)), exchanges));
    }

    @Test
    @DisplayName("one instrument source claims both MEXC venues")
    void sourceClaimsBothVenues() {
        MexcAdapterConfig config = new MexcAdapterConfig();

        assertEquals(Set.of(Venue.MEXC_SPOT, Venue.MEXC_FUTURES),
                config.mexcInstrumentSource(new MexcSpotRestClient(null), new MexcFuturesRestClient(null)).venues());
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

    private static SnapshotRequestQueue queue(SnapshotQueueFactory queues, ExchangesProperties exchanges,
                                              MexcSnapshotProperties snapshot) {
        return new MexcAdapterConfig().mexcFuturesSnapshotQueue(queues, new MexcFuturesRestClient(null), snapshot, exchanges);
    }

    @Test
    @DisplayName("each MEXC venue recovers through its own snapshot queue")
    void eachVenueHasItsOwnQueue() {
        ExchangesProperties exchanges = exchanges(QUEUE);
        SnapshotQueueFactory queues = queues(exchanges);

        SnapshotRequestQueue futures = queue(queues, exchanges, SNAPSHOT);
        SnapshotRequestQueue spot = new MexcAdapterConfig()
                .mexcSpotSnapshotQueue(queues, new MexcSpotRestClient(null), SNAPSHOT, exchanges);

        assertEquals(Venue.MEXC_FUTURES, futures.venue());
        assertEquals(Venue.MEXC_SPOT, spot.venue());
    }

    @Test
    @DisplayName("MEXC without a snapshot-queue block fails at startup")
    void missingSnapshotQueueBlockThrows() {
        ExchangesProperties exchanges = exchanges(null);

        assertThrows(IllegalStateException.class, () -> queue(queues(exchanges), exchanges, SNAPSHOT));
    }

    @Test
    @DisplayName("MEXC futures without its snapshot block fails at startup")
    void missingVenueSnapshotBlockThrows() {
        ExchangesProperties exchanges = exchanges(QUEUE);

        assertThrows(IllegalStateException.class,
                () -> queue(queues(exchanges), exchanges, new MexcSnapshotProperties(Map.of())));
    }

    @Test
    @DisplayName("batch-timeout must cover a paced batch, not just one response: 12 × 250ms + 10s = 13s")
    void batchTimeoutMustCoverPacing() {
        // 13s clears SnapshotQueueFactory's own check (> response-timeout) but could seal a batch
        // whose last send goes out at 3s and takes the full 10s.
        ExchangesProperties tooShort = exchanges(new SnapshotQueueProperties(12, Duration.ofMillis(250), Duration.ofSeconds(13)));
        ExchangesProperties enough = exchanges(
                new SnapshotQueueProperties(12, Duration.ofMillis(250), Duration.ofSeconds(13).plusMillis(1)));

        assertThrows(IllegalStateException.class, () -> queue(queues(tooShort), tooShort, SNAPSHOT));
        assertEquals(Venue.MEXC_FUTURES, queue(queues(enough), enough, SNAPSHOT).venue());
    }

    private static StreamKey depth(Venue venue) {
        return new StreamKey(venue, StreamKind.DEPTH);
    }
}
