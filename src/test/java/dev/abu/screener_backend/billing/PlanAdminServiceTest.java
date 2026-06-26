package dev.abu.screener_backend.billing;

import dev.abu.screener_backend.billing.dto.AdminPlanRequest;
import dev.abu.screener_backend.billing.dto.AdminPlanResponse;
import dev.abu.screener_backend.billing.dto.AdminPriceRequest;
import dev.abu.screener_backend.billing.dto.AdminPriceResponse;
import dev.abu.screener_backend.error.ApiException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PlanAdminService}: code uniqueness, the type/duration invariant, soft-delete
 * (sets {@code active=false}, never removes), and price upsert (create then update the same
 * {@code (plan, currency)} row). Repositories are stateful in-memory reflective proxies (the codebase
 * avoids Mockito); {@code save} assigns an id when absent, mirroring JPA generation.
 */
class PlanAdminServiceTest {

    private final Map<UUID, Plan> plans = new LinkedHashMap<>();
    private final Map<UUID, PlanPrice> prices = new LinkedHashMap<>();
    private final PlanAdminService service = new PlanAdminService(planRepo(plans), priceRepo(prices));

    @SuppressWarnings("unchecked")
    private static PlanRepository planRepo(Map<UUID, Plan> store) {
        return (PlanRepository) Proxy.newProxyInstance(
                PlanRepository.class.getClassLoader(),
                new Class<?>[]{PlanRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        Plan p = (Plan) args[0];
                        if (p.getId() == null) p.setId(UUID.randomUUID());
                        store.put(p.getId(), p);
                        yield p;
                    }
                    case "findById" -> Optional.ofNullable(store.get((UUID) args[0]));
                    case "existsByCode" -> store.values().stream().anyMatch(p -> p.getCode().equals(args[0]));
                    case "findAllByOrderByCode" -> store.values().stream()
                            .sorted(Comparator.comparing(Plan::getCode)).toList();
                    default -> defaultReturn(method.getReturnType());
                });
    }

    @SuppressWarnings("unchecked")
    private static PlanPriceRepository priceRepo(Map<UUID, PlanPrice> store) {
        return (PlanPriceRepository) Proxy.newProxyInstance(
                PlanPriceRepository.class.getClassLoader(),
                new Class<?>[]{PlanPriceRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        PlanPrice p = (PlanPrice) args[0];
                        if (p.getId() == null) p.setId(UUID.randomUUID());
                        store.put(p.getId(), p);
                        yield p;
                    }
                    case "findById" -> Optional.ofNullable(store.get((UUID) args[0]));
                    case "findByPlan_IdAndCurrency" -> store.values().stream()
                            .filter(p -> p.getPlan().getId().equals(args[0]) && p.getCurrency().equals(args[1]))
                            .findFirst();
                    case "findByPlan_IdIn" -> {
                        Collection<UUID> ids = (Collection<UUID>) args[0];
                        yield store.values().stream().filter(p -> ids.contains(p.getPlan().getId())).toList();
                    }
                    default -> defaultReturn(method.getReturnType());
                });
    }

    private static Object defaultReturn(Class<?> rt) {
        if (Optional.class.isAssignableFrom(rt)) return Optional.empty();
        if (List.class.isAssignableFrom(rt)) return List.of();
        if (rt == boolean.class) return false;
        return null;
    }

    // ---------------------------------------------------------------------------------------

    @Test
    void createPlanRejectsDuplicateCode() {
        service.createPlan(new AdminPlanRequest("weekly", "Weekly", PlanType.FIXED, 7, true));

        ApiException ex = assertThrows(ApiException.class, () ->
                service.createPlan(new AdminPlanRequest("weekly", "Weekly 2", PlanType.FIXED, 14, true)));
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    void createPlanEnforcesTypeDurationInvariant() {
        // FIXED requires a duration.
        assertThrows(ApiException.class, () ->
                service.createPlan(new AdminPlanRequest("weekly", "Weekly", PlanType.FIXED, null, true)));
        // PER_DAY must not have a duration.
        assertThrows(ApiException.class, () ->
                service.createPlan(new AdminPlanRequest("payg", "Pay by days", PlanType.PER_DAY, 30, true)));
    }

    @Test
    void createPerDayPlanSucceedsWithNullDuration() {
        AdminPlanResponse res =
                service.createPlan(new AdminPlanRequest("payg", "Pay by days", PlanType.PER_DAY, null, null));

        assertEquals("payg", res.code());
        assertEquals(PlanType.PER_DAY, res.type());
        assertTrue(res.active()); // null active defaults to true
    }

    @Test
    void deletePlanSoftDisablesInsteadOfRemoving() {
        AdminPlanResponse created =
                service.createPlan(new AdminPlanRequest("weekly", "Weekly", PlanType.FIXED, 7, true));

        service.deletePlan(created.id());

        // Still present, just inactive (soft-disable).
        Plan stored = plans.get(created.id());
        assertFalse(stored.isActive());
        // Idempotent: deleting again does not blow up.
        service.deletePlan(created.id());
        assertFalse(plans.get(created.id()).isActive());
    }

    @Test
    void deletePlanNotFound() {
        ApiException ex = assertThrows(ApiException.class, () -> service.deletePlan(UUID.randomUUID()));
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    void upsertPriceCreatesThenUpdatesSamePlanCurrencyRow() {
        AdminPlanResponse plan =
                service.createPlan(new AdminPlanRequest("weekly", "Weekly", PlanType.FIXED, 7, true));

        AdminPriceResponse first =
                service.upsertPrice(plan.id(), new AdminPriceRequest("UZS", "50000.00", true));
        assertEquals(0, new BigDecimal("50000.00").compareTo(first.amount()));
        assertEquals(1, prices.size());

        AdminPriceResponse second =
                service.upsertPrice(plan.id(), new AdminPriceRequest("uzs", "60000.00", true));
        // Same (plan, currency) row updated in place — not a new row, currency normalized to upper.
        assertEquals(1, prices.size());
        assertEquals(first.id(), second.id());
        assertEquals("UZS", second.currency());
        assertEquals(0, new BigDecimal("60000.00").compareTo(second.amount()));
    }

    @Test
    void upsertPriceRejectsNegativeAmountAndBadCurrency() {
        AdminPlanResponse plan =
                service.createPlan(new AdminPlanRequest("weekly", "Weekly", PlanType.FIXED, 7, true));

        assertThrows(ApiException.class, () ->
                service.upsertPrice(plan.id(), new AdminPriceRequest("UZS", "-1", true)));
        assertThrows(ApiException.class, () ->
                service.upsertPrice(plan.id(), new AdminPriceRequest("US", "1", true)));
        // Unsupported (but well-formed) currency is rejected — the system can't know its decimals (E10).
        assertThrows(ApiException.class, () ->
                service.upsertPrice(plan.id(), new AdminPriceRequest("XYZ", "1", true)));
    }

    @Test
    void upsertPriceRejectsAmountWithTooManyDecimalsForCurrency() {
        AdminPlanResponse plan =
                service.createPlan(new AdminPlanRequest("weekly", "Weekly", PlanType.FIXED, 7, true));

        // UZS allows 2 dp; 3 significant decimals is a 400 (E10). Trailing zeros are fine.
        assertThrows(ApiException.class, () ->
                service.upsertPrice(plan.id(), new AdminPriceRequest("UZS", "19.999", true)));
        AdminPriceResponse ok =
                service.upsertPrice(plan.id(), new AdminPriceRequest("UZS", "19.900", true));
        assertEquals(0, new BigDecimal("19.9").compareTo(ok.amount()));
    }

    @Test
    void upsertPriceNotFoundWhenPlanAbsent() {
        ApiException ex = assertThrows(ApiException.class, () ->
                service.upsertPrice(UUID.randomUUID(), new AdminPriceRequest("UZS", "1", true)));
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    void updatePlanMutatesDisplayNameDurationAndActiveButNotType() {
        AdminPlanResponse created =
                service.createPlan(new AdminPlanRequest("weekly", "Weekly", PlanType.FIXED, 7, true));

        AdminPlanResponse updated = service.updatePlan(created.id(),
                new AdminPlanRequest("ignored-code", "Weekly Plus", PlanType.PER_DAY, 14, false));

        assertEquals("weekly", updated.code());       // code immutable
        assertEquals(PlanType.FIXED, updated.type()); // type immutable (invariant checked vs FIXED)
        assertEquals("Weekly Plus", updated.displayName());
        assertEquals(14, updated.durationDays());
        assertFalse(updated.active());
    }
}
