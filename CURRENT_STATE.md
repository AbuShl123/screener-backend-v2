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
│   │           └── RuleResponse.java
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
│   │   │   ├── OrderBookProcessor.java
│   │   │   ├── OrderBookResult.java
│   │   │   ├── OrderBookState.java
│   │   │   ├── OrderBookStore.java
│   │   │   ├── PriceLevelEntry.java
│   │   │   └── SnapshotFetchQueue.java
│   │   └── websocket/
│   │       ├── Market.java
│   │       ├── RawDepthMessageHandler.java
│   │       ├── LoggingDepthMessageHandler.java   ← inactive (@Component removed); kept for reference
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

**Implementation status**: Ticker enumeration, registry, WebSocket connection layer (Phase 1), Disruptor pipeline (Phase 2), orderbook management with sync state machine and snapshot fetch queue (Phase 3), classification + feed pipeline (Phase 4/5), outbound WebSocket server (Phase 5), and JWT-based auth with PostgreSQL user storage (Phase 6) are complete. Per-user classification is complete through **Phase C**: persistence + REST CRUD (Phase A), the `ClassificationRule` abstraction (Phase B), and connect-time runtime wiring with the two-pass classifier and broadcaster merge (Phase C). Live propagation of rule edits to connected users (Phase D) is **not yet implemented** — rule edits take effect only after all of a user's sessions reconnect. Real default tier thresholds live in `DefaultClassificationRule`.

---

## Application Entry Point

### `ScreenerBackendApplication`
`src/main/java/dev/abu/screener_backend/ScreenerBackendApplication.java`

Spring Boot entry point. Runs as a servlet/Tomcat application (not Netty) even though `spring-boot-starter-webflux` is on the classpath — the `spring.main.web-application-type=servlet` property forces this. `@EnableScheduling` activates the ticker refresh scheduler and snapshot dispatch schedulers.

---

## `auth` — Authentication & Token Management (Phase 6)

### `JwtService`
`src/main/java/dev/abu/screener_backend/auth/JwtService.java`

`@Service`. Handles all JWT operations using Nimbus JOSE JWT with HS256 signing. Key responsibilities:
- `generateAccessToken(User)` — builds a signed JWT with `sub` (UUID), `email`, `role`, `iat`, `exp`; access tokens expire in **3 hours**
- `generateRawRefreshToken()` — produces a 32-byte `SecureRandom` value, Base64-URL encoded
- `hashToken(String)` — SHA-256 hex digest; used to store refresh tokens safely in the DB
- `validateAndExtract(String)` — parses and verifies a JWT, checks expiry, returns `AuthenticatedUser` or `null` on any failure

The signing key is an `OctetSequenceKey` built from the base64-decoded `screener.jwt.secret` property.

### `JwtAuthenticationFilter`
`src/main/java/dev/abu/screener_backend/auth/JwtAuthenticationFilter.java`

`OncePerRequestFilter`. Reads the `Authorization: Bearer <token>` header, validates with `JwtService.validateAndExtract()`, and sets a `UsernamePasswordAuthenticationToken` on `SecurityContextHolder`. **No database call per request** — all needed data is in the JWT claims. Instantiated directly in `SecurityConfig` (not a `@Bean`) to prevent double-registration as a servlet filter.

### `AuthService`
`src/main/java/dev/abu/screener_backend/auth/AuthService.java`

`@Service @Transactional`. Implements register, login, refresh, logout, and user lookup. Stores one refresh token per user (old token invalidated on each new login). Throws `ApiException` (from the `error` package) with the appropriate HTTP status for all error cases; the global handler maps it to a standardized JSON body.

- `register(RegisterRequest)` — validates fields, checks email uniqueness (409 on conflict), BCrypt-hashes the password, persists the user, issues a token pair
- `login(LoginRequest)` — looks up user by email, verifies BCrypt hash (401 on mismatch), issues a token pair
- `refresh(String rawToken)` — SHA-256 hashes the incoming token, looks it up in DB, checks expiry, issues a new token pair
- `logout(UUID userId)` — deletes the user's refresh token row; no-op if none exists
- Private `issueTokenPair(User)` — deletes any existing refresh token for the user, creates a new one, returns `AuthResponse`

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

Record: `(userId, email, role)`. Used as the Spring Security principal set by `JwtAuthenticationFilter` and retrieved from `Authentication.getPrincipal()` in controller methods.

---

## `user` — User Domain

### `User`
`src/main/java/dev/abu/screener_backend/user/User.java`

JPA entity mapped to the `users` table. Fields: `id` (UUID, generated), `firstName`, `lastName`, `email` (unique), `passwordHash` (BCrypt), `role` (`UserRole` enum, STRING-mapped), `enabled`, `createdAt`. `@PrePersist` sets `createdAt`, defaults `role` to `USER`, and sets `enabled = true`.

### `UserRole`
`src/main/java/dev/abu/screener_backend/user/UserRole.java`

Enum with one value: `USER`. Extend with `ADMIN`, `PREMIUM` etc. as needed.

### `RefreshToken`
`src/main/java/dev/abu/screener_backend/user/RefreshToken.java`

JPA entity mapped to `refresh_tokens`. Fields: `id`, `user` (eager `@ManyToOne` → `users`), `tokenHash` (SHA-256 of the raw token), `expiresAt`, `createdAt`. Only the hash is stored — raw tokens never persist.

### `UserRepository`
`src/main/java/dev/abu/screener_backend/user/UserRepository.java`

`JpaRepository<User, UUID>`. Derived queries: `findByEmail`, `existsByEmail`.

### `RefreshTokenRepository`
`src/main/java/dev/abu/screener_backend/user/RefreshTokenRepository.java`

`JpaRepository<RefreshToken, UUID>`. `findByTokenHash` for lookup; `deleteByUserId(UUID)` via `@Modifying @Query` for efficient single-statement delete without a prior SELECT.

---

## `binance/api` — Binance REST Integration

### `BinanceRestClient`
`src/main/java/dev/abu/screener_backend/binance/api/BinanceRestClient.java`

`@Component`. Generic, reusable HTTP client for all Binance REST calls. Holds two named `WebClient` beans (one for Spot, one for Futures). All methods return cold `Mono<T>` publishers — callers decide whether to block or subscribe reactively. Non-2xx responses are converted to `BinanceApiException` and logged at WARN.

- `getSpot(path, responseType)` — GET against Spot base URL
- `getFutures(path, responseType)` — GET against Futures base URL

### `BinanceApiException`
`src/main/java/dev/abu/screener_backend/binance/api/BinanceApiException.java`

`RuntimeException` subclass. Carries the HTTP status code and raw response body from a failed Binance REST call. Thrown by `BinanceRestClient` on non-2xx responses.

### `WeightGuard`
`src/main/java/dev/abu/screener_backend/binance/api/WeightGuard.java`

Plain object (no Spring annotations). Tracks Binance API weight usage for one market (spot or futures). Two `volatile long` fields: `lastObservedWeight` and `lastObservedAtMs` (server send time from the HTTP `Date` header).

- `delayMillisRequired()` — returns 0 if the minute has flipped or weight is below threshold; otherwise returns ms until the next minute boundary + 1 s safety buffer
- `observe(sentTimeMs, weight)` — records a weight observation; discards out-of-order stale responses (older AND lower)

Constructed with a `long threshold`. Owned by `WebClientConfig`.

### `WeightLimitFilter`
`src/main/java/dev/abu/screener_backend/binance/api/WeightLimitFilter.java`

Implements `ExchangeFilterFunction` (WebClient middleware). Wraps every outbound request: checks `WeightGuard.delayMillisRequired()` before the call, and feeds `x-mbx-used-weight-1m` + HTTP `Date` header back to the guard after each response. Uses server send time (not local clock) to avoid minute-boundary errors under network latency.

### `BinanceSymbolDto`
`src/main/java/dev/abu/screener_backend/binance/api/dto/BinanceSymbolDto.java`

Jackson DTO for a single entry in Binance's `/exchangeInfo` `symbols` array. Maps only the fields needed for eligibility filtering: `symbol`, `quoteAsset`, `status`, `contractType`.

### `ExchangeInfoResponse`
`src/main/java/dev/abu/screener_backend/binance/api/dto/ExchangeInfoResponse.java`

Jackson DTO for the Binance `/exchangeInfo` response. Only maps `symbols: List<BinanceSymbolDto>`.

---

## `binance/disruptor` — LMAX Disruptor Pipeline

### `EventType`
`src/main/java/dev/abu/screener_backend/binance/disruptor/EventType.java`

Enum with two values: `DIFF` (a stream depth update) and `SNAPSHOT` (a REST snapshot response). Used by `DepthEvent` to distinguish how the consumer should route the event.

### `DepthEvent`
`src/main/java/dev/abu/screener_backend/binance/disruptor/DepthEvent.java`

Ring buffer event carrier. Mutable fields: `type` (EventType), `symbol`, `market` (Market), `rawJson`. `clear()` nulls all fields after consumption to avoid retaining references in the ring buffer slots.

### `DepthEventFactory`
`src/main/java/dev/abu/screener_backend/binance/disruptor/DepthEventFactory.java`

`EventFactory<DepthEvent>` — pre-allocates `DepthEvent` instances into the ring buffer at startup.

### `DepthEventHandler`
`src/main/java/dev/abu/screener_backend/binance/disruptor/DepthEventHandler.java`

`EventHandler<DepthEvent>` — consumer for one shard. Delegates every event to `OrderBookProcessor.process()` and then calls `event.clear()`.

### `DisruptorShardManager`
`src/main/java/dev/abu/screener_backend/binance/disruptor/DisruptorShardManager.java`

`@Component`. Creates and owns all Disruptor shards. On `@PostConstruct` starts `shardCount` disruptors (`BlockingWaitStrategy`, `ProducerType.MULTI`), each with one `DepthEventHandler` wrapping one `OrderBookClassifier` (built with the shared `DefaultClassificationRule`). `getRingBuffer(symbol)` hashes the symbol to a shard via `abs(symbol.hashCode()) % shardCount` — deterministic, stable assignment.

Keeps an `OrderBookClassifier[] classifiers` and exposes `setActiveUserContexts(UserClassificationContext[])` (Phase C) — fans the **same** array reference to every shard's classifier, because a user's configured symbols spread across all shards. Called from the Tomcat connect/disconnect thread via `UserFeedRegistry`.

### `DisruptorDepthMessageHandler`
`src/main/java/dev/abu/screener_backend/binance/disruptor/DisruptorDepthMessageHandler.java`

`@Component`. Implements `RawDepthMessageHandler`. Called from the WebSocket `onMessage` callback. Publishes a `DIFF` event to the correct shard's ring buffer. This is the only work done on the WebSocket thread — minimal and non-blocking.

---

## `binance/orderbook` — Orderbook Management (Phase 3)

### `OrderBookState`
`src/main/java/dev/abu/screener_backend/binance/orderbook/OrderBookState.java`

Enum with three values:

| State                | Meaning |
|----------------------|---------|
| `PENDING`            | Snapshot not yet requested; diff updates are dropped |
| `SNAPSHOT_REQUESTED` | Snapshot request dispatched; diffs are buffered in `diffBuffer` |
| `SYNCED`             | Live — diffs applied in real time |

Note: The originally planned `SYNCING` and `STALE` states are not implemented. Re-sync failures call `resync()` which returns directly to `PENDING`.

### `OrderBookResult`
`src/main/java/dev/abu/screener_backend/binance/orderbook/OrderBookResult.java`

Enum returned by `OrderBook` methods to signal what action the caller (`OrderBookProcessor`) should take:

| Value           | Meaning |
|-----------------|---------|
| `OK`            | Event processed normally |
| `NEEDS_SNAPSHOT`| PENDING orderbook received first diff; caller must enqueue and replay diff |
| `NEEDS_RESYNC`  | Sync failure; caller must re-enqueue for snapshot |
| `DROPPED`       | Event dropped for a known, non-actionable reason |

### `PriceLevelEntry`
`src/main/java/dev/abu/screener_backend/binance/orderbook/PriceLevelEntry.java`

Mutable value object for a single price level. Fields:
- `quantity` (mutable `double`) — current quantity at this price; updated in-place on each diff (zero allocation on hot path)
- `firstSeenMillis` (final `long`) — wall-clock timestamp when this level first appeared in the current continuous presence; never changed after insertion

### `OrderBook`
`src/main/java/dev/abu/screener_backend/binance/orderbook/OrderBook.java`

Core class. Maintains a local orderbook for one `(symbol, market)` pair. Exposes four public getters for the classifier: `getSymbol()`, `getMarket()`, `getBids()`, `getAsks()`. The TreeMap references returned by `getBids()`/`getAsks()` are live — must only be read by the owning shard's consumer thread.

**Fields**:
- `bids`: `TreeMap<Double, PriceLevelEntry>` with `Comparator.reverseOrder()` — `firstKey()` = best bid
- `asks`: `TreeMap<Double, PriceLevelEntry>` natural order — `firstKey()` = best ask
- `diffBuffer`: `ArrayDeque<String>` — raw diff JSON strings, populated only in `SNAPSHOT_REQUESTED`
- `state`: `volatile OrderBookState` — volatile because `SnapshotFetchQueue` scheduler thread writes `SNAPSHOT_REQUESTED`
- `lastUpdateId`: last `u` value applied; used for sequence continuity validation
- `lastPu`: last `pu` value (futures continuity); updated alongside `lastUpdateId`
- `filterThreshold`: fraction from mid-price beyond which levels are swept (from `screener.orderbook.price-filter-threshold`, currently `0.1`)
- `MAX_BUFFER_SIZE`: 500 — hard cap on `diffBuffer` size to prevent memory overflow

**Key methods**:

`onDiff(rawJson)` — routes a diff based on current state:
- `PENDING` → returns `NEEDS_SNAPSHOT`
- `SNAPSHOT_REQUESTED` → buffers the raw JSON string; returns `NEEDS_RESYNC` if buffer overflows
- `SYNCED` → calls `applyLiveDiff()`

`applySnapshot(rawJson)` — applies a REST snapshot and drains the diff buffer:
1. Parses `lastUpdateId` (snapshotId), bids, asks from snapshot JSON
2. Discards buffered diffs where `u < snapshotId` (strict, not `<=`)
3. Validates that `snapshotId ∈ [U, u]` of the first remaining diff
4. Loads snapshot levels into TreeMaps
5. Applies the first buffered diff (levels only, no sequence check)
6. Applies remaining buffered diffs via `applyLiveDiff()`
7. Transitions to `SYNCED`; calls `resync()` on any failure

`applyLiveDiff(rawJson)` — applies one diff in live mode:
1. Parses `U`, `u`, `pu`, bids, asks via Jackson streaming API
2. Validates continuity: SPOT checks `U == lastUpdateId + 1`; FUTURES checks `pu == lastUpdateId`
3. Applies level updates to both TreeMaps (remove if qty=0, upsert otherwise)
4. Sweeps levels outside ±30% of mid-price
5. Sets `lastUpdateId = u`

`resync()` — clears the diff buffer and returns to `PENDING`.

`markSnapshotRequested()` — called by the `SnapshotFetchQueue` scheduler thread; volatile write to `SNAPSHOT_REQUESTED`.

For a detailed description of the sync algorithm including sequence ID semantics, non-obvious design decisions, and the 3-second delay rationale, see `.claude/docs/orderbook-sync-algorithm.md`.

### `OrderBookStore`
`src/main/java/dev/abu/screener_backend/binance/orderbook/OrderBookStore.java`

`@Component`. Registry of all active `OrderBook` instances, keyed by `"SYMBOL:MARKET"` string. Backed by `ConcurrentHashMap` so the `SnapshotFetchQueue` scheduler thread can safely read it while consumer threads write.

- `getOrCreate(symbol, market)` — lazily creates an `OrderBook` via `computeIfAbsent`; safe because each symbol always hashes to the same shard, so `computeIfAbsent` is never called concurrently for the same key
- `get(symbol, market)` — lookup without creation; used for SNAPSHOT events where the book must already exist
- `size()` — current count

### `OrderBookProcessor`
`src/main/java/dev/abu/screener_backend/binance/orderbook/OrderBookProcessor.java`

`@Component`. Called by each `DepthEventHandler` for every ring buffer event. Routes events to the correct `OrderBook` and reacts to `OrderBookResult` values.

For `DIFF` events: `getOrCreate` to ensure the book exists, call `ob.onDiff()`.  
For `SNAPSHOT` events: `get` (book must already exist), call `ob.applySnapshot()`.

When result is `NEEDS_SNAPSHOT` or `NEEDS_RESYNC`:
- Calls `snapshotFetchQueue.enqueue(ob)`
- If enqueue succeeds: calls `ob.markSnapshotRequested()` to transition the state
- If result was `NEEDS_SNAPSHOT` (first diff for a PENDING book): replays the current diff into `ob.onDiff()` so it is buffered and not lost
- If enqueue fails (queue at capacity): does nothing; book stays `PENDING`

### `SnapshotFetchQueue`
`src/main/java/dev/abu/screener_backend/binance/orderbook/SnapshotFetchQueue.java`

`@Component`. Rate-limited snapshot dispatcher. Maintains two `ConcurrentHashMap<String, OrderBook>` queues (spot and futures), each bounded by a configurable max size (default 10 each).

`enqueue(ob)` — adds an orderbook to the appropriate queue if not already at capacity. Thread-safe; called from consumer threads.

`@Scheduled dispatchSpot()` / `dispatchFutures()` — fires every `spot-snapshot-dispatch-rate-ms` (6 000 ms). Fires all queued HTTP snapshot requests concurrently via `BinanceRestClient`. Each request applies `.delayElement(Duration.ofSeconds(3))` before publishing the snapshot — this gives the diff buffer time to accumulate enough events to span the snapshot's `lastUpdateId`, avoiding immediate re-sync loops. On success, removes from queue and publishes a `SNAPSHOT` event to the correct shard's ring buffer. On failure, removes and re-enqueues for retry.

`publishSnapshotEvent(ob, rawJson)` — publishes to the Disruptor ring buffer from the Reactor/WebClient thread. Never writes to the `OrderBook` directly — all mutations are single-threaded via the shard consumer.

`@Lazy DisruptorShardManager` — injected lazily to break the circular dependency: `DisruptorShardManager → OrderBookProcessor → SnapshotFetchQueue → DisruptorShardManager`.

---

## `analysis` — Order Classification

The classification rule (which tier a level falls into) is split from the state machine (HIGH/LOW
tracking, change detection) and feed submission. The same state-machine/feed logic runs against any
`ClassificationRule`: the always-on global default, plus a per-user override for configured
`(symbol, market)` keys. See the three per-user-classification plan docs under `.claude/plans/`.

### `ClassificationRule`
`src/main/java/dev/abu/screener_backend/analysis/ClassificationRule.java`

Pure, stateless interface (Phase B). `int computeTier(notional, distance, highLiquidity)` returns
the tier 1–4 a level matches, or 0 (invisible). `double maxDistance(highLiquidity)` returns the
widest distance any tier can match — drives the classifier's distance-ordered early-break. The
`highLiquidity` flag is a default-rule concern threaded in by the rule-agnostic classifier; user
rules ignore it.

### `DefaultClassificationRule`
`src/main/java/dev/abu/screener_backend/analysis/DefaultClassificationRule.java`

`@Component` singleton (Phase B). The real default tier thresholds, the `HIGH_LIQUIDITY_TICKERS`
set, and `isHighLiquidity(symbol)`. Two threshold tables: a tighter high-liquidity table
(`maxDistance` 0.025) and the normal table (`maxDistance` 0.05). Shared across all shards (stateless).

### `ThresholdClassificationRule`
`src/main/java/dev/abu/screener_backend/analysis/ThresholdClassificationRule.java`

Immutable per-`(symbol, market)` user override leaf (Phase B; consumed in Phase C). Built off the
hot path from a list of `TierThreshold(tier, minNotional, maxDistance)` records into parallel
primitive arrays sorted highest-tier-first; `computeTier` returns the first (highest) matching tier
or 0. Absolute thresholds — ignores `highLiquidity`. `maxDistance(...)` returns the precomputed
widest configured distance.

### `OrderBookClassifier`
`src/main/java/dev/abu/screener_backend/analysis/OrderBookClassifier.java`

Not a Spring bean — constructed manually by `DisruptorShardManager`, one instance per shard. Per-shard
state is accessed exclusively by the owning shard's consumer thread; the only cross-thread field is
the `volatile UserClassificationContext[] activeUserContexts` (swapped atomically, read once per
`process()`).

**Entry point**: `process(OrderBook ob)` — runs a **two-pass** classification (Phase C):
1. **Default pass** — always — against the per-shard `defaultStates` (`HashMap`), `defaultRule`, and
   the global `OrderBookFeedStore`.
2. **Per-user passes** — for each active context whose `configuredKeys()` contain this key — against
   that context's own `states` map, override leaf rule, and personal feed store.

`highLiquidity` is computed once per book via `defaultRule.isHighLiquidity()` and threaded into both
passes. When no custom users are connected, the user loop body never runs (one volatile read + empty
check). The shared per-context work lives in `classifyOne(ob, key, state, rule, feedStore, hl)`,
which contains the full state machine — so a configured book leaving SYNCED also DROPs out of the
user's personal feed and resets that context's state.

**Per-context state per `(symbol, market)`**: a `SymbolState` tracking `ActivityLevel` (LOW/HIGH),
the last-emitted `ClassifiedLevel[]` working arrays, and two reusable top-K `Scratch` buffers.
Selection picks the top-5 by `(tier DESC, notional DESC, distance ASC)`; a side is visible iff its
best slot has tier ≥ 1. No `ClassifiedLevel` is allocated for LOW books (the dominant GC win).

### `SymbolState`
`src/main/java/dev/abu/screener_backend/analysis/SymbolState.java`

Package-private (Phase C — extracted from `OrderBookClassifier` with no behavior change so a context
can declare `Map<String, SymbolState>`). Holds the `ActivityLevel`, the `workBids`/`workAsks`
arrays, and the two `Scratch` buffers (nested class). Each instance is single-threaded (its key is
pinned to one shard), even when the enclosing map is a `ConcurrentHashMap`.

### `UserClassificationRule`
`src/main/java/dev/abu/screener_backend/analysis/UserClassificationRule.java`

POJO (Phase C). A per-user lookup table `Map<String, ThresholdClassificationRule> byKey` keyed by
`"SYMBOL:MARKET"`, plus a cached `configuredKeys()` set for O(1) hot-path membership. Deliberately
does **not** implement `ClassificationRule`: the classifier fetches the leaf via `ruleFor(key)` and
passes it to selection; unconfigured keys are never touched by the user pass (they reach the user via
the broadcaster merge).

### `UserClassificationContext`
`src/main/java/dev/abu/screener_backend/analysis/UserClassificationContext.java`

POJO (Phase C). One per connected user **with at least one rule**, shared by all that user's
sessions. Bundles `userId`, the `UserClassificationRule`, a personal `OrderBookFeedStore` (plain
`new` instance — the store is dependency-free), and a `ConcurrentHashMap<String, SymbolState> states`
(concurrent because multiple shard threads insert configured keys; each `SymbolState` value stays
single-threaded). Users with no rules get no context (`session.context == null`) and consume the
global feed directly.

### `UserFeedRegistry`
`src/main/java/dev/abu/screener_backend/analysis/UserFeedRegistry.java`

`@Component` (Phase C). Source of truth for active contexts, **refcounted per `userId`**. All mutation
runs inside one `synchronized` block (connect/disconnect are rare Tomcat-thread calls).
`onUserConnect(userId)` builds a context via `ruleService.buildRuntimeRule` on first connect (returns
`null` for a no-rules user) or reuses + bumps the refcount on subsequent connects;
`onUserDisconnect(userId)` decrements and discards the context only when the last session closes.
Each lifecycle change rebuilds an immutable `volatile active[]` array, fans it out to every shard via
`shardManager.setActiveUserContexts(active)`, and exposes it to the broadcaster via
`activeContexts()`. The array is never mutated in place, so readers need no lock.

---

## `analysis/rule` — Per-User Classification Rules (Phase A: persistence + REST; Phase C seam)

Persistence and REST CRUD for user-defined classification thresholds, plus the Phase C
connect-time translation seam (`buildRuntimeRule`). See `.claude/plans/per-user-classification-phase-a.md`
and `.claude/plans/per-user-classification-phase-c.md`.

### `ClassificationRuleEntity`
`src/main/java/dev/abu/screener_backend/analysis/rule/ClassificationRuleEntity.java`

JPA entity mapped to `classification_rules`. **One row per tier** — a logical rule for one `(user, symbol, market)` is 1–4 rows. Fields: `id` (UUID), `user` (LAZY `@ManyToOne` → `users`), `symbol`, `market` (`Market` enum, STRING-mapped), `tierNo`, `minNotional`, `maxDistance`, `createdAt`, `updatedAt`. `@PrePersist`/`@PreUpdate` maintain timestamps. Unique constraint on `(user_id, symbol, market, tier_no)`.

### `ClassificationRuleRepository`
`src/main/java/dev/abu/screener_backend/analysis/rule/ClassificationRuleRepository.java`

`JpaRepository<ClassificationRuleEntity, UUID>`. `findByUserId`, `findByUserIdAndSymbolAndMarket`, and `@Modifying deleteByUserIdAndSymbolAndMarket` (bulk DELETE that runs immediately, so it precedes the replacement inserts — avoids a `uq_rule_tier` violation from Hibernate insert/delete reordering).

### `ClassificationRuleService`
`src/main/java/dev/abu/screener_backend/analysis/rule/ClassificationRuleService.java`

`@Service`. Tomcat-thread CRUD + validation; shares no state with the Disruptor pipeline. All validation runs before any DB write — the whole request is rejected atomically with `400` on first failure.

- `upsertRules(userId, BulkRuleRequest)` — `@Transactional`; for each `(assignment, target)`: replace (delete-then-insert) the tier set. `userRepository.getReferenceById` sets the FK without an extra SELECT.
- `deleteRules(userId, BulkDeleteRequest)` — `@Transactional`; bulk delete per target; idempotent.
- `getRules(userId)` — groups tier rows by `(symbol, market)` into `RuleResponse` list.
- `getRule(userId, symbol, market)` — single pair; `404` if none.
- `buildRuntimeRule(userId)` — **Phase C seam**, `@Transactional(readOnly = true)`. Translates the
  user's persisted rows into an immutable runtime `UserClassificationRule` (`Optional.empty()` if the
  user has no rules), reusing the same `"SYMBOL:MARKET"` grouping as `getRules`; each group becomes a
  `ThresholdClassificationRule`. Called by `UserFeedRegistry` at WebSocket connect time. Input was
  range-validated at write time, so this does not re-validate.

Validation rules: tiers non-empty; each tier ∈ `[1,4]`; no duplicate tiers; **tiers contiguous starting at 1** (`{1,2,4}` rejected — tier 3 missing); `minNotional ≥ 0`; `maxDistance ∈ (0, priceFilterThreshold]` (upper bound read from live `OrderbookProperties`, currently `0.1` — a rule beyond the orderbook's price filter could never match); each target must be a currently-tracked ticker covering the requested market (via `TickerRegistry`); total targets per request ≤ `screener.classification.max-targets-per-request` (default 200). Symbols normalized to uppercase.

### `ClassificationRuleController`
`src/main/java/dev/abu/screener_backend/analysis/rule/ClassificationRuleController.java`

`@RestController` at `/api/rules`. `userId` always from the JWT principal, never the body.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `GET` | `/api/rules/default` | Public | Return the global default classification rule (`DefaultRuleResponse`) |
| `GET` | `/api/rules` | Bearer JWT | List the caller's rules, grouped by `(symbol, market)` |
| `GET` | `/api/rules/{symbol}/{market}` | Bearer JWT | The caller's rule for one pair (404 if none) |
| `PUT` | `/api/rules` | Bearer JWT | Bulk upsert — replace tier set for each target |
| `DELETE` | `/api/rules` | Bearer JWT | Bulk delete (reset to default) |

DTOs (`analysis/rule/dto/`): `TierDto`, `RuleDto`, `TargetDto`, `RuleAssignmentDto`, `BulkRuleRequest`, `BulkDeleteRequest`, `RuleResponse`, `DefaultRuleResponse` — records mirroring the auth DTO style.

---

## `error` — Global Exception Handling

Standardizes the MVC-layer error response so every failed request that reaches the dispatcher returns the same JSON shape (`{message, status, path}`) with a human-readable message. Keeps the service layer free of `org.springframework.web.*` exception types. Does **not** touch authentication: unauthenticated requests are still rejected by Spring Security before the dispatcher (empty `403`). See `.claude/plans/global-exception-handling.md`.

### `ApiException`
`src/main/java/dev/abu/screener_backend/error/ApiException.java`

`RuntimeException` carrying an `HttpStatus` plus a client-safe message. The single exception the application layer throws for any expected, client-facing failure. Static factories: `badRequest`, `notFound`, `conflict`, `unauthorized`. A dedicated type (not `IllegalArgumentException`) ensures only messages *we* choose reach the client.

### `ApiError`
`src/main/java/dev/abu/screener_backend/error/ApiError.java`

Record `(String message, int status, String path)` — the uniform JSON body serialized for every MVC-layer error.

### `GlobalExceptionHandler`
`src/main/java/dev/abu/screener_backend/error/GlobalExceptionHandler.java`

`@RestControllerAdvice` — the only place exceptions become HTTP responses. Mapping: `ApiException` → its status + message; `HttpMessageNotReadableException` → `400` "Malformed JSON request body"; `ErrorResponseException` (Spring 6's common 4xx base — unknown endpoint 404, wrong method 405, wrong `Content-Type` 415, missing/mistyped params, and any stray `ResponseStatusException`) → its status with a generic status-derived message; any other `Exception` → `500` "Internal server error", real cause logged via `log.error`, never echoed to the client.

---

## `feed` — Feed Store and Broadcaster

### `ClassifiedLevel`
`src/main/java/dev/abu/screener_backend/feed/ClassifiedLevel.java`

Record: `(price, quantity, tier, firstSeenMillis, distance)`. All-primitive fields. `distance` is the fractional distance from mid-price (`0.05` = 5%), carried verbatim from `PriceLevelEntry.distance` and delivered to clients. The classifier's `applyNewOrders` detects changes by comparing these fields slot-by-slot (not `Arrays.equals`), allocating a new `ClassifiedLevel` only when a slot's value actually changed.

### `FeedEventType`
`src/main/java/dev/abu/screener_backend/feed/FeedEventType.java`

Enum: `ADD`, `UPDATE`, `DROP`.

### `OrderBookUpdate`
`src/main/java/dev/abu/screener_backend/feed/OrderBookUpdate.java`

Record: `(symbol, market, type, bids[], asks[])`. Used as both `snapshotMap` values and `pendingRef` values. Arrays are `ClassifiedLevel[TOP_LEVELS]` with null sentinels beyond actual count. Do not compare two `OrderBookUpdate` instances with `equals()` — record `equals()` on array fields is reference equality.

### `OrderBookFeedStore`
`src/main/java/dev/abu/screener_backend/feed/OrderBookFeedStore.java`

`@Component`. Shared data structure between classifier (consumer threads) and broadcaster (sender thread).

- `snapshotMap`: `ConcurrentHashMap<String, OrderBookUpdate>` — authoritative current active state; read on new-client connect
- `pendingRef`: `AtomicReference<ConcurrentHashMap<String, OrderBookUpdate>>` — pending updates since last drain; swapped atomically by broadcaster

**`submit(key, update)`** — coalesces via `ConcurrentHashMap.merge()`, then syncs `snapshotMap` directly from the incoming update's type (DROP → remove, ADD/UPDATE → put). The snapshotMap sync does not re-read `pendingRef`, so it is unaffected by the drain race. Coalescing: ADD + UPDATE in the same 100ms window delivers UPDATE to clients; the client is expected to treat UPDATE as ADD for an unknown symbol.

**`drainPending()`** — atomic swap; returns the old map for broadcaster processing.

**`getSnapshot()`** — unmodifiable view of `snapshotMap` for initial snapshot delivery.

### `OrderBookBroadcaster`
`src/main/java/dev/abu/screener_backend/feed/OrderBookBroadcaster.java`

`@Component`. Runs the 100ms drain loop on a single `@Scheduled` thread. All broadcaster logic is single-threaded — no synchronization inside this class. Injects `UserFeedRegistry` to read active contexts each tick (Phase C).

- `drain()` — drains the global `feedStore.drainPending()` once, then drains **each active context's personal feed once per tick** (a context may back multiple sessions). Global update bodies are built lazily as a **keyed** `Map<"SYMBOL:MARKET", body>` so they can be filtered per custom session. Per session:
  - `NEED_SNAPSHOT` → default session gets the global snapshot; a custom session gets a **merged snapshot** (global filtered to exclude its `configuredKeys`, unioned with its personal snapshot).
  - `READY` default session → the global bodies (today's path).
  - `READY` custom session → its personal bodies **plus** global bodies for keys it has **not** configured — exactly one authoritative update per `(symbol, market)` per tick.
  - `enqueueBatch()` returning `false` evicts the slow client via `session.disconnect()`.
- `addSession(session)` / `removeSession(session)` — called from the WebSocket endpoint on connect/disconnect; thread-safe via `CopyOnWriteArrayList`.
- `@PreDestroy shutdown()` — signals all sessions to disconnect on Spring context shutdown.
- JSON building uses a reusable `StringBuilder sb` (4096-byte initial capacity); all body strings are captured via `toString()` before `injectSeq()` reuses the buffer. `injectSeq()` prepends `{"seq":N,` to a pre-built body string; `seq` stays strictly per-session across its merged batch.

The broadcaster never touches the TCP socket. Its only per-session interaction is `enqueueBatch()` — a non-blocking in-memory offer.

---

## `ws` — Outbound WebSocket Server (Phase 5)

For a full description of the design, concurrency model, message protocol, and session lifecycle, see `.claude/docs/websocket-server.md`.

### `WebSocketConfig`
`src/main/java/dev/abu/screener_backend/ws/WebSocketConfig.java`

`@Configuration`. Declares a single `ServerEndpointExporter` bean. This tells Spring to scan for `@ServerEndpoint`-annotated classes and register them with the embedded Tomcat WebSocket container. Without it, `ScreenerWebSocketEndpoint` is compiled and discovered by Spring but never activated by Tomcat.

### `CustomSpringConfigurator`
`src/main/java/dev/abu/screener_backend/ws/CustomSpringConfigurator.java`

`@Component`. Bridges Spring DI into Tomcat's WebSocket lifecycle. Tomcat instantiates `@ServerEndpoint` classes itself, bypassing Spring — which would leave `@Autowired` fields null. This class stores the `ApplicationContext` in a `static volatile` field via `ApplicationContextAware.setApplicationContext()`. When a connection arrives, Tomcat calls `getEndpointInstance(ScreenerWebSocketEndpoint.class)`, which returns `context.getBean(ScreenerWebSocketEndpoint.class)` — the Spring singleton with all dependencies already injected.

Replaces `SpringConfigurator` from `spring-websocket`, which fails in Spring Boot's embedded Tomcat because it looks up the `ApplicationContext` via `ContextLoaderListener` (which does not exist in embedded mode).

### `ScreenerWebSocketEndpoint`
`src/main/java/dev/abu/screener_backend/ws/ScreenerWebSocketEndpoint.java`

Singleton `@Component` + `@ServerEndpoint("/ws")`. All client connections share one instance — safe because it holds no per-connection mutable state. Per-session state is stored in `session.getUserProperties()` under the key `"session"`. Autowires `UserFeedRegistry` for connect-time rule loading (Phase C).

| Callback | Responsibility |
|---|---|
| `@OnOpen` | Validate JWT, then `userFeedRegistry.onUserConnect(userId)` (may return `null`); create `UserWebSocketSession(session, userId, context)`, store in `getUserProperties`, start send loop, register with broadcaster |
| `@OnClose` | Retrieve session → `release(session)`: `disconnect()` + `broadcaster.removeSession()` + (guarded) `userFeedRegistry.onUserDisconnect(userId)` |
| `@OnError` | Same `release(session)` path; Tomcat fires both on error |
| `@OnMessage` | Currently handles only `SNAPSHOT_REQUEST` (raw string); sets session status to `NEED_SNAPSHOT` |

**Refcount-safe teardown**: Tomcat may fire both `@OnClose` and `@OnError` for one connection. `disconnect()`/`removeSession()` are idempotent, but a registry refcount decrement is **not** — so `onUserDisconnect` is guarded by the session's one-shot `markReleased()` (AtomicBoolean CAS), ensuring exactly one decrement per session.

### `UserWebSocketSession`
`src/main/java/dev/abu/screener_backend/ws/UserWebSocketSession.java`

Per-session object created in `@OnOpen` and stored in `session.getUserProperties()`. Holds:

- `jakartaSession` — the underlying Tomcat `jakarta.websocket.Session`
- `sendQueue` — `ArrayBlockingQueue<List<String>>` with capacity 32; each slot is one drain cycle's worth of pre-serialized messages
- `status` (`volatile`) — `NEED_SNAPSHOT` or `READY`; readable by broadcaster, writable by `@OnMessage` Tomcat thread
- `running` (`volatile`) — send loop exit signal; written by broadcaster (via `disconnect()`), read by virtual thread
- `seqNumber` — per-session monotonic counter; accessed exclusively by the broadcaster thread (not `volatile`); reset by `resetSeq()` before snapshot delivery, incremented by `getAndIncrementSeq()`
- `virtualThread` (`volatile`) — reference to the send loop thread for interruption
- `context` (final, nullable) — this user's `UserClassificationContext`, or `null` for a default-only user; read by the broadcaster to drive the per-session merge (Phase C)
- `released` (`AtomicBoolean`) — one-shot guard; `markReleased()` returns `true` only on the first call so the registry refcount is decremented exactly once across the `@OnClose`/`@OnError` double-fire

Key methods: `startSendLoop()`, `enqueueBatch(List<String>)`, `disconnect()`, `resetSeq()`, `getAndIncrementSeq()`, `getContext()`, `markReleased()`.

The send loop runs on a Java 21 virtual thread: `sendQueue.take()` parks (not OS-blocks) when empty; `sendText()` parks when the TCP buffer is full. A stalled client parks only its own virtual thread — the broadcaster and all other sessions are unaffected.

---

## `binance/websocket` — WebSocket Connection Layer

### `Market`
`src/main/java/dev/abu/screener_backend/binance/websocket/Market.java`

Enum: `SPOT`, `FUTURES`.

### `RawDepthMessageHandler`
`src/main/java/dev/abu/screener_backend/binance/websocket/RawDepthMessageHandler.java`

`@FunctionalInterface`. Single method `handle(symbol, market, rawJson)`. Called from WebSocket `onMessage` — must be fast; no blocking, no heavy parsing.

### `BinanceStreamClient`
`src/main/java/dev/abu/screener_backend/binance/websocket/BinanceStreamClient.java`

Wraps one `java-websocket` `WebSocketClient` connection. Manages `@depth@100ms` stream subscriptions, reconnection with exponential backoff, and calls the `RawDepthMessageHandler` on each message.

### `BinanceConnectionPool`
`src/main/java/dev/abu/screener_backend/binance/websocket/BinanceConnectionPool.java`

Manages a pool of `BinanceStreamClient` connections for one market, respecting the 1024-stream-per-connection limit.

### `BinanceWebSocketManager`
`src/main/java/dev/abu/screener_backend/binance/websocket/BinanceWebSocketManager.java`

`@Component`. Top-level coordinator. Initializes connection pools for spot and futures on `TickersRefreshedEvent`, distributes tickers across connections.

### `LoggingDepthMessageHandler`
`src/main/java/dev/abu/screener_backend/binance/websocket/LoggingDepthMessageHandler.java`

Inactive (no `@Component`). Kept for reference — was used to log raw depth messages during development.

---

## `config` — Spring Configuration

### `BinanceApiProperties`
`src/main/java/dev/abu/screener_backend/config/BinanceApiProperties.java`

Java record bound from `screener.binance.*`:

| Property                | Purpose |
|-------------------------|---------|
| `spotBaseUrl`           | Binance Spot REST base URL |
| `futuresBaseUrl`        | Binance Futures REST base URL |
| `codecBufferSizeMb`     | WebClient codec buffer limit (MB) |
| `spotWeightThreshold`   | Weight level at which spot requests are held (default 5 800) |
| `futuresWeightThreshold`| Weight level at which futures requests are held (default 2 200) |

### `DisruptorProperties`
`src/main/java/dev/abu/screener_backend/config/DisruptorProperties.java`

Java record bound from `screener.disruptor.*`: `shardCount`, `ringBufferSize`.

### `OrderbookProperties`
`src/main/java/dev/abu/screener_backend/config/OrderbookProperties.java`

Java record bound from `screener.orderbook.*`:

| Property                        | Purpose |
|---------------------------------|---------|
| `priceFilterThreshold`          | Fraction from mid-price beyond which levels are swept (`price-filter-threshold`, currently `0.1`) |
| `spotSnapshotDispatchRateMs`    | Scheduler interval for spot snapshot dispatch (ms) |
| `futuresSnapshotDispatchRateMs` | Scheduler interval for futures snapshot dispatch (ms) |

Note: `spot-snapshot-queue-size` and `futures-snapshot-queue-size` are read via `@Value` directly in `SnapshotFetchQueue` rather than through this record.

### `WebClientConfig`
`src/main/java/dev/abu/screener_backend/config/WebClientConfig.java`

`@Configuration`. Produces `spotWebClient` and `futuresWebClient` beans. Each is built with its market's `WeightGuard` + `WeightLimitFilter`, enlarged codec buffer, and pre-set `Accept: application/json` header. Also enables `BinanceApiProperties`, `WebSocketProperties`, `DisruptorProperties`, and `OrderbookProperties` via `@EnableConfigurationProperties`.

### `WebSocketProperties`
`src/main/java/dev/abu/screener_backend/config/WebSocketProperties.java`

Java record bound from `screener.websocket.*`: stream URLs, max streams per connection, subscribe chunk size, reconnect delays.

### `JwtProperties`
`src/main/java/dev/abu/screener_backend/config/JwtProperties.java`

Java record bound from `screener.jwt.*`: `secret` (base64-encoded signing key), `accessTokenExpiry` (Duration, default 3 h), `refreshTokenExpiry` (Duration, default 7 d). Registered via `@EnableConfigurationProperties` in `WebClientConfig`.

### `SecurityConfig`
`src/main/java/dev/abu/screener_backend/config/SecurityConfig.java`

Real JWT-based security filter chain. Stateless (`STATELESS` session policy, CSRF disabled). Public paths: `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/ws`. All other paths require a valid Bearer JWT. `JwtAuthenticationFilter` is instantiated directly here (not as a `@Bean`) to avoid double-registration as a Tomcat servlet filter. Also declares the `BCryptPasswordEncoder` bean.

---

## `ticker` — Ticker Domain

### `Ticker`
`src/main/java/dev/abu/screener_backend/ticker/Ticker.java`

Immutable Java record. Fields: `symbol`, `hasFutures` (always true), `hasSpot`.

### `TickerRegistry`
`src/main/java/dev/abu/screener_backend/ticker/TickerRegistry.java`

`@Component`. Thread-safe, lock-free in-memory store backed by `AtomicReference<Map<String, Ticker>>`. Refresh is a single pointer swap.

### `TickerService`
`src/main/java/dev/abu/screener_backend/ticker/TickerService.java`

`@Service`. Fetches exchange info from Spot and Futures APIs concurrently (`Mono.zip`), applies eligibility rules, atomically replaces the registry.

### `TickerRefreshScheduler`
`src/main/java/dev/abu/screener_backend/ticker/TickerRefreshScheduler.java`

`@Component`. Calls `TickerService.refreshTickers()` on a configurable `fixedDelayString` schedule (`PT4H`).

### `TickerController`
`src/main/java/dev/abu/screener_backend/ticker/TickerController.java`

`@RestController` at `/api/screener`. Debug endpoint — `GET /api/screener/tickers`.

### `TickersRefreshedEvent`
`src/main/java/dev/abu/screener_backend/ticker/TickersRefreshedEvent.java`

Spring `ApplicationEvent` published after each successful ticker registry refresh. `BinanceWebSocketManager` listens to this event to (re-)open WebSocket subscriptions.

---

## Tests

### `ScreenerBackendApplicationTests`
`src/test/java/dev/abu/screener_backend/ScreenerBackendApplicationTests.java`

`@SpringBootTest` smoke test. Single `contextLoads()` method — verifies the Spring context initializes without errors.

### `UserClassificationRuleTest`
`src/test/java/dev/abu/screener_backend/analysis/UserClassificationRuleTest.java`

Plain JUnit unit test (Phase C). Verifies `UserClassificationRule.configuredKeys()` mirrors the
`byKey` map and `ruleFor(key)` returns the leaf for configured keys / `null` otherwise.

### `UserFeedRegistryTest`
`src/test/java/dev/abu/screener_backend/analysis/UserFeedRegistryTest.java`

Plain JUnit unit test (Phase C) of the refcount lifecycle, using hand-rolled test doubles (no
Mockito): connect builds + pushes a context; a no-rules user gets no context; a second connect
reuses the context without a DB reload or re-push; the context survives until the **last** session
disconnects; disconnecting an unknown user is a no-op.

---

## What Is Not Yet Implemented

| Component | Status |
|-----------|--------|
| WebSocket connection pool (java-websocket) | **Complete (Phase 1)** |
| LMAX Disruptor pipeline (sharded ring buffers) | **Complete (Phase 2)** |
| Binance API weight limit guard (`WeightGuard` + `WeightLimitFilter`) | **Complete (Phase 2.5)** |
| Orderbook store (`TreeMap<Double, PriceLevelEntry>`) | **Complete (Phase 3)** |
| Orderbook sync state machine (PENDING → SYNCED) | **Complete (Phase 3)** |
| Snapshot fetch queue with rate limiting | **Complete (Phase 3)** |
| 30% price level filter | **Complete (Phase 3)** |
| Order classification pipeline (classifier + feed store + broadcaster) | **Complete (Phase 4/5)** |
| Outbound WebSocket server (`ws/` package, virtual-thread delivery) | **Complete (Phase 5)** |
| JWT auth (register/login/refresh/logout/me) + PostgreSQL user storage | **Complete (Phase 6)** |
| WebSocket auth (query-param JWT validated in `@OnOpen`) | **Complete (Phase 6)** |
| Per-user classification rules — persistence + REST CRUD (`analysis/rule/`) | **Complete (Phase A)** |
| Per-user classification — `ClassificationRule` abstraction (default + threshold rules) | **Complete (Phase B)** |
| Per-user classification — runtime wiring (contexts, registry, two-pass classifier, broadcaster merge, connect-time loading) | **Complete (Phase C)** |
| Real classification thresholds (proximity %, notional USD) | **Complete** — default tiers in `DefaultClassificationRule`; per-user overrides via `ThresholdClassificationRule` |
| Per-user classification — live propagation of rule edits to connected users (rebuild + atomic swap + fresh SNAPSHOT) | Not started (Phase D) |

### Phase C accepted limitations

- **Cold-start snapshot gap**: a just-registered context's personal store is empty until the next diff classifies each configured symbol (sub-second for SYNCED books). The first merged snapshot may omit those symbols; they appear via UPDATE within a tick or two.
- **`TOP_LEVELS = 5` still caps custom users**: a user with wide/lenient thresholds still receives only the top 5 per side by `(tier, notional, distance)`.
- **Edits need a full reconnect of all the user's sessions**: a second connect reuses the already-loaded context, so a rule edit takes effect only after every session for that user disconnects and a fresh connect reloads from the DB. Live propagation is Phase D.
