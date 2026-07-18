package dev.abu.screener_backend.user;

import dev.abu.screener_backend.user.dto.AdminUserPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only user directory. Mounted under {@code /api/admin/users}; the {@code /api/admin/**} matcher
 * in {@code SecurityConfig} restricts it to the {@code ADMIN} role.
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    /**
     * Paginated listing of all users (newest first), each with derived access state, so an admin can
     * pick the {@code id}s to gift access to.
     */
    @GetMapping
    public AdminUserPage listUsers(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return userAdminService.listUsers(page, size);
    }
}
