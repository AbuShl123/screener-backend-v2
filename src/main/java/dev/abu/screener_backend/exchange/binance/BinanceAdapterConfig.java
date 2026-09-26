package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.health.PipelineMetrics;
import dev.abu.screener_backend.exchange.spi.InstrumentSource;
import dev.abu.screener_backend.exchange.spi.RecoverySink;
import dev.abu.screener_backend.exchange.spi.VenueStrategyBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Binance adapter's registration point — the only place in the application that knows which
 * strategy serves which Binance venue, and where Binance's universe comes from.
 *
 * <p>The strategies are constructed here rather than component-scanned so that core's
 * {@code SyncStrategyRegistry} depends on the {@link VenueStrategyBinding} abstraction alone. Do
 * not also annotate the strategy classes {@code @Component}: that yields two instances of each and
 * a duplicate-binding failure at startup. The same holds for {@link BinanceInstrumentSource}, which
 * core's {@code InstrumentUniverseService} would reject as a second claim on the Binance venues.
 *
 * <p>One set of adapter beans per <em>venue</em>, even though both venues share
 * {@link BinanceDepthSyncStrategy} as a base — the seam is already there for the first config value
 * that diverges. Discovery is the exception: one source spans both venues, because spot inclusion
 * depends on the futures list.
 */
@Configuration
@EnableConfigurationProperties(BinanceDiscoveryProperties.class)
public class BinanceAdapterConfig {

    @Bean
    VenueStrategyBinding binanceSpotStrategyBinding(RecoverySink recoverySink, PipelineMetrics metrics) {
        return new VenueStrategyBinding(Venue.BINANCE_SPOT, new BinanceSpotSyncStrategy(recoverySink, metrics));
    }

    @Bean
    VenueStrategyBinding binanceFuturesStrategyBinding(RecoverySink recoverySink, PipelineMetrics metrics) {
        return new VenueStrategyBinding(Venue.BINANCE_FUTURES, new BinanceFuturesSyncStrategy(recoverySink, metrics));
    }

    @Bean
    InstrumentSource binanceInstrumentSource(BinanceRestClient restClient,
                                             BinanceDiscoveryProperties discovery) {
        return new BinanceInstrumentSource(restClient, discovery);
    }
}
