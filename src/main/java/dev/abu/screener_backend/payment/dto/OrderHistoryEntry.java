package dev.abu.screener_backend.payment.dto;

import dev.abu.screener_backend.payment.OrderReason;
import dev.abu.screener_backend.payment.OrderSource;
import dev.abu.screener_backend.payment.OrderStatus;

import java.time.Instant;

/**
 * One transition from {@code order_status_history}, exposed for audit/debugging via
 * {@code GET /api/billing/orders/{id}/history}. {@code seq} is the DB-generated monotonic ordering key;
 * entries are returned newest-first by {@code seq}. {@code reason}/{@code reasonDetail} mirror the order
 * status DTO (a canonical {@link OrderReason} code + free-form text, e.g. a raw provider error).
 */
public record OrderHistoryEntry(
        OrderStatus fromStatus,
        OrderStatus toStatus,
        String reason,
        String reasonDetail,
        OrderSource source,
        Instant createdAt,
        long seq
) {}
