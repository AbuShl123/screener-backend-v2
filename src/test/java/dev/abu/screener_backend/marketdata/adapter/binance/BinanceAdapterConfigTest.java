package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.marketdata.adapter.binance.*;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceSnapshotProperties.MarketSnapshot;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceSnapshotProperties.VenueBlock;
import dev.abu.screener_backend.marketdata.Exchange;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEventPublisher;
import dev.abu.screener_backend.marketdata.core.recovery.SnapshotQueueFactory;
import dev.abu.screener_backend.marketdata.core.recovery.SnapshotRequestQueue;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;
import dev.abu.screener_backend.marketdata.spi.FakeRecoverySink;
import dev.abu.screener_backend.marketdata.spi.StreamProtocolRegistry;
import dev.abu.screener_backend.marketdata.spi.SyncStrategyRegistry;
import dev.abu.screener_backend.marketdata.spi.VenueStrategyBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Binance adapter's venue bindings, and what {@link SyncStrategyRegistry} makes of them.
 *
 * <p>Worth pinning because the failure mode is remote from the cause: an unbound venue would
 * otherwise surface as a {@code null} strategy on a {@code BookSlot}, and then as an NPE on a
 * Disruptor consumer thread on the first depth message for that instrument.
 */
class BinanceAdapterConfigTest {

    private static SyncStrategyRegistry registry() {
        BinanceAdapterConfig config = new BinanceAdapterConfig();
        FakeRecoverySink sink = new FakeRecoverySink();
        PipelineMetrics metrics = new PipelineMetrics();
        return new SyncStrategyRegistry(List.of(
                config.binanceSpotStrategyBinding(sink, metrics),
                config.binanceFuturesStrategyBinding(sink, metrics)));
    }

    @Test
    @DisplayName("every Binance venue resolves to the strategy that implements its sequence rule")
    void everyBinanceVenueIsBound() {
        SyncStrategyRegistry registry = registry();

        assertInstanceOf(BinanceSpotSyncStrategy.class, registry.forVenue(Venue.BINANCE_SPOT));
        assertInstanceOf(BinanceFuturesSyncStrategy.class, registry.forVenue(Venue.BINANCE_FUTURES));

        // Scoped to Binance so this stays honest once a second exchange's venues exist but its
        // adapter has not landed yet.
        for (Venue venue : Venue.values()) {
            if (venue.exchange() == Exchange.BINANCE) {
                assertNotNullStrategy(registry, venue);
            }
        }
    }

    private static void assertNotNullStrategy(SyncStrategyRegistry registry, Venue venue) {
        DepthSyncStrategy strategy = registry.forVenue(venue);
        assertTrue(strategy instanceof BinanceDepthSyncStrategy, venue + " must map to a Binance strategy");
    }

    @Test
    @DisplayName("one strategy instance per venue, even though both share a base class")
    void oneInstancePerVenue() {
        SyncStrategyRegistry registry = registry();

        // Vision §1: one set of adapter beans per venue. It costs nothing and the seam is already
        // there for the first config value on which the two venues diverge.
        assertNotSame(registry.forVenue(Venue.BINANCE_SPOT), registry.forVenue(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("each venue gets its own sync context, so books never share a cursor")
    void contextsAreNotShared() {
        SyncStrategyRegistry registry = registry();
        DepthSyncStrategy spot = registry.forVenue(Venue.BINANCE_SPOT);

        assertNotSame(spot.newContext(), spot.newContext(), "newContext must be per book");
        assertEquals(-1, ((BinanceSyncContext) spot.newContext()).lastUpdateId, "a fresh context has no sync point");
    }

    @Test
    @DisplayName("every Binance venue has a stream binding with its own BinanceStreamProtocol")
    void everyBinanceVenueHasStreamBinding() {
        BinanceAdapterConfig config = new BinanceAdapterConfig();
        RestProperties rest = new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));
        VenueProperties spot = new VenueProperties("wss://x", rest, "{symbol}@depth", 1024, 1, 1, 400, 120, null);
        VenueProperties futures = new VenueProperties("wss://x", rest, "{symbol}@depth@500ms", 1024, 1, 1, 400, 120, null);
        ExchangesProperties exchanges = new ExchangesProperties(Map.of(Exchange.BINANCE,
                new ExchangeProperties(true, Map.of(Market.SPOT, spot, Market.FUTURES, futures), null)));
        StreamProtocolRegistry registry = new StreamProtocolRegistry(List.of(
                config.binanceSpotStreamBinding(exchanges),
                config.binanceFuturesStreamBinding(exchanges)), exchanges);

        for (Venue venue : Venue.values()) {
            if (venue.exchange() == Exchange.BINANCE) {
                assertInstanceOf(BinanceStreamProtocol.class, registry.forVenue(venue), venue + " must stream with Binance's protocol");
            }
        }
        assertNotSame(registry.forVenue(Venue.BINANCE_SPOT), registry.forVenue(Venue.BINANCE_FUTURES));
    }

    @Test
    @DisplayName("an unmapped venue fails loudly at startup rather than returning null")
    void unmappedVenueThrows() {
        SyncStrategyRegistry registry = new SyncStrategyRegistry(List.of());

        // A null here would travel all the way to a consumer thread before failing.
        assertThrows(IllegalStateException.class, () -> registry.forVenue(Venue.BINANCE_SPOT));
    }

    @Test
    @DisplayName("two bindings for one venue fail at construction — the stray-@Component guard")
    void duplicateBindingThrows() {
        BinanceAdapterConfig config = new BinanceAdapterConfig();
        FakeRecoverySink sink = new FakeRecoverySink();
        PipelineMetrics metrics = new PipelineMetrics();

        // What a leftover @Component on a strategy class would look like: the same venue bound
        // twice, silently giving half the books a different instance.
        assertThrows(IllegalStateException.class, () -> new SyncStrategyRegistry(List.of(
                config.binanceSpotStrategyBinding(sink, metrics),
                new VenueStrategyBinding(Venue.BINANCE_SPOT, new BinanceSpotSyncStrategy(sink, metrics)))));
    }

    // --- Snapshot recovery wiring --------------------------------------------------------------

    private static final DepthEventPublisher NO_OP_PUBLISHER = new DepthEventPublisher() {
        @Override public void publishFrame(int instrumentId, String payload) { }
        @Override public void publishFrame(int instrumentId, ByteBuffer payload) { }
        @Override public void publishSnapshot(int instrumentId, String payload) { }
        @Override public void publishSnapshotFailure(int instrumentId) { }
    };

    private final List<SnapshotQueueFactory> factories = new ArrayList<>();

    @AfterEach
    void stopQueues() {
        factories.forEach(SnapshotQueueFactory::shutdown);
    }

    private SnapshotQueueFactory queues(SnapshotQueueProperties snapshotQueue, Duration responseTimeout) {
        RestProperties rest = new RestProperties("https://x", 1, Duration.ofSeconds(5), responseTimeout);
        VenueProperties spot = new VenueProperties("wss://x", rest, "{symbol}@depth", 1024, 1, 1, 400, 120, null);
        VenueProperties futures = new VenueProperties("wss://x", rest, "{symbol}@depth@500ms", 1024, 1, 1, 400, 120, null);
        ExchangesProperties exchanges = new ExchangesProperties(Map.of(Exchange.BINANCE,
                new ExchangeProperties(true, Map.of(Market.SPOT, spot, Market.FUTURES, futures), snapshotQueue)));
        SnapshotQueueFactory factory = new SnapshotQueueFactory(NO_OP_PUBLISHER, new PipelineMetrics(), exchanges);
        factories.add(factory);
        return factory;
    }

    private static final SnapshotQueueProperties QUEUE =
            new SnapshotQueueProperties(10, Duration.ofMillis(250), Duration.ofSeconds(30));

    private static final BinanceSnapshotProperties BINANCE_SNAPSHOT = new BinanceSnapshotProperties(Map.of(
            Market.SPOT, new VenueBlock(new MarketSnapshot(1000, 6000, 200, Duration.ofMinutes(2))),
            Market.FUTURES, new VenueBlock(new MarketSnapshot(1000, 2400, 200, Duration.ofMinutes(2)))));

    private static BinanceRestClient restClient(Venue venue) {
        BinancePaths paths = venue == Venue.BINANCE_SPOT ? BinancePaths.SPOT : BinancePaths.FUTURES;
        return new BinanceRestClient(venue, WebClient.create("https://x"), paths);
    }

    @Test
    @DisplayName("each Binance venue recovers through its own snapshot queue")
    void eachVenueHasItsOwnQueue() {
        BinanceAdapterConfig config = new BinanceAdapterConfig();
        SnapshotQueueFactory queues = queues(QUEUE, Duration.ofSeconds(10));

        SnapshotRequestQueue spot = config.binanceSpotSnapshotQueue(queues,
                restClient(Venue.BINANCE_SPOT), BINANCE_SNAPSHOT);
        SnapshotRequestQueue futures = config.binanceFuturesSnapshotQueue(queues,
                restClient(Venue.BINANCE_FUTURES), BINANCE_SNAPSHOT);

        // A shared queue would let one venue's batch close the other's, and price spot requests
        // against the futures weight limit.
        assertEquals(Venue.BINANCE_SPOT, spot.venue());
        assertEquals(Venue.BINANCE_FUTURES, futures.venue());
        assertNotSame(spot, futures);
    }

    @Test
    @DisplayName("Binance without a snapshot-queue block fails at startup")
    void missingSnapshotQueueBlockThrows() {
        BinanceAdapterConfig config = new BinanceAdapterConfig();
        SnapshotQueueFactory queues = queues(null, Duration.ofSeconds(10));

        assertThrows(IllegalStateException.class, () -> config.binanceSpotSnapshotQueue(queues,
                restClient(Venue.BINANCE_SPOT), BINANCE_SNAPSHOT));
    }

    @Test
    @DisplayName("a Binance venue without its own snapshot block fails at startup")
    void missingVenueSnapshotBlockThrows() {
        BinanceAdapterConfig config = new BinanceAdapterConfig();
        SnapshotQueueFactory queues = queues(QUEUE, Duration.ofSeconds(10));
        BinanceSnapshotProperties spotOnly = new BinanceSnapshotProperties(Map.of(
                Market.SPOT, new VenueBlock(new MarketSnapshot(1000, 6000, 200, Duration.ofMinutes(2)))));

        assertThrows(IllegalStateException.class, () -> config.binanceFuturesSnapshotQueue(queues,
                restClient(Venue.BINANCE_FUTURES), spotOnly));
    }

    @Test
    @DisplayName("batch-timeout must exceed rest.response-timeout")
    void batchTimeoutMustExceedResponseTimeout() {
        BinanceAdapterConfig config = new BinanceAdapterConfig();
        SnapshotQueueFactory queues = queues(
                new SnapshotQueueProperties(10, Duration.ofMillis(250), Duration.ofSeconds(10)), Duration.ofSeconds(10));

        assertThrows(IllegalStateException.class, () -> config.binanceFuturesSnapshotQueue(queues,
                restClient(Venue.BINANCE_FUTURES), BINANCE_SNAPSHOT));
    }
}
