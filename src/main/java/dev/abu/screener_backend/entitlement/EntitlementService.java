package dev.abu.screener_backend.entitlement;

import dev.abu.screener_backend.config.BillingProperties;
import dev.abu.screener_backend.error.ApiException;
import dev.abu.screener_backend.user.User;
import dev.abu.screener_backend.user.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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
    private final Duration trialDuration;

    public EntitlementService(UserEntitlementRepository repository,
                              EntitlementLedgerRepository ledgerRepository,
                              BillingProperties props) {
        this.repository = repository;
        this.ledgerRepository = ledgerRepository;
        this.trialDuration = props.trialDuration();
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
     * Derives the current access state for the frontend. Admins short-circuit to
     * {@code ADMIN}/{@code null}; non-admins are evaluated against their stored facts.
     */
    @Transactional(readOnly = true)
    public EntitlementView currentState(User user) {
        if (user.getRole() == UserRole.ADMIN) {
            return new EntitlementView(AccessState.ADMIN, null);
        }
        Instant now = Instant.now();
        return repository.findByUserId(user.getId())
                .map(e -> {
                    Instant expiresAt = e.getAccessExpiresAt();
                    if (expiresAt == null || !now.isBefore(expiresAt)) {
                        return new EntitlementView(AccessState.EXPIRED, expiresAt);
                    }
                    return new EntitlementView(e.isHasPaid() ? AccessState.ACTIVE : AccessState.TRIAL, expiresAt);
                })
                .orElse(new EntitlementView(AccessState.EXPIRED, null));
    }

    /**
     * Whether the user may use the screener right now. Provided for the enforcement plan to consume;
     * not wired into any gate yet.
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
}
