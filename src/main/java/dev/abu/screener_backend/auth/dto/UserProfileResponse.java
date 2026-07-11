package dev.abu.screener_backend.auth.dto;

import dev.abu.screener_backend.entitlement.AccessState;

import java.time.Instant;
import java.util.UUID;

/**
 * Profile + entitlement returned by {@code GET /api/auth/me}, so the SPA gets identity and access
 * state in one call. {@code accessState}/{@code accessExpiresAt} are derived on read by
 * {@code EntitlementService} ({@code ADMIN} reports {@code accessExpiresAt = null}).
 */
public record UserProfileResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String role,
        AccessState accessState,
        Instant accessExpiresAt,
        Instant registeredAt
) {}
