package dev.abu.screener_backend.payment;

/**
 * The result of creating a hosted checkout: the provider's transaction id (persisted on the order as
 * the idempotency key) and the URL to redirect the user to.
 *
 * <p><strong>Known generalization point (not built now).</strong> {@code checkoutUrl} bakes in the
 * hosted-redirect model — fine for Multicard, but a future crypto adapter would return an address/QR
 * and a synchronous saved-card charge would return nothing to redirect to. When a second provider
 * lands, generalize this into a neutral {@code PaymentInitiation} (e.g. a sealed
 * {@code Redirect/Qr/Completed}) via an additive expand/contract migration. Today, with only
 * Multicard, the plain {@code (providerUuid, checkoutUrl)} stays as-is.
 */
public record CheckoutSession(String providerUuid, String checkoutUrl) {}
