package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.spi.RecoverySink;
import dev.abu.screener_backend.exchange.spi.VenueStrategyBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Binance adapter's registration point — the only place in the application that knows which
 * strategy serves which Binance venue.
 *
 * <p>The strategies are constructed here rather than component-scanned so that core's
 * {@code SyncStrategyRegistry} depends on the {@link VenueStrategyBinding} abstraction alone. Do
 * not also annotate the strategy classes {@code @Component}: that yields two instances of each and
 * a duplicate-binding failure at startup.
 *
 * <p>One set of adapter beans per <em>venue</em>, even though both venues share
 * {@link BinanceDepthSyncStrategy} as a base — the seam is already there for the first config value
 * that diverges.
 */
@Configuration
public class BinanceAdapterConfig {

    @Bean
    VenueStrategyBinding binanceSpotStrategyBinding(RecoverySink recoverySink) {
        return new VenueStrategyBinding(Venue.BINANCE_SPOT, new BinanceSpotSyncStrategy(recoverySink));
    }

    @Bean
    VenueStrategyBinding binanceFuturesStrategyBinding(RecoverySink recoverySink) {
        return new VenueStrategyBinding(Venue.BINANCE_FUTURES, new BinanceFuturesSyncStrategy(recoverySink));
    }
}
