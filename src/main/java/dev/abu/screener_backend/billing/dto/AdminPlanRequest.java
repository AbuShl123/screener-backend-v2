package dev.abu.screener_backend.billing.dto;

import dev.abu.screener_backend.billing.PlanType;

/**
 * Admin create/update payload for a {@link dev.abu.screener_backend.billing.Plan}.
 *
 * <p>On create, all fields apply. On update, {@code code} is ignored — it is the immutable stable
 * identifier the frontend keys text/order off. {@code durationDays} must be non-null for
 * {@code FIXED} and null for {@code PER_DAY} (validated server-side, same as the DB CHECK).
 */
public record AdminPlanRequest(
        String code,
        String displayName,
        PlanType type,
        Integer durationDays,
        Boolean active
) {}
