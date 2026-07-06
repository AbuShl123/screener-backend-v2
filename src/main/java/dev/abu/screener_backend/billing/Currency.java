package dev.abu.screener_backend.billing;

import dev.abu.screener_backend.error.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * The supported billing currencies and their decimal places — the single source of truth for how many
 * fractional digits a currency may carry. Fiat follows ISO 4217 minor units (UZS / USD = 2); crypto
 * follows its native precision (BTC = 8, ETH = 18).
 *
 * <p>Money is stored canonically as a major-unit {@link BigDecimal} (see {@link PlanPrice}); this enum
 * is what input validation and the provider-boundary minor-unit conversion read so the scale lives in
 * exactly one place rather than being hardcoded at each call site. It is an enum for now because a
 * currency binds to a provider adapter (code); it can graduate to a {@code currencies} reference table
 * later, additively.
 */
public enum Currency {
    UZS(2),
    USD(2),
    BTC(8),
    ETH(18);

    private final int decimals;

    Currency(int decimals) {
        this.decimals = decimals;
    }

    /** Maximum number of fractional digits this currency may carry. */
    public int decimals() {
        return decimals;
    }

    /**
     * Resolves a currency code (case-insensitive), rejecting a badly-formatted or unsupported code with
     * a {@code 400}. Use this wherever a client- or admin-supplied currency enters the billing core.
     */
    public static Currency of(String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("currency required");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 3 || !normalized.chars().allMatch(Character::isLetter)) {
            throw ApiException.badRequest("currency must be a 3-letter ISO 4217 code: " + code);
        }
        try {
            return Currency.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unsupported currency: " + normalized);
        }
    }

    /**
     * Rejects an amount that carries more decimal places than this currency allows (e.g. {@code 480.888}
     * for UZS, {@code 19.999} for USD) with a {@code 400}. Trailing zeros are tolerated — {@code 19.900}
     * is valid for a 2-dp currency because its significant scale is 1. Keeping input scale within the
     * currency's decimals also guarantees the provider-boundary {@code movePointRight(decimals)} stays
     * exact.
     */
    public void requireScale(BigDecimal amount) {
        if (amount != null && amount.stripTrailingZeros().scale() > decimals) {
            throw ApiException.badRequest(
                    "amount has more than " + decimals + " decimal place(s) allowed for " + name());
        }
    }

    /**
     * Rescales a stored amount to exactly this currency's decimal places for display, dropping the
     * padding zeros that {@code NUMERIC(38,18)} storage carries (e.g. {@code 150000.000000000000000000}
     * → {@code 150000.00} for UZS). Because input scale is validated {@code <=} decimals on the way in
     * (see {@link #requireScale}), every digit being trimmed is a zero, so {@link RoundingMode#UNNECESSARY}
     * never throws — it asserts that invariant rather than silently rounding real precision away.
     */
    public BigDecimal forDisplay(BigDecimal amount) {
        return amount == null ? null : amount.setScale(decimals, RoundingMode.UNNECESSARY);
    }
}
