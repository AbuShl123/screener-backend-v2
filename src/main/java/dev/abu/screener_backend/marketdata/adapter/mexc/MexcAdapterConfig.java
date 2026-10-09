package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcSnapshotProperties.MarketSnapshot;
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

import java.time.Duration;

/**
 * The MEXC adapter's registration point: spot and futures.
 *
 * <p>Like {@code BinanceAdapterConfig}, strategies and sources are constructed here and never
 * component-scanned; a stray {@code @Component} on any of them yields a duplicate binding or a
 * second claim on a MEXC venue at startup. One set of beans per venue, except discovery: one
 * {@link MexcInstrumentSource} spans both, because spot inclusion depends on the futures list.
 * {@code mexc.enabled} is the master switch and each venue has its own; with one venue off, the
 * source still fetches both and core registers only the enabled one.
 *
 * <p>Each venue recovers through its own core {@link SnapshotRequestQueue}, fed by its own
 * {@link MexcSnapshotFetcher} that classifies and paces every request — the two do not share a
 * budget (spot depth does not draw from the futures window). The queue's shape is
 * {@code mexc.snapshot-queue}; each fetcher's sizing and pacing is its venue's {@code snapshot} block.
 */
@Configuration
@EnableConfigurationProperties(MexcSnapshotProperties.class)
public class MexcAdapterConfig {

    @Bean
    VenueStrategyBinding mexcSpotStrategyBinding(@Qualifier("mexcSpotSnapshotQueue") RecoverySink recoverySink,
                                                 PipelineMetrics metrics) {
        return new VenueStrategyBinding(Venue.MEXC_SPOT, new MexcSpotSyncStrategy(recoverySink, metrics));
    }

    @Bean
    VenueStrategyBinding mexcFuturesStrategyBinding(@Qualifier("mexcFuturesSnapshotQueue") RecoverySink recoverySink,
                                                    PipelineMetrics metrics) {
        return new VenueStrategyBinding(Venue.MEXC_FUTURES, new MexcFuturesSyncStrategy(recoverySink, metrics));
    }

    /**
     * @throws IllegalStateException if a config block is missing, or {@code batch-timeout} cannot
     *                               cover a paced batch (see {@link #requireBatchTimeoutCoversPacing})
     */
    @Bean
    SnapshotRequestQueue mexcSpotSnapshotQueue(SnapshotQueueFactory queues, MexcSpotRestClient mexcSpotRestClient,
                                               MexcSnapshotProperties snapshot, ExchangesProperties exchanges) {
        return snapshotQueue(queues, mexcSpotRestClient, snapshot, exchanges);
    }

    /** @throws IllegalStateException as {@link #mexcSpotSnapshotQueue} */
    @Bean
    SnapshotRequestQueue mexcFuturesSnapshotQueue(SnapshotQueueFactory queues, MexcFuturesRestClient mexcFuturesRestClient,
                                                  MexcSnapshotProperties snapshot, ExchangesProperties exchanges) {
        return snapshotQueue(queues, mexcFuturesRestClient, snapshot, exchanges);
    }

    private static SnapshotRequestQueue snapshotQueue(SnapshotQueueFactory queues, MexcDepthClient client,
                                                      MexcSnapshotProperties snapshot, ExchangesProperties exchanges) {
        Venue venue = client.venue();
        MarketSnapshot props = snapshot.forMarket(venue.market());
        requireBatchTimeoutCoversPacing(exchanges, venue, props);
        return queues.create(venue, new MexcSnapshotFetcher(client, props));
    }

    /**
     * The fetcher spreads a batch over time, so its last request goes out up to
     * {@code max-batch-size × request-interval} after the batch starts — the first send may wait one
     * interval for the previous batch's last, since the send clock persists — and may then take
     * {@code rest.response-timeout}. A {@code batch-timeout} shorter than that would seal batches
     * whose tail had not even been sent. {@code SnapshotQueueFactory} checks against
     * {@code response-timeout} alone, since it cannot know the fetcher's pacing.
     *
     * <p>A missing {@code snapshot-queue} or {@code rest} block is left to the factory to report.
     */
    static void requireBatchTimeoutCoversPacing(ExchangesProperties exchanges, Venue venue, MarketSnapshot props) {
        SnapshotQueueProperties queue = exchanges.exchange(venue.exchange()).snapshotQueue();
        RestProperties rest = exchanges.venue(venue).rest();
        if (queue == null || rest == null || rest.responseTimeout() == null) return;

        Duration needed = props.requestInterval().multipliedBy(queue.maxBatchSize()).plus(rest.responseTimeout());
        if (queue.batchTimeout().compareTo(needed) <= 0) {
            throw new IllegalStateException("screener.exchanges.mexc.snapshot-queue.batch-timeout ("
                    + queue.batchTimeout() + ") must exceed max-batch-size × request-interval + rest.response-timeout ("
                    + queue.maxBatchSize() + " × " + props.requestInterval() + " + " + rest.responseTimeout()
                    + " = " + needed + ")");
        }
    }

    @Bean
    VenueStreamBinding mexcSpotStreamBinding(ExchangesProperties exchanges) {
        return new VenueStreamBinding(Venue.MEXC_SPOT,
                new MexcSpotStreamProtocol(Venue.MEXC_SPOT, exchanges.venue(Venue.MEXC_SPOT)));
    }

    @Bean
    VenueStreamBinding mexcFuturesStreamBinding(ExchangesProperties exchanges) {
        return new VenueStreamBinding(Venue.MEXC_FUTURES,
                new MexcFuturesStreamProtocol(Venue.MEXC_FUTURES, exchanges.venue(Venue.MEXC_FUTURES)));
    }

    @Bean
    InstrumentSource mexcInstrumentSource(MexcSpotRestClient mexcSpotRestClient,
                                          MexcFuturesRestClient mexcFuturesRestClient) {
        return new MexcInstrumentSource(mexcSpotRestClient, mexcFuturesRestClient);
    }

    @Bean
    MexcSpotRestClient mexcSpotRestClient(ExchangeWebClientFactory webClientFactory, ExchangesProperties exchanges) {
        return new MexcSpotRestClient(webClientFactory.create(exchanges.venue(Venue.MEXC_SPOT).rest()));
    }

    @Bean
    MexcFuturesRestClient mexcFuturesRestClient(ExchangeWebClientFactory webClientFactory, ExchangesProperties exchanges) {
        return new MexcFuturesRestClient(webClientFactory.create(exchanges.venue(Venue.MEXC_FUTURES).rest()));
    }
}
