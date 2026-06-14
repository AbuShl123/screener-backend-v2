package dev.abu.screener_backend.billing.dto;

import dev.abu.screener_backend.billing.PlanType;

import java.util.List;
import java.util.UUID;

/**
 * Full admin view of a plan with all its price rows (every currency, active + inactive) — distinct
 * from the public {@code PlanDto}, which carries only active, currency-resolved data.
 */
public record AdminPlanResponse(
        UUID id,
        String code,
        String displayName,
        PlanType type,
        Integer durationDays,
        boolean active,
        List<AdminPriceResponse> prices
) {}
