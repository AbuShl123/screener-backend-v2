package dev.abu.screener_backend.payment.multicard;

import dev.abu.screener_backend.config.PaymentProperties;
import dev.abu.screener_backend.config.PaymentProperties.MulticardProperties;
import dev.abu.screener_backend.payment.*;
import dev.abu.screener_backend.payment.multicard.dto.MulticardCallbackPayload;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the success-callback decision logic: happy grant, idempotent replay, unknown order,
 * amount mismatch, bad source IP / bad signature, and a transient grant failure → RETRY. The order
 * lookup is a reflective-proxy repository; the grant is a hand-rolled {@link OrderService} subclass.
 */
class MulticardCallbackServiceTest {

    private static final String SECRET = "s3cr3t";
    private static final String ALLOWED_IP = "195.158.26.90";
    private static final String STORE_ID = "store-1";

    private final AtomicInteger grantCalls = new AtomicInteger();
    private final AtomicReference<OrderSource> grantSource = new AtomicReference<>();
    private boolean grantThrows = false;

    @Test
    void happyPathGrantsAndReturnsOk() {
        Order order = order(OrderStatus.PENDING, new BigDecimal("5000.00")); // 500000 tiyin
        CallbackOutcome outcome = service(order).handle(payload(order, 500_000L), ALLOWED_IP);
        assertEquals(CallbackOutcome.Kind.OK, outcome.kind());
        assertEquals(1, grantCalls.get());
        assertEquals(OrderSource.CALLBACK, grantSource.get());
    }

    @Test
    void alreadyPaidIsIdempotentNoGrant() {
        Order order = order(OrderStatus.PAID, new BigDecimal("5000.00"));
        CallbackOutcome outcome = service(order).handle(payload(order, 500_000L), ALLOWED_IP);
        assertEquals(CallbackOutcome.Kind.OK, outcome.kind());
        assertEquals(0, grantCalls.get());
    }

    @Test
    void unknownOrderRejected() {
        CallbackOutcome outcome = service(null).handle(payload(order(OrderStatus.PENDING, new BigDecimal("5000.00")), 500_000L), ALLOWED_IP);
        assertEquals(CallbackOutcome.Kind.REJECT, outcome.kind());
        assertEquals(OrderReason.UNKNOWN_ORDER, outcome.reason());
    }

    @Test
    void amountMismatchRejected() {
        Order order = order(OrderStatus.PENDING, new BigDecimal("5000.00")); // expects 500000
        CallbackOutcome outcome = service(order).handle(payload(order, 499_999L), ALLOWED_IP);
        assertEquals(CallbackOutcome.Kind.REJECT, outcome.kind());
        assertEquals(OrderReason.AMOUNT_MISMATCH, outcome.reason());
        assertEquals(0, grantCalls.get());
    }

    @Test
    void badSourceIpRejected() {
        Order order = order(OrderStatus.PENDING, new BigDecimal("5000.00"));
        CallbackOutcome outcome = service(order).handle(payload(order, 500_000L), "10.0.0.1");
        assertEquals(CallbackOutcome.Kind.REJECT_BAD_SOURCE, outcome.kind());
    }

    @Test
    void badSignatureRejected() {
        Order order = order(OrderStatus.PENDING, new BigDecimal("5000.00"));
        MulticardCallbackPayload tampered = new MulticardCallbackPayload(STORE_ID, 500_000L,
                order.getId().toString(), "b", "t", "p", "c", "uzcard", "tok", order.getProviderUuid(), "r", "deadbeef");
        CallbackOutcome outcome = service(order).handle(tampered, ALLOWED_IP);
        assertEquals(CallbackOutcome.Kind.REJECT_BAD_SIGN, outcome.kind());
    }

    @Test
    void transientGrantFailureSignalsRetry() {
        grantThrows = true;
        Order order = order(OrderStatus.PENDING, new BigDecimal("5000.00"));
        CallbackOutcome outcome = service(order).handle(payload(order, 500_000L), ALLOWED_IP);
        assertEquals(CallbackOutcome.Kind.RETRY, outcome.kind());
    }

    // ---- helpers ----

    private MulticardCallbackService service(Order order) {
        return new MulticardCallbackService(orderRepo(order), orderService(), props());
    }

    private MulticardCallbackPayload payload(Order order, long amountTiyin) {
        String sign = MulticardSignature.md5Hex(STORE_ID + order.getId() + amountTiyin + SECRET);
        return new MulticardCallbackPayload(STORE_ID, amountTiyin, order.getId().toString(), "bill",
                "2024-12-14 14:36:31", "998900000000", "860030******5959", "uzcard", "tok",
                order.getProviderUuid(), "https://r", sign);
    }

    private static Order order(OrderStatus status, BigDecimal amount) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(status);
        order.setAmount(amount);
        order.setProviderUuid("pu-" + UUID.randomUUID());
        return order;
    }

    @SuppressWarnings("unchecked")
    private OrderRepository orderRepo(Order order) {
        return (OrderRepository) Proxy.newProxyInstance(
                OrderRepository.class.getClassLoader(),
                new Class<?>[]{OrderRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findByProviderUuid")) {
                        return order != null && order.getProviderUuid().equals(args[0])
                                ? Optional.of(order) : Optional.empty();
                    }
                    return Optional.class.isAssignableFrom(method.getReturnType()) ? Optional.empty() : null;
                });
    }

    private OrderService orderService() {
        return new OrderService(null, null, null, null, null, null, null, props()) {
            @Override
            public void markPaidAndGrant(UUID orderId, String ps, OrderSource source) {
                if (grantThrows) {
                    throw new RuntimeException("transient db error");
                }
                grantCalls.incrementAndGet();
                grantSource.set(source);
            }
        };
    }

    private static PaymentProperties props() {
        MulticardProperties mc = new MulticardProperties(
                "https://dev-mesh.multicard.uz", "app", SECRET, STORE_ID,
                "https://cb", "https://ret", Duration.ofMinutes(30), ALLOWED_IP, "ru", false);
        return new PaymentProperties(Duration.ofMinutes(1), mc);
    }
}
