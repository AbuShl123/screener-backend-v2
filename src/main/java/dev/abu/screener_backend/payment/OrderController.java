package dev.abu.screener_backend.payment;

import dev.abu.screener_backend.auth.AuthenticatedUser;
import dev.abu.screener_backend.auth.AuthService;
import dev.abu.screener_backend.billing.RegionResolver;
import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.payment.dto.CreateOrderRequest;
import dev.abu.screener_backend.payment.dto.CreateOrderResponse;
import dev.abu.screener_backend.payment.dto.OrderStatusResponse;
import dev.abu.screener_backend.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Order endpoints under {@code /api/billing/orders} (Bearer JWT via the {@code authenticated()}
 * catch-all). The client sends only a plan {@code code} (+ an {@code amount} for pay-by-days); price
 * and currency are resolved server-side. {@code checkoutUrl} is returned as JSON — the SPA performs
 * the redirect; the backend never issues a 302.
 */
@RestController
@RequestMapping("/api/billing/orders")
public class OrderController {

    private final OrderService orderService;
    private final AuthService authService;
    private final RegionResolver regionResolver;

    public OrderController(OrderService orderService, AuthService authService, RegionResolver regionResolver) {
        this.orderService = orderService;
        this.authService = authService;
        this.regionResolver = regionResolver;
    }

    @PostMapping
    public CreateOrderResponse create(Authentication authentication,
                                      HttpServletRequest request,
                                      @RequestBody CreateOrderRequest body) {
        if (body == null || body.planCode() == null || body.planCode().isBlank()) {
            throw ApiException.badRequest("planCode is required");
        }
        User user = currentUser(authentication);
        String currency = regionResolver.resolve(request, user).currency();
        return orderService.createOrReuse(user, body.planCode().trim(), parseAmount(body.amount()), currency);
    }

    /**
     * Parses the optional pay-by-days {@code amount} string into a {@code BigDecimal} losslessly. Null
     * or blank (a FIXED plan carries no amount) returns {@code null}; a malformed value is a {@code 400}.
     * Per-currency scale and sign validation happens server-side in {@link OrderService}.
     */
    private static BigDecimal parseAmount(String amount) {
        if (amount == null || amount.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("amount is not a valid number: " + amount);
        }
    }

    @GetMapping
    public List<OrderStatusResponse> history(Authentication authentication) {
        return orderService.listOrders(principal(authentication).userId());
    }

    @GetMapping("/current")
    public OrderStatusResponse current(Authentication authentication) {
        return orderService.currentOrder(principal(authentication).userId());
    }

    @GetMapping("/{id}")
    public OrderStatusResponse one(Authentication authentication, @PathVariable UUID id) {
        return orderService.getOrder(principal(authentication).userId(), id);
    }

    private User currentUser(Authentication authentication) {
        return authService.getUser(principal(authentication).userId());
    }

    private static AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }
}
