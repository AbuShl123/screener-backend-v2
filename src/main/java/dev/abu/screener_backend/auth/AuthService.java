package dev.abu.screener_backend.auth;

import dev.abu.screener_backend.auth.dto.AuthResponse;
import dev.abu.screener_backend.auth.dto.LoginRequest;
import dev.abu.screener_backend.auth.dto.RegisterRequest;
import dev.abu.screener_backend.auth.dto.RegisterResponse;
import dev.abu.screener_backend.auth.dto.UserProfileResponse;
import dev.abu.screener_backend.config.EmailProperties;
import dev.abu.screener_backend.config.JwtProperties;
import dev.abu.screener_backend.entitlement.EntitlementService;
import dev.abu.screener_backend.entitlement.EntitlementView;
import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.user.EmailVerificationToken;
import dev.abu.screener_backend.user.EmailVerificationTokenRepository;
import dev.abu.screener_backend.user.RefreshToken;
import dev.abu.screener_backend.user.RefreshTokenRepository;
import dev.abu.screener_backend.user.User;
import dev.abu.screener_backend.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final EntitlementService entitlementService;
    private final ApplicationEventPublisher eventPublisher;
    private final EmailProperties emailProperties;
    private final Duration refreshTokenExpiry;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       EmailVerificationTokenRepository emailVerificationTokenRepository,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       EntitlementService entitlementService,
                       ApplicationEventPublisher eventPublisher,
                       EmailProperties emailProperties,
                       JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.entitlementService = entitlementService;
        this.eventPublisher = eventPublisher;
        this.emailProperties = emailProperties;
        this.refreshTokenExpiry = jwtProperties.refreshTokenExpiry();
    }

    /**
     * Creates the unverified account and mails a verification link. Returns no token pair — the user
     * cannot log in until they verify (see {@link #login}). All DB writes share this transaction; the
     * email is sent AFTER_COMMIT + async so a rollback never leaks a live link.
     */
    public RegisterResponse register(RegisterRequest req) {
        if (req.firstName() == null || req.firstName().isBlank()
                || req.lastName() == null || req.lastName().isBlank()
                || req.email() == null || req.email().isBlank()
                || req.password() == null || req.password().isBlank()) {
            throw ApiException.badRequest("All fields are required");
        }
        if (userRepository.existsByEmail(req.email())) {
            throw ApiException.conflict("Email already registered");
        }
        User user = new User();
        user.setFirstName(req.firstName());
        user.setLastName(req.lastName());
        user.setEmail(req.email().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        userRepository.save(user);
        // Seed the free trial in the same transaction so every account starts in TRIAL and the
        // 1:1 user_entitlement invariant always holds. A few minutes of trial clock before the user
        // clicks the link is immaterial — an unverified user cannot log in to consume it.
        entitlementService.startTrial(user);
        // Mint the verification token and publish the send event (raw token in-memory only).
        issueVerificationToken(user);
        return new RegisterResponse("VERIFICATION_REQUIRED", user.getEmail());
    }

    public AuthResponse login(LoginRequest req) {
        if (req.email() == null || req.password() == null) {
            throw ApiException.badRequest("Email and password required");
        }
        User user = userRepository.findByEmail(req.email().toLowerCase())
                .orElseThrow(() -> ApiException.unauthorized("Invalid credentials"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid credentials");
        }
        if (!user.isEnabled()) {
            throw ApiException.unauthorized("Account disabled");
        }
        // Verification gate AFTER the password + enabled checks, so verification status is only
        // revealed to someone who already proved the password (no account enumeration). A distinct
        // 403 lets the SPA offer a "resend" affordance, separate from bad-credentials / banned 401s.
        if (!user.isEmailVerified()) {
            throw ApiException.forbidden("Email not verified");
        }
        return issueTokenPair(user);
    }

    /**
     * Verifies a raw token from the email link. Single-use: the row is deleted on success. Returns an
     * outcome the controller maps to the SPA landing redirect — never throws for the miss/expired
     * cases (a stale or double-clicked link is normal UX, not an error).
     */
    public EmailVerificationOutcome verifyEmail(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return EmailVerificationOutcome.INVALID;
        }
        EmailVerificationToken token =
                emailVerificationTokenRepository.findByTokenHash(jwtService.hashToken(rawToken)).orElse(null);
        if (token == null) {
            return EmailVerificationOutcome.INVALID;
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            // Leave the row; resend does delete-then-insert. The user recovers via resend.
            return EmailVerificationOutcome.EXPIRED;
        }
        token.getUser().setEmailVerified(true);
        emailVerificationTokenRepository.delete(token);
        return EmailVerificationOutcome.SUCCESS;
    }

    /**
     * Re-mints and re-sends a verification link. Always a no-op-safe void — the controller returns a
     * generic 202 regardless of outcome, so no account-enumeration or cooldown oracle leaks. Skips
     * unknown/already-verified emails and requests inside the resend cooldown window.
     */
    public void resendVerification(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        User user = userRepository.findByEmail(email.toLowerCase()).orElse(null);
        if (user == null || user.isEmailVerified()) {
            return;
        }
        Instant cooldownCutoff = Instant.now().minus(emailProperties.resendCooldown());
        boolean onCooldown = emailVerificationTokenRepository
                .findFirstByUser_IdOrderByCreatedAtDesc(user.getId())
                .map(t -> t.getCreatedAt().isAfter(cooldownCutoff))
                .orElse(false);
        if (onCooldown) {
            return;
        }
        // Regenerate unconditionally — an expired or already-deleted token is fine; delete-then-insert
        // always mints a fresh link. This is what makes expiry self-healing rather than a dead end.
        issueVerificationToken(user);
    }

    public AuthResponse refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw ApiException.badRequest("refreshToken required");
        }
        String hash = jwtService.hashToken(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepository.delete(stored);
            throw ApiException.unauthorized("Refresh token expired");
        }
        return issueTokenPair(stored.getUser());
    }

    public void logout(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    @Transactional(readOnly = true)
    public User getUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
    }

    /**
     * Profile + derived entitlement for {@code GET /api/auth/me}, so the SPA bootstraps identity and
     * access state in one call.
     */
    @Transactional(readOnly = true)
    public UserProfileResponse me(UUID userId) {
        User user = getUser(userId);
        EntitlementView view = entitlementService.currentState(user);
        return new UserProfileResponse(
                user.getId(), user.getFirstName(), user.getLastName(),
                user.getEmail(), user.getRole().name(),
                view.state(), view.accessExpiresAt(), user.getCreatedAt()
        );
    }

    /**
     * Mints one active verification token (delete-then-insert to keep one-per-user) and publishes the
     * send event. The raw token is carried only in-memory (in the event) — the DB stores its SHA-256
     * hash. Shared by register and resend.
     */
    private void issueVerificationToken(User user) {
        emailVerificationTokenRepository.deleteByUserId(user.getId());

        String rawToken = jwtService.generateRawRefreshToken();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(user);
        token.setTokenHash(jwtService.hashToken(rawToken));
        token.setExpiresAt(Instant.now().plus(emailProperties.verificationTokenExpiry()));
        emailVerificationTokenRepository.save(token);

        eventPublisher.publishEvent(
                new RegistrationEmailEvent(user.getId(), rawToken, user.getEmail(), user.getFirstName()));
    }

    private AuthResponse issueTokenPair(User user) {
        refreshTokenRepository.deleteByUserId(user.getId());

        String rawToken = jwtService.generateRawRefreshToken();
        RefreshToken rt = new RefreshToken();
        rt.setUser(user);
        rt.setTokenHash(jwtService.hashToken(rawToken));
        rt.setExpiresAt(Instant.now().plus(refreshTokenExpiry));
        refreshTokenRepository.save(rt);

        return new AuthResponse(
                jwtService.generateAccessToken(user),
                rawToken,
                jwtService.accessTokenExpirySeconds()
        );
    }
}
