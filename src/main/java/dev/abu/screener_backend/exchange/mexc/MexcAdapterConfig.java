package dev.abu.screener_backend.exchange.mexc;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.rest.ExchangeWebClientFactory;
import dev.abu.screener_backend.exchange.spi.InstrumentSource;
import dev.abu.screener_backend.exchange.spi.VenueStrategyBinding;
import dev.abu.screener_backend.exchange.spi.VenueStreamBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The MEXC adapter's registration point. Futures only — MEXC spot streams Protobuf and has no
 * adapter, so {@code Venue} has no {@code MEXC_SPOT}.
 *
 * <p>Like {@code BinanceAdapterConfig}, strategies and sources are constructed here and never
 * component-scanned; a stray {@code @Component} on any of them yields a duplicate binding or a
 * second claim on {@code MEXC_FUTURES} at startup.
 *
 * <p>Built up by the MEXC plan's phases ({@code .claude/plans/mexc-impl-plan.md}): discovery, the
 * stream protocol and a placeholder strategy so far. Enabled, MEXC streams and counts frames, but
 * its books stay {@code PENDING}: {@link MexcFuturesSyncStrategy} exists, but is bound only once
 * its snapshot queue does (Phase 4b).
 */
@Configuration
public class MexcAdapterConfig {

    @Bean
    VenueStrategyBinding mexcFuturesStrategyBinding() {
        return new VenueStrategyBinding(Venue.MEXC_FUTURES, new MexcPlaceholderSyncStrategy());
    }

    @Bean
    VenueStreamBinding mexcFuturesStreamBinding(ExchangesProperties exchanges) {
        return new VenueStreamBinding(Venue.MEXC_FUTURES,
                new MexcFuturesStreamProtocol(Venue.MEXC_FUTURES, exchanges.venue(Venue.MEXC_FUTURES)));
    }

    @Bean
    InstrumentSource mexcInstrumentSource(MexcFuturesRestClient mexcFuturesRestClient) {
        return new MexcInstrumentSource(mexcFuturesRestClient);
    }

    @Bean
    MexcFuturesRestClient mexcFuturesRestClient(ExchangeWebClientFactory webClientFactory, ExchangesProperties exchanges) {
        return new MexcFuturesRestClient(webClientFactory.create(exchanges.venue(Venue.MEXC_FUTURES).rest()));
    }
}
