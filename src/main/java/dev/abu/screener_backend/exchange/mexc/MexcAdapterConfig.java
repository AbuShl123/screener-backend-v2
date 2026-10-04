package dev.abu.screener_backend.exchange.mexc;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.health.PipelineMetrics;
import dev.abu.screener_backend.exchange.mexc.MexcSnapshotProperties.MarketSnapshot;
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

import java.time.Duration;

/**
 * The MEXC adapter's registration point. Futures only — MEXC spot streams Protobuf and has no
 * adapter, so {@code Venue} has no {@code MEXC_SPOT}.
 *
 * <p>Like {@code BinanceAdapterConfig}, strategies and sources are constructed here and never
 * component-scanned; a stray {@code @Component} on any of them yields a duplicate binding or a
 * second claim on {@code MEXC_FUTURES} at startup.
 *
 * <p>MEXC futures recovers through its own core {@link SnapshotRequestQueue}, fed by a
 * {@link MexcSnapshotFetcher} that classifies and paces every request. The queue's shape is
 * {@code mexc.snapshot-queue}; the fetcher's sizing and pacing is the venue's {@code snapshot} block.
 */
@Configuration
@EnableConfigurationProperties(MexcSnapshotProperties.class)
public class MexcAdapterConfig {

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
    SnapshotRequestQueue mexcFuturesSnapshotQueue(SnapshotQueueFactory queues, MexcFuturesRestClient mexcFuturesRestClient,
                                                  MexcSnapshotProperties snapshot, ExchangesProperties exchanges) {
        Venue venue = Venue.MEXC_FUTURES;
        MarketSnapshot props = snapshot.forMarket(venue.market());
        requireBatchTimeoutCoversPacing(exchanges, venue, props);
        return queues.create(venue, new MexcSnapshotFetcher(mexcFuturesRestClient, props));
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
