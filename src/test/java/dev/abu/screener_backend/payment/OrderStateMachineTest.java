package dev.abu.screener_backend.payment;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OrderStateMachine}: the benign {@code from == to} no-op (E2) and the
 * late-success rescue legality (E6) — {@code EXPIRED/FAILED/CANCELED → PAID} legal, {@code REVERTED →
 * PAID} permanently illegal. Repositories are hand-rolled reflective proxies (the codebase avoids Mockito).
 */
class OrderStateMachineTest {

    private final List<OrderStatusHistory> history = new ArrayList<>();
    private final OrderStateMachine stateMachine = new OrderStateMachine(orderRepo(), historyRepo());

    @Test
    void sameStateIsNoOpWithoutHistoryRow() {
        Order order = order(OrderStatus.PENDING);

        stateMachine.transition(order, OrderStatus.PENDING, OrderSource.SYSTEM, OrderReason.SUPERSEDED, null);

        assertEquals(OrderStatus.PENDING, order.getStatus());
        assertTrue(history.isEmpty(), "a from == to transition must not write a duplicate history row");
    }

    @Test
    void lateSuccessRescueTransitionsAreLegal() {
        assertDoesNotThrow(() -> stateMachine.transition(
                order(OrderStatus.EXPIRED), OrderStatus.PAID, OrderSource.CALLBACK, OrderReason.CALLBACK_GRANT, null));
        assertDoesNotThrow(() -> stateMachine.transition(
                order(OrderStatus.FAILED), OrderStatus.PAID, OrderSource.CALLBACK, OrderReason.CALLBACK_GRANT, null));
        assertDoesNotThrow(() -> stateMachine.transition(
                order(OrderStatus.CANCELED), OrderStatus.PAID, OrderSource.CALLBACK, OrderReason.CALLBACK_GRANT, null));
    }

    @Test
    void revertedToPaidIsIllegal() {
        assertThrows(IllegalStateException.class, () -> stateMachine.transition(
                order(OrderStatus.REVERTED), OrderStatus.PAID, OrderSource.CALLBACK, OrderReason.CALLBACK_GRANT, null));
    }

    private static Order order(OrderStatus status) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(status);
        return order;
    }

    @SuppressWarnings("unchecked")
    private OrderRepository orderRepo() {
        return (OrderRepository) Proxy.newProxyInstance(
                OrderRepository.class.getClassLoader(), new Class<?>[]{OrderRepository.class},
                (proxy, method, args) -> method.getName().equals("save") ? args[0] : null);
    }

    @SuppressWarnings("unchecked")
    private OrderStatusHistoryRepository historyRepo() {
        return (OrderStatusHistoryRepository) Proxy.newProxyInstance(
                OrderStatusHistoryRepository.class.getClassLoader(), new Class<?>[]{OrderStatusHistoryRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("save")) {
                        history.add((OrderStatusHistory) args[0]);
                        return args[0];
                    }
                    return null;
                });
    }
}
