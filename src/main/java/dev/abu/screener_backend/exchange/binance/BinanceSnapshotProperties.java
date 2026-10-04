package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.Market;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * How Binance snapshot requests are priced and paced, per venue:
 * {@code screener.exchanges.binance.venues.<market>.snapshot.*}. The core queue's own shape (batch
 * size, flush interval) is the exchange-level {@code snapshot-queue} block, not this.
 *
 * <p>Adapter-owned (registered by {@link BinanceAdapterConfig}), yet it lives inside the venue block
 * that core's {@code ExchangesProperties} binds. Both bind the same {@code venues} subtree and each
 * takes only its own keys: core ignores {@code snapshot}, and this record ignores {@code rest},
 * {@code stream-url} and the rest — as it ignores the exchange-level siblings ({@code enabled},
 * {@code snapshot-queue}).
 *
 * @param venues per-market blocks; only their {@code snapshot} subtree is bound
 */
@ConfigurationProperties(prefix = "screener.exchanges.binance")
public record BinanceSnapshotProperties(Map<Market, VenueBlock> venues) {

    public BinanceSnapshotProperties {
        venues = venues == null ? Map.of() : Map.copyOf(venues);
        venues.forEach((market, block) -> {
            MarketSnapshot props = block.snapshot();
            if (props == null) return;   // reported by forMarket, if the venue is ever wired
            int perRequest = props.weightPerRequest(market);
            if (props.weightBudget() < perRequest) {
                throw new IllegalArgumentException("screener.exchanges.binance.venues." + market
                        + ".snapshot: weight-limit-per-minute - weight-reserve (" + props.weightBudget()
                        + ") cannot afford a single depth request (" + perRequest + ")");
            }
        });
    }

    /** @throws IllegalStateException if the market has no {@code snapshot} block */
    public MarketSnapshot forMarket(Market market) {
        VenueBlock block = venues.get(market);
        if (block == null || block.snapshot() == null) {
            throw new IllegalStateException("Missing configuration for screener.exchanges.binance.venues."
                    + market + ".snapshot");
        }
        return block.snapshot();
    }

    /** The slice of one venue block this adapter binds. */
    public record VenueBlock(MarketSnapshot snapshot) {}

    /**
     * @param depthLimit           {@code limit} on every depth request; one of 100, 500, 1000
     * @param weightLimitPerMinute Binance's request-weight limit for this venue
     * @param weightReserve        weight left unspent for traffic the fetcher does not see
     *                             (discovery calls)
     * @param banFallback          how long to stop on a 418 that carries no {@code Retry-After}
     */
    public record MarketSnapshot(int depthLimit, int weightLimitPerMinute, int weightReserve, Duration banFallback) {

        public MarketSnapshot {
            BinanceDepthLimit.of(depthLimit);   // validates
            if (weightReserve < 0 || weightReserve >= weightLimitPerMinute) {
                throw new IllegalArgumentException("weight-reserve must be in [0, weight-limit-per-minute), got "
                        + weightReserve + " against " + weightLimitPerMinute);
            }
            if (banFallback == null || !banFallback.isPositive()) {
                throw new IllegalArgumentException("ban-fallback must be positive");
            }
        }

        /** What the fetcher may spend per minute. */
        public int weightBudget() {
            return weightLimitPerMinute - weightReserve;
        }

        /** The cost of one depth request at {@link #depthLimit} on this market. */
        public int weightPerRequest(Market market) {
            return BinanceDepthLimit.of(depthLimit).weight(market);
        }
    }
}
