package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Billing configuration bound from {@code screener.billing.*}.
 *
 * <ul>
 *   <li>{@code trialDuration} — length of the free trial granted on registration (default 7 days).</li>
 *   <li>{@code defaultCurrency} — currency the stub {@code RegionResolver} returns (default UZS).</li>
 *   <li>{@code defaultCountry} — country the stub {@code RegionResolver} returns (default UZ).</li>
 * </ul>
 *
 * Per-day pricing is NOT here — it lives in {@code plan_prices} as the {@code PER_DAY} plan's price.
 */
@ConfigurationProperties(prefix = "screener.billing")
public record BillingProperties(
        Duration trialDuration,
        String defaultCurrency,
        String defaultCountry
) {
    public BillingProperties {
        if (trialDuration == null) trialDuration = Duration.ofDays(7);
        if (defaultCurrency == null) defaultCurrency = "UZS";
        if (defaultCountry == null) defaultCountry = "UZ";
    }
}
