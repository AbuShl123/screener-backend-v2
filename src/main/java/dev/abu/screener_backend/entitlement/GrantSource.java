package dev.abu.screener_backend.entitlement;

/**
 * What kind of grant moved a user's {@code accessExpiresAt} forward, recorded on every
 * {@link EntitlementLedger} row.
 *
 * <ul>
 *   <li>{@code TRIAL} — the free week seeded on registration.</li>
 *   <li>{@code PURCHASE} — a paid order (fixed plan or pay-by-days).</li>
 *   <li>{@code ADMIN} — a future admin grant/gift (no call site yet).</li>
 * </ul>
 */
public enum GrantSource {
    TRIAL,
    PURCHASE,
    ADMIN
}
