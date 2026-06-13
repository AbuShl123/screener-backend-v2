# User Roles & Privileges — Implementation Plan (ADMIN)

## Scope

Implement the `USER` / `ADMIN` role model. This is a small, self-contained feature: it adds the
role, enforces it on monitoring endpoints, and bootstraps the two existing production admins. It
also fixes a live exposure — `/api/monitoring/**` is currently readable by any authenticated user.

This plan is **only about roles and privileges** — it does not touch billing, payments, or
subscriptions, and nothing here depends on those modules.

---

## How roles & privileges are distributed and controlled

### Where the role lives
- The role is a field on the `User` entity (`user.role`, `UserRole` enum, `@Enumerated(STRING)`).
  It is the single persistent source of truth.
- At login, `JwtService.generateAccessToken` already embeds `role` as a JWT claim
  (`JwtService.java:42`). The access token therefore carries the role for its 3-hour lifetime.

### How the role becomes an authority
- `JwtAuthenticationFilter` already maps the claim to a Spring authority:
  `new SimpleGrantedAuthority("ROLE_" + user.role())` (`JwtAuthenticationFilter.java:38`).
  So a token minted for an `ADMIN` user produces `ROLE_ADMIN` with **zero filter changes**.
- This is the only place authorities are created, so it is the single seam through which all role
  enforcement flows (REST today, WebSocket reads the same claim independently).

### How privileges are controlled
- **REST**: `SecurityConfig` request matchers gate URL prefixes by authority. Adding
  `.requestMatchers("/api/monitoring/**").hasRole("ADMIN")` is the entire enforcement change.
  `hasRole("ADMIN")` matches the `ROLE_ADMIN` authority (Spring prepends `ROLE_` internally).
- **Granularity**: today the only ADMIN-gated surface is `/api/monitoring/**`. The role check is the
  extension point — future admin-only endpoints either join that matcher or use method-level
  `@PreAuthorize("hasRole('ADMIN')")`. We use URL matchers now (one line, centralized, matches the
  existing config style).

### Propagation delay (intended behaviour, documented not solved)
Because the role is baked into the JWT at mint time, **promoting a user to ADMIN only takes effect
on their next login / token refresh** (≤ 3h for an active access token, or immediately if they
re-login). For the bootstrap case this is irrelevant — admins are promoted at startup, before they
authenticate. Noted so it is not mistaken for a bug later. No per-request DB lookup is added (the
filter is deliberately DB-free; see `CURRENT_STATE.md` → `JwtAuthenticationFilter`).

---

## Where the admin email list lives

The bootstrap needs a list of emails to promote, and that list **must not be committed to the
repo** — otherwise admin identities are public and the list can't change without a code change.

**Decision: an environment variable referenced from `application.yml` with an empty default.** This
matches the project's existing 12-factor pattern — `JWT_SECRET`, `DB_PASSWORD`, `DB_URL` are all
already env-driven in `application.yml`. The repo only ever contains the empty placeholder; the real
emails live in the deployment environment (systemd `Environment=`, Docker `--env`, or a gitignored
`.env`), never in git. Editing the admin set is an env change + restart — no code change.

```yaml
# application.yml
screener:
  admin:
    # Comma-separated emails promoted to ADMIN on startup. Empty in the repo on purpose —
    # the real list is supplied via the SCREENER_ADMIN_EMAILS environment variable in the
    # deployment environment, so admin identities are never committed.
    emails: "${SCREENER_ADMIN_EMAILS:}"
```

### Format of `SCREENER_ADMIN_EMAILS`
A **comma-separated list of email addresses**, no surrounding brackets or quotes:

```
SCREENER_ADMIN_EMAILS=owner@example.com,client@example.com
```

Spring Boot natively splits a comma-separated property value into a `List<String>` when binding it
to `AdminProperties.emails` — no custom parsing needed. A single email (`owner@example.com`) binds
to a one-element list; an empty/unset variable binds to an empty list (no promotions run).
Whitespace around entries is tolerated — `AdminBootstrap` trims each email before lookup — but the
canonical form is no spaces.

---

## Code changes

### 1. Extend the enum
`user/UserRole.java`
```java
public enum UserRole {
    USER,
    ADMIN
}
```
No DB schema change — the column is already `@Enumerated(STRING)`; `ADMIN` is a purely additive
value. (Confirmed against `User.java:34`.)

### 2. Config properties record
`config/AdminProperties.java` (new)
```java
package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Bootstrap admin configuration. The email list is supplied via the SCREENER_ADMIN_EMAILS
 * environment variable (empty by default) so admin identities are never committed to the repo.
 */
@ConfigurationProperties(prefix = "screener.admin")
public record AdminProperties(List<String> emails) {

    public AdminProperties {
        emails = emails == null ? List.of() : emails;
    }
}
```
Spring binds the comma-separated `"${SCREENER_ADMIN_EMAILS:}"` value into `List<String>`
automatically. An empty/blank env var binds to an empty list.

Register it: add `AdminProperties.class` to the `@EnableConfigurationProperties({...})` set in
`config/WebClientConfig.java:30` (the project's single registration site).

### 3. Lock down monitoring — `config/SecurityConfig.java`
Add one matcher **before** `anyRequest()` (order matters — most specific first):
```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
        .requestMatchers("/ws").permitAll()
        .requestMatchers("/api/monitoring/**").hasRole("ADMIN")   // ← ADMIN-only
        .anyRequest().authenticated()
)
```
No `@EnableMethodSecurity` needed — URL matchers are sufficient and centralized.

### 4. Bootstrap runner — `user/AdminBootstrap.java` (new)
Promotes any *existing* user whose email is in the configured list to `ADMIN` on startup.
Idempotent, in-place `UPDATE`, touches only the `role` field, never creates accounts.
```java
package dev.abu.screener_backend.user;

import dev.abu.screener_backend.config.AdminProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

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
```
Notes:
- `findByEmail` already exists (`UserRepository.java`). Confirm whether stored emails are
  normalized; if registration lower-cases emails, the `toLowerCase` here matches — otherwise compare
  as stored. **Verify against `AuthService.register` before finalizing** (one-line check).
- Dirty checking inside `@Transactional` issues the `UPDATE`; no explicit `save` required.
- Runs once per boot, survives redeploys, extends trivially (add an email to the env var, restart).

### 5. Config + docs
- `application.yml`: add the `screener.admin.emails` block (see above).
- Deployment env: set `SCREENER_ADMIN_EMAILS=owner@example.com,client@example.com` (the two real
  users). **Not committed.**

---

## Migration safety (existing live users)

- No schema migration. The `role` column already exists and is `STRING`-mapped; adding the `ADMIN`
  enum constant requires no DDL.
- The runner performs only `UPDATE users SET role='ADMIN'` on matched rows — **no insert, no delete,
  no reset.** Accounts, passwords, refresh tokens are untouched.
- Idempotent: re-running on every boot is a no-op once roles are set.
- If a configured email has not registered yet, it is logged at WARN and skipped — no account is
  created. (When that person later registers as `USER`, the next restart promotes them. If
  promotion must be immediate on registration, that is a future enhancement, not needed for the two
  known admins who already exist.)

---

## Testing

- **Unit** `AdminBootstrapTest` (plain JUnit, hand-rolled `UserRepository` stub in the project's
  no-Mockito style — see `UserFeedRegistryTest`): promotes a matching `USER` → `ADMIN`; leaves an
  already-`ADMIN` user unchanged; skips an unknown email without throwing; ignores blank/empty
  entries.
- **Manual / integration**:
  - `GET /api/monitoring/presence` with a `USER` token → `403`.
  - Same with an `ADMIN` token (re-login after promotion to mint a `ROLE_ADMIN` claim) → `200`.
  - `GET /api/tickers`, `/api/rules` with a `USER` token still `200` (only monitoring is gated).
- Smoke: `ScreenerBackendApplicationTests` still loads the context with the new property + runner.

---

## Files touched (summary)

| File | Change |
|---|---|
| `user/UserRole.java` | add `ADMIN` |
| `config/AdminProperties.java` | **new** — bind `screener.admin.emails` |
| `config/WebClientConfig.java` | register `AdminProperties` in `@EnableConfigurationProperties` |
| `config/SecurityConfig.java` | `/api/monitoring/**` → `hasRole("ADMIN")` |
| `user/AdminBootstrap.java` | **new** — `ApplicationRunner` promotes configured emails |
| `application.yml` | add `screener.admin.emails: "${SCREENER_ADMIN_EMAILS:}"` |
| `MonitoringController` javadoc | update "any authenticated user" → "ADMIN-only" |
| deployment env | set `SCREENER_ADMIN_EMAILS` (not committed) |
| `CURRENT_STATE.md` | document the new classes after implementation |

---

## Build order within this slice

1. `UserRole.ADMIN` + `AdminProperties` + register it.
2. `SecurityConfig` matcher (the exposure fix).
3. `AdminBootstrap` + `application.yml` + env var.
4. Tests + `CURRENT_STATE.md` + javadoc updates.

Each step compiles independently; step 2 alone already closes the monitoring exposure even before
any admin exists (it would simply lock everyone out of monitoring until an admin is bootstrapped —
so ship 2 and 3 together).
