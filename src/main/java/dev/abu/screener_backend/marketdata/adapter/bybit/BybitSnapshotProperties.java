package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * How Bybit snapshot requests are paced and paused: {@code screener.exchanges.bybit.*}. The core
 * queue's own shape (batch size, flush interval) is the exchange-level {@code snapshot-queue} block,
 * not this.
 *
 * <p>Adapter-owned (to be registered by {@code BybitAdapterConfig}), bound from the same exchange
 * block as core's {@code ExchangesProperties}, the arrangement {@code MexcSnapshotProperties} uses:
 * each binds only its own keys and ignores the other's. Unlike MEXC, the pauses are exchange-level,
 * because Bybit's limit is per IP across both venues; only the pacing is per venue. There is no
 * {@code depth-limit}: every snapshot is {@code limit=1000}, the subscribed depth, since REST
 * {@code u} only stitches onto the {@code orderbook.1000} counter.
 *
 * @param venues           per-market blocks; only their {@code snapshot} subtree is bound
 * @param snapshotCooldown the pauses after Bybit pushes back, shared by both venues
 */
@ConfigurationProperties(prefix = "screener.exchanges.bybit")
public record BybitSnapshotProperties(Map<Market, VenueBlock> venues, Cooldown snapshotCooldown) {

    public BybitSnapshotProperties {
        venues = venues == null ? Map.of() : Map.copyOf(venues);
    }

    /** @throws IllegalStateException if the market has no {@code snapshot} block */
    public MarketSnapshot forMarket(Market market) {
        VenueBlock block = venues.get(market);
        if (block == null || block.snapshot() == null) {
            throw new IllegalStateException("Missing configuration for screener.exchanges.bybit.venues."
                    + market + ".snapshot");
        }
        return block.snapshot();
    }

    /** @throws IllegalStateException if there is no {@code snapshot-cooldown} block */
    public Cooldown cooldown() {
        if (snapshotCooldown == null) {
            throw new IllegalStateException("Missing configuration for screener.exchanges.bybit.snapshot-cooldown");
        }
        return snapshotCooldown;
    }

    /**
     * A fetcher spreads a batch over time, so its last request goes out up to
     * {@code max-batch-size × request-interval} after the batch starts (the send clock persists, so
     * the first send may wait one interval for the previous batch's last), and may then take
     * {@code rest.response-timeout}. A shorter {@code batch-timeout} would seal batches whose tail had
     * not even been sent. {@code SnapshotQueueFactory} checks against {@code response-timeout} alone,
     * since it cannot know the fetcher's pacing.
     *
     * <p>A missing {@code snapshot-queue} or {@code rest} block is left to the factory to report.
     *
     * @throws IllegalStateException if {@code batch-timeout} does not cover a paced batch
     */
    public static void requireBatchTimeoutCoversPacing(ExchangesProperties exchanges, Venue venue,
                                                       MarketSnapshot props) {
        SnapshotQueueProperties queue = exchanges.exchange(venue.exchange()).snapshotQueue();
        RestProperties rest = exchanges.venue(venue).rest();
        if (queue == null || rest == null || rest.responseTimeout() == null) return;

        Duration needed = props.requestInterval().multipliedBy(queue.maxBatchSize()).plus(rest.responseTimeout());
        if (queue.batchTimeout().compareTo(needed) <= 0) {
            throw new IllegalStateException("screener.exchanges.bybit.snapshot-queue.batch-timeout ("
                    + queue.batchTimeout() + ") must exceed max-batch-size × request-interval + rest.response-timeout ("
                    + queue.maxBatchSize() + " × " + props.requestInterval() + " + " + rest.responseTimeout()
                    + " = " + needed + ") for " + venue);
        }
    }

    /** The slice of one venue block this adapter binds. */
    public record VenueBlock(MarketSnapshot snapshot) {}

    /**
     * @param requestInterval spacing between consecutive snapshot sends on this venue. Bybit allows
     *                        600 requests per 5 s per IP, shared by both venues; there is no weight
     *                        and no budget header, so pacing is the only control
     */
    public record MarketSnapshot(Duration requestInterval) {

        public MarketSnapshot {
            if (requestInterval == null || !requestInterval.isPositive()) {
                throw new IllegalArgumentException("request-interval must be positive");
            }
        }
    }

    /**
     * Validated by {@link BybitRestCooldown}'s constructor.
     *
     * @param rateLimited how long both venues stop after a rate-limit answer: {@code retCode} 10006 /
     *                    10018 in an HTTP 200, or an HTTP 429. The window slides over 5 s
     * @param banned      how long both venues stop after an HTTP 403, Bybit's 10-minute IP ban (or a
     *                    geo-block, which a pause does not fix but does keep quiet)
     */
    public record Cooldown(Duration rateLimited, Duration banned) {}
}
