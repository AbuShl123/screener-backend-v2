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
A single authoritative field: **`accessExpiresAt` (Instant)** on the user (or a small entitlement
record). It is the only thing access gates read. Every successful purchase moves it forward:
```
accessExpiresAt = max(now, accessExpiresAt) + grantedDuration
```
Buying while access is still active **stacks** — the new duration is added on top of the remaining
time. Generous and simple.

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
The backend exposes the current access **state** so the UI can render correctly. With auto-renew
gone, the set is small:
- `TRIAL` — within the free week
- `ACTIVE` — paid access (from a fixed plan or prepaid days) currently valid
- `EXPIRED` — no valid access; user must purchase

(Finer granularity — e.g. distinguishing trial-vs-paid or surfacing the source of the current
period — can be added later if the UI needs it. The exact enum is finalized in the detailed plan.)

### Enforcement points
The access gate is applied at:
- **REST endpoints** — paid endpoints (rules CRUD, tickers, screener data) require active
  entitlement; `/api/auth/**` stays public; `/api/monitoring/**` is ADMIN-only.
- **WebSocket `/ws` `@OnOpen`** — reject the connection if the user has no active entitlement and is
  not ADMIN.

---

## Purchases: Plans & Pricing

### Plan type
- **Plan type = enum** (`WEEKLY` / `MONTHLY` / `YEARLY`). A fixed, closed set that drives the access
  duration in code (+7 / +30 / +365 days). Not stored as DB rows — "subscriptions are all the
  same", so table-driven plan definitions would be flexibility we don't need.

### Pricing
- **Price = data**, resolved by `(plan, currency)`; the per-day price is resolved by `(currency)`.
  Config-driven now (**UZS only**); a pricing table later. Re-pricing must not require a code
  change. Localization is additive — the resolver signature already takes currency, so adding
  KZT/RUB/crypto later is purely additive.
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
CREATED → PENDING → PAID | FAILED | EXPIRED
```
Each order records **what it is for** (which fixed plan, or how many prepaid days) so the webhook
handler knows which entitlement to grant on success. The order also stores the provider's
transaction id for idempotency and reconciliation.

### The canonical redirect flow (now concrete, via Multicard)
1. User picks a plan / enters an amount → backend creates a `PENDING` order; price resolved in UZS.
2. Backend asks Multicard to create an invoice → receives a `checkout_url` and Multicard's
   transaction `uuid` (persisted on the order) → user is redirected to the checkout page.
3. User pays on Multicard's hosted page (card, or apps like Payme/Click/Uzum).
4. Multicard sends our **webhook** (public, signature-verified) on each status change.
5. On a `success` status we verify the signature, mark the order `PAID` **idempotently**, and grant
   the entitlement (extend `accessExpiresAt`).

### Non-negotiables for the payment flow
- **Signature verification** on every webhook — never trust an unsigned callback.
- **Idempotency** — providers retry callbacks; granting access twice for one payment must be
  impossible (unique constraint on the provider transaction id + a state-guarded transition).
- **Reconciliation fallback** — a poller that queries the provider for the status of stale
  `PENDING` orders, so a lost webhook never leaves a paying user without access.

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
- **Webhooks (chosen notification mechanism).** Multicard offers two callback styles; we use the
  **full webhooks** that fire on *every* status change (`draft / progress / success / error /
  revert / hold`), because they give us complete visibility into failures and reversals, not just
  the happy path. Each webhook is **signed** (an SHA-1 hash over a fixed field string that includes
  our secret) and is sent from a **fixed Multicard IP**, so we can verify both the signature and
  the source IP. Non-2xx responses are retried, which is exactly why idempotency is mandatory.
- **Idempotency key = Multicard `uuid`.** The provider explicitly documents that retried callbacks
  may repeat for the same transaction; we key idempotent grants on its `uuid`.
- **Reconciliation.** `GET /payment/invoice/{uuid}` returns an invoice's current status, which the
  reconciliation poller uses to resolve stale `PENDING` orders.
- **Fiscalization (`ofd`) — deferred to production.** Multicard's invoice API can require fiscal
  receipt data (an `ofd` block with Uzbek tax classification codes). This is tied to having an
  **official merchant agreement**. We do not have one yet, so for now we use Multicard's
  **published test/sandbox credentials** (see `external-docs/payments/multicard/README.md`), where
  fiscalization is not a concern. Wiring real fiscal data is a **go-to-production** task, not part
  of the initial build.

---

## Money Handling (designed for provider diversity)

- The billing module is low-frequency and correctness-critical. The CLAUDE.md **"no `BigDecimal`"
  rule applies to the market-data hot path only** and explicitly does **not** apply here. This
  exception will be documented in code so it is not "optimized away" later.
- Money is modelled as an **amount + currency** abstraction, stored canonically as **integer minor
  units**. Multicard fits this perfectly (integer tiyin), so the Multicard path needs no
  `BigDecimal`.
- **Future-proofing:** a later provider may price in fractional units or demand decimal arithmetic
  (rates, crypto conversions, percentage fees). The money abstraction must not bake in
  "integer-only" assumptions — it should allow a `BigDecimal`-backed representation where a future
  provider requires it, without reworking the billing core. Provider adapters convert between the
  canonical money model and their own wire format.

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
