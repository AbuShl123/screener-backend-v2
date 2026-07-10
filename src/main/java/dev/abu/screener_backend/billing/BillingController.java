package dev.abu.screener_backend.billing;

import dev.abu.screener_backend.billing.RegionResolver.Region;
import dev.abu.screener_backend.billing.dto.PayAsYouGoDaysResponse;
import dev.abu.screener_backend.billing.dto.PlanCatalogResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;


/**
 * Public subscription catalog. Mounted under {@code /api/billing}; covered by the
 * {@code .anyRequest().authenticated()} catch-all in {@code SecurityConfig}, so every call requires a
 * Bearer JWT.
 *
 * <p>The client never sends a price or currency — the server resolves the caller's billing currency
 * via {@link RegionResolver} (a UZ/UZS stub today) and returns the authoritative prices for it.
 */
@RestController
@RequestMapping("/api/billing-catalog")
public class BillingController {

    private final PricingService pricingService;
    private final RegionResolver regionResolver;

    public BillingController(PricingService pricingService,
                             RegionResolver regionResolver) {
        this.pricingService = pricingService;
        this.regionResolver = regionResolver;
    }

    @GetMapping("/plans")
    public PlanCatalogResponse plans(HttpServletRequest request) {
        Region region = regionResolver.resolve(request, null);
        return pricingService.catalogFor(region.currency());
    }

    /**
     * Truly public (no JWT — see the {@code permitAll} rule in {@code SecurityConfig}): lets a
     * not-yet-registered visitor estimate how many days their money buys under the single pay-as-you-go
     * plan before signing up. Only {@code currency} + {@code amount} are accepted; the plan is implicit.
     */
    @GetMapping("/pay-as-you-go/days")
    public PayAsYouGoDaysResponse payAsYouGoDays(@RequestParam String currency, @RequestParam BigDecimal amount) {
        return new PayAsYouGoDaysResponse(pricingService.payAsYouGoDays(currency, amount));
    }
}
