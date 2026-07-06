package dev.abu.screener_backend.payment;

/**
 * Provider-neutral payment status, mapped from each adapter's own status vocabulary at the boundary.
 *
 * <ul>
 *   <li>{@code SUCCESS} — paid; grant access.</li>
 *   <li>{@code ERROR} — the provider reported the payment failed.</li>
 *   <li>{@code REVERT} — reversed/refunded (recorded only; access not revoked).</li>
 *   <li>{@code CANCELED} — the invoice was cancelled/closed unpaid (TTL elapsed or deleted before payment).</li>
 *   <li>{@code PENDING} — still in progress / not yet resolved.</li>
 *   <li>{@code NOT_FOUND} — the provider has no such transaction.</li>
 * </ul>
 */
public enum ProviderStatus {
    SUCCESS,
    ERROR,
    REVERT,
    CANCELED,
    PENDING,
    NOT_FOUND
}
