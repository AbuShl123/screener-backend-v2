# Payment Flow — Frontend Design Guide

> Audience: frontend engineers building the subscription/payment UX for the screener SPA.
> Backend contract source of truth: [`../payment-gateway-multicard.md`](../payment-gateway-multicard.md).
> This doc translates that backend behavior into concrete user flows, page transitions, and the
> API calls each screen makes. Where the backend is missing something the UX needs, it's called out
> explicitly in [§4](#4-missing--nice-to-have-api-endpoints).

---

## 1. Two backend facts that shape everything

Before designing a single screen, internalize these — they dictate the whole flow:

### 1.1 The backend never redirects, and never grants access on the browser's return trip

`POST /api/billing/orders` returns **JSON** containing a `checkoutUrl` — the backend deliberately never
sends a `302`. **The SPA owns the redirect** to Multicard's hosted page.

The browser's return to `return_url` after payment **grants nothing** — it is UX only. Access is granted
solely by Multicard's server-to-server **success callback** (or the 1-minute reconciliation sweep as a
safety net). Therefore:

> **The SPA learns the payment outcome by polling `GET /api/billing/orders/current` — never from the
> return URL.**

### 1.2 Access-gate enforcement is NOT wired server-side yet

Per the backend doc (§1, §15), `EntitlementService.hasAccess(...)` exists but is **not** enforced in any
REST endpoint or the WebSocket `@OnOpen`. Practically, for the frontend:

- The **source of truth for "does this user have access?"** is `GET /api/billing/entitlement`.
- The paywall the SPA renders is currently **the only thing** gating the experience. The backend won't
  `403` a data request or reject the `/ws` connection on its own (yet).
- This is fine for building the UI now, but the frontend gate is **advisory, not security**. Flag to
  whoever owns the enforcement plan; don't mistake the client paywall for real access control.

---

## 2. Relevant API surface

All Bearer-JWT **unless the Auth column says PUBLIC**. Full details in the backend doc §11 and the
endpoint-level reference [`monetization-api.md`](./monetization-api.md).

| Method | Path | Auth | Purpose (frontend use) |
|--------|------|------|------------------------|
| `GET`  | `/api/billing/entitlement` | Bearer | Access state + `accessExpiresAt` + `hasPaid`. **Boot-time gate + renewal nudge.** |
| `GET`  | `/api/billing/entitlement/history` | Bearer | Access ledger (trial + purchase grants, stacking moves). Account page. |
| `GET`  | `/api/billing-catalog/plans` | **PUBLIC** | Catalog: 4 plan codes + prices (UZS). Paywall. |
| `GET`  | `/api/billing-catalog/pay-as-you-go/days?currency=&amount=` | **PUBLIC** | Authoritative `ceil(amount/pricePerDay)` day estimate → `{ days }`. |
| `POST` | `/api/billing/orders` | Bearer | Create order `{ planCode, amount? }` → `OrderDetailsEntry` with `checkoutUrl`. |
| `GET`  | `/api/billing/orders/current` | Latest open / most-recent order. **The endpoint the SPA polls.** |
| `GET`  | `/api/billing/orders` | Order history, newest first (cap 100). |
| `GET`  | `/api/billing/orders/{id}` | One order's detail. |
| `GET`  | `/api/billing/orders/{id}/history` | One order's status-transition audit. |

**`OrderDetailsEntry`** (returned by create + all status reads) carries everything the UI needs:
`orderId, status, planCode, amount, accessDurationSeconds, currency, provider, reason, reasonDetail,
checkoutUrl, providerUuid, expiresAt, paidAt, createdAt`.

- `status` ∈ `CREATED | PENDING | PAID | EXPIRED | FAILED | CANCELED | REVERTED`.
- `checkoutUrl` — redirect target; also lets the SPA **recover** the payment link from `current`/`{id}`
  if it lost the `POST` response.
- `reason`/`reasonDetail` — human-ish explanation of the latest transition (use on failure screens).
- `accessDurationSeconds` — how much access this order buys (confirm-what-you-bought UI).

**Request rules for `POST /orders`:**
- Client sends **only** `planCode` (+ `amount` as a **string** for `pay_as_you_go`). Never a price or
  currency — the server resolves those.
- `amount` is a string to avoid `double` precision loss. Validate scale client-side: UZS allows **2
  decimals max**, must be **positive**.

---

## 3. Flows in detail

### 3.1 Happy path — pages & transitions

```
[Login] ──► App shell boots
   │  GET /api/billing/entitlement   →  state = EXPIRED, hasPaid = false
   ▼
[Paywall / Subscribe page]           ← the app is locked behind this
   │  GET /api/billing-catalog/plans  → weekly / monthly / yearly / pay_as_you_go (+ UZS prices)
   │  user picks a plan (or enters an amount for pay-as-you-go)
   ▼
   │  POST /api/billing/orders { planCode, amount? }
   │     → 200 OrderDetailsEntry { orderId, status: PENDING, checkoutUrl, … }
   ▼
[Redirecting… interstitial] ──► window.location = checkoutUrl
   ▼
[Multicard hosted page]  (NOT ours — user enters card, OTP)
   │  user pays
   ▼  browser redirected to return_url
[Payment processing page]  ← "Confirming your payment…" spinner
   │  poll GET /api/billing/orders/current every ~2s
   │  status: PENDING → PENDING → PAID
   ▼
[Success page]  "You're in until 14 Aug 2026"
   │  refetch GET /api/billing/entitlement  → state = ACTIVE
   ▼
[App unlocked]  (open the /ws feed, etc.)
```

### 3.2 Pay-as-you-go specifics

The user types an arbitrary sum; the UI should show a **live day estimate** as they type
(`≈ 790 days`). The formula is `days = ceil(amount / pricePerDay)`.

- This is computable client-side from `GET /api/billing-catalog/plans`, which exposes the per-day price
  for `pay_as_you_go` (its price row *is* the per-day price).
- For an authoritative count, `GET /api/billing-catalog/pay-as-you-go/days?currency=&amount=` (PUBLIC)
  returns the server-side `ceil` — confirm the estimate with it on blur / before submit (see §4.1).
- Validate before `POST`: positive, and ≤ 2 decimals for UZS. Backend also returns `400` for
  non-positive / over-scale — map that to an inline field error, not a page.

### 3.3 Renewal window (the "active but expiring soon" case)

The backend blocks a redundant fixed-plan purchase while a paid subscription is **comfortably** in the
future, but **allows** renewal once access falls within the **renewal window** (configurable, default
**5 days**). For the UI:

- Read `entitlement`. If `ACTIVE` and `accessExpiresAt` is **> 5 days** away → fixed plans should be
  **disabled/hidden** with a "You're covered until *date*" message (a `POST` would `409`).
- If `ACTIVE` and `accessExpiresAt` is **≤ 5 days** away → show a **"Renew"** nudge; buying is allowed
  and the new period **stacks** on the remaining time.
- **Pay-as-you-go is always exempt** — the top-up card stays enabled regardless, even mid-subscription.

### 3.4 Negative / edge scenarios (detailed)

| # | Scenario | Backend behavior | Frontend handling |
|---|----------|------------------|-------------------|
| **N1** | Active paid, expiry far off, tries to buy a **fixed** plan | `POST /orders` → **409** (`hasPaidAccessBeyondRenewalWindow` gate) | Don't render as an error toast. Show "You already have access until *date* — you can renew closer to expiry." Proactively disable/hide fixed plans when entitlement is active-beyond-window. Keep pay-as-you-go enabled (exempt). |
| **N2** | User **cancels or fails** on the Multicard page | Browser still returns to `return_url`. Order stays `PENDING`; only becomes `EXPIRED` after the 30-min TTL + sweep. | **Don't poll forever.** After ~60–90s of `PENDING` post-return, show "Payment not completed." Offer **Retry** (reuse the same `checkoutUrl`) and **Choose another plan** (a new `POST` supersedes the old order). See [§4.2](#42-cancelabandon-current-order-genuinely-useful) — an explicit cancel endpoint would make "start over" instant. |
| **N3** | User **closes the tab**, returns later | `GET /orders/current` returns the `PENDING` order + its `checkoutUrl`. | On load, detect an open order → show a "You have an unfinished payment" banner with **Resume** (reuse `checkoutUrl`) / **Start over**. |
| **N4** | **Lost the `POST` response** (network blip) | Order was created server-side regardless. | Never rely solely on the `POST` return value. Reconstruct state from `GET /orders/current` and reuse its `checkoutUrl`. |
| **N5** | Callback **slow or temporarily lost** | The 1-min reconciliation sweep grants access via `GET /payment/{uuid}`. | Keep the processing spinner. After ~10s, add reassuring copy ("Still confirming — this can take up to a minute"). |
| **N6** | Paid OK but the SPA **missed the flip** (user navigated away mid-poll) | Entitlement is already `ACTIVE`. | Refetch `entitlement` on window focus / app re-entry; if `ACTIVE`, unlock silently. Belt-and-suspenders for N2/N5. |
| **N7** | **Amount mismatch / refund** (`REVERTED` / `FAILED`) | Rare. Callback/sweep marks the order terminal. | Poll surfaces the terminal status → failure page showing `reason` / `reasonDetail`. |
| **N8** | **Retryable 409 on create** (lost one-open-order race) | Backend expects a retry that reuses the now-committed open order. | Transparent auto-retry the `POST` once; no user-visible error. |
| **N9** | **Bad pay-as-you-go input** (non-positive, over-scale like `100.123` UZS) | Backend `400`. | Validate client-side first (positive, ≤ 2 dp UZS). Map backend `400` to an inline field error. |
| **N10** | **Token/session expiry mid-flow** | Standard refresh path (session layer already handles 401/403). | If refresh fails, bounce to login, then resume from `GET /orders/current`. |
| **N11** | **Re-pay, same plan** while an order is open | One-open-order lookup returns the existing `PENDING`; its `checkoutUrl` is reused (no new grant). | Don't create duplicate orders — if `current` is open for the same plan, reuse it. |
| **N12** | **Re-pay, different plan** | Backend `supersede`s: cancels the old invoice best-effort, creates fresh. | Just `POST` the new plan; the old order is closed server-side. |

---

## 4. Missing / nice-to-have API endpoints

Ordered by UX impact. Everything the core flow *needs* already exists; these are improvements.

### 4.1 Quote / preview endpoint — IMPLEMENTED (pay-as-you-go)

`GET /api/billing-catalog/pay-as-you-go/days?currency=UZS&amount=790000` → `{ "days": 158 }` (PUBLIC).

The pay-as-you-go day count is derivable client-side (`ceil(amount / pricePerDay)`), but this endpoint
makes the rounding (`ceil`) and per-currency scale validation **authoritative**, so the "you'll get N
days" preview is guaranteed identical to what the order actually grants. Use the catalog per-day price for
instant keystroke feedback, then confirm with this endpoint before submit. A general FIXED-plan quote is
unnecessary (those prices come straight from the catalog).

### 4.2 Cancel / abandon current order — IMPLEMENTED

`POST /api/billing/orders/current/cancel` → the updated `OrderDetailsEntry` (`status: "CANCELED"`,
`reason: "USER_CANCELED"`); **409** if the current order isn't `PENDING`. No body. See
[`monetization-api.md` §4.3/§5.3](./monetization-api.md).

This closes what was the biggest gap: a "changed my mind" user no longer has to wait out the 30-min TTL or
pick a *different* plan to trigger `supersede`. Wire a **"Cancel payment" / "Start over"** button on the
polling and resume screens (N2/N3) straight to this endpoint; on `200` clear the pending UI, on `409`
refetch `current`/`entitlement` and reconcile. The invoice cancel is best-effort, so after a cancel still
trust `entitlement` for the true access state — a payment that landed just before the click can still be
granted by the success callback.

### 4.3 Receipt URL exposure (minor)

Multicard's callback returns `receipt_url`, but `OrderDetailsEntry` doesn't surface it. To show
"Download receipt" on the success page or in order history, add that field to the DTO.

### 4.4 Access-gate enforcement (not a UX endpoint — flag it)

Since `hasAccess` isn't enforced on REST/WS yet, the paywall is advisory only. Building the UI against
`entitlement` is correct, but real gating (data endpoints `403`, `/ws` rejects on `@OnOpen`) is a
separate backend workstream that the UX assumes will land.

### 4.5 Push-based outcome instead of polling (optional)

Polling `/orders/current` is the documented, sufficient model. A lightweight "order PAID" push over the
existing `/ws` connection could remove the poll, but it's not required and adds coupling — leave as
polling for now.

---

## 5. Pages to design

### Core purchase flow
1. **Paywall / Subscribe page** — the locked-app gate and primary conversion surface. Renders 4 plan
   cards (weekly / monthly / yearly / pay-as-you-go) from `GET /api/billing-catalog/plans`. Pay-as-you-go card has an amount
   input with a **live "≈ N days"** estimate. Reflects the N1 "already active" state by disabling fixed
   plans and the N3 renewal nudge. Design this one well.
2. **"Redirecting to payment" interstitial** — brief, between `POST /orders` and the `checkoutUrl`
   redirect. Can be a spinner overlay rather than a full page.
3. **Payment processing / return page** (the `return_url` target) — polls `GET /orders/current`, shows
   "Confirming your payment…", and branches on `PENDING` / `PAID` / terminal. Owns the N2 timeout logic.
4. **Payment success page** — confirms the new expiry date, "Continue to app" CTA, refetches
   `entitlement`.
5. **Payment failed / cancelled page** — shows `reason` / `reasonDetail`, offers **Retry** (reuse
   `checkoutUrl`) and **Choose another plan** (supersede).

### Account / management (persistent, not just first purchase)
6. **Subscription / Account page** — current access state + expiry from `entitlement`, a **Renew** CTA
   (especially within the 5-day window), and the N3/N4 "unfinished payment — resume" banner.
7. **Order history** — from `GET /orders`; each row links to `GET /orders/{id}` and its `/{id}/history`
   audit trail. Optional receipts (needs [§4.3](#43-receipt-url-exposure-minor)).
8. **Access history** — from `GET /entitlement/history` (the ledger): trial + each purchase's stacking
   move. Can be a tab on the Account page rather than a standalone page.

### Cross-cutting UI (not full pages)
9. **Global access guard / banner** in the app shell — reads `entitlement` on boot and on window focus
   (N6), routes to the paywall when locked, and shows the renewal nudge when near expiry.

---

## 6. Implementation checklist (quick reference)

- [ ] Gate the app on `GET /api/billing/entitlement` at boot and on window focus.
- [ ] Paywall reads `GET /api/billing-catalog/plans` (public); disable fixed plans when
      active-beyond-window; keep pay-as-you-go always enabled.
- [ ] Pay-as-you-go: string amount, positive, ≤ 2 dp (UZS), live day estimate.
- [ ] `POST /orders` → redirect to `checkoutUrl`; auto-retry once on a `409` race (N8).
- [ ] Handle `409` active-beyond-window (N1) as an informational state, not an error.
- [ ] `return_url` page polls `GET /orders/current` (~2s); resolve on `PAID`, time out on prolonged
      `PENDING` (N2).
- [ ] Always reconstruct in-flight state from `GET /orders/current` (N3/N4), never only the `POST`
      response.
- [ ] On `PAID`, refetch `entitlement` before unlocking.
- [ ] Never treat the browser return as proof of payment.
