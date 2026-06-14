package dev.abu.screener_backend.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {

    /**
     * Active plans for the public catalog. The frontend re-orders by {@code code} for display, so
     * the ordering here is only for stable, deterministic output.
     */
    List<Plan> findByActiveTrueOrderByCode();

    /** All plans (active + inactive) for the admin catalog view. */
    List<Plan> findAllByOrderByCode();

    /** Code-uniqueness check for plan creation (the DB UNIQUE constraint is the backstop). */
    boolean existsByCode(String code);
}
