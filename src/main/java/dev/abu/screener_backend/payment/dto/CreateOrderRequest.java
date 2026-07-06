package dev.abu.screener_backend.payment.dto;

/**
 * Body of {@code POST /api/billing/orders}. The client sends only a plan {@code code} (and, for
 * pay-by-days, an {@code amount} of money in major units) — never a price or currency, which are
 * resolved server-side.
 *
 * <p>{@code amount} is a <strong>string</strong>, parsed to {@code BigDecimal} server-side: a JSON
 * number can lose precision when a client serialises it from a {@code double}, which matters for
 * high-precision currencies. The controller parses and validates it.
 */
public record CreateOrderRequest(String planCode, String amount) {}
