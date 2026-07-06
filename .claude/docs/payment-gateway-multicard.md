# Payment Gateway & Multicard — Reference Documentation

> **Single source of truth** for how subscription payments work in the screener backend. This
> consolidates four planning docs (`monetization-plan.md`, `subscription-entitlement-foundation-plan.md`,
> `payment-gateway-multicard-plan.md`, `payment-multicard-enhancements-plan.md`) into one reference,
> reconciled against the **actual committed code** (commit `154e810` + the E10 follow-up changes). Where
> a plan and the code diverged, the **code wins** and this doc reflects the code.
>
> Read this instead of the plan files. The plans remain only as a historical record of *why* decisions
> were made; this doc is *what was built*.

---

## 1. Business Requirements & Intent

### What is being sold

A **single-tier subscription** to the screener. There is no PRO/Enterprise distinction — every paid
subscription grants the *same* access; only the billing period differs. Access is sold as a **one-time
purchase of a fixed period**, sold four ways:

| Plan code       | Type      | Grants            |
|-----------------|-----------|-------------------|
| `weekly`        | `FIXED`   | 7 days            |
| `monthly`       | `FIXED`   | 30 days           |
| `yearly`        | `FIXED`   | 365 days          |
| `pay_as_you_go` | `PER_DAY` | `ceil(amount / pricePerDay)` days |

**Pay-by-days** is the unusual, business-critical feature: the user enters an arbitrary amount of money,
and the system computes how many days that buys, **rounding up** on uneven division (e.g. at 1 unit/day,
790 units → 790 days; 7900.50 → rounds up). It is *not* a special entity — it is just a `PER_DAY` plan
whose price row is the per-day price.

### Deliberate non-goals (deferred, not built)

- **No auto-renewal / recurring charges.** Nothing renews automatically. When access ends, the user buys
  again from inside the app. No saved cards, no background charging, no grace period.
- **No cancellation flow, no refunds.** A paid period simply runs to its end. (A refund *detected* from
  the provider is recorded but never auto-revokes access.)
- **No multi-provider / multi-currency selection wiring.** Multicard only; audience Uzbekistan; all live
  prices in UZS. (The data model is multi-currency-ready — see §9 — but only UZS is seeded/resolved.)
- **No access-gate enforcement yet.** `EntitlementService.hasAccess(...)` exists but is **not** wired
  into any REST endpoint or the WebSocket `@OnOpen`. That is a separate "enforcement plan."
- **No production fiscalization (`ofd`) tax data.** The `ofd` payload is a placeholder (see §8).

### Guiding principle

**Start simple, but make every layer additive.** Adding a second provider, a new currency, or
auto-renew later must be an *extension*, not a rewrite. This is why money is provider-agnostic
`BigDecimal` major units, plans/prices are DB rows (not enums), currency is resolved through a seam, and
the provider boundary is a two-method interface.

---

## 2. The Three Domain Concepts

With auto-renewal out of scope, the feature collapses to three layers over a provider boundary:

```
ENTITLEMENT  — "can this user use the screener right now?"
               One authoritative field: accessExpiresAt. Admins bypass entirely.
PURCHASE     — "what did the user buy, and how much access does it grant?"
               An Order: fixed plan OR pay-by-days. Both push accessExpiresAt forward.
PAYMENT      — "money moved from user to us."
               Provider-agnostic Orders + a PaymentProvider adapter + a success callback + a sweep.
```

**The unifying insight:** a fixed-plan purchase and a pay-by-days purchase do the *same thing* — they
extend one timestamp, `accessExpiresAt`, using the stacking rule
`accessExpiresAt = max(now, accessExpiresAt) + grantedDuration`. Because nothing renews, there is **no
separate "subscription" entity** tracking renewal state. A purchase is just a paid order that, on
success, moves the entitlement forward.

---

## 3. Package & File Map

```
billing/                         (catalog + pricing + currency — pre-existing, extended by E10)
  Plan, PlanType, PlanPrice       JPA entities; price is BigDecimal major units NUMERIC(38,18)
  PlanRepository, PlanPriceRepository
  Currency                        NEW (E10): enum {UZS(2),USD(2),BTC(8),ETH(18)} — decimals source of truth
  RegionResolver/DefaultRegionResolver   currency seam (UZ/UZS stub)
  PricingService, BillingController      GET /api/billing/plans
  PlanAdminService, PlanAdminController  ADMIN /api/admin/billing/**

entitlement/                     (access state — pre-existing, extended for the ledger)
  UserEntitlement                 1:1 with users; accessExpiresAt + hasPaid (+ @Version)
  EntitlementService              startTrial + extend (now writes the ledger)
  EntitlementLedger, EntitlementLedgerRepository   NEW: append-only grant audit
  GrantSource                     NEW: enum {TRIAL, PURCHASE, ADMIN}
  AccessState, EntitlementView, EntitlementController  (+ GET /api/billing/entitlement/history)
  dto/EntitlementLedgerEntry      NEW: ledger read row; embeds the purchase's OrderDetailsEntry

payment/                         (NEW: orders + provider boundary)
  Order                           JPA entity; @Version; snapshots grantedDurationSeconds + amount
  OrderStatus                     CREATED|PENDING|PAID|EXPIRED|FAILED|CANCELED|REVERTED
  OrderReason                     canonical reason codes (self-documenting)
  OrderSource                     API|CALLBACK|RECONCILIATION|SYSTEM
  OrderStateMachine               legal transitions + writes order_status_history
  OrderStatusHistory, OrderStatusHistoryRepository   append-only audit (+ monotonic seq)
  OrderRepository                 incl. findByIdForUpdate (pessimistic lock)
  OrderService                    create/reuse + pay-by-days + the idempotent markPaidAndGrant
  OrderController                 /api/billing/orders/**
  PaymentReconciliationService    @Scheduled sweep over PENDING orders
  PaymentProvider                 the provider-agnostic boundary (interface)
  CheckoutSession, ProviderPayment, ProviderStatus   boundary value types
  dto/                            CreateOrderRequest, OrderDetailsEntry (one order view for create +
                                  status reads), OrderHistoryEntry
  multicard/
    MulticardClient               WebClient wrapper: auth-token cache, invoice, getPayment, cancel
    MulticardPaymentProvider      implements PaymentProvider; tiyin conversion; status mapping; ofd
    MulticardSignature            MD5 verify (SHA-1 stub for future full webhooks)
    MulticardCallbackController   public POST /api/payment/multicard/callback
    MulticardCallbackService      verify + grant-before-acknowledge; returns CallbackOutcome
    CallbackOutcome               OK | REJECT | REJECT_BAD_SIGN | REJECT_BAD_SOURCE | RETRY
    MulticardException
    dto/                          MulticardAuthResponse, MulticardInvoiceRequest/Response,
                                  MulticardPaymentResponse, MulticardCallbackPayload, MulticardError

config/
  PaymentProperties               screener.payment.* (+ nested MulticardProperties)
  WebClientConfig                 adds multicardWebClient bean
  SecurityConfig                  adds public callback matcher

db/migration/
  V8__create_orders.sql                    (orders in final shape: version column + NUMERIC(38,18) amount)
  V9__create_order_status_history.sql      (history in final shape: seq identity + (order_id, seq DESC) index)
  V10__create_entitlement_ledger.sql
  V11__payment_concurrency_and_audit.sql   (pre-existing tables only: user_entitlement.version + plan_prices → NUMERIC(38,18))
```

---

## 4. Data Model

All money is stored in **major units** (UZS sum) as `BigDecimal` / `NUMERIC`. Tiyin (minor units) never
touch the DB — conversion happens only at the Multicard boundary.

### `orders` (V8) — one row per purchase attempt

Key columns: `status`, `granted_duration_seconds` (snapshot of the grant — `FIXED` =
`duration_days*86400`, `PER_DAY` = `ceil(amount/pricePerDay)*86400`), `amount` (snapshot, major units,
`NUMERIC(38,18)`), `currency`, `payment_provider` (`'multicard'`), `provider_uuid` (Multicard
transaction uuid, set after invoice creation), `ps` (payment service from callback), `checkout_url`,
`expires_at` (`now + invoiceTtl`, 30m), `paid_at`, `version` (optimistic lock). The table is created in
its final shape — the `version` column and `NUMERIC(38,18)` precision are in the `CREATE TABLE`, not a
later `ALTER`.

Three indexes encode the invariants:
- `uq_orders_provider_uuid` (unique, partial `WHERE provider_uuid IS NOT NULL`) — **idempotency**: one
  order per Multicard transaction.
- `uq_orders_one_open_per_user` (unique, partial `WHERE status IN ('CREATED','PENDING')`) — **at most one
  open order per user**.
- `idx_orders_open` (partial `WHERE status = 'PENDING'`) — cheap reconciliation-sweep scan.

### `order_status_history` (V9) — append-only transition audit

Every state change writes one row: `from_status`, `to_status`, `reason` (an `OrderReason` enum code),
`reason_detail` (free-form, e.g. raw provider error text), `source` (`API`/`CALLBACK`/
`RECONCILIATION`/`SYSTEM`), `created_at`, and `seq` (a DB `GENERATED ALWAYS AS IDENTITY` monotonic
sequence). **The `orders` table has no `reason` column** — the reason lives only here, and the "latest
reason" lookup orders by `seq DESC` (deterministic; `created_at` can tie since it's app-clock). The
table is created in its final shape — `seq` and the `(order_id, seq DESC)` index are in `CREATE TABLE`.

### `entitlement_ledger` (V10) — append-only grant audit

Reverses the foundation plan's earlier "no ledger" decision, now that **real money grants access**.
Every `EntitlementService.extend(...)` and `startTrial(...)` writes one row: `user_id`, `source`
(`TRIAL`/`PURCHASE`/`ADMIN`), `granted_duration_seconds`, `previous_expires_at`, `new_expires_at`,
`order_id` (for purchases), `admin_id` (for future admin grants), `reason`. `order_id`/`admin_id` are
plain UUID columns (not JPA associations) to keep the entitlement domain decoupled from payment.

### `user_entitlement` (+V11) — unchanged except a `version` column for future-proofing.

---

## 5. The Canonical Payment Flow

```
1. User picks a plan / enters an amount
        │  POST /api/billing/orders { planCode, amount? }
        ▼
2. OrderService.createOrReuse:
     resolve plan + price (server-side currency via RegionResolver)
     reuse / supersede / expire any existing open order (one-open-order invariant)
     create Order(CREATED), saveAndFlush  → assigns id, enforces one-open index
     provider.createCheckout(order)       → Multicard POST /payment/invoice
        → persist provider_uuid + checkout_url; transition → PENDING
     return { orderId, status: PENDING, checkoutUrl }   (JSON; backend never 302s)
        │
        ▼
3. SPA redirects the user to checkoutUrl → user pays on Multicard's hosted page
        │
        ├──────────────────────────────────────────────┐
        ▼                                               ▼
4a. Multicard POSTs the SUCCESS CALLBACK          4b. Browser redirected to return_url
    to our callback_url                               (UX ONLY — grants nothing)
        │                                               │
        ▼                                               ▼
5. MulticardCallbackService.handle:               SPA polls GET /api/billing/orders/current
     verify source IP + MD5 signature                 until status flips to PAID
     find order by provider_uuid
     verify amount == order amount
     markPaidAndGrant(CALLBACK)  ─ grant BEFORE acknowledging ─┐
     return 200 {"success": true}                              │
                                                               ▼
6. RECONCILIATION SWEEP (every 1m) is the safety net:   markPaidAndGrant:
     for each PENDING order, GET /payment/{uuid}           (locked, idempotent)
     SUCCESS → verify amount → markPaidAndGrant            order → PAID
     ERROR/REVERT/CANCELED/expired → terminal              entitlementService.extend(PURCHASE)
                                                           writes ledger + history
```

### The non-negotiables (and how the code satisfies them)

| Rule | Implementation |
|------|----------------|
| **Verify every callback** | `MulticardCallbackService.handle` checks source IP == `allowedIp` **and** MD5 signature before touching any order. Failure → HTTP 400, unprocessed. |
| **Grant before acknowledging** | `markPaidAndGrant` is its own `@Transactional` method that **commits** before `handle` returns `OK`. The callback service is deliberately **not** `@Transactional`. A `200 OK` is never sent for access the user didn't actually receive. |
| **Idempotency** | Unique index on `provider_uuid` + a pessimistically-locked re-check (`status == PAID → return`) in `markPaidAndGrant`. Callback and sweep both funnel through it; the row lock serializes them; the loser short-circuits. |
| **Transient vs permanent failure** | Our-side failure (DB/lock) → `CallbackOutcome.retry()` → **HTTP 500** (Multicard freezes funds + retries). Deliberate rejection (amount mismatch, unknown order) → `200 {"success": false}` → Multicard **refunds**. Bad sign/IP → **400**, unprocessed. Never refund a good payment over a transient error. |
| **Never grant on the browser redirect** | `return_url` is UX only. Entitlement is granted solely from the verified callback (and the sweep). The SPA learns the outcome by polling. |
| **Reconciliation fallback** | `PaymentReconciliationService` resolves abandoned checkouts, lost callbacks, and refunds via the durable `GET /payment/{uuid}`. |

---

## 6. The Provider Boundary

The billing core depends on a **two-method** interface and never imports anything Multicard-specific:

```java
public interface PaymentProvider {
    String id();                                       // "multicard"
    CheckoutSession createCheckout(Order order);       // create invoice → {providerUuid, checkoutUrl}
    ProviderPayment fetchPayment(String providerUuid); // durable GET — for reconciliation
    default void cancelCheckout(String providerUuid) {} // best-effort; default no-op
}
```

- `CheckoutSession(providerUuid, checkoutUrl)` — bakes in the hosted-redirect model. A documented
  **generalization point**: a future crypto/QR or synchronous saved-card provider would generalize this
  to a neutral `PaymentInitiation` (sealed `Redirect`/`Qr`/`Completed`). Not pre-built — only Multicard
  exists today.
- `ProviderPayment(status, ps, amountTiyin, error)` — durable view used by the sweep. `status` is the
  provider-neutral `ProviderStatus` enum: `SUCCESS | ERROR | REVERT | CANCELED | PENDING | NOT_FOUND`.

**The success callback is deliberately NOT on the interface** — different providers post different
shapes/signatures. Each provider gets its own callback controller + service. The interface stays minimal;
a future Kaspi/crypto adapter implements these methods plus its own callback handler.

---

## 7. The Multicard Adapter

Key provider facts (from `external-docs/payments/multicard/`):

- **Auth**: `POST /auth` (`application_id` + `secret`) → Bearer token (~24h). `MulticardClient` caches it
  in an `AtomicReference<CachedToken>` with a conservative 23h local TTL, refetches lazily, and on a
  `401` refetches once and retries (`withAuthRetry`). Refresh is `synchronized` with a double-check.
- **Amounts are integer tiyin** (1 UZS = 100 tiyin). Conversion happens **only** in the adapter:
  `MulticardPaymentProvider.toTiyin(amount)` = `amount.movePointRight(Currency.UZS.decimals()).longValueExact()`.
  Reading `Currency.UZS.decimals()` (E10) instead of a literal `2` keeps the source of truth in one
  place. Because input scale is validated ≤ the currency's decimals up front, `longValueExact()` never
  throws here.
- **Create invoice**: `POST /payment/invoice` with `store_id`, `amount` (tiyin), `invoice_id` (our order
  id), `callback_url`, `return_url`, `ttl` (seconds), `lang`, optional `ofd` → returns `data.uuid`
  (persisted on the order) and `data.checkout_url`. `MulticardInvoiceRequest` uses
  `@JsonInclude(NON_NULL)` so a null `ofd`/`return_error_url` is omitted.
- **Success callback** (`POST` to our `callback_url`): fields `store_id, amount, invoice_id, billing_id,
  payment_time, phone, card_pan, ps, card_token, uuid, receipt_url, sign`. **No `status` field** — the
  default callback fires only on success. We respond `200 {"success": true}` to confirm.
  - **Signature = MD5** of `{store_id}{invoice_id}{amount}{secret}` (concatenated, no separators),
    constant-time compared. `MulticardSignature.sha1Hex` is a **stub** for a future switch to full
    per-status webhooks (which sign with SHA-1 of `{uuid}{invoice_id}{amount}{secret}`).
  - **Fixed source IP** `195.158.26.90`.
- **Durable status**: `GET /payment/{uuid}` → `PaymentModel.status` ∈
  `draft|progress|billing|success|error|revert`. Used by the sweep — **not** `GET /payment/invoice/{uuid}`,
  which returns `ERROR_TRANS_NOT_READY`/`ERROR_NOT_FOUND` once an invoice is expired/cancelled (goes blind
  exactly when we need it).
- **Cancel unpaid invoice**: `DELETE /payment/invoice/{uuid}` — used best-effort when superseding an open
  order.

### Status mapping (`MulticardPaymentProvider.mapStatus`)

| Multicard status | `ProviderStatus` |
|------------------|------------------|
| `success` | `SUCCESS` |
| `error` | `ERROR` |
| `revert` | `REVERT` |
| `cancelled` / `canceled` | `CANCELED` *(E3: undocumented but observed — an invoice becomes cancelled when its TTL elapses or it's deleted before payment. Both spellings guarded.)* |
| `draft` / `progress` / `billing` | `PENDING` |
| anything else | `PENDING` + a WARN log |

`fetchPayment` also maps a `MulticardException` carrying `ERROR_NOT_FOUND` → `ProviderStatus.NOT_FOUND`.

---

## 8. Why Success-Only Callback (not full webhooks)

Multicard offers two callback styles. We use the **default success-only callback** because:
1. It works on test credentials with **no Multicard support ticket** (full webhooks must be enabled by
   support).
2. Full webhooks add five status types **and still require the sweep anyway** — an abandoned checkout
   never pushes any event, and callbacks can still be lost. So success-only is the **simpler complete
   system**: the sweep is mandatory regardless, and success-only callback + sweep covers every case.

The adapter is shaped so switching to full webhooks later is localized (the `sha1Hex` signature stub,
the isolated callback service).

### Fiscalization (`ofd`) — placeholder, off by default

`MulticardPaymentProvider.buildOfd` attaches an `ofd` block **only** when `ofd-enabled`. The current
payload is a **PLACEHOLDER** (`qty`/`price`/`total`/`name` only) — it lacks real `mxik`/`package_code`/
tax-rate data. Config (E9): `application.yml` sets `ofd-enabled: ${MULTICARD_OFD_ENABLED:true}`
(env-overridable, prod default true); `application-local.yml` forces it `false` (sandbox accepts invoices
without `ofd`). **Wiring real tax data is a go-to-production dependency** — enabling `ofd` as-is sends
incomplete fiscal data.

---

## 9. Money Handling

- The billing module is **low-frequency and correctness-critical**, so the CLAUDE.md **"no `BigDecimal`"
  rule (which is hot-path-only) does NOT apply here** — this is documented in code so it isn't
  "optimized away."
- Money is an **amount + currency** abstraction, stored canonically as `BigDecimal` in **major units**
  (sum, not tiyin) — directly displayable to the user, provider-agnostic. Minor-unit conversion lives
  only at the provider boundary.
- **E10 (precision & multi-currency-ready):**
  1. **`Currency` enum** — `{ UZS(2), USD(2), BTC(8), ETH(18) }` is the single source of truth for
     per-currency decimal places. `Currency.of(code)` normalizes case and rejects a malformed/unsupported
     code (`400`); `requireScale(amount)` rejects a value carrying more decimals than the currency allows
     (`400`), tolerating trailing zeros (`19.900` is fine for a 2-dp currency).
  2. **Storage is `NUMERIC(38,18)`** on `orders.amount` (created at that precision in V8) and
     `plan_prices.amount` (widened from the original `NUMERIC(19,4)` in V11, since `plan_prices` predates
     the payment feature) (+ JPA `precision=38, scale=18`). 20 integer digits cover every fiat sum +
     crypto supply; 18 fractional cover ETH. `NUMERIC` is variable-width, so the unused scale on fiat is
     nearly free.
  3. **Per-currency input validation** — pay-by-days (`OrderService.computeDays`) and admin
     `upsertPrice` both call `Currency.of(currency).requireScale(amount)`. This replaced an earlier
     whole-number-only pay-by-days hack, so a within-scale fractional amount (e.g. `7900.50` UZS) is now
     accepted and `100.123` UZS is rejected.
  4. **Money DTOs are strings** — `CreateOrderRequest.amount` and `AdminPriceRequest.amount` are `String`,
     parsed to `BigDecimal` at the boundary (lossless; malformed → `400`). A JSON *number* can lose
     precision when a client serializes from a `double`.
- **Rejected alternative:** minor-unit `BIGINT` (Stripe-style). `Long.MAX ≈ 9.2×10¹⁸` and 1 ETH = 10¹⁸
  wei, so a `BIGINT` of wei tops out at ~9.2 ETH — it cannot represent the crypto case the design
  future-proofs for. `BigDecimal` + `NUMERIC` is the only model holding UZS, USD, BTC, and ETH without
  special-casing.

---

## 10. Concurrency & Correctness (the hardening pass)

The enhancements plan (E1–E10) was a review-driven hardening pass. The load-bearing pieces, as built:

- **One mutation surface for grants (E1).** Only the success callback and the reconciliation sweep grant
  access. `createOrReuse` **never** grants — there is no provider call inside the create transaction.
- **All order mutations are concurrency-safe (E2).** Every status mutation reloads under a pessimistic
  lock (`findByIdForUpdate`) and re-checks current state before transitioning. `createOrReuse`'s two
  branches route through locked helpers: stale-open → `expire(...)`; different-plan → `supersede(...)`.
  Both **flush** their UPDATE before `createNew`'s INSERT — Hibernate emits INSERTs ahead of UPDATEs at
  autoflush, so without the explicit flush the new `CREATED` row and the not-yet-closed old row would
  both satisfy `uq_orders_one_open_per_user` for an instant and trip the partial unique index. `@Version`
  on `Order` is the guarantor for the rare genuine concurrent-close race (stale L1-cache re-check → flush
  fails loudly with `OptimisticLockException`, a safe retryable rollback, never a silent lost update).
- **`OrderStateMachine` no-op on `from == to` (E2).** A benign re-check race returns without writing a
  duplicate history row; illegal transitions still throw.
- **Late-success rescue (E6).** A terminal order (`EXPIRED`/`FAILED`/`CANCELED`) may be resurrected to
  `PAID` — but **only by the `CALLBACK` source** (the authoritative provider push). The state machine
  permits `EXPIRED/FAILED/CANCELED → PAID`; the **source gating lives in `markPaidAndGrant`**. The
  reconciliation sweep never rescues a terminal order (it only scans `PENDING`; a terminal status at lock
  time is a rare race it deliberately leaves). **`REVERTED → PAID` is permanently illegal** — refunded
  money never resurrects.
- **Amount verification in both paths (E5).** The callback rejects on amount mismatch (`AMOUNT_MISMATCH`
  → refund, order left `PENDING` for the sweep). The sweep mirrors this: it verifies
  `payment.amountTiyin == toTiyin(order.amount)` before granting on `SUCCESS`, failing the order on
  mismatch (handles a null amount defensively).
- **Deterministic "latest reason" (E7).** `order_status_history.seq` (monotonic identity) orders the
  latest-transition lookup, not the tie-prone `created_at`.
- **Defensive-only null-uuid sweep branch (E4).** A persisted `PENDING` order *always* has a
  `provider_uuid` (create is single-transaction; a failed `createCheckout` rolls the row back), so the
  branch is unreachable by design — a hit logs at **ERROR** (broken invariant).
- **`X-Forwarded-For` trust (E8, recorded only).** `resolveSourceIp` trusts the first XFF hop, spoofable
  if the app is reachable bypassing the trusted proxy. A prominent code comment flags it; **must be
  revisited** (e.g. `ForwardedHeaderFilter` + trusted-proxy allow-list) before exposing the callback
  outside a trusted-proxy deployment.

### `OrderStatus` state machine

```
CREATED ──► PENDING ──► PAID ──► REVERTED
   │           ├─► EXPIRED
   │           ├─► FAILED            EXPIRED ─┐
   │           ├─► CANCELED          FAILED  ─┼─► PAID  (CALLBACK-only rescue, E6)
   │           └─► REVERTED          CANCELED ┘
   └─► CANCELED / EXPIRED            REVERTED ──► (terminal; never PAID)
```

---

## 11. HTTP API

### Order endpoints — `/api/billing/orders` (Bearer JWT)

| Method | Path | Body | Returns |
|--------|------|------|---------|
| `POST` | `/api/billing/orders` | `{ "planCode": "monthly" }` or `{ "planCode": "pay_as_you_go", "amount": "790000" }` | `OrderDetailsEntry` (the created order, with a usable `checkoutUrl`) |
| `GET`  | `/api/billing/orders` | — | order history, newest first, capped at 100 |
| `GET`  | `/api/billing/orders/current` | — | latest open / most-recent order (UI polls this) |
| `GET`  | `/api/billing/orders/{id}` | — | that order's detail |
| `GET`  | `/api/billing/orders/{id}/history` | — | that order's full status-transition audit, newest first |

- **Client sends only `planCode` (+ `amount` for pay-by-days)** — never a price or currency. The server
  resolves currency via `RegionResolver` and looks up the authoritative price. `amount` is a **string**
  (E10). `planCode` (not `planId`) because the public catalog exposes only `code`, never internal UUIDs.
- **Active-subscription gate (create-time), with a renewal window.** A `FIXED` order is rejected with
  **409** when the caller already holds a paid subscription that is **not yet near expiry** —
  `OrderService.createOrReuse` calls `EntitlementService.hasPaidAccessBeyondRenewalWindow(user)` right
  after resolving the plan and before any reuse/create work. The check is intentionally narrower than
  `hasAccess`: it is true only when `has_paid` **and** `accessExpiresAt > now + renewalWindow`. So a paid
  subscriber whose access is comfortably in the future cannot stack a redundant fixed period (nothing to
  renew yet; it would muddy the audit), **but once access falls within the renewal window they may buy
  again to extend** — the normal "renew before it lapses" flow. The window is configurable via
  `screener.billing.renewal-window` (ISO-8601 duration, default **`P5D`** = 5 days): access expiring in
  >5 days is blocked, ≤5 days is allowed. Deliberately **not** gated in any case: **trial** users
  (`has_paid = false` → conversion mid-trial still works), **expired** users (free to buy again), a paid
  user **within the renewal window**, and **admins** (`hasPaidAccessBeyondRenewalWindow` returns false
  for `ADMIN` — they bypass entitlement and aren't subscribers, keeping payment testing easy).
  **`PER_DAY` (pay-by-days) is exempt** by business rule — a user may top up arbitrary days at any time,
  even mid-subscription.
- **One order view, `OrderDetailsEntry(orderId, status, planCode, amount, accessDurationSeconds,
  currency, provider, reason, reasonDetail, checkoutUrl, providerUuid, expiresAt, paidAt, createdAt)`** —
  returned on **both** create and status reads (a freshly created order and a polled order carry the same
  facts; the separate `CreateOrderResponse` was dropped — its only extra field, `alreadyPaid`, was always
  `false`). `reason`/`reasonDetail` come from the latest `order_status_history` row (by `seq DESC`). The
  order **snapshot** fields let the SPA confirm what was bought: `planCode` echoes the request; `amount`
  (a JSON number / `BigDecimal`, major units — matching the `PlanDto` output convention, *not* the string
  input DTOs) is the FIXED price or echoed pay-by-days input; `accessDurationSeconds` is the access this
  order buys (the order's snapshotted `granted_duration_seconds`); `currency`/`provider` are
  server-resolved (today always `UZS` / `multicard`) and recorded for the future multi-currency /
  multi-provider world. `checkoutUrl` lets the SPA redirect on create and **recover the hosted-payment
  link** from `current`/`{id}` if it lost the `POST` response; `providerUuid` (the Multicard transaction
  uuid) is exposed for support/debugging.
- History read DTO `OrderHistoryEntry(fromStatus, toStatus, reason, reasonDetail, source, createdAt,
  seq)` — one row per transition from `order_status_history`, ordered by `seq DESC`. The `/{id}/history`
  endpoint is **owner-only** (404 if the order isn't the caller's), like `/{id}`.
- A lost one-open-order create race surfaces as a retryable **409**; the SPA retries and reuses the
  now-committed open order.

### Access-history endpoint — `GET /api/billing/entitlement/history` (Bearer JWT)

Lives in the **entitlement** domain (`EntitlementController` → `EntitlementService.listAccessHistory`),
not under `/orders`, because it reports access grants, not orders. Returns the caller's
`entitlement_ledger` rows newest-first as `EntitlementLedgerEntry(source, grantedDurationSeconds,
previousExpiresAt, newExpiresAt, order, reason, createdAt)`: `source` is `TRIAL`/`PURCHASE`/`ADMIN`;
`previousExpiresAt`/`newExpiresAt` bracket the stacking move; `order` is the full `OrderDetailsEntry` for
a `PURCHASE` grant's `order_id` (resolved at read time — the ledger stores only the id so the domains
stay decoupled at the persistence layer), or `null` for trial/admin grants. `EntitlementService` injects
`OrderService` **`@Lazy`** to break the bean cycle (`OrderService` → `EntitlementService` for grants;
`EntitlementService` → `OrderService.findOrderDetails` only at read time).

### Callback endpoint — `POST /api/payment/multicard/callback` (PUBLIC)

Secured by **signature + source IP**, not JWT (registered as `permitAll` in `SecurityConfig`). Maps
`CallbackOutcome` → HTTP:

| Outcome | HTTP | Multicard reaction |
|---------|------|--------------------|
| `OK` | `200 {"success": true}` | confirms the payment |
| `REJECT(reason)` | `200 {"success": false, "message": reason.description}` | **refunds/reverses** |
| `REJECT_BAD_SIGN` / `REJECT_BAD_SOURCE` | `400` | treated as unprocessed (likely forged) |
| `RETRY` | `500` | **freezes funds + retries** |

---

## 12. Configuration

`screener.payment.*` (record `PaymentProperties` + nested `MulticardProperties`), registered via
`WebClientConfig`'s `@EnableConfigurationProperties`. Secrets via env vars (mirroring `jwt`/`admin`):

```yaml
screener:
  payment:
    reconciliation-interval: PT1M
    multicard:
      base-url: https://dev-mesh.multicard.uz        # prod: https://mesh.multicard.uz
      application-id: ${MULTICARD_APP_ID:}
      secret:         ${MULTICARD_SECRET:}
      store-id:       ${MULTICARD_STORE_ID:}
      callback-url:   ${MULTICARD_CALLBACK_URL:}      # public URL of our callback endpoint
      return-url:     ${MULTICARD_RETURN_URL:}        # SPA post-payment landing page
      invoice-ttl:    PT30M
      allowed-ip:     195.158.26.90
      lang:           ru
      ofd-enabled:    ${MULTICARD_OFD_ENABLED:true}   # application-local.yml forces false
```

`PaymentProperties` supplies safe defaults in its compact constructor (base-url sandbox, ttl 30m,
allowed-ip, lang `ru`, ofd false), so a partially-configured environment still boots. A dedicated
`multicardWebClient` bean (base URL + JSON codecs, no weight filter) is added to `WebClientConfig`.
Sandbox test credentials live in `application-local.yml` for local runs.

**Sandbox test data** (`external-docs/payments/multicard/README.md`): `application_id: rhmt_test`,
`secret: Pw18axeBFo8V7NamKHXX`, `store_id: a1df872e-d5aa-11ee-8de8-005056b4367d`; test card
`8600533364098829` exp `28/06` OTP `112233`.

---

## 13. Edge Cases — Quick Reference

| Scenario | Handling |
|----------|----------|
| **Abandoned checkout** (never pays) | No callback fires. Sweep marks `EXPIRED` once `expires_at` passes (PENDING+stale, or `CANCELED`/`NOT_FOUND` from the provider). |
| **Lost/failed success callback** | Sweep's `GET /payment/{uuid}` sees `success`, verifies amount, runs the same `markPaidAndGrant`. |
| **Duplicate/retried callback** | `provider_uuid` unique + `PAID`-state guard → second call no-ops, returns `200 {success:true}`. |
| **Active paid user buys a fixed plan again, expiry far off** | Rejected at create with **409** (`hasPaidAccessBeyondRenewalWindow` gate: paid + `accessExpiresAt > now + renewalWindow`). |
| **Active paid user buys a fixed plan within the renewal window** | Allowed — access expiring in ≤ `renewalWindow` (default 5d) may renew; the new period stacks on the remaining time. |
| **Trial/expired user, or admin, buys a fixed plan** | Allowed — none are gated (trial converts, expired re-buys, admin bypasses entitlement). Pay-by-days is always exempt. |
| **Lost-tab re-pay, same plan** | One-open-order lookup returns the existing `PENDING`; reuse its `checkoutUrl` (no grant — sweep/callback flips to PAID within a cycle, E1). |
| **Re-pay, different plan** | Locked `supersede`: cancel old invoice best-effort, mark old `CANCELED`/`SUPERSEDED`, create fresh. |
| **Race expired the order, then a real success callback arrives** | Callback rescues `EXPIRED/FAILED/CANCELED → PAID` (E6). Sweep does not. |
| **Refund / `revert`** | Detected only via sweep. Recorded `REVERTED`; access **not** revoked. |
| **Amount mismatch** | Callback → `200 {success:false}` (refund), order left `PENDING`. Sweep → `FAILED`/`AMOUNT_MISMATCH`. |
| **Transient DB failure mid-grant** | Callback → `500`; Multicard freezes + retries; sweep backstops. |
| **Multicard token expired** | `MulticardClient` catches `401`, refetches token, retries once. |
| **Pay-by-days uneven/fractional** | `ceil(amount/pricePerDay)`. Non-positive → `400`. Over-scale amount for the currency → `400`. |
| **Browser redirect before grant** | `return_url` grants nothing; SPA polls `/orders/current` until `PAID`. |

---

## 14. Test Coverage

Plain JUnit, matching existing style (under `src/test/.../payment/` and `.../billing/`):

- **`OrderServiceTest`** — fixed-plan create; pay-by-days `ceil` (exact / +1 partial / reject
  non-positive / within-scale fractional accepted / over-scale rejected); reuse same plan (no grant);
  supersede different plan; expired-open recreate; one-open-order flush ordering; **active-subscription
  gate (FIXED rejected with 409 when paid-beyond-window; pay-by-days exempt)**.
- **`OrderStateMachineTest`** — `from == to` no-op; `EXPIRED/FAILED/CANCELED → PAID` legal,
  `REVERTED → PAID` illegal.
- **`MulticardCallbackServiceTest`** — happy grant; idempotent replay; unknown order; amount mismatch;
  bad IP/sign → 400; transient → RETRY; late-success rescue via callback; `REVERTED` never grants.
- **`MulticardPaymentProviderTest`** — `Money`→tiyin; `ofd` only when enabled; status mapping incl.
  `cancelled`/`canceled` → `CANCELED`.
- **`MulticardSignatureTest`** — known-vector MD5; tamper → invalid.
- **`PaymentReconciliationServiceTest`** — `SUCCESS`+match grants; mismatch → `FAILED`; `error` →
  `FAILED`; `revert` → `REVERTED` (no grant); `CANCELED`/stale → `EXPIRED`; one bad order doesn't abort
  the sweep.
- **`EntitlementServiceTest`** — `extend` writes a ledger row with correct prev/new expiry; `startTrial`
  ledger row; stacking from past vs future expiry; **`hasPaidAccessBeyondRenewalWindow` gates only a
  paid sub expiring beyond the window — trial / expired / within-window / admin / no-row all allowed**.
- **`CurrencyTest`** — `decimals()`; `of` normalize/reject; `requireScale` accept-within / reject-over /
  trailing-zeros tolerated.
- **`PlanAdminServiceTest`** — over-scale price rejected; existing tests use string amounts.

---

## 15. Still Deferred (open items)

- **Auto-renewal / saved cards / recurring charges.** Multicard *can* support it (the hosted-checkout
  success callback already returns a `card_token`), but it's out of scope by business decision. Would add
  a saved-instrument concept, a `chargeSaved` provider method, a renewal sweep, and a grace period.
- **Refund initiation** and refund-driven access revocation (admin-console concern). A `revert` is
  recorded only.
- **Per-status (full) webhooks** (SHA-1 signature) — the adapter is shaped for the switch but it's not
  built.
- **Access-gate enforcement** — `hasAccess(...)` exists but isn't wired into REST/`@OnOpen`; plus
  mid-session WS expiry.
- **Production fiscalization (`ofd`)** real tax payload (`mxik`/`package_code`).
- **The `X-Forwarded-For` spoofing fix** (E8) before exposing the callback outside a trusted proxy.
- **Multi-provider / multi-currency selection wiring**, real geo/IP/phone `RegionResolver`,
  `user_settings` account table, currency-immutability policy.
</content>
</invoke>
