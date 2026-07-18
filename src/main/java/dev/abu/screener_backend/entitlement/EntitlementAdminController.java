package dev.abu.screener_backend.entitlement;

import dev.abu.screener_backend.auth.AuthenticatedUser;
import dev.abu.screener_backend.entitlement.dto.AdminGiftRequest;
import dev.abu.screener_backend.entitlement.dto.AdminGiftResponse;
import dev.abu.screener_backend.entitlement.dto.AdminGiftResult;
import dev.abu.screener_backend.error.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * ADMIN-only entitlement management. Mounted under {@code /api/admin/entitlement}; the
 * {@code /api/admin/**} matcher in {@code SecurityConfig} restricts the whole tree to the {@code ADMIN}
 * role (mirroring {@code /api/admin/billing} and {@code /api/monitoring}).
 */
@RestController
@RequestMapping("/api/admin/entitlement")
public class EntitlementAdminController {

    private final EntitlementService entitlementService;

    public EntitlementAdminController(EntitlementService entitlementService) {
        this.entitlementService = entitlementService;
    }

    /**
     * Bulk gift of free access — extends every listed user's access by the same {@code addPeriodDays},
     * stamping the acting admin on each user's ledger row. Validated all-or-nothing: an unknown user id
     * rejects the whole request ({@code 404}) and grants nobody.
     */
    @PostMapping("/gift")
    public AdminGiftResponse gift(@RequestBody AdminGiftRequest req, Authentication authentication) {
        if (req == null) {
            throw ApiException.badRequest("request body required");
        }
        AuthenticatedUser admin = (AuthenticatedUser) authentication.getPrincipal();
        // A null addPeriodDays maps to a zero duration, which giftAccess rejects with a clear message.
        Duration granted = Duration.ofDays(req.addPeriodDays() == null ? 0L : req.addPeriodDays());
        List<AdminGiftResult> results =
                entitlementService.giftAccess(req.userIds(), granted, admin.userId(), req.reason());
        return new AdminGiftResponse(results.size(), granted.toSeconds(), results);
    }
}
