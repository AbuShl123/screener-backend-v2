package dev.abu.screener_backend.entitlement.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-user outcome of an admin gift: the gifted user and their new {@code accessExpiresAt} after the
 * grant stacked on top of any remaining time.
 */
public record AdminGiftResult(
        UUID userId,
        Instant newExpiresAt
) {}
