package dev.abu.screener_backend.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The price of one {@link Plan} in one currency.
 *
 * <p><strong>Money is stored in MAJOR units</strong> (e.g. UZS sum), not minor units (tiyin). For a
 * {@link PlanType#FIXED} plan {@code amount} is the full-period price; for a {@link PlanType#PER_DAY}
 * plan it is the price of a single day. Minor-unit conversion (tiyin, x100) happens only at the
 * provider boundary in the payment plan — the billing core never deals in minor units.
 *
 * <p>This is a deliberate, documented exception to the CLAUDE.md "no {@code BigDecimal}" rule, which
 * applies to the market-data hot path only. Billing is low-frequency and correctness-critical.
 *
 * <p>Localization is additive: this table is already keyed by currency, so adding KZT/RUB/crypto
 * rows later needs no schema change.
 */
@Entity
@Table(name = "plan_prices")
@Getter
@Setter
@NoArgsConstructor
public class PlanPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    /** ISO 4217, e.g. {@code "UZS"}. */
    @Column(nullable = false, length = 3)
    private String currency;

    /** Major units (sum). Never minor units. NUMERIC(38,18) holds fiat and crypto precision. */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal amount;

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
