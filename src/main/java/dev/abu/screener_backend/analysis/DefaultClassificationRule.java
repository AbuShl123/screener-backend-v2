package dev.abu.screener_backend.analysis;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The default, system-wide classification rule. Holds the hardcoded notional/distance
 * threshold tables and the high-liquidity ticker set that {@link OrderBookClassifier} used to
 * own directly. A single stateless {@code @Component} instance is shared across all shards.
 */
@Component
public class DefaultClassificationRule implements ClassificationRule {

    /**
     * High-liquidity tickers use tighter notional/distance thresholds due to deeper books
     * and tighter spreads — standard thresholds would classify nearly everything as tier-4.
     */
    private static final Set<String> HIGH_LIQUIDITY_TICKERS = Set.of("BTCUSDT", "ETHUSDT", "SOLUSDT");

    /** True if {@code symbol} uses the tighter high-liquidity threshold table. */
    public boolean isHighLiquidity(String symbol) {
        return HIGH_LIQUIDITY_TICKERS.contains(symbol);
    }

    /**
     * Checks tiers 4→1 in order; returns the first (highest-numbered) tier whose both
     * notional and distance thresholds are satisfied. Returns 0 if none match.
     * Checking highest first means a $100M order at 0.1% distance resolves to tier-4
     * (not tier-1), because higher notional grants a wider distance window rather than
     * a better tier number.
     */
    @Override
    public int computeTier(double notional, double distance, boolean highLiquidity) {
        if (highLiquidity) {
            if (notional >= 100_000_000 && distance <= 0.025)  return 4;
            if (notional >= 30_000_000  && distance <= 0.01)   return 3;
            if (notional >= 10_000_000  && distance <= 0.005)  return 2;
            if (notional >= 3_000_000   && distance <= 0.0025) return 1;
        } else {
            if (notional >= 10_000_000  && distance <= 0.05)   return 4;
            if (notional >= 1_000_000   && distance <= 0.02)   return 3;
            if (notional >= 500_000     && distance <= 0.01)   return 2;
            if (notional >= 300_000     && distance <= 0.005)  return 1;
        }
        return 0;
    }

    @Override
    public double maxDistance(boolean highLiquidity) {
        // Matches the widest (tier-4) max_distance of each table above, preserving the exact
        // early-break behavior the classifier has today (was: highLiquidity ? 0.025 : 0.05).
        return highLiquidity ? 0.025 : 0.05;
    }
}
