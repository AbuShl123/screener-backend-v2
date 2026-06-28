package dev.abu.screener_backend.payment;

import dev.abu.screener_backend.billing.Plan;
import dev.abu.screener_backend.billing.PlanPrice;
import dev.abu.screener_backend.billing.PlanPriceRepository;
import dev.abu.screener_backend.billing.PlanRepository;
import dev.abu.screener_backend.billing.PlanType;
import dev.abu.screener_backend.config.BillingProperties;
import dev.abu.screener_backend.config.PaymentProperties;
import dev.abu.screener_backend.config.PaymentProperties.MulticardProperties;
import dev.abu.screener_backend.entitlement.EntitlementService;
import dev.abu.screener_backend.entitlement.GrantSource;
import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.payment.dto.OrderDetailsEntry;
import dev.abu.screener_backend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OrderService}: fixed-plan create (seconds snapshot), pay-by-days {@code ceil},
 * reuse same plan, supersede different plan (history reason SUPERSEDED), expired-open recreate,
 * server-side currency resolution, the lost-tab reuse path, and idempotent grant. Uses
 * stateful in-memory reflective-proxy repositories plus a real {@link OrderStateMachine} (the codebase
 * avoids Mockito).
 */
class OrderServiceTest {

    private static final long DAY = 86_400L;

    private final Map<UUID, Order> orders = new LinkedHashMap<>();
    private final List<OrderStatusHistory> history = new ArrayList<>();
    private final Map<String, Plan> plansByCode = new LinkedHashMap<>();
    private final Map<String, PlanPrice> pricesByPlanCurrency = new LinkedHashMap<>();

    private final AtomicInteger extendCalls = new AtomicInteger();
    private final AtomicInteger fetchCalls = new AtomicInteger();
    private final List<String> cancelCalls = new ArrayList<>();
    private boolean paidAccessBeyondRenewal = false;

    private OrderService service;
    private final User user = user();

    @BeforeEach
    void setUp() {
        plansByCode.clear();
        pricesByPlanCurrency.clear();
        orders.clear();
        history.clear();
        cancelCalls.clear();
        extendCalls.set(0);
        fetchCalls.set(0);
        paidAccessBeyondRenewal = false;

        OrderRepository orderRepo = orderRepo();
        OrderStatusHistoryRepository historyRepo = historyRepo();
        OrderStateMachine stateMachine = new OrderStateMachine(orderRepo, historyRepo);
        service = new OrderService(planRepo(), planPriceRepo(), orderRepo, historyRepo, stateMachine,
                provider(), entitlementService(), props());
    }

    // ---------------------------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------------------------

    @Test
    void fixedPlanCreateSnapshotsSeconds() {
        plan("monthly", PlanType.FIXED, 30);
        price("monthly", "UZS", "150000");

        OrderDetailsEntry res = service.createOrReuse(user, "monthly", null, "UZS");

        assertEquals(OrderStatus.PENDING, res.status());
        assertEquals("https://checkout", res.checkoutUrl());
        Order saved = orders.get(res.orderId());
        assertEquals(30 * DAY, saved.getGrantedDurationSeconds());
        assertEquals(0, new BigDecimal("150000").compareTo(saved.getAmount()));
        assertEquals("UZS", saved.getCurrency());
        assertEquals("pu-1", saved.getProviderUuid());
    }

    @Test
    void payByDaysCeilRoundsUp() {
        plan("pay_as_you_go", PlanType.PER_DAY, null);
        price("pay_as_you_go", "UZS", "1000");

        assertEquals(8 * DAY, durationOf(service.createOrReuse(user, "pay_as_you_go", new BigDecimal("7900"), "UZS")));
        reset();
        assertEquals(8 * DAY, durationOf(service.createOrReuse(user, "pay_as_you_go", new BigDecimal("8000"), "UZS")));
        reset();
        assertEquals(8 * DAY, durationOf(service.createOrReuse(user, "pay_as_you_go", new BigDecimal("7001"), "UZS")));
    }

    @Test
    void payByDaysRejectsNonPositiveAmount() {
        plan("pay_as_you_go", PlanType.PER_DAY, null);
        price("pay_as_you_go", "UZS", "1000");

        assertThrows(ApiException.class, () -> service.createOrReuse(user, "pay_as_you_go", BigDecimal.ZERO, "UZS"));
        assertThrows(ApiException.class, () -> service.createOrReuse(user, "pay_as_you_go", null, "UZS"));
    }

    @Test
    void payByDaysAcceptsWithinScaleFractionalAmount() {
        plan("pay_as_you_go", PlanType.PER_DAY, null);
        price("pay_as_you_go", "UZS", "1000");
        // 7900.50 / 1000 = 7.9005 → ceil → 8 days. UZS permits 2 dp, so a fractional sum is valid.
        assertEquals(8 * DAY, durationOf(service.createOrReuse(user, "pay_as_you_go", new BigDecimal("7900.50"), "UZS")));
    }

    @Test
    void payByDaysRejectsAmountWithTooManyDecimalsForCurrency() {
        plan("pay_as_you_go", PlanType.PER_DAY, null);
        price("pay_as_you_go", "UZS", "1000");
        // UZS allows 2 dp; 100.123 carries 3 → 400.
        assertThrows(ApiException.class, () -> service.createOrReuse(user, "pay_as_you_go", new BigDecimal("100.123"), "UZS"));
    }

    @Test
    void currencyResolvedServerSideRejectsMissingPrice() {
        plan("monthly", PlanType.FIXED, 30);
        price("monthly", "UZS", "150000");
        // No KZT price row → resolved currency that lacks a price is a 400.
        assertThrows(ApiException.class, () -> service.createOrReuse(user, "monthly", null, "KZT"));
    }

    // ---------------------------------------------------------------------------------------
    // Active-subscription gate
    // ---------------------------------------------------------------------------------------

    @Test
    void fixedPlanRejectedWhenUserHasActivePaidAccess() {
        plan("monthly", PlanType.FIXED, 30);
        price("monthly", "UZS", "150000");
        paidAccessBeyondRenewal = true;

        ApiException ex = assertThrows(ApiException.class,
                () -> service.createOrReuse(user, "monthly", null, "UZS"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(orders.isEmpty(), "no order created when an active paid subscription gates the request");
    }

    @Test
    void payByDaysAllowedWhenUserHasActivePaidAccess() {
        plan("pay_as_you_go", PlanType.PER_DAY, null);
        price("pay_as_you_go", "UZS", "1000");
        paidAccessBeyondRenewal = true; // pay-by-days is exempt — a paid, active user may still top up days.

        assertEquals(8 * DAY, durationOf(service.createOrReuse(user, "pay_as_you_go", new BigDecimal("7900"), "UZS")));
    }

    // ---------------------------------------------------------------------------------------
    // Reuse / supersede
    // ---------------------------------------------------------------------------------------

    @Test
    void reuseSamePlanReturnsExistingCheckoutWithoutProbingProvider() {
        Plan monthly = plan("monthly", PlanType.FIXED, 30);
        price("monthly", "UZS", "150000");
        Order open = seedOpenOrder(monthly, "https://existing", Instant.now().plusSeconds(600));

        OrderDetailsEntry res = service.createOrReuse(user, "monthly", null, "UZS");

        assertEquals(open.getId(), res.orderId());
        assertEquals("https://existing", res.checkoutUrl());
        assertEquals(1, orders.size(), "no new order created on reuse");
        // Reuse never probes the provider or grants — the sweep/callback handles a lost-tab payment.
        assertEquals(0, fetchCalls.get(), "reuse must not call the provider");
        assertEquals(0, extendCalls.get(), "reuse must not grant");
    }

    @Test
    void supersedeDifferentPlanCancelsOldAndCreatesNew() {
        Plan yearly = plan("yearly", PlanType.FIXED, 365);
        plan("monthly", PlanType.FIXED, 30);
        price("monthly", "UZS", "150000");
        Order old = seedOpenOrder(yearly, "https://old", Instant.now().plusSeconds(600));

        OrderDetailsEntry res = service.createOrReuse(user, "monthly", null, "UZS");

        assertEquals(OrderStatus.CANCELED, orders.get(old.getId()).getStatus());
        assertEquals(OrderReason.SUPERSEDED, latestReason(old.getId()));
        assertEquals(List.of(old.getProviderUuid()), cancelCalls);
        assertNotEquals(old.getId(), res.orderId());
        assertEquals(OrderStatus.PENDING, orders.get(res.orderId()).getStatus());
        // Exactly one open order remains (old CANCELED, new PENDING) — the invariant the
        // flush-before-INSERT ordering protects at the DB partial index.
        assertEquals(1, orders.values().stream().filter(Order::isOpen).count());
    }

    @Test
    void supersedeNoOpsWhenOrderAlreadyClosed() {
        Plan yearly = plan("yearly", PlanType.FIXED, 365);
        Order old = seedOpenOrder(yearly, "https://old", Instant.now().plusSeconds(600));
        old.setStatus(OrderStatus.PAID); // a concurrent callback won the race before we locked

        service.supersede(old.getId());

        assertEquals(OrderStatus.PAID, orders.get(old.getId()).getStatus(), "must not clobber a closed order");
        assertTrue(cancelCalls.isEmpty(), "no provider cancel for an already-closed order");
    }

    @Test
    void expiredOpenOrderIsRecreated() {
        Plan monthly = plan("monthly", PlanType.FIXED, 30);
        price("monthly", "UZS", "150000");
        Order stale = seedOpenOrder(monthly, "https://stale", Instant.now().minusSeconds(60));

        OrderDetailsEntry res = service.createOrReuse(user, "monthly", null, "UZS");

        assertEquals(OrderStatus.EXPIRED, orders.get(stale.getId()).getStatus());
        assertEquals(OrderReason.INVOICE_EXPIRED, latestReason(stale.getId()));
        assertNotEquals(stale.getId(), res.orderId());
    }

    // ---------------------------------------------------------------------------------------
    // Idempotent grant
    // ---------------------------------------------------------------------------------------

    @Test
    void markPaidAndGrantIsIdempotent() {
        Plan monthly = plan("monthly", PlanType.FIXED, 30);
        price("monthly", "UZS", "150000");
        Order order = seedOpenOrder(monthly, "https://x", Instant.now().plusSeconds(600));

        service.markPaidAndGrant(order.getId(), "uzcard", OrderSource.CALLBACK);
        service.markPaidAndGrant(order.getId(), "uzcard", OrderSource.CALLBACK);

        assertEquals(OrderStatus.PAID, orders.get(order.getId()).getStatus());
        assertEquals(1, extendCalls.get(), "entitlement extended exactly once for one payment");
    }

    @Test
    void lateSuccessViaCallbackRescuesExpiredOrder() {
        Plan monthly = plan("monthly", PlanType.FIXED, 30);
        Order order = seedOpenOrder(monthly, "https://x", Instant.now().plusSeconds(600));
        order.setStatus(OrderStatus.EXPIRED); // a race expired it before the genuine callback arrived

        service.markPaidAndGrant(order.getId(), "uzcard", OrderSource.CALLBACK);

        assertEquals(OrderStatus.PAID, orders.get(order.getId()).getStatus(), "callback rescues a terminal order");
        assertEquals(OrderReason.CALLBACK_GRANT, latestReason(order.getId()));
        assertEquals(1, extendCalls.get());
    }

    @Test
    void reconciliationDoesNotRescueExpiredOrder() {
        Plan monthly = plan("monthly", PlanType.FIXED, 30);
        Order order = seedOpenOrder(monthly, "https://x", Instant.now().plusSeconds(600));
        order.setStatus(OrderStatus.EXPIRED);

        service.markPaidAndGrant(order.getId(), "uzcard", OrderSource.RECONCILIATION);

        assertEquals(OrderStatus.EXPIRED, orders.get(order.getId()).getStatus(), "sweep must not resurrect a terminal order");
        assertEquals(0, extendCalls.get());
    }

    @Test
    void revertedOrderNeverGrants() {
        Plan monthly = plan("monthly", PlanType.FIXED, 30);
        Order order = seedOpenOrder(monthly, "https://x", Instant.now().plusSeconds(600));
        order.setStatus(OrderStatus.REVERTED); // refunded

        service.markPaidAndGrant(order.getId(), "uzcard", OrderSource.CALLBACK);

        assertEquals(OrderStatus.REVERTED, orders.get(order.getId()).getStatus(), "refunded money never resurrects");
        assertEquals(0, extendCalls.get());
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures & in-memory repositories
    // ---------------------------------------------------------------------------------------

    private void reset() {
        orders.clear();
        history.clear();
    }

    private long durationOf(OrderDetailsEntry res) {
        return orders.get(res.orderId()).getGrantedDurationSeconds();
    }

    /** Last history row for the order in insertion order — mirrors the DB-generated {@code seq} ordering. */
    private OrderReason latestReason(UUID orderId) {
        OrderReason reason = null;
        for (OrderStatusHistory h : history) {
            if (h.getOrderId().equals(orderId)) {
                reason = h.getReason();
            }
        }
        return reason;
    }

    private Order seedOpenOrder(Plan plan, String checkoutUrl, Instant expiresAt) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setUser(user);
        order.setPlan(plan);
        order.setStatus(OrderStatus.PENDING);
        order.setGrantedDurationSeconds(plan.getDurationDays() == null ? DAY : plan.getDurationDays() * DAY);
        order.setAmount(new BigDecimal("150000"));
        order.setCurrency("UZS");
        order.setProviderUuid("pu-old-" + UUID.randomUUID());
        order.setCheckoutUrl(checkoutUrl);
        order.setExpiresAt(expiresAt);
        order.setCreatedAt(Instant.now());
        orders.put(order.getId(), order);
        return order;
    }

    private Plan plan(String code, PlanType type, Integer durationDays) {
        Plan plan = new Plan();
        plan.setId(UUID.randomUUID());
        plan.setCode(code);
        plan.setDisplayName(code);
        plan.setType(type);
        plan.setDurationDays(durationDays);
        plan.setActive(true);
        plansByCode.put(code, plan);
        return plan;
    }

    private void price(String code, String currency, String amount) {
        PlanPrice price = new PlanPrice();
        price.setId(UUID.randomUUID());
        price.setPlan(plansByCode.get(code));
        price.setCurrency(currency);
        price.setAmount(new BigDecimal(amount));
        price.setActive(true);
        pricesByPlanCurrency.put(plansByCode.get(code).getId() + ":" + currency, price);
    }

    private static User user() {
        User u = new User();
        u.setId(UUID.randomUUID());
        return u;
    }

    @SuppressWarnings("unchecked")
    private PlanRepository planRepo() {
        return (PlanRepository) Proxy.newProxyInstance(
                PlanRepository.class.getClassLoader(), new Class<?>[]{PlanRepository.class},
                (proxy, method, args) -> method.getName().equals("findByCode")
                        ? Optional.ofNullable(plansByCode.get((String) args[0]))
                        : (Optional.class.isAssignableFrom(method.getReturnType()) ? Optional.empty() : null));
    }

    @SuppressWarnings("unchecked")
    private PlanPriceRepository planPriceRepo() {
        return (PlanPriceRepository) Proxy.newProxyInstance(
                PlanPriceRepository.class.getClassLoader(), new Class<?>[]{PlanPriceRepository.class},
                (proxy, method, args) -> method.getName().equals("findByPlan_IdAndCurrency")
                        ? Optional.ofNullable(pricesByPlanCurrency.get(args[0] + ":" + args[1]))
                        : (Optional.class.isAssignableFrom(method.getReturnType()) ? Optional.empty() : null));
    }

    @SuppressWarnings("unchecked")
    private OrderRepository orderRepo() {
        return (OrderRepository) Proxy.newProxyInstance(
                OrderRepository.class.getClassLoader(), new Class<?>[]{OrderRepository.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "save", "saveAndFlush" -> {
                            Order o = (Order) args[0];
                            if (o.getId() == null) o.setId(UUID.randomUUID());
                            if (o.getCreatedAt() == null) o.setCreatedAt(Instant.now());
                            o.setUpdatedAt(Instant.now());
                            orders.put(o.getId(), o);
                            return o;
                        }
                        case "flush" -> { return null; }
                        case "findById", "findByIdForUpdate" -> {
                            return Optional.ofNullable(orders.get((UUID) args[0]));
                        }
                        case "findFirstByUser_IdAndStatusIn" -> {
                            UUID uid = (UUID) args[0];
                            Collection<OrderStatus> statuses = (Collection<OrderStatus>) args[1];
                            return orders.values().stream()
                                    .filter(o -> o.getUser().getId().equals(uid) && statuses.contains(o.getStatus()))
                                    .findFirst();
                        }
                        case "findByUser_IdOrderByCreatedAtDesc" -> {
                            UUID uid = (UUID) args[0];
                            return orders.values().stream()
                                    .filter(o -> o.getUser().getId().equals(uid))
                                    .sorted(Comparator.comparing(Order::getCreatedAt).reversed())
                                    .toList();
                        }
                        default -> {
                            return Optional.class.isAssignableFrom(method.getReturnType()) ? Optional.empty()
                                    : (List.class.isAssignableFrom(method.getReturnType()) ? List.of() : null);
                        }
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private OrderStatusHistoryRepository historyRepo() {
        return (OrderStatusHistoryRepository) Proxy.newProxyInstance(
                OrderStatusHistoryRepository.class.getClassLoader(), new Class<?>[]{OrderStatusHistoryRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        OrderStatusHistory h = (OrderStatusHistory) args[0];
                        if (h.getCreatedAt() == null) h.setCreatedAt(Instant.now());
                        history.add(h);
                        yield h;
                    }
                    default -> Optional.class.isAssignableFrom(method.getReturnType()) ? Optional.empty()
                            : (List.class.isAssignableFrom(method.getReturnType()) ? List.of() : null);
                });
    }

    private PaymentProvider provider() {
        return new PaymentProvider() {
            @Override public String id() { return "multicard"; }
            @Override public CheckoutSession createCheckout(Order order) { return new CheckoutSession("pu-1", "https://checkout"); }
            @Override public ProviderPayment fetchPayment(String providerUuid) { fetchCalls.incrementAndGet(); return new ProviderPayment(ProviderStatus.PENDING, "uzcard", 100L, null); }
            @Override public void cancelCheckout(String providerUuid) { cancelCalls.add(providerUuid); }
        };
    }

    private EntitlementService entitlementService() {
        return new EntitlementService(null, null, null,
                new BillingProperties(Duration.ofDays(7), "UZS", "UZ", Duration.ofDays(5))) {
            @Override
            public void extend(UUID userId, Duration granted, boolean paid,
                               GrantSource source, UUID orderId, UUID adminId, String reason) {
                extendCalls.incrementAndGet();
            }

            @Override
            public boolean hasPaidAccessBeyondRenewalWindow(User user) {
                return paidAccessBeyondRenewal;
            }
        };
    }

    private static PaymentProperties props() {
        MulticardProperties mc = new MulticardProperties(
                "https://dev-mesh.multicard.uz", "app", "secret", "store-1",
                "https://cb", "https://ret", Duration.ofMinutes(30), "195.158.26.90", "ru", false);
        return new PaymentProperties(Duration.ofMinutes(1), mc);
    }
}
