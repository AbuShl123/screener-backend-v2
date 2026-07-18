package dev.abu.screener_backend.user.dto;

import dev.abu.screener_backend.entitlement.AccessState;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the ADMIN user listing ({@code GET /api/admin/users}). Carries account facts plus the
 * user's derived access state and {@code accessExpiresAt}, so an admin can see who to gift and pass the
 * {@code id} to {@code POST /api/admin/entitlement/gift}.
 *
 * @param accessState    derived {@code TRIAL/ACTIVE/EXPIRED/ADMIN} (never stored)
 * @param accessExpiresAt current access expiry, or {@code null} for admins / never-granted
 * @param hasPaid        whether the user has ever paid (distinguishes a paid {@code ACTIVE} from a gift/trial)
 * @param lastSeenAt     last successful, entitled WebSocket open, or {@code null} if never seen
 */
public record AdminUserView(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String role,
        boolean emailVerified,
        boolean enabled,
        Instant createdAt,
        AccessState accessState,
        Instant accessExpiresAt,
        boolean hasPaid,
        Instant lastSeenAt
) {}
