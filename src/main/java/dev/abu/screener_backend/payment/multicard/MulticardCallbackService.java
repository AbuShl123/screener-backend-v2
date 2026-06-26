package dev.abu.screener_backend.payment.multicard;

import dev.abu.screener_backend.config.PaymentProperties;
import dev.abu.screener_backend.config.PaymentProperties.MulticardProperties;
import dev.abu.screener_backend.payment.*;
import dev.abu.screener_backend.payment.multicard.dto.MulticardCallbackPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * The heart of the money flow. Verifies the success callback and, on a good payment, performs the
 * idempotent grant — <strong>before</strong> the controller acknowledges (grant-before-acknowledge,
 * so a {@code 200 OK} is never sent for access the user didn't actually receive).
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}: the grant
 * ({@link OrderService#markPaidAndGrant}) runs in its own transaction and commits when it returns,
 * and only then does {@code handle} return {@code OK}.
 *
 * <p>Failure semantics: our-side failure → {@link CallbackOutcome#retry()} (HTTP 500;
 * Multicard freezes + retries); deliberate rejection → {@link CallbackOutcome#reject} (Multicard
 * reverses/refunds); bad signature / wrong IP → 400, unprocessed.
 */
@Service
@Slf4j
public class MulticardCallbackService {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final MulticardProperties props;

    public MulticardCallbackService(OrderRepository orderRepository,
                                    OrderService orderService,
                                    PaymentProperties paymentProperties) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.props = paymentProperties.multicard();
    }

    public CallbackOutcome handle(MulticardCallbackPayload payload, String sourceIp) {
        if (!Objects.equals(props.allowedIp(), sourceIp)) {
            log.warn("Rejecting Multicard callback from unexpected source IP: {}", sourceIp);
            return CallbackOutcome.badSource();
        }
        if (!MulticardSignature.valid(payload, props.secret())) {
            log.warn("Rejecting Multicard callback with bad signature (uuid={})", payload.uuid());
            return CallbackOutcome.badSign();
        }

        Order order = orderRepository.findByProviderUuid(payload.uuid()).orElse(null);
        if (order == null) {
            log.warn("Multicard callback for unknown provider uuid {}", payload.uuid());
            return CallbackOutcome.reject(OrderReason.UNKNOWN_ORDER);
        }
        if (order.getStatus() == OrderStatus.PAID) {
            return CallbackOutcome.ok(); // idempotent replay
        }
        long expectedTiyin = MulticardPaymentProvider.toTiyin(order.getAmount());
        if (payload.amount() == null || payload.amount() != expectedTiyin) {
            log.warn("Amount mismatch for order {}: callback={} expected={}",
                    order.getId(), payload.amount(), expectedTiyin);
            // Reject → refund. The order is left PENDING for the sweep.
            return CallbackOutcome.reject(OrderReason.AMOUNT_MISMATCH);
        }

        try {
            orderService.markPaidAndGrant(order.getId(), payload.ps(), OrderSource.CALLBACK);
        } catch (RuntimeException e) {
            // Transient (DB/lock) error → 500 so Multicard freezes funds and retries; the sweep is the backstop.
            log.error("Grant failed for order {} (transient); signalling retry", order.getId(), e);
            return CallbackOutcome.retry();
        }
        return CallbackOutcome.ok();
    }
}
