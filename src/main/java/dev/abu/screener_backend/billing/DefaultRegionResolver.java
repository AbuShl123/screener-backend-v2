package dev.abu.screener_backend.billing;

import dev.abu.screener_backend.config.BillingProperties;
import dev.abu.screener_backend.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Stub {@link RegionResolver}: returns the configured default region (UZ / UZS) for every caller,
 * resolved at request time and persisted nowhere. Real geo/phone resolution and persistence land in
 * the localization/account-settings plan.
 */
@Component
public class DefaultRegionResolver implements RegionResolver {

    private final Region defaultRegion;

    public DefaultRegionResolver(BillingProperties props) {
        this.defaultRegion = new Region(props.defaultCountry(), props.defaultCurrency());
    }

    @Override
    public Region resolve(HttpServletRequest request, User user) {
        return defaultRegion;
    }
}
