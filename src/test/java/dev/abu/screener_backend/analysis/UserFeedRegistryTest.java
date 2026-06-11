package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.analysis.rule.ClassificationRuleService;
import dev.abu.screener_backend.analysis.rule.RuleUpdatedEvent;
import dev.abu.screener_backend.binance.disruptor.DisruptorShardManager;
import dev.abu.screener_backend.config.OrderbookProperties;
import dev.abu.screener_backend.ws.UserWebSocketSession;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Session/context lifecycle and live rule propagation for {@link UserFeedRegistry}. Uses
 * hand-rolled test doubles (no Mockito) so it has no dependency beyond JUnit, which is already
 * on the test classpath. Sessions are constructed with a {@code null} Jakarta session — the
 * send loop is never started, so only the context/status fields are exercised.
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
            super(null, null, null, null, new OrderbookProperties(0.3, 6000, 6000), 200);
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

    private static UserWebSocketSession newSession(UUID userId) {
        return new UserWebSocketSession(null, userId);
    }

    // ---------------------------------------------------------------------------------------
    // Connect / disconnect lifecycle
    // ---------------------------------------------------------------------------------------

    @Test
    void connectBuildsContextAndPushesToShards() {
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UUID user = UUID.randomUUID();
        svc.rules.put(user, ruleWith("BTCUSDT:FUTURES"));

        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);
        UserWebSocketSession session = newSession(user);
        registry.onUserConnect(user, session);

        UserClassificationContext ctx = session.getContext();
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
        UUID user = UUID.randomUUID();
        UserWebSocketSession session = newSession(user);

        registry.onUserConnect(user, session);

        assertNull(session.getContext());
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

        UserWebSocketSession first = newSession(user);
        registry.onUserConnect(user, first);
        int pushesAfterFirst = shard.pushCount;
        UserWebSocketSession second = newSession(user);
        registry.onUserConnect(user, second);

        assertSame(first.getContext(), second.getContext());  // reuse — no new context
        assertEquals(1, svc.buildCount);                       // DB translated exactly once
        assertEquals(1, registry.activeContexts().length);
        assertEquals(pushesAfterFirst, shard.pushCount);       // reuse does not re-push
    }

    @Test
    void contextSurvivesUntilLastSessionDisconnects() {
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UUID user = UUID.randomUUID();
        svc.rules.put(user, ruleWith("BTCUSDT:FUTURES"));
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);

        UserWebSocketSession s1 = newSession(user);
        UserWebSocketSession s2 = newSession(user);
        registry.onUserConnect(user, s1);
        registry.onUserConnect(user, s2);
        UserClassificationContext ctx = s1.getContext();

        registry.onUserDisconnect(user, s1);                  // one session left — still present
        assertEquals(1, registry.activeContexts().length);
        assertSame(ctx, registry.activeContexts()[0]);

        registry.onUserDisconnect(user, s2);                  // last session — discarded
        assertEquals(0, registry.activeContexts().length);
    }

    @Test
    void disconnectUnknownUserIsNoOp() {
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);
        UUID user = UUID.randomUUID();

        assertDoesNotThrow(() -> registry.onUserDisconnect(user, newSession(user)));
        assertEquals(0, registry.activeContexts().length);
    }

    @Test
    void doubleDisconnectOfSameSessionIsNoOp() {
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UUID user = UUID.randomUUID();
        svc.rules.put(user, ruleWith("BTCUSDT:FUTURES"));
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);

        UserWebSocketSession s1 = newSession(user);
        UserWebSocketSession s2 = newSession(user);
        registry.onUserConnect(user, s1);
        registry.onUserConnect(user, s2);

        // @OnClose + @OnError double-fire for s1 must not tear down s2's context.
        registry.onUserDisconnect(user, s1);
        registry.onUserDisconnect(user, s1);
        assertEquals(1, registry.activeContexts().length);
        assertSame(s2.getContext(), registry.activeContexts()[0]);
    }

    // ---------------------------------------------------------------------------------------
    // Live rule propagation (onRuleUpdated)
    // ---------------------------------------------------------------------------------------

    @Test
    void ruleUpdateReplacesContextAndQueuesSnapshot() { // Case A
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UUID user = UUID.randomUUID();
        svc.rules.put(user, ruleWith("BTCUSDT:FUTURES"));
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);

        UserWebSocketSession s1 = newSession(user);
        UserWebSocketSession s2 = newSession(user);
        registry.onUserConnect(user, s1);
        registry.onUserConnect(user, s2);
        UserClassificationContext oldCtx = s1.getContext();
        s1.setStatus(UserWebSocketSession.Status.READY); // broadcaster served the initial snapshot
        s2.setStatus(UserWebSocketSession.Status.READY);
        int pushesBefore = shard.pushCount;

        svc.rules.put(user, ruleWith("ETHUSDT:SPOT"));
        registry.onRuleUpdated(new RuleUpdatedEvent(user));

        UserClassificationContext newCtx = s1.getContext();
        assertNotNull(newCtx);
        assertNotSame(oldCtx, newCtx);                         // full rebuild, not in-place swap
        assertSame(newCtx, s2.getContext());                   // both sessions retargeted
        assertNotNull(newCtx.rule().ruleFor("ETHUSDT:SPOT")); // carries the updated thresholds
        assertEquals(UserWebSocketSession.Status.NEED_SNAPSHOT, s1.getStatus());
        assertEquals(UserWebSocketSession.Status.NEED_SNAPSHOT, s2.getStatus());
        assertEquals(1, registry.activeContexts().length);
        assertSame(newCtx, registry.activeContexts()[0]);      // old context no longer active
        assertEquals(pushesBefore + 1, shard.pushCount);
        assertSame(registry.activeContexts(), shard.lastPushed);
    }

    @Test
    void firstRuleAddedWhileConnectedCreatesContext() { // Case B
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UUID user = UUID.randomUUID();
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);

        UserWebSocketSession session = newSession(user);
        registry.onUserConnect(user, session);                 // no rules yet → null context
        assertNull(session.getContext());
        session.setStatus(UserWebSocketSession.Status.READY);

        svc.rules.put(user, ruleWith("BTCUSDT:FUTURES"));
        registry.onRuleUpdated(new RuleUpdatedEvent(user));

        assertNotNull(session.getContext());
        assertEquals(UserWebSocketSession.Status.NEED_SNAPSHOT, session.getStatus());
        assertEquals(1, registry.activeContexts().length);
        assertSame(session.getContext(), registry.activeContexts()[0]);
    }

    @Test
    void ruleUpdateForDisconnectedUserIsNoOp() { // Case B with no sessions
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UUID user = UUID.randomUUID();
        svc.rules.put(user, ruleWith("BTCUSDT:FUTURES"));
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);

        assertDoesNotThrow(() -> registry.onRuleUpdated(new RuleUpdatedEvent(user)));
        assertEquals(0, registry.activeContexts().length);
        assertEquals(0, shard.pushCount);                      // rules load at next connect
    }

    @Test
    void deletingAllRulesRevertsSessionsToGlobalFeed() { // Case C
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UUID user = UUID.randomUUID();
        svc.rules.put(user, ruleWith("BTCUSDT:FUTURES"));
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);

        UserWebSocketSession session = newSession(user);
        registry.onUserConnect(user, session);
        session.setStatus(UserWebSocketSession.Status.READY);
        int pushesBefore = shard.pushCount;

        svc.rules.remove(user);                                // user deleted every rule
        registry.onRuleUpdated(new RuleUpdatedEvent(user));

        assertNull(session.getContext());                      // session reverts to global feed
        assertEquals(UserWebSocketSession.Status.NEED_SNAPSHOT, session.getStatus());
        assertEquals(0, registry.activeContexts().length);
        assertEquals(pushesBefore + 1, shard.pushCount);
    }

    @Test
    void ruleUpdateForUserWithNoRulesAndNoContextIsNoOp() {
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);

        assertDoesNotThrow(() -> registry.onRuleUpdated(new RuleUpdatedEvent(UUID.randomUUID())));
        assertEquals(0, registry.activeContexts().length);
        assertEquals(0, shard.pushCount);
    }

    @Test
    void rapidConsecutiveUpdatesLeaveLastWriteWins() {
        FakeShardManager shard = new FakeShardManager();
        FakeRuleService svc = new FakeRuleService();
        UUID user = UUID.randomUUID();
        svc.rules.put(user, ruleWith("BTCUSDT:FUTURES"));
        UserFeedRegistry registry = new UserFeedRegistry(svc, shard);

        UserWebSocketSession session = newSession(user);
        registry.onUserConnect(user, session);

        svc.rules.put(user, ruleWith("ETHUSDT:SPOT"));
        registry.onRuleUpdated(new RuleUpdatedEvent(user));
        svc.rules.put(user, ruleWith("SOLUSDT:FUTURES"));
        registry.onRuleUpdated(new RuleUpdatedEvent(user));

        UserClassificationContext ctx = session.getContext();
        assertNotNull(ctx.rule().ruleFor("SOLUSDT:FUTURES")); // final state reflects last write
        assertNull(ctx.rule().ruleFor("ETHUSDT:SPOT"));
        assertEquals(1, registry.activeContexts().length);
        assertSame(ctx, registry.activeContexts()[0]);
    }
}
