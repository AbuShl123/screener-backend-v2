package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Email configuration bound from {@code screener.email.*}.
 *
 * <ul>
 *   <li>{@code fromAddress} / {@code fromName} — the {@code From:} header of outbound mail.</li>
 *   <li>{@code verificationTokenExpiry} — TTL of a verification token (default 24h).</li>
 *   <li>{@code resendCooldown} — minimum gap between resend requests for one account (default 1m).</li>
 *   <li>{@code verifyPageUrl} — the SPA email-verification page. The email link points here carrying
 *       {@code ?token=<raw>}; the page shows a Confirm button that POSTs the token to
 *       {@code /api/auth/verify-email}. Loading the page consumes nothing, so passive link scanners
 *       (Outlook Safe Links, AV prefetch) can't burn the single-use token before the human clicks.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "screener.email")
public record EmailProperties(
        String fromAddress,
        String fromName,
        Duration verificationTokenExpiry,
        Duration resendCooldown,
        String verifyPageUrl
) {
    public EmailProperties {
        if (fromAddress == null || fromAddress.isBlank()) fromAddress = "noreply@tc-screener.com";
        if (fromName == null || fromName.isBlank()) fromName = "TC Screener";
        if (verificationTokenExpiry == null) verificationTokenExpiry = Duration.ofHours(24);
        if (resendCooldown == null) resendCooldown = Duration.ofMinutes(1);
    }
}
