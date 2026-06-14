package dev.abu.screener_backend.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A catalog plan — a named, pre-priced bundle of access days. Its duration is <em>data</em>, not
 * code: the business edits the catalog (add a quarterly plan, retire weekly via {@code active=false})
 * without a code change. Pay-by-days is just a {@link PlanType#PER_DAY} plan.
 *
 * <p>{@code code} is the single stable identifier the frontend keys all user-facing text, styling,
 * and display order off (via its own i18n bundles). {@code displayName} is only an admin/log label
 * and English fallback — not the user-facing string.
 *
 * <p>Plans are never hard-deleted once referenced by historical orders; they are soft-disabled with
 * {@code active = false}.
 */
@Entity
@Table(name = "plans")
@Getter
@Setter
@NoArgsConstructor
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String code;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanType type;

    /** 7/30/365 for {@link PlanType#FIXED}; {@code null} for {@link PlanType#PER_DAY}. */
    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
}
