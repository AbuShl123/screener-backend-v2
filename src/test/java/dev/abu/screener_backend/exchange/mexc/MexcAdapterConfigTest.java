package dev.abu.screener_backend.exchange.mexc;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.ExchangeProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.exchange.Exchange;
import dev.abu.screener_backend.exchange.Market;
import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.spi.DepthSyncStrategy;
import dev.abu.screener_backend.exchange.spi.StreamProtocolRegistry;
import dev.abu.screener_backend.exchange.spi.SyncStrategyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    @DisplayName("with MEXC enabled, the stream registry accepts its binding and resolves the MEXC protocol")
    void futuresStreamBound() {
        RestProperties rest = new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));
        VenueProperties futures = new VenueProperties("wss://x", rest, "{symbol}", 300, 1, 8, 1, 15);
        ExchangesProperties exchanges = new ExchangesProperties(
                Map.of(Exchange.MEXC, new ExchangeProperties(true, Map.of(Market.FUTURES, futures), null)));

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
}
