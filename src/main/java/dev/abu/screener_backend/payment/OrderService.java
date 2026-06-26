package dev.abu.screener_backend.payment;

import dev.abu.screener_backend.billing.*;
import dev.abu.screener_backend.billing.Currency;
import dev.abu.screener_backend.config.PaymentProperties;
import dev.abu.screener_backend.entitlement.EntitlementService;
import dev.abu.screener_backend.entitlement.GrantSource;
import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.payment.dto.CreateOrderResponse;
import dev.abu.screener_backend.payment.dto.OrderStatusResponse;
import dev.abu.screener_backend.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Order creation/reuse + pay-by-days math, plus the idempotent grant shared by the success callback
 * and the reconciliation sweep.
 *
 * <p>The hard rule is preserved: the client sends only {@code planCode} (+ an {@code amount} for
 * pay-by-days). Price and currency are resolved server-side. At most one open order exists per user
 * (DB partial-unique index); the create path reuses or supersedes an existing open order accordingly.
 */
@Service
@Transactional
@Slf4j
public class OrderService {

    private static final Set<OrderStatus> OPEN = EnumSet.of(OrderStatus.CREATED, OrderStatus.PENDING);
    private static final long SECONDS_PER_DAY = 86_400L;
    private static final int ORDER_HISTORY_LIMIT = 100;

    private final PlanRepository planRepository;
    private final PlanPriceRepository planPriceRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderStateMachine stateMachine;
    private final PaymentProvider provider;
    private final EntitlementService entitlementService;
    private final Duration invoiceTtl;

    public OrderService(PlanRepository planRepository,
                        PlanPriceRepository planPriceRepository,
                        OrderRepository orderRepository,
                        OrderStatusHistoryRepository historyRepository,
                        OrderStateMachine stateMachine,
                        PaymentProvider provider,
                        EntitlementService entitlementService,
                        PaymentProperties paymentProperties) {
        this.planRepository = planRepository;
        this.planPriceRepository = planPriceRepository;
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.stateMachine = stateMachine;
        this.provider = provider;
        this.entitlementService = entitlementService;
        this.invoiceTtl = paymentProperties.multicard().invoiceTtl();
    }

    // ---------------------------------------------------------------------------------------
    // Create / reuse  (POST /api/billing/orders)
    // ---------------------------------------------------------------------------------------

    public CreateOrderResponse createOrReuse(User user, String planCode, BigDecimal amountMoney, String currency) {
        Plan plan = planRepository.findByCode(planCode)
                .filter(Plan::isActive)
                .orElseThrow(() -> ApiException.badRequest("Unknown or inactive plan: " + planCode));
        PlanPrice price = planPriceRepository.findByPlan_IdAndCurrency(plan.getId(), currency)
                .filter(PlanPrice::isActive)
                .orElseThrow(() -> ApiException.badRequest("No price for plan " + planCode + " in " + currency));

        Instant now = Instant.now();
        Optional<Order> openOrder = orderRepository.findFirstByUser_IdAndStatusIn(user.getId(), OPEN);
        if (openOrder.isPresent()) {
            Order existing = openOrder.get();
            boolean stale = existing.getExpiresAt() != null && !existing.getExpiresAt().isAfter(now);
            if (stale) {
                // The sweep would also catch this; expire now (locked + re-checked) so a fresh order can
                // be created. expire() flushes, so its UPDATE precedes createNew's INSERT.
                expire(existing.getId(), OrderSource.API);
            } else if (existing.getPlan().getId().equals(plan.getId())) {
                // Same plan: lost-tab re-pay. Reuse the existing checkout URL as-is.
                // If the user already paid, the reconciliation sweep (or the
                // success callback) flips this order to PAID within one cycle.
                return new CreateOrderResponse(existing.getId(), existing.getStatus(), existing.getCheckoutUrl(), false);
            } else {
                // Different plan: supersede the old open order (locked + re-checked), then create fresh.
                supersede(existing.getId());
            }
        }

        return createNew(user, plan, price, amountMoney, currency, now);
    }

    /**
     * Supersede a still-open order because the user started a new order for a different plan. Reloads
     * under a pessimistic lock and re-checks {@code isOpen()} (a concurrent sweep/callback may have closed
     * it first — then this no-ops and the caller falls through to create). Cancels the provider invoice
     * best-effort <em>after</em> the local transition so a provider failure never blocks the supersede.
     *
     * <p><strong>Flush ordering:</strong> the {@code → CANCELED} UPDATE is flushed before the caller's
     * subsequent {@code createNew} INSERT. Hibernate emits INSERTs ahead of UPDATEs at autoflush, so
     * without this the new {@code CREATED} row and the not-yet-{@code CANCELED} old row would both
     * satisfy {@code uq_orders_one_open_per_user} for an instant and trip the partial unique index.
     */
    public void supersede(UUID orderId) {
        // Note: createOrReuse already loaded this order (unlocked) into the persistence context, so this
        // locked reload returns the cached instance with the lock upgraded but fields NOT refreshed. The
        // isOpen() re-check is therefore accurate in the common case but stale under a genuine concurrent
        // close — in which case @Version turns the flush into a safe OptimisticLockException (retryable)
        // rather than a lost update. We accept that over introducing EntityManager.refresh.
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || !order.isOpen()) {
            return; // already closed by a concurrent sweep/callback — fall through to create
        }
        String providerUuid = order.getProviderUuid();
        stateMachine.transition(order, OrderStatus.CANCELED, OrderSource.API, OrderReason.SUPERSEDED, null);
        orderRepository.flush(); // UPDATE before the new INSERT (one-open-order partial index)
        if (providerUuid != null) {
            try {
                provider.cancelCheckout(providerUuid);
            } catch (RuntimeException e) {
                log.warn("Failed to cancel superseded invoice {}: {}", providerUuid, e.getMessage());
            }
        }
    }

    private CreateOrderResponse createNew(
            User user,
            Plan plan,
            PlanPrice price,
            BigDecimal amountMoney,
            String currency,
            Instant now
    ) {
        long days = computeDays(plan, price, amountMoney, currency);
        BigDecimal grantAmount = plan.getType() == PlanType.FIXED ? price.getAmount() : amountMoney;

        Order order = new Order();
        order.setUser(user);
        order.setPlan(plan);
        order.setStatus(OrderStatus.CREATED);
        order.setGrantedDurationSeconds(days * SECONDS_PER_DAY);
        order.setAmount(grantAmount);
        order.setCurrency(currency);
        order.setPaymentProvider(provider.id());
        order.setExpiresAt(now.plus(invoiceTtl));
        try {
            orderRepository.saveAndFlush(order); // assigns id + enforces the one-open-order index early
        } catch (DataIntegrityViolationException race) {
            // Lost the one-open-order race with a concurrent create. The violation marks this
            // transaction rollback-only, so we can't reuse within it — surface a retryable 409. The
            // SPA's retry then finds the now-committed open order and reuses it.
            log.info("Concurrent order create for user {} lost the one-open-order race", user.getId());
            throw ApiException.conflict("An order is already being created; please retry.");
        }

        CheckoutSession session = provider.createCheckout(order);
        order.setProviderUuid(session.providerUuid());
        order.setCheckoutUrl(session.checkoutUrl());
        stateMachine.transition(order, OrderStatus.PENDING, OrderSource.API, null, null);
        return new CreateOrderResponse(order.getId(), order.getStatus(), order.getCheckoutUrl(), false);
    }

    /**
     * {@code days = FIXED ? duration_days : ceil(amount / pricePerDay)}. The pay-by-days {@code amount}
     * is in major units (sum) and must be positive and within the currency's allowed decimal places
     * (E10): UZS permits 2 dp, BTC 8, ETH 18. Keeping the scale within the currency's decimals also
     * guarantees the Multicard adapter's {@code movePointRight(decimals).longValueExact()} conversion
     * stays exact, so we reject an over-scale amount up front with a clean 400 rather than failing deep
     * in invoice creation.
     */
    private static long computeDays(Plan plan, PlanPrice price, BigDecimal amountMoney, String currency) {
        if (plan.getType() == PlanType.FIXED) {
            return plan.getDurationDays();
        }
        if (amountMoney == null || amountMoney.signum() <= 0) {
            throw ApiException.badRequest("amount must be a positive number for pay-by-days");
        }
        Currency.of(currency).requireScale(amountMoney);
        BigDecimal pricePerDay = price.getAmount();
        if (pricePerDay.signum() <= 0) {
            throw ApiException.badRequest("per-day price is not configured");
        }
        return amountMoney.divide(pricePerDay, 0, RoundingMode.CEILING).longValueExact();
    }

    // ---------------------------------------------------------------------------------------
    // Idempotent grant  (shared by the callback + the reconciliation sweep)
    // ---------------------------------------------------------------------------------------

    /**
     * Marks the order {@code PAID} and extends entitlement, exactly once. Re-checks under a pessimistic
     * lock so concurrent callback + sweep cannot double-grant. The funnel for both the success callback
     * and reconciliation.
     *
     * <p><strong>Late-success rescue (E6):</strong> a terminal order ({@code EXPIRED}/{@code FAILED}/
     * {@code CANCELED}) reached here via a race may be resurrected to {@code PAID} <em>only</em> by the
     * {@code CALLBACK} source — the authoritative success push. The reconciliation sweep must not rescue
     * a terminal order (it only ever scans {@code PENDING}; a terminal status at lock time is a rare race
     * and we leave it). A {@code REVERTED} order never grants — refunded money never resurrects.
     */
    public void markPaidAndGrant(UUID orderId, String ps, OrderSource source) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found: " + orderId));
        OrderStatus status = order.getStatus();
        if (status == OrderStatus.PAID) {
            return; // idempotent: already granted
        }
        if (status == OrderStatus.REVERTED) {
            log.warn("Refusing to grant REVERTED order {} — refunded money never resurrects", orderId);
            return;
        }
        if (status != OrderStatus.PENDING && source != OrderSource.CALLBACK) {
            // Terminal order (EXPIRED/FAILED/CANCELED) at lock time; only the success callback may rescue.
            log.warn("Reconciliation will not rescue terminal order {} (status {}) to PAID", orderId, status);
            return;
        }
        order.setPs(ps);
        order.setPaidAt(Instant.now());
        OrderReason grantReason = source == OrderSource.CALLBACK ? OrderReason.CALLBACK_GRANT : OrderReason.RECONCILED_GRANT;
        stateMachine.transition(order, OrderStatus.PAID, source, grantReason, null);
        entitlementService.extend(
                order.getUser().getId(),
                Duration.ofSeconds(order.getGrantedDurationSeconds()),
                true, GrantSource.PURCHASE, order.getId(), null, null);
    }

    /** Reconciliation: provider reported {@code error}. Only a still-PENDING order is moved to FAILED. */
    public void markFailed(UUID orderId, String detail) {
        transitionPendingOnly(orderId, OrderStatus.FAILED, OrderReason.PROVIDER_ERROR, detail);
    }

    /**
     * Reconciliation: provider reports {@code success} but the amount does not match the order snapshot
     * (E5, mirroring the callback's guard). Only a still-PENDING order is moved to FAILED; never grants.
     */
    public void markAmountMismatch(UUID orderId, String detail) {
        transitionPendingOnly(orderId, OrderStatus.FAILED, OrderReason.AMOUNT_MISMATCH, detail);
    }

    /** Reconciliation: provider reported {@code revert}. Recorded only — access is NOT revoked. */
    public void markReverted(UUID orderId) {
        transitionPendingOnly(orderId, OrderStatus.REVERTED, OrderReason.PROVIDER_REVERT, null);
    }

    /**
     * Marks a still-open order EXPIRED (invoice TTL elapsed / provider no longer knows it). Flushes so
     * that when called from {@code createOrReuse} the UPDATE precedes the subsequent {@code createNew}
     * INSERT (one-open-order partial index); harmless when called standalone from the sweep.
     */
    public void expire(UUID orderId, OrderSource source) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || !order.isOpen()) {
            return;
        }
        stateMachine.transition(order, OrderStatus.EXPIRED, source, OrderReason.INVOICE_EXPIRED, null);
        orderRepository.flush();
    }

    private void transitionPendingOnly(UUID orderId, OrderStatus to, OrderReason reason, String detail) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING) {
            // A callback may have paid it between the sweep scan and now — leave it.
            return;
        }
        stateMachine.transition(order, to, OrderSource.RECONCILIATION, reason, detail);
    }

    // ---------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<OrderStatusResponse> listOrders(UUID userId) {
        return orderRepository.findByUser_IdOrderByCreatedAtDesc(userId, PageRequest.of(0, ORDER_HISTORY_LIMIT))
                .stream().map(this::toStatusResponse).toList();
    }

    @Transactional(readOnly = true)
    public OrderStatusResponse currentOrder(UUID userId) {
        Order order = orderRepository.findFirstByUser_IdAndStatusIn(userId, OPEN)
                .orElseGet(() -> orderRepository
                        .findByUser_IdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1))
                        .stream().findFirst().orElse(null));
        if (order == null) {
            throw ApiException.notFound("No orders for user");
        }
        return toStatusResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderStatusResponse getOrder(UUID userId, UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getUser().getId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Order not found: " + orderId));
        return toStatusResponse(order);
    }

    private OrderStatusResponse toStatusResponse(Order order) {
        var latest = historyRepository.findFirstByOrderIdOrderBySeqDesc(order.getId());
        String reason = latest.map(h -> h.getReason() == null ? null : h.getReason().name()).orElse(null);
        String detail = latest.map(OrderStatusHistory::getReasonDetail).orElse(null);
        return new OrderStatusResponse(order.getId(), order.getStatus(), reason, detail,
                order.getExpiresAt(), order.getPaidAt());
    }
}
