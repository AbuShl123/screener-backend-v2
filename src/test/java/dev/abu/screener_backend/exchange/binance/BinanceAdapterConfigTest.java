package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.Exchange;
import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.health.PipelineMetrics;
import dev.abu.screener_backend.exchange.spi.DepthSyncStrategy;
import dev.abu.screener_backend.exchange.spi.SyncStrategyRegistry;
import dev.abu.screener_backend.exchange.spi.VenueStrategyBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
