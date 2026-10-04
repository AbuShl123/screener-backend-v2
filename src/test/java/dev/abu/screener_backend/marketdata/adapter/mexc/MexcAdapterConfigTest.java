package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.marketdata.Exchange;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.*;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEventPublisher;
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

    private static final RestProperties REST =
            new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));
    private static final VenueProperties FUTURES = new VenueProperties("wss://x", REST, "{symbol}", 300, 1, 8, 1, 15);
    private static final SnapshotQueueProperties QUEUE =
            new SnapshotQueueProperties(12, Duration.ofMillis(250), Duration.ofSeconds(20));
    private static final MexcSnapshotProperties SNAPSHOT = new MexcSnapshotProperties(Map.of(Market.FUTURES,
            new VenueBlock(new MarketSnapshot(1500, Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofSeconds(90)))));

    private static ExchangesProperties exchanges(SnapshotQueueProperties queue) {
        return new ExchangesProperties(
                Map.of(Exchange.MEXC, new ExchangeProperties(true, Map.of(Market.FUTURES, FUTURES), queue)));
    }

    @Test
    @DisplayName("MEXC futures is bound to its real sync strategy, so BookSlotTable can allocate its books")
    void futuresStrategyBound() {
        SyncStrategyRegistry registry = new SyncStrategyRegistry(List.of(
                new MexcAdapterConfig().mexcFuturesStrategyBinding(new FakeRecoverySink(), new PipelineMetrics())));

        DepthSyncStrategy strategy = registry.forVenue(Venue.MEXC_FUTURES);

        assertInstanceOf(MexcFuturesSyncStrategy.class, strategy);
        assertNotSame(strategy.newContext(), strategy.newContext(), "newContext must be per book");
        assertEquals(-1, ((MexcSyncContext) strategy.newContext()).lastVersion, "a fresh context has no sync point");
    }

    @Test
    @DisplayName("with MEXC enabled, the stream registry accepts its binding and resolves the MEXC protocol")
    void futuresStreamBound() {
        ExchangesProperties exchanges = exchanges(null);

        StreamProtocolRegistry registry = new StreamProtocolRegistry(
                List.of(new MexcAdapterConfig().mexcFuturesStreamBinding(exchanges)), exchanges);

        assertInstanceOf(MexcFuturesStreamProtocol.class, registry.forVenue(Venue.MEXC_FUTURES));
    }

    @Test
    @DisplayName("the instrument source claims exactly MEXC futures")
    void sourceClaimsFutures() {
        MexcAdapterConfig config = new MexcAdapterConfig();

        assertEquals(Set.of(Venue.MEXC_FUTURES),
                config.mexcInstrumentSource(new MexcFuturesRestClient(null)).venues());
    }

    // --- Snapshot recovery wiring --------------------------------------------------------------

    private static final DepthEventPublisher NO_OP_PUBLISHER = new DepthEventPublisher() {
        @Override public void publishFrame(int instrumentId, String payload) { }
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
    @DisplayName("MEXC futures recovers through its own snapshot queue")
    void futuresHasItsOwnQueue() {
        ExchangesProperties exchanges = exchanges(QUEUE);

        assertEquals(Venue.MEXC_FUTURES, queue(queues(exchanges), exchanges, SNAPSHOT).venue());
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
}
