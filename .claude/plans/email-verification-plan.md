# Email Verification — Implementation Plan

> **Amendment (as-built):** the verify step was changed from a passive `GET /api/auth/verify-email`
> that consumed the token and 302-redirected, to a **Confirm-button flow**. The email link now points
> at the SPA verify page (`screener.email.verify-page-url` + `?token=<raw>`; the old `verify-base-url`
> / `frontend-redirect-url` props were collapsed into this one). Only the user's Confirm click POSTs
> the token to `POST /api/auth/verify-email`, which consumes it and returns HTTP 200 +
> `VerifyEmailResponse(status)` (`success|expired|invalid`). This prevents email link scanners
> (Outlook Safe Links, AV prefetch) from burning the single-use token before the human clicks.
> Everything else below is as-built. `CURRENT_STATE.md` reflects the shipped design.

## Scope

Add an email-confirmation layer to registration. A new user cannot log in until they click a
verification link sent to their email. Existing (already-deployed) users are **grandfathered** —
they are treated as already verified and are never blocked.

**Motivation**: the screener is being monetized (payment + subscription modules are built, not yet
deployed). Proving the registrant controls a real mailbox before they can use — and pay for — the
product reduces fake/throwaway accounts and gives us a reachable address for billing/receipts.

**Building now**: `email_verified` flag, verification-token table + entity, verify + resend
endpoints, an `EmailService` over Spring `JavaMailSender`, register/login flow changes, two Flyway
migrations, config, tests.

**Not building now**: password-reset email (same machinery — deliberately deferred), a non-SMTP
transactional-API sender, HTML email templating engine, i18n of email copy.

---

## Locked decisions

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| 1 | Existing users | **Grandfather** (`email_verified = TRUE` for all current rows) | They've already proven the address is real; some have paid. A re-verify wall risks locking out real customers for no gain. Only *new* registrations are gated. |
| 2 | Login when unverified | **Block** | The whole point is proving the email before use. Login returns a distinct `403` so the SPA can show a "resend" affordance. |
| 3 | Verified state storage | **New `email_verified` column** (not overloading `enabled`) | `enabled` means "admin hasn't banned this account". Overloading it makes an unverified user indistinguishable from a banned one, and login couldn't tell "resend your email" from "you're banned". |
| 4 | Email sender | **Spring `JavaMailSender` (SMTP) + one thin `EmailService`** — no vendor-agnostic port | SMTP is universal on the receiving end (every mailbox accepts it). `JavaMailSender` is *already* an interface; switching SMTP providers (Gmail → SES → Mailgun) is a config change, not a code change. A `PaymentProvider`-style port would be over-engineering until/unless we adopt a non-SMTP HTTP API. |

**Also locked** (from discussion):
- **Verification token is opaque + single-use**, not a JWT. Reuse the refresh-token machinery:
  32-byte `SecureRandom` → Base64URL raw value in the link; **SHA-256 hash** stored in DB. JWTs
  can't be revoked/consumed once-only without extra state; the opaque token is simpler and safer.
- **Trial still starts at register.** Keep `entitlementService.startTrial(user)` in the register
  transaction so the 1:1 `user_entitlement` invariant always holds. A few minutes of trial clock
  before the user clicks the link is immaterial, and an unverified user can't log in to consume it
  anyway.
- **Register no longer returns tokens.** It creates the unverified user and returns a
  "verification required" body (see §5). Frontend contract change — see §11.

---

## 1. Schema changes

### 1a. `users.email_verified`

New boolean column. Grandfather every existing row to `TRUE`; new rows default to `FALSE` via
`@PrePersist` (matching the "app sets values, no DB default on the baselined `users` table"
convention established in V1).

### 1b. `email_verification_tokens` table

Mirrors `refresh_tokens`: one active token per user (delete-then-insert on register/resend), the
raw value never persisted — only its SHA-256 hash. Single-use is enforced by **deleting the row on
successful verification** (same idiom as refresh tokens), so no `consumed_at` column is needed.

```
email_verification_tokens
  id          UUID PK
  user_id     UUID NOT NULL  FK → users(id) ON DELETE CASCADE
  token_hash  VARCHAR(255) NOT NULL UNIQUE
  expires_at  TIMESTAMPTZ NOT NULL
  created_at  TIMESTAMPTZ NOT NULL
  INDEX (user_id)
```

`created_at` doubles as the resend-cooldown clock (§7).

---

## 2. Migrations

Two new Flyway migrations, numbered after the current head (`V11`). Both run on the baselined
prod/local chain and on a fresh chain identically (they touch only post-baseline structure).

### `V12__add_email_verified_to_users.sql`

Add the column nullable → backfill (grandfather) → enforce `NOT NULL`, retaining **no DB default**
(consistent with the baselined `users` table; the app sets the value on insert):

```sql
ALTER TABLE users ADD COLUMN email_verified BOOLEAN;
-- Grandfather every existing account: they are already trusted (some have paid).
UPDATE users SET email_verified = TRUE;
ALTER TABLE users ALTER COLUMN email_verified SET NOT NULL;
```

### `V13__create_email_verification_tokens.sql`

New table — follows the post-V7 convention for fresh tables (inline FK cascade + index + DB
defaults, as in `V8__create_orders.sql`):

```sql
CREATE TABLE email_verification_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens(user_id);
```

> **Prod/local safety**: neither table nor column exists in any environment today
> (`email_verified` is brand new; the token table is new), so no adopt/align dance like V1–V7 is
> needed — plain `CREATE`/`ALTER` on the baselined chain. Take the usual `pg_dump` before deploy.

---

## 3. Entities & repositories

### `User` (edit)
- Add `private boolean emailVerified;` mapped to `email_verified` (`@Column(nullable = false)`).
- In `@PrePersist`, add `emailVerified = false;` (alongside the existing `enabled = true;`). Every
  freshly registered user starts unverified; grandfathered rows already carry `TRUE` from V12 and
  `@PrePersist` never runs for them.

### `EmailVerificationToken` (new, `user` package)
Structural mirror of `RefreshToken`: `id`, eager `@ManyToOne User`, `tokenHash` (unique),
`expiresAt`, `createdAt` (`@PrePersist` sets it).

### `EmailVerificationTokenRepository` (new)
`JpaRepository<EmailVerificationToken, UUID>`:
- `findByTokenHash(String)` — verify lookup.
- `deleteByUserId(UUID)` via `@Modifying @Query` — delete-then-insert on register/resend
  (one active token per user), mirroring `RefreshTokenRepository.deleteByUserId`.
- `findFirstByUser_IdOrderByCreatedAtDesc(UUID)` — for the resend cooldown check (§7).

---

## 4. Token generation (reuse `JwtService`)

No new crypto. `JwtService.generateRawRefreshToken()` is just a 32-byte `SecureRandom` Base64URL
value (not JWT-specific), and `hashToken()` is SHA-256 hex — both reused verbatim for verification
tokens. (Optional cosmetic: add pass-through aliases `generateRawToken()` / keep `hashToken()` if we
want the intent to read clearly at the call site; not required.)

Token TTL from config (`screener.email.verification-token-expiry`, default `PT24H`).

---

## 5. Register flow (`AuthService.register` — edit)

New sequence (all DB writes in the existing `@Transactional`):

1. Validate fields + email uniqueness (unchanged).
2. Create + save the `User` (now persists `email_verified = false`).
3. `entitlementService.startTrial(user)` (unchanged — invariant).
4. `refreshTokenRepository` is **not** touched — no token pair is issued.
5. Create the verification token: `deleteByUserId` (none yet, but keeps the one-per-user
   invariant), generate raw + hash, save row with `expiresAt = now + verificationTokenExpiry`.
6. Publish a `RegistrationEmailEvent(userId, rawToken, email, firstName)` — the **raw** token is
   carried in-memory only (never persisted), exactly like the raw refresh token is handed back in
   `AuthResponse`.
7. Return the new response shape.

**Response**: `register` returns `RegisterResponse(String status, String email)` with
`status = "VERIFICATION_REQUIRED"`, HTTP **202 Accepted** (request accepted; the account exists but
is not yet usable — action required). `AuthController.register` return type + `@ResponseStatus`
change accordingly.

**Why an AFTER_COMMIT async event, not an inline send**: sending mail is a blocking SMTP round-trip;
doing it inside the request/transaction would stall the Tomcat thread and — worse — could send a
link for a token row that later rolls back. We reuse the exact pattern already in the codebase
(`RuleUpdatedEvent` → `@TransactionalEventListener(AFTER_COMMIT)` in `UserFeedRegistry`):

- `RegistrationEmailEvent` — plain record, published inside the transaction.
- A listener annotated `@TransactionalEventListener(phase = AFTER_COMMIT)` **and** `@Async` invokes
  `EmailService.sendVerificationEmail(...)`. The token row is guaranteed committed before the send,
  and registration responds immediately. A send failure is logged (WARN) and is non-fatal — the user
  can trigger a resend (§7).
- Requires `@EnableAsync` (new `@Configuration`, e.g. `AsyncConfig`, with a small dedicated
  `ThreadPoolTaskExecutor` so email I/O never borrows the common pool). This executor is entirely
  separate from Disruptor/Tomcat threads — no market-data impact.

---

## 6. Login flow (`AuthService.login` — edit)

Insert the verification gate **after** the password check, so account-verification status is only
revealed to someone who already proved the password (no account enumeration):

```
verify credentials      → 401 "Invalid credentials"   (existing)
if (!user.isEnabled())  → 401 "Account disabled"       (existing — admin ban)
if (!user.isEmailVerified()) → 403 "Email not verified" (NEW, distinct status)
issue token pair                                        (existing)
```

`403` (vs the `401`s) lets the SPA distinguish "unverified — offer resend" from "bad credentials"
and "banned". Add an `ApiException.forbidden(String)` factory (the class currently has
`badRequest/notFound/conflict/unauthorized`); `GlobalExceptionHandler` already maps any
`ApiException` to its carried status, so no handler change is needed.

---

## 7. New endpoints (`AuthController` / `AuthService`)

### `GET /api/auth/verify-email?token=<raw>` — public
Opened directly by the browser from the email link (no JWT). Logic:
1. Hash the raw token, `findByTokenHash`.
2. Missing or `expiresAt < now` → **302 redirect** to
   `${frontend-redirect-url}?status=invalid` (or `expired`).
3. Valid → set `user.emailVerified = true`, **delete the token row** (single-use), **302 redirect**
   to `${frontend-redirect-url}?status=success`.

Returns a `ResponseEntity` `302` with a `Location` header — no JSON body. Configurable landing URL
(`screener.email.frontend-redirect-url`), same idea as `MULTICARD_RETURN_URL`.

> Double-click UX: a second click after success finds no token → `status=invalid`. Acceptable for
> v1; a friendlier "already verified" would require keeping a consumed marker — deferred.

### `POST /api/auth/resend-verification` — public
Body `{ "email": "..." }`. Logic:
1. Look up the user by (lowercased) email.
2. If it exists, is not yet verified, **and** the last token for that user is older than
   `screener.email.resend-cooldown` (default `PT1M`) → regenerate token (delete-then-insert) +
   publish the same `RegistrationEmailEvent`.
3. **Always return 202** with a generic body regardless of whether the email exists / was already
   verified / was on cooldown — no account enumeration, no cooldown oracle.

Both paths added to `SecurityConfig` `permitAll` (see §9).

> **Resend regenerates unconditionally** — it does not require the old token to still be valid. An
> expired or already-deleted token is fine; `deleteByUserId` + insert always mints a fresh 24h token.
> This is what makes expiry self-healing rather than a dead end.

### Verification lifecycle & the two resend paths

The token is short-lived by design (`PT24H`), so the plan must make re-sending easy from every state.
There are exactly two moments a user needs a (new) link, and **both call
`POST /api/auth/resend-verification`** — a fresh 24h token every time:

| Situation | How the user reaches resend | Backend |
|---|---|---|
| **Registered, email never arrived** (spam/typo-free but lost) | The post-register "check your inbox" screen shows a *"Didn't get it? Resend"* button | Regenerate + re-send (subject to the `PT1M` cooldown) |
| **Token expired (>24h) or user just returns later** | User attempts **login** → `403 "Email not verified"` → SPA shows the same resend button | Regenerate a fresh token + re-send |

**Design choices behind this:**
- **Login does not auto-send.** The next login attempt *surfaces* the need to verify (the `403`), but
  the email is sent only when the user clicks resend. Auto-sending on every login attempt would turn
  login into an inbox-spam vector against any registered address (resend is public + email-only, with
  no password to gate it). The manual button + cooldown + generic `202` keep it abuse-resistant.
- **No "expired" special-casing needed on the client.** Whether the token is expired, consumed, or
  never delivered, the recovery is identical: click resend. The `verify-email` endpoint's
  `?status=expired` landing (§7) can itself render a resend button, closing the loop for a user who
  clicks a stale link.
- **Cooldown vs. first-email failure.** A user who registers and immediately clicks resend hits the
  `PT1M` cooldown once; acceptable. Tunable via `screener.email.resend-cooldown` if support feedback
  says otherwise.

---

## 8. `EmailService` (new) + `JavaMailSender`

- Add `spring-boot-starter-mail` to `pom.xml`. **Note the Spring Boot 4.0 module-split lesson from
  the Flyway saga**: use the *starter* (pulls the mail autoconfiguration module), not a bare
  `jakarta.mail` dependency, or `JavaMailSender` won't be auto-configured.
- `EmailService` (thin, `@Service`): injects `JavaMailSender` + `EmailProperties`. One method now:
  `sendVerificationEmail(String toEmail, String firstName, String rawToken)` — builds the link
  `${verify-base-url}/api/auth/verify-email?token=<raw>`, composes a simple message
  (`SimpleMailMessage` for v1; upgrade to a `MimeMessage` + minimal HTML later), sends via
  `JavaMailSender`. Catches/propagates send failure to the async listener which logs WARN.
- This is the single seam we'd later reimplement if we ever move off SMTP to an HTTP API — but per
  decision #4 we do **not** introduce a `PaymentProvider`-style interface now.

---

## 9. Config

### `SecurityConfig` (edit)
Add to the `permitAll` matchers:
```
"/api/auth/verify-email", "/api/auth/resend-verification"
```
(joining the existing `/api/auth/register`, `/login`, `/refresh`).

### `application.yml` (edit)
```yaml
spring:
  mail:
    host: "${MAIL_HOST:}"
    port: "${MAIL_PORT:587}"
    username: "${MAIL_USERNAME:}"
    password: "${MAIL_PASSWORD:}"
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true

screener:
  email:
    from-address: "${MAIL_FROM:noreply@tc-screener.com}"
    from-name: "TC Screener"
    verification-token-expiry: PT24H
    resend-cooldown: PT1M
    # Public backend base URL the email link points at (hosts /api/auth/verify-email).
    verify-base-url: "${EMAIL_VERIFY_BASE_URL:}"
    # SPA landing page the verify endpoint 302-redirects to (?status=success|expired|invalid).
    frontend-redirect-url: "${EMAIL_FRONTEND_REDIRECT_URL:}"
```

### `EmailProperties` (new record, `config` package)
Bound from `screener.email.*`: `fromAddress`, `fromName`, `verificationTokenExpiry` (Duration),
`resendCooldown` (Duration), `verifyBaseUrl`, `frontendRedirectUrl`. Register in
`WebClientConfig`'s `@EnableConfigurationProperties` (where the other property records live).

### `AsyncConfig` (new)
`@Configuration @EnableAsync` with a bounded `ThreadPoolTaskExecutor` bean for the email listener.

---

## 10. Tests

- **`AuthServiceTest`** (new or extend): register persists an unverified user, seeds the trial,
  issues **no** refresh token, and publishes exactly one `RegistrationEmailEvent`; login throws
  `403` for an unverified user and succeeds once verified; login still `401`s on bad password /
  disabled *before* reaching the verification check (enumeration guard).
- **Verify endpoint**: valid token → user flips to verified + token deleted + `success` redirect;
  expired → `expired` redirect, no flip; unknown → `invalid` redirect; second use → `invalid`.
- **Resend**: within cooldown → no new token + generic 202; unknown/already-verified email →
  generic 202 with no token issued.
- **`EmailService`**: link is composed from `verify-base-url` + raw token (mock `JavaMailSender`,
  assert the captured message body/recipient).
- Follow the existing **plain-JUnit + reflective-proxy-repo** house style (no Mockito), as in
  `EntitlementServiceTest` / `ClassificationRuleServiceTest`.

---

## 11. Frontend contract changes (hand-off notes)

1. **Register** now returns **202** `{ status: "VERIFICATION_REQUIRED", email }` and **no tokens** —
   the SPA shows a "check your inbox" screen. **That screen must include a "Didn't get it? Resend"
   button** (calls `POST /api/auth/resend-verification` with the email from the register response) —
   the first resend entry point (§7 lifecycle).
2. **Login** can return **403 "Email not verified"** — the SPA distinguishes this from `401` and
   shows the **same resend button** (email is the one the user just typed). This is the second resend
   entry point and the recovery path for an expired (>24h) token — login surfaces it, the user clicks
   resend, a fresh 24h link is sent.
3. **Verify landing page**: the SPA needs a route that reads `?status=success|expired|invalid` (the
   target of the backend `302`) and shows success (→ prompt to log in) or, on `expired`/`invalid`, an
   error **with a resend button** so a user who clicked a stale link recovers without re-logging-in.
4. Existing users are unaffected — grandfathered, they log in exactly as today.

---

## 12. Deliverables checklist

- [ ] `V12__add_email_verified_to_users.sql` (add column + grandfather backfill + NOT NULL)
- [ ] `V13__create_email_verification_tokens.sql`
- [ ] `User`: `emailVerified` field + `@PrePersist` default `false`
- [ ] `EmailVerificationToken` entity + `EmailVerificationTokenRepository`
- [ ] `ApiException.forbidden(...)` factory
- [ ] `AuthService.register` — no token pair, create+send verification, publish event, 202 response
- [ ] `AuthService.login` — verification gate after password check
- [ ] `verifyEmail` + `resendVerification` service methods & controller endpoints
- [ ] `RegisterResponse` DTO; `AuthController.register` return type + status
- [ ] `RegistrationEmailEvent` + `@TransactionalEventListener(AFTER_COMMIT) @Async` listener
- [ ] `EmailService` over `JavaMailSender`
- [ ] `AsyncConfig` (`@EnableAsync` + executor)
- [ ] `EmailProperties` + `application.yml` `spring.mail.*` / `screener.email.*`
- [ ] `pom.xml`: `spring-boot-starter-mail`
- [ ] `SecurityConfig`: permit verify + resend paths
- [ ] Tests (§10)
- [ ] Update `CURRENT_STATE.md` (auth + user + config sections; migration list) and memory index

---

## 13. Rollout

1. Provision SMTP credentials + a `noreply@` sender; set `MAIL_*`, `EMAIL_VERIFY_BASE_URL`,
   `EMAIL_FRONTEND_REDIRECT_URL`. Verify deliverability from the deploy environment (SPF/DKIM on the
   sending domain to avoid spam-foldering — a real deliverability prerequisite, independent of code).
2. `pg_dump` prod, deploy the build → Flyway runs `V12`/`V13`; **all existing users are grandfathered
   verified** and see no change.
3. Smoke test: register a fresh account → receive link → click → `success` redirect → log in.
   Confirm an unverified account gets `403` on login and that resend works + is cooldown-limited.

---

## 14. Deferred / future

- Password-reset email (identical token machinery — factor a shared token helper when it lands).
- HTML email templates + i18n (ru/uz/en) — v1 sends plain text.
- Non-SMTP transactional-API sender — only if deliverability/volume demands it (the `EmailService`
  seam is where it would slot in).
- A `@Scheduled` sweep to purge expired verification tokens (low priority; volume is tiny and rows
  are replaced per-user on resend).
- Friendlier "already verified" landing on double-click (needs a consumed marker instead of delete).
