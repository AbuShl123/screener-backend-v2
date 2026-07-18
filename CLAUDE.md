# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Backend for a cryptocurrency market screener. It is a classical **Spring Boot 4 (MVC/servlet, Java 21)** application that combines several independent concerns:

- **Market-data pipeline** — maintains accurate local Binance order books (spot + futures) and classifies price levels by importance. This is the performance-critical core and the largest module, but it is one feature among many.
- **Accounts** — registration, login, email verification, JWT auth, `USER`/`ADMIN` roles.
- **Monetization** — a plan catalog, per-user access entitlement (with a free trial), an order/payment flow (Multicard gateway today), and entitlement enforcement on the screener.
- **Delivery** — a per-user classified feed pushed over a WebSocket server, with per-user custom classification rules layered on top of global defaults.

Everything runs in **one JVM**. Persistence is **PostgreSQL** via Spring Data JPA + Flyway migrations. Outbound HTTP (to Binance, to Multicard) uses `WebClient` from `spring-webflux` — WebFlux is a client library only; the app itself runs on Tomcat (`spring.main.web-application-type=servlet`).

Target scale for the market-data core: ~500 futures tickers + a spot subset = 1000+ concurrent depth streams, hundreds of thousands of diff messages per second.

---

## Build, Run, Test

Maven wrapper (`mvnw` / `mvnw.cmd`). Build artifact final name is `screener`.

```bash
./mvnw clean package            # full build + tests → target/screener.jar
./mvnw test                     # run all tests
./mvnw test -Dtest=OrderServiceTest              # single test class
./mvnw test -Dtest=OrderServiceTest#methodName   # single test method
./mvnw spring-boot:run          # run locally (needs env / local profile below)
```

**Local run** relies on secrets and DB coordinates supplied via environment variables (see `application.yml` — `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `MAIL_*`, `MULTICARD_*`, `SCREENER_ADMIN_EMAILS`, `SPRING_FLYWAY_ENABLED`, `SPRING_JPA_HIBERNATE_DDL_AUTO`). `application-local.yml` holds local-dev overrides (activate with `-Dspring.profiles.active=local`); it forces Multicard sandbox settings. Deployment steps live in `deployment-guide.md`.

---

## Module Map

All code is under `src/main/java/dev/abu/screener_backend/`. Each package is a feature slice; DTOs live in a `dto/` subpackage.

| Package | Responsibility |
|---------|----------------|
| `binance/` | **Hot path.** Market-data pipeline: `api/` (REST snapshot/exchange-info client, weight guard), `websocket/` (java-websocket connection pools to Binance depth streams), `disruptor/` (sharded LMAX ring buffers + consumer), `orderbook/` (TreeMap local books, sync state machine, snapshot fetch queue). |
| `analysis/` | Order classification engine (`OrderBookClassifier`, tier rules) and per-user custom rules — `rule/` holds the `/api/rules` CRUD, entity, and live rule-update propagation. |
| `feed/` | `OrderBookBroadcaster` (100ms drain loop), global + per-user feed stores, classified-level model. |
| `ws/` | Jakarta WebSocket server — `/ws` endpoint, JWT-gated `@OnOpen`, virtual-thread send loops, per-session state. |
| `auth/` | Registration, login, refresh, logout, email verification, `/api/auth/me`; JWT issue/validate + auth filter. |
| `user/` | `User`, `UserRole` (`USER`/`ADMIN`), refresh + email-verification tokens, `AdminBootstrap` (promotes configured admin emails on startup). |
| `email/` | `EmailService` + Thymeleaf HTML templates (`resources/templates/email/`), async registration-email listener. |
| `billing/` | Plan catalog: `Plan` + `PlanPrice` (`FIXED` day-bundles and `PER_DAY` pay-as-you-go), `Currency`, `PricingService`, `RegionResolver`. Public catalog `/api/billing-catalog`, admin catalog `/api/admin/billing`. |
| `entitlement/` | The single access source of truth. Derives access state (`TRIAL`/`ACTIVE`/`EXPIRED`/`ADMIN`) from an `accessExpiresAt` stamp; append-only `EntitlementLedger` audits every grant. `/api/billing/entitlement`. |
| `payment/` | Order lifecycle: `Order`, `OrderService`, `OrderStateMachine` (+ status history), scheduled `PaymentReconciliationService`, `PaymentProvider` abstraction. `multicard/` is the only provider impl today (client, signature, callback controller/service). `/api/billing/orders`, `/api/payment/multicard/callback`. |
| `ticker/` | Ticker enumeration + scheduled refresh; drives which symbols/streams are tracked. |
| `monitoring/` | Admin-only diagnostics: `/api/monitoring/presence`, `/api/monitoring/orderbook`. |
| `config/` | `@ConfigurationProperties` records (one per feature) + `SecurityConfig`, `WebClientConfig`, `AsyncConfig`. |
| `error/` | `ApiException` + `ApiError` + `GlobalExceptionHandler` — the canonical error shape for all REST responses. |

**Cross-cutting flows worth reading multiple files for:**
- **Money → access**: `MulticardCallbackService` / `PaymentReconciliationService` → `OrderService.markPaidAndGrant` → `EntitlementService.extend` (stacking `accessExpiresAt = max(now, expiry) + granted`, one ledger row). Both the callback and the reconciliation sweep funnel through the *same idempotent* grant. Grant happens **before** the callback acknowledges.
- **Access → screener**: `EntitlementService.hasAccess` gates both the WebSocket `@OnOpen` and every `/api/rules` endpoint. Admins bypass entitlement entirely.
- **Rule edit → live feed**: a rule change rebuilds the user's classification context, retargets connected sessions, and pushes a fresh snapshot — no reconnect needed.

---

## Performance Rules: Hot Path vs. Everything Else

The **`binance/`, `feed/`, and `analysis/` classifier** code is the hot path — it processes the full Binance depth firehose on the Disruptor consumer threads and the 100ms broadcaster. Strict rules apply **there**:

- **No object allocation** in `onMessage` callbacks and Disruptor `onEvent` loops. Reuse mutable objects; parse in place.
- **No `BigDecimal`** for market data. Prices/quantities are primitive `double` (parsed via fastdoubleparser). `double`'s precision is more than sufficient for a screener.
- **Jackson streaming (`JsonParser`)** for high-frequency diff parsing — extract only `b`, `a`, `U`, `u`, `pu`. Never full-POJO-deserialize a depth diff.
- **No per-message logging** at INFO+; use lazy-evaluated `log.debug("{}", x)`, never string concatenation.
- Order-book `TreeMap`s are **single-thread-owned** by their shard's consumer — never synchronized, never shared across shards.

**These rules do NOT apply to the rest of the codebase.** Auth, billing, entitlement, payment, email, admin, and REST controllers are ordinary Spring MVC + JPA code. Use `BigDecimal` for money (`Order.amount`, `PlanPrice`), full POJO deserialization for low-frequency REST/config, normal logging, and standard transactional service patterns. Do not import hot-path austerity into these modules — clarity wins there.

---

## Market-Data Pipeline (hot path detail)

The performance-critical core. Read the dedicated docs (`.claude/docs/orderbook-sync-algorithm.md`) before touching sync logic.

- **Streams**: `@depth` (spot 1/s, futures 1/500ms). Separate WebSocket connection pools for spot (`wss://stream.binance.com/ws`) and futures (`wss://fstream.binance.com/ws`), ≤1024 streams per connection.
- **Disruptor**: configurable shards (default 2), each a `Disruptor` + one dedicated consumer thread, `ProducerType.MULTI`, power-of-2 ring buffer. **Ticker → shard is `Math.abs(symbol.hashCode()) % shardCount`** — a ticker's events must never split across shards (books are not thread-safe).
- **Order books**: per `(symbol, market)`, `TreeMap<Double, PriceLevelEntry>` (bids reverse-ordered, asks natural). `PriceLevelEntry` is mutable and updated in place; `firstSeenMillis` tracks level lifetime. Zero-quantity updates **must** remove the level. Levels beyond the price filter (`screener.orderbook.price-filter-threshold`, default 0.1) are dropped; recalc mid-price *after* applying each batch.
- **Sync state machine** per book: `PENDING` (diffs dropped) → `SNAPSHOT_REQUESTED` (buffering) → `SYNCED` (live). Only `SYNCED` books produce classification output. Any sequence gap / parse error / empty buffer resets to `PENDING` and re-enqueues. Buffering starts when the snapshot **request is dispatched**, not when it returns. Futures additionally validate `pu` continuity. Snapshot fetches are rate-limited against Binance weight budgets.
- **Classification**: two passes per update — a default pass (global tiers → global feed) and a per-user pass (each connected custom-rules user → their personal feed). Top-5 levels per side ranked by `(tier DESC, notional DESC, distance ASC)`. The full order book (bounded only by the price filter) is retained so wide custom user rules can still see far levels.

---

## Conventions

- **Config**: every tunable is externalized to `application.yml` under `screener.*` and bound to a `@ConfigurationProperties` record in `config/`. Add new tunables there, not as literals.
- **DB schema**: change only via a new Flyway migration in `src/main/resources/db/migration/` (`V<n>__description.sql`). Never edit an applied migration. `baseline-version` is 4.
- **Errors**: throw `ApiException` (e.g. `ApiException.notFound(...)`); `GlobalExceptionHandler` renders the `ApiError` shape. Don't hand-roll error responses in controllers.
- **Auth on endpoints**: `SecurityConfig` is the allow-list. Public: `/api/auth/{register,login,refresh,verify-email,resend-verification}`, `/ws`, `/api/billing-catalog/**`, the Multicard callback. Admin-only: `/api/monitoring/**`, `/api/admin/**`. Everything else requires a valid JWT. Screener use additionally requires active entitlement.
- **Bean cycles**: broken with `@Lazy` injection (e.g. `EntitlementService` ↔ `OrderService`) — follow that existing pattern rather than restructuring.
- **Docs**: implementation plans go under `.claude/plans/`. Curated documentation lives under `.claude/docs/` (including `.claude/docs/for-frontend/` API contracts) and is *usually* current but not guaranteed. Some markdown files in `.claude/` are stale — trust the code first.

---

## Future Work

Planned: klines/candlestick streams for extra signals; expanded roles and plan-limit enforcement; additional payment providers behind the existing `PaymentProvider` abstraction. Long-term: a primitive-friendly order-book store to shed `TreeMap<Double,…>` boxing overhead, distributed ticker partitioning, and multi-exchange support (keep core interfaces free of Binance-specific assumptions).
