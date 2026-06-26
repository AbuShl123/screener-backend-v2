# Current State — Screener Backend

This file documents every class under `src/` so future sessions don't need to re-explore the codebase from scratch. Update this file whenever a class is added, removed, or significantly changed.

---

## Project Layout

```
src/
├── main/java/dev/abu/screener_backend/
│   ├── ScreenerBackendApplication.java
│   ├── analysis/
│   │   ├── ClassificationRule.java
│   │   ├── DefaultClassificationRule.java
│   │   ├── ThresholdClassificationRule.java
│   │   ├── OrderBookClassifier.java
│   │   ├── SymbolState.java
│   │   ├── UserClassificationRules.java
│   │   ├── UserClassificationContext.java
│   │   ├── UserFeedRegistry.java
│   │   └── rule/
│   │       ├── ClassificationRuleEntity.java
│   │       ├── ClassificationRuleRepository.java
│   │       ├── ClassificationRuleService.java
│   │       ├── ClassificationRuleController.java
│   │       ├── RuleUpdatedEvent.java
│   │       └── dto/
│   │           ├── TierDto.java
│   │           ├── RuleDto.java
│   │           ├── TargetDto.java
│   │           ├── RuleAssignmentDto.java
│   │           ├── BulkRuleRequest.java
│   │           ├── BulkDeleteRequest.java
│   │           ├── RuleResponse.java
│   │           └── DefaultRuleResponse.java
│   ├── auth/
│   │   ├── AuthController.java
│   │   ├── AuthService.java   ← seeds free trial on register (EntitlementService.startTrial)
│   │   ├── AuthenticatedUser.java
│   │   ├── JwtAuthenticationFilter.java
│   │   ├── JwtService.java
│   │   └── dto/
│   │       ├── AuthResponse.java
│   │       ├── LoginRequest.java
│   │       ├── RefreshRequest.java
│   │       ├── RegisterRequest.java
│   │       └── UserProfileResponse.java
│   ├── billing/
│   │   ├── Plan.java
│   │   ├── PlanType.java
│   │   ├── PlanPrice.java
│   │   ├── Currency.java                 ← enum {UZS,USD,BTC,ETH}: per-currency decimals + scale validation
│   │   ├── PlanRepository.java
│   │   ├── PlanPriceRepository.java
│   │   ├── RegionResolver.java
│   │   ├── DefaultRegionResolver.java
│   │   ├── PricingService.java
│   │   ├── BillingController.java       ← GET /api/billing/plans
│   │   ├── PlanAdminService.java        ← ADMIN catalog CRUD
│   │   ├── PlanAdminController.java     ← /api/admin/billing/** (ADMIN-only)
│   │   └── dto/
│   │       ├── PlanDto.java
│   │       ├── PlanCatalogResponse.java
│   │       ├── AdminPlanRequest.java
│   │       ├── AdminPriceRequest.java
│   │       ├── AdminPlanResponse.java
│   │       └── AdminPriceResponse.java
│   ├── entitlement/
│   │   ├── UserEntitlement.java
│   │   ├── UserEntitlementRepository.java
│   │   ├── EntitlementLedger.java       ← append-only grant audit row
│   │   ├── EntitlementLedgerRepository.java
│   │   ├── GrantSource.java             ← enum {TRIAL, PURCHASE, ADMIN}
│   │   ├── AccessState.java
│   │   ├── EntitlementView.java
│   │   ├── EntitlementService.java      ← startTrial + extend write the ledger; listAccessHistory reads it
│   │   ├── EntitlementController.java   ← GET /api/billing/entitlement (+ /entitlement/history)
│   │   └── dto/
│   │       ├── EntitlementResponse.java
│   │       └── EntitlementLedgerEntry.java ← GET /api/billing/entitlement/history (access-grant events)
│   ├── payment/                          ← orders + provider boundary (Multicard adapter)
│   │   ├── Order.java
│   │   ├── OrderStatus.java
│   │   ├── OrderReason.java
│   │   ├── OrderSource.java
│   │   ├── OrderStateMachine.java
│   │   ├── OrderStatusHistory.java
│   │   ├── OrderStatusHistoryRepository.java
│   │   ├── OrderRepository.java
│   │   ├── OrderService.java
│   │   ├── OrderController.java          ← /api/billing/orders/**
│   │   ├── PaymentReconciliationService.java   ← @Scheduled sweep over PENDING orders
│   │   ├── PaymentProvider.java          ← provider-agnostic boundary (interface)
│   │   ├── CheckoutSession.java
│   │   ├── ProviderPayment.java
│   │   ├── ProviderStatus.java
│   │   ├── dto/
│   │   │   ├── CreateOrderRequest.java
│   │   │   ├── OrderDetailsEntry.java     ← one order view (create + status reads)
│   │   │   └── OrderHistoryEntry.java     ← GET /api/billing/orders/{id}/history
│   │   └── multicard/
│   │       ├── MulticardClient.java
│   │       ├── MulticardPaymentProvider.java
│   │       ├── MulticardSignature.java
│   │       ├── MulticardCallbackController.java   ← public POST /api/payment/multicard/callback
│   │       ├── MulticardCallbackService.java
│   │       ├── CallbackOutcome.java
│   │       ├── MulticardException.java
│   │       └── dto/
│   │           ├── MulticardAuthResponse.java
│   │           ├── MulticardInvoiceRequest.java
│   │           ├── MulticardInvoiceResponse.java
│   │           ├── MulticardPaymentResponse.java
│   │           ├── MulticardCallbackPayload.java
│   │           └── MulticardError.java
│   ├── binance/
│   │   ├── api/
│   │   │   ├── BinanceApiException.java
│   │   │   ├── BinanceRestClient.java
│   │   │   ├── WeightGuard.java
│   │   │   ├── WeightLimitFilter.java
│   │   │   └── dto/
│   │   │       ├── BinanceSymbolDto.java
│   │   │       └── ExchangeInfoResponse.java
│   │   ├── disruptor/
│   │   │   ├── DepthEvent.java
│   │   │   ├── DepthEventFactory.java
│   │   │   ├── DepthEventHandler.java
│   │   │   ├── DisruptorShardManager.java
│   │   │   ├── DisruptorDepthMessageHandler.java
│   │   │   └── EventType.java
│   │   ├── orderbook/
│   │   │   ├── OrderBook.java
│   │   │   ├── OrderBookProcessor.java
│   │   │   ├── OrderBookResult.java
│   │   │   ├── OrderBookState.java
│   │   │   ├── OrderBookStore.java
│   │   │   ├── PriceLevelEntry.java
│   │   │   └── SnapshotFetchQueue.java
│   │   └── websocket/
│   │       ├── Market.java
│   │       ├── RawDepthMessageHandler.java
│   │       ├── LoggingDepthMessageHandler.java   ← inactive (no @Component); kept for reference
│   │       ├── BinanceStreamClient.java
│   │       ├── BinanceConnectionPool.java
│   │       └── BinanceWebSocketManager.java
│   ├── config/
│   │   ├── AdminProperties.java
│   │   ├── BillingProperties.java
│   │   ├── BinanceApiProperties.java
│   │   ├── DisruptorProperties.java
│   │   ├── JwtProperties.java
│   │   ├── OrderbookProperties.java
│   │   ├── PaymentProperties.java        ← screener.payment.* (+ nested MulticardProperties)
│   │   ├── SecurityConfig.java
│   │   ├── WebClientConfig.java
│   │   └── WebSocketProperties.java
│   ├── error/
│   │   ├── ApiException.java
│   │   ├── ApiError.java
│   │   └── GlobalExceptionHandler.java
│   ├── feed/
│   │   ├── ClassifiedLevel.java
│   │   ├── FeedEventType.java
│   │   ├── OrderBookBroadcaster.java
│   │   ├── OrderBookFeedStore.java
│   │   └── OrderBookUpdate.java
│   ├── monitoring/
│   │   └── MonitoringController.java
│   ├── ticker/
│   │   ├── Ticker.java
│   │   ├── TickerController.java
│   │   ├── TickerRefreshScheduler.java
│   │   ├── TickerRegistry.java
│   │   ├── TickerService.java
│   │   └── TickersRefreshedEvent.java
│   ├── user/
│   │   ├── RefreshToken.java
│   │   ├── RefreshTokenRepository.java
│   │   ├── User.java
│   │   ├── UserRepository.java
│   │   └── UserRole.java
│   └── ws/
│       ├── WebSocketConfig.java
│       ├── CustomSpringConfigurator.java
│       ├── ScreenerWebSocketEndpoint.java
│       └── UserWebSocketSession.java
├── main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__create_users.sql
│       ├── V2__create_refresh_tokens.sql
│       ├── V3__create_classification_rules.sql
│       ├── V4__update_role_check_constraint.sql
│       ├── V5__create_plans.sql               ← plans + plan_prices + UZS seed
│       ├── V6__create_user_entitlement.sql    ← 1:1 access table (manual backfill, see scripts/)
│       ├── V7__align_constraints_and_indexes.sql  ← cross-env index/FK/constraint alignment
│       ├── V8__create_orders.sql              ← orders (version + NUMERIC(38,18) amount)
│       ├── V9__create_order_status_history.sql    ← append-only transition audit (seq identity)
│       ├── V10__create_entitlement_ledger.sql ← append-only grant audit
│       └── V11__payment_concurrency_and_audit.sql ← user_entitlement.version + plan_prices → NUMERIC(38,18)
└── test/java/dev/abu/screener_backend/
    ├── ScreenerBackendApplicationTests.java
    ├── analysis/
    │   ├── DefaultClassificationRuleTest.java
    │   ├── ThresholdClassificationRuleTest.java
    │   ├── UserClassificationRulesTest.java
    │   ├── UserFeedRegistryTest.java
    │   └── rule/
    │       └── ClassificationRuleServiceTest.java
    ├── billing/
    │   ├── PricingServiceTest.java
    │   ├── PlanAdminServiceTest.java
    │   └── CurrencyTest.java
    ├── entitlement/
    │   └── EntitlementServiceTest.java
    └── payment/
        ├── OrderServiceTest.java
        ├── OrderStateMachineTest.java
        ├── PaymentReconciliationServiceTest.java
        └── multicard/
            ├── MulticardCallbackServiceTest.java
            ├── MulticardPaymentProviderTest.java
            └── MulticardSignatureTest.java
```

**Implementation status**: All core features are complete. The full pipeline — Binance WebSocket integration, LMAX Disruptor processing, orderbook sync, order classification, feed broadcasting, and client WebSocket delivery — is implemented and wired together. JWT auth, PostgreSQL user storage, per-user classification rules (persistence, REST CRUD, runtime wiring, two-pass classifier, broadcaster merge), live rule propagation to connected sessions, and Spring Security are all complete.

---

## Application Entry Point

### `ScreenerBackendApplication`
`src/main/java/dev/abu/screener_backend/ScreenerBackendApplication.java`

Spring Boot entry point. Runs as a servlet/Tomcat application (not Netty) even though `spring-boot-starter-webflux` is on the classpath — the `spring.main.web-application-type=servlet` property forces this. `@EnableScheduling` activates the ticker refresh and snapshot dispatch schedulers.

---

## `auth` — Authentication & Token Management

### `JwtService`
`src/main/java/dev/abu/screener_backend/auth/JwtService.java`

`@Service`. All JWT operations using Nimbus JOSE JWT with HS256 signing. `generateAccessToken(User)` builds a signed JWT with `sub`, `email`, `role`, `iat`, `exp`; access tokens expire in 3 hours. `generateRawRefreshToken()` produces a 32-byte `SecureRandom` value, Base64-URL encoded. `hashToken(String)` SHA-256 hex digests a raw token for safe DB storage. `validateAndExtract(String)` parses and verifies a JWT, returns `AuthenticatedUser` or `null` on failure. The signing key is an `OctetSequenceKey` built from the base64-decoded `screener.jwt.secret` property.

### `JwtAuthenticationFilter`
`src/main/java/dev/abu/screener_backend/auth/JwtAuthenticationFilter.java`

`OncePerRequestFilter`. Reads the `Authorization: Bearer <token>` header, validates with `JwtService.validateAndExtract()`, and sets a `UsernamePasswordAuthenticationToken` on `SecurityContextHolder`. No database call per request — all needed data is in the JWT claims. Instantiated directly in `SecurityConfig` (not a `@Bean`) to prevent double-registration as a servlet filter.

### `AuthService`
`src/main/java/dev/abu/screener_backend/auth/AuthService.java`

`@Service @Transactional`. Register, login, refresh, logout, and user lookup. Stores one refresh token per user (old token invalidated on each new login). Throws `ApiException` with the appropriate HTTP status for all error cases.

- `register` — validates fields, checks email uniqueness (409 on conflict), BCrypt-hashes password, issues a token pair
- `login` — verifies BCrypt hash (401 on mismatch), issues a token pair
- `refresh` — SHA-256 hashes the incoming token, looks it up in DB, checks expiry, issues a new token pair
- `logout` — deletes the user's refresh token row; no-op if none exists
- `me` — `@Transactional(readOnly = true)`; builds `UserProfileResponse` from the user plus derived entitlement (`EntitlementService.currentState`) for a one-call SPA bootstrap

### `AuthController`
`src/main/java/dev/abu/screener_backend/auth/AuthController.java`

`@RestController` at `/api/auth`. Five endpoints:

| Method | Path | Auth | Response |
|--------|------|------|----------|
| `POST` | `/api/auth/register` | Public | 201 + `AuthResponse` |
| `POST` | `/api/auth/login` | Public | 200 + `AuthResponse` |
| `POST` | `/api/auth/refresh` | Public | 200 + `AuthResponse` |
| `POST` | `/api/auth/logout` | Bearer JWT | 204 |
| `GET` | `/api/auth/me` | Bearer JWT | 200 + `UserProfileResponse` |

`AuthResponse` carries `accessToken`, `refreshToken` (raw value), and `expiresIn` (seconds). `GET /api/auth/me` returns `UserProfileResponse(id, firstName, lastName, email, role, accessState, accessExpiresAt)` — the last two derived by `EntitlementService` so the SPA gets identity and access state in one call.

### `AuthenticatedUser`
`src/main/java/dev/abu/screener_backend/auth/AuthenticatedUser.java`

Record: `(userId, email, role)`. Used as the Spring Security principal set by `JwtAuthenticationFilter` and retrieved from `Authentication.getPrincipal()` in controllers.

---

## `user` — User Domain

### `User`
`src/main/java/dev/abu/screener_backend/user/User.java`

JPA entity mapped to the `users` table. Fields: `id` (UUID), `firstName`, `lastName`, `email` (unique), `passwordHash` (BCrypt), `role` (`UserRole` enum, STRING-mapped), `enabled`, `createdAt`. `@PrePersist` sets `createdAt`, defaults `role` to `USER`, and sets `enabled = true`.

### `UserRole`
`src/main/java/dev/abu/screener_backend/user/UserRole.java`

Enum: `USER`, `ADMIN`. The entitlement gate short-circuits on `ADMIN`.

### `RefreshToken`
`src/main/java/dev/abu/screener_backend/user/RefreshToken.java`

JPA entity mapped to `refresh_tokens`. Fields: `id`, `user` (eager `@ManyToOne`), `tokenHash` (SHA-256 of the raw token), `expiresAt`, `createdAt`. Only the hash is stored — raw tokens never persist.

### `UserRepository`
`src/main/java/dev/abu/screener_backend/user/UserRepository.java`

`JpaRepository<User, UUID>`. Derived queries: `findByEmail`, `existsByEmail`.

### `RefreshTokenRepository`
`src/main/java/dev/abu/screener_backend/user/RefreshTokenRepository.java`

`JpaRepository<RefreshToken, UUID>`. `findByTokenHash` for lookup; `deleteByUserId(UUID)` via `@Modifying @Query` for efficient single-statement delete.

---

## `billing` — Subscription Catalog & Pricing

DB-driven plan catalog (no plan-type enum driving durations). Ships the data foundation, the pricing service, the public catalog endpoint (`GET /api/billing/plans`), and the ADMIN catalog-CRUD (`/api/admin/billing/**`). The `SecurityConfig` `/api/admin/**` ADMIN-only rule is wired in.

### `Plan` / `PlanType`
`src/main/java/dev/abu/screener_backend/billing/`

`Plan` is a JPA entity mapped to `plans` — a named, pre-priced bundle of access days. Fields: `id` (UUID), `code` (stable unique identifier the frontend keys text/order off; immutable), `displayName` (admin/log label + English fallback only), `type` (`PlanType` enum STRING-mapped), `durationDays` (7/30/365 for `FIXED`, null for `PER_DAY`), `active` (soft-disable; never hard-delete), `createdAt`, `updatedAt`. `PlanType` is `{ FIXED, PER_DAY }` — pay-by-days is a `PER_DAY` plan, not a special entity.

### `PlanPrice`
`src/main/java/dev/abu/screener_backend/billing/PlanPrice.java`

JPA entity mapped to `plan_prices`. Price of one `(plan, currency)` pair. `amount` is `BigDecimal NUMERIC(19,4)` in **major units** (sum) — for `FIXED` the full-period price, for `PER_DAY` the price of one day. LAZY `@ManyToOne Plan`. Documented exception to the no-`BigDecimal` rule (billing is low-frequency, correctness-critical). Minor-unit (tiyin) conversion is a future provider-boundary concern, never here.

### `PlanRepository` / `PlanPriceRepository`
`src/main/java/dev/abu/screener_backend/billing/`

`PlanRepository.findByActiveTrueOrderByCode()` for the public catalog; `findAllByOrderByCode()` and `existsByCode(code)` for the admin surface. `PlanPriceRepository.findByPlan_IdInAndCurrencyAndActiveTrue(planIds, currency)` for the per-currency public lookup; `findByPlan_IdIn(planIds)` (all currencies, active + inactive) and `findByPlan_IdAndCurrency(planId, currency)` for the admin views/upsert.

### `RegionResolver` / `DefaultRegionResolver`
`src/main/java/dev/abu/screener_backend/billing/`

Server-side region (country + billing currency) resolution seam. `RegionResolver.resolve(HttpServletRequest, User)` → `Region(countryCode, currency)`. `DefaultRegionResolver` (`@Component`) is a stub returning the configured default (`UZ` / `UZS`) for everyone, persisting nothing. Real geo/phone resolution is future work.

### `PricingService`
`src/main/java/dev/abu/screener_backend/billing/PricingService.java`

`@Service`. `catalogFor(currency)` loads active plans + their active price rows in that currency and returns a `PlanCatalogResponse(currency, List<PlanDto>)`. Plans with no active price in the requested currency are omitted (logged at WARN). DTOs: `PlanDto(code, displayName, type, durationDays, amount)` (no per-plan currency — declared once on the response).

### `BillingController`
`src/main/java/dev/abu/screener_backend/billing/BillingController.java`

`@RestController` at `/api/billing`. `GET /api/billing/plans` (Bearer JWT, via the `anyRequest().authenticated()` catch-all) resolves the caller's billing currency through `RegionResolver` (a UZ/UZS stub today), then returns `pricingService.catalogFor(currency)`. The client never sends a price or currency.

### `PlanAdminService`
`src/main/java/dev/abu/screener_backend/billing/PlanAdminService.java`

`@Service @Transactional`. ADMIN catalog mutation over `plans`/`plan_prices`. All validation runs before any write — the whole request is rejected with `400` on the first failure (matching `ClassificationRuleService`); a missing plan/price is `404`.

- `listPlans()` — all plans (active + inactive) with all their price rows (every currency), as full `AdminPlanResponse` views
- `createPlan(req)` — validates `code` non-blank + unique (`409` on conflict), the `FIXED`⇒duration / `PER_DAY`⇒null invariant, positive duration
- `updatePlan(id, req)` — mutates `displayName`/`durationDays`/`active`; `code` and `type` are immutable (the duration invariant is checked against the existing type)
- `deletePlan(id)` — **soft-disable** (`active=false`), idempotent, never a hard delete
- `upsertPrice(planId, req)` — validates `amount >= 0` and 3-letter ISO currency (normalized upper-case); updates the existing `(plan, currency)` row in place or inserts a new one
- `deletePrice(id)` — soft-disable the price row, idempotent

### `PlanAdminController`
`src/main/java/dev/abu/screener_backend/billing/PlanAdminController.java`

`@RestController` at `/api/admin/billing`. ADMIN-only via the `SecurityConfig` `/api/admin/**` rule. Returns full admin views (id, active, all currencies, both plan types) — distinct from the public catalog.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/admin/billing/plans` | List all plans (active + inactive) with their price rows |
| `POST` | `/api/admin/billing/plans` | Create a plan (201) |
| `PUT` | `/api/admin/billing/plans/{id}` | Update a plan (not `code`/`type`) |
| `DELETE` | `/api/admin/billing/plans/{id}` | Soft-disable a plan (204) |
| `PUT` | `/api/admin/billing/plans/{id}/prices` | Upsert a price for `(plan, currency)` |
| `DELETE` | `/api/admin/billing/prices/{id}` | Soft-disable a price row (204) |

Admin DTOs: `AdminPlanRequest(code, displayName, type, durationDays, active)`, `AdminPriceRequest(currency, amount, active)`, `AdminPlanResponse(id, code, displayName, type, durationDays, active, List<AdminPriceResponse>)`, `AdminPriceResponse(id, currency, amount, active)`.

---

## `entitlement` — Access State

The authoritative "can this user use the screener right now?" domain. Ships the entity, service, derivation, and the read endpoint (`GET /api/billing/entitlement`) plus the `/api/auth/me` mirror. Access-gate enforcement (REST + WS `@OnOpen`) is still deferred to the enforcement plan.

### `UserEntitlement`
`src/main/java/dev/abu/screener_backend/entitlement/UserEntitlement.java`

JPA entity mapped to `user_entitlement`, 1:1 with `users` via a shared-primary-key `@OneToOne` + `@MapsId` (`user_id` is both PK and FK; setting the `user` association derives `userId`). Holds access facts only: `accessExpiresAt` (the single authoritative field; null = never granted) and `hasPaid` (distinguishes TRIAL from ACTIVE). `updatedAt` touched on persist/update. Account/localization facts live elsewhere (future `user_settings`).

### `UserEntitlementRepository`
`src/main/java/dev/abu/screener_backend/entitlement/UserEntitlementRepository.java`

`JpaRepository<UserEntitlement, UUID>`. `findByUserId` (equivalent to `findById` since `user_id` is the PK — named for intent).

### `AccessState` / `EntitlementView`
`src/main/java/dev/abu/screener_backend/entitlement/`

`AccessState` is `{ TRIAL, ACTIVE, EXPIRED, ADMIN }` — derived on read, never stored. `EntitlementView(AccessState state, Instant accessExpiresAt)` is the service's read return type (`ADMIN` reports `accessExpiresAt = null`).

### `EntitlementService`
`src/main/java/dev/abu/screener_backend/entitlement/EntitlementService.java`

`@Service @Transactional`. The single mutation + read path for access:
- `startTrial(User)` — seeds `accessExpiresAt = now + trialDuration`, `hasPaid = false`. Called from `AuthService.register` in the same transaction.
- `extend(userId, Duration, paid)` — stacking grant `accessExpiresAt = max(now, accessExpiresAt) + granted`; sets `hasPaid` when paid. Shared by trial top-ups and future purchases.
- `currentState(User)` — derives `EntitlementView`; `ADMIN` short-circuits to `(ADMIN, null)`.
- `hasAccess(User)` — `role == ADMIN || (expiresAt != null && now < expiresAt)`. Provided for the enforcement plan; not wired into any gate yet.
- `listAccessHistory(userId)` — the user's `entitlement_ledger` rows newest-first as `dto/EntitlementLedgerEntry`; a `PURCHASE` row's `order_id` is resolved to the full `payment.dto.OrderDetailsEntry`, trial/admin rows carry `null` order. Injects `payment.OrderService` **`@Lazy`** to break the bean cycle (`OrderService` depends on this service for grants; this back-edge is read-time only).

### `EntitlementController`
`src/main/java/dev/abu/screener_backend/entitlement/EntitlementController.java`

`@RestController` at `/api/billing` (Bearer JWT via the catch-all). `GET /api/billing/entitlement` returns `EntitlementResponse(state, accessExpiresAt)` from `EntitlementService.currentState` for cheap UI polling (the same two fields are mirrored on `GET /api/auth/me`). `GET /api/billing/entitlement/history` returns `List<EntitlementLedgerEntry>` from `EntitlementService.listAccessHistory` — the caller's access-grant events (trial/purchase/admin), newest first, each purchase embedding its full `OrderDetailsEntry`. DTOs: `dto/EntitlementResponse(AccessState, Instant)`, `dto/EntitlementLedgerEntry(GrantSource source, long grantedDurationSeconds, Instant previousExpiresAt, Instant newExpiresAt, OrderDetailsEntry order, String reason, Instant createdAt)`.

**Migrations**: `V5__create_plans.sql` (plans + plan_prices + placeholder UZS seed), `V6__create_user_entitlement.sql`. Existing-user backfill is **not** a migration — `scripts/backfill_user_entitlement.sql` is run manually in production (role-split: non-admins get a fresh 7-day trial, admins get `NULL` expiry) so a redeploy can never re-grant trials.

---

## `payment` — Subscription Payments & Multicard Adapter

The subscription-payment module: provider-agnostic `Order`s (create/reuse, pay-by-days math, idempotent grant-on-success, a `@Scheduled` reconciliation sweep, append-only status-history audit) sitting behind a two-method `PaymentProvider` boundary, with the first and only adapter under `multicard/` (UZS, Uzbekistan — `WebClient` client, hosted-checkout invoice, MD5-signed success callback, durable status polling). On a verified payment it calls `EntitlementService.extend(...)`, which now also writes the new `entitlement_ledger` grant audit; the `billing.Currency` enum is the source of truth for per-currency decimal precision. **This is only a generic summary** — the full reference (flow, state machine, edge cases, config, tests) lives in `.claude/docs/payment-gateway-multicard.md`.

---

## `binance/api` — Binance REST Integration

### `BinanceRestClient`
`src/main/java/dev/abu/screener_backend/binance/api/BinanceRestClient.java`

`@Component`. Generic HTTP client for all Binance REST calls. Holds two named `WebClient` beans (spot and futures). All methods return cold `Mono<T>` publishers. Non-2xx responses are converted to `BinanceApiException`.

- `getSpot(path, responseType)` — GET against Spot base URL
- `getFutures(path, responseType)` — GET against Futures base URL

### `BinanceApiException`
`src/main/java/dev/abu/screener_backend/binance/api/BinanceApiException.java`

`RuntimeException` subclass. Carries the HTTP status code and raw response body from a failed Binance REST call.

### `WeightGuard`
`src/main/java/dev/abu/screener_backend/binance/api/WeightGuard.java`

Plain object (no Spring annotations). Tracks Binance API weight usage for one market. Two `volatile long` fields: `lastObservedWeight` and `lastObservedAtMs`. `delayMillisRequired()` returns 0 if the minute has flipped or weight is below threshold; otherwise returns ms until the next minute boundary + 1s safety buffer. `observe(sentTimeMs, weight)` records a weight observation; discards stale out-of-order responses.

### `WeightLimitFilter`
`src/main/java/dev/abu/screener_backend/binance/api/WeightLimitFilter.java`

Implements `ExchangeFilterFunction` (WebClient middleware). Checks `WeightGuard.delayMillisRequired()` before each request, and feeds `x-mbx-used-weight-1m` + HTTP `Date` header back to the guard after each response. Uses server send time (not local clock) to avoid minute-boundary errors under network latency.

### `BinanceSymbolDto` / `ExchangeInfoResponse`
`src/main/java/dev/abu/screener_backend/binance/api/dto/`

Jackson DTOs for Binance `/exchangeInfo`. `BinanceSymbolDto` maps the fields needed for eligibility filtering: `symbol`, `quoteAsset`, `status`, `contractType`. `ExchangeInfoResponse` wraps `List<BinanceSymbolDto>`.

---

## `binance/disruptor` — LMAX Disruptor Pipeline

### `EventType`
`src/main/java/dev/abu/screener_backend/binance/disruptor/EventType.java`

Enum: `DIFF` (stream depth update) and `SNAPSHOT` (REST snapshot response).

### `DepthEvent`
`src/main/java/dev/abu/screener_backend/binance/disruptor/DepthEvent.java`

Ring buffer event carrier. Mutable fields: `type`, `symbol`, `market`, `rawJson`. `clear()` nulls all fields after consumption to avoid retaining references in ring buffer slots.

### `DepthEventFactory`
`src/main/java/dev/abu/screener_backend/binance/disruptor/DepthEventFactory.java`

`EventFactory<DepthEvent>` — pre-allocates `DepthEvent` instances into the ring buffer at startup.

### `DepthEventHandler`
`src/main/java/dev/abu/screener_backend/binance/disruptor/DepthEventHandler.java`

`EventHandler<DepthEvent>` — consumer for one shard. Delegates every event to `OrderBookProcessor.process()` and then calls `event.clear()`.

### `DisruptorShardManager`
`src/main/java/dev/abu/screener_backend/binance/disruptor/DisruptorShardManager.java`

`@Component`. Creates and owns all Disruptor shards. On `@PostConstruct`, starts `shardCount` disruptors (`BlockingWaitStrategy`, `ProducerType.MULTI`), each with one `DepthEventHandler` wrapping one `OrderBookClassifier`. `getRingBuffer(symbol)` hashes the symbol to a shard via `abs(symbol.hashCode()) % shardCount`. Exposes `setActiveUserContexts(UserClassificationContext[])` — fans the same array reference to every shard's classifier atomically. Called from the Tomcat connect/disconnect thread via `UserFeedRegistry`.

### `DisruptorDepthMessageHandler`
`src/main/java/dev/abu/screener_backend/binance/disruptor/DisruptorDepthMessageHandler.java`

`@Component`. Implements `RawDepthMessageHandler`. Called from the WebSocket `onMessage` callback. Publishes a `DIFF` event to the correct shard's ring buffer. This is the only work done on the WebSocket thread — minimal and non-blocking.

---

## `binance/orderbook` — Orderbook Management

### `OrderBookState`
`src/main/java/dev/abu/screener_backend/binance/orderbook/OrderBookState.java`

Enum: `PENDING` (snapshot not yet requested; diffs dropped), `SNAPSHOT_REQUESTED` (request dispatched; buffering diffs), `SYNCED` (live; diffs applied in real time).

### `OrderBookResult`
`src/main/java/dev/abu/screener_backend/binance/orderbook/OrderBookResult.java`

Enum returned by `OrderBook` methods signalling what action the caller should take: `OK`, `NEEDS_SNAPSHOT`, `NEEDS_RESYNC`, `DROPPED`.

### `PriceLevelEntry`
`src/main/java/dev/abu/screener_backend/binance/orderbook/PriceLevelEntry.java`

Mutable value object for a single price level. Fields: `quantity` (mutable `double`, updated in-place — zero allocation on hot path), `firstSeenMillis` (final `long`, set once on insertion), `distance` (mutable `double`, fractional distance from mid-price, updated after each diff sweep).

### `OrderBook`
`src/main/java/dev/abu/screener_backend/binance/orderbook/OrderBook.java`

Core class. Maintains a local orderbook for one `(symbol, market)` pair.

**Fields**:
- `bids`: `TreeMap<Double, PriceLevelEntry>` with `Comparator.reverseOrder()` — `firstKey()` = best bid
- `asks`: `TreeMap<Double, PriceLevelEntry>` natural order — `firstKey()` = best ask
- `diffBuffer`: `ArrayDeque<String>` — raw diff JSON strings buffered during `SNAPSHOT_REQUESTED`
- `state`: `volatile OrderBookState` — volatile because `SnapshotFetchQueue` scheduler thread writes `SNAPSHOT_REQUESTED`
- `lastUpdateId`, `lastPu` — sequence continuity tracking
- `filterThreshold` — from `screener.orderbook.price-filter-threshold` (default `0.1`)
- `MAX_BUFFER_SIZE`: 500 — hard cap on `diffBuffer`

**Key methods**: `onDiff(rawJson)` routes by state; `applySnapshot(rawJson)` applies a REST snapshot and drains the diff buffer; `applyLiveDiff(rawJson)` applies one diff in live mode using Jackson streaming API; `resync()` clears and returns to `PENDING`; `markSnapshotRequested()` called by the scheduler thread. `snapshotBids()` / `snapshotAsks()` return defensive copies for debug reads.

### `OrderBookStore`
`src/main/java/dev/abu/screener_backend/binance/orderbook/OrderBookStore.java`

`@Component`. Registry of all active `OrderBook` instances, keyed by `"SYMBOL:MARKET"`. Backed by `ConcurrentHashMap`. `getOrCreate(symbol, market)` lazily creates via `computeIfAbsent`; `get(symbol, market)` for lookup without creation; `size()` for monitoring.

### `OrderBookProcessor`
`src/main/java/dev/abu/screener_backend/binance/orderbook/OrderBookProcessor.java`

`@Component`. Called by each `DepthEventHandler` for every ring buffer event. Routes `DIFF` events to `ob.onDiff()` and `SNAPSHOT` events to `ob.applySnapshot()`. On `NEEDS_SNAPSHOT` or `NEEDS_RESYNC`, enqueues the book for a snapshot fetch, transitions its state, and replays the triggering diff (so it is buffered rather than lost) if the book was previously `PENDING`.

### `SnapshotFetchQueue`
`src/main/java/dev/abu/screener_backend/binance/orderbook/SnapshotFetchQueue.java`

`@Component`. Rate-limited snapshot dispatcher. Maintains two `ConcurrentHashMap<String, OrderBook>` queues (spot and futures), each bounded by a configurable max size (default 10). `@Scheduled dispatchSpot()` / `dispatchFutures()` fire every 6 000ms. Each request applies a 3-second `delayElement` before publishing the snapshot — giving the diff buffer time to accumulate enough events to span the snapshot's `lastUpdateId`. On success, publishes a `SNAPSHOT` event to the correct shard's ring buffer. On failure, removes and re-enqueues. Uses `@Lazy DisruptorShardManager` to break the circular dependency.

The orderbook debug endpoint formerly here (`OrderBookController`) has moved into `MonitoringController` (see `monitoring`).

---

## `analysis` — Order Classification

### `ClassificationRule`
`src/main/java/dev/abu/screener_backend/analysis/ClassificationRule.java`

Pure, stateless interface. `int computeTier(notional, distance, highLiquidity)` returns 1–4 for a matching tier or 0 (invisible). `double maxDistance(highLiquidity)` returns the widest distance any tier can match — drives the classifier's early-break.

### `DefaultClassificationRule`
`src/main/java/dev/abu/screener_backend/analysis/DefaultClassificationRule.java`

`@Component` singleton. The global default tier thresholds, a `HIGH_LIQUIDITY_TICKERS` set, and `isHighLiquidity(symbol)`. Two threshold tables: a tighter high-liquidity table (`maxDistance` 0.025) and the normal table (`maxDistance` 0.05). Shared across all shards (stateless).

### `ThresholdClassificationRule`
`src/main/java/dev/abu/screener_backend/analysis/ThresholdClassificationRule.java`

Immutable per-`(symbol, market)` user override. Built off the hot path from a list of `TierThreshold(tier, minNotional, maxDistance)` records into parallel primitive arrays sorted highest-tier-first. `computeTier` returns the first matching tier or 0. Ignores `highLiquidity` — user thresholds are absolute.

### `OrderBookClassifier`
`src/main/java/dev/abu/screener_backend/analysis/OrderBookClassifier.java`

Not a Spring bean — constructed manually by `DisruptorShardManager`, one instance per shard. Per-shard state is accessed exclusively by the owning shard's consumer thread; the only cross-thread field is the `volatile UserClassificationContext[] activeUserContexts` (swapped atomically, read once per `process()`).

**Entry point**: `process(OrderBook ob)` runs a two-pass classification:
1. **Default pass** — always — against `defaultStates` (`HashMap`), `defaultRule`, and the global `OrderBookFeedStore`
2. **Per-user passes** — for each active context whose `configuredKeys()` contain this book's key — against that context's own states, override rule, and personal feed store

`highLiquidity` is computed once per book and threaded into both passes. Shared per-pass logic lives in `classifyOne(...)`, which contains the full state machine. Per-context state per `(symbol, market)` is a `SymbolState` tracking `ActivityLevel` (LOW/HIGH), last-emitted level arrays, and two reusable top-K `Scratch` buffers. No `ClassifiedLevel` is allocated for LOW books.

### `SymbolState`
`src/main/java/dev/abu/screener_backend/analysis/SymbolState.java`

Package-private. Holds the `ActivityLevel`, `workBids`/`workAsks` arrays, and two `Scratch` buffers (nested class). Each instance is single-threaded (its key is pinned to one shard).

### `UserClassificationRules`
`src/main/java/dev/abu/screener_backend/analysis/UserClassificationRules.java`

POJO. A per-user lookup table `Map<String, ThresholdClassificationRule> byKey` keyed by `"SYMBOL:MARKET"`, plus a cached `configuredKeys()` set for O(1) hot-path membership. Does not implement `ClassificationRule` — the classifier fetches the leaf via `ruleFor(key)`.

### `UserClassificationContext`
`src/main/java/dev/abu/screener_backend/analysis/UserClassificationContext.java`

POJO. One per connected user with at least one rule, shared by all that user's sessions. Bundles `userId`, the `UserClassificationRule`, a personal `OrderBookFeedStore`, and a `ConcurrentHashMap<String, SymbolState> states` (concurrent because multiple shard threads insert configured keys; each `SymbolState` value stays single-threaded). Users with no rules get no context and consume the global feed directly.

### `UserFeedRegistry`
`src/main/java/dev/abu/screener_backend/analysis/UserFeedRegistry.java`

`@Component`. Source of truth for active contexts AND connected sessions (`sessionsByUser: Map<UUID, List<UserWebSocketSession>>` — the single source of truth; the old refcount is the list size). All mutation runs inside one `synchronized` block. `onUserConnect(userId, session)` registers the session and builds (or reuses) the context atomically, setting it on the session via `setContext`. `onUserDisconnect(userId, session)` removes the session (idempotent against the @OnClose/@OnError double-fire — second removal no-ops) and discards the context only when the list empties. `onRuleUpdated(RuleUpdatedEvent)` — `@TransactionalEventListener(AFTER_COMMIT)` — implements live rule propagation: re-reads the user's rules from DB outside the lock, then inside the lock rebuilds the context from scratch (fresh feed store, empty state map) or removes it when all rules were deleted, retargets every connected session (`setContext` before `setStatus(NEED_SNAPSHOT)` — write order establishes the happens-before for the broadcaster), and re-pushes `active[]`. Each lifecycle change rebuilds an immutable `volatile active[]` array and fans it out to every shard via `shardManager.setActiveUserContexts(active)`. `presenceSnapshot()` returns a consistent (lock-captured) `List<UserPresence>` of currently-connected users — one entry per user with open-session count and a `custom` flag (has an active context) — consumed by `PresenceController`.

---

## `analysis/rule` — Per-User Classification Rules

### `ClassificationRuleEntity`
`src/main/java/dev/abu/screener_backend/analysis/rule/ClassificationRuleEntity.java`

JPA entity mapped to `classification_rules`. One row per tier — a logical rule for one `(user, symbol, market)` is 1–4 rows. Fields: `id` (UUID), `user` (LAZY `@ManyToOne`), `symbol`, `market` (`Market` enum, STRING-mapped), `tierNo`, `minNotional`, `maxDistance`, `createdAt`, `updatedAt`. Unique constraint on `(user_id, symbol, market, tier_no)`.

### `ClassificationRuleRepository`
`src/main/java/dev/abu/screener_backend/analysis/rule/ClassificationRuleRepository.java`

`JpaRepository<ClassificationRuleEntity, UUID>`. `findByUserId`, `findByUserIdAndSymbolAndMarket`, and `@Modifying deleteByUserIdAndSymbolAndMarket` (bulk DELETE that precedes replacement inserts to avoid unique constraint violations from Hibernate reordering).

### `ClassificationRuleService`
`src/main/java/dev/abu/screener_backend/analysis/rule/ClassificationRuleService.java`

`@Service`. CRUD + validation for user-defined classification rules. All validation runs before any DB write — the whole request is rejected atomically with `400` on first failure.

- `upsertRules(userId, BulkRuleRequest)` — `@Transactional`; delete-then-insert the tier set per target; publishes `RuleUpdatedEvent(userId)` after the writes (consumed AFTER_COMMIT by `UserFeedRegistry` for live propagation)
- `deleteRules(userId, BulkDeleteRequest)` — `@Transactional`; bulk delete per target; idempotent; also publishes `RuleUpdatedEvent`
- `getRules(userId)` — groups tier rows by `(symbol, market)` into `RuleResponse` list
- `getRule(userId, symbol, market)` — single pair; `404` if none
- `buildRuntimeRule(userId)` — `@Transactional(readOnly = true)`. Translates persisted rows into an immutable runtime `UserClassificationRule` (`Optional.empty()` if the user has no rules). Called by `UserFeedRegistry` at WebSocket connect time.

Validation: tiers non-empty; each tier ∈ `[1,4]`; no duplicates; tiers contiguous starting at 1; `minNotional ≥ 0`; `maxDistance ∈ (0, priceFilterThreshold]`; each target is a currently-tracked ticker covering the requested market; total targets per request ≤ `screener.classification.max-targets-per-request` (default 200).

### `RuleUpdatedEvent`
`src/main/java/dev/abu/screener_backend/analysis/rule/RuleUpdatedEvent.java`

Record `(UUID userId)`. Published by `ClassificationRuleService` after each successful rule write, still inside the `@Transactional` boundary. `UserFeedRegistry.onRuleUpdated` consumes it with `@TransactionalEventListener(AFTER_COMMIT)`, so the listener's DB re-read always sees committed data. Plain record — not an `ApplicationEvent` subclass.

### `ClassificationRuleController`
`src/main/java/dev/abu/screener_backend/analysis/rule/ClassificationRuleController.java`

`@RestController` at `/api/rules`. `userId` always from the JWT principal, never the body.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `GET` | `/api/rules/default` | Bearer JWT | Return the global default rule (`DefaultRuleResponse`) |
| `GET` | `/api/rules` | Bearer JWT | List caller's rules grouped by `(symbol, market)` |
| `GET` | `/api/rules/{symbol}/{market}` | Bearer JWT | Caller's rule for one pair (404 if none) |
| `PUT` | `/api/rules` | Bearer JWT | Bulk upsert — replace tier set for each target |
| `DELETE` | `/api/rules` | Bearer JWT | Bulk delete (reset to default) |

---

## `monitoring` — Operational Endpoints

### `MonitoringController`
`src/main/java/dev/abu/screener_backend/monitoring/MonitoringController.java`

`@RestController` at `/api/monitoring`, grouping the read-only operational/debug endpoints (intended to become `ADMIN`-only once a role model exists). Two endpoints:

- `GET /api/monitoring/presence` — live WebSocket presence read from `UserFeedRegistry.presenceSnapshot()` — `onlineUsers` (distinct connected users), `totalSessions`, and a per-user breakdown (`userId`, `sessions`, `custom`) sorted by session count.
- `GET /api/monitoring/orderbook?symbol=BTCUSDT&market=FUTURES` — current bids/asks with price, quantity, distance, and lifetime per level, plus the book's sync state and level counts. Reads are best-effort (no synchronization — acceptable for debugging).

No DB access, no persistence — instantaneous state only (no history). Requires a Bearer JWT (any authenticated user; no `ADMIN` role exists yet). Future work will add persisted usage metrics (active-connection counts over time, last-access timestamps) alongside these live views.

This merges the former `PresenceController` (monitoring) and `OrderBookController` (binance/orderbook).

---

## `error` — Global Exception Handling

### `ApiException`
`src/main/java/dev/abu/screener_backend/error/ApiException.java`

`RuntimeException` carrying an `HttpStatus` plus a client-safe message. The single exception type the application layer throws for expected client-facing failures. Static factories: `badRequest`, `notFound`, `conflict`, `unauthorized`.

### `ApiError`
`src/main/java/dev/abu/screener_backend/error/ApiError.java`

Record `(String message, int status, String path)` — the uniform JSON body for every MVC-layer error.

### `GlobalExceptionHandler`
`src/main/java/dev/abu/screener_backend/error/GlobalExceptionHandler.java`

`@RestControllerAdvice`. Mapping: `ApiException` → its status + message; `HttpMessageNotReadableException` → `400`; `ErrorResponseException` (Spring 6 base — unknown endpoint 404, wrong method 405, wrong Content-Type 415, etc.) → its status with a generic message; any other `Exception` → `500`, real cause logged, never echoed to the client.

---

## `feed` — Feed Store and Broadcaster

### `ClassifiedLevel`
`src/main/java/dev/abu/screener_backend/feed/ClassifiedLevel.java`

Record: `(price, quantity, tier, firstSeenMillis, distance)`. All-primitive fields. The classifier detects changes by comparing these fields slot-by-slot, allocating a new `ClassifiedLevel` only when a slot's value actually changed.

### `FeedEventType`
`src/main/java/dev/abu/screener_backend/feed/FeedEventType.java`

Enum: `ADD`, `UPDATE`, `DROP`.

### `OrderBookUpdate`
`src/main/java/dev/abu/screener_backend/feed/OrderBookUpdate.java`

Record: `(symbol, market, type, bids[], asks[])`. Used as both snapshot map values and pending update values. Arrays are `ClassifiedLevel[TOP_LEVELS]` with null sentinels beyond actual count.

### `OrderBookFeedStore`
`src/main/java/dev/abu/screener_backend/feed/OrderBookFeedStore.java`

`@Component`. Shared between classifier (consumer threads) and broadcaster (sender thread).

- `snapshotMap`: `ConcurrentHashMap<String, OrderBookUpdate>` — authoritative current active state; read on new-client connect
- `pendingRef`: `AtomicReference<ConcurrentHashMap<String, OrderBookUpdate>>` — pending updates since last drain; swapped atomically by broadcaster

`submit(key, update)` — coalesces via `ConcurrentHashMap.merge()`, then syncs `snapshotMap` directly. `drainPending()` — atomic swap; returns the old map. `getSnapshot()` — unmodifiable view of `snapshotMap`.

### `OrderBookBroadcaster`
`src/main/java/dev/abu/screener_backend/feed/OrderBookBroadcaster.java`

`@Component`. Runs the 100ms drain loop on a single `@Scheduled` thread. Drains the global feed once per tick, then drains each active user context's personal feed. Builds update bodies lazily as a keyed map per tick to allow per-session filtering. Per session:
- `NEED_SNAPSHOT` → delivers the appropriate full snapshot (global for default users, merged for custom users)
- `READY` default session → the global bodies
- `READY` custom session → personal bodies for configured symbols + global bodies for unconfigured symbols

`enqueueBatch()` returning `false` evicts the slow client via `session.disconnect()`. JSON is built with a reusable `StringBuilder` (4096-byte initial capacity). `injectSeq()` prepends `{"seq":N,` to a pre-built body string; `seq` is strictly per-session.

---

## `ws` — Outbound WebSocket Server

### `WebSocketConfig`
`src/main/java/dev/abu/screener_backend/ws/WebSocketConfig.java`

`@Configuration`. Declares a `ServerEndpointExporter` bean that registers `@ServerEndpoint`-annotated classes with the embedded Tomcat WebSocket container.

### `CustomSpringConfigurator`
`src/main/java/dev/abu/screener_backend/ws/CustomSpringConfigurator.java`

`@Component`. Bridges Spring DI into Tomcat's WebSocket lifecycle. Stores the `ApplicationContext` in a `static volatile` field via `ApplicationContextAware`. When a connection arrives, Tomcat calls `getEndpointInstance(ScreenerWebSocketEndpoint.class)`, which returns `context.getBean(...)` — the Spring singleton with all dependencies injected. Replaces `SpringConfigurator` from `spring-websocket`, which fails in embedded Tomcat.

### `ScreenerWebSocketEndpoint`
`src/main/java/dev/abu/screener_backend/ws/ScreenerWebSocketEndpoint.java`

Singleton `@Component` + `@ServerEndpoint("/ws")`. Per-session state is stored in `session.getUserProperties()` under the key `"session"`.

| Callback | Responsibility |
|---|---|
| `@OnOpen` | Validate JWT query param, create `UserWebSocketSession` (no context yet), call `userFeedRegistry.onUserConnect(userId, session)` (registers + sets context) BEFORE `broadcaster.addSession` — so the broadcaster never serves a custom user from the global feed — then start the send loop |
| `@OnClose` | Retrieve session → `release(session)`: `disconnect()` + `broadcaster.removeSession()` + `userFeedRegistry.onUserDisconnect(userId, session)` |
| `@OnError` | Same `release(session)` path |
| `@OnMessage` | Handles `SNAPSHOT_REQUEST` — sets session status to `NEED_SNAPSHOT` |

Tomcat may fire both `@OnClose` and `@OnError` for one connection. Every step in `release` is idempotent — the registry's session-list removal absorbs the double-fire (no `markReleased()` guard anymore).

### `UserWebSocketSession`
`src/main/java/dev/abu/screener_backend/ws/UserWebSocketSession.java`

Per-session object created in `@OnOpen`. Fields:
- `jakartaSession` — the underlying `jakarta.websocket.Session`
- `sendQueue` — `ArrayBlockingQueue<List<String>>` capacity 32; each slot is one drain cycle's batch
- `status` (volatile) — `NEED_SNAPSHOT` or `READY`
- `running` (volatile) — send loop exit signal; written by `disconnect()`
- `seqNumber` — per-session monotonic counter; accessed exclusively by the broadcaster thread
- `virtualThread` (volatile) — reference to the send loop thread for interruption
- `context` (volatile, nullable) — this user's `UserClassificationContext`, or `null` for a default-only user; written by `UserFeedRegistry` at connect time and on live rule updates (always BEFORE the `status = NEED_SNAPSHOT` write — that ordering is what guarantees the broadcaster sees the new context when it consumes the snapshot request)

The send loop runs on a Java 21 virtual thread: `sendQueue.take()` parks when empty; `sendText()` parks when the TCP buffer is full. A stalled client parks only its own virtual thread.

---

## `binance/websocket` — WebSocket Connection Layer

### `Market`
`src/main/java/dev/abu/screener_backend/binance/websocket/Market.java`

Enum: `SPOT`, `FUTURES`.

### `RawDepthMessageHandler`
`src/main/java/dev/abu/screener_backend/binance/websocket/RawDepthMessageHandler.java`

`@FunctionalInterface`. Single method `handle(symbol, market, rawJson)`. Called from WebSocket `onMessage` — must be fast; no blocking.

### `BinanceStreamClient`
`src/main/java/dev/abu/screener_backend/binance/websocket/BinanceStreamClient.java`

Wraps one `java-websocket` `WebSocketClient` connection. Manages `@depth` stream subscriptions, reconnection with exponential backoff (100ms initial, 30s max), PING/PONG heartbeat (configurable, default 120s), and calls `RawDepthMessageHandler` on each message.

### `BinanceConnectionPool`
`src/main/java/dev/abu/screener_backend/binance/websocket/BinanceConnectionPool.java`

Manages a pool of `BinanceStreamClient` connections for one market, respecting the 1024-stream-per-connection limit. Creates new connections dynamically as stream count grows.

### `BinanceWebSocketManager`
`src/main/java/dev/abu/screener_backend/binance/websocket/BinanceWebSocketManager.java`

`@Component`. Top-level coordinator. Listens for `TickersRefreshedEvent` and initializes or updates connection pools for spot and futures, distributing tickers across connections.

### `LoggingDepthMessageHandler`
`src/main/java/dev/abu/screener_backend/binance/websocket/LoggingDepthMessageHandler.java`

Inactive (no `@Component`). Kept for reference — logs raw depth messages; used during development.

---

## `config` — Spring Configuration

### `BinanceApiProperties`
Java record bound from `screener.binance.*`: `spotBaseUrl`, `futuresBaseUrl`, `codecBufferSizeMb`, `spotWeightThreshold` (default 5800), `futuresWeightThreshold` (default 2200).

### `DisruptorProperties`
Java record bound from `screener.disruptor.*`: `shardCount`, `ringBufferSize`.

### `OrderbookProperties`
Java record bound from `screener.orderbook.*`: `priceFilterThreshold` (default `0.1`), `spotSnapshotDispatchRateMs`, `futuresSnapshotDispatchRateMs`.

### `WebClientConfig`
`@Configuration`. Produces `spotWebClient` and `futuresWebClient` beans, each built with its market's `WeightGuard` + `WeightLimitFilter`, enlarged codec buffer, and `Accept: application/json`. Also enables all `@ConfigurationProperties` records.

### `WebSocketProperties`
Java record bound from `screener.websocket.*`: stream URLs, connection counts, max streams per connection, subscribe chunk size, reconnect delays, heartbeat interval.

### `JwtProperties`
Java record bound from `screener.jwt.*`: `secret` (base64-encoded), `accessTokenExpiry` (default 3h), `refreshTokenExpiry` (default 7d).

### `AdminProperties`
Java record bound from `screener.admin.*`: `emails` (comma-separated list, supplied via `SCREENER_ADMIN_EMAILS`, empty in the repo). Emails promoted to `ADMIN` on startup.

### `BillingProperties`
Java record bound from `screener.billing.*`: `trialDuration` (default `P7D`), `defaultCurrency` (default `UZS`), `defaultCountry` (default `UZ`). No per-day price here — that lives in `plan_prices`. All three records are registered via `WebClientConfig`'s `@EnableConfigurationProperties`.

### `SecurityConfig`
`@Configuration`. Spring Security filter chain. Stateless (`STATELESS` session policy, CSRF disabled). Public paths: `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/ws`. `/api/monitoring/**` and `/api/admin/**` are ADMIN-only; all other paths require Bearer JWT (catch-all `anyRequest().authenticated()` — covers `/api/billing/**`). `JwtAuthenticationFilter` instantiated directly here to prevent double-registration. Declares the `BCryptPasswordEncoder` bean. CORS configured for localhost and production origins.

---

## `ticker` — Ticker Domain

### `Ticker`
Immutable Java record. Fields: `symbol`, `hasFutures` (always true), `hasSpot`.

### `TickerRegistry`
`@Component`. Thread-safe, lock-free store backed by `AtomicReference<Map<String, Ticker>>`. Refresh is a single pointer swap.

### `TickerService`
`@Service`. Fetches exchange info from Spot and Futures APIs concurrently (`Mono.zip`), applies eligibility rules (USDT-quoted, PERPETUAL, TRADING), atomically replaces the registry, and publishes `TickersRefreshedEvent`.

### `TickerRefreshScheduler`
`@Component`. Calls `TickerService.refreshTickers()` on a configurable `fixedDelayString` schedule (default `PT4H`).

### `TickerController`
`@RestController` at `/api/tickers`. Debug endpoint — `GET /api/tickers` returns the current ticker list.

### `TickersRefreshedEvent`
Spring `ApplicationEvent` published after each successful ticker registry refresh. `BinanceWebSocketManager` listens to this to open or update WebSocket subscriptions.

---

## Tests

### `ScreenerBackendApplicationTests`
`@SpringBootTest` smoke test. Verifies the Spring context initializes without errors.

### `UserClassificationRulesTest`
Plain JUnit unit test. Verifies `UserClassificationRules.configuredKeys()` mirrors the `byKey` map and `ruleFor(key)` returns the leaf for configured keys / `null` otherwise.

### `DefaultClassificationRuleTest` / `ThresholdClassificationRuleTest`
Plain JUnit unit tests for the default tier tables and the per-user threshold rule's tier computation.

### `UserFeedRegistryTest`
Plain JUnit unit test of the session/context lifecycle and live rule propagation, using hand-rolled test doubles (no Mockito): connect builds + pushes a context and sets it on the session; a no-rules user gets no context; a second connect reuses the context without a DB reload or re-push; the context survives until the last session disconnects; disconnecting an unknown session or the same session twice is a no-op; `onRuleUpdated` Cases A (rule update → context replaced, sessions retargeted + NEED_SNAPSHOT), B (first rule while connected → context created; no sessions → no-op), and C (all rules deleted → context removed, sessions revert to null context); rapid consecutive updates leave last-write-wins state.

### `ClassificationRuleServiceTest`
Plain JUnit unit test with reflective proxy repository stubs. Verifies `RuleUpdatedEvent` is published exactly once after `upsertRules` and `deleteRules`, and never on validation failure.

### `PricingServiceTest`
Plain JUnit unit test (reflective proxy repos). Verifies the resolved currency is echoed on the response, `BigDecimal` amounts survive exactly, plans without an active price in the requested currency are omitted, and an empty catalog when no active plans.

### `PlanAdminServiceTest`
Plain JUnit unit test with stateful in-memory reflective proxy repos. Verifies code-uniqueness conflict, the `FIXED`⇒duration / `PER_DAY`⇒null invariant, soft-delete (`active=false`, idempotent, `404` when absent), price upsert (create-then-update the same `(plan, currency)` row with currency normalization), negative-amount/bad-currency rejection, and `updatePlan` immutability of `code`/`type`.

### `EntitlementServiceTest`
Plain JUnit unit test with a stateful in-memory reflective proxy repo. Verifies trial seeding (7-day unpaid), stacking from a future vs a past expiry, the ADMIN short-circuit (`ADMIN`/null), the derived `{TRIAL, ACTIVE, EXPIRED}` states, and `hasAccess` for admin/valid/expired users.

---

## What Is Not Yet Implemented

| Feature | Notes |
|---------|-------|
| Klines streams | Candlestick data integration for additional analysis signals |
| Access-gate enforcement | Wiring `EntitlementService.hasAccess` into REST endpoints and WS `@OnOpen`; mid-session WS expiry (enforcement plan) |
| Auto-renewal / saved cards | Recurring charges, saved-instrument concept, renewal sweep, grace period — out of scope by business decision (see payment doc §15) |
| Full webhooks & production fiscalization | Per-status (SHA-1) Multicard webhooks and real `ofd` tax payload (`mxik`/`package_code`) — the payment adapter is shaped for these but they are deferred |
| Account settings / localization | Real geo/IP/phone `RegionResolver`, `user_settings` table (currency/country/locale), multi-currency seed (KZT/RUB/crypto) |
| User roles and privileges | `USER`/`ADMIN` exist; `PREMIUM` (paid) tier and per-plan limits (max tracked tickers, max custom rules) not yet enforced |
| Distributed deployment | Ticker partitioning across multiple JVM instances |
| Primitive orderbook store | Replace `TreeMap<Double, PriceLevelEntry>` with a memory-efficient structure |
| Snapshot optimization | Caching, pre-warming, or higher-weight API access to reduce startup sync time |
| Additional exchanges | Architecture is Binance-first; non-Binance support is future work |
