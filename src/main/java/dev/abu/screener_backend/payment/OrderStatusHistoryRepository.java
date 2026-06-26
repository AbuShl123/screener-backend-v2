package dev.abu.screener_backend.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {

    /** Full transition history for an order, newest first. Ordered by the monotonic {@code seq} (E7). */
    List<OrderStatusHistory> findByOrderIdOrderBySeqDesc(UUID orderId);

    /**
     * The most recent transition for an order — source of the {@code reason}/{@code reasonDetail} exposed
     * to the UI. Ordered by the monotonic {@code seq}, not {@code created_at}: two transitions can share a
     * {@code created_at} tick (app-clock {@code Instant.now()}), so {@code seq} is the deterministic tiebreak.
     */
    Optional<OrderStatusHistory> findFirstByOrderIdOrderBySeqDesc(UUID orderId);
}
