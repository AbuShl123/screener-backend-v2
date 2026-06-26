package dev.abu.screener_backend.payment;

/**
 * The provider-agnostic payment boundary the billing core depends on. It stays minimal — two methods —
 * so a future Kaspi/crypto adapter implements the same shape plus its own callback controller.
 *
 * <p>The <strong>success callback is intentionally NOT on this interface</strong>: different providers
 * post different payloads/signatures, so each provider gets a dedicated callback controller + service
 * (see {@code MulticardCallbackController}).
 */
public interface PaymentProvider {

    /** Stable provider id, e.g. {@code "multicard"}. */
    String id();

    /** Creates a hosted-checkout invoice for the order; returns the provider uuid + checkout URL. */
    CheckoutSession createCheckout(Order order);

    /** Durable status fetch for reconciliation — must stay authoritative after expiry/cancel/refund. */
    ProviderPayment fetchPayment(String providerUuid);

    /**
     * Cancels an unpaid checkout when superseding an open order. Optional: a provider that cannot (or
     * need not) cancel leaves the default no-op. Must be best-effort — never block a supersede on it.
     */
    default void cancelCheckout(String providerUuid) {
        // no-op by default
    }
}
