package dev.abu.screener_backend.user;

import dev.abu.screener_backend.config.AdminProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit test for {@link AdminBootstrap}. Uses a hand-rolled {@link UserRepository} stub (this
 * codebase's tests avoid Mockito) backed by an email→user map, so {@code findByEmail} resolves
 * against registered users and every other repository method is a harmless no-op.
 *
 * <p>The {@code @Transactional} dirty-checking that issues the {@code UPDATE} in production is a
 * Spring container concern; here the in-place {@code setRole} on the returned entity is observed
 * directly, which is what dirty checking would have persisted.
 */
class AdminBootstrapTest {

    /** Reflective stub: {@code findByEmail} consults the backing map; defaults otherwise. */
    @SuppressWarnings("unchecked")
    private static UserRepository stubRepo(Map<String, User> byEmail) {
        return (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> {
                    if ("findByEmail".equals(method.getName())) {
                        return Optional.ofNullable(byEmail.get(args[0]));
                    }
                    if ("save".equals(method.getName())) return args[0];
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (List.class.isAssignableFrom(rt)) return List.of();
                    if (Optional.class.isAssignableFrom(rt)) return Optional.empty();
                    return null;
                });
    }

    private static User userWith(String email, UserRole role) {
        User u = new User();
        u.setEmail(email);
        u.setRole(role);
        return u;
    }

    private static void run(AdminProperties props, UserRepository repo) {
        new AdminBootstrap(props, repo).run(null);
    }

    @Test
    void promotesMatchingUserToAdmin() {
        Map<String, User> byEmail = new HashMap<>();
        User owner = userWith("owner@example.com", UserRole.USER);
        byEmail.put("owner@example.com", owner);

        run(new AdminProperties(List.of("owner@example.com")), stubRepo(byEmail));

        assertEquals(UserRole.ADMIN, owner.getRole());
    }

    @Test
    void leavesAlreadyAdminUnchanged() {
        Map<String, User> byEmail = new HashMap<>();
        User admin = userWith("boss@example.com", UserRole.ADMIN);
        byEmail.put("boss@example.com", admin);

        run(new AdminProperties(List.of("boss@example.com")), stubRepo(byEmail));

        assertEquals(UserRole.ADMIN, admin.getRole());
    }

    @Test
    void unknownEmailIsSkippedWithoutThrowing() {
        Map<String, User> byEmail = new HashMap<>();

        assertDoesNotThrow(() ->
                run(new AdminProperties(List.of("ghost@example.com")), stubRepo(byEmail)));
    }

    @Test
    void blankAndEmptyEntriesAreIgnored() {
        Map<String, User> byEmail = new HashMap<>();
        User owner = userWith("owner@example.com", UserRole.USER);
        byEmail.put("owner@example.com", owner);

        // Blank entries skipped; whitespace and case around a real email are normalized before lookup.
        run(new AdminProperties(List.of("  ", "", "  OWNER@example.com  ")), stubRepo(byEmail));

        assertEquals(UserRole.ADMIN, owner.getRole());
    }
}
