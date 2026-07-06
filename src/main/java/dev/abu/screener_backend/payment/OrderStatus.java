package dev.abu.screener_backend.payment;

/**
 * Provider-neutral order lifecycle:
 * <pre>
 *   CREATED → PENDING → PAID
 *                 ├→ EXPIRED   (invoice TTL elapsed, no payment)
 *                 ├→ FAILED    (provider reported an error)
 *                 └→ CANCELED  (superseded before payment)
 *   PENDING/PAID → REVERTED    (refund detected — recorded only, access not revoked)
 * </pre>
 * {@code CREATED} and {@code PENDING} are the two "open" states (at most one open order per user).
 */
public enum OrderStatus {
    CREATED,
    PENDING,
    PAID,
    EXPIRED,
    FAILED,
    CANCELED,
    REVERTED
}
