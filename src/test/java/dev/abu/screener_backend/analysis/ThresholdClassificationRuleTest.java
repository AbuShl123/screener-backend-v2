package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.analysis.ThresholdClassificationRule.TierThreshold;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit test for the standalone user-override rule. Verifies highest-first resolution, the
 * no-match fall-through to tier 0, and the precomputed widest distance — all independent of
 * the {@code highLiquidity} flag, which the rule must ignore.
 */
class ThresholdClassificationRuleTest {

    @Test
    void highestTierWins_whenMultipleBandsQualify() {
        // deliberately unsorted input — tier 1 listed before tier 4
        ThresholdClassificationRule rule = ThresholdClassificationRule.of(List.of(
                new TierThreshold(1, 200_000, 0.01),
                new TierThreshold(4, 5_000_000, 0.04)
        ));

        // matches both bands → must resolve to the higher tier (4), not 1
        assertEquals(4, rule.computeTier(5_000_000, 0.005, false));
    }

    @Test
    void matchesOnlyLowerBand() {
        ThresholdClassificationRule rule = ThresholdClassificationRule.of(List.of(
                new TierThreshold(1, 200_000, 0.01),
                new TierThreshold(4, 5_000_000, 0.04)
        ));

        // notional too low for tier 4, but qualifies for tier 1
        assertEquals(1, rule.computeTier(300_000, 0.008, false));
    }

    @Test
    void matchesNoBand() {
        ThresholdClassificationRule rule = ThresholdClassificationRule.of(List.of(
                new TierThreshold(1, 200_000, 0.01),
                new TierThreshold(4, 5_000_000, 0.04)
        ));

        // notional too low for any band
        assertEquals(0, rule.computeTier(100_000, 0.005, false));
        // distance too far for any band
        assertEquals(0, rule.computeTier(10_000_000, 0.05, false));
    }

    @Test
    void maxDistance_returnsWidest_ignoringHighLiquidityFlag() {
        ThresholdClassificationRule rule = ThresholdClassificationRule.of(List.of(
                new TierThreshold(1, 200_000, 0.01),
                new TierThreshold(4, 5_000_000, 0.04)
        ));

        assertEquals(0.04, rule.maxDistance(false));
        assertEquals(0.04, rule.maxDistance(true));
    }

    @Test
    void singleTierRule_evaluatesCorrectly() {
        ThresholdClassificationRule rule = ThresholdClassificationRule.of(List.of(
                new TierThreshold(2, 1_000_000, 0.02)
        ));

        assertEquals(2, rule.computeTier(1_000_000, 0.02, true));
        assertEquals(0, rule.computeTier(999_999, 0.02, true));
        assertEquals(0.02, rule.maxDistance(false));
    }

    @Test
    void fourTierRule_evaluatesEachBand() {
        ThresholdClassificationRule rule = ThresholdClassificationRule.of(List.of(
                new TierThreshold(1, 100_000, 0.005),
                new TierThreshold(2, 500_000, 0.01),
                new TierThreshold(3, 1_000_000, 0.02),
                new TierThreshold(4, 5_000_000, 0.04)
        ));

        assertEquals(4, rule.computeTier(5_000_000, 0.001, false));
        assertEquals(3, rule.computeTier(1_000_000, 0.015, false));
        assertEquals(2, rule.computeTier(500_000, 0.008, false));
        assertEquals(1, rule.computeTier(100_000, 0.004, false));
        assertEquals(0, rule.computeTier(50_000, 0.5, false));
        assertEquals(0.04, rule.maxDistance(true));
    }
}
