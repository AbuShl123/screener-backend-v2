# Auth API — Registration & Authentication Flow

> **Audience**: frontend engineers/agents building the registration, email-verification, and
> login UI against the screener backend. This document is a complete contract reference — request/
> response shapes, status codes, error cases, and the exact UI states each endpoint implies. It does
> not cover rules/payments/WebSocket APIs beyond the one auth touchpoint the socket needs.
>
> All endpoints below live under `@RestController("/api/auth")` —
> `src/main/java/dev/abu/screener_backend/auth/AuthController.java`.

---

## 1. Conventions

- **Base path**: `/api/auth`. Full URL depends on environment (local dev vs. `tc-screener.com`).
- **Content type**: all request/response bodies are `application/json`. `GET /me` has no body.
- **Auth header**: `Authorization: Bearer <accessToken>` for the two protected endpoints
  (`/logout`, `/me`). Everything else in this doc is public (`permitAll` in `SecurityConfig`).
- **Errors**: every non-2xx response (auth failures, validation, not-found, etc.) — anywhere in the
  API, not just `/api/auth` — has this exact shape:

  ```json
  {
    "message": "Invalid credentials",
    "status": 401,
    "path": "/api/auth/login"
  }
  ```
  `message` is safe to show to the user (or use to branch UI logic) — it's never a stack trace or
  internal detail. `status` duplicates the HTTP status code. There is no `error` field, no `errors`
  array — validation failures are a single flat message, not field-level.

- **No account enumeration**: `resend-verification` always returns 202 with an identical generic
  body regardless of whether the email exists, is already verified, or is on cooldown. Do not try to
  infer account existence from this endpoint's response — there is deliberately no signal.

---

## 2. The account lifecycle (mental model)

```
register ──► unverified account created, email sent ──► user clicks Confirm on SPA verify page
                                                                    │
                                                    POST /verify-email (token)
                                                                    │
                                              success ──► emailVerified = true ──► user can log in
                                              expired/invalid ──► show resend button
```

Key point for the frontend: **the backend never redirects the browser**. The email link points
directly at an SPA route (`screener.email.verify-page-url` + `?token=<raw>`, e.g.
`https://tc-screener.com/verify-email?token=abc123...`). Loading that SPA page does **not** consume
the token — it's just a normal page load. The page must render a **"Confirm" button** that, only on
click, calls `POST /api/auth/verify-email` with the token from the URL query string. This is
deliberate: email link scanners (Outlook Safe Links, corporate AV, link-preview bots) fetch links
automatically, and if the GET itself consumed the single-use token, the real human would find it
already burned by the time they clicked. Requiring a human-initiated POST avoids that.

**Practical implication for the verify page component**:
1. Read `token` from `location.search` on mount.
2. Render a "Confirm your email" button — do not auto-submit on mount.
3. On click, `POST /api/auth/verify-email` with `{ "token": "<value>" }`.
4. Branch UI on the returned `status` field (`success` / `expired` / `invalid`) — see §3.3.
5. If no `token` param is present at all (user navigated here directly), show an invalid state
   without calling the backend.

---

## 3. Endpoints

### 3.1 `POST /api/auth/register`

Creates a new (unverified) account and triggers a verification email. Does **not** log the user in
— there is no token pair in the response.

**Request**
```json
{
  "firstName": "Ada",
  "lastName": "Lovelace",
  "email": "ada@example.com",
  "password": "correct horse battery staple"
}
```
All four fields required and non-blank, or `400 Bad Request` ("All fields are required"). Email is
lowercased server-side before storage/uniqueness check — case is not significant.

**Success — `202 Accepted`**
```json
{
  "status": "VERIFICATION_REQUIRED",
  "email": "ada@example.com"
}
```
`status` is currently always this one literal value on success; it's structured as a string (not a
boolean) so future states can be added without a breaking shape change. Treat 202 as "account
created, not yet usable."

**UI on success**: navigate to a "check your inbox" screen. Show the email address (`response.email`
— already normalized/lowercased) and a **"Didn't get it? Resend"** button that calls
`resend-verification` (§3.4) with that same address. Do not attempt to log the user in — there is no
token to store.

**Errors**
| Status | Body `message` | Cause |
|---|---|---|
| 400 | `All fields are required` | Any of the 4 fields missing/blank |
| 409 | `Email already registered` | Email already exists (any verification state) |

A `409` here **is** an intentional enumeration signal (unlike resend) — register has always worked
this way and predates the verification feature; it's an accepted tradeoff, not an oversight.

---

### 3.2 `POST /api/auth/verify-email`

Called by the SPA verify page when the user clicks **Confirm** — not from the raw email link.
Consumes the single-use token.

**Request**
```json
{ "token": "the-raw-token-from-the-url-query-string" }
```

**Response — always `200 OK`** (never a 4xx/5xx for a bad/expired/missing token — see below):
```json
{ "status": "success" }
```
`status` is one of:

| Value | Meaning | Suggested UI |
|---|---|---|
| `"success"` | Token valid, account now verified, token consumed | "Email confirmed — you can log in now." Route to login (do not auto-login; no tokens are issued here). |
| `"expired"` | Token existed but past its 24h TTL; row left in place | "This link has expired." Show a resend affordance (needs the user's email — see note below). |
| `"invalid"` | Token unknown, malformed, blank, or already used (including a **double-click** of Confirm, or clicking a link that was already confirmed) | "This link is invalid or already used." Show a resend affordance. |

**Important UX note — expired/invalid has no email to prefill resend with.** The verify endpoint
only ever receives a token, never an email address, so on `expired`/`invalid` the frontend doesn't
know *whose* link this was. The resend form on this landing page should ask the user to type their
email (it's a public, unauthenticated endpoint anyway — see §3.4). This is different from the
post-register resend button, which already has the email from the register response.

**Why this never throws a 4xx**: an expired or already-used link is a completely normal, expected
occurrence (user waited >24h, or double-clicked, or clicked an already-confirmed old email) — not a
client bug or server error. It's modeled as a discriminated result the page renders, not an
exception path.

**No request validation errors to handle** beyond the three statuses above — even a missing/blank
token resolves to `"invalid"` rather than a 400.

---

### 3.3 `POST /api/auth/resend-verification`

Re-sends the verification email with a fresh token (and a fresh 24h TTL). Used from two places in
the UI:
1. The post-register "check your inbox" screen (§3.1).
2. The verify-page landing on `expired`/`invalid` (§3.2), and the `403` login flow (§3.5).

**Request**
```json
{ "email": "ada@example.com" }
```

**Response — always `202 Accepted`, regardless of outcome:**
```json
{ "message": "If an unverified account exists for that email, a new verification link has been sent." }
```

This response is **identical** whether the email doesn't exist, is already verified, or was
requested too recently (see cooldown below). This is intentional — do not build UI that
distinguishes these cases, because the backend gives you nothing to distinguish them with. Treat
every 202 the same: show a generic "check your inbox again" confirmation.

**Cooldown**: server-side, resends for the same account are throttled to one per 60 seconds
(`screener.email.resend-cooldown`, default `PT1M`). A request inside the cooldown window still
returns `202` with the same generic body — no new email is sent, and the frontend has no way to know
this happened. **Recommendation**: client-side-only cosmetic cooldown — disable the resend button
for ~60s after a click (a simple local timer) purely to prevent spam-clicking and set user
expectations; do not treat it as a source of truth, since the backend won't tell you if the cooldown
was actually hit.

---

### 3.4 `POST /api/auth/login`

**Request**
```json
{ "email": "ada@example.com", "password": "correct horse battery staple" }
```
Email is lowercased server-side; case-insensitive login.

**Success — `200 OK`**
```json
{
  "accessToken": "<JWT>",
  "refreshToken": "<opaque raw string>",
  "expiresIn": 10800
}
```
- `accessToken`: signed JWT (HS256), 3h default lifetime (`screener.jwt.access-token-expiry`). Send
  as `Authorization: Bearer <accessToken>` on subsequent requests, and as the `?token=` query param
  when opening the `/ws` WebSocket connection.
- `refreshToken`: opaque random string (not a JWT — don't try to decode it). Exchange it via
  `/api/auth/refresh` when the access token expires. The backend keeps only **one** active refresh
  token per user — issuing a new one (via login or refresh) invalidates the previous one.
- `expiresIn`: access token lifetime in **seconds** (3h → `10800`). Use this to schedule a proactive
  refresh (e.g. refresh at `expiresIn - 60s`) rather than waiting for a 401.

**Errors**
| Status | Body `message` | Cause | Suggested UI |
|---|---|---|---|
| 401 | `Invalid credentials` | Unknown email, or wrong password | Generic "invalid email or password" — do not distinguish "email not found" from "wrong password" (backend doesn't either, by design) |
| 401 | `Account disabled` | Admin-disabled account | "Your account has been disabled. Contact support." |
| **403** | `Email not verified` | Password is correct, but `emailVerified = false` | **Show the resend affordance** — see below |

**The 403 case is the key branch for the frontend.** It only fires *after* the password has already
been verified correct (deliberately — see enumeration note in §1), so a `403` means: *this is a real
account, password is right, they just haven't clicked their verification link yet.* The UI should:
1. Distinguish this from the two `401` cases (different message/treatment — e.g. don't say "invalid
   credentials").
2. Show something like: "Please verify your email before logging in." + a **"Resend verification
   email"** button that calls `POST /api/auth/resend-verification` with the email the user just
   typed into the login form (you already have it — no need to ask again).

This is also the **recovery path for an expired (>24h) verification link**: a user who registered,
ignored the email for a day, and then just tries to log in will hit this `403` and get the same
resend button — they never need to find their way back to the stale verify-page link.

---

### 3.5 `POST /api/auth/refresh`

Exchanges a valid refresh token for a new access+refresh pair (rotation — the old refresh token is
invalidated).

**Request**
```json
{ "refreshToken": "<opaque raw string>" }
```

**Success — `200 OK`**: same `AuthResponse` shape as login (§3.4) — new `accessToken`, new
`refreshToken`, `expiresIn`. **Always store the new `refreshToken`** — the old one no longer works
after this call.

**Errors**
| Status | Body `message` | Cause |
|---|---|---|
| 400 | `refreshToken required` | Missing/blank body field |
| 401 | `Invalid refresh token` | Unknown/malformed token |
| 401 | `Refresh token expired` | Past `expires_at` (7d default, `screener.jwt.refresh-token-expiry`); the row is deleted server-side on this response |

On any `401` here, treat it as a hard logout: clear stored tokens and route to the login screen —
there is no recovery short of logging in again.

---

### 3.6 `POST /api/auth/logout` — requires `Authorization: Bearer <accessToken>`

**Request**: no body.

**Success — `204 No Content`**: server deletes the user's refresh token row. Client should discard
both stored tokens regardless of response (logout is idempotent — calling it twice, or with an
already-expired access token that still passes signature/expiry checks, is harmless).

---

### 3.7 `GET /api/auth/me` — requires `Authorization: Bearer <accessToken>`

Fetches the current user's profile plus their derived subscription/entitlement state in one call —
intended as the app's bootstrap/hydration call after login or on page reload (when an access token
is already in storage).

**Success — `200 OK`**
```json
{
  "id": "5b1e6b8e-....-....-....-............",
  "firstName": "Ada",
  "lastName": "Lovelace",
  "email": "ada@example.com",
  "role": "USER",
  "accessState": "TRIAL",
  "accessExpiresAt": "2026-07-10T12:00:00Z"
}
```
- `role`: currently only `USER` or `ADMIN` exist server-side today.
- `accessState`: one of `TRIAL` (free week, never paid), `ACTIVE` (paid, currently valid),
  `EXPIRED` (no valid access — must purchase), `ADMIN` (bypass; always paired with
  `accessExpiresAt: null`). This is the field to gate paid-feature UI on — not `role`.
- `accessExpiresAt`: ISO-8601 instant, or `null` for `ADMIN`. Use this to show "trial ends in N
  days" style messaging.

No auth-specific errors beyond the standard "no/invalid/expired bearer token" → the request never
reaches the controller; Spring Security returns an empty `403` before the JSON error body would even
apply (per `GlobalExceptionHandler`'s note: authentication failures are handled below MVC, so they
do **not** carry the `ApiError` JSON shape — expect an empty body on that particular `403`).

---

## 4. Full flow reference

### 4.1 New user registration → first login

```
1. POST /register              → 202 VERIFICATION_REQUIRED           [show "check inbox" screen]
2. (user opens email, clicks link → SPA verify page loads, token in URL, nothing sent yet)
3. user clicks "Confirm"       → POST /verify-email {token}          → 200 {status: "success"}
4. [route to login screen]
5. POST /login {email, pw}     → 200 {accessToken, refreshToken, expiresIn}
6. store tokens, route to app
```

### 4.2 Link expired / lost

```
1. POST /register → 202                                    [user never finds/clicks the email]
2a. Path A: user clicks "Resend" on the inbox screen
    → POST /resend-verification {email} → 202 generic       [new 24h token sent]
2b. Path B: user just tries to log in later
    → POST /login → 403 "Email not verified"
    → show resend button (email already known from the form)
    → POST /resend-verification {email} → 202 generic
3. user clicks the NEW email's link → Confirm → POST /verify-email → success
4. POST /login → 200 tokens
```

### 4.3 Stale/expired verify-page click

```
1. user clicks an old email link (already used, or >24h old) → SPA verify page loads
2. user clicks Confirm → POST /verify-email {token} → 200 {status: "expired" | "invalid"}
3. UI shows error state + asks for email → POST /resend-verification {email} → 202
4. user gets a new email, repeats verify flow
```

### 4.4 Session refresh (steady state, already logged in)

```
- Access token nearing expiry (e.g. scheduled at expiresIn - 60s, or reactively on a 401
  from any authenticated endpoint):
  POST /refresh {refreshToken} → 200 new {accessToken, refreshToken, expiresIn}
  → overwrite both stored tokens
- If /refresh itself returns 401 → clear storage, redirect to /login
```

---

## 5. Token storage & the WebSocket touchpoint

- `accessToken` (JWT): send as `Authorization: Bearer <token>` on every authenticated REST call.
- `refreshToken` (opaque): only ever sent to `/api/auth/refresh`. Never attach it to any other
  request.
- The realtime order-book feed (`/ws`, Jakarta WebSocket endpoint, out of scope for the rest of this
  doc) authenticates via `?token=<accessToken>` on the connection URL — the **same** access token
  used for REST, not the refresh token. If the access token expires while a WS session is open,
  reconnect with a freshly refreshed token rather than assuming the socket re-authenticates itself.
- Storage mechanism (localStorage vs. memory vs. httpOnly cookie) is a frontend decision not
  constrained by this backend — there's no CSRF protection to coordinate with since the API is
  stateless bearer-token auth (`SessionCreationPolicy.STATELESS`), and CORS already allows credentials
  from the known origins listed in `SecurityConfig`.

---

## 6. Quick endpoint summary

| Method | Path | Auth | Success | Notes |
|---|---|---|---|---|
| POST | `/api/auth/register` | Public | 202 `RegisterResponse` | No tokens issued |
| POST | `/api/auth/verify-email` | Public | 200 `VerifyEmailResponse` | Called from SPA Confirm button, not the email link itself |
| POST | `/api/auth/resend-verification` | Public | 202 generic | Always 202, no enumeration |
| POST | `/api/auth/login` | Public | 200 `AuthResponse` | 403 if unverified |
| POST | `/api/auth/refresh` | Public (refresh token in body) | 200 `AuthResponse` | Rotates refresh token |
| POST | `/api/auth/logout` | Bearer JWT | 204 | Idempotent |
| GET | `/api/auth/me` | Bearer JWT | 200 `UserProfileResponse` | Bootstrap/hydration call |
