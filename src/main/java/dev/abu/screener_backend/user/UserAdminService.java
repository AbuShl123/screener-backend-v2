package dev.abu.screener_backend.user;

import dev.abu.screener_backend.entitlement.AccessState;
import dev.abu.screener_backend.entitlement.EntitlementService;
import dev.abu.screener_backend.entitlement.UserEntitlement;
import dev.abu.screener_backend.entitlement.UserEntitlementRepository;
import dev.abu.screener_backend.user.dto.AdminUserPage;
import dev.abu.screener_backend.user.dto.AdminUserView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ADMIN-only user directory. Backs {@code GET /api/admin/users} — a paginated listing an admin browses
 * to find the {@code id}s to hand to {@code POST /api/admin/entitlement/gift}.
 *
 * <p>Each row carries the user's derived access state, batch-joined against {@code user_entitlement}
 * (one extra query per page, not per row) so the listing shows who is on trial / paid / expired.
 */
@Service
@Transactional(readOnly = true)
public class UserAdminService {

    /** Guards against an unbounded page a client could request via {@code ?size=}. */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final UserRepository userRepository;
    private final UserEntitlementRepository entitlementRepository;

    public UserAdminService(UserRepository userRepository,
                            UserEntitlementRepository entitlementRepository) {
        this.userRepository = userRepository;
        this.entitlementRepository = entitlementRepository;
    }

    public AdminUserPage listUsers(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<User> users = userRepository.findAll(pageable);
        List<UUID> ids = users.getContent().stream().map(User::getId).toList();
        Map<UUID, UserEntitlement> entitlements = entitlementRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(UserEntitlement::getUserId, Function.identity()));

        List<AdminUserView> views = users.getContent().stream()
                .map(u -> toView(u, entitlements.get(u.getId())))
                .toList();

        return new AdminUserPage(views, users.getNumber(), users.getSize(),
                users.getTotalElements(), users.getTotalPages());
    }

    private static AdminUserView toView(User u, UserEntitlement e) {
        Instant expiresAt = e == null ? null : e.getAccessExpiresAt();
        boolean hasPaid = e != null && e.isHasPaid();
        AccessState state = EntitlementService.deriveState(u.getRole(), expiresAt, hasPaid);
        return new AdminUserView(
                u.getId(), u.getFirstName(), u.getLastName(), u.getEmail(), u.getRole().name(),
                u.isEmailVerified(), u.isEnabled(), u.getCreatedAt(), state, expiresAt, hasPaid,
                u.getLastSeenAt());
    }
}
