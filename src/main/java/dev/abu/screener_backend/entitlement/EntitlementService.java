package dev.abu.screener_backend.entitlement;

import dev.abu.screener_backend.config.BillingProperties;
import dev.abu.screener_backend.entitlement.dto.AdminGiftResult;
import dev.abu.screener_backend.entitlement.dto.EntitlementLedgerEntry;
import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.payment.OrderService;
import dev.abu.screener_backend.payment.dto.OrderDetailsEntry;
import dev.abu.screener_backend.user.User;
import dev.abu.screener_backend.user.UserRole;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * The single mutation and read path for user entitlement (access).
 *
 * <p>All grants flow through {@link #extend} (shared by trial seeding and purchases) using the
 * stacking rule {@code accessExpiresAt = max(now, accessExpiresAt) + granted} — buying while still
 * active adds time on top of the remaining period. Every grant (trial, purchase, future admin gift)
 * writes one append-only {@link EntitlementLedger} row so access changes are auditable now that real
 * money grants access.
 *
 * <p>State is derived on read; nothing access-related is stored as a status column.
 */
@Service
@Transactional
public class EntitlementService {

    private final UserEntitlementRepository repository;
    private final EntitlementLedgerRepository ledgerRepository;
    private final OrderService orderService;
    private final Duration trialDuration;
    private final Duration renewalWindow;

    /**
     * {@code orderService} is injected {@code @Lazy} to break the construction cycle: {@link OrderService}
     * depends on this service (to grant access on payment), and this service depends on it only at read
     * time (to resolve a ledger row's order into an {@link OrderDetailsEntry}). The lazy proxy defers that
     * back-edge until first use. (Same pattern the codebase uses elsewhere to break a bean cycle.)
     */
    public EntitlementService(UserEntitlementRepository repository,
                              EntitlementLedgerRepository ledgerRepository,
                              @Lazy OrderService orderService,
                              BillingProperties props) {
        this.repository = repository;
        this.ledgerRepository = ledgerRepository;
        this.orderService = orderService;
        this.trialDuration = props.trialDuration();
        this.renewalWindow = props.renewalWindow();
    }

    /**
     * Seeds the one-week free trial for a brand-new user. Called from the registration transaction so
     * every account starts in {@code TRIAL} and the 1:1 invariant always holds. Writes a {@code TRIAL}
     * ledger row.
     */
    public void startTrial(User user) {
        Instant next = Instant.now().plus(trialDuration);
        UserEntitlement entitlement = new UserEntitlement();
        entitlement.setUser(user);
        entitlement.setAccessExpiresAt(next);
        entitlement.setHasPaid(false);
        repository.save(entitlement);
        ledgerRepository.save(new EntitlementLedger(
                user.getId(), GrantSource.TRIAL, trialDuration.toSeconds(),
                null, next, null, null, null));
    }

    /**
     * Extends access by {@code granted}, stacking on top of any remaining time, and writes a ledger
     * row. Sets {@code hasPaid} when the grant is a paid purchase. The single mutation path for trial
     * top-ups and purchases.
     *
     * @param source   what kind of grant this is (TRIAL/PURCHASE/ADMIN)
     * @param orderId  the paying order for PURCHASE grants; {@code null} otherwise
     * @param adminId  the acting admin for ADMIN grants; {@code null} otherwise
     * @param reason   optional free-form note for the ledger
     */
    public void extend(
            UUID userId,
            Duration granted,
            boolean paid,
            GrantSource source,
            UUID orderId,
            UUID adminId,
            String reason
    ) {
        UserEntitlement entitlement = repository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Entitlement not found"));
        Instant now = Instant.now();
        Instant prev = entitlement.getAccessExpiresAt();
        Instant base = (prev == null || prev.isBefore(now)) ? now : prev;
        Instant next = base.plus(granted);
        entitlement.setAccessExpiresAt(next);
        if (paid) {
            entitlement.setHasPaid(true);
        }
        repository.save(entitlement);
        ledgerRepository.save(new EntitlementLedger(
                userId, source, granted.toSeconds(), prev, next, orderId, adminId, reason));
    }

    /**
     * Admin gift: pushes each listed user's {@code accessExpiresAt} forward by {@code granted}, writing
     * one {@code ADMIN} {@link EntitlementLedger} row per user (stamped with {@code adminId}) so the
     * grant is auditable. Every id is validated to exist up front, so the whole batch is atomic — a
     * single unknown user id rejects the request with {@code 404} and grants nobody (the surrounding
     * transaction also rolls back any partial application). Duplicate ids are de-duplicated so a user is
     * gifted at most once. The gift is <strong>unpaid</strong> (it never sets {@code hasPaid}): a gifted
     * user reports as {@code TRIAL} and remains free to buy a paid plan.
     *
     * @param userIds users to gift (de-duplicated; must all exist)
     * @param granted access to add on top of each user's remaining time (must be positive)
     * @param adminId the acting admin, recorded on every ledger row
     * @param reason  optional free-form note stored on every ledger row
     * @return one result per gifted user, in request order, with the new expiry
     */
    public List<AdminGiftResult> giftAccess(Collection<UUID> userIds, Duration granted, UUID adminId, String reason) {
        if (userIds == null || userIds.isEmpty()) {
            throw ApiException.badRequest("userIds required");
        }
        if (granted == null || granted.isZero() || granted.isNegative()) {
            throw ApiException.badRequest("addPeriodDays must be > 0");
        }
        // De-duplicate, preserving order, so a repeated id is gifted once.
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(userIds));
        // Validate every id up front so the batch is all-or-nothing and the error lists every miss.
        List<UUID> missing = ids.stream()
                .filter(id -> repository.findByUserId(id).isEmpty())
                .toList();
        if (!missing.isEmpty()) {
            throw ApiException.notFound("Unknown user id(s): " + missing);
        }
        List<AdminGiftResult> results = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            extend(id, granted, false, GrantSource.ADMIN, null, adminId, reason);
            Instant newExpiry = repository.findByUserId(id).orElseThrow().getAccessExpiresAt();
            results.add(new AdminGiftResult(id, newExpiry));
        }
        return results;
    }

    /**
     * Derives the current access state for the frontend. Admins short-circuit to
     * {@code ADMIN}/{@code null}; non-admins are evaluated against their stored facts.
     */
    @Transactional(readOnly = true)
    public EntitlementView currentState(User user) {
        if (user.getRole() == UserRole.ADMIN) {
            return new EntitlementView(AccessState.ADMIN, null);
        }
        return repository.findByUserId(user.getId())
                .map(e -> new EntitlementView(
                        deriveState(user.getRole(), e.getAccessExpiresAt(), e.isHasPaid()),
                        e.getAccessExpiresAt()))
                .orElse(new EntitlementView(AccessState.EXPIRED, null));
    }

    /**
     * Pure derivation of the presentation-only {@link AccessState} from a user's role and stored access
     * facts — the single place the {@code ADMIN}/{@code EXPIRED}/{@code ACTIVE}/{@code TRIAL} rule lives,
     * so read paths that already hold the facts (e.g. the admin user listing) don't re-implement it.
     * Admins short-circuit to {@code ADMIN}; a null or past expiry is {@code EXPIRED}; otherwise
     * {@code hasPaid} distinguishes {@code ACTIVE} from {@code TRIAL}.
     */
    public static AccessState deriveState(UserRole role, Instant accessExpiresAt, boolean hasPaid) {
        if (role == UserRole.ADMIN) {
            return AccessState.ADMIN;
        }
        Instant now = Instant.now();
        if (accessExpiresAt == null || !now.isBefore(accessExpiresAt)) {
            return AccessState.EXPIRED;
        }
        return hasPaid ? AccessState.ACTIVE : AccessState.TRIAL;
    }

    /**
     * Whether the user may use the screener right now. Enforced at connect time by the WebSocket
     * {@code @OnOpen} gate and by every authenticated {@code /api/rules} endpoint.
     */
    @Transactional(readOnly = true)
    public boolean hasAccess(User user) {
        if (user.getRole() == UserRole.ADMIN) {
            return true;
        }
        Instant now = Instant.now();
        return repository.findByUserId(user.getId())
                .map(e -> e.getAccessExpiresAt() != null && now.isBefore(e.getAccessExpiresAt()))
                .orElse(false);
    }

    /**
     * Whether the user holds a paid subscription that is <em>not yet eligible for renewal</em> — i.e.
     * paid access that expires further out than the configured {@code renewalWindow} (default 5 days).
     * This is the condition that blocks a redundant fixed-plan purchase.
     *
     * <p>Deliberately distinct from {@link #hasAccess}: a trial user "has access" but holds no paid
     * subscription, and admins bypass entitlement entirely (they are not subscribers). Returns
     * {@code false} — so the purchase is <em>allowed</em> — for trial users (mid-conversion), expired
     * users, admins, and a paid subscriber whose access expires within the renewal window (so they may
     * renew before it lapses). Pay-by-days is exempt at the call site regardless of this value.
     */
    @Transactional(readOnly = true)
    public boolean hasPaidAccessBeyondRenewalWindow(User user) {
        if (user.getRole() == UserRole.ADMIN) {
            return false; // admins aren't subscribers — never gated from creating an order
        }
        Instant renewableFrom = Instant.now().plus(renewalWindow);
        return repository.findByUserId(user.getId())
                .map(e -> e.isHasPaid()
                        && e.getAccessExpiresAt() != null
                        && e.getAccessExpiresAt().isAfter(renewableFrom))
                .orElse(false);
    }

    /**
     * The user's access-granting events from the ledger, newest first — every push of
     * {@code accessExpiresAt} forward (trial seed, paid purchase, future admin grant). A {@code PURCHASE}
     * row's {@code order_id} is resolved to a full {@link OrderDetailsEntry}; trial/admin rows carry a
     * {@code null} order (no order id on the ledger row).
     */
    @Transactional(readOnly = true)
    public List<EntitlementLedgerEntry> listAccessHistory(UUID userId) {
        return ledgerRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toLedgerEntry).toList();
    }

    private EntitlementLedgerEntry toLedgerEntry(EntitlementLedger ledger) {
        OrderDetailsEntry order = ledger.getOrderId() == null ? null
                : orderService.findOrderDetails(ledger.getOrderId()).orElse(null);
        return new EntitlementLedgerEntry(
                ledger.getSource(), ledger.getGrantedDurationSeconds(),
                ledger.getPreviousExpiresAt(), ledger.getNewExpiresAt(),
                order, ledger.getReason(), ledger.getCreatedAt());
    }
}
