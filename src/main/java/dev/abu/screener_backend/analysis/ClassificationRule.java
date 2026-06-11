package dev.abu.screener_backend.analysis;

/**
 * A pure, stateless classification rule. Implementations decide which tier (0–4) a single
 * price level falls into, given its notional value and distance from mid-price.
 *
 * <p>The {@code highLiquidity} flag is a default-rule concern (some tickers use tighter
 * thresholds). The classifier computes it once per book and threads it into whichever rule is
 * active; rules that don't care about it (user rules) simply ignore the parameter.
 */
public interface ClassificationRule {

    // TODO: remove highLiquidity from the methods below

    /**
     * @return the tier 1–4 if the level matches a tier's notional AND distance thresholds,
     *         or 0 (invisible) if it matches none. Higher-numbered tiers are checked first,
     *         so a level that qualifies for several tiers resolves to the highest.
     */
    int computeTier(double notional, double distance, boolean highLiquidity);

    /**
     * @return the widest distance any tier in this rule can match. The classifier uses this to
     *         break out of its distance-ordered top-K loop early: once the top-K buffer is full,
     *         any level beyond this distance can only be tier-0 and is already out-ranked.
     */
    double maxDistance(boolean highLiquidity);
}
