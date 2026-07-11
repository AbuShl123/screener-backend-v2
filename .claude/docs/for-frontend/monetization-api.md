# Monetization API — Frontend Reference

> Audience: frontend engineers integrating the subscription/payment feature into the screener SPA.
> This is the **endpoint-level contract** — every request/response shape, status code, and enum the UI
> touches. For the narrative UX flow (page transitions, spinners, retry copy) see the companion
> [`payment-flow-frontend.md`](./payment-flow-frontend.md). For backend internals (state machine,
> concurrency, Multicard adapter) see [`../payment-gateway-multicard.md`](../payment-gateway-multicard.md).
>
> Contract verified against committed code on branch `feature/monetization`. **Where this doc and
> `payment-flow-frontend.md` disagree on paths, this doc wins** — the catalog moved to
> `/api/billing-catalog/**` and gained a public days-estimate endpoint.

---

## 1. What is being sold

A **single-tier** subscription. There is no PRO/Enterprise split — every paid subscription grants the
*same* access; only the billing period differs. Access is a **one-time purchase of a fixed period**
(nothing auto-renews), sold four ways:

| `planCode`       | `type`    | Grants                            |
|------------------|-----------|-----------------------------------|
| `weekly`         | `FIXED`   | 7 days                            |
| `monthly`        | `FIXED`   | 30 days                           |
| `yearly`         | `FIXED`   | 365 days                          |
| `pay_as_you_go`  | `PER_DAY` | `ceil(amount / pricePerDay)` days |

Every purchase pushes one timestamp — `accessExpiresAt` — forward, stacking on any remaining time
(`newExpiry = max(now, currentExpiry) + grantedDuration`). There is no separate "subscription" entity.

Audience is Uzbekistan; the only live currency is **UZS**, and the only payment provider is **Multicard**
(hosted card page). Money is always in **major units** (sum, not tiyin).

---

## 2. Conventions

### 2.1 Base URL & auth

All paths below are relative to the API host. Two auth tiers:

- **Public** (no token): the plan catalog and the days estimate, plus the auth bootstrap endpoints. These
  let a not-yet-registered visitor browse pricing.
- **Bearer JWT** (everything else): send `Authorization: Bearer <accessToken>`. Missing/invalid token →
  **403 with an empty body** (Spring Security rejects before the controller runs, so there is no JSON
  error body on auth failures — treat a 403 with no body as "not authenticated").

CORS allows `https://tc-screener.com`, `https://www.tc-screener.com`, and localhost during dev.
Credentials are allowed.

### 2.2 Error shape

Every error that reaches a controller returns this uniform JSON (except the empty-body 403 above):

```json
{ "message": "amount must be a positive number for pay-by-days", "status": 400, "path": "/api/billing/orders" }
```

`message` is safe to surface to users for `4xx`. A `500` always carries the generic
`"Internal server error"` (real cause is logged server-side, never leaked).

### 2.3 Money & amounts

- **Reading** money (catalog price, order `amount`): a JSON **number** (`BigDecimal`), already scaled for
  display — e.g. UZS renders as `150000.00`. Show it directly; no division.
- **Sending** money (`pay_as_you_go` amount): a JSON **string** (e.g. `"790000"`). This avoids
  `double` precision loss. Validate client-side: **positive**, and **≤ 2 decimal places for UZS**. The
  server rejects a non-positive or over-scale amount with **400**.

### 2.4 Timestamps

All timestamps are ISO-8601 instants in UTC (`"2026-08-14T09:30:00Z"`), Jackson-serialized `Instant`.

---

## 3. Enums the UI branches on

**`AccessState`** (from `entitlement`) — the paywall gate:

| Value     | Meaning                                        | `accessExpiresAt` |
|-----------|------------------------------------------------|-------------------|
| `TRIAL`   | Granted the free week, never paid              | trial-end instant |
| `ACTIVE`  | Paid access currently valid                    | subscription end  |
| `EXPIRED` | No valid access — must purchase                | past, or `null`   |
| `ADMIN`   | Admin bypass (unlimited)                        | `null`            |

**`OrderStatus`** — the order lifecycle the SPA polls:

| Value     | Terminal? | Meaning                                                  |
|-----------|-----------|----------------------------------------------------------|
| `CREATED` | no (open) | Order row created; invoice not yet attached (transient)  |
| `PENDING` | no (open) | Invoice created; awaiting payment. **This is what you poll on.** |
| `PAID`    | ✅        | Payment confirmed, access granted                        |
| `EXPIRED` | ✅        | Invoice TTL (30 min) elapsed with no payment             |
| `FAILED`  | ✅        | Provider reported an error / amount mismatch             |
| `CANCELED`| ✅        | Canceled before payment — user-canceled, or superseded by a different-plan order |
| `REVERTED`| ✅        | Refund detected — **recorded only; access is NOT revoked** |

At most **one open order** (`CREATED`/`PENDING`) exists per user at a time.

**`PlanType`**: `FIXED` (has `durationDays`) or `PER_DAY` (`durationDays` is `null`).

**`GrantSource`** (entitlement ledger): `TRIAL` | `PURCHASE` | `ADMIN`.

---

## 4. Endpoints

### 4.0 Auth prerequisite (context)

Payment endpoints need a JWT. The relevant auth surface (full auth docs are separate):

| Method | Path | Auth | Body → Returns |
|--------|------|------|----------------|
| `POST` | `/api/auth/register` | public | `{firstName,lastName,email,password}` → `202 {status:"VERIFICATION_REQUIRED", email}` |
| `POST` | `/api/auth/verify-email` | public | `{token}` → `{status: "success"\|"expired"\|"invalid"}` (always 200) |
| `POST` | `/api/auth/resend-verification` | public | `{email}` → `202 {message}` |
| `POST` | `/api/auth/login` | public | `{email,password}` → `{accessToken, refreshToken, expiresIn}` |
| `POST` | `/api/auth/refresh` | public | `{refreshToken}` → `{accessToken, refreshToken, expiresIn}` |
| `POST` | `/api/auth/logout` | Bearer | — → `204` |
| `GET`  | `/api/auth/me` | Bearer | → `{id, firstName, lastName, email, role, accessState, accessExpiresAt}` |

> **Bootstrap tip:** `GET /api/auth/me` returns identity **and** `accessState`/`accessExpiresAt` in one
> call — use it to render the shell + paywall gate on load. `GET /api/billing/entitlement` (§4.2) returns
> the same two access fields alone, for cheap re-polling without re-fetching the whole profile.
>
> Registration returns **no token** — the account exists but is unusable until the email is verified.
> `expiresIn` on `AuthResponse` is the access-token lifetime in seconds.

---

### 4.1 Plan catalog — PUBLIC (no JWT)

Mounted under `/api/billing-catalog/**`, registered `permitAll` in `SecurityConfig`. Lets a visitor see
pricing before signing up.

#### `GET /api/billing-catalog/plans`

Returns the priced catalog for the server-resolved currency (UZS today). A plan with no active price in
that currency is silently omitted.

```json
{
  "currency": "UZS",
  "plans": [
    { "code": "monthly",       "displayName": "Monthly",        "type": "FIXED",   "durationDays": 30,   "amount": 150000.00 },
    { "code": "weekly",        "displayName": "Weekly",         "type": "FIXED",   "durationDays": 7,    "amount": 45000.00 },
    { "code": "yearly",        "displayName": "Yearly",         "type": "FIXED",   "durationDays": 365,  "amount": 1500000.00 },
    { "code": "pay_as_you_go", "displayName": "Pay as you go",  "type": "PER_DAY", "durationDays": null, "amount": 5000.00 }
  ]
}
```

- `currency` is declared **once** at the top; there is no per-plan currency.
- `amount` for `pay_as_you_go` **is the per-day price** — use it for the live day estimate.
- `displayName` is a fallback label. Prefer rendering your own localized name/description keyed by `code`.
- Order of `plans` is by `code` (alphabetical), not by price — sort client-side if needed.

#### `GET /api/billing-catalog/pay-as-you-go/days?currency=UZS&amount=790000`

Public server-side day estimate for a pay-as-you-go amount — the authoritative `ceil(amount/pricePerDay)`
without creating an order. Prefer this over re-implementing the rounding client-side when you want the
"you'll get N days" preview to exactly match what a purchase grants.

Query params: `currency` (e.g. `UZS`), `amount` (decimal, major units).

```json
{ "days": 158 }
```

Errors: non-positive or over-scale `amount` → **400**; unknown/misconfigured plan or currency → **400**.

> You *may* still compute the estimate purely client-side (`ceil(amount / pricePerDay)`) from the catalog
> price for instant keystroke feedback, and confirm with this endpoint on blur / before submit.

---

### 4.2 Entitlement (access state) — Bearer JWT

Mounted under `/api/billing`.

#### `GET /api/billing/entitlement`

The source of truth for **"does this user have access right now?"** — poll it at boot, on window focus,
and right after a payment flips to `PAID`.

```json
{ "state": "ACTIVE", "accessExpiresAt": "2026-08-14T09:30:00Z" }
```

- `state` is an `AccessState` (§3). `ADMIN` returns `accessExpiresAt: null`.
- **Enforcement caveat:** access-gating is **not yet wired server-side** — the backend will not `403` a
  data request or reject the `/ws` connection based on entitlement. The client paywall is **advisory UX,
  not security** (see backend doc §1/§15). Build the UI against this endpoint, but don't mistake it for
  real access control.

#### `GET /api/billing/entitlement/history`

The caller's access-granting events (the entitlement ledger), **newest first** — trial seed, each paid
purchase, future admin grants. Each row is one push of `accessExpiresAt` forward.

```json
[
  {
    "source": "PURCHASE",
    "grantedDurationSeconds": 2592000,
    "previousExpiresAt": "2026-07-15T09:30:00Z",
    "newExpiresAt": "2026-08-14T09:30:00Z",
    "order": { "orderId": "…", "status": "PAID", "planCode": "monthly", "...": "full OrderDetailsEntry" },
    "reason": "PAYMENT_SUCCESS",
    "createdAt": "2026-07-15T09:31:02Z"
  },
  {
    "source": "TRIAL",
    "grantedDurationSeconds": 604800,
    "previousExpiresAt": null,
    "newExpiresAt": "2026-07-15T09:30:00Z",
    "order": null,
    "reason": "TRIAL_START",
    "createdAt": "2026-07-08T09:30:00Z"
  }
]
```

- `order` is the **full `OrderDetailsEntry`** (§4.3) for a `PURCHASE` grant, or `null` for `TRIAL`/`ADMIN`.
- `previousExpiresAt`/`newExpiresAt` bracket the stacking move — good for an account-page timeline.

---

### 4.3 Orders — Bearer JWT

Mounted under `/api/billing/orders`. Every read is **owner-scoped** (you only ever see your own orders;
a foreign `{id}` returns 404).

| Method | Path | Body | Returns |
|--------|------|------|---------|
| `POST` | `/api/billing/orders` | `CreateOrderRequest` | `OrderDetailsEntry` (created order, with `checkoutUrl`) |
| `GET`  | `/api/billing/orders` | — | `OrderDetailsEntry[]` — history, newest first (cap 100) |
| `GET`  | `/api/billing/orders/current` | — | `OrderDetailsEntry` — latest open / most-recent order (**poll this**) |
| `POST` | `/api/billing/orders/current/cancel` | — | `OrderDetailsEntry` — the now-`CANCELED` order (or **409** if it isn't `PENDING`) |
| `GET`  | `/api/billing/orders/{id}` | — | `OrderDetailsEntry` for that order |
| `GET`  | `/api/billing/orders/{id}/history` | — | `OrderHistoryEntry[]` — status-transition audit, newest first |

#### Create order — `POST /api/billing/orders`

Request body (`CreateOrderRequest`):

```jsonc
// fixed plan
{ "planCode": "monthly" }

// pay-as-you-go (amount is a STRING, major units)
{ "planCode": "pay_as_you_go", "amount": "790000" }
```

- Send **only** `planCode` (+ `amount` for pay-as-you-go). Never a price or currency — resolved
  server-side.
- `amount` is **omitted/ignored** for `FIXED` plans; **required** for `pay_as_you_go`.

Response is an `OrderDetailsEntry` (below) with `status: "PENDING"` and a usable `checkoutUrl`. **The
backend never sends a 302** — the SPA reads `checkoutUrl` and performs the redirect itself.

**Create-time error/response codes:**

| Status | When | Handling |
|--------|------|----------|
| `200` | Order created (or an existing open order reused) | Redirect to `checkoutUrl` |
| `400` | Missing `planCode`; malformed/non-positive/over-scale `amount` | Inline field error |
| `409` | **Active-subscription gate** — caller already holds paid access expiring **beyond** the renewal window (default 5 days). | Not an error toast — show "You're covered until *date*; renew closer to expiry". Proactively disable/hide FIXED plans in this state. |
| `409` | **Lost one-open-order race** (rare) | Transparently retry the POST once; it reuses the now-committed open order. |

Renewal-gate nuances (mirror in the UI):
- The 409 gate applies to **`FIXED` plans only**. **`pay_as_you_go` is always exempt** — a user may top
  up arbitrary days anytime, even mid-subscription.
- **Not gated:** `TRIAL` users (can convert mid-trial), `EXPIRED` users (can re-buy), paid users **within**
  the renewal window (≤ 5 days to expiry → the normal "renew before it lapses" flow; the new period
  stacks), and `ADMIN`.

#### `OrderDetailsEntry` — the one order view

Returned by create **and** every status read. Fields:

| Field | Type | Notes |
|-------|------|-------|
| `orderId` | UUID string | |
| `status` | `OrderStatus` | §3 |
| `planCode` | string | echoes the requested plan |
| `amount` | number (`BigDecimal`) | major units — FIXED price or echoed pay-as-you-go input |
| `accessDurationSeconds` | number | how much access this order buys (snapshot) |
| `currency` | string | server-resolved; today always `"UZS"` |
| `provider` | string | server-resolved; today always `"multicard"` |
| `reason` | string \| null | latest transition's canonical reason code |
| `reasonDetail` | string \| null | free-form detail (e.g. raw provider error) — use on failure screens |
| `checkoutUrl` | string \| null | hosted Multicard page; redirect target, and recoverable from `current`/`{id}` |
| `providerUuid` | string \| null | Multicard transaction uuid — for support/debugging |
| `expiresAt` | instant \| null | invoice TTL deadline (~30 min after creation) |
| `paidAt` | instant \| null | set when `PAID` |
| `createdAt` | instant | |

#### `OrderHistoryEntry` — one transition (from `/{id}/history`)

```json
{
  "fromStatus": "PENDING",
  "toStatus": "PAID",
  "reason": "PAYMENT_SUCCESS",
  "reasonDetail": null,
  "source": "CALLBACK",
  "createdAt": "2026-07-15T09:31:02Z",
  "seq": 42
}
```

`source` ∈ `API | CALLBACK | RECONCILIATION | SYSTEM`. Ordered by `seq DESC` (monotonic). Purely for an
audit/debug view.

#### Cancel the current order — `POST /api/billing/orders/current/cancel`

Cancels the caller's current order (the same order `GET /orders/current` returns) and cancels its unpaid
Multicard invoice, so a user can abandon a checkout immediately instead of waiting out the 30-min TTL.
No request body.

- **Succeeds only when the current order is `PENDING`** → transitions it to `CANCELED` and returns the
  updated `OrderDetailsEntry` (`status: "CANCELED"`, `reason: "USER_CANCELED"`).
- The Multicard invoice cancel is **best-effort**: the local `CANCELED` is committed even if the provider
  call fails, so the response is authoritative for your UI.

**Response codes:**

| Status | When | Handling |
|--------|------|----------|
| `200` | Current order was `PENDING` → now `CANCELED` | Clear the pending-payment UI; let the user pick a plan again |
| `409` | Current order is **not** `PENDING` (already `PAID`, or terminal) | A paid/terminal order has no live invoice — refetch `current`/`entitlement` and reconcile the UI |
| `404` | User has no orders at all | — |

> **Race note:** if the user actually paid on Multicard's page just before cancelling, the cancel may 200
> with `CANCELED`, but the authoritative success callback can still later flip the order `CANCELED → PAID`
> and grant access. So after a cancel, still trust `GET /api/billing/entitlement` for the real access
> state rather than assuming the cancel was final.

---

## 5. How a payment is actually made

The critical mental model: **the backend never grants access on the browser's return trip.** Access is
granted only by Multicard's server-to-server success callback (or a 1-minute reconciliation sweep as a
safety net). **The SPA learns the outcome by polling `GET /api/billing/orders/current`.**

```
1. User is logged in (has a JWT) and picks a plan / enters a pay-as-you-go amount.

2. POST /api/billing/orders { planCode, amount? }
        → 200 OrderDetailsEntry { status: "PENDING", checkoutUrl, ... }

3. SPA redirects the browser to checkoutUrl  (window.location = checkoutUrl)
        → user enters card + OTP on Multicard's HOSTED page (not ours)

        ┌─────────────────────────────┬──────────────────────────────────────────┐
        ▼                             ▼
4a. Multicard → OUR backend      4b. Browser → return_url (a page you host)
    server-to-server callback        UX ONLY — proves nothing, grants nothing
    (verified by signature+IP)
    → backend grants access,
      order flips CREATED/PENDING
      → PAID, accessExpiresAt
      pushed forward

5. return_url page POLLS GET /api/billing/orders/current every ~2s:
      PENDING → PENDING → PAID     (or a terminal status: EXPIRED/FAILED/CANCELED/REVERTED)

6. On PAID → refetch GET /api/billing/entitlement (now ACTIVE) → unlock the app.
```

**Safety net:** if the callback is slow or lost, a backend reconciliation sweep (~every minute) queries
Multicard directly and grants access. So keep polling through a prolonged `PENDING` — don't give up at the
first slow response; add reassuring copy after ~10s ("this can take up to a minute").

**Never** treat the browser landing on `return_url` as proof of payment. A user who *cancels* on
Multicard's page still gets redirected back; the order simply stays `PENDING` until its 30-min TTL expires
and the sweep marks it `EXPIRED`.

### 5.1 Status handling on the polling page

| Polled `status` | Meaning | UI |
|-----------------|---------|-----|
| `PENDING` | still awaiting/confirming | keep spinner; after ~60–90s post-return offer "Payment not completed → Retry / Choose another plan" |
| `PAID` | success | refetch entitlement, show success + new expiry, unlock |
| `EXPIRED` | TTL elapsed, unpaid | "Payment not completed" → let them start a new order |
| `FAILED` | provider error / amount mismatch | failure screen showing `reason`/`reasonDetail` |
| `CANCELED` | user-canceled, or superseded by a newer order | usually silent — the user abandoned it, or a newer order is now current |
| `REVERTED` | refund detected | rare; note access is **not** auto-revoked |

### 5.2 Recovering in-flight state

Always reconstruct payment state from `GET /api/billing/orders/current`, never solely from the `POST`
response:

- **Lost the POST response** (network blip): the order exists server-side anyway — `current` returns it
  with its `checkoutUrl`.
- **Closed the tab / came back later:** `current` returns the open `PENDING` order → show a "resume
  unfinished payment" banner reusing its `checkoutUrl`.
- **Re-pay, same plan:** the one-open-order rule returns the existing `PENDING` — reuse it, don't create a
  duplicate.
- **Re-pay, different plan:** just `POST` the new plan; the backend supersedes (cancels) the old order.

### 5.3 Canceling a `PENDING` order

The user can abandon an in-flight checkout three ways:

- **Explicit cancel** — `POST /api/billing/orders/current/cancel` (§4.3). Immediately flips the `PENDING`
  order to `CANCELED` and cancels the Multicard invoice. Use this for a "Cancel payment" button on the
  polling/resume screen; on `200`, clear the pending UI and let them pick a plan again.
- **Start a different plan** — a new `POST /api/billing/orders` for another plan supersedes (cancels) the
  old order automatically.
- **Do nothing** — the invoice's 30-min TTL elapses and the reconciliation sweep marks it `EXPIRED`.

After an explicit cancel, still trust `GET /api/billing/entitlement` for the true access state — a payment
that landed a moment before the cancel can still be granted by the success callback (see the race note in
§4.3).

---

## 6. Quick integration checklist

- [ ] Bootstrap the shell + gate with `GET /api/auth/me`; re-poll access with `GET /api/billing/entitlement` on focus.
- [ ] Paywall reads **`GET /api/billing-catalog/plans`** (public). Sort/render by your own i18n keyed on `code`.
- [ ] Pay-as-you-go: string amount, positive, ≤ 2 dp (UZS); live estimate from the catalog per-day price, confirmed via **`GET /api/billing-catalog/pay-as-you-go/days`**.
- [ ] Disable/hide FIXED plans when entitlement is `ACTIVE` and expiry is > 5 days out (a POST would 409). Keep pay-as-you-go enabled always.
- [ ] `POST /api/billing/orders` → redirect to `checkoutUrl`; auto-retry once on a 409 *race* (distinguish from the 409 renewal gate by the current entitlement state).
- [ ] `return_url` page polls `GET /api/billing/orders/current` (~2s); resolve on `PAID`, keep going through slow `PENDING`, time out gracefully.
- [ ] Offer a "Cancel payment" action on the polling/resume screen → `POST /api/billing/orders/current/cancel`; on 200 clear the pending UI, on 409 refetch `current`/`entitlement` and reconcile.
- [ ] On `PAID`, refetch `entitlement` before unlocking.
- [ ] Never treat the browser return as proof of payment.
- [ ] Treat an empty-body 403 as "not authenticated" → refresh token or bounce to login, then resume from `current`.
