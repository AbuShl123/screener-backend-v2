package dev.abu.screener_backend.billing.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Full admin view of one price row (includes {@code id} and {@code active}, unlike the public catalog). */
public record AdminPriceResponse(
        UUID id,
        String currency,
        BigDecimal amount,
        boolean active
) {}
