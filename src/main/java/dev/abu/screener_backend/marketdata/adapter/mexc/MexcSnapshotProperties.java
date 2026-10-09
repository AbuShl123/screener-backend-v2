package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Market;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * How MEXC snapshot requests are sized and paced, per venue:
 * {@code screener.exchanges.mexc.venues.<market>.snapshot.*}. The core queue's own shape (batch
 * size, flush interval) is the exchange-level {@code snapshot-queue} block, not this.
 *
 * <p>Adapter-owned (registered by {@link MexcAdapterConfig}), bound from the same venue block as
 * core's {@code ExchangesProperties} — the arrangement {@code BinanceSnapshotProperties} uses: each
 * binds only its own keys and ignores the other's.
 *
 * @param venues per-market blocks; only their {@code snapshot} subtree is bound
 */
@ConfigurationProperties(prefix = "screener.exchanges.mexc")
public record MexcSnapshotProperties(Map<Market, VenueBlock> venues) {

    /**
     * MEXC returns at most this many levels per side, and silently caps a larger {@code limit}:
     * 1500 on futures, 2000 on spot ({@code mexc-spot-depth-empirical.md} §3).
     */
    static int maxDepthLimit(Market market) {
        return switch (market) {
            case FUTURES -> 1500;
            case SPOT -> 2000;
        };
    }

    public MexcSnapshotProperties {
        venues = venues == null ? Map.of() : Map.copyOf(venues);
    }

    /**
     * @throws IllegalStateException    if the market has no {@code snapshot} block
     * @throws IllegalArgumentException if its {@code depth-limit} exceeds the market's server cap
     */
    public MarketSnapshot forMarket(Market market) {
        VenueBlock block = venues.get(market);
        if (block == null || block.snapshot() == null) {
            throw new IllegalStateException("Missing configuration for screener.exchanges.mexc.venues."
                    + market + ".snapshot");
        }
        MarketSnapshot snapshot = block.snapshot();
        if (snapshot.depthLimit() > maxDepthLimit(market)) {
            throw new IllegalArgumentException("screener.exchanges.mexc.venues." + market + ".snapshot.depth-limit must be in [1, "
                    + maxDepthLimit(market) + "], got " + snapshot.depthLimit());
        }
        return snapshot;
    }

    /** The slice of one venue block this adapter binds. */
    public record VenueBlock(MarketSnapshot snapshot) {}

    /**
     * @param depthLimit       {@code limit} on every depth request, from 1 to the market's server cap
     *                         ({@link #maxDepthLimit}, checked by {@link #forMarket}). On futures every
     *                         value costs the same against MEXC's window (V2c), so the cap is the
     *                         sensible default
     * @param requestInterval  spacing between consecutive snapshot sends. Futures' limit is ~10 per 2s
     *                         per IP, and evenly spaced sends at 250ms never put more than 8 in one
     *                         window; spot's own budget is wider
     * @param throttleCooldown how long to stop after a throttled request (futures: HTTP 200,
     *                         {@code code 510}). Spot has no such response, so it never applies there
     * @param wafCooldown      how long to stop after an HTTP 403 (Akamai WAF block) or 429
     */
    public record MarketSnapshot(int depthLimit, Duration requestInterval, Duration throttleCooldown,
                                 Duration wafCooldown) {

        public MarketSnapshot {
            if (depthLimit < 1) {
                throw new IllegalArgumentException("depth-limit must be positive, got " + depthLimit);
            }
            requirePositive(requestInterval, "request-interval");
            requirePositive(throttleCooldown, "throttle-cooldown");
            requirePositive(wafCooldown, "waf-cooldown");
        }

        private static void requirePositive(Duration value, String name) {
            if (value == null || !value.isPositive()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
