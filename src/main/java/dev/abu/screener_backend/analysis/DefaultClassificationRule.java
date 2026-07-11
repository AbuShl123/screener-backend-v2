package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.analysis.rule.dto.DefaultRuleResponse;
import dev.abu.screener_backend.analysis.rule.dto.TierDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * The default, system-wide classification rule. Holds the notional/distance threshold tables
 * and the high-liquidity ticker set that {@link OrderBookClassifier} used to own directly. A
 * single stateless {@code @Component} instance is shared across all shards.
 *
 * <p>{@link #NORMAL_TIERS} and {@link #HIGH_LIQUIDITY_TIERS} are the single source of truth:
 * both the hot-path {@link #computeTier} evaluation and the client-facing {@link #toResponse}
 * payload are derived from these two lists, so the numbers the API advertises can never drift
 * from what the classifier actually applies. (They previously didn't share a source — one copy
 * of the tier-1 threshold was edited without the other, and only {@link #toResponse}'s copy was
 * correct.)
 */
@Component
public class DefaultClassificationRule implements ClassificationRule {

    /**
     * High-liquidity tickers use tighter notional/distance thresholds due to deeper books
     * and tighter spreads — standard thresholds would classify nearly everything as tier-4.
     */
    private static final Set<String> HIGH_LIQUIDITY_TICKERS = Set.of("BTCUSDT", "ETHUSDT", "SOLUSDT");

    private static final List<TierDto> NORMAL_TIERS = List.of(
            new TierDto(4, 10_000_000,  0.05),
            new TierDto(3,  1_000_000,  0.02),
            new TierDto(2,    500_000,  0.01),
            new TierDto(1,    200_000,  0.005)
    );

    private static final List<TierDto> HIGH_LIQUIDITY_TIERS = List.of(
            new TierDto(4, 100_000_000, 0.025),
            new TierDto(3,  30_000_000, 0.01),
            new TierDto(2,  10_000_000, 0.005),
            new TierDto(1,   3_000_000, 0.0025)
    );

    // Allocation-free runtime evaluators built once from the tables above, reusing the same
    // sorted-array evaluation ThresholdClassificationRule already provides for per-user rules.
    private static final ThresholdClassificationRule NORMAL_RULE = toRuntimeRule(NORMAL_TIERS);
    private static final ThresholdClassificationRule HIGH_LIQUIDITY_RULE = toRuntimeRule(HIGH_LIQUIDITY_TIERS);

    private static ThresholdClassificationRule toRuntimeRule(List<TierDto> tiers) {
        List<ThresholdClassificationRule.TierThreshold> bands = tiers.stream()
                .map(t -> new ThresholdClassificationRule.TierThreshold(t.tier(), t.minNotional(), t.maxDistance()))
                .toList();
        return ThresholdClassificationRule.of(bands);
    }

    /** True if {@code symbol} uses the tighter high-liquidity threshold table. */
    public boolean isHighLiquidity(String symbol) {
        return HIGH_LIQUIDITY_TICKERS.contains(symbol);
    }

    /**
     * Delegates to the pre-built runtime rule for the applicable table. Checks tiers 4→1 in
     * order; returns the first (highest-numbered) tier whose both notional and distance
     * thresholds are satisfied, or 0 if none match. Checking highest first means a $100M order
     * at 0.1% distance resolves to tier-4 (not tier-1), because higher notional grants a wider
     * distance window rather than a better tier number.
     */
    @Override
    public int computeTier(double notional, double distance, boolean highLiquidity) {
        return (highLiquidity ? HIGH_LIQUIDITY_RULE : NORMAL_RULE).computeTier(notional, distance, highLiquidity);
    }

    @Override
    public double maxDistance(boolean highLiquidity) {
        return (highLiquidity ? HIGH_LIQUIDITY_RULE : NORMAL_RULE).maxDistance(highLiquidity);
    }

    /** Returns both threshold tables and the high-liquidity symbol list for client display. */
    public DefaultRuleResponse toResponse() {
        return new DefaultRuleResponse(NORMAL_TIERS, List.of("BTCUSDT", "ETHUSDT", "SOLUSDT"), HIGH_LIQUIDITY_TIERS);
    }
}
