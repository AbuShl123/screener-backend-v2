package dev.abu.screener_backend.billing.dto;

/**
 * Admin upsert payload for one {@code (plan, currency)} price row. {@code amount} is in MAJOR units
 * (sum); must be {@code >= 0} and within the currency's allowed decimal places. {@code currency} is an
 * ISO 4217 code (e.g. {@code "UZS"}).
 *
 * <p>{@code amount} is a <strong>string</strong>, parsed to {@code BigDecimal} server-side so a JSON
 * number can't lose precision on the way in.
 */
public record AdminPriceRequest(
        String currency,
        String amount,
        Boolean active
) {}
