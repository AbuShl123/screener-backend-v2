package dev.abu.screener_backend.entitlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EntitlementLedgerRepository extends JpaRepository<EntitlementLedger, UUID> {

    /** A user's grant history, newest first — for future admin/audit views. */
    List<EntitlementLedger> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
