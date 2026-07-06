package dev.abu.screener_backend.entitlement;

/**
 * Derived, presentation-only access state for the frontend. Never stored — computed on read from
 * {@code accessExpiresAt}, {@code hasPaid}, and the user's role.
 *
 * <ul>
 *   <li>{@code TRIAL}   — granted access (the free week) but never paid.</li>
 *   <li>{@code ACTIVE}  — paid access currently valid.</li>
 *   <li>{@code EXPIRED} — no valid access; the user must purchase.</li>
 *   <li>{@code ADMIN}   — admin bypass; reported with {@code accessExpiresAt = null}.</li>
 * </ul>
 */
public enum AccessState {
    TRIAL,
    ACTIVE,
    EXPIRED,
    ADMIN
}
