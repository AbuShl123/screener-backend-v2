package dev.abu.screener_backend.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A user-supplied classification rule for one (symbol, market). Absolute thresholds, no
 * high-liquidity special-casing. Backed by parallel primitive arrays sorted highest-tier-first
 * for allocation-free hot-path evaluation. Immutable — built once at user connect/update time.
 */
public final class ThresholdClassificationRule implements ClassificationRule {

    /** Construction input — one tier band. Decoupled from JPA; Phase C maps DB rows to these. */
    public record TierThreshold(int tier, double minNotional, double maxDistance) {}

    private final int[]    tiers;          // sorted by tier DESC (highest-first)
    private final double[] minNotionals;   // parallel to tiers
    private final double[] maxDistances;   // parallel to tiers
    private final double   widestDistance; // precomputed max of maxDistances

    private ThresholdClassificationRule(int[] tiers, double[] minNotionals,
                                        double[] maxDistances, double widestDistance) {
        this.tiers          = tiers;
        this.minNotionals   = minNotionals;
        this.maxDistances   = maxDistances;
        this.widestDistance = widestDistance;
    }

    /**
     * Builds an immutable rule from an unordered list of tier bands. Sorts highest-tier-first
     * and precomputes the widest distance. Callers (Phase C) are expected to pass validated
     * input — tiers in [1,4], no duplicates — but this method does not re-validate.
     */
    public static ThresholdClassificationRule of(List<TierThreshold> bands) {
        List<TierThreshold> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparingInt(TierThreshold::tier).reversed());

        int n = sorted.size();
        int[]    t  = new int[n];
        double[] mn = new double[n];
        double[] md = new double[n];
        double widest = 0.0;
        for (int i = 0; i < n; i++) {
            TierThreshold b = sorted.get(i);
            t[i]  = b.tier();
            mn[i] = b.minNotional();
            md[i] = b.maxDistance();
            if (b.maxDistance() > widest) widest = b.maxDistance();
        }
        return new ThresholdClassificationRule(t, mn, md, widest);
    }

    @Override
    public int computeTier(double notional, double distance, boolean highLiquidity) {
        // highLiquidity intentionally ignored — user thresholds are absolute.
        for (int i = 0; i < tiers.length; i++) {
            if (notional >= minNotionals[i] && distance <= maxDistances[i]) {
                return tiers[i]; // highest-first order → first match is the highest qualifying tier
            }
        }
        return 0;
    }

    @Override
    public double maxDistance(boolean highLiquidity) {
        return widestDistance;
    }
}
