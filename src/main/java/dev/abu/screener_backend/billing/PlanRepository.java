package dev.abu.screener_backend.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {

    /** Resolve a plan by its stable {@code code} (used by the order endpoint — the client sends only the code). */
    Optional<Plan> findByCode(String code);

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
