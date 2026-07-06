package dev.abu.screener_backend.entitlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserEntitlementRepository extends JpaRepository<UserEntitlement, UUID> {

    /** {@code user_id} is the PK, so this is equivalent to {@code findById} — named for intent. */
    Optional<UserEntitlement> findByUserId(UUID userId);
}
