package dev.abu.screener_backend.billing;

import dev.abu.screener_backend.billing.dto.PlanCatalogResponse;
import dev.abu.screener_backend.billing.dto.PlanDto;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PricingService}: the resolved currency is echoed on the response, amounts
 * survive as exact {@link BigDecimal}, and a plan with no active price in the requested currency is
 * omitted from the catalog. Repositories are reflective proxies (the codebase avoids Mockito)
 * dispatching the two queried methods by name.
 */
class PricingServiceTest {

    private static Plan plan(String code, PlanType type, Integer durationDays) {
        Plan p = new Plan();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setDisplayName(code);
        p.setType(type);
        p.setDurationDays(durationDays);
        p.setActive(true);
        return p;
    }

    private static PlanPrice price(Plan plan, String currency, BigDecimal amount) {
        PlanPrice pp = new PlanPrice();
        pp.setId(UUID.randomUUID());
        pp.setPlan(plan);
        pp.setCurrency(currency);
        pp.setAmount(amount);
        pp.setActive(true);
        return pp;
    }

    @SuppressWarnings("unchecked")
    private static PlanRepository planRepo(List<Plan> activePlans) {
        return (PlanRepository) Proxy.newProxyInstance(
                PlanRepository.class.getClassLoader(),
                new Class<?>[]{PlanRepository.class},
                (proxy, method, args) -> "findByActiveTrueOrderByCode".equals(method.getName())
                        ? activePlans
                        : defaultReturn(method.getReturnType()));
    }

    @SuppressWarnings("unchecked")
    private static PlanPriceRepository priceRepo(List<PlanPrice> prices) {
        return (PlanPriceRepository) Proxy.newProxyInstance(
                PlanPriceRepository.class.getClassLoader(),
                new Class<?>[]{PlanPriceRepository.class},
                (proxy, method, args) -> {
                    if ("findByPlan_IdInAndCurrencyAndActiveTrue".equals(method.getName())) {
                        Collection<UUID> ids = (Collection<UUID>) args[0];
                        String currency = (String) args[1];
                        return prices.stream()
                                .filter(p -> ids.contains(p.getPlan().getId()) && p.getCurrency().equals(currency))
                                .toList();
                    }
                    return defaultReturn(method.getReturnType());
                });
    }

    private static Object defaultReturn(Class<?> rt) {
        if (List.class.isAssignableFrom(rt)) return List.of();
        if (rt == boolean.class) return false;
        return null;
    }

    @Test
    void catalogEchoesCurrencyAndPreservesExactAmounts() {
        Plan weekly = plan("weekly", PlanType.FIXED, 7);
        BigDecimal amount = new BigDecimal("50000.0000");
        PricingService svc = new PricingService(
                planRepo(List.of(weekly)), priceRepo(List.of(price(weekly, "UZS", amount))));

        PlanCatalogResponse res = svc.catalogFor("UZS");

        assertEquals("UZS", res.currency());
        assertEquals(1, res.plans().size());
        PlanDto dto = res.plans().getFirst();
        assertEquals("weekly", dto.code());
        assertEquals(PlanType.FIXED, dto.type());
        assertEquals(7, dto.durationDays());
        assertEquals(0, amount.compareTo(dto.amount())); // exact BigDecimal, no float drift
    }

    @Test
    void plansWithoutPriceInRequestedCurrencyAreOmitted() {
        Plan weekly = plan("weekly", PlanType.FIXED, 7);
        Plan monthly = plan("monthly", PlanType.FIXED, 30);
        // Only weekly is priced in UZS; monthly is priced only in KZT.
        PricingService svc = new PricingService(
                planRepo(List.of(weekly, monthly)),
                priceRepo(List.of(
                        price(weekly, "UZS", new BigDecimal("50000.0000")),
                        price(monthly, "KZT", new BigDecimal("999.0000")))));

        PlanCatalogResponse res = svc.catalogFor("UZS");

        assertEquals(1, res.plans().size());
        assertEquals("weekly", res.plans().getFirst().code());
    }

    @Test
    void emptyCatalogWhenNoActivePlans() {
        PricingService svc = new PricingService(planRepo(List.of()), priceRepo(List.of()));

        PlanCatalogResponse res = svc.catalogFor("UZS");

        assertEquals("UZS", res.currency());
        assertTrue(res.plans().isEmpty());
    }
}
