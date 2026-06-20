# Monetization — Subscriptions & Payments (High-Level Vision)

## Status & Scope

This is a **high-level architectural vision**, not an implementation plan. It establishes the
shape of the monetization feature so that future sessions can each produce a detailed,
phase-specific plan.

**Prerequisites:** User roles (USER / ADMIN) and role-based access control are already implemented.
This plan assumes they exist and focuses exclusively on subscriptions and payments.

**Provider is known.** The first (and currently only) payment provider is **Multicard**
(Uzbekistan, UZS only). Its API has been investigated and the relevant behaviour is folded into
this document — this plan no longer operates on guesses about the provider. See
"How Multicard Works" below. The value of this document is the **stable, provider-agnostic
architecture**; Multicard is the first concrete adapter behind it.

**What we are building now (deliberately simple):**
- A single-tier subscription (weekly / monthly / yearly) sold as a **one-time purchase of a fixed
  access period**.
- A **pay-by-days** one-time purchase.
- The Multicard payment flow (hosted checkout + webhooks) that backs both.

**What we are explicitly NOT building now (deferred — see "Deferred Concerns"):**
- **Auto-renewal / recurring charges.** Multicard *does* support this (card binding + off-session
  token charging), but the business has not asked for it. For now, when a subscription nears or
  passes its expiry, **the user makes a new payment themselves inside the app.** No saved cards, no
  background charging, no grace period, no cancellation flow.
- Other providers (Kaspi, crypto, Russian options), multi-currency beyond UZS, mid-session
  WebSocket expiry enforcement.

---

## Business Requirements (the "why")

This section captures the business intent so future sessions understand *what we are trying to
achieve*, not just *how*.

### Subscription model
1. **One tier only** — there is no PRO / Enterprise distinction. Every subscription grants the same
   access; only the billing period differs.
2. The UI offers three fixed-price subscription options (final UZS prices TBD; the per-day price is
   also configurable):
   - Weekly
   - Monthly
   - Yearly
3. **A subscription is a one-time purchase of a fixed period of access.** It does **not** renew
   automatically. When the period ends, access ends. To keep access, the user buys again from
   inside the app. There is therefore **no cancellation flow and no refund** — a paid period simply
   runs to its end.
4. **Pay-by-days (one-time purchase).** Users who don't want a fixed plan can pay for a specific
   number of days:
   - A single day of access has a configurable price.
   - The user enters an arbitrary amount of money (e.g. the equivalent of $790).
   - The system computes how many days that amount buys, **rounding up** on uneven division — so
     \$790 at $1/day buys 790 days.
   - Like the fixed plans, this is a deliberate one-time purchase; it does not renew.

   This pay-by-days feature is unusual but is considered one of the most important features, so it
   must be implemented precisely.

### Payment gateway & currencies
- The long-term goal is **many payment options**: card, Kaspi (Kazakhstan), Multicard
  (Uzbekistan), other providers local to Russia and elsewhere, and crypto.
- Consequently users will eventually pay in **different currencies**, and prices (and the per-day
  price) must be **localized** — they will not be the same across countries.
- **We start simple: Multicard only, audience Uzbekistan, all prices in Uzbekistani sum (UZS).**
  The architecture must nonetheless be designed so additional providers and currencies are
  **additive, not rewrites.**

### Guiding principle
Start simple, but design every layer so future scaling (more providers, more currencies,
auto-renew, more roles) is additive.

---

## Core Design: Two Domain Concepts + A Provider Boundary

With auto-renewal out of scope, the feature collapses to two domain concepts sitting on top of a
provider-agnostic payment boundary:

```
┌────────────────────────────────────────────────────────────────────┐
│ ENTITLEMENT   — "can this user use the screener right now?"        │
│   Single source of truth: accessExpiresAt. Every gate checks it.   │
│   Admins bypass it entirely (always have access).                  │
├────────────────────────────────────────────────────────────────────┤
│ PURCHASE      — "what did the user buy, and how much access does   │
│                 it grant?"                                         │
│   Fixed plan (W/M/Y) OR pay-by-days. BOTH are one-time purchases   │
│   that push accessExpiresAt forward. No recurring engine.          │
├────────────────────────────────────────────────────────────────────┤
│ PAYMENT       — "money moved from user to us."                     │
│   Provider-agnostic orders + provider adapters + webhooks.         │
│   First (and only) adapter: Multicard.                             │
└────────────────────────────────────────────────────────────────────┘
```

**The unifying insight:** a fixed-plan purchase and a pay-by-days purchase do the *same thing* —
they extend a single timestamp, `accessExpiresAt`. Because nothing renews automatically, there is
**no separate "subscription" entity** to track renewal state. A purchase is just a paid order that,
on success, moves the entitlement forward. This is what makes the "odd" pay-by-days requirement
fall out as a natural variant rather than a special case.

---

## Entitlement & Access Gate

### Entitlement foundation
A single authoritative field: **`accessExpiresAt` (Instant)**, stored on a **separate 1:1
`user_entitlement` record** (not on the `User` entity — keeps identity/auth lean and gives the
entitlement domain its own home). It is the only thing access gates read. Every successful purchase
moves it forward:
```
accessExpiresAt = max(now, accessExpiresAt) + grantedDuration
```
Buying while access is still active **stacks** — the new duration is added on top of the remaining
time. Generous and simple. All mutation runs through one `EntitlementService.extend(...)` method
(shared by trial-grant and purchase-grant). The
entitlement record holds *access facts only* — `access_expires_at` plus `has_paid` (to distinguish
trial from paid). **Update (payment phase):** the foundation plan's earlier "no audit ledger"
decision is **reversed** once real money grants access — an append-only `entitlement_ledger` records
every grant (trial, purchase, future admin gift) so access changes are auditable. Details in the
payment plan. Account/localization facts (currency, country, language) are **not** stored here —
they belong to the account domain (a future `user_settings` table). **Invariant:** every user has
exactly one entitlement row (created in the registration transaction; existing users backfilled);
admins included, their row simply ignored by the gate. Detailed schema in
`subscription-entitlement-foundation-plan.md`.

### Admin bypass
Admins have unlimited access and do not participate in payments at all:
```
hasAccess = (role == ADMIN) || (accessExpiresAt != null && now < accessExpiresAt)
```
All enforcement below applies only to `USER`. Admins are never charged, never expire.

### Free trial
- New accounts receive a **one-week free trial**, modelled simply as
  `accessExpiresAt = createdAt + 7d`. No payment, no order record. The backend treats this
  identically to any other active entitlement.

### Entitlement state (for the frontend)
The backend exposes the current access **state** so the UI can render correctly. The state is
**derived on read** from the stored facts (`accessExpiresAt`, `has_paid`, role) — never stored as a
column (a stored state column would go stale the instant a timestamp passes with no event to flip
it). The set:
- `TRIAL` — granted access (the free week) but never paid
- `ACTIVE` — paid access (from a fixed plan or prepaid days) currently valid
- `EXPIRED` — no valid access; user must purchase
- `ADMIN` — admin bypass; **presentation-only**, reported with `accessExpiresAt = null`. Nothing
  admin-specific is stored — `role == ADMIN` short-circuits the derivation.

Derivation:
```
role == ADMIN                            -> ADMIN
expiresAt == null || now >= expiresAt    -> EXPIRED
!has_paid                                -> TRIAL
else                                     -> ACTIVE
```
For `TRIAL`/`ACTIVE`, `accessExpiresAt` is the same field — only the UI label differs (trial-end vs
subscription-end).

### Enforcement points
The access gate is applied at:
- **REST endpoints** — paid endpoints (rules CRUD, tickers, screener data) require active
  entitlement; `/api/auth/**` stays public; `/api/monitoring/**` is ADMIN-only.
- **WebSocket `/ws` `@OnOpen`** — reject the connection if the user has no active entitlement and is
  not ADMIN.

---

## Purchases: Plans & Pricing

### Plan catalog (revised — DB-driven, not an enum)
- **Plans are DB rows**, not an enum. A plan is a named, pre-priced **bundle of days** — its
  duration is *data*, not code. A `plans` table holds `code`, `display_name`, `type`
  (`FIXED` | `PER_DAY`), `duration_days` (7/30/365 for fixed; null for pay-by-days), `active`, and
  `sort_order`. Business edits the catalog (add quarterly, retire weekly via `active=false`) without
  a code change, and the future admin console gets a CRUD surface for free.
- **Pay-by-days is just a `type = PER_DAY` plan** — same two tables, no special entity. Its price
  row *is* the per-day price.
- **Never hard-delete** a plan referenced by historical orders; soft-disable with `active=false`.
  Orders **snapshot** `days_granted` and `amount_paid` so later re-pricing never alters past grants.

### Pricing
- **Price = data**, resolved by `(plan, currency)` from a `plan_prices` table (DB now, not config).
  Re-pricing is a DML change, never a code change. Localization is additive — `plan_prices` is
  already keyed by currency, so adding KZT/RUB/crypto rows later needs no migration.
- **Currency is resolved server-side** via a `RegionResolver` seam (CDN/IP header → verified phone
  country → user override). Today it is a stub returning UZS for everyone, resolved at request time
  and **persisted nowhere**; when real geo/phone resolution lands, the chosen currency is stored on a
  future `user_settings` account record so pricing stays stable across sessions (and is typically
  locked after the first transaction to prevent price arbitrage). **The client never sends a price or
  currency** — only a plan `code` (or, for pay-by-days, an amount of money).
- Provider and currency are **linked dimensions** (Multicard→UZS, future Kaspi→KZT, …). Model that
  pairing even with only one of each today.

### Pay-by-days math
- `days = ceil(amountPaid / pricePerDay)` — `pricePerDay` and currency configurable. `ceil` handles
  uneven division (e.g. $790 → 790 days; any partial day rounds up).
- No special entity: it records a one-time order and extends `accessExpiresAt` exactly like a plan
  purchase.

---

## Payment Gateway & Orders

### Provider-agnostic core + adapters
A `PaymentProvider` interface the billing core depends on, never importing anything
provider-specific:
- `createPayment(order) → checkoutUrl` — create a hosted-checkout invoice, return the URL to
  redirect the user to.
- `verifyWebhook(payload) → result` — verify the provider's signed status callback.

`MulticardProvider` is the first (and currently only) implementation. Future providers (Kaspi,
crypto, …) are additive adapters behind the same interface.

> **Note on a future method:** auto-renew would add a third method, e.g.
> `chargeSaved(instrument, amount)`, to this interface. It is intentionally **not part of the
> interface now** so the current build stays minimal. Multicard can support it later (see Deferred
> Concerns) without disturbing the rest of the architecture.

### Order state machine (provider-neutral)
```
CREATED → PENDING → PAID
              ├→ EXPIRED   (invoice TTL elapsed, no payment)
              ├→ FAILED    (provider reported an error)
              └→ CANCELED  (superseded / abandoned before payment)
PAID → REVERTED            (refund detected — recorded only, access not revoked)
```
Each order records **what it is for** (which fixed plan, or how many prepaid days) so the webhook
handler knows which entitlement to grant on success. It also stores the provider's transaction id
(for idempotency and reconciliation), a `reason` for non-happy transitions, and the chosen
`payment_provider` + `ps` (payment service: card / Payme / Click / …) for future multi-provider
analytics. Every transition is appended to an **`order_status_history`** table so a payment can be
audited end-to-end. Refunds are out of scope for now (a future admin-console concern); if a `revert`
is ever observed it is recorded as `REVERTED` but access is **not** auto-revoked.

### The canonical redirect flow (now concrete, via Multicard)
1. User picks a plan / enters an amount → backend **reuses an open order or creates a new `PENDING`
   one** (at most one open order per user); price resolved server-side in UZS.
2. Backend asks Multicard to create an invoice → receives a `checkout_url` and Multicard's
   transaction `uuid` (persisted on the order). The endpoint **returns the `checkoutUrl` as JSON**
   (the SPA performs the redirect — the backend never issues a 302).
3. User pays on Multicard's hosted page (card, or apps like Payme/Click/Uzum).
4. Multicard sends our **success callback** (public, signature-verified) once the payment succeeds.
   The browser is separately redirected back to our `return_url` — that redirect is **UX only and
   grants nothing**; the SPA polls our order-status endpoint to learn the outcome.
5. On the callback we verify the signature + source IP, then **grant the entitlement and mark the
   order `PAID` in one transaction, and only then acknowledge** (extend `accessExpiresAt`). The grant
   is idempotent on Multicard's `uuid`.
6. A **reconciliation sweep** is the safety net: for abandoned checkouts (no callback ever fires),
   lost callbacks, and refund detection, a scheduled job resolves stale `PENDING` orders via the
   durable status endpoint. Both the callback and the sweep funnel into the **same idempotent grant**.

### Non-negotiables for the payment flow
- **Signature + source verification** on every callback — never trust an unsigned/unknown-IP call.
- **Grant before acknowledging.** Access must be granted (committed) *before* we return success to
  the provider, so a "200 OK" is never sent for a payment the user didn't actually receive access
  for.
- **Transient vs permanent failure.** A failure we expect to recover from (our DB down) returns a
  retry signal (HTTP 500), not a rejection — the provider freezes funds and retries. A deliberate
  rejection (amount mismatch, unknown order) returns the provider's "reverse this payment" signal.
  Never reject a good payment over a transient error.
- **Idempotency** — providers retry callbacks; granting access twice for one payment must be
  impossible (unique constraint on the provider transaction id + a state-guarded transition).
- **Never grant on the browser redirect.** The post-payment `return_url` is UX only; entitlement is
  granted solely from the verified callback (and the reconciliation safety net).
- **Reconciliation fallback** — a scheduled poller that queries the provider for the status of stale
  `PENDING` orders, so a lost callback never leaves a paying user without access.

---

## How Multicard Works (provider facts behind the adapter)

This section records the concrete provider behaviour so the architecture above is grounded, not
assumed. It stays high-level; exact request/response fields belong in the detailed plan and in
`external-docs/payments/multicard/`.

- **Auth.** `POST /auth` with an `application_id` + `secret` returns a Bearer token valid ~24h. The
  token is cached and reused; it is not re-fetched per request.
- **Money is integer minor units (tiyin).** Every amount is an integer in tiyin (1 UZS = 100
  tiyin). For Multicard, monetary values are integers end-to-end — no fractional arithmetic at the
  boundary. (See "Money handling" for why the architecture still keeps a money abstraction.)
- **Hosted checkout.** `POST /payment/invoice` with our order id, the amount in tiyin, a
  `callback_url`, and a return URL → returns a `checkout_url` (redirect target) and Multicard's
  transaction `uuid`. This maps 1:1 onto `createPayment`.
- **Notification mechanism — the default success-only callback.** Multicard offers two callback
  styles; we use the **default success callback** (fires once, on a successful payment), not the
  richer per-status webhooks. The per-status webhooks must be enabled by Multicard support and add
  five status types to handle — but they do **not** remove the need for a scheduled sweep (an
  abandoned checkout never pushes any event, and lost callbacks still happen), so success-only is
  the simpler complete system. The success callback is **signed with MD5** over
  `{store_id}{invoice_id}{amount}{secret}` and arrives from a **fixed Multicard IP** (`195.158.26.90`);
  we verify both. We must respond **HTTP 200 with `{"success": true}`**; a non-200 *or* `success != true`
  makes Multicard **reverse/refund** the payment, while a **timeout or HTTP 500** makes it **freeze the
  funds and retry** — hence "grant before acknowledging" and the transient-vs-permanent distinction
  above. (If we later adopt per-status webhooks, the signature flips to **SHA-1** over
  `{uuid}{invoice_id}{amount}{secret}`; the adapter isolates this so the switch is config, not a rewrite.)
- **Idempotency key = Multicard `uuid`.** The provider explicitly documents that retried callbacks
  may repeat for the same transaction; we key idempotent grants on its `uuid`.
- **Reconciliation uses the durable `GET /payment/{uuid}`** — **not** `GET /payment/invoice/{uuid}`.
  The invoice endpoint returns `ERROR_TRANS_NOT_READY` / `ERROR_NOT_FOUND` once an invoice is
  expired or cancelled — i.e. it goes blind exactly when we need it. `GET /payment/{uuid}` always
  returns the full payment with an authoritative `status` (`draft/progress/billing/success/error/revert`),
  so the sweep can resolve abandoned, lost-callback, and refunded cases reliably.
- **Fiscalization (`ofd`) — configurable, off by default.** Multicard's invoice API documents `ofd`
  (Uzbek tax classification data) as required, but on the **test/sandbox credentials** the call
  succeeds **without** it (verified). So `ofd` is a **configurable** concern: omitted locally/in
  sandbox, populated in production once a real merchant agreement and tax data exist. Wiring real
  fiscal data is a **go-to-production** task, not part of the initial build.

---

## Money Handling (designed for provider diversity)

- The billing module is low-frequency and correctness-critical. The CLAUDE.md **"no `BigDecimal`"
  rule applies to the market-data hot path only** and explicitly does **not** apply here. This
  exception will be documented in code so it is not "optimized away" later.
- Money is modelled as an **amount + currency** abstraction, stored canonically as a **`BigDecimal`
  in major units** (e.g. UZS sum), column `NUMERIC(19,4)`. Major units keep the value
  provider-agnostic *and* directly displayable to the user (regular users never see tiyin). A
  `BigDecimal` of *minor* units would buy nothing — minor units are integers by definition — so the
  generality only pays off when the stored amount is the natural major-unit value.
- **Provider boundary owns minor-unit conversion.** Each adapter converts the canonical `Money` to
  its own wire format: the Multicard adapter multiplies by 100 to produce integer tiyin
  (`amount.movePointRight(2).longValueExact()`) at the edge. The billing core never deals in tiyin.
- **Future-proofing:** a later provider may price in fractional units or demand decimal arithmetic
  (rates, crypto conversions, percentage fees). `BigDecimal` major units already accommodate this;
  the `NUMERIC` scale is widened additively for crypto's 8+ decimals when needed.

---

## Deferred Concerns (acknowledged, not built now)

- **Auto-renewal / recurring charges.** Out of scope by business decision. **Multicard supports it**
  via card binding (a hosted, no-PCI-DSS form that returns a reusable card token) plus off-session
  ("acceptance-free") charging of that token — subject to Multicard enabling that mode for our
  application. Notably, the hosted-checkout success callback already returns a card token, so a
  future auto-renew could capture it from the first payment without a separate binding step. When
  the business wants recurring billing, this becomes its own plan: add a saved-instrument concept,
  a `chargeSaved` provider method, a renewal sweep, and a grace period. **None of that is built
  now.**
- **Mid-session WebSocket expiry.** A `/ws` session is long-lived; a user whose `accessExpiresAt`
  passes *while connected* should eventually be disconnected. The `@OnOpen` check alone does not
  cover this. Flagged only — no solution proposed here.
- **Fiscalization (`ofd`) for production.** Required once a real Multicard merchant agreement
  exists (see "How Multicard Works"). Not needed against test credentials.
- **Full entitlement-state enum** finalization, and any finer access-type reporting the UI may want.

---

## Suggested Build Order (rationale, not a commitment)

1. **Entitlement layer** (`accessExpiresAt`, free trial, access gate at REST + `@OnOpen`,
   entitlement-state exposure) — the spine the rest attaches to.
2. **Payment core + Multicard adapter + webhooks + reconciliation** — the real money flow that
   backs both fixed-plan purchases and pay-by-days. With auto-renew gone, this is the last major
   piece; once an order can reach `PAID` and grant entitlement, the feature is functionally
   complete.

Each step above becomes its own detailed plan in a future session.
