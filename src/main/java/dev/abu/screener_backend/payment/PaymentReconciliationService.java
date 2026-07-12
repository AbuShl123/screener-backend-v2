package dev.abu.screener_backend.payment;

import dev.abu.screener_backend.payment.multicard.MulticardPaymentProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * The safety net. A scheduled sweep resolves stale {@code PENDING} orders via the durable
 * {@code GET /payment/{uuid}}, catching abandoned checkouts (no callback ever fires), lost callbacks,
 * and refunds. Both the callback and this sweep funnel into the same idempotent
 * {@link OrderService#markPaidAndGrant}.
 *
 * <p>Volume is low, so scanning open orders each minute is cheap. Per-order failures are caught and
 * logged so one bad order never aborts the sweep.
 */
@Component
@Slf4j
public class PaymentReconciliationService {

    private final OrderRepository orderRepository;
    private final PaymentProvider provider;
    private final OrderService orderService;

    public PaymentReconciliationService(OrderRepository orderRepository,
                                        PaymentProvider provider,
                                        OrderService orderService) {
        this.orderRepository = orderRepository;
        this.provider = provider;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${screener.payment.reconciliation-interval}")
    public void reconcile() {
        List<Order> pending = orderRepository.findByStatus(OrderStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }
        log.debug("Reconciliation sweep over {} pending order(s)", pending.size());
        for (Order order : pending) {
            try {
                reconcileOne(order);
            } catch (RuntimeException e) {
                log.warn("Reconciliation failed for order {}: {}", order.getId(), e.getMessage());
            }
        }
    }

    private void reconcileOne(Order order) {
        Instant now = Instant.now();
        if (order.getProviderUuid() == null) {
            // Defensive-only: a persisted PENDING order ALWAYS has a provider_uuid — create is a single
            // transaction and a failed createCheckout rolls the row back, so this branch is unreachable
            // by design. A hit means a broken DB/code invariant — shout about it.
            log.error("PENDING order {} has no provider_uuid — broken invariant (create is single-transaction)",
                    order.getId());
            if (isStale(order, now)) {
                orderService.expire(order.getId(), OrderSource.RECONCILIATION);
            }
            return;
        }

        ProviderPayment payment = provider.fetchPayment(order.getProviderUuid());

        switch (payment.status()) {
            case SUCCESS -> grantIfAmountMatches(order, payment);
            case ERROR -> orderService.markFailed(order.getId(), payment.error());
            case REVERT -> orderService.markReverted(order.getId()); // access NOT revoked
            case CANCELED -> orderService.expire(order.getId(), OrderSource.RECONCILIATION); // closed unpaid
            case PENDING -> {
                if (isStale(order, now)) {
                    orderService.expire(order.getId(), OrderSource.RECONCILIATION);
                }
            }
            case NOT_FOUND -> orderService.expire(order.getId(), OrderSource.RECONCILIATION);
        }
    }

    /**
     * Verifies the provider-reported amount matches the order snapshot before granting, mirroring the
     * callback's {@code AMOUNT_MISMATCH} guard. A {@code null} or mismatched amount fails the order
     * (never grants); the durable {@code GET /payment/{uuid}} carries the authoritative {@code total_amount}.
     */
    private void grantIfAmountMatches(Order order, ProviderPayment payment) {
        long expectedTiyin = MulticardPaymentProvider.toTiyin(order.getAmount());
        Long actualTiyin = payment.amountTiyin();
        if (actualTiyin == null || actualTiyin != expectedTiyin) {
            log.warn("Reconciliation amount mismatch for order {}: provider={} expected={}",
                    order.getId(), actualTiyin, expectedTiyin);
            orderService.markAmountMismatch(order.getId(),
                    "reconciliation: provider=" + actualTiyin + " expected=" + expectedTiyin);
            return;
        }
        orderService.markPaidAndGrant(order.getId(), payment.ps(), payment.receiptUrl(), OrderSource.RECONCILIATION);
    }

    private static boolean isStale(Order order, Instant now) {
        return order.getExpiresAt() != null && !order.getExpiresAt().isAfter(now);
    }
}
