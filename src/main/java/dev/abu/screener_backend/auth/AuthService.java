package dev.abu.screener_backend.auth;

import dev.abu.screener_backend.auth.dto.AuthResponse;
import dev.abu.screener_backend.auth.dto.LoginRequest;
import dev.abu.screener_backend.auth.dto.RegisterRequest;
import dev.abu.screener_backend.config.JwtProperties;
import dev.abu.screener_backend.user.RefreshToken;
import dev.abu.screener_backend.user.RefreshTokenRepository;
import dev.abu.screener_backend.user.User;
import dev.abu.screener_backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    private final Duration refreshTokenExpiry;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenExpiry = jwtProperties.refreshTokenExpiry();
    }

    public AuthResponse register(RegisterRequest req) {
        if (req.firstName() == null || req.firstName().isBlank()
                || req.lastName() == null || req.lastName().isBlank()
                || req.email() == null || req.email().isBlank()
                || req.password() == null || req.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "All fields are required");
        }
        if (userRepository.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = new User();
        user.setFirstName(req.firstName());
        user.setLastName(req.lastName());
        user.setEmail(req.email().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        userRepository.save(user);
        return issueTokenPair(user);
    }

    public AuthResponse login(LoginRequest req) {
        if (req.email() == null || req.password() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and password required");
        }
        User user = userRepository.findByEmail(req.email().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account disabled");
        }
        return issueTokenPair(user);
    }

    public AuthResponse refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "refreshToken required");
        }
        String hash = jwtService.hashToken(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepository.delete(stored);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }
        return issueTokenPair(stored.getUser());
    }

    public void logout(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    @Transactional(readOnly = true)
    public User getUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
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
