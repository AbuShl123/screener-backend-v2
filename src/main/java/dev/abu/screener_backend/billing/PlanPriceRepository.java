package dev.abu.screener_backend.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanPriceRepository extends JpaRepository<PlanPrice, UUID> {

    /**
     * Active price rows for the given plans in a single currency — the public catalog lookup.
     * ({@code Plan_Id} navigates the LAZY {@code plan} association's id without loading the plan.)
     */
    List<PlanPrice> findByPlan_IdInAndCurrencyAndActiveTrue(Collection<UUID> planIds, String currency);

    /** All price rows (every currency, active + inactive) for the given plans — the admin view. */
    List<PlanPrice> findByPlan_IdIn(Collection<UUID> planIds);

    /** A single {@code (plan, currency)} price row, for the admin upsert. */
    Optional<PlanPrice> findByPlan_IdAndCurrency(UUID planId, String currency);
}
