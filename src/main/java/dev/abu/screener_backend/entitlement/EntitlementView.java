package dev.abu.screener_backend.entitlement;

import java.time.Instant;

/**
 * The current access state for a user, as derived by {@link EntitlementService}.
 *
 * <p>For {@code TRIAL}/{@code ACTIVE}, {@code accessExpiresAt} is the trial-end / subscription-end
 * instant (same field; only the UI label differs). For {@code ADMIN} it is {@code null}. For
 * {@code EXPIRED} it is the past expiry (or {@code null} if never granted).
 */
public record EntitlementView(AccessState state, Instant accessExpiresAt) {}
