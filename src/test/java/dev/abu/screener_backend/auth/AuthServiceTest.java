package dev.abu.screener_backend.auth;

import dev.abu.screener_backend.auth.dto.AuthResponse;
import dev.abu.screener_backend.auth.dto.LoginRequest;
import dev.abu.screener_backend.auth.dto.RegisterRequest;
import dev.abu.screener_backend.auth.dto.RegisterResponse;
import dev.abu.screener_backend.config.BillingProperties;
import dev.abu.screener_backend.config.EmailProperties;
import dev.abu.screener_backend.config.JwtProperties;
import dev.abu.screener_backend.entitlement.EntitlementService;
import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.user.EmailVerificationToken;
import dev.abu.screener_backend.user.EmailVerificationTokenRepository;
import dev.abu.screener_backend.user.RefreshToken;
import dev.abu.screener_backend.user.RefreshTokenRepository;
import dev.abu.screener_backend.user.User;
import dev.abu.screener_backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AuthService}'s email-verification behaviour, in the codebase's plain-JUnit +
 * reflective-proxy-repo house style (no Mockito). {@link JwtService} and {@link PasswordEncoder} are
 * the real implementations (their crypto is exercised directly); {@link EntitlementService} is a
 * trivial subclass recording the trial seed; repositories are stateful in-memory proxies.
 *
 * <p>Covers: register persists an unverified user, seeds the trial, issues NO refresh token, and
 * publishes exactly one {@link RegistrationEmailEvent}; login gates unverified users with 403 but only
 * AFTER the 401 password/enabled checks (enumeration guard); verify flips + single-uses the token; and
 * resend respects the cooldown and never leaks via a publish.
 */
class AuthServiceTest {

    private final Map<UUID, User> usersById = new HashMap<>();
    private final Map<String, User> usersByEmail = new HashMap<>();
    private final List<EmailVerificationToken> evTokens = new ArrayList<>();
    private final List<Object> events = new ArrayList<>();
    private int refreshSaveCount = 0;
    private boolean trialSeeded = false;

    private final JwtService jwtService = new JwtService(new JwtProperties(
            Base64.getEncoder().encodeToString("this-is-a-test-secret-key-of-32b!".getBytes(StandardCharsets.UTF_8)),
            Duration.ofHours(3), Duration.ofDays(7)));
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final EmailProperties emailProps = new EmailProperties(
            "noreply@test.com", "TC Screener", Duration.ofHours(24), Duration.ofMinutes(1),
            "https://app.test.com/verify-email");

    private final EntitlementService entitlement =
            new EntitlementService(null, null, null, new BillingProperties(null, null, null, null)) {
                @Override
                public void startTrial(User user) {
                    trialSeeded = true;
                }
            };

    private final AuthService service = new AuthService(
            userRepo(), refreshRepo(), evTokenRepo(), jwtService, encoder, entitlement,
            (ApplicationEventPublisher) events::add, emailProps,
            new JwtProperties("dummy", Duration.ofHours(3), Duration.ofDays(7)));

    // --- register -------------------------------------------------------------------------------

    @Test
    void registerCreatesUnverifiedUserSeedsTrialIssuesNoRefreshTokenAndPublishesOneEmailEvent() {
        RegisterResponse resp = service.register(new RegisterRequest("Alice", "Ng", "Test@Email.com", "pw123456"));

        assertEquals("VERIFICATION_REQUIRED", resp.status());
        assertEquals("test@email.com", resp.email());

        User stored = usersByEmail.get("test@email.com");
        assertTrue(stored != null, "user persisted");
        assertTrue(!stored.isEmailVerified(), "new user is unverified");
        assertTrue(trialSeeded, "trial seeded in the register transaction");
        assertEquals(0, refreshSaveCount, "register issues no refresh token");

        assertEquals(1, events.size());
        RegistrationEmailEvent ev = (RegistrationEmailEvent) events.get(0);
        assertEquals("test@email.com", ev.email());
        assertTrue(ev.rawToken() != null && !ev.rawToken().isBlank(), "raw token carried in-memory");

        // Exactly one token row, and only its SHA-256 hash is stored (never the raw value).
        assertEquals(1, evTokens.size());
        assertEquals(jwtService.hashToken(ev.rawToken()), evTokens.get(0).getTokenHash());
    }

    // --- login gate + enumeration guard ---------------------------------------------------------

    @Test
    void loginUnverifiedThrowsForbidden() {
        seedUser("u@e.com", "pw123456", true, false);
        ApiException ex = expectApi(() -> service.login(new LoginRequest("u@e.com", "pw123456")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void loginVerifiedSucceeds() {
        seedUser("u@e.com", "pw123456", true, true);
        AuthResponse resp = service.login(new LoginRequest("u@e.com", "pw123456"));
        assertTrue(resp.accessToken() != null && !resp.accessToken().isBlank());
    }

    @Test
    void loginBadPasswordIsUnauthorizedBeforeVerificationCheck() {
        seedUser("u@e.com", "pw123456", true, false); // unverified, but wrong password must win first
        ApiException ex = expectApi(() -> service.login(new LoginRequest("u@e.com", "wrong")));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void loginDisabledIsUnauthorizedBeforeVerificationCheck() {
        seedUser("u@e.com", "pw123456", false, false); // disabled + unverified → disabled (401) wins
        ApiException ex = expectApi(() -> service.login(new LoginRequest("u@e.com", "pw123456")));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("Account disabled", ex.getMessage());
    }

    // --- verify ---------------------------------------------------------------------------------

    @Test
    void verifyValidTokenFlipsUserAndDeletesToken() {
        User u = seedUser("u@e.com", "pw123456", true, false);
        String raw = seedToken(u, Instant.now().plus(Duration.ofHours(1)));

        assertEquals(EmailVerificationOutcome.SUCCESS, service.verifyEmail(raw));
        assertTrue(u.isEmailVerified());
        assertEquals(0, evTokens.size(), "token single-used (deleted)");
    }

    @Test
    void verifyExpiredTokenReturnsExpiredWithoutFlipping() {
        User u = seedUser("u@e.com", "pw123456", true, false);
        String raw = seedToken(u, Instant.now().minus(Duration.ofMinutes(1)));

        assertEquals(EmailVerificationOutcome.EXPIRED, service.verifyEmail(raw));
        assertTrue(!u.isEmailVerified());
        assertEquals(1, evTokens.size(), "expired token left for resend to replace");
    }

    @Test
    void verifyUnknownTokenReturnsInvalid() {
        assertEquals(EmailVerificationOutcome.INVALID, service.verifyEmail("no-such-token"));
        assertEquals(EmailVerificationOutcome.INVALID, service.verifyEmail(null));
    }

    @Test
    void verifySecondUseReturnsInvalid() {
        User u = seedUser("u@e.com", "pw123456", true, false);
        String raw = seedToken(u, Instant.now().plus(Duration.ofHours(1)));

        assertEquals(EmailVerificationOutcome.SUCCESS, service.verifyEmail(raw));
        assertEquals(EmailVerificationOutcome.INVALID, service.verifyEmail(raw));
    }

    // --- resend ---------------------------------------------------------------------------------

    @Test
    void resendWithinCooldownIssuesNothing() {
        User u = seedUser("u@e.com", "pw123456", true, false);
        seedToken(u, Instant.now().plus(Duration.ofHours(1))); // just created → inside 1m cooldown

        service.resendVerification("u@e.com");

        assertEquals(1, evTokens.size(), "no new token minted inside cooldown");
        assertEquals(0, events.size(), "no email published inside cooldown");
    }

    @Test
    void resendUnknownOrVerifiedEmailIssuesNothing() {
        service.resendVerification("nobody@e.com");
        seedUser("done@e.com", "pw123456", true, true);
        service.resendVerification("done@e.com");

        assertEquals(0, evTokens.size());
        assertEquals(0, events.size());
    }

    @Test
    void resendPastCooldownMintsFreshTokenAndPublishes() {
        User u = seedUser("u@e.com", "pw123456", true, false);
        EmailVerificationToken old = new EmailVerificationToken();
        old.setId(UUID.randomUUID());
        old.setUser(u);
        old.setTokenHash("old-hash");
        old.setExpiresAt(Instant.now().minus(Duration.ofHours(1)));
        old.setCreatedAt(Instant.now().minus(Duration.ofMinutes(2))); // older than 1m cooldown
        evTokens.add(old);

        service.resendVerification("u@e.com");

        assertEquals(1, evTokens.size(), "old token replaced (delete-then-insert)");
        assertEquals(1, events.size());
        RegistrationEmailEvent ev = (RegistrationEmailEvent) events.get(0);
        assertEquals(jwtService.hashToken(ev.rawToken()), evTokens.get(0).getTokenHash());
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private User seedUser(String email, String rawPassword, boolean enabled, boolean verified) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstName("First");
        u.setLastName("Last");
        u.setEmail(email.toLowerCase());
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setRole(dev.abu.screener_backend.user.UserRole.USER);
        u.setEnabled(enabled);
        u.setEmailVerified(verified);
        u.setCreatedAt(Instant.now());
        usersById.put(u.getId(), u);
        usersByEmail.put(u.getEmail(), u);
        return u;
    }

    private String seedToken(User user, Instant expiresAt) {
        String raw = jwtService.generateRawRefreshToken();
        EmailVerificationToken t = new EmailVerificationToken();
        t.setId(UUID.randomUUID());
        t.setUser(user);
        t.setTokenHash(jwtService.hashToken(raw));
        t.setExpiresAt(expiresAt);
        t.setCreatedAt(Instant.now());
        evTokens.add(t);
        return raw;
    }

    private static ApiException expectApi(Runnable r) {
        try {
            r.run();
        } catch (ApiException ex) {
            return ex;
        }
        throw new AssertionError("expected ApiException");
    }

    // --- reflective proxy repositories ------------------------------------------------------------

    private UserRepository userRepo() {
        return (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "existsByEmail" -> usersByEmail.containsKey(((String) args[0]).toLowerCase());
                    case "save" -> {
                        User u = (User) args[0];
                        if (u.getId() == null) { // simulate @PrePersist on insert
                            u.setId(UUID.randomUUID());
                            u.setCreatedAt(Instant.now());
                            if (u.getRole() == null) u.setRole(dev.abu.screener_backend.user.UserRole.USER);
                            u.setEnabled(true);
                            u.setEmailVerified(false);
                        }
                        usersById.put(u.getId(), u);
                        usersByEmail.put(u.getEmail().toLowerCase(), u);
                        yield u;
                    }
                    case "findByEmail" -> Optional.ofNullable(usersByEmail.get(((String) args[0]).toLowerCase()));
                    case "findById" -> Optional.ofNullable(usersById.get((UUID) args[0]));
                    default -> Optional.class.isAssignableFrom(method.getReturnType()) ? Optional.empty() : null;
                });
    }

    private RefreshTokenRepository refreshRepo() {
        return (RefreshTokenRepository) Proxy.newProxyInstance(
                RefreshTokenRepository.class.getClassLoader(),
                new Class<?>[]{RefreshTokenRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        refreshSaveCount++;
                        RefreshToken rt = (RefreshToken) args[0];
                        if (rt.getId() == null) rt.setId(UUID.randomUUID());
                        yield rt;
                    }
                    case "deleteByUserId" -> null;
                    default -> Optional.class.isAssignableFrom(method.getReturnType()) ? Optional.empty() : null;
                });
    }

    private EmailVerificationTokenRepository evTokenRepo() {
        return (EmailVerificationTokenRepository) Proxy.newProxyInstance(
                EmailVerificationTokenRepository.class.getClassLoader(),
                new Class<?>[]{EmailVerificationTokenRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        EmailVerificationToken t = (EmailVerificationToken) args[0];
                        if (t.getId() == null) t.setId(UUID.randomUUID());
                        if (t.getCreatedAt() == null) t.setCreatedAt(Instant.now());
                        evTokens.add(t);
                        yield t;
                    }
                    case "deleteByUserId" -> {
                        UUID uid = (UUID) args[0];
                        evTokens.removeIf(t -> t.getUser().getId().equals(uid));
                        yield null;
                    }
                    case "delete" -> {
                        evTokens.remove((EmailVerificationToken) args[0]);
                        yield null;
                    }
                    case "findByTokenHash" -> evTokens.stream()
                            .filter(t -> t.getTokenHash().equals((String) args[0])).findFirst();
                    case "findFirstByUser_IdOrderByCreatedAtDesc" -> {
                        UUID uid = (UUID) args[0];
                        yield evTokens.stream()
                                .filter(t -> t.getUser().getId().equals(uid))
                                .max(Comparator.comparing(EmailVerificationToken::getCreatedAt));
                    }
                    default -> Optional.class.isAssignableFrom(method.getReturnType()) ? Optional.empty() : null;
                });
    }
}
