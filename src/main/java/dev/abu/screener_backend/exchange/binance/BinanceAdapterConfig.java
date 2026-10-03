package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.health.PipelineMetrics;
import dev.abu.screener_backend.exchange.recovery.SnapshotQueueFactory;
import dev.abu.screener_backend.exchange.recovery.SnapshotRequestQueue;
import dev.abu.screener_backend.exchange.rest.ExchangeWebClientFactory;
import dev.abu.screener_backend.exchange.spi.InstrumentSource;
import dev.abu.screener_backend.exchange.spi.RecoverySink;
import dev.abu.screener_backend.exchange.spi.VenueStrategyBinding;
import dev.abu.screener_backend.exchange.spi.VenueStreamBinding;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The Binance adapter's registration point — the only place in the application that knows which
 * strategy serves which Binance venue, how Binance venues are streamed, and where Binance's
 * universe comes from.
 *
 * <p>The strategies and protocols are constructed here rather than component-scanned so that
 * core's {@code SyncStrategyRegistry} and {@code StreamProtocolRegistry} depend on the
 * {@link VenueStrategyBinding} / {@link VenueStreamBinding} abstractions alone. Do not also annotate
 * those classes {@code @Component}: that yields two instances of each and a duplicate-binding
 * failure at startup. The same holds for {@link BinanceInstrumentSource}, which core's
 * {@code InstrumentUniverseService} would reject as a second claim on the Binance venues.
 *
 * <p>One set of adapter beans per <em>venue</em>, even though both venues share
 * {@link BinanceDepthSyncStrategy} as a base and {@link BinanceStreamProtocol} as a class — the
 * seam is already there for the first config value that diverges. Discovery is the exception: one
 * source spans both venues, because spot inclusion depends on the futures list.
 *
 * <p>Each venue recovers through its own core {@link SnapshotRequestQueue}, fed by its own
 * {@link BinanceSnapshotFetcher} — which alone tracks that venue's request weight. The queue's shape
 * is {@code binance.snapshot-queue}; each fetcher's pricing is its venue's {@code snapshot} block.
 * The REST clients carry no filters.
 */
@Configuration
@EnableConfigurationProperties({BinanceDiscoveryProperties.class, BinanceSnapshotProperties.class})
public class BinanceAdapterConfig {

    @Bean
    VenueStrategyBinding binanceSpotStrategyBinding(@Qualifier("binanceSpotSnapshotQueue") RecoverySink recoverySink,
                                                    PipelineMetrics metrics) {
        return new VenueStrategyBinding(Venue.BINANCE_SPOT, new BinanceSpotSyncStrategy(recoverySink, metrics));
    }

    @Bean
    VenueStrategyBinding binanceFuturesStrategyBinding(@Qualifier("binanceFuturesSnapshotQueue") RecoverySink recoverySink,
                                                       PipelineMetrics metrics) {
        return new VenueStrategyBinding(Venue.BINANCE_FUTURES, new BinanceFuturesSyncStrategy(recoverySink, metrics));
    }

    @Bean
    SnapshotRequestQueue binanceSpotSnapshotQueue(SnapshotQueueFactory queues,
                                                  @Qualifier("binanceSpotRestClient") BinanceRestClient client,
                                                  BinanceSnapshotProperties snapshot) {
        return snapshotQueue(queues, client, snapshot);
    }

    @Bean
    SnapshotRequestQueue binanceFuturesSnapshotQueue(SnapshotQueueFactory queues,
                                                     @Qualifier("binanceFuturesRestClient") BinanceRestClient client,
                                                     BinanceSnapshotProperties snapshot) {
        return snapshotQueue(queues, client, snapshot);
    }

    private static SnapshotRequestQueue snapshotQueue(SnapshotQueueFactory queues, BinanceRestClient client,
                                                      BinanceSnapshotProperties snapshot) {
        Venue venue = client.venue();
        BinanceSnapshotFetcher fetcher = new BinanceSnapshotFetcher(client, snapshot.forMarket(venue.market()));
        return queues.create(venue, fetcher);
    }

    @Bean
    VenueStreamBinding binanceSpotStreamBinding(ExchangesProperties exchanges) {
        return new VenueStreamBinding(Venue.BINANCE_SPOT,
                new BinanceStreamProtocol(Venue.BINANCE_SPOT, exchanges.venue(Venue.BINANCE_SPOT)));
    }

    @Bean
    VenueStreamBinding binanceFuturesStreamBinding(ExchangesProperties exchanges) {
        return new VenueStreamBinding(Venue.BINANCE_FUTURES,
                new BinanceStreamProtocol(Venue.BINANCE_FUTURES, exchanges.venue(Venue.BINANCE_FUTURES)));
    }

    @Bean
    InstrumentSource binanceInstrumentSource(@Qualifier("binanceSpotRestClient") BinanceRestClient spotClient,
                                             @Qualifier("binanceFuturesRestClient") BinanceRestClient futuresClient,
                                             BinanceDiscoveryProperties discovery) {
        return new BinanceInstrumentSource(spotClient, futuresClient, discovery);
    }

    @Bean
    BinanceRestClient binanceSpotRestClient(ExchangeWebClientFactory webClientFactory, ExchangesProperties exchanges) {
        WebClient webClient = webClientFactory.create(exchanges.venue(Venue.BINANCE_SPOT).rest());
        return new BinanceRestClient(Venue.BINANCE_SPOT, webClient, BinancePaths.SPOT);
    }

    @Bean
    BinanceRestClient binanceFuturesRestClient(ExchangeWebClientFactory webClientFactory, ExchangesProperties exchanges) {
        WebClient webClient = webClientFactory.create(exchanges.venue(Venue.BINANCE_FUTURES).rest());
        return new BinanceRestClient(Venue.BINANCE_FUTURES, webClient, BinancePaths.FUTURES);
    }
}
