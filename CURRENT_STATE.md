# Current State — Screener Backend

This file documents every class under `src/` so future sessions don't need to re-explore the codebase from scratch. Update this file whenever a class is added, removed, or significantly changed.

---

## Project Layout

```
src/
├── main/java/dev/abu/screener_backend/
│   ├── ScreenerBackendApplication.java
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
│   │   ├── OrderbookProperties.java
│   │   ├── SecurityConfig.java
│   │   ├── WebClientConfig.java
│   │   └── WebSocketProperties.java
│   └── ticker/
│       ├── Ticker.java
│       ├── TickerController.java
│       ├── TickerRefreshScheduler.java
│       ├── TickerRegistry.java
│       ├── TickerService.java
│       └── TickersRefreshedEvent.java
└── test/java/dev/abu/screener_backend/
    └── ScreenerBackendApplicationTests.java
```

**Implementation status**: Ticker enumeration, registry, WebSocket connection layer (Phase 1), Disruptor pipeline (Phase 2), and orderbook management with sync state machine and snapshot fetch queue (Phase 3) are complete. Order classification is **not yet implemented**.

---

## Application Entry Point

### `ScreenerBackendApplication`
`src/main/java/dev/abu/screener_backend/ScreenerBackendApplication.java`

Spring Boot entry point. Runs as a servlet/Tomcat application (not Netty) even though `spring-boot-starter-webflux` is on the classpath — the `spring.main.web-application-type=servlet` property forces this. `@EnableScheduling` activates the ticker refresh scheduler and snapshot dispatch schedulers.

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

`@Component`. Creates and owns all Disruptor shards. On `@PostConstruct` starts `shardCount` disruptors (`BlockingWaitStrategy`, `ProducerType.MULTI`), each with one `DepthEventHandler`. `getRingBuffer(symbol)` hashes the symbol to a shard via `abs(symbol.hashCode()) % shardCount` — deterministic, stable assignment.

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

Core class. Maintains a local orderbook for one `(symbol, market)` pair.

**Fields**:
- `bids`: `TreeMap<Double, PriceLevelEntry>` with `Comparator.reverseOrder()` — `firstKey()` = best bid
- `asks`: `TreeMap<Double, PriceLevelEntry>` natural order — `firstKey()` = best ask
- `diffBuffer`: `ArrayDeque<String>` — raw diff JSON strings, populated only in `SNAPSHOT_REQUESTED`
- `state`: `volatile OrderBookState` — volatile because `SnapshotFetchQueue` scheduler thread writes `SNAPSHOT_REQUESTED`
- `lastUpdateId`: last `u` value applied; used for sequence continuity validation
- `lastPu`: last `pu` value (futures continuity); updated alongside `lastUpdateId`
- `filterThreshold`: fraction from mid-price beyond which levels are swept (0.30)
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
| `priceFilterThreshold`          | Fraction from mid-price beyond which levels are swept (0.30) |
| `spotSnapshotDispatchRateMs`    | Scheduler interval for spot snapshot dispatch (ms) |
| `futuresSnapshotDispatchRateMs` | Scheduler interval for futures snapshot dispatch (ms) |

Note: `spot-snapshot-queue-size` and `futures-snapshot-queue-size` are read via `@Value` directly in `SnapshotFetchQueue` rather than through this record.

### `WebClientConfig`
`src/main/java/dev/abu/screener_backend/config/WebClientConfig.java`

`@Configuration`. Produces `spotWebClient` and `futuresWebClient` beans. Each is built with its market's `WeightGuard` + `WeightLimitFilter`, enlarged codec buffer, and pre-set `Accept: application/json` header. Also enables `BinanceApiProperties`, `WebSocketProperties`, `DisruptorProperties`, and `OrderbookProperties` via `@EnableConfigurationProperties`.

### `WebSocketProperties`
`src/main/java/dev/abu/screener_backend/config/WebSocketProperties.java`

Java record bound from `screener.websocket.*`: stream URLs, max streams per connection, subscribe chunk size, reconnect delays.

### `SecurityConfig`
`src/main/java/dev/abu/screener_backend/config/SecurityConfig.java`

Temporary placeholder — permits all requests, CSRF disabled. Will be replaced with JWT-based auth once the core pipeline is stable.

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
| Order classification (Purple/Red/Yellow/Green/Gray tiers) | Not started |
| User WebSocket server (push to subscribers) | Not started |
