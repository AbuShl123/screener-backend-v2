package dev.abu.screener_backend.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** Open-order lookup ({@code status IN {CREATED, PENDING}}) — at most one row given the partial index. */
    Optional<Order> findFirstByUser_IdAndStatusIn(UUID userId, Collection<OrderStatus> statuses);

    /** Callback / reconciliation lookup by the Multicard transaction uuid. */
    Optional<Order> findByProviderUuid(String providerUuid);

    /** Reconciliation sweep scan over open-but-pending orders. */
    List<Order> findByStatus(OrderStatus status);

    /** Capped order-history list for the user, newest first. */
    List<Order> findByUser_IdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Pessimistic-write reload for the idempotent grant: callback and sweep both funnel through
     * {@code markPaidAndGrant}, so the {@code PENDING → PAID} transition must be serialized per order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);
}
