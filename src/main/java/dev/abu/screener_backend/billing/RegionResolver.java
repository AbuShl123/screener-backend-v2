package dev.abu.screener_backend.billing;

import dev.abu.screener_backend.user.User;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the caller's region (country + billing currency) server-side. The client never sends a
 * price or currency — it sends only a plan {@code code} (or, for pay-by-days, an amount); the server
 * resolves the currency here and looks up the authoritative price.
 *
 * <p>This is a seam. Today {@link DefaultRegionResolver} returns the configured default for everyone
 * and persists nothing. The real precedence (CDN/IP header → verified phone country → user override)
 * and any persistence (a future {@code user_settings} record) are future work.
 */
public interface RegionResolver {

    Region resolve(HttpServletRequest request, User user);

    /** Country (ISO 3166-1 alpha-2) and its billing currency (ISO 4217). */
    record Region(String countryCode, String currency) {}
}
