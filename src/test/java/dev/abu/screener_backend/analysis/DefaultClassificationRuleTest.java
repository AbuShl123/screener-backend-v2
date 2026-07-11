package dev.abu.screener_backend.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization test pinning the thresholds moved out of {@code OrderBookClassifier} so the
 * Phase B refactor cannot silently alter the default feed. Boundaries are exercised per tier.
 */
class DefaultClassificationRuleTest {

    private final DefaultClassificationRule rule = new DefaultClassificationRule();

    @Test
    void isHighLiquidity_recognisesConfiguredTickers() {
        assertTrue(rule.isHighLiquidity("BTCUSDT"));
        assertTrue(rule.isHighLiquidity("ETHUSDT"));
        assertTrue(rule.isHighLiquidity("SOLUSDT"));
        assertFalse(rule.isHighLiquidity("DOGEUSDT"));
    }

    // --- normal table: notional >= X && distance <= Y ---

    @Test
    void normalTable_tierBoundaries() {
        // tier 4: notional >= 10_000_000 && distance <= 0.05
        assertEquals(4, rule.computeTier(10_000_000, 0.05, false));
        // tier 3: notional >= 1_000_000 && distance <= 0.02
        assertEquals(3, rule.computeTier(1_000_000, 0.02, false));
        // tier 2: notional >= 500_000 && distance <= 0.01
        assertEquals(2, rule.computeTier(500_000, 0.01, false));
        // tier 1: notional >= 200_000 && distance <= 0.005
        assertEquals(1, rule.computeTier(200_000, 0.005, false));
    }

    @Test
    void normalTable_justBelowNotional_dropsTier() {
        // just below tier-4 notional but still meets tier-3 (distance within tier-3's 0.02 window) → tier 3
        assertEquals(3, rule.computeTier(9_999_999, 0.02, false));
        // just below tier-1 notional → 0
        assertEquals(0, rule.computeTier(199_999, 0.005, false));
    }

    @Test
    void normalTable_justBeyondDistance_dropsTier() {
        // tier-4 notional but distance beyond tier-4 window; falls through to lower tiers,
        // each of which also fails its tighter distance → 0
        assertEquals(0, rule.computeTier(10_000_000, 0.0500001, false));
        // tier-2 notional just beyond its distance, but still within tier-1's? no — tier-1 needs 0.005
        assertEquals(0, rule.computeTier(500_000, 0.0100001, false));
    }

    @Test
    void normalTable_clearTierZero() {
        assertEquals(0, rule.computeTier(100, 0.5, false));
    }

    // --- high-liquidity table ---

    @Test
    void highLiquidityTable_tierBoundaries() {
        // tier 4: notional >= 100_000_000 && distance <= 0.025
        assertEquals(4, rule.computeTier(100_000_000, 0.025, true));
        // tier 3: notional >= 30_000_000 && distance <= 0.01
        assertEquals(3, rule.computeTier(30_000_000, 0.01, true));
        // tier 2: notional >= 10_000_000 && distance <= 0.005
        assertEquals(2, rule.computeTier(10_000_000, 0.005, true));
        // tier 1: notional >= 3_000_000 && distance <= 0.0025
        assertEquals(1, rule.computeTier(3_000_000, 0.0025, true));
    }

    @Test
    void highLiquidityTable_clearTierZero() {
        // a $10M order at 3% distance is tier-4 under the normal table but tier-0 under HL
        assertEquals(0, rule.computeTier(10_000_000, 0.03, true));
    }

    @Test
    void highestTierWins_whenMultipleQualify() {
        // a $100M order at 0.1% distance qualifies for every normal tier → resolves to 4
        assertEquals(4, rule.computeTier(100_000_000, 0.001, false));
    }

    @Test
    void maxDistance_matchesEarlyBreakConstants() {
        assertEquals(0.025, rule.maxDistance(true));
        assertEquals(0.05, rule.maxDistance(false));
    }
}
