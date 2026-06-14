package dev.abu.screener_backend.entitlement;

import dev.abu.screener_backend.config.BillingProperties;
import dev.abu.screener_backend.user.User;
import dev.abu.screener_backend.user.UserRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EntitlementService}: trial seeding, the stacking grant (from a past vs a
 * future expiry), and the derived {@link AccessState} for all four cases including the ADMIN
 * short-circuit. The repository is a stateful in-memory reflective proxy (the codebase avoids
 * Mockito) keyed by user id; {@code save} mirrors JPA {@code @MapsId} by deriving the id from the
 * {@code user} association when absent.
 */
class EntitlementServiceTest {

    private final Map<UUID, UserEntitlement> store = new HashMap<>();
    private final EntitlementService service =
            new EntitlementService(repo(store), new BillingProperties(Duration.ofDays(7), "UZS", "UZ"));

    @SuppressWarnings("unchecked")
    private static UserEntitlementRepository repo(Map<UUID, UserEntitlement> store) {
        return (UserEntitlementRepository) Proxy.newProxyInstance(
                UserEntitlementRepository.class.getClassLoader(),
                new Class<?>[]{UserEntitlementRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        UserEntitlement e = (UserEntitlement) args[0];
                        UUID id = e.getUserId() != null ? e.getUserId() : e.getUser().getId();
                        e.setUserId(id);
                        store.put(id, e);
                        yield e;
                    }
                    case "findByUserId" -> Optional.ofNullable(store.get((UUID) args[0]));
                    default -> Optional.class.isAssignableFrom(method.getReturnType()) ? Optional.empty() : null;
                });
    }

    private static User user(UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        return u;
    }

    private UserEntitlement seed(UUID userId, Instant expiresAt, boolean paid) {
        UserEntitlement e = new UserEntitlement();
        e.setUserId(userId);
        e.setAccessExpiresAt(expiresAt);
        e.setHasPaid(paid);
        store.put(userId, e);
        return e;
    }

    @Test
    void startTrialSeedsSevenDayUnpaidTrial() {
        User u = user(UserRole.USER);
        Instant before = Instant.now();

        service.startTrial(u);

        UserEntitlement e = store.get(u.getId());
        assertNotNull(e);
        assertTrue(e.getAccessExpiresAt().isAfter(before.plus(Duration.ofDays(7)).minusSeconds(5)));
        assertTrue(e.getAccessExpiresAt().isBefore(before.plus(Duration.ofDays(7)).plusSeconds(5)));
        assertEquals(false, e.isHasPaid());
        assertEquals(AccessState.TRIAL, service.currentState(u).state());
    }

    @Test
    void extendFromFutureExpiryStacksOnRemainingTime() {
        User u = user(UserRole.USER);
        Instant future = Instant.now().plus(Duration.ofDays(10));
        seed(u.getId(), future, false);

        service.extend(u.getId(), Duration.ofDays(30), true);

        UserEntitlement e = store.get(u.getId());
        // Stacks on the remaining future time: future + 30d (not now + 30d).
        assertEquals(future.plus(Duration.ofDays(30)), e.getAccessExpiresAt());
        assertTrue(e.isHasPaid());
        assertEquals(AccessState.ACTIVE, service.currentState(u).state());
    }

    @Test
    void extendFromPastExpiryStartsFromNow() {
        User u = user(UserRole.USER);
        Instant past = Instant.now().minus(Duration.ofDays(3));
        seed(u.getId(), past, false);
        Instant before = Instant.now();

        service.extend(u.getId(), Duration.ofDays(7), true);

        Instant result = store.get(u.getId()).getAccessExpiresAt();
        // Past expiry is ignored: base is "now", so result ≈ now + 7d.
        assertTrue(result.isAfter(before.plus(Duration.ofDays(7)).minusSeconds(5)));
        assertTrue(result.isBefore(before.plus(Duration.ofDays(7)).plusSeconds(5)));
    }

    @Test
    void currentStateAdminShortCircuitsWithNullExpiry() {
        User admin = user(UserRole.ADMIN);
        // Even with a stored (ignored) row, ADMIN reports ADMIN/null.
        seed(admin.getId(), Instant.now().minus(Duration.ofDays(1)), false);

        EntitlementView view = service.currentState(admin);

        assertEquals(AccessState.ADMIN, view.state());
        assertNull(view.accessExpiresAt());
    }

    @Test
    void currentStateExpiredWhenPastOrNeverGranted() {
        User u = user(UserRole.USER);
        seed(u.getId(), Instant.now().minus(Duration.ofSeconds(1)), true);
        assertEquals(AccessState.EXPIRED, service.currentState(u).state());

        User orphan = user(UserRole.USER); // no row at all
        EntitlementView view = service.currentState(orphan);
        assertEquals(AccessState.EXPIRED, view.state());
        assertNull(view.accessExpiresAt());
    }

    @Test
    void currentStateTrialVsActiveByHasPaid() {
        User trial = user(UserRole.USER);
        seed(trial.getId(), Instant.now().plus(Duration.ofDays(5)), false);
        assertEquals(AccessState.TRIAL, service.currentState(trial).state());

        User active = user(UserRole.USER);
        seed(active.getId(), Instant.now().plus(Duration.ofDays(5)), true);
        assertEquals(AccessState.ACTIVE, service.currentState(active).state());
    }

    @Test
    void hasAccessTrueForAdminAndValidUserFalseOtherwise() {
        assertTrue(service.hasAccess(user(UserRole.ADMIN)));

        User valid = user(UserRole.USER);
        seed(valid.getId(), Instant.now().plus(Duration.ofDays(1)), false);
        assertTrue(service.hasAccess(valid));

        User expired = user(UserRole.USER);
        seed(expired.getId(), Instant.now().minus(Duration.ofDays(1)), false);
        assertEquals(false, service.hasAccess(expired));
    }
}
