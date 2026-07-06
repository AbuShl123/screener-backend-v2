package dev.abu.screener_backend.billing;

import dev.abu.screener_backend.billing.dto.AdminPlanRequest;
import dev.abu.screener_backend.billing.dto.AdminPlanResponse;
import dev.abu.screener_backend.billing.dto.AdminPriceRequest;
import dev.abu.screener_backend.billing.dto.AdminPriceResponse;
import dev.abu.screener_backend.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADMIN-only catalog management over {@code plans} and {@code plan_prices}. All validation runs
 * before any DB write — the whole request is rejected atomically with {@code 400} on the first
 * failure (matching {@code ClassificationRuleService}); a missing plan/price is {@code 404}.
 *
 * <p><strong>Delete is a soft-disable</strong> ({@code active = false}), never a hard delete: plans
 * and prices may be referenced by historical orders. Both deletes are idempotent.
 *
 * <p>Returns <strong>full admin views</strong> (id, active, both {@code FIXED}/{@code PER_DAY} plans,
 * and every currency) — distinct from the public {@code GET /api/billing/plans}, which returns only
 * active plans priced in the caller's resolved currency.
 */
@Service
@Transactional
@Slf4j
public class PlanAdminService {

    private final PlanRepository planRepository;
    private final PlanPriceRepository planPriceRepository;

    public PlanAdminService(PlanRepository planRepository, PlanPriceRepository planPriceRepository) {
        this.planRepository = planRepository;
        this.planPriceRepository = planPriceRepository;
    }

    // ---------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AdminPlanResponse> listPlans() {
        List<Plan> plans = planRepository.findAllByOrderByCode();
        if (plans.isEmpty()) {
            return List.of();
        }
        List<UUID> planIds = plans.stream().map(Plan::getId).toList();

        // Group every price row (all currencies, active + inactive) by its plan id.
        Map<UUID, List<AdminPriceResponse>> pricesByPlan = new LinkedHashMap<>();
        for (PlanPrice price : planPriceRepository.findByPlan_IdIn(planIds)) {
            pricesByPlan.computeIfAbsent(price.getPlan().getId(), k -> new ArrayList<>())
                    .add(toPriceResponse(price));
        }

        List<AdminPlanResponse> result = new ArrayList<>(plans.size());
        for (Plan plan : plans) {
            result.add(toPlanResponse(plan, pricesByPlan.getOrDefault(plan.getId(), List.of())));
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------
    // Plan writes
    // ---------------------------------------------------------------------------------------

    public AdminPlanResponse createPlan(AdminPlanRequest req) {
        if (req == null) {
            throw ApiException.badRequest("request body required");
        }
        String code = requireText(req.code(), "code");
        String displayName = requireText(req.displayName(), "displayName");
        PlanType type = req.type();
        if (type == null) {
            throw ApiException.badRequest("type must be FIXED or PER_DAY");
        }
        validateDurationInvariant(type, req.durationDays());
        if (planRepository.existsByCode(code)) {
            throw ApiException.conflict("plan code already exists: " + code);
        }

        Plan plan = new Plan();
        plan.setCode(code);
        plan.setDisplayName(displayName);
        plan.setType(type);
        plan.setDurationDays(req.durationDays());
        plan.setActive(req.active() == null || req.active());
        planRepository.save(plan);
        return toPlanResponse(plan, List.of());
    }

    public AdminPlanResponse updatePlan(UUID id, AdminPlanRequest req) {
        if (req == null) {
            throw ApiException.badRequest("request body required");
        }
        Plan plan = planRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("plan not found: " + id));

        String displayName = requireText(req.displayName(), "displayName");
        // type and code are immutable; the duration invariant is checked against the existing type.
        validateDurationInvariant(plan.getType(), req.durationDays());

        plan.setDisplayName(displayName);
        plan.setDurationDays(req.durationDays());
        if (req.active() != null) {
            plan.setActive(req.active());
        }
        planRepository.save(plan);
        return toPlanResponse(plan, loadPriceResponses(plan.getId()));
    }

    public void deletePlan(UUID id) {
        Plan plan = planRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("plan not found: " + id));
        if (plan.isActive()) {
            plan.setActive(false);
            planRepository.save(plan);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Price writes
    // ---------------------------------------------------------------------------------------

    public AdminPriceResponse upsertPrice(UUID planId, AdminPriceRequest req) {
        if (req == null) {
            throw ApiException.badRequest("request body required");
        }
        Currency cur = Currency.of(req.currency());
        BigDecimal amount = parseAmount(req.amount());
        if (amount.signum() < 0) {
            throw ApiException.badRequest("amount must be >= 0");
        }
        cur.requireScale(amount);
        String currency = cur.name();
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> ApiException.notFound("plan not found: " + planId));

        PlanPrice price = planPriceRepository.findByPlan_IdAndCurrency(planId, currency)
                .orElseGet(() -> {
                    PlanPrice fresh = new PlanPrice();
                    fresh.setPlan(plan);
                    fresh.setCurrency(currency);
                    return fresh;
                });
        price.setAmount(amount);
        price.setActive(req.active() == null || req.active());
        planPriceRepository.save(price);
        return toPriceResponse(price);
    }

    public void deletePrice(UUID id) {
        PlanPrice price = planPriceRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("price not found: " + id));
        if (price.isActive()) {
            price.setActive(false);
            planPriceRepository.save(price);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Validation & mapping helpers
    // ---------------------------------------------------------------------------------------

    private static void validateDurationInvariant(PlanType type, Integer durationDays) {
        if (type == PlanType.FIXED && durationDays == null) {
            throw ApiException.badRequest("FIXED plan requires durationDays");
        }
        if (type == PlanType.PER_DAY && durationDays != null) {
            throw ApiException.badRequest("PER_DAY plan must not have durationDays");
        }
        if (durationDays != null && durationDays <= 0) {
            throw ApiException.badRequest("durationDays must be > 0");
        }
    }

    /** Parses an admin-supplied money string losslessly; a missing/malformed value is a {@code 400}. */
    private static BigDecimal parseAmount(String amount) {
        if (amount == null || amount.isBlank()) {
            throw ApiException.badRequest("amount required");
        }
        try {
            return new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("amount is not a valid number: " + amount);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " required");
        }
        return value.trim();
    }

    private List<AdminPriceResponse> loadPriceResponses(UUID planId) {
        return planPriceRepository.findByPlan_IdIn(List.of(planId)).stream()
                .map(PlanAdminService::toPriceResponse)
                .toList();
    }

    private static AdminPlanResponse toPlanResponse(Plan plan, List<AdminPriceResponse> prices) {
        return new AdminPlanResponse(
                plan.getId(), plan.getCode(), plan.getDisplayName(), plan.getType(),
                plan.getDurationDays(), plan.isActive(), prices);
    }

    private static AdminPriceResponse toPriceResponse(PlanPrice price) {
        BigDecimal amount = Currency.of(price.getCurrency()).forDisplay(price.getAmount());
        return new AdminPriceResponse(price.getId(), price.getCurrency(), amount, price.isActive());
    }
}
