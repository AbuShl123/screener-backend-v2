package dev.abu.screener_backend.payment;

/**
 * The durable view of a payment fetched from the provider during reconciliation.
 *
 * @param status      provider-neutral status
 * @param ps          payment service (uzcard/humo/payme/…), may be {@code null}
 * @param amountTiyin the amount the provider reports, in minor units; may be {@code null} if unknown
 * @param error       raw provider error text for the audit trail, may be {@code null}
 */
public record ProviderPayment(ProviderStatus status, String ps, Long amountTiyin, String error) {}
