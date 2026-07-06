package dev.abu.screener_backend.billing.dto;

import dev.abu.screener_backend.billing.PlanType;

import java.math.BigDecimal;

/**
 * One plan in the public catalog. {@code amount} is in MAJOR units (sum) — displayed directly, no
 * division. No per-plan currency: the currency is declared once on {@link PlanCatalogResponse}.
 * {@code displayName} is a fallback; the frontend renders the localized name/description from its
 * own i18n keyed by {@code code}.
 */
public record PlanDto(
        String code,
        String displayName,
        PlanType type,
        Integer durationDays,
        BigDecimal amount
) {}
