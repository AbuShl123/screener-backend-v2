package dev.abu.screener_backend.entitlement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit of one access grant. Written on every {@link EntitlementService#extend} (and
 * {@link EntitlementService#startTrial}) call, so access changes — trial, purchase, future admin
 * gift — are reconstructable.
 *
 * <p>Real money moving entitlement must be auditable. {@code orderId}/{@code adminId} are stored as
 * plain UUIDs (not JPA
 * associations) to keep the entitlement domain decoupled from the payment domain.
 */
@Entity
@Table(name = "entitlement_ledger")
@Getter
@Setter
@NoArgsConstructor
public class EntitlementLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GrantSource source;

    @Column(name = "granted_duration_seconds", nullable = false)
    private long grantedDurationSeconds;

    @Column(name = "previous_expires_at")
    private Instant previousExpiresAt;

    @Column(name = "new_expires_at", nullable = false)
    private Instant newExpiresAt;

    /** Set for {@code PURCHASE} grants — the order that paid for the access. */
    @Column(name = "order_id")
    private UUID orderId;

    /** Set for future {@code ADMIN} grants. */
    @Column(name = "admin_id")
    private UUID adminId;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EntitlementLedger(UUID userId, GrantSource source, long grantedDurationSeconds,
                             Instant previousExpiresAt, Instant newExpiresAt,
                             UUID orderId, UUID adminId, String reason) {
        this.userId = userId;
        this.source = source;
        this.grantedDurationSeconds = grantedDurationSeconds;
        this.previousExpiresAt = previousExpiresAt;
        this.newExpiresAt = newExpiresAt;
        this.orderId = orderId;
        this.adminId = adminId;
        this.reason = reason;
    }

    @PrePersist
    private void prePersist() {
        createdAt = Instant.now();
    }
}
