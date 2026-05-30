package dev.abu.screener_backend.analysis.rule;

import dev.abu.screener_backend.binance.websocket.Market;
import dev.abu.screener_backend.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One persisted classification tier for a single {@code (user, symbol, market)} key.
 *
 * <p>A logical rule for one {@code (user, symbol, market)} spans 1–4 of these rows — one per
 * tier. The row-per-tier shape keeps the tier range extensible without a schema change.
 * Phase A only persists these rows; nothing on the Disruptor hot path reads them yet
 * (see {@code per-user-classification-phase-a.md}).
 */
@Entity
@Table(name = "classification_rules")
@Getter
@Setter
@NoArgsConstructor
public class ClassificationRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * {@code FetchType.LAZY} (unlike {@link dev.abu.screener_backend.user.RefreshToken}, which is
     * EAGER) — rules are always loaded by {@code user_id}; the full {@link User} graph is never
     * needed when reading them.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Market market;

    @Column(name = "tier_no", nullable = false)
    private int tierNo;

    @Column(name = "min_notional", nullable = false)
    private double minNotional;

    @Column(name = "max_distance", nullable = false)
    private double maxDistance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    private void prePersist() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    private void preUpdate() {
        updatedAt = Instant.now();
    }
}
