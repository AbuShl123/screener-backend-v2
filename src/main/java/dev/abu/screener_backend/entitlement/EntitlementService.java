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
 * <p>All grants flow through {@link #extend} (shared by trial seeding and, later, purchases) using
 * the stacking rule {@code accessExpiresAt = max(now, accessExpiresAt) + granted} — buying while
 * still active adds time on top of the remaining period. There is no audit ledger.
 *
 * <p>State is derived on read; nothing access-related is stored as a status column.
 */
@Service
@Transactional
public class EntitlementService {

    private final UserEntitlementRepository repository;
    private final Duration trialDuration;

    public EntitlementService(UserEntitlementRepository repository, BillingProperties props) {
        this.repository = repository;
        this.trialDuration = props.trialDuration();
    }

    /**
     * Seeds the one-week free trial for a brand-new user. Called from the registration transaction so
     * every account starts in {@code TRIAL} and the 1:1 invariant always holds.
     */
    public void startTrial(User user) {
        UserEntitlement entitlement = new UserEntitlement();
        entitlement.setUser(user);
        entitlement.setAccessExpiresAt(Instant.now().plus(trialDuration));
        entitlement.setHasPaid(false);
        repository.save(entitlement);
    }

    /**
     * Extends access by {@code granted}, stacking on top of any remaining time. Sets {@code hasPaid}
     * when the grant is a paid purchase. The single mutation path for trial top-ups and purchases.
     */
    public void extend(UUID userId, Duration granted, boolean paid) {
        UserEntitlement entitlement = repository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Entitlement not found"));
        Instant now = Instant.now();
        Instant current = entitlement.getAccessExpiresAt();
        Instant base = (current == null || current.isBefore(now)) ? now : current;
        entitlement.setAccessExpiresAt(base.plus(granted));
        if (paid) {
            entitlement.setHasPaid(true);
        }
        repository.save(entitlement);
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
