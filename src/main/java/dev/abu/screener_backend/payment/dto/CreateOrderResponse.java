package dev.abu.screener_backend.payment.dto;

import dev.abu.screener_backend.payment.OrderStatus;

import java.util.UUID;

/**
 * Response of {@code POST /api/billing/orders}. The SPA performs the redirect to {@code checkoutUrl}
 * (the backend never issues a 302). {@code alreadyPaid} is {@code true} when a reuse/double-pay race
 * found the order already granted — in which case {@code checkoutUrl} may be {@code null}.
 */
public record CreateOrderResponse(UUID orderId, OrderStatus status, String checkoutUrl, boolean alreadyPaid) {}
