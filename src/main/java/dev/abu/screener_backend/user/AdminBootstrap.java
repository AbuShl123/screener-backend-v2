package dev.abu.screener_backend.user;

import dev.abu.screener_backend.config.AdminProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Promotes any <em>existing</em> user whose email is in the configured {@link AdminProperties#emails()}
 * list to {@link UserRole#ADMIN} on startup.
 *
 * <p>Idempotent: it performs an in-place {@code UPDATE} of the {@code role} field only — it never
 * creates accounts and never touches passwords or refresh tokens. Re-running on every boot is a no-op
 * once roles are set. If a configured email has not registered yet it is logged at WARN and skipped.
 *
 * <p>Because the role is baked into the JWT at mint time, a promotion only takes effect on the user's
 * next login / token refresh. For the bootstrap case this is irrelevant — admins are promoted at
 * startup, before they authenticate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final AdminProperties adminProperties;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (String raw : adminProperties.emails()) {
            String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (email.isEmpty()) continue;

            userRepository.findByEmail(email).ifPresentOrElse(user -> {
                if (user.getRole() != UserRole.ADMIN) {
                    user.setRole(UserRole.ADMIN);          // dirty-checked UPDATE within @Transactional
                    log.info("Promoted user to ADMIN: {}", email);
                } else {
                    log.debug("User already ADMIN: {}", email);
                }
            }, () -> log.warn("Admin email not found among registered users (skipping): {}", email));
        }
    }
}
