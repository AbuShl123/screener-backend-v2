package dev.abu.screener_backend.payment.dto;

import dev.abu.screener_backend.payment.OrderStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of an order for UI polling. {@code reason}/{@code reasonDetail} are read from the latest
 * {@code order_status_history} row (the {@code orders} table carries no reason column).
 */
public record OrderStatusResponse(
        UUID orderId,
        OrderStatus status,
        String reason,
        String reasonDetail,
        Instant expiresAt,
        Instant paidAt
) {}
