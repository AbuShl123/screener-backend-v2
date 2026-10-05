# Handoff — Subscription Foundation (Session 1 → CRUD Session)

## Context
Implementing the monetization milestone. Full vision: `.claude/plans/monetization-plan.md`.
Detailed first-step plan: `.claude/plans/subscription-entitlement-foundation-plan.md`.
Codebase map: `CURRENT_STATE.md` (already updated with the new packages).

This session built the **data foundation + services**. The next session builds the **read/admin
HTTP layer (CRUD)** — explicitly deferred last time to keep scope manageable.

## What is DONE (this session)
- **Migrations**: `V5__create_plans.sql` (`plans` + `plan_prices` + placeholder UZS seed),
  `V6__create_user_entitlement.sql` (1:1 access table).
- **Manual backfill** (NOT a migration, by design): `scripts/backfill_user_entitlement.sql` —
  must be run by hand on prod after V6, before any enforcement. Role-split (non-admins → fresh
  7-day trial; admins → NULL expiry).
- **`billing` package**: `Plan`, `PlanType {FIXED, PER_DAY}`, `PlanPrice` entities;
  `PlanRepository.findByActiveTrueOrderByCode()`,
  `PlanPriceRepository.findByPlan_IdInAndCurrencyAndActiveTrue(...)`;
  `RegionResolver` + `DefaultRegionResolver` stub (UZ/UZS, persists nothing);
  `PricingService.catalogFor(currency)` → `PlanCatalogResponse`; dtos `PlanDto`, `PlanCatalogResponse`.
- **`entitlement` package**: `UserEntitlement` entity (shared-PK `@OneToOne @MapsId` to `User`),
  `UserEntitlementRepository.findByUserId`, `AccessState {TRIAL, ACTIVE, EXPIRED, ADMIN}`,
  `EntitlementView(state, accessExpiresAt)`, `EntitlementService` with `startTrial(User)`,
  `extend(userId, Duration, paid)` (stacking), `currentState(User)`, `hasAccess(User)`.
- **Wiring**: `AuthService.register` calls `entitlementService.startTrial(user)` in-transaction.
  `BillingProperties` (`screener.billing.*`: trial-duration P7D, default-currency UZS,
  default-country UZ) registered in `WebClientConfig`; yml block added.
- Compiles clean (`mvnw compile` → BUILD SUCCESS). No tests written yet.

## What is TODO (next session — the CRUD portion)
All of this is specified in detail in `subscription-entitlement-foundation-plan.md` §"Domain &
Service Layer" and §"Build Order" steps 4, 6, 7, 8, 9. Summary:

1. **`BillingController`** `@RestController /api/billing`:
   - `GET /api/billing/plans` — resolve caller currency via `RegionResolver.resolve(request, user)`,
     return `pricingService.catalogFor(currency)`. Bearer JWT.
2. **`EntitlementController`** `GET /api/billing/entitlement` → `EntitlementService.currentState(user)`
   as an `EntitlementResponse(state, accessExpiresAt)` dto. Bearer JWT.
3. **`/api/auth/me` extension**: add `accessState` + `accessExpiresAt` to `UserProfileResponse`
   and populate from `EntitlementService.currentState` in `AuthService.me` (one-call UI bootstrap).
4. **Admin catalog CRUD** (ADMIN-only, mirror `/api/monitoring/**` style):
   - `PlanAdminService @Service @Transactional` — `createPlan` (validate code uniqueness +
     FIXED⇒duration / PER_DAY⇒null invariant), `updatePlan` (mutate displayName/durationDays/active;
     `code` immutable), `deletePlan` = soft-disable (`active=false`), `upsertPrice(planId, currency,
     amount, active)`, `deletePrice` = soft-disable. Validate-all-before-write, 400 on first failure
     (match `ClassificationRuleService` style). 404 when plan/price absent.
   - `PlanAdminController @RestController /api/admin/billing` — full admin views (id, active, all
     currencies, FIXED+PER_DAY). Endpoints: `GET /plans`, `POST /plans`, `PUT /plans/{id}`,
     `DELETE /plans/{id}`, `PUT /plans/{id}/prices`, `DELETE /prices/{id}`.
   - dtos: `AdminPlanRequest`, `AdminPriceRequest`, `AdminPlanResponse`, `AdminPriceResponse`.
   - **Repository additions needed**: `PlanRepository.findAllByOrderByCode()`, `existsByCode(...)`;
     `PlanPriceRepository.findByPlan_IdIn(...)` (all currencies, active+inactive).
5. **`SecurityConfig`**: add `.requestMatchers("/api/admin/**").hasRole("ADMIN")` BEFORE the
   catch-all (mirror existing `/api/monitoring/**`). `/api/billing/**` needs no explicit matcher —
   falls under `anyRequest().authenticated()`. No entitlement gating yet (that's the enforcement plan).
6. **Tests** (plain JUnit, match existing style): `PricingService` (currency filter, inactive/missing
   price skip, BigDecimal intact), `EntitlementService` (trial seed, stacking past vs future,
   all-four-states incl. ADMIN short-circuit), `PlanAdminService` (code uniqueness, type/duration
   invariant, soft-delete, price upsert).
7. Update `CURRENT_STATE.md`: add the new controllers/admin service, move rows out of
   "What Is Not Yet Implemented".

## Decisions locked (do not relitigate)
- Plans are DB rows, not an enum; pay-by-days is a `PER_DAY` plan.
- Access state is DERIVED on read, never stored.
- Money in MAJOR units (`BigDecimal NUMERIC(19,4)`); tiyin conversion is payment-plan-only.
- Soft-disable (`active=false`), never hard-delete plans/prices.
- `code` is the immutable stable identifier the frontend keys text/order off.
- Client never sends price/currency — only a plan `code` (or, later, a pay-by-days amount).

## Conventions to follow
- Lombok `@Slf4j` for logging (not manual `LoggerFactory`).
- `ApiException.badRequest/notFound/conflict/unauthorized` for client-facing errors.
- `userId` always from the JWT principal (`AuthenticatedUser`), never the request body.
- Entities: `@GeneratedValue(strategy = GenerationType.UUID)`, `@PrePersist`/`@PreUpdate` timestamps.
- Records for dtos.

## Still deferred BEYOND the CRUD session (don't build yet)
- Access-gate enforcement (REST + WS `@OnOpen`), mid-session WS expiry.
- Orders, `PaymentProvider`, Multicard adapter, webhooks, reconciliation, pay-by-days math,
  major→tiyin conversion, the `Money` abstraction (re-add it then — removed now as unused).
- Real geo/phone `RegionResolver`, `user_settings` table, multi-currency seed.
- `plan_translations`, entitlement audit/ledger, `PREMIUM` role + per-plan limits.
