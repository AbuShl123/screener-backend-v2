package dev.abu.screener_backend.analysis.rule;

import dev.abu.screener_backend.analysis.rule.dto.BulkDeleteRequest;
import dev.abu.screener_backend.analysis.rule.dto.BulkRuleRequest;
import dev.abu.screener_backend.analysis.rule.dto.RuleAssignmentDto;
import dev.abu.screener_backend.analysis.rule.dto.RuleDto;
import dev.abu.screener_backend.analysis.rule.dto.TargetDto;
import dev.abu.screener_backend.analysis.rule.dto.TierDto;
import dev.abu.screener_backend.binance.websocket.Market;
import dev.abu.screener_backend.config.OrderbookProperties;
import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.ticker.Ticker;
import dev.abu.screener_backend.ticker.TickerRegistry;
import dev.abu.screener_backend.user.User;
import dev.abu.screener_backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RuleUpdatedEvent} publication semantics for {@link ClassificationRuleService}:
 * published exactly once after a successful upsert/delete, never on validation failure. The
 * repositories are reflective no-op proxies (this codebase's tests avoid Mockito) — persistence
 * behaviour itself is not under test here.
 *
 * <p>The {@code @TransactionalEventListener(AFTER_COMMIT)} deferral is a Spring container concern
 * and is not exercised by these unit tests.
 */
class ClassificationRuleServiceTest {

    /** Minimal reflective stub: returns the saved entity, empty lists, defaults otherwise. */
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> iface) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) return args[0];
                    if ("getReferenceById".equals(method.getName())) return new User();
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (List.class.isAssignableFrom(rt)) return List.of();
                    if (Optional.class.isAssignableFrom(rt)) return Optional.empty();
                    return null;
                });
    }

    private final List<Object> published = new ArrayList<>();

    private ClassificationRuleService newService() {
        TickerRegistry tickers = new TickerRegistry();
        tickers.replace(Map.of("BTCUSDT", new Ticker("BTCUSDT", true, true)));
        ApplicationEventPublisher publisher = published::add;
        return new ClassificationRuleService(
                stub(ClassificationRuleRepository.class),
                stub(UserRepository.class),
                tickers,
                publisher,
                new OrderbookProperties(0.3, 6000, 6000),
                200);
    }

    private static BulkRuleRequest validUpsert() {
        return new BulkRuleRequest(List.of(new RuleAssignmentDto(
                new RuleDto(List.of(new TierDto(1, 100_000, 0.01))),
                List.of(new TargetDto("BTCUSDT", Market.FUTURES)))));
    }

    @Test
    void upsertPublishesRuleUpdatedEvent() {
        ClassificationRuleService svc = newService();
        UUID user = UUID.randomUUID();

        svc.upsertRules(user, validUpsert());

        assertEquals(List.of(new RuleUpdatedEvent(user)), published);
    }

    @Test
    void deletePublishesRuleUpdatedEvent() {
        ClassificationRuleService svc = newService();
        UUID user = UUID.randomUUID();

        svc.deleteRules(user, new BulkDeleteRequest(
                List.of(new TargetDto("BTCUSDT", Market.FUTURES))));

        assertEquals(List.of(new RuleUpdatedEvent(user)), published);
    }

    @Test
    void upsertValidationFailureDoesNotPublish() {
        ClassificationRuleService svc = newService();

        // Unknown symbol — rejected before any write.
        BulkRuleRequest bad = new BulkRuleRequest(List.of(new RuleAssignmentDto(
                new RuleDto(List.of(new TierDto(1, 100_000, 0.01))),
                List.of(new TargetDto("NOPEUSDT", Market.FUTURES)))));

        assertThrows(ApiException.class, () -> svc.upsertRules(UUID.randomUUID(), bad));
        assertTrue(published.isEmpty());
    }

    @Test
    void deleteValidationFailureDoesNotPublish() {
        ClassificationRuleService svc = newService();

        assertThrows(ApiException.class,
                () -> svc.deleteRules(UUID.randomUUID(), new BulkDeleteRequest(List.of())));
        assertTrue(published.isEmpty());
    }
}
