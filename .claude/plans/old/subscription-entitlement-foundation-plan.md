# Subscription Catalog & Entitlement Foundation — Detailed Plan

## Status & Scope

This is the **first concrete implementation plan** under the monetization milestone (see
`monetization-plan.md` for the high-level vision). It builds the **data foundation and read APIs**
that the entitlement-enforcement and payment layers will sit on top of. Implement this before any
payment-provider work.

**What this plan delivers:**
- A **DB-driven subscription catalog** (`plans` + `plan_prices`) — weekly / monthly / yearly **and**
  pay-by-days, all as rows. No plan-type enum driving durations in code.
- A **pricing/region resolution seam** — currency resolved server-side per request; UZS-only today,
  additive later. Stub geo resolver, nothing persisted yet.
- A **1:1 `user_entitlement` table** holding the authoritative `accessExpiresAt` plus the one extra
  fact (`has_paid`) needed to derive access state. Purely about *access*, nothing else.
- **Free-trial seeding** on registration, and a **backfill** of all existing users.
- **Read endpoints**: catalog (`GET /api/billing/plans`) and access state (on `/api/auth/me` plus a
  dedicated `GET /api/billing/entitlement`).
- **Admin catalog-management endpoints** — ADMIN-only CRUD over `plans` and `plan_prices` so the
  business can edit the catalog over HTTP (not just by hand-editing SQL). Restricted exactly like the
  existing `/api/monitoring/**` endpoints. Delete is a **soft-disable** (`active=false`), never a hard
  delete, to preserve rows referenced by future orders.

**What this plan explicitly DEFERS (becomes the next plans):**
- **Access-gate enforcement** at REST endpoints and WebSocket `@OnOpen`. This plan exposes the
  entitlement *state*; wiring it into Spring Security + the WS endpoint is the opening of the
  entitlement-enforcement plan.
- **Orders, payment providers, Multicard, webhooks, reconciliation, pay-by-days math** — the payment
  plan. The tiyin (minor-unit) conversion lives there, at the provider boundary.
- **Real geo/IP/phone resolver, multi-currency seed (KZT/RUB/crypto), a `user_settings` account
  table** (currency / country / locale) — localization/provider expansion.
- **Plan-text translations** (`plan_translations`) and any **audit/ledger** of entitlement changes.

---

## Decisions Locked

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | **Plans are DB rows** (`plans` + `plan_prices`), not an enum. Pay-by-days is a `type = PER_DAY` plan. | A plan is a named, pre-priced bundle of days; duration is data. Business edits the catalog without code changes; future admin console gets a CRUD surface for free. Overrides the vision doc's earlier "plan type = enum". |
| 2 | **Access state is derived on read**, never stored. | A stored state column goes stale the instant a timestamp passes with no event. Store facts (`access_expires_at`, `has_paid`), compute `{TRIAL, ACTIVE, EXPIRED, ADMIN}` per request. |
| 3 | **`user_entitlement` (1:1 with `users`) holds *access only*** — `access_expires_at`, `has_paid`. | Currency/country/locale are *account* facts, not *access* facts; they move to a future `user_settings` table. Keeps the identity/auth `User` lean and entitlement focused. |
| 4 | **Single `accessExpiresAt`, no ledger.** All mutation routed through `EntitlementService.extend(...)`. | Business chose no audit trail now. The service method exists only because trial-grant and (future) purchase-grant share the stacking math — not as ledger scaffolding. |
| 5 | **`ADMIN` is a presentation-only state.** | Admins bypass the gate (`role == ADMIN`); the derived state returns `ADMIN` with `accessExpiresAt = null`. Nothing admin-specific is stored. |
| 6 | **Money is `BigDecimal` in *major units*** (sum, not tiyin); column `amount NUMERIC(19,4)`. | A `BigDecimal` of integer minor units buys nothing; storing major units makes the value provider-agnostic *and* directly displayable in sum. Minor-unit (tiyin) conversion happens only at the provider boundary, in the payment plan. The CLAUDE.md "no `BigDecimal`" rule is hot-path only and does not apply to billing. |
| 7 | **Every user has exactly one `user_entitlement` row** (admins included). | Domain invariant: a non-admin without a row is an orphan. Enforced by the register transaction (new users) + a backfill migration (existing users). Admins' rows are simply ignored by the gate, which also survives an admin→user demotion. |
| 8 | **Admin entitlement rows store `access_expires_at = NULL`, `has_paid = FALSE`** (the gate ignores both via the `role == ADMIN` short-circuit). `has_paid` stays `NOT NULL`. | Admins must satisfy the 1:1 invariant but never receive a trial. `NULL` expiry honestly means "no time-based grant" and makes a *demoted* admin correctly derive to `EXPIRED`. Keeping `has_paid` non-null avoids a three-state boolean for a value that is never read for admins. The backfill therefore **splits by role**. |
| 9 | **Catalog management is admin-CRUD over HTTP**, ADMIN-only, mirroring `/api/monitoring/**`. Delete = soft-disable (`active=false`). | The business edits plans/prices without hand-writing SQL. The richer admin console (translations, `sort_order`, discounts, gifting, entitlement audit) stays deferred — this is just the catalog CRUD surface the data model already supports. |

---

## Data Model

Three new tables. **All money is stored in major units** (UZS sum) as `BigDecimal` / `NUMERIC`.
Conversion to minor units (tiyin, ×100) is a provider-boundary concern handled by the Multicard
adapter in the payment plan — the billing core never touches tiyin.

> **Migration numbering.** The new migrations are **`V5` / `V6` / `V7`**.

### `plans` — the catalog
```sql
-- V5__create_plans.sql
CREATE TABLE plans (
    id            UUID PRIMARY KEY,
    code          TEXT NOT NULL UNIQUE,          -- stable id: 'weekly','monthly','yearly','pay_as_you_go'
    display_name  TEXT NOT NULL,                 -- internal/admin label + English fallback ONLY
    type          TEXT NOT NULL,                 -- 'FIXED' | 'PER_DAY'
    duration_days INT,                           -- 7/30/365 for FIXED; NULL for PER_DAY
    active        BOOLEAN NOT NULL DEFAULT TRUE, -- soft-disable; never hard-delete referenced plans
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT plans_fixed_has_duration
        CHECK ((type = 'FIXED' AND duration_days IS NOT NULL)
            OR (type = 'PER_DAY' AND duration_days IS NULL))
);
```
- `code` is the **single stable identifier**. The frontend keys all user-facing text (via its
  ru/uz/en i18n bundles), styling, and **display order** off `code`. There is intentionally **no
  `sort_order`** column — reintroduce one only when an admin console needs business-controlled
  reordering without a frontend deploy (additive then).
- `display_name` is **not** the user-facing string — it is an admin/log label and English fallback.
  Localized name + description come from the frontend i18n keyed by `code`; a backend
  `plan_translations(plan_id, locale, display_name, description)` table is future work for the admin
  console.

### `plan_prices` — price per (plan, currency)
```sql
-- same migration
CREATE TABLE plan_prices (
    id         UUID PRIMARY KEY,
    plan_id    UUID NOT NULL REFERENCES plans(id),
    currency   CHAR(3) NOT NULL,           -- ISO 4217: 'UZS'
    amount     NUMERIC(19,4) NOT NULL,     -- MAJOR units (sum). FIXED: full period price; PER_DAY: price for ONE day
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT plan_prices_amount_nonneg CHECK (amount >= 0),
    CONSTRAINT plan_prices_unique UNIQUE (plan_id, currency)
);
```
`NUMERIC(19,4)` covers fiat comfortably; widen later for crypto's 8+ decimals (additive).

**Seed (UZS only, placeholder amounts in sum — business finalizes):**
```sql
-- weekly / monthly / yearly + pay-as-you-go, plus one UZS price row each.
-- amounts in SUM (major units); values below are PLACEHOLDERS pending business pricing.
-- weekly         :    50000.00 UZS
-- monthly        :   150000.00 UZS
-- yearly         :  1500000.00 UZS
-- pay_as_you_go  :     8000.00 UZS  (price per ONE day)
```

### `user_entitlement` — 1:1 with users, access only
```sql
-- V6__create_user_entitlement.sql
CREATE TABLE user_entitlement (
    user_id           UUID PRIMARY KEY REFERENCES users(id),
    access_expires_at TIMESTAMPTZ,                    -- the single authoritative field; NULL = never granted
    has_paid          BOOLEAN NOT NULL DEFAULT FALSE, -- distinguishes TRIAL from ACTIVE
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Backfill existing users
```sql
-- V7__backfill_user_entitlement.sql
-- One-time: every existing user gets a row so the 1:1 invariant holds before enforcement is ever
-- switched on. The backfill SPLITS BY ROLE:
--   * Non-admins get a FRESH courtesy trial (now + 7d), NOT createdAt-based, so they are not born
--     EXPIRED. Trial length mirrors screener.billing.trial-duration (hardcoded here for the one-time
--     backfill; documented divergence is acceptable).
--   * Admins get (access_expires_at = NULL, has_paid = FALSE): they never receive a trial; the gate
--     ignores both columns via the role == ADMIN short-circuit (Decision #8). NULL expiry also makes
--     a future admin->user demotion correctly derive to EXPIRED.
INSERT INTO user_entitlement (user_id, access_expires_at, has_paid, updated_at)
SELECT id,
       CASE WHEN role = 'ADMIN' THEN NULL ELSE now() + INTERVAL '7 days' END,
       FALSE,
       now()
FROM users
WHERE id NOT IN (SELECT user_id FROM user_entitlement);
```

**State derivation (pure function, no stored column):**
```
state(user, entitlement, now):
    role == ADMIN                                  -> ADMIN     (accessExpiresAt reported as null)
    expiresAt == null || now >= expiresAt          -> EXPIRED
    !has_paid                                       -> TRIAL
    else                                           -> ACTIVE
```

**Stacking (in `EntitlementService.extend`):**
```
accessExpiresAt = max(now, accessExpiresAt) + grantedDuration
```

---

## Domain & Service Layer

New package `dev.abu.screener_backend.billing` (catalog + pricing) and
`dev.abu.screener_backend.entitlement` (access state).

### `billing` package
- **`Plan`** — JPA entity for `plans`; `type` is `PlanType` enum (STRING-mapped). No `sort_order`.
- **`PlanType`** — enum `{ FIXED, PER_DAY }`.
- **`PlanPrice`** — JPA entity for `plan_prices`; `amount` is `BigDecimal`; LAZY `@ManyToOne Plan`.
- **`PlanRepository`** — `findByActiveTrueOrderByCode()` (frontend re-orders by `code` anyway);
  `findAllByOrderByCode()` and `existsByCode(...)` for the admin surface.
- **`PlanPriceRepository`** — `findByPlanIdInAndCurrencyAndActiveTrue(...)`; `findByPlanIdIn(...)` for
  the admin surface (all currencies, active + inactive).
- **`Money`** — record `(BigDecimal amount, String currency)`. **Major units.** The sole money
  abstraction; documented exception to the no-`BigDecimal` rule. Minor-unit conversion is a provider
  adapter concern (payment plan), never done here.
- **`RegionResolver`** — interface: `Region resolve(HttpServletRequest, User)` → `Region(countryCode,
  currency)`. **`DefaultRegionResolver`** stub returns the configured default (`UZ` / `UZS`) for
  everyone. The real precedence (CDN/IP header → verified phone country → user override) and any
  persistence (`user_settings`) are future work. **Resolves at request time; persists nothing now.**
- **`PricingService`** — `@Service`. `catalogFor(currency)` loads active plans + their price rows for
  that currency, returns a `PlanCatalogResponse`. Plans missing a price in the requested currency are
  skipped (logged at WARN).
- **`BillingController`** — `@RestController` at `/api/billing`. `GET /api/billing/plans` resolves the
  caller's currency via `RegionResolver` and returns the catalog.
- **`dto/`** — `PlanDto(code, displayName, type, durationDays, amount)` (no per-plan currency),
  `PlanCatalogResponse(currency, List<PlanDto>)` (currency declared once).

#### Admin catalog management (ADMIN-only)
- **`PlanAdminService`** — `@Service @Transactional`. Catalog mutation, all validation before any
  write (reject the whole request with `400` on first failure, matching `ClassificationRuleService`):
  - `createPlan(...)` — validates `code` uniqueness, the `type`/`duration_days` invariant
    (`FIXED` ⇒ duration present; `PER_DAY` ⇒ duration null — same as the DB CHECK), inserts a `Plan`.
  - `updatePlan(id, ...)` — mutates `display_name`, `duration_days`, `active`; `code` is immutable
    once created (it is the stable identifier the frontend keys off). `404` if absent.
  - `deletePlan(id)` — **soft-disable** (`active=false`), never a hard delete (Decision #9). Idempotent.
  - `upsertPrice(planId, currency, amount, active)` — validates `amount >= 0` and currency format;
    relies on the `UNIQUE (plan_id, currency)` constraint; `404` if the plan is absent.
  - `deletePrice(id)` — soft-disable the price row (`active=false`). Idempotent.
- **`PlanAdminController`** — `@RestController` at `/api/admin/billing`. ADMIN-only (see
  `SecurityConfig`). Returns **full admin views** (including `id`, `active`, both `FIXED`/`PER_DAY`
  plans, and *all* currencies) — distinct from the public `GET /api/billing/plans`, which returns only
  active plans priced in the caller's resolved currency.

  | Method | Path | Purpose |
  |--------|------|---------|
  | `GET` | `/api/admin/billing/plans` | List all plans (active + inactive) with their price rows |
  | `POST` | `/api/admin/billing/plans` | Create a plan |
  | `PUT` | `/api/admin/billing/plans/{id}` | Update a plan (not `code`) |
  | `DELETE` | `/api/admin/billing/plans/{id}` | Soft-disable a plan (`active=false`) |
  | `PUT` | `/api/admin/billing/plans/{id}/prices` | Upsert a price for `(plan, currency)` |
  | `DELETE` | `/api/admin/billing/prices/{id}` | Soft-disable a price row |
- **`dto/`** (admin) — `AdminPlanRequest(code, displayName, type, durationDays, active)`,
  `AdminPriceRequest(currency, amount, active)`, `AdminPlanResponse(id, code, displayName, type,
  durationDays, active, List<AdminPriceResponse>)`, `AdminPriceResponse(id, currency, amount, active)`.

### `entitlement` package
- **`UserEntitlement`** — JPA entity for `user_entitlement`; `user_id` PK + shared-id `@OneToOne` to
  `User`. Mutable `accessExpiresAt`, `hasPaid`. No currency/country.
- **`UserEntitlementRepository`** — `JpaRepository<UserEntitlement, UUID>`; `findByUserId`.
- **`AccessState`** — enum `{ TRIAL, ACTIVE, EXPIRED, ADMIN }`. Presentation only.
- **`EntitlementService`** — `@Service @Transactional`:
  - `startTrial(User)` — creates the row with `access_expires_at = now + trialDuration`,
    `has_paid = false`. Called from registration (same transaction as user creation).
  - `extend(userId, Duration granted, boolean paid)` — applies the stacking formula; sets
    `has_paid = true` when `paid`. The single mutation path (trial top-ups and, later, purchases).
  - `currentState(User)` — returns `EntitlementView(state, accessExpiresAt)` using the derivation
    above; short-circuits `ADMIN` (reports `accessExpiresAt = null`).
  - `hasAccess(User)` — `role == ADMIN || (expiresAt != null && now < expiresAt)`. Provided now for
    the enforcement plan to consume; **not wired into any gate in this plan.**
- **`EntitlementController`** — `@RestController`. `GET /api/billing/entitlement` → `EntitlementView`
  for cheap UI polling.
- **`dto/EntitlementResponse`** — `(AccessState state, Instant accessExpiresAt)`. No currency.

### Touch points in existing code
- **`AuthService.register`** — after creating the `User`, call `entitlementService.startTrial(user)`
  in the same transaction so every new account starts in `TRIAL` and the 1:1 invariant always holds.
- **`UserProfileResponse` + `AuthService.me`** — add `accessState`, `accessExpiresAt` (from
  `EntitlementService.currentState`) so the SPA gets entitlement on initial load in one call. No
  currency here.
- **`SecurityConfig`** — add `/api/admin/**` as **ADMIN-only** (`.requestMatchers("/api/admin/**").hasRole("ADMIN")`,
  placed *before* the catch-all, mirroring the existing `/api/monitoring/**` rule). `/api/billing/**`
  needs only the authenticated (Bearer JWT) set — it falls under `anyRequest().authenticated()`, so no
  explicit matcher is required. No entitlement gating yet — that is the enforcement plan.
- **`UserRole`** — `ADMIN` must exist (already added per commit `b7cdcfe`); the derivation depends on it.

---

## Configuration

New `screener.billing.*` properties record (`BillingProperties`), enabled in `WebClientConfig`'s
`@ConfigurationProperties` set alongside the others:
- `trial-duration` — default `P7D`.
- `default-currency` — default `UZS`.
- `default-country` — default `UZ`.

No per-day price in config — pay-by-days pricing lives in `plan_prices` (the `PER_DAY` plan row).

---

## Client ↔ Server Contract

**`GET /api/billing/plans`** (Bearer JWT) — currency declared once; amounts in sum:
```jsonc
{
  "currency": "UZS",
  "plans": [
    { "code": "weekly",        "displayName": "Weekly",      "type": "FIXED",   "durationDays": 7,    "amount": 50000.00 },
    { "code": "monthly",       "displayName": "Monthly",     "type": "FIXED",   "durationDays": 30,   "amount": 150000.00 },
    { "code": "yearly",        "displayName": "Yearly",      "type": "FIXED",   "durationDays": 365,  "amount": 1500000.00 },
    { "code": "pay_as_you_go", "displayName": "Pay by days", "type": "PER_DAY", "durationDays": null, "amount": 8000.00 }
  ]
}
```
`amount` is in **major units (sum)** — displayed directly, no `/100`. `displayName` is a fallback;
the frontend renders localized name/description from its i18n keyed by `code`. Pay-by-days math
(`days = ceil(amountPaid / pricePerDay)`) lives in the payment plan — the catalog only advertises
the per-day price.

**`GET /api/billing/entitlement`** (Bearer JWT) — access state only:
```jsonc
{ "state": "TRIAL", "accessExpiresAt": "2026-06-21T10:00:00Z" }
```
- `TRIAL`/`ACTIVE` → `accessExpiresAt` is the trial-end / subscription-end instant (same field, label
  differs by state).
- `EXPIRED` → `accessExpiresAt` is in the past (or null if never granted).
- `ADMIN` → `accessExpiresAt` is `null`.

Same two fields are mirrored on `GET /api/auth/me`.

**Hard rule:** the client never sends a price or currency. It sends a plan `code` (or, for
pay-by-days, an amount of money). The server resolves currency via `RegionResolver` and looks up the
authoritative price. Enforced when the order endpoint lands in the payment plan.

---

## Build Order

1. **Migrations** `V5` (plans + plan_prices + UZS seed), `V6` (user_entitlement), `V7` (role-split
   backfill: non-admins fresh trial, admins `NULL` expiry).
2. **`billing` entities + repositories + `Money`** (`BigDecimal`).
3. **`RegionResolver` (stub)** + `BillingProperties`.
4. **`PricingService` + `BillingController`** (`GET /api/billing/plans`).
5. **`entitlement` entity + repository + `AccessState` + `EntitlementService`**.
6. **Wire trial into `AuthService.register`**; extend `/me`; add `EntitlementController`.
7. **Admin catalog CRUD** — `PlanAdminService` + `PlanAdminController` (`/api/admin/billing/**`).
8. **`SecurityConfig`**: `/api/admin/**` ADMIN-only; `/api/billing/**` requires JWT (catch-all).
9. **Tests** — `PricingService` (currency filtering, inactive plans skipped, missing-price skip,
   `BigDecimal` amounts intact), `EntitlementService` (trial seeding, stacking from past vs future
   expiry, derived state for all four cases incl. ADMIN short-circuit), `PlanAdminService` (code
   uniqueness, type/duration invariant, soft-delete sets `active=false`, price upsert). Plain JUnit,
   matching the existing test style.

---

## Deferred → Subsequent Plans

| Concern | Owning plan |
|---------|-------------|
| Access-gate enforcement (REST + WS `@OnOpen`), mid-session WS expiry | Entitlement-enforcement plan |
| Orders, `PaymentProvider` interface, Multicard adapter, webhooks, reconciliation, pay-by-days math, **major→tiyin conversion** | Payment plan |
| Real geo/IP/phone-SMS resolver, **`user_settings` table** (currency / country / locale), multi-currency seed (KZT/RUB/crypto), currency-immutability policy | Localization/account-settings plan |
| **`plan_translations`** (per-locale name/description), full admin console (`sort_order`, discounts, gifting days, editable localized text), entitlement audit/ledger | Admin-console plan |

---

## CURRENT_STATE.md

After implementation, add `billing/` and `entitlement/` package sections, the `V5`/`V6`/`V7`
migrations, the extended `UserProfileResponse`, and the new `/api/billing/**` and ADMIN-only
`/api/admin/billing/**` endpoints; update the stale `UserRole` note (it already has `USER, ADMIN`);
and move the relevant rows out of the "What Is Not Yet Implemented" table.
