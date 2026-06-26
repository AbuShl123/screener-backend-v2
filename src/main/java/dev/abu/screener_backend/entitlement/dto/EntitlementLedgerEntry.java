package dev.abu.screener_backend.entitlement.dto;

import dev.abu.screener_backend.entitlement.GrantSource;
import dev.abu.screener_backend.payment.dto.OrderDetailsEntry;

import java.time.Instant;

/**
 * One access-granting event from {@code entitlement_ledger}, exposed via
 * {@code GET /api/billing/entitlement/history} (newest first). Each row is a single push of the user's
 * {@code accessExpiresAt} forward — a trial seed, a paid purchase, or a future admin grant.
 *
 * <p>{@code source} is what kind of grant it was ({@code TRIAL}/{@code PURCHASE}/{@code ADMIN});
 * {@code grantedDurationSeconds} is how much access it added; {@code previousExpiresAt}/
 * {@code newExpiresAt} bracket the stacking move. {@code order} is the full detail view of the order
 * that paid for a {@code PURCHASE} grant, or {@code null} for trial/admin grants (no order). The ledger
 * stores only an order id (the domains stay decoupled at the persistence layer), so it is resolved to an
 * {@link OrderDetailsEntry} at read time.
 */
public record EntitlementLedgerEntry(
        GrantSource source,
        long grantedDurationSeconds,
        Instant previousExpiresAt,
        Instant newExpiresAt,
        OrderDetailsEntry order,
        String reason,
        Instant createdAt
) {}
