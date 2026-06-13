# Monetization — Roles, Subscriptions & Payments (High-Level Vision)

## Status & Scope

This is a **high-level architectural vision**, not an implementation plan. It establishes the
shape of three features so that future sessions can each produce a detailed, phase-specific plan:

1. **User roles & privileges** (USER / ADMIN)
2. **Subscription / entitlement model** (recurring plans + pay-by-days)
3. **Payment gateway** (provider-agnostic core, Multicard first, UZS first)

**Operating under uncertainty:** the concrete behaviour of the Multicard provider (recurring
charges? card tokenization? webhook format? signature scheme?) is **not yet known**. This plan is
written on explicit *assumptions*, called out where they occur. A later session will investigate
Multicard directly and revise the affected sections. The value of this document is the stable
architecture around the provider, not the provider details.

**Not building yet:** Kaspi, Multicard-beyond-basics, crypto, Russian providers, multi-currency
beyond UZS, mid-session WebSocket expiry enforcement (see "Known Deferred Concerns").

---

## Business Requirements (the "why")

This section captures the business intent behind the architecture so future sessions understand
*what we are trying to achieve*, not just *how*. The technical design in the rest of this document
exists to satisfy these requirements.

### Roles & privileges
- Two roles for now — `USER` and `ADMIN` — with room to grow later.
- Unlike regular users, an `ADMIN` can call the operational/monitoring REST endpoints
  (`MonitoringController`). Today that monitoring information is exposed to every authenticated
  user; that must be fixed so it is ADMIN-only.
- An `ADMIN` never has to pay to use the screener — all REST and WebSocket endpoints are accessible
  to admins at all times by default. ADMIN capabilities may expand further in the future.
- **Migration constraint:** the screener is **already deployed and has real registered users.** Two
  existing users (the owner and the client) must be promoted to `ADMIN` **without dropping or
  resetting any existing accounts.**

### Subscription model
The business rules are deliberately specific:

1. **One tier only** — there is no PRO / Enterprise distinction. Every subscription grants the same
   access; only the billing period differs.
2. The UI offers three fixed-price subscription options (example prices, to be finalized and
   localized):
   - Weekly — e.g. $6
   - Monthly — e.g. $20
   - Yearly — e.g. $200
3. **Cancellation any time.** Cancelling means the subscription will simply not renew once it
   expires. No refund is given — if a user paid for a month and then cancels, they keep full access
   for the remainder of that month, and access ends when the paid period expires.
4. **Auto-renewal by default.** When the last day of the current period is reached, the
   subscription renews automatically, which requires an automatic payment.
5. **Pay-by-days (one-time purchase).** Users who do not want a recurring plan can instead pay for a
   specific number of days:
   - A single day of access has a configurable price (e.g. $1/day).
   - The user enters an arbitrary amount of money (e.g. $790).
   - The system computes how many days that amount buys, **rounding up** on uneven division — so
     $790 at $1/day buys 790 days.
   - This purchase is **not** auto-renewable. It is a deliberate one-time purchase of a fixed number
     of days derived from the amount paid.

   This pay-by-days feature is unusual but is considered one of the most important features, so it
   must be implemented precisely.

### Payment gateway & currencies
- The long-term goal is **many payment options**: card, Kaspi (Kazakhstan), Multicard
  (Uzbekistan), other providers local to Russia and elsewhere, and crypto.
- Consequently users will pay in **different currencies**, and subscription prices (and the
  per-day price) must be **localized** — they will not be the same across countries (e.g. a day of
  access might cost ~100 rubles in Russia).
- **We are not building all of that at once.** We start simple: **Multicard only**, target audience
  **users from Uzbekistan**, and all prices in **Uzbekistani sum (UZS)**. The architecture must
  nonetheless be designed so that additional providers and currencies are additive, not rewrites.

### Guiding principle
Start simple, but design every layer with future scaling (more providers, more currencies, more
roles, more privileges) in mind.

---

## Core Design: Three Independent Layers

The whole feature set decomposes into three layers, each ignorant of the others' internals:

```
┌────────────────────────────────────────────────────────────────────┐
│ 1. ACCESS / ENTITLEMENT  — "can this user use the screener now?"   │
│    Single source of truth (accessExpiresAt). Every gate checks it. │
├────────────────────────────────────────────────────────────────────┤
│ 2. SUBSCRIPTION          — "what recurring arrangement renews it?" │
│    Drives auto-renew. Optional: pay-by-days has no subscription.   │
├────────────────────────────────────────────────────────────────────┤
│ 3. PAYMENT               — "money moved from user to us."          │
│    Provider-agnostic orders + provider adapters + webhooks.        │
└────────────────────────────────────────────────────────────────────┘
```

The unifying insight: **both a subscription renewal and a one-time day-purchase do the same
thing — they push `accessExpiresAt` forward.** The subscription layer is just an auto-renewal
engine on top; pay-by-days skips that engine entirely. This is what makes the "odd" pay-by-days
requirement fall out naturally instead of being a special case.

---

## Layer 1 — Roles & Privileges

### Model
- Add `ADMIN` to the existing `UserRole` enum (column is already `STRING`-mapped — no schema
  change to the column type; ADMIN is purely an additive enum value).
- **Verified:** `JwtAuthenticationFilter` already attaches `ROLE_<role>` as a `GrantedAuthority`
  (`JwtAuthenticationFilter.java:38`). So `.hasRole("ADMIN")` / `@PreAuthorize` will work with no
  filter changes. The only auth-chain change is endpoint matchers.

### Enforcement
- `MonitoringController` (`/api/monitoring/**`) becomes **ADMIN-only** — either via
  `SecurityConfig` request matchers (`.requestMatchers("/api/monitoring/**").hasRole("ADMIN")`) or
  method-level `@PreAuthorize`. Today it is open to any authenticated user; this is the bug to fix.
- ADMIN's privileges are expected to grow later; the role check is the seam for that.

### ADMIN bypasses billing
Because of the layering, the access gate is simply:
```
hasAccess = (role == ADMIN) || (accessExpiresAt != null && now < accessExpiresAt)
```
Admins are never subject to Layer 1 — no payment, full REST + WebSocket access at all times.

### Production migration (existing live users → admins)
Two real users (owner + client) must be promoted **without dropping any accounts**.

**Decision: config-driven bootstrap.** A configuration property holds the admin email list; on
startup an `ApplicationRunner` promotes any matching existing user to `ADMIN` (idempotent, pure
in-place `UPDATE`, survives redeploys, trivial to extend with future admins). No accounts are
touched beyond the role field.

> **Open question (defer to implementation plan):** *where* the admin email list lives
> (`application.yml`, environment variable, dedicated config). Decided later.

---

## Layer 2 — Subscription / Entitlement Model

### Entitlement (the gate)
A single authoritative field: **`accessExpiresAt` (Instant)** on the user (or a small entitlement
record). It is the only thing access gates read. Both renewals and day-purchases move it forward.

### Plans
- **Plan type = enum** (`WEEKLY` / `MONTHLY` / `YEARLY`). A fixed, closed set that drives period
  math in code (+7 / +30 / +365 days). Not stored as DB rows — "subscriptions are all the same",
  so table-driven plan definitions would be flexibility we don't need.
- **Plan price = data**, resolved by `(plan, currency)`. Config-driven now (UZS only); a pricing
  table later. Re-pricing must not require a code change. Localization is additive — the resolver
  signature already takes currency.

### Subscription entity (Layer 2 only)
Fields (conceptual): `plan`, `status` (`ACTIVE` / `CANCELLED` / `EXPIRED`), `currentPeriodEnd`,
`autoRenew` (bool), reference to a saved payment instrument (if/when the provider supports it).

- **`currentPeriodEnd`** = when the current *paid billing cycle* ends and renewal is attempted
  (billing concept).
- **`accessExpiresAt`** = the *hard access cutoff* (entitlement concept).
- They are equal in steady state but **diverge** when: (a) pay-by-days is stacked on top of a
  subscription; (b) a grace period is active (renewal failed but access continues briefly);
  (c) entitlement is the union of all access sources while `currentPeriodEnd` only knows the
  subscription. Keeping them separate is what lets cancel + stacking + grace coexist cleanly.

### Behaviours
- **Cancel** = set `autoRenew = false`. Access continues until `accessExpiresAt`; no refund. Exactly
  the stated requirement.
- **Auto-renew** = a scheduled sweep finds subscriptions where `autoRenew` is true and
  `currentPeriodEnd` is approaching/passed, charges the saved instrument, and on success extends
  both `currentPeriodEnd` and `accessExpiresAt`.
  > **Assumption (Multicard-dependent):** this requires the provider to support saving a card and
  > re-charging it (tokenized recurring). **If Multicard cannot do this**, auto-renew degrades to
  > "notify the user before expiry with a payment link." The auto-renew engine is therefore designed
  > behind an interface so the silent-charge vs. notify-link behaviour can be swapped without
  > touching the subscription model. Revisit once Multicard is investigated.

### Pay-by-days (the one-time purchase)
- `days = ceil(amountPaid / pricePerDay)` — both `pricePerDay` and currency configurable. `ceil`
  handles uneven division (e.g. $790 → 790 days; partial day rounds up).
- **No subscription row.** It records a one-time order and does
  `accessExpiresAt = max(now, accessExpiresAt) + days`.
- **Stacking confirmed:** buying days while a subscription is active **moves `accessExpiresAt`
  forward** on top of the existing period (single-timestamp behaviour). Generous and simple.

### Free trial
- New accounts receive a **one-week free trial**, modelled as `accessExpiresAt = createdAt + 7d`.
  Backend treats this exactly like a weekly entitlement (no payment, no subscription row).
- **Backend must expose the access *type*** so the frontend can render correctly: the UI shows
  "Free plan" during the trial, then prompts the user to choose and pay for a subscription. So the
  entitlement state needs to distinguish at least: `TRIAL`, `SUBSCRIPTION_ACTIVE`,
  `SUBSCRIPTION_GRACE`, `PREPAID_DAYS`, `EXPIRED` (exact set finalized in the detailed plan).

### Grace period
- On failed auto-renew (or a lapsed subscription the user forgot to extend), **do not cut access
  immediately.** A grace period keeps access alive for a configurable number of days while
  renewal is retried / the user is nudged.
  > **Open question (defer):** exact grace duration and retry cadence. Decided later.

### Money handling
- Use **`BigDecimal`** (or integer minor units) for all monetary values — standard payment
  practice. The CLAUDE.md "no `BigDecimal`" rule applies to the **market-data hot path only**; the
  billing module is low-frequency and correctness-critical, so the rule does not apply here. This
  exception will be documented in code so it is not "optimized away" later.

---

## Layer 3 — Payment Gateway

### Provider-agnostic core + adapters
- A `PaymentProvider` interface: `createPayment(order) → checkoutUrl`,
  `verifyWebhook(payload) → result`, and (optionally, provider-permitting)
  `chargeSaved(instrument, amount)` for recurring. `MulticardProvider` is the first implementation.
  The core never imports anything provider-specific. Future providers (Kaspi, crypto, Russian
  options) are additive adapters.

### Order / Transaction state machine (provider-neutral)
```
CREATED → PENDING → PAID | FAILED | EXPIRED
```
Each order records **what it is for** (a subscription plan period, or N prepaid days) so the
webhook handler knows which entitlement to grant on success.

### Canonical redirect flow (assumed Multicard shape)
1. User picks a plan / enters an amount → backend creates a `PENDING` order; price resolved in UZS.
2. Backend asks Multicard to create an invoice → receives a checkout URL → user is redirected.
3. User pays on Multicard's hosted page.
4. Multicard calls our **webhook** (public, signature-verified) with the result.
5. We verify the signature, mark the order `PAID` **idempotently**, and grant the entitlement
   (extend `accessExpiresAt`; activate/extend the subscription if applicable).

> **Assumption:** Multicard is a redirect + webhook provider. The exact API, payload, and signature
> scheme are unknown and will be filled in after investigation.

### Non-negotiables for the payment flow
- **Signature verification** on every webhook — never trust an unsigned callback.
- **Idempotency** — providers retry callbacks; granting access twice for one payment must be
  impossible (unique constraint on provider transaction id + state-guarded transition).
- **Reconciliation fallback** — a poller that queries the provider for the status of stale
  `PENDING` orders, so a lost webhook never leaves a paid user without access.

### Pricing & currency
- A `PricingService` resolving `(plan, currency) → amount` and `(currency) → pricePerDay`.
- Start config-driven, **UZS only**. The resolver signature already accepts currency, so adding
  KZT/RUB/crypto later is additive. Provider and currency are **linked dimensions**
  (Multicard→UZS, future Kaspi→KZT, …); model that pairing even with one of each today.

---

## Cross-Cutting Enforcement Points

The Layer-1 access gate must be applied at:
- **REST endpoints** — paid endpoints (rules CRUD, tickers, screener data) require active
  entitlement; `/api/auth/**` stays public; `/api/monitoring/**` is ADMIN-only.
- **WebSocket `/ws` `@OnOpen`** — reject the connection if the user has no active entitlement and
  is not ADMIN.

---

## Known Deferred Concerns (mentioned, not solved here)

- **Mid-session WebSocket expiry.** A `/ws` session is long-lived; a user whose `accessExpiresAt`
  passes *while connected* should eventually be disconnected. The `@OnOpen` check alone does not
  cover this. **This plan only flags the concern — it deliberately proposes no solution.** To be
  addressed in a future session.
- **Multicard specifics** — recurring support, tokenization, API/webhook/signature format. Drives
  the final shape of auto-renew and the payment adapter. To be investigated and folded in next.
- **Exact grace-period duration and retry cadence.** Deferred.
- **Admin email list location** (config vs env vs dedicated). Deferred.
- **Full entitlement-state enum** finalization. Deferred.

---

## Suggested Build Order (rationale, not a commitment)

1. **Roles + ADMIN bootstrap + lock down `/api/monitoring`** — small, unblocks everything, fixes a
   real exposure today.
2. **Entitlement layer (`accessExpiresAt`, free trial, access gate at REST + `@OnOpen`)** — the
   spine the other two layers attach to.
3. **Payment core + Multicard adapter + webhooks + reconciliation** — enables the first real money
   flow (pay-by-days and one-time subscription purchases).
4. **Subscription auto-renew engine** — last, because it is the riskiest piece and most dependent
   on Multicard's actual capabilities.

Each step above becomes its own detailed plan in a future session.
