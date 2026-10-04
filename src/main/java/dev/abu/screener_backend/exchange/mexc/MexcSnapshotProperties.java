package dev.abu.screener_backend.exchange.mexc;

import dev.abu.screener_backend.exchange.Market;
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

    /** MEXC returns at most this many levels per side; a larger {@code limit} is silently capped. */
    static final int MAX_DEPTH_LIMIT = 1500;

    public MexcSnapshotProperties {
        venues = venues == null ? Map.of() : Map.copyOf(venues);
    }

    /** @throws IllegalStateException if the market has no {@code snapshot} block */
    public MarketSnapshot forMarket(Market market) {
        VenueBlock block = venues.get(market);
        if (block == null || block.snapshot() == null) {
            throw new IllegalStateException("Missing configuration for screener.exchanges.mexc.venues."
                    + market + ".snapshot");
        }
        return block.snapshot();
    }

    /** The slice of one venue block this adapter binds. */
    public record VenueBlock(MarketSnapshot snapshot) {}

    /**
     * @param depthLimit       {@code limit} on every depth request, 1–{@value #MAX_DEPTH_LIMIT}. Every
     *                         value costs the same against MEXC's window (V2c), so the cap is the
     *                         sensible default
     * @param requestInterval  spacing between consecutive snapshot sends — MEXC's limit is ~10 per 2s
     *                         per IP, and evenly spaced sends at 250ms never put more than 8 in one
     *                         window
     * @param throttleCooldown how long to stop after a throttled request (HTTP 200, {@code code 510})
     * @param wafCooldown      how long to stop after an HTTP 403 (Akamai WAF block) or 429
     */
    public record MarketSnapshot(int depthLimit, Duration requestInterval, Duration throttleCooldown,
                                 Duration wafCooldown) {

        public MarketSnapshot {
            if (depthLimit < 1 || depthLimit > MAX_DEPTH_LIMIT) {
                throw new IllegalArgumentException("depth-limit must be in [1, " + MAX_DEPTH_LIMIT + "], got " + depthLimit);
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
