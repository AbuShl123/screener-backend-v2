package dev.abu.screener_backend.exchange.mexc;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.spi.DepthSyncStrategy;
import dev.abu.screener_backend.exchange.spi.SyncStrategyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** The MEXC adapter's bindings, and what core's registries make of them. */
class MexcAdapterConfigTest {

    @Test
    @DisplayName("MEXC futures has a strategy bound, so BookSlotTable can allocate its books")
    void futuresStrategyBound() {
        SyncStrategyRegistry registry = new SyncStrategyRegistry(List.of(new MexcAdapterConfig().mexcFuturesStrategyBinding()));

        DepthSyncStrategy strategy = registry.forVenue(Venue.MEXC_FUTURES);

        assertInstanceOf(MexcPlaceholderSyncStrategy.class, strategy);
        assertNotSame(strategy.newContext(), strategy.newContext(), "newContext must be per book");
    }

    @Test
    @DisplayName("the instrument source claims exactly MEXC futures")
    void sourceClaimsFutures() {
        MexcAdapterConfig config = new MexcAdapterConfig();

        assertEquals(Set.of(Venue.MEXC_FUTURES),
                config.mexcInstrumentSource(new MexcFuturesRestClient(null)).venues());
    }
}
