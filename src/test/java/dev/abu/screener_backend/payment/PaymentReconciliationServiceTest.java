package dev.abu.screener_backend.payment;

import dev.abu.screener_backend.config.PaymentProperties;
import dev.abu.screener_backend.config.PaymentProperties.MulticardProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the reconciliation sweep: success grants, error → FAILED, revert → REVERTED (no
 * entitlement change), stale PENDING → EXPIRED, and that one bad order never aborts the sweep. The
 * provider and {@link OrderService} are hand-rolled doubles.
 */
class PaymentReconciliationServiceTest {

    private final Map<UUID, String> action = new ConcurrentHashMap<>();
    /** Provider status the test double should report for each order's providerUuid. */
    private final Map<String, ProviderStatus> intendedByUuid = new ConcurrentHashMap<>();

    @Test
    void successGrantsErrorFailsRevertReverts() {
        Order ok = order(ProviderStatus.SUCCESS, false);
        Order err = order(ProviderStatus.ERROR, false);
        Order rev = order(ProviderStatus.REVERT, false);

        sweep(List.of(ok, err, rev));

        assertEquals("GRANT", action.get(ok.getId()));
        assertEquals("FAILED", action.get(err.getId()));
        assertEquals("REVERTED", action.get(rev.getId()));
    }

    @Test
    void stalePendingExpiresButFreshPendingIsLeft() {
        Order stale = order(ProviderStatus.PENDING, true);   // expiresAt in the past
        Order fresh = order(ProviderStatus.PENDING, false);  // still within TTL

        sweep(List.of(stale, fresh));

        assertEquals("EXPIRED", action.get(stale.getId()));
        assertNull(action.get(fresh.getId()), "a non-stale pending order is left untouched");
    }

    @Test
    void notFoundExpires() {
        Order nf = order(ProviderStatus.NOT_FOUND, false);
        sweep(List.of(nf));
        assertEquals("EXPIRED", action.get(nf.getId()));
    }

    @Test
    void cancelledInvoiceExpires() {
        Order cancelled = order(ProviderStatus.CANCELED, false); // closed unpaid
        sweep(List.of(cancelled));
        assertEquals("EXPIRED", action.get(cancelled.getId()));
    }

    @Test
    void successWithMismatchedAmountFailsWithoutGrant() {
        Order mismatch = order(ProviderStatus.SUCCESS, false);
        mismatch.setAmount(new BigDecimal("5.00")); // 500 tiyin ≠ the provider double's reported 100
        sweep(List.of(mismatch));
        assertEquals("MISMATCH", action.get(mismatch.getId()), "amount mismatch must not grant");
    }

    @Test
    void nullProviderUuidStaleExpires() {
        Order orphan = order(ProviderStatus.PENDING, true);
        orphan.setProviderUuid(null); // defensive-only branch — still expires when stale
        sweep(List.of(orphan));
        assertEquals("EXPIRED", action.get(orphan.getId()));
    }

    @Test
    void oneBadOrderDoesNotAbortSweep() {
        Order bad = order(ProviderStatus.SUCCESS, false);
        intendedByUuid.remove(bad.getProviderUuid());
        bad.setProviderUuid("BOOM"); // provider throws for this uuid
        Order good = order(ProviderStatus.SUCCESS, false);

        sweep(List.of(bad, good));

        assertNull(action.get(bad.getId()));
        assertEquals("GRANT", action.get(good.getId()), "the good order is still processed after the bad one");
    }

    // ---- harness ----

    private void sweep(List<Order> pending) {
        PaymentProvider provider = new PaymentProvider() {
            @Override public String id() { return "test"; }
            @Override public CheckoutSession createCheckout(Order order) { return null; }
            @Override public ProviderPayment fetchPayment(String providerUuid) {
                if ("BOOM".equals(providerUuid)) {
                    throw new RuntimeException("provider unreachable");
                }
                return new ProviderPayment(intendedByUuid.get(providerUuid), "uzcard", 100L, "https://receipt", "err-detail");
            }
        };
        new PaymentReconciliationService(orderRepo(pending), provider, orderService()).reconcile();
    }

    private Order order(ProviderStatus status, boolean stale) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(OrderStatus.PENDING);
        order.setAmount(new BigDecimal("1.00")); // 100 tiyin — matches the provider double's reported amount
        order.setProviderUuid("pu-" + UUID.randomUUID());
        order.setExpiresAt(stale ? Instant.now().minusSeconds(60) : Instant.now().plusSeconds(600));
        intendedByUuid.put(order.getProviderUuid(), status);
        return order;
    }

    @SuppressWarnings("unchecked")
    private OrderRepository orderRepo(List<Order> pending) {
        return (OrderRepository) Proxy.newProxyInstance(
                OrderRepository.class.getClassLoader(),
                new Class<?>[]{OrderRepository.class},
                (proxy, method, args) -> method.getName().equals("findByStatus")
                        ? new ArrayList<>(pending)
                        : (List.class.isAssignableFrom(method.getReturnType()) ? List.of() : null));
    }

    private OrderService orderService() {
        return new OrderService(null, null, null, null, null, null, null, props()) {
            @Override public void markPaidAndGrant(UUID orderId, String ps, String receiptUrl, OrderSource source) { action.put(orderId, "GRANT"); }
            @Override public void markFailed(UUID orderId, String detail) { action.put(orderId, "FAILED"); }
            @Override public void markAmountMismatch(UUID orderId, String detail) { action.put(orderId, "MISMATCH"); }
            @Override public void markReverted(UUID orderId) { action.put(orderId, "REVERTED"); }
            @Override public void expire(UUID orderId, OrderSource source) { action.put(orderId, "EXPIRED"); }
        };
    }

    private static PaymentProperties props() {
        MulticardProperties mc = new MulticardProperties(
                "https://dev-mesh.multicard.uz", "app", "secret", "store-1",
                "https://cb", "https://ret", Duration.ofMinutes(30), "195.158.26.90", "ru", false);
        return new PaymentProperties(Duration.ofMinutes(1), mc);
    }
}
