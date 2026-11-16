package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.marketdata.StreamKey;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.bybit.BybitSnapshotProperties.Cooldown;
import dev.abu.screener_backend.marketdata.adapter.bybit.BybitSnapshotProperties.MarketSnapshot;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.recovery.SnapshotQueueFactory;
import dev.abu.screener_backend.marketdata.core.recovery.SnapshotRequestQueue;
import dev.abu.screener_backend.marketdata.core.rest.ExchangeWebClientFactory;
import dev.abu.screener_backend.marketdata.spi.InstrumentSource;
import dev.abu.screener_backend.marketdata.spi.RecoverySink;
import dev.abu.screener_backend.marketdata.spi.VenueStrategyBinding;
import dev.abu.screener_backend.marketdata.spi.VenueStreamBinding;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Bybit adapter's registration point: spot ({@code category=spot}) and futures (linear USDT
 * perpetuals, {@code category=linear}).
 *
 * <p>Like {@code BinanceAdapterConfig} and {@code MexcAdapterConfig}, every adapter bean is
 * constructed here and never component-scanned; a stray {@code @Component} would yield a duplicate
 * binding or a second claim on a Bybit venue at startup. One set of beans per venue, except two
 * that span both: the {@link BybitInstrumentSource}, because spot inclusion depends on the futures
 * list, and the {@link BybitRestCooldown}, because Bybit's REST limit and ban are per IP across both
 * categories. {@code bybit.enabled} is the master switch and each venue has its own; with one venue
 * off, the source still fetches both and core registers only the enabled one.
 *
 * <p>Both venues are one class with one instance per venue: {@link BybitStreamProtocol},
 * {@link BybitSyncStrategy}, {@link BybitRestClient} and {@link BybitSnapshotFetcher}. Books sync
 * from the in-stream snapshot, so the snapshot queue only serves a mid-stream gap. Its shape is
 * {@code bybit.snapshot-queue}; each fetcher's pacing is its venue's {@code snapshot} block, and the
 * shared pause is {@code bybit.snapshot-cooldown}.
 */
@Configuration
@EnableConfigurationProperties(BybitSnapshotProperties.class)
public class BybitAdapterConfig {

    @Bean
    VenueStrategyBinding bybitSpotStrategyBinding(@Qualifier("bybitSpotSnapshotQueue") RecoverySink recoverySink,
                                                  PipelineMetrics metrics) {
        return new VenueStrategyBinding(Venue.BYBIT_SPOT, new BybitSyncStrategy(recoverySink, metrics));
    }

    @Bean
    VenueStrategyBinding bybitFuturesStrategyBinding(@Qualifier("bybitFuturesSnapshotQueue") RecoverySink recoverySink,
                                                     PipelineMetrics metrics) {
        return new VenueStrategyBinding(Venue.BYBIT_FUTURES, new BybitSyncStrategy(recoverySink, metrics));
    }

    /**
     * @throws IllegalStateException if a config block is missing, or {@code batch-timeout} cannot
     *                               cover a paced batch
     *                               ({@link BybitSnapshotProperties#requireBatchTimeoutCoversPacing})
     */
    @Bean
    SnapshotRequestQueue bybitSpotSnapshotQueue(SnapshotQueueFactory queues,
                                                @Qualifier("bybitSpotRestClient") BybitRestClient client,
                                                BybitRestCooldown cooldown, BybitSnapshotProperties snapshot,
                                                ExchangesProperties exchanges) {
        return snapshotQueue(queues, client, cooldown, snapshot, exchanges);
    }

    /** @throws IllegalStateException as {@link #bybitSpotSnapshotQueue} */
    @Bean
    SnapshotRequestQueue bybitFuturesSnapshotQueue(SnapshotQueueFactory queues,
                                                   @Qualifier("bybitFuturesRestClient") BybitRestClient client,
                                                   BybitRestCooldown cooldown, BybitSnapshotProperties snapshot,
                                                   ExchangesProperties exchanges) {
        return snapshotQueue(queues, client, cooldown, snapshot, exchanges);
    }

    private static SnapshotRequestQueue snapshotQueue(SnapshotQueueFactory queues, BybitRestClient client,
                                                      BybitRestCooldown cooldown, BybitSnapshotProperties snapshot,
                                                      ExchangesProperties exchanges) {
        Venue venue = client.venue();
        MarketSnapshot props = snapshot.forMarket(venue.market());
        BybitSnapshotProperties.requireBatchTimeoutCoversPacing(exchanges, venue, props);
        return queues.create(venue, new BybitSnapshotFetcher(client, props.requestInterval(), cooldown));
    }

    /**
     * The one pause both venues' fetchers share.
     *
     * @throws IllegalStateException    if there is no {@code snapshot-cooldown} block
     * @throws IllegalArgumentException if a pause is not positive
     */
    @Bean
    BybitRestCooldown bybitRestCooldown(BybitSnapshotProperties snapshot) {
        Cooldown cooldown = snapshot.cooldown();
        return new BybitRestCooldown(cooldown.rateLimited(), cooldown.banned());
    }

    /** @throws IllegalArgumentException if {@code subscribe-chunk-size} could break a server limit */
    @Bean
    VenueStreamBinding bybitSpotStreamBinding(ExchangesProperties exchanges) {
        StreamKey depth = new StreamKey(Venue.BYBIT_SPOT, StreamKind.DEPTH);
        return new VenueStreamBinding(Venue.BYBIT_SPOT,
                new BybitStreamProtocol(Venue.BYBIT_SPOT, exchanges.stream(depth)));
    }

    /** @throws IllegalArgumentException as {@link #bybitSpotStreamBinding} */
    @Bean
    VenueStreamBinding bybitFuturesStreamBinding(ExchangesProperties exchanges) {
        StreamKey depth = new StreamKey(Venue.BYBIT_FUTURES, StreamKind.DEPTH);
        return new VenueStreamBinding(Venue.BYBIT_FUTURES,
                new BybitStreamProtocol(Venue.BYBIT_FUTURES, exchanges.stream(depth)));
    }

    @Bean
    InstrumentSource bybitInstrumentSource(@Qualifier("bybitSpotRestClient") BybitRestClient spotClient,
                                           @Qualifier("bybitFuturesRestClient") BybitRestClient futuresClient) {
        return new BybitInstrumentSource(spotClient, futuresClient);
    }

    @Bean
    BybitRestClient bybitSpotRestClient(ExchangeWebClientFactory webClientFactory, ExchangesProperties exchanges) {
        return new BybitRestClient(Venue.BYBIT_SPOT, webClientFactory.create(exchanges.venue(Venue.BYBIT_SPOT).rest()));
    }

    @Bean
    BybitRestClient bybitFuturesRestClient(ExchangeWebClientFactory webClientFactory, ExchangesProperties exchanges) {
        return new BybitRestClient(Venue.BYBIT_FUTURES,
                webClientFactory.create(exchanges.venue(Venue.BYBIT_FUTURES).rest()));
    }
}
