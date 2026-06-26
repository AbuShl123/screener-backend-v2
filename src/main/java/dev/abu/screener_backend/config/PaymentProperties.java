package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * Payment configuration bound from {@code screener.payment.*}. Secrets (application id / secret /
 * store id) come from environment variables, mirroring {@code jwt}/{@code admin}.
 *
 * <ul>
 *   <li>{@code reconciliationInterval} — how often the stale-{@code PENDING} sweep runs (default 1m).</li>
 *   <li>{@code multicard} — the Multicard adapter's settings (see {@link MulticardProperties}).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "screener.payment")
public record PaymentProperties(
        Duration reconciliationInterval,
        @NestedConfigurationProperty MulticardProperties multicard
) {
    public PaymentProperties {
        if (reconciliationInterval == null) reconciliationInterval = Duration.ofMinutes(1);
        if (multicard == null) multicard = new MulticardProperties(null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Multicard adapter settings. {@code baseUrl} points at the sandbox by default; production swaps
     * it to {@code https://mesh.multicard.uz}. {@code ofdEnabled} stays {@code false} until a real
     * merchant agreement and tax data exist (sandbox accepts invoice creation without {@code ofd}).
     */
    public record MulticardProperties(
            String baseUrl,
            String applicationId,
            String secret,
            String storeId,
            String callbackUrl,
            String returnUrl,
            Duration invoiceTtl,
            String allowedIp,
            String lang,
            Boolean ofdEnabled
    ) {
        public MulticardProperties {
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://dev-mesh.multicard.uz";
            if (invoiceTtl == null) invoiceTtl = Duration.ofMinutes(30);
            if (allowedIp == null || allowedIp.isBlank()) allowedIp = "195.158.26.90";
            if (lang == null || lang.isBlank()) lang = "ru";
            if (ofdEnabled == null) ofdEnabled = false;
        }
    }
}
