package dev.abu.screener_backend.payment.multicard;

import dev.abu.screener_backend.payment.OrderReason;

/**
 * The decision the callback service hands back to the controller, which maps it to HTTP:
 * <ul>
 *   <li>{@code OK} → {@code 200 {"success": true}}</li>
 *   <li>{@code REJECT(reason)} → {@code 200 {"success": false, "message": reason.description}} (Multicard refunds)</li>
 *   <li>{@code REJECT_BAD_SIGN} / {@code REJECT_BAD_SOURCE} → {@code 400} (unprocessed; likely forged)</li>
 *   <li>{@code RETRY} → {@code 500} (Multicard freezes funds + retries)</li>
 * </ul>
 */
public record CallbackOutcome(Kind kind, OrderReason reason) {

    public enum Kind { OK, REJECT, REJECT_BAD_SIGN, REJECT_BAD_SOURCE, RETRY }

    public static CallbackOutcome ok() {
        return new CallbackOutcome(Kind.OK, null);
    }

    public static CallbackOutcome reject(OrderReason reason) {
        return new CallbackOutcome(Kind.REJECT, reason);
    }

    public static CallbackOutcome badSign() {
        return new CallbackOutcome(Kind.REJECT_BAD_SIGN, null);
    }

    public static CallbackOutcome badSource() {
        return new CallbackOutcome(Kind.REJECT_BAD_SOURCE, null);
    }

    public static CallbackOutcome retry() {
        return new CallbackOutcome(Kind.RETRY, null);
    }
}
