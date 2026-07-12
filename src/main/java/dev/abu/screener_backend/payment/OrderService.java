package dev.abu.screener_backend.payment;

import dev.abu.screener_backend.billing.*;
import dev.abu.screener_backend.billing.Currency;
import dev.abu.screener_backend.config.PaymentProperties;
import dev.abu.screener_backend.entitlement.EntitlementService;
import dev.abu.screener_backend.entitlement.GrantSource;
import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.payment.dto.OrderDetailsEntry;
import dev.abu.screener_backend.payment.dto.OrderHistoryEntry;
import dev.abu.screener_backend.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    public OrderDetailsEntry createOrReuse(User user, String planCode, BigDecimal amountMoney, String currency) {
        Plan plan = planRepository.findByCode(planCode)
                .filter(Plan::isActive)
                .orElseThrow(() -> ApiException.badRequest("Unknown or inactive plan: " + planCode));

        // Gate: a user with a paid subscription that is not yet near expiry may not stack another fixed
        // plan — there is nothing to renew yet, and a redundant fixed purchase muddies the audit. Once
        // access is within the configured renewal window (default 5 days) the purchase is allowed, so a
        // user can renew before lapsing. PER_DAY (pay-by-days) is deliberately exempt by business rule:
        // a user may top up arbitrary days at any time. Trial and expired users are NOT gated
        // (hasPaidAccessBeyondRenewalWindow is false for them), so conversion during a trial still
        // works; admins bypass entitlement and are never gated.
        if (plan.getType() == PlanType.FIXED && entitlementService.hasPaidAccessBeyondRenewalWindow(user)) {
            throw ApiException.conflict("You already have an active subscription; "
                    + "you can renew closer to its expiry, or use pay-by-days to add more time.");
        }

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
                return toDetails(existing);
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

    // ---------------------------------------------------------------------------------------
    // Cancel  (POST /api/billing/orders/current/cancel)
    // ---------------------------------------------------------------------------------------

    /**
     * User-initiated cancel of the current order's unpaid invoice. Finds the user's current order (the
     * open one, else the most recent — the same view {@code /orders/current} exposes) and, only if it is
     * still {@code PENDING}, transitions it to {@code CANCELED} and cancels the Multicard invoice
     * best-effort. Any other status is a {@code 409}: a paid order has nothing to cancel, and an
     * already-terminal order (expired/failed/canceled) has no live invoice.
     *
     * <p>The provider cancel is best-effort (mirrors {@link #supersede}): if the user in fact paid on the
     * hosted page just before cancelling, Multicard's DELETE 400s (already completed), we swallow it, and
     * the authoritative success callback later rescues {@code CANCELED → PAID} (E6). The local CANCELED is
     * never blocked on the provider call.
     */
    public OrderDetailsEntry cancelCurrentOrder(UUID userId) {
        Order current = orderRepository.findFirstByUser_IdAndStatusIn(userId, OPEN)
                .orElseGet(() -> orderRepository
                        .findByUser_IdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1))
                        .stream().findFirst().orElse(null));
        if (current == null) {
            throw ApiException.notFound("No orders for user");
        }
        if (current.getStatus() != OrderStatus.PENDING) {
            throw ApiException.conflict("Current order is not pending; there is no active invoice to cancel.");
        }

        // Reload under a pessimistic lock and re-check: a concurrent success callback or sweep may have
        // moved it out of PENDING between the read above and now. @Version backs the rare stale re-check.
        Order order = orderRepository.findByIdForUpdate(current.getId())
                .orElseThrow(() -> ApiException.notFound("Order not found: " + current.getId()));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw ApiException.conflict("Current order is not pending; there is no active invoice to cancel.");
        }

        String providerUuid = order.getProviderUuid();
        stateMachine.transition(order, OrderStatus.CANCELED, OrderSource.API, OrderReason.USER_CANCELED, null);
        if (providerUuid != null) {
            try {
                provider.cancelCheckout(providerUuid);
            } catch (RuntimeException e) {
                log.warn("Failed to cancel invoice {} on user cancel: {}", providerUuid, e.getMessage());
            }
        }
        return toDetails(order);
    }

    private OrderDetailsEntry createNew(
            User user,
            Plan plan,
            PlanPrice price,
            BigDecimal amountMoney,
            String currency,
            Instant now
    ) {
        long days = computeDays(plan, price, amountMoney);
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
        return toDetails(order);
    }

    /**
     * {@code days = FIXED ? duration_days : ceil(amount / pricePerDay)}. The pay-by-days math (amount
     * validation + ceiling division) lives on {@link PlanPrice#daysFor} so it's shared with the public
     * pay-as-you-go days estimate ({@code BillingController}), which computes it without an order.
     */
    private static long computeDays(Plan plan, PlanPrice price, BigDecimal amountMoney) {
        if (plan.getType() == PlanType.FIXED) {
            return plan.getDurationDays();
        }
        return price.daysFor(amountMoney);
    }

    // ---------------------------------------------------------------------------------------
    // Idempotent grant  (shared by the callback + the reconciliation sweep)
    // ---------------------------------------------------------------------------------------

    /**
     * Marks the order {@code PAID} and extends entitlement, exactly once. Re-checks under a pessimistic
     * lock so concurrent callback + sweep cannot double-grant. The funnel for both the success callback
     * and reconciliation.
     *
     * <p><strong>Late-success rescue:</strong> a terminal order ({@code EXPIRED}/{@code FAILED}/
     * {@code CANCELED}) reached here via a race may be resurrected to {@code PAID} <em>only</em> by the
     * {@code CALLBACK} source — the authoritative success push. The reconciliation sweep must not rescue
     * a terminal order (it only ever scans {@code PENDING}; a terminal status at lock time is a rare race
     * and we leave it). A {@code REVERTED} order never grants — refunded money never resurrects.
     */
    public void markPaidAndGrant(UUID orderId, String ps, String receiptUrl, OrderSource source) {
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
        order.setReceiptUrl(receiptUrl);
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
     * (mirroring the callback's guard). Only a still-PENDING order is moved to FAILED; never grants.
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
    public List<OrderDetailsEntry> listOrders(UUID userId) {
        return orderRepository.findByUser_IdOrderByCreatedAtDesc(userId, PageRequest.of(0, ORDER_HISTORY_LIMIT))
                .stream().map(this::toDetails).toList();
    }

    @Transactional(readOnly = true)
    public OrderDetailsEntry currentOrder(UUID userId) {
        Order order = orderRepository.findFirstByUser_IdAndStatusIn(userId, OPEN)
                .orElseGet(() -> orderRepository
                        .findByUser_IdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1))
                        .stream().findFirst().orElse(null));
        if (order == null) {
            throw ApiException.notFound("No orders for user");
        }
        return toDetails(order);
    }

    @Transactional(readOnly = true)
    public OrderDetailsEntry getOrder(UUID userId, UUID orderId) {
        return toDetails(requireOwnedOrder(userId, orderId));
    }

    /**
     * Resolves one order to its detail view by id, for cross-domain reads (the entitlement ledger embeds
     * a purchase's order). No ownership filter: the only caller resolves an {@code order_id} taken from a
     * ledger row already scoped to its owning user, so the order is the caller's own.
     */
    @Transactional(readOnly = true)
    public Optional<OrderDetailsEntry> findOrderDetails(UUID orderId) {
        return orderRepository.findById(orderId).map(this::toDetails);
    }

    /**
     * Full transition audit for one order (newest first by {@code seq}). Ownership is enforced — a user
     * may only read their own order's history.
     */
    @Transactional(readOnly = true)
    public List<OrderHistoryEntry> getOrderHistory(UUID userId, UUID orderId) {
        requireOwnedOrder(userId, orderId); // 404 if not found / not owned
        return historyRepository.findByOrderIdOrderBySeqDesc(orderId).stream()
                .map(OrderService::toHistoryEntry).toList();
    }

    private Order requireOwnedOrder(UUID userId, UUID orderId) {
        return orderRepository.findById(orderId)
                .filter(o -> o.getUser().getId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Order not found: " + orderId));
    }

    private OrderDetailsEntry toDetails(Order order) {
        var latest = historyRepository.findFirstByOrderIdOrderBySeqDesc(order.getId());
        String reason = latest.map(h -> h.getReason() == null ? null : h.getReason().name()).orElse(null);
        String detail = latest.map(OrderStatusHistory::getReasonDetail).orElse(null);
        BigDecimal amount = Currency.of(order.getCurrency()).forDisplay(order.getAmount());
        return new OrderDetailsEntry(order.getId(), order.getStatus(),
                order.getPlan().getCode(), amount, order.getGrantedDurationSeconds(),
                order.getCurrency(), order.getPaymentProvider(), reason, detail,
                order.getCheckoutUrl(), order.getProviderUuid(), order.getReceiptUrl(),
                order.getExpiresAt(), order.getPaidAt(), order.getCreatedAt());
    }

    private static OrderHistoryEntry toHistoryEntry(OrderStatusHistory h) {
        return new OrderHistoryEntry(
                h.getFromStatus(), h.getToStatus(),
                h.getReason() == null ? null : h.getReason().name(),
                h.getReasonDetail(), h.getSource(), h.getCreatedAt(), h.getSeq());
    }
}
