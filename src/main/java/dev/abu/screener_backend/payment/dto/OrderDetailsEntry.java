package dev.abu.screener_backend.payment.dto;

import dev.abu.screener_backend.payment.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The single read view of an order — returned both on creation ({@code POST /api/billing/orders}) and
 * on status polling ({@code GET /api/billing/orders/current}, {@code /{id}}, the history list, and the
 * entitlement ledger's embedded order). One DTO because a freshly created order and a polled order carry
 * the same facts; the create flow simply returns this view with a usable {@code checkoutUrl}.
 *
 * <p>{@code reason}/{@code reasonDetail} are read from the latest {@code order_status_history} row (the
 * {@code orders} table carries no reason column). {@code checkoutUrl} lets the SPA redirect to / recover
 * the hosted-payment link (a still-{@code PENDING} order keeps a usable one); {@code providerUuid} (the
 * Multicard transaction uuid) is exposed for support/debugging. {@code receiptUrl} is the provider's
 * bank-receipt link, present only on a {@code PAID} order (and even then may be {@code null} — Multicard
 * marks it optional); the SPA renders it as a "view receipt" link.
 *
 * <p>The order snapshot fields confirm what was bought: {@code planCode} (the requested plan),
 * {@code amount} (a JSON number / {@code BigDecimal}, major units — the FIXED price or echoed
 * pay-by-days input), {@code accessDurationSeconds} (access this order buys), {@code currency} and
 * {@code provider} (server-resolved; today always {@code UZS} / {@code multicard}), and
 * {@code createdAt}.
 */
public record OrderDetailsEntry(
        UUID orderId,
        OrderStatus status,
        String planCode,
        BigDecimal amount,
        long accessDurationSeconds,
        String currency,
        String provider,
        String reason,
        String reasonDetail,
        String checkoutUrl,
        String providerUuid,
        String receiptUrl,
        Instant expiresAt,
        Instant paidAt,
        Instant createdAt
) {}
