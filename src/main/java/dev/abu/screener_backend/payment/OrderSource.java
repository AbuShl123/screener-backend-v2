package dev.abu.screener_backend.payment;

/**
 * Who/what drove an order state transition — recorded on every {@code order_status_history} row.
 *
 * <ul>
 *   <li>{@code API} — a user-facing order endpoint (create / reuse / supersede).</li>
 *   <li>{@code CALLBACK} — the Multicard success callback.</li>
 *   <li>{@code RECONCILIATION} — the scheduled stale-order sweep.</li>
 *   <li>{@code SYSTEM} — any other internal transition.</li>
 * </ul>
 */
public enum OrderSource {
    API,
    CALLBACK,
    RECONCILIATION,
    SYSTEM
}
