package dev.abu.screener_backend.payment;

import dev.abu.screener_backend.billing.Plan;
import dev.abu.screener_backend.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One purchase attempt. Provider-neutral; the Multicard transaction uuid lives in {@link #providerUuid}
 * once the invoice is created (and is the idempotency key for grants).
 *
 * <p><strong>Snapshots</strong> {@link #grantedDurationSeconds} and {@link #amount} so later
 * re-pricing or plan edits never alter a past grant. Duration is stored in <em>seconds</em> (not days)
 * to match {@code entitlement_ledger} and the {@code Duration}-based entitlement model, and to stay
 * generic for future non-day grants.
 *
 * <p>{@code amount} is in <strong>major units</strong> (sum); tiyin conversion is the Multicard
 * adapter's concern. The audit trail (reasons) lives in {@code order_status_history}, never on this row.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    /** Snapshot of the granted access in seconds: FIXED = duration_days*86400; PER_DAY = ceil(amount/pricePerDay)*86400. */
    @Column(name = "granted_duration_seconds", nullable = false)
    private long grantedDurationSeconds;

    /** Snapshot of what the user pays, in major units (sum). NUMERIC(38,18) — fiat + crypto precision. */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "payment_provider", nullable = false, length = 32)
    private String paymentProvider = "multicard";

    /** Multicard transaction uuid; set after invoice creation. Unique (idempotency key). */
    @Column(name = "provider_uuid", length = 64)
    private String providerUuid;

    /** Payment service from the callback (uzcard/humo/payme/…). */
    @Column(length = 32)
    private String ps;

    @Column(name = "checkout_url", columnDefinition = "text")
    private String checkoutUrl;

    /** now + invoice ttl (30m). After this, an unpaid order is expired by the sweep. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic-lock guard (defense-in-depth). The live mutation paths serialize with
     * {@code findByIdForUpdate} (pessimistic), so this never contends; it exists so any future unlocked
     * racing write fails loudly with {@code OptimisticLockException} instead of silently lost-updating.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @PrePersist
    private void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        updatedAt = Instant.now();
    }

    /** True while the order is still open (CREATED or PENDING) — at most one such order per user. */
    public boolean isOpen() {
        return status == OrderStatus.CREATED || status == OrderStatus.PENDING;
    }
}
