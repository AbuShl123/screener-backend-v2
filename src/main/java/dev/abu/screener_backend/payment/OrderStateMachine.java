package dev.abu.screener_backend.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralizes the legal order transitions and writes one {@code order_status_history} row on every
 * change. Its {@link #transition} signature forces an {@link OrderReason} (+ optional detail) so no
 * caller forgets the audit trail or invents an ad-hoc reason string.
 *
 * <p>This is a pure helper: it mutates the passed (managed) {@link Order} and persists within the
 * <em>caller's</em> transaction. The caller (e.g. {@code OrderService}) owns the transactional
 * boundary.
 */
@Component
@Slf4j
public class OrderStateMachine {

    /** Legal {@code from → to} transitions. Anything else is a programming error. */
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(OrderStatus.CREATED, EnumSet.of(OrderStatus.PENDING, OrderStatus.CANCELED, OrderStatus.EXPIRED));
        ALLOWED.put(OrderStatus.PENDING, EnumSet.of(OrderStatus.PAID, OrderStatus.EXPIRED, OrderStatus.FAILED,
                OrderStatus.CANCELED, OrderStatus.REVERTED));
        ALLOWED.put(OrderStatus.PAID, EnumSet.of(OrderStatus.REVERTED));
        // Late-success rescue: an authoritative success may resurrect a terminal order to PAID. The
        // transition is structurally legal here; whether it is *applied* (callback-only) is gated in
        // OrderService.markPaidAndGrant. REVERTED stays permanently terminal — refunded money never grants.
        ALLOWED.put(OrderStatus.EXPIRED, EnumSet.of(OrderStatus.PAID));
        ALLOWED.put(OrderStatus.FAILED, EnumSet.of(OrderStatus.PAID));
        ALLOWED.put(OrderStatus.CANCELED, EnumSet.of(OrderStatus.PAID));
        ALLOWED.put(OrderStatus.REVERTED, EnumSet.noneOf(OrderStatus.class));
    }

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;

    public OrderStateMachine(OrderRepository orderRepository, OrderStatusHistoryRepository historyRepository) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
    }

    /**
     * Moves {@code order} to {@code to}, persisting the change and an append-only history row. The
     * transition must be legal; an illegal one is a bug, so it throws.
     *
     * @param reason canonical reason code for this transition (required)
     * @param detail free-form detail (e.g. a provider error message); {@code null} when self-explanatory
     */
    public void transition(Order order, OrderStatus to, OrderSource source, OrderReason reason, String detail) {
        OrderStatus from = order.getStatus();
        if (from == to) {
            // Benign no-op: a locked re-check raced and the order is already in the target state. Return
            // without writing a duplicate history row. Illegal transitions still throw below.
            return;
        }
        if (from != null && !ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException("Illegal order transition " + from + " → " + to + " (order " + order.getId() + ")");
        }
        order.setStatus(to);
        orderRepository.save(order);
        historyRepository.save(new OrderStatusHistory(order.getId(), from, to, reason, detail, source));
        log.debug("Order {} transitioned {} → {} [{}/{}]", order.getId(), from, to, source, reason);
    }
}
