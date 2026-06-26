package dev.abu.screener_backend.payment.dto;

import java.math.BigDecimal;

/**
 * Body of {@code POST /api/billing/orders}. The client sends only a plan {@code code} (and, for
 * pay-by-days, an {@code amount} of money in major units) — never a price or currency, which are
 * resolved server-side.
 */
public record CreateOrderRequest(String planCode, BigDecimal amount) {}
