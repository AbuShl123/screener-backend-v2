package dev.abu.screener_backend.billing.dto;

import java.math.BigDecimal;

/**
 * Admin upsert payload for one {@code (plan, currency)} price row. {@code amount} is in MAJOR units
 * (sum); must be {@code >= 0}. {@code currency} is an ISO 4217 code (e.g. {@code "UZS"}).
 */
public record AdminPriceRequest(
        String currency,
        BigDecimal amount,
        Boolean active
) {}
