package dev.abu.screener_backend.billing;

import dev.abu.screener_backend.billing.dto.PlanCatalogResponse;
import dev.abu.screener_backend.billing.dto.PlanDto;
import dev.abu.screener_backend.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the public plan catalog for a resolved currency. Loads active plans and their active price
 * rows in that currency; a plan with no active price in the requested currency is skipped (logged at
 * WARN) rather than advertised without a price.
 */
@Service
@Slf4j
public class PricingService {

    /** The single non-fixed (pay-by-days) plan's stable code. */
    private static final String PAY_AS_YOU_GO_CODE = "pay_as_you_go";

    private final PlanRepository planRepository;
    private final PlanPriceRepository planPriceRepository;

    public PricingService(PlanRepository planRepository, PlanPriceRepository planPriceRepository) {
        this.planRepository = planRepository;
        this.planPriceRepository = planPriceRepository;
    }

    @Transactional(readOnly = true)
    public PlanCatalogResponse catalogFor(String currency) {
        Currency cur = Currency.of(currency);
        List<Plan> plans = planRepository.findByActiveTrueOrderByCode();
        if (plans.isEmpty()) {
            return new PlanCatalogResponse(currency, List.of());
        }

        List<UUID> planIds = plans.stream().map(Plan::getId).toList();
        Map<UUID, BigDecimal> amountByPlanId = planPriceRepository
                .findByPlan_IdInAndCurrencyAndActiveTrue(planIds, currency).stream()
                .collect(Collectors.toMap(p -> p.getPlan().getId(), PlanPrice::getAmount));

        List<PlanDto> dtos = new ArrayList<>(plans.size());
        for (Plan plan : plans) {
            BigDecimal amount = amountByPlanId.get(plan.getId());
            if (amount == null) {
                log.warn("Plan '{}' has no active price in {}; omitting from catalog", plan.getCode(), currency);
                continue;
            }
            dtos.add(new PlanDto(plan.getCode(), plan.getDisplayName(), plan.getType(),
                    plan.getDurationDays(), cur.forDisplay(amount)));
        }
        return new PlanCatalogResponse(currency, dtos);
    }

    /**
     * How many days {@code amount} buys under the pay-as-you-go plan in {@code currency}, without
     * creating an order. Mirrors {@code OrderService}'s pay-by-days math via {@link PlanPrice#daysFor}.
     */
    @Transactional(readOnly = true)
    public long payAsYouGoDays(String currency, BigDecimal amount) {
        Currency cur = Currency.of(currency);
        Plan plan = planRepository.findByCode(PAY_AS_YOU_GO_CODE)
                .filter(Plan::isActive)
                .orElseThrow(() -> ApiException.badRequest("Pay-as-you-go plan is not available"));
        PlanPrice price = planPriceRepository.findByPlan_IdAndCurrency(plan.getId(), cur.name())
                .filter(PlanPrice::isActive)
                .orElseThrow(() -> ApiException.badRequest("No price for pay-as-you-go plan in " + cur.name()));
        return price.daysFor(amount);
    }
}
