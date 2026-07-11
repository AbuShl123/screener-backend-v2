package dev.abu.screener_backend.payment;

/**
 * The canonical, centralized list of {@code reason} codes the backend writes into
 * {@code order_status_history.reason}. Each constant carries a human-readable {@link #getDescription()}
 * so the meaning of a reason lives in exactly one place (and the callback can surface it to the user).
 *
 * <p>Free-form provider text (e.g. a raw error message) goes into {@code order_status_history.reason_detail},
 * never into this enum. New reasons are added here, never as scattered string literals at call sites.
 */
public enum OrderReason {

    SUPERSEDED("Replaced by a new order for a different plan."),
    USER_CANCELED("Canceled by the user before payment."),
    INVOICE_EXPIRED("Invoice TTL elapsed; no payment received."),
    AMOUNT_MISMATCH("Payment amount did not match the order amount."),
    UNKNOWN_ORDER("Callback referenced a provider uuid with no matching order."),
    PROVIDER_ERROR("Provider reported the payment failed."),
    PROVIDER_REVERT("Provider reversed/refunded the payment; access not revoked."),
    CALLBACK_GRANT("Paid and granted via the success callback."),
    RECONCILED_GRANT("Paid and granted via reconciliation (lost callback recovered).");

    private final String description;

    OrderReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
