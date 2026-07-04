package dev.abu.screener_backend.auth;

import java.util.UUID;

/**
 * Published inside {@code AuthService}'s registration/resend transaction and consumed
 * {@code AFTER_COMMIT} + {@code @Async} by the email listener, so the token row is guaranteed
 * committed before the SMTP send and the request thread never blocks on mail I/O.
 *
 * <p>The <em>raw</em> token is carried in-memory only (never persisted — the DB holds only its
 * SHA-256 hash), exactly as the raw refresh token is handed back in {@code AuthResponse}. All fields
 * are plain values, so the listener needs no DB re-read and touches no detached entity.
 */
public record RegistrationEmailEvent(UUID userId, String rawToken, String email, String firstName) {}
