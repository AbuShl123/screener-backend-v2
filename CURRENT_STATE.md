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
│   │   ├── UserClassificationRule.java
│   │   ├── UserClassificationContext.java
│   │   ├── UserFeedRegistry.java
│   │   └── rule/
│   │       ├── ClassificationRuleEntity.java
│   │       ├── ClassificationRuleRepository.java
│   │       ├── ClassificationRuleService.java
│   │       ├── ClassificationRuleController.java
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
│   │   ├── AuthService.java
│   │   ├── AuthenticatedUser.java
│   │   ├── JwtAuthenticationFilter.java
│   │   ├── JwtService.java
│   │   └── dto/
│   │       ├── AuthResponse.java
│   │       ├── LoginRequest.java
│   │       ├── RefreshRequest.java
│   │       ├── RegisterRequest.java
│   │       └── UserProfileResponse.java
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
│   │   │   ├── OrderBookController.java
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
│   │   ├── BinanceApiProperties.java
│   │   ├── DisruptorProperties.java
│   │   ├── JwtProperties.java
│   │   ├── OrderbookProperties.java
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
│       └── V3__create_classification_rules.sql
└── test/java/dev/abu/screener_backend/
    ├── ScreenerBackendApplicationTests.java
    └── analysis/
        ├── UserClassificationRuleTest.java
        └── UserFeedRegistryTest.java
```

**Implementation status**: All core features are complete. The full pipeline — Binance WebSocket integration, LMAX Disruptor processing, orderbook sync, order classification, feed broadcasting, and client WebSocket delivery — is implemented and wired together. JWT auth, PostgreSQL user storage, per-user classification rules (persistence, REST CRUD, runtime wiring, two-pass classifier, broadcaster merge), and Spring Security are all complete. The only major item not yet implemented is live propagation of rule edits to already-connected users.

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

`AuthResponse` carries `accessToken`, `refreshToken` (raw value), and `expiresIn` (seconds).

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

Enum with one value: `USER`. Extend with `ADMIN`, `PREMIUM` as needed.

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

### `OrderBookController`
`src/main/java/dev/abu/screener_backend/binance/orderbook/OrderBookController.java`

`@RestController` at `/api/orderbook`. Debug endpoint — `GET /api/orderbook?symbol=BTCUSDT&market=FUTURES` returns the current bids/asks with price, quantity, distance, and lifetime for each level, plus the book's sync state and level counts. Reads are best-effort (no synchronization — acceptable for debugging).

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

### `UserClassificationRule`
`src/main/java/dev/abu/screener_backend/analysis/UserClassificationRule.java`

POJO. A per-user lookup table `Map<String, ThresholdClassificationRule> byKey` keyed by `"SYMBOL:MARKET"`, plus a cached `configuredKeys()` set for O(1) hot-path membership. Does not implement `ClassificationRule` — the classifier fetches the leaf via `ruleFor(key)`.

### `UserClassificationContext`
`src/main/java/dev/abu/screener_backend/analysis/UserClassificationContext.java`

POJO. One per connected user with at least one rule, shared by all that user's sessions. Bundles `userId`, the `UserClassificationRule`, a personal `OrderBookFeedStore`, and a `ConcurrentHashMap<String, SymbolState> states` (concurrent because multiple shard threads insert configured keys; each `SymbolState` value stays single-threaded). Users with no rules get no context and consume the global feed directly.

### `UserFeedRegistry`
`src/main/java/dev/abu/screener_backend/analysis/UserFeedRegistry.java`

`@Component`. Source of truth for active contexts, refcounted per `userId`. All mutation runs inside one `synchronized` block. `onUserConnect(userId)` builds a context via `ruleService.buildRuntimeRule` on first connect (returns `null` for a no-rules user) or reuses + bumps the refcount on subsequent connects. `onUserDisconnect(userId)` decrements and discards the context only when the last session closes. Each lifecycle change rebuilds an immutable `volatile active[]` array and fans it out to every shard via `shardManager.setActiveUserContexts(active)`.

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

- `upsertRules(userId, BulkRuleRequest)` — `@Transactional`; delete-then-insert the tier set per target
- `deleteRules(userId, BulkDeleteRequest)` — `@Transactional`; bulk delete per target; idempotent
- `getRules(userId)` — groups tier rows by `(symbol, market)` into `RuleResponse` list
- `getRule(userId, symbol, market)` — single pair; `404` if none
- `buildRuntimeRule(userId)` — `@Transactional(readOnly = true)`. Translates persisted rows into an immutable runtime `UserClassificationRule` (`Optional.empty()` if the user has no rules). Called by `UserFeedRegistry` at WebSocket connect time.

Validation: tiers non-empty; each tier ∈ `[1,4]`; no duplicates; tiers contiguous starting at 1; `minNotional ≥ 0`; `maxDistance ∈ (0, priceFilterThreshold]`; each target is a currently-tracked ticker covering the requested market; total targets per request ≤ `screener.classification.max-targets-per-request` (default 200).

### `ClassificationRuleController`
`src/main/java/dev/abu/screener_backend/analysis/rule/ClassificationRuleController.java`

`@RestController` at `/api/rules`. `userId` always from the JWT principal, never the body.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `GET` | `/api/rules/default` | Public | Return the global default rule (`DefaultRuleResponse`) |
| `GET` | `/api/rules` | Bearer JWT | List caller's rules grouped by `(symbol, market)` |
| `GET` | `/api/rules/{symbol}/{market}` | Bearer JWT | Caller's rule for one pair (404 if none) |
| `PUT` | `/api/rules` | Bearer JWT | Bulk upsert — replace tier set for each target |
| `DELETE` | `/api/rules` | Bearer JWT | Bulk delete (reset to default) |

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
| `@OnOpen` | Validate JWT query param, call `userFeedRegistry.onUserConnect(userId)`, create `UserWebSocketSession`, start send loop, register with broadcaster |
| `@OnClose` | Retrieve session → `release(session)`: `disconnect()` + `broadcaster.removeSession()` + (guarded) `userFeedRegistry.onUserDisconnect(userId)` |
| `@OnError` | Same `release(session)` path |
| `@OnMessage` | Handles `SNAPSHOT_REQUEST` — sets session status to `NEED_SNAPSHOT` |

Tomcat may fire both `@OnClose` and `@OnError` for one connection. The `release` path is guarded by the session's one-shot `markReleased()` (AtomicBoolean CAS), ensuring exactly one registry refcount decrement per session.

### `UserWebSocketSession`
`src/main/java/dev/abu/screener_backend/ws/UserWebSocketSession.java`

Per-session object created in `@OnOpen`. Fields:
- `jakartaSession` — the underlying `jakarta.websocket.Session`
- `sendQueue` — `ArrayBlockingQueue<List<String>>` capacity 32; each slot is one drain cycle's batch
- `status` (volatile) — `NEED_SNAPSHOT` or `READY`
- `running` (volatile) — send loop exit signal; written by `disconnect()`
- `seqNumber` — per-session monotonic counter; accessed exclusively by the broadcaster thread
- `virtualThread` (volatile) — reference to the send loop thread for interruption
- `context` (final, nullable) — this user's `UserClassificationContext`, or `null` for a default-only user
- `released` (AtomicBoolean) — one-shot guard for the registry decrement

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

### `SecurityConfig`
`@Configuration`. Spring Security filter chain. Stateless (`STATELESS` session policy, CSRF disabled). Public paths: `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/ws`. All other paths require Bearer JWT. `JwtAuthenticationFilter` instantiated directly here to prevent double-registration. Declares the `BCryptPasswordEncoder` bean. CORS configured for localhost and production origins.

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

### `UserClassificationRuleTest`
Plain JUnit unit test. Verifies `UserClassificationRule.configuredKeys()` mirrors the `byKey` map and `ruleFor(key)` returns the leaf for configured keys / `null` otherwise.

### `UserFeedRegistryTest`
Plain JUnit unit test of the refcount lifecycle, using hand-rolled test doubles (no Mockito): connect builds + pushes a context; a no-rules user gets no context; a second connect reuses the context without a DB reload or re-push; the context survives until the last session disconnects; disconnecting an unknown user is a no-op.

---

## What Is Not Yet Implemented

| Feature | Notes |
|---------|-------|
| Live rule propagation | Rule edits take effect only after all of a user's sessions reconnect; atomic swap + fresh snapshot push is not implemented |
| Klines streams | Candlestick data integration for additional analysis signals |
| Payment gateway | Integration with a payment provider (e.g. Stripe) for subscription billing and lifecycle events |
| User subscription model | Per-plan limits (max tracked tickers, max custom rules) persisted on the `User` entity and enforced at the service layer |
| User roles and privileges | Expand `UserRole` to `USER` (free), `PREMIUM` (paid), and `ADMIN`; enforce role-based access on REST and WebSocket endpoints |
| Distributed deployment | Ticker partitioning across multiple JVM instances |
| Primitive orderbook store | Replace `TreeMap<Double, PriceLevelEntry>` with a memory-efficient structure |
| Snapshot optimization | Caching, pre-warming, or higher-weight API access to reduce startup sync time |
| Additional exchanges | Architecture is Binance-first; non-Binance support is future work |
