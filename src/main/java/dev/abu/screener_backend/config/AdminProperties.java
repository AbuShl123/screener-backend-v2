package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Bootstrap admin configuration. The email list is supplied via the {@code SCREENER_ADMIN_EMAILS}
 * environment variable (empty by default) so admin identities are never committed to the repo.
 *
 * <p>Spring binds the comma-separated {@code "${SCREENER_ADMIN_EMAILS:}"} property value into a
 * {@code List<String>} automatically. An empty/unset variable binds to an empty list (no promotions
 * run). Editing the admin set is an env change + restart — no code change.
 */
@ConfigurationProperties(prefix = "screener.admin")
public record AdminProperties(List<String> emails) {

    public AdminProperties {
        emails = emails == null ? List.of() : emails;
    }
}
