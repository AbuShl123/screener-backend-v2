package dev.abu.screener_backend.entitlement;

import dev.abu.screener_backend.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 1:1 with {@code users} ({@code user_id} is both PK and FK). Holds <strong>access facts only</strong>:
 * the single authoritative {@code accessExpiresAt} plus {@code hasPaid} (which distinguishes a TRIAL
 * from a paid ACTIVE subscription). Account/localization facts (currency, country, locale) live
 * elsewhere (a future {@code user_settings} table).
 *
 * <p>Access <em>state</em> ({@code TRIAL/ACTIVE/EXPIRED/ADMIN}) is derived on read by
 * {@link EntitlementService}, never stored.
 *
 * <p>Mapped as a shared-primary-key {@code @OneToOne} via {@code @MapsId}: the entity's id IS the
 * owning {@code User}'s id ({@code user_id} is simultaneously PK and FK). Setting {@link #user}
 * populates {@link #userId} automatically.
 *
 * <p>Invariant: every user has exactly one row (created in the registration transaction for new
 * users; existing users backfilled manually in production). Admin rows store
 * {@code access_expires_at = NULL, has_paid = FALSE} and are ignored by the gate via the
 * {@code role == ADMIN} short-circuit.
 */
@Entity
@Table(name = "user_entitlement")
@Getter
@Setter
@NoArgsConstructor
public class UserEntitlement {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    /** The single authoritative access field. {@code null} = never granted. */
    @Column(name = "access_expires_at")
    private Instant accessExpiresAt;

    @Column(name = "has_paid", nullable = false)
    private boolean hasPaid;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic-lock guard (future-proofing). Concurrent extends on one user are impossible today (the
     * one-open-order index ⇒ at most one payable order, trial seeding happens only at registration), but
     * a future admin/gift grant racing a purchase would surface loudly rather than lost-update.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @PrePersist
    @PreUpdate
    private void touch() {
        updatedAt = Instant.now();
    }
}
