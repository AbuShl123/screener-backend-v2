package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.analysis.rule.ClassificationRuleService;
import dev.abu.screener_backend.binance.disruptor.DisruptorShardManager;
import dev.abu.screener_backend.config.OrderbookProperties;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Refcount lifecycle for {@link UserFeedRegistry}. Uses hand-rolled test doubles (no Mockito) so
 * it has no dependency beyond JUnit, which is already on the test classpath.
 */
class UserFeedRegistryTest {

    /** Records the array reference pushed to the shards; never starts a real Disruptor. */
    static class FakeShardManager extends DisruptorShardManager {
        UserClassificationContext[] lastPushed = new UserClassificationContext[0];
        int pushCount = 0;

        FakeShardManager() {
            super(null, null, null, null);
        }

        @Override
        public void setActiveUserContexts(UserClassificationContext[] ctxs) {
            this.lastPushed = ctxs;
            this.pushCount++;
        }
    }

    /** Returns a canned rule per user; counts how often a DB translation was requested. */
    static class FakeRuleService extends ClassificationRuleService {
        final Map<UUID, UserClassificationRules> rules = new HashMap<>();
        int buildCount = 0;

        FakeRuleService() {
            super(null, null, null, new OrderbookProperties(0.3, 6000, 6000), 200);
        }

        @Override
        public Optional<UserClassificationRules> buildRuntimeRule(UUID userId) {
            buildCount++;
            return Optional.ofNullable(rules.get(userId));
        }
    }

    private static UserClassificationRules ruleWith(String key) {
        ThresholdClassificationRule leaf = ThresholdClassificationRule.of(List.of(
                new ThresholdClassificationRule.TierThreshold(1, 100_000, 0.01)));
        return new UserClassificationRules(Map.of(key, leaf));
    }

    @Test
    void connectBuildsContextAndPushesToShards() {
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UUID user = UUID.randomUUID();
        svc.rules.put(user, ruleWith("BTCUSDT:FUTURES"));

        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);
        UserClassificationContext ctx = registry.onUserConnect(user);

        assertNotNull(ctx);
        assertEquals(user, ctx.userId());
        assertEquals(1, registry.activeContexts().length);
        assertSame(ctx, registry.activeContexts()[0]);
        assertEquals(1, shard.pushCount);
        assertSame(registry.activeContexts(), shard.lastPushed); // same array reference fanned out
    }

    @Test
    void userWithNoRulesGetsNoContext() {
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);

        UserClassificationContext ctx = registry.onUserConnect(UUID.randomUUID());

        assertNull(ctx);
        assertEquals(0, registry.activeContexts().length);
        assertEquals(0, shard.pushCount); // nothing changed → no push
    }

    @Test
    void secondConnectReusesContextWithoutReload() {
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UUID user = UUID.randomUUID();
        svc.rules.put(user, ruleWith("BTCUSDT:FUTURES"));
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);

        UserClassificationContext first = registry.onUserConnect(user);
        int pushesAfterFirst = shard.pushCount;
        UserClassificationContext second = registry.onUserConnect(user);

        assertSame(first, second);                        // reuse — no new context
        assertEquals(1, svc.buildCount);                  // DB translated exactly once
        assertEquals(1, registry.activeContexts().length);
        assertEquals(pushesAfterFirst, shard.pushCount);  // reuse does not re-push
    }

    @Test
    void contextSurvivesUntilLastSessionDisconnects() {
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UUID user = UUID.randomUUID();
        svc.rules.put(user, ruleWith("BTCUSDT:FUTURES"));
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);

        UserClassificationContext ctx = registry.onUserConnect(user); // refcount 1
        registry.onUserConnect(user);                                 // refcount 2

        registry.onUserDisconnect(user);                              // refcount 1 — still present
        assertEquals(1, registry.activeContexts().length);
        assertSame(ctx, registry.activeContexts()[0]);

        registry.onUserDisconnect(user);                              // refcount 0 — discarded
        assertEquals(0, registry.activeContexts().length);
    }

    @Test
    void disconnectUnknownUserIsNoOp() {
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);

        assertDoesNotThrow(() -> registry.onUserDisconnect(UUID.randomUUID()));
        assertEquals(0, registry.activeContexts().length);
    }
}
