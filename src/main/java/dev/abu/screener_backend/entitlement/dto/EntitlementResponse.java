package dev.abu.screener_backend.entitlement.dto;

import dev.abu.screener_backend.entitlement.AccessState;

import java.time.Instant;

/**
 * The wire shape for the caller's current access state, for cheap UI polling.
 *
 * <ul>
 *   <li>{@code TRIAL}/{@code ACTIVE} → {@code accessExpiresAt} is the trial-end / subscription-end
 *       instant (same field; only the label differs).</li>
 *   <li>{@code EXPIRED} → {@code accessExpiresAt} is in the past (or {@code null} if never granted).</li>
 *   <li>{@code ADMIN} → {@code accessExpiresAt} is {@code null}.</li>
 * </ul>
 */
public record EntitlementResponse(AccessState state, Instant accessExpiresAt) {}
