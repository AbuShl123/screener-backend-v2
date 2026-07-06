package dev.abu.screener_backend.billing;

import dev.abu.screener_backend.billing.dto.AdminPlanRequest;
import dev.abu.screener_backend.billing.dto.AdminPlanResponse;
import dev.abu.screener_backend.billing.dto.AdminPriceRequest;
import dev.abu.screener_backend.billing.dto.AdminPriceResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * ADMIN-only catalog management. Mounted under {@code /api/admin/billing}; the {@code /api/admin/**}
 * matcher in {@code SecurityConfig} restricts the whole tree to the {@code ADMIN} role (mirroring
 * {@code /api/monitoring/**}).
 *
 * <p>Returns full admin views (id, active, all currencies, both plan types) — distinct from the
 * public {@code GET /api/billing/plans}.
 */
@RestController
@RequestMapping("/api/admin/billing")
public class PlanAdminController {

    private final PlanAdminService planAdminService;

    public PlanAdminController(PlanAdminService planAdminService) {
        this.planAdminService = planAdminService;
    }

    @GetMapping("/plans")
    public List<AdminPlanResponse> listPlans() {
        return planAdminService.listPlans();
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminPlanResponse createPlan(@RequestBody AdminPlanRequest req) {
        return planAdminService.createPlan(req);
    }

    @PutMapping("/plans/{id}")
    public AdminPlanResponse updatePlan(@PathVariable UUID id, @RequestBody AdminPlanRequest req) {
        return planAdminService.updatePlan(id, req);
    }

    @DeleteMapping("/plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlan(@PathVariable UUID id) {
        planAdminService.deletePlan(id);
    }

    @PutMapping("/plans/{id}/prices")
    public AdminPriceResponse upsertPrice(@PathVariable UUID id, @RequestBody AdminPriceRequest req) {
        return planAdminService.upsertPrice(id, req);
    }

    @DeleteMapping("/prices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePrice(@PathVariable UUID id) {
        planAdminService.deletePrice(id);
    }
}
