package dev.abu.screener_backend.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit of one order state transition. {@code reason} stores an {@link OrderReason} code
 * (canonical, self-documenting); {@code reasonDetail} carries free-form text (e.g. a raw provider
 * error message), {@code null} for self-explanatory reasons.
 */
@Entity
@Table(name = "order_status_history")
@Getter
@Setter
@NoArgsConstructor
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 16)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 16)
    private OrderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private OrderReason reason;

    @Column(name = "reason_detail", columnDefinition = "text")
    private String reasonDetail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Monotonic, DB-generated sequence (identity column) — the deterministic ordering key for "latest
     * transition" lookups (E7). Read-only here; assigned by the database on insert, never set by the app.
     */
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    public OrderStatusHistory(UUID orderId, OrderStatus fromStatus, OrderStatus toStatus,
                              OrderReason reason, String reasonDetail, OrderSource source) {
        this.orderId = orderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.reasonDetail = reasonDetail;
        this.source = source;
    }

    @PrePersist
    private void prePersist() {
        createdAt = Instant.now();
    }
}
