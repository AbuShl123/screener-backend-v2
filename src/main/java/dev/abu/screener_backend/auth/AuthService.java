package dev.abu.screener_backend.auth;

import dev.abu.screener_backend.auth.dto.AuthResponse;
import dev.abu.screener_backend.auth.dto.LoginRequest;
import dev.abu.screener_backend.auth.dto.RegisterRequest;
import dev.abu.screener_backend.auth.dto.UserProfileResponse;
import dev.abu.screener_backend.config.JwtProperties;
import dev.abu.screener_backend.entitlement.EntitlementService;
import dev.abu.screener_backend.entitlement.EntitlementView;
import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.user.RefreshToken;
import dev.abu.screener_backend.user.RefreshTokenRepository;
import dev.abu.screener_backend.user.User;
import dev.abu.screener_backend.user.UserRepository;
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
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final EntitlementService entitlementService;
    private final Duration refreshTokenExpiry;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       EntitlementService entitlementService,
                       JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.entitlementService = entitlementService;
        this.refreshTokenExpiry = jwtProperties.refreshTokenExpiry();
    }

    public AuthResponse register(RegisterRequest req) {
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
        // 1:1 user_entitlement invariant always holds for new users.
        entitlementService.startTrial(user);
        return issueTokenPair(user);
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
        return issueTokenPair(user);
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
                view.state(), view.accessExpiresAt()
        );
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
