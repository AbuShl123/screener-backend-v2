package dev.abu.screener_backend.entitlement;

import dev.abu.screener_backend.auth.AuthenticatedUser;
import dev.abu.screener_backend.auth.AuthService;
import dev.abu.screener_backend.entitlement.dto.EntitlementLedgerEntry;
import dev.abu.screener_backend.entitlement.dto.EntitlementResponse;
import dev.abu.screener_backend.user.User;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes the caller's current access state for the frontend. Mounted under {@code /api/billing};
 * covered by the {@code .anyRequest().authenticated()} catch-all in {@code SecurityConfig}, so every
 * call requires a Bearer JWT.
 *
 * <p>The same two fields are mirrored on {@code GET /api/auth/me} for the one-call UI bootstrap; this
 * dedicated endpoint exists for cheap re-polling without re-fetching the whole profile.
 */
@RestController
@RequestMapping("/api/billing")
public class EntitlementController {

    private final EntitlementService entitlementService;
    private final AuthService authService;

    public EntitlementController(EntitlementService entitlementService, AuthService authService) {
        this.entitlementService = entitlementService;
        this.authService = authService;
    }

    @GetMapping("/entitlement")
    public EntitlementResponse entitlement(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        User user = authService.getUser(principal.userId());
        EntitlementView view = entitlementService.currentState(user);
        return new EntitlementResponse(view.state(), view.accessExpiresAt());
    }

    /**
     * The caller's access-granting events (entitlement ledger), newest first — trial seed, paid
     * purchases (each embedding the full order detail), and future admin grants.
     */
    @GetMapping("/entitlement/history")
    public List<EntitlementLedgerEntry> entitlementHistory(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        return entitlementService.listAccessHistory(principal.userId());
    }
}
