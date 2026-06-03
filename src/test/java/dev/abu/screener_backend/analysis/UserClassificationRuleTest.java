package dev.abu.screener_backend.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserClassificationRuleTest {

    private static ThresholdClassificationRule leaf(int tier, double notional, double distance) {
        return ThresholdClassificationRule.of(List.of(
                new ThresholdClassificationRule.TierThreshold(tier, notional, distance)));
    }

    @Test
    void configuredKeysReflectsByKeyMap() {
        ThresholdClassificationRule btc = leaf(1, 100_000, 0.01);
        ThresholdClassificationRule eth = leaf(2, 200_000, 0.02);
        UserClassificationRule rule = new UserClassificationRule(Map.of(
                "BTCUSDT:FUTURES", btc,
                "ETHUSDT:SPOT", eth));

        assertEquals(2, rule.configuredKeys().size());
        assertTrue(rule.configuredKeys().contains("BTCUSDT:FUTURES"));
        assertTrue(rule.configuredKeys().contains("ETHUSDT:SPOT"));
        assertFalse(rule.configuredKeys().contains("SOLUSDT:FUTURES"));
    }

    @Test
    void ruleForReturnsLeafForConfiguredKeyAndNullOtherwise() {
        ThresholdClassificationRule btc = leaf(1, 100_000, 0.01);
        UserClassificationRule rule = new UserClassificationRule(Map.of("BTCUSDT:FUTURES", btc));

        assertSame(btc, rule.ruleFor("BTCUSDT:FUTURES"));
        assertNull(rule.ruleFor("ETHUSDT:FUTURES"));
    }

    @Test
    void emptyRuleHasNoConfiguredKeys() {
        UserClassificationRule rule = new UserClassificationRule(Map.of());

        assertTrue(rule.configuredKeys().isEmpty());
        assertNull(rule.ruleFor("ANYTHING"));
    }
}
