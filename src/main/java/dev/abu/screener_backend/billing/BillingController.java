package dev.abu.screener_backend.billing;

import dev.abu.screener_backend.auth.AuthenticatedUser;
import dev.abu.screener_backend.auth.AuthService;
import dev.abu.screener_backend.billing.RegionResolver.Region;
import dev.abu.screener_backend.billing.dto.PlanCatalogResponse;
import dev.abu.screener_backend.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public subscription catalog. Mounted under {@code /api/billing}; covered by the
 * {@code .anyRequest().authenticated()} catch-all in {@code SecurityConfig}, so every call requires a
 * Bearer JWT.
 *
 * <p>The client never sends a price or currency — the server resolves the caller's billing currency
 * via {@link RegionResolver} (a UZ/UZS stub today) and returns the authoritative prices for it.
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final PricingService pricingService;
    private final RegionResolver regionResolver;
    private final AuthService authService;

    public BillingController(PricingService pricingService,
                             RegionResolver regionResolver,
                             AuthService authService) {
        this.pricingService = pricingService;
        this.regionResolver = regionResolver;
        this.authService = authService;
    }

    @GetMapping("/plans")
    public PlanCatalogResponse plans(Authentication authentication, HttpServletRequest request) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        User user = authService.getUser(principal.userId());
        Region region = regionResolver.resolve(request, user);
        return pricingService.catalogFor(region.currency());
    }
}
