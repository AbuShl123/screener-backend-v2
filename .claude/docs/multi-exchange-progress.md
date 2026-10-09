# Market-Data Pipeline — Current State

**As of**: 2026-10-04, branch `feature/multi-exchange` (after the `exchange/` → `marketdata/` package
restructure).

This file describes the market-data pipeline as the code stands: what exists, how it fits together,
what each adapter does, and what is still missing. It ends at a `SYNCED` local order book.
Classification, the feed and the client WebSocket server are covered only where they depend on
venue identity. Where this file and the code disagree, the code is right.

All paths below are relative to `src/main/java/dev/abu/screener_backend/marketdata/`.

---

## 1. Status

| Venue | Adapter | Default | Live-verified |
|---|---|---|---|
| `BINANCE_SPOT` | `adapter/binance/` | enabled (`BINANCE_ENABLED`) | yes: ~354 books `SYNCED` ~2 min after start, held |
| `BINANCE_FUTURES` | `adapter/binance/` | enabled (`BINANCE_ENABLED`) | yes: ~525 books `SYNCED` ~2.5 min after start, held |
| `MEXC_FUTURES` | `adapter/mexc/` | **disabled** (`MEXC_ENABLED`, until Phase 5 of `.claude/plans/mexc-impl-plan.md`) | yes: ~678 books `SYNCED` ~3.5 min after the sockets open; zero steady-state resyncs over 25–40 min runs |

MEXC spot has no `Venue` constant and no adapter, because its stream is Protobuf and the pipeline
only handles text.

| Area | State |
|---|---|
| Identity (`Venue`, `Instrument`, dense ids, `BookSlotTable`) | Done |
| Sync SPI + per-venue strategies | Done (Binance spot, Binance futures, MEXC futures) |
| Transport SPI (`StreamProtocol`) + venue-agnostic connection pools | Done |
| Snapshot recovery (core queue + per-venue `SnapshotFetcher`) | Done |
| Discovery (`InstrumentSource` per exchange, merged by core) | Done; no startup retry (§12) |
| Config consolidation under `screener.exchanges.*` | Partial: `screener.orderbook.*` and `screener.websocket.*` still sit outside it |
| Robustness: reset lane, non-blocking ingress, dynamic subscribe, staleness watchdog, queryable health | Not started (§12) |
| Primitive-array book store | Not started |

---

## 2. Package layout

```
marketdata/
  Exchange, Market, Venue, Instrument, InstrumentRegistry,
  InstrumentUniverseService, InstrumentUniverseChangedEvent,
  TickerRefreshScheduler, TickerController          identity, universe, refresh, /api/tickers
  spi/                                              contract between core and adapters
  core/
    stream/     WebSocket transport (java-websocket)          hot
    ingress/    Disruptor shards, publisher, consumer         hot
    book/       OrderBook, BookSlot, BookSlotTable            hot
    recovery/   SnapshotRequestQueue, BatchOutcome, factory   hot (accept side)
    rest/       ExchangeWebClientFactory, ExchangeApiException
    health/     PipelineMetrics
  adapter/
    binance/  (+ dto/)
    mexc/     (+ dto/)
```

- **Root** holds what other modules import (`analysis`, `feed`, `ws`, `config`, `monitoring`), so
  those imports stay short.
- **`spi/`** sits beside core and the adapters, inside neither. Core never imports an adapter class.
- Every package was moved whole, so package-private access is unchanged. Test packages mirror
  the main ones for the same reason.

---

## 3. Identity and the universe

### 3.1 Types

- **`Venue = (Exchange, Market)`** is the adapter unit: `BINANCE_SPOT`, `BINANCE_FUTURES`,
  `MEXC_FUTURES`. `Market` stays the persistence- and API-facing type. `Venue.of(exchange, market)`
  bridges the two.
- **`Instrument`** (record) is one tradable pair on one venue: `id`, `venue`, `nativeSymbol`, `base`,
  `quote`, `quantityMultiplier`, plus four strings precomputed once:

  | Field | Example | Used for |
  |---|---|---|
  | `symbol` | `BTCUSDT` | `base + quote`, the normalized spelling for the rule API and the WS payload |
  | `ruleKey` | `BTCUSDT:FUTURES` | venue-agnostic user-rule lookup; must stay byte-identical to the stored rule format |
  | `feedKey` | `MEXC:FUTURES:BTCUSDT` | venue-specific classification state and feed-store entries |
  | `logName` | `MEXC_FUTURES/BTC_USDT` | log lines only |

  `quantityMultiplier` converts wire quantity to base asset. It is 1.0 on Binance and the contract
  size on MEXC futures. Strategies apply it at parse time, so every `OrderBook` holds base asset.
- **`InstrumentRegistry`** hands out dense `int` ids. They are **dense** (`[0, everRegistered)`), so
  the book store can be an array. They are **stable**: re-registering returns the same id. They are
  **never transferred**: a delisted id is left as a hole. They are **never persisted**:
  `(venue, nativeSymbol)` is the durable identity. A refresh that reports a different
  `quantityMultiplier` for an existing instrument is logged and ignored until restart.
  `isTracked(symbol, market)` answers "tracked on any exchange", which is what rule validation asks.
  `describe(id)` is the cold-path name lookup for logs.

### 3.2 Discovery

`TickerRefreshScheduler` calls `InstrumentUniverseService.refresh()` with
`@Scheduled(fixedDelay = screener.ticker.refresh-interval)` (PT4H). The first run at startup is what
brings the pipeline up.

`InstrumentUniverseService` is exchange-agnostic. It merges every `InstrumentSource` bean:

- **At construction**, it validates source claims. Each claims a non-empty set of venues of one
  exchange, and no venue is claimed twice. A source whose venues are all disabled is skipped. A
  partly-enabled source is a startup error.
- **On refresh**, it fetches every source concurrently on virtual threads under one
  `screener.discovery.source-timeout` deadline. Failure is **isolated per source**: a source that
  throws, times out, returns the wrong venues or empties a previously non-empty venue keeps its
  venues' previous universe and contributes no removals.
- **Exclusion**: it applies `screener.discovery.excluded-symbols` (written `base + quote`) to every
  validated result. Eligibility filters (quote asset, contract type, status) are hardcoded in each
  adapter's source.
- **Ids** are assigned in `(venue.ordinal(), nativeSymbol)` order, so they are reproducible
  across restarts.

**Ordering invariant** (discovery thread): **register all → `BookSlotTable.allocate` → `publish()` →
fire `InstrumentUniverseChangedEvent` → transport subscribes.** If a subscribe went out before
`publish()`, a frame could resolve an id past the end of the slot array. If every source fails, no
event fires.

`TickerController` (`GET /api/tickers`) is a debug view of the registry. Its `id` field is
process-local and must never be used as an identity.

---

## 4. Threads and data flow

```
 venue WS ──► StreamConnection.onMessage (reader thread, one per connection)
                │  protocol.route(frame, SubscriptionIndex) → instrument id
                ▼
            DepthEventPublisher.publishFrame ──► ring[id & (shards-1)]  (WS_MSG)
                                                      │
 snapshot-queue thread ──► SnapshotFetcher.fetchAll   │
                │  HTTP threads report each slot      │
                ▼                                     ▼
            BatchOutcome ──► publishSnapshot / publishSnapshotFailure (REST_MSG / REST_FAILED)
                                                      │
                               disruptor-shard-N consumer (DepthEventHandler)
                                   slot = slots.get(id)
                                   slot.strategy().onEvent(slot, event)   ← sync + book mutation
                                   classifier.process(instrument, book)   ← gates on SYNCED
```

| Thread | Owner | Work |
|---|---|---|
| java-websocket reader, one per connection | `StreamConnection` | route a frame, publish it into the ring |
| `reconnect-<venue>` | `ConnectionPool` | reconnect backoff and heartbeats for that venue's connections |
| `disruptor-shard-N` | `DisruptorShardManager` | **the only writer of its books and sync contexts**; runs strategy + classifier |
| `snapshot-queue` (daemon) | `SnapshotQueueFactory` | ticks every venue's queue at its `flush-interval` |
| Reactor / Netty | fetchers, REST clients | HTTP I/O; MEXC send pacing on `Schedulers.parallel()` |
| Spring `@Scheduled` | discovery refresh, `PipelineHealthLogger`, feed drain | cold work |
| virtual threads | `InstrumentUniverseService` | one per source fetch |

**Shard routing is `id & (shardCount - 1)`**, with `screener.disruptor.shard-count` validated as a
power of two at startup. Both producers go through `DisruptorDepthEventPublisher`, which is the only
fill-and-publish site, so an instrument's events can never split across shards.

---

## 5. The SPI and how adapters plug in

`spi/` holds every contract an adapter implements or consumes:

| Type | Kind | Role |
|---|---|---|
| `DepthSyncStrategy` | interface | `newContext()` once per book (cold); `onEvent(slot, event)` per event on the shard thread (hot) |
| `BookSyncContext` | marker interface | per-book sync state; opaque to core, never inspected |
| `RecoverySink` | interface | `boolean requestRecovery(slot)`; `false` = refused, ask again later |
| `SnapshotFetcher` | interface | `isAcceptingRequests()` (hot, volatile reads only) + `fetchAll(batch, outcome)` |
| `SnapshotOutcome` | interface, core-implemented | `delivered(slot, body)` / `failed(slot)` |
| `StreamProtocol` | interface | `subscribeFrame`, `routingKey`, `route` (hot), `heartbeat` |
| `Heartbeat` | sealed | `ProtocolPing(interval)` (WS control frame) or `TextPing(interval, payload)` |
| `InstrumentSource` | interface | `venues()` + blocking `fetch()` → `Map<Venue, List<InstrumentCandidate>>` |
| `InstrumentCandidate` | record | `nativeSymbol, base, quote, quantityMultiplier` (validated positive and finite) |
| `VenueStrategyBinding`, `VenueStreamBinding` | records | bind a venue to its strategy / protocol |
| `SyncStrategyRegistry`, `StreamProtocolRegistry` | core `@Component`s | `EnumMap<Venue, …>` assembled from the binding beans |

**Registration.** Each adapter has one `@Configuration` (`BinanceAdapterConfig`,
`MexcAdapterConfig`) that **constructs** its strategies, protocols, sources, REST clients and
snapshot queues as `@Bean`s. None of those classes is a `@Component`. A stray annotation would create
a second instance and fail startup as a duplicate binding or a second venue claim.

- `SyncStrategyRegistry` throws on a duplicate binding, and `forVenue` throws for an unbound venue.
  `BookSlotTable.allocate` resolves the strategy once per book and pins it in the `BookSlot`, so the
  consumer never walks `id → venue → strategy`.
- `StreamProtocolRegistry` also checks completeness at construction: an **enabled** venue with no
  binding fails startup.

**Bean cycle.** `BookSlotTable → SyncStrategyRegistry → VenueStrategyBinding (adapter config) →
SnapshotRequestQueue → SnapshotQueueFactory → DepthEventPublisher → DisruptorShardManager →
BookSlotTable`. It is broken by `@Lazy` on `SnapshotQueueFactory`'s publisher, the one place in core
that does this. Removing it fails the context.

---

## 6. Core

### 6.1 `core/stream/` — transport

- **`StreamManager`** listens for `InstrumentUniverseChangedEvent`. The first time a venue appears in
  `added`, that list is its whole universe, so the manager starts one `ConnectionPool` for it. Later
  changes to a venue that already has a pool are **only logged** ("dynamic re-subscription not yet
  implemented"). A pool that fails to start is logged, and the other venues are unaffected.
- **`ConnectionPool`** splits a venue's instruments evenly over
  `clamp(ceil(streams / max-streams-per-connection), min-connections, max-connections)` connections.
- **`StreamConnection`** (a java-websocket `WebSocketClient`) sends subscribe frames on open, chunked
  by `subscribe-chunk-size` and built by the protocol. It routes each text frame through
  `protocol.route` and publishes it (`PipelineMetrics.recordFrame`). It schedules the protocol's
  heartbeat. It reconnects with exponential backoff (`screener.websocket.reconnect-*`) and
  resubscribes everything. Frames for an unsubscribed key and **binary frames** are dropped with a
  rate-limited WARN.
- **`SubscriptionIndex`** is the per-connection `routingKey → id` map (`HashMap`, one `substring` per
  frame). It rejects two instruments sharing a key on one connection.

### 6.2 `core/ingress/` — Disruptor

- `DisruptorShardManager`: N shards, each a `Disruptor<DepthEvent>` with `ProducerType.MULTI`,
  `BlockingWaitStrategy`, `ring-buffer-size` slots, one consumer thread and its own
  `OrderBookClassifier`.
- `DepthEvent` is a reused mutable slot: `type`, `instrumentId`, `rawJson`.
- `EventType` records **provenance, not semantics**: `WS_MSG`, `REST_MSG`, `REST_FAILED` (null
  body). Whether a payload is a snapshot is the strategy's call. Neither REST type may ever be
  dropped, because each is the single outcome of a request.
- `DisruptorDepthEventPublisher` claims with the **blocking** `rb.next()`. A full ring stalls the
  producer, which for `WS_MSG` is a reader thread and therefore its whole connection.
- `DepthEventHandler` resolves the slot (a missing slot is counted and dropped, and should never
  happen), calls `slot.strategy().onEvent`, runs the classifier, then clears the event.

### 6.3 `core/book/` — storage

- **`BookSlotTable`** is a copy-on-write `BookSlot[]` indexed by id, published with one volatile
  store. `BookSlot = (instrument, book, strategy, ctx)`.
- **`OrderBook`** is pure storage. It holds `volatile OrderBookState state`
  (`PENDING → RECOVERING → SYNCED`), and two `TreeMap<Double, PriceLevelEntry>` (bids reverse-ordered,
  asks natural) owned by the shard thread. It exposes `applyLevel(isBid, price, qty, millis)` (a qty
  of 0 removes the level; an existing level is updated in place), `clearLevels()`, `markX()` and
  `computeDistance()`. `computeDistance()` recomputes the mid, drops every level outside
  ±`screener.orderbook.price-filter-threshold` (0.1) and stores `distance` as a **fraction** on the
  survivors. No venue, no sequence numbers, no parsing.
- **`PriceLevelEntry`**: mutable `quantity`, final `firstSeenMillis`, `distance`. A level's age
  starts when its price is first seen. Recovery clears every level, so **a resync resets all level
  ages on that book**.
- `state` is volatile only because `PipelineHealthLogger` reads it from another thread.

### 6.4 `core/recovery/` — snapshot queue

For a venue that recovers from REST snapshots, the adapter builds one queue through
`SnapshotQueueFactory.create(venue, fetcher)` and hands it to its strategy as the `RecoverySink`.

**`SnapshotRequestQueue`** (one per venue, not a `@Component`):
- `requestRecovery` runs on shard threads, so it is **lock-free and allocation-free**. It refuses
  while a batch is in flight, while the queue already holds `max-batch-size` requests, or while
  `fetcher.isAcceptingRequests()` is false. Otherwise one CAS reserves an index in a preallocated
  array. A refusal is the normal path during a ramp.
- `tick()` (on the `snapshot-queue` thread) CASes the queue `CLOSED` and drains the reserved slots.
  It spins for a consumer that won its CAS but has not stored yet, and dedupes by id. It then calls
  `fetcher.fetchAll`. The queue stays closed until the batch is sealed, so batches never overlap.
- **`BatchOutcome`** turns each slot's first report into exactly one ring event (`REST_MSG` or
  `REST_FAILED`). It seals when the fetcher's stage completes, fails, throws or passes
  `batch-timeout`: unreported slots are failed by core, and later or duplicate reports are dropped.
  Every failure increments `PipelineMetrics.recordSnapshotFailure`.
- **No retries and no delays in core.** A failure becomes `REST_FAILED` → `PENDING`, and the book
  asks again on its next diff. Back-off is purely refusal.

**`SnapshotQueueFactory`** owns the `@Lazy` publisher and the single `snapshot-queue` thread. That
thread is not Boot's shared `@Scheduled` thread, which a discovery refresh can block. The factory
requires the exchange's `snapshot-queue` block and checks `batch-timeout > rest.response-timeout`.
It cannot know a fetcher's pacing, so a pacing adapter adds its own check (§9.2).

### 6.5 `core/health/` and the health log

`PipelineMetrics` keeps per-venue `LongAdder` totals: `frames` (reader threads), `resyncs`
(strategies' `recover()`) and `snapshotFailures` (`BatchOutcome`). `monitoring/PipelineHealthLogger`
logs a block every 30s with one row per venue (synced/tracked, resyncs, snapshot failures, frames/s),
then msgs/s and free ring slots per shard, and the feed drain's worst tick. Nothing is queryable.

---

## 7. The sync-strategy contract

Every current strategy has the same shape (`BinanceDepthSyncStrategy`, `MexcFuturesSyncStrategy`).
A new strategy that recovers through `SnapshotRequestQueue` must follow it. The `DepthSyncStrategy`
javadoc states the `REST_FAILED` part.

### 7.1 Dispatch — `onEvent`

One flat if-chain, and **the only caller of `recover()`**:

| Book state | Event | Action |
|---|---|---|
| not `RECOVERING` | `REST_MSG` / `REST_FAILED` | **Drop.** A late, duplicate or superseded outcome never touches a book that is not waiting. |
| `RECOVERING` | `REST_MSG` | `handleSnapshot()`. Success → `markSynced()`. Failure → `recover()`. |
| `RECOVERING` | `REST_FAILED` | `ctx.reset()` → `markPending()`. No `recover()` (it would re-request at once and count a resync), and no `clearLevels()` (the book is already empty). |
| `SYNCED` | `WS_MSG` | `handleDiff()`. Failure → `recover()`. |
| `RECOVERING` | `WS_MSG` | Buffer the raw frame. Buffer full (500) → `recover()`. |
| `PENDING` | `WS_MSG` | `requestRecovery()`. Accepted → `markRecovering()` and buffer this frame. Refused → drop it, stay `PENDING`. |

`PENDING` re-asks on **every** diff. That is what makes refusal a safe back-off: a book that could
not get into the queue asks again on its next diff (≤1s), so none is stranded.

**`recover()`**: `recordResync` → `ctx.reset()` → `clearLevels()` → `markPending()` →
`requestRecovery()` → `markRecovering()` if accepted. `requestRecovery` must come before
`markRecovering`. This is safe only because outcomes come back as ring events, which the same
thread consumes after it sets the state.

**`handleDiff`**: `check()` → `DE_SYNCED` returns false; `IGNORE` applies nothing and returns
true; `OK` applies the levels, runs `computeDistance()` and returns true. Any exception logs a WARN
and returns false.

**`handleSnapshot`** (book guaranteed empty): stream the snapshot's levels straight into the book in
one pass, then read its sequence id (missing or unparseable → false). Run `computeDistance()`, set
the cursor, then drain the buffer with `pollFirst()` through the full `handleDiff` path, including
`check()`. The first failing diff clears the buffer and returns false. **An empty or all-stale buffer
is success**: the book syncs on the snapshot and validates the next live frame.

`check()` returns `OK` / `IGNORE` / `DE_SYNCED`, and only `OK` advances the cursor. A boolean
could not express "stale, drop it, stay synced".

### 7.2 Invariants

1. `recover()` is called from `onEvent` only, so there is at most one sink call per event. Helpers
   return `boolean` and never touch the sink.
2. **A `PENDING` or `RECOVERING` book has empty levels**, and a `PENDING` book also has an empty
   context (empty buffer, cursor `-1`). The single-pass snapshot apply depends on this.
3. Only the shard's consumer thread writes book state, levels and context.
4. An accepted request gets **exactly one** outcome event. `BatchOutcome` enforces this, so the
   fetcher is not trusted to. Losing one would strand the book in `RECOVERING`.
5. Each sequence-field parser fails loudly (throws → resync) if its fields are missing. It never
   drifts silently.

### 7.3 Traces

- **Cold start**: first diff → accepted → `RECOVERING`, buffering. The snapshot lands, its levels
  load, then the buffer drains (stale frames are `IGNORE`d, the first in-range one is the sync
  point) → `SYNCED`.
- **Cold start, queue busy**: refused → frame dropped, still `PENDING`. The next diff asks again.
  This is most books' path for the first minutes.
- **Snapshot fails** (HTTP error, timeout, budget skip, cooldown skip) → `REST_FAILED` → `PENDING`.
  The next diff re-asks, refused while the fetcher is not accepting.
- **Gap while `SYNCED`** → `DE_SYNCED` → `recover()`: levels cleared, re-queued, buffering again.
- **Late snapshot on a `SYNCED` book** → dropped by the state guard.

---

## 8. Binance adapter — `adapter/binance/`

### 8.1 Sync

| File | Role |
|---|---|
| `BinanceDepthSyncStrategy` | abstract base: dispatch, parsing, buffering, snapshot, recovery |
| `BinanceSpotSyncStrategy` / `BinanceFuturesSyncStrategy` | `check()` only |
| `BinanceSyncContext` | `diffBuffer` (≤500), `lastUpdateId` (-1 = none), `syncPointFound` (futures) |

**Single pass.** `check(JsonParser, ctx, logName)` is handed the parser on the diff's
`START_OBJECT`. It reads only `U` / `u` (spot) or `U` / `u` / `pu` (futures) and **must stop before
`b` / `a` without consuming `END_OBJECT`**. `applyDiff` resumes from there. This relies on Binance
sending the sequence fields first. An over-consuming `check()` would leave the book silently frozen
while it still reports `SYNCED`, and a test pins this. Prices and quantities are JSON strings, parsed
from the parser's char buffer with `JavaDoubleParser`. The clock is read once per message.

**Spot `check()`**: one predicate both finds the sync point and validates every frame after it.
```
u < lastUpdateId          → IGNORE
U <= lastUpdateId + 1     → OK, lastUpdateId = u
otherwise                 → DE_SYNCED
```
The strict `<` deliberately contradicts Binance's docs, which say discard `u <= lastUpdateId`. The
snapshot id very often equals a buffered `u`, and under the documented rule spot books never sync
(decision 8).

**Futures `check()`**: two regimes.
```
hunting (!syncPointFound):  u < lastUpdateId → IGNORE
                            U <= lastUpdateId → OK, lastUpdateId = u, syncPointFound = true
                            otherwise → DE_SYNCED
locked:                     pu == lastUpdateId → OK, lastUpdateId = u
                            otherwise → DE_SYNCED
```
Hunting follows the docs exactly (`lastUpdateId ∈ [U, u]`, no `+1`). A frame with
`U == lastUpdateId + 1` is rejected and costs a rare wasted resync. `u` advances the cursor in both
regimes.

**Snapshot**: `{"lastUpdateId":…,"bids":[…],"asks":[…]}`, streamed straight into the book.

### 8.2 Recovery

`BinanceSnapshotFetcher`, one per venue, is the only component that tracks Binance weight:

- Its `WeightGuard` is fed by `x-mbx-used-weight-1m` + `Date` from its own depth responses,
  successes and errors alike (`ExchangeApiException` carries headers). Out-of-order readings
  within a server minute are discarded.
- **The window is measured in elapsed time, not wall-clock time.** It counts as rolled once the
  local time since receipt covers what was left of that response's server minute. Comparing local
  `now` with the server boundary failed live with 2.3s of clock skew. `WeightGuardTest` pins the
  skewed cases.
- `fetchAll` clamps the batch to what the remaining budget affords (the rest are failed at once)
  and sends the affordable part **in parallel**.
- A 429 or 418 bans the fetcher for `Retry-After`. Without the header, a 429 bans until the next
  server minute and a 418 for `ban-fallback`. Bans only extend.
- `isAcceptingRequests()` = not banned ∧ remaining ≥ one request's weight (volatile reads).

Pricing lives in `BinanceSnapshotProperties` (`venues.<market>.snapshot`): `depth-limit` ∈
{100, 500, 1000}, `weight-limit-per-minute`, `weight-reserve` (left for discovery calls) and
`ban-fallback`. Costs come from `BinanceDepthLimit` (1000 levels: spot 50, futures 20). Queue shape:
`binance.snapshot-queue` (`max-batch-size: 10`, `flush-interval: PT0.25S`, `batch-timeout: PT30S`).
The ramp is weight-limited, not queue-limited.

### 8.3 Discovery and transport

- **`BinanceInstrumentSource`** is one source for both venues, because spot inclusion needs the
  futures list. `futures = TRADING ∧ PERPETUAL ∧ quote USDT`; `spot = TRADING ∧ quote USDT ∧ symbol ∈
  futures`. Both `exchangeInfo` calls run concurrently (`Mono.zip`).
- **`BinanceRestClient`** serves both venues, parameterized by `BinancePaths` (`/api/v3/*` vs
  `/fapi/v1/*`). No filters.
- **`BinanceStreamProtocol`** (one instance per venue) subscribes with `{"method":"SUBSCRIBE",…}`
  (topic `{symbol}@depth` / `{symbol}@depth@500ms`, lower-cased, 400 per frame). It routes on the
  `"s"` field and ignores `{"result":…}` acks. `{"error":…}` frames log a WARN. Heartbeat is
  `ProtocolPing(120s)`.

---

## 9. MEXC futures adapter — `adapter/mexc/`

Background measurements are in `external-docs/mexc/`: API contracts, versioning, rate limits and WS
limits. Where the empirical files disagree with the documentation-derived ones, the empirical files
win.

### 9.1 Sync — `MexcFuturesSyncStrategy` + `MexcSyncContext`

The dispatch, recovery and buffering are §7 exactly. `MexcSyncContext` holds `diffBuffer` (≤500)
and `lastVersion` (-1 = none). Three things differ from Binance:

- **Sequence rule on `begin` / `end`, not `version`.** MEXC documents `version == previous + 1`, which
  is false: pushes are aggregated. Every push carries undocumented, contiguous `begin` / `end`
  fields (`external-docs/mexc/mexc-depth-versioning-empirical.md`). That makes the stream
  Binance-spot-shaped:
  ```
  end < lastVersion          → IGNORE
  begin <= lastVersion + 1   → OK, lastVersion = end
  otherwise                  → DE_SYNCED
  ```
  The snapshot `version` is on the same counter, but about a third of snapshots land inside a
  push's range, which the predicate accepts. A push without `begin` / `end` throws → resync.
- **Sequence fields come after the levels**, as
  `{"symbol":…,"data":{"asks","bids","end","begin","version"},"channel":"push.depth","ts"}`. So
  `check()` finds `begin` / `end` with `lastIndexOf` on the raw frame and parses them by hand. Only
  an `OK` push is then streamed through `JsonParser`. This is independent of field order.
- **Levels are JSON numbers, in contracts.** Rows are `[price, vol, orderCount]`. `vol ×
  quantityMultiplier` (contract size) goes to `applyLevel`, and `orderCount` is skipped.

Pushes and snapshot bodies share one walk: find the top-level `data` object, stream `asks` / `bids`,
read `version`. A body with no `data.version` (including a throttled `success:false`) returns -1 →
resync.

### 9.2 Recovery — `MexcSnapshotFetcher`

MEXC's limit is ~10 requests per 2s **per IP, across all symbols and both hosts**. `limit` 100–1500
all cost the same, and `/contract/detail` is not counted.

- **Classify before reporting.** MEXC throttles with an HTTP 200, so `classify(body)` reads only the
  envelope's `success` / `code` and returns as soon as `success:true`:

  | Response | Report | Cooldown |
  |---|---|---|
  | 200, `success:true` | delivered | — |
  | 200, `success:false, code:510` | failed | `throttle-cooldown` (3s) |
  | 200, other `success:false` / unreadable body | failed, WARN | — |
  | 403 (Akamai WAF, HTML) or 429 | failed | `waf-cooldown` (90s) |
  | timeout / connection error / other status | failed | — |

- **One persistent send clock.** Each slot reserves `nextSendAt` and advances it by
  `request-interval` (250ms, so 4 req/s, and never more than 8 in any 2s window). The send waits on
  `Mono.delay` on the Reactor parallel scheduler, never sleeping the queue thread. Sends are merged,
  so their spacing does not depend on response latency. The clock persists across batches.
- **Cooldown is checked at send time**, so a 510 or 403 mid-batch fails the rest of that batch
  without sending. Cooldowns only extend. `isAcceptingRequests()` = `now >= cooldownUntil`. A
  cooldown's start (or a throttle-to-WAF escalation) logs a WARN, and repeats log at debug.
- **Fail-fast in `MexcAdapterConfig`**: `batch-timeout` must exceed `max-batch-size ×
  request-interval + rest.response-timeout` (12 × 0.25s + 10s = 13s; configured 20s).

Config: `mexc.snapshot-queue` (`max-batch-size: 12`, `flush-interval: PT0.25S`,
`batch-timeout: PT20S`). `MexcSnapshotProperties` (`venues.FUTURES.snapshot`) holds
`depth-limit: 1500` (server cap, validated 1–1500), `request-interval: PT0.25S`,
`throttle-cooldown: PT3S` and `waf-cooldown: PT90S`. Here `max-batch-size` bounds only how many books
buffer at once; the fetcher sets the rate. Measured cold start: ~3m25s for 678 books, about 10% over
the ideal because batches do not overlap.

### 9.3 Discovery and transport

- **`MexcInstrumentSource`** reads `GET /api/v1/contract/detail` (~2.3 MB, hence
  `codec-buffer-size-mb: 8`). Its filter is `quoteCoin USDT ∧ futureType 1 ∧ state 0 ∧ apiAllowed ∧
  not TradFi`, where TradFi is `conceptPlate ∋ mc-trade-zone-tradfi ∨ symbol *STOCK_USDT`. MEXC's
  TradFi sector tag covers stocks, ETFs, indices, commodities and forex (~465 contracts, including
  names without the `STOCK` suffix like `XAU_USDT`, `USOIL_USDT`, `NVIDIA_USDT`); the suffix catches the odd
  untagged tokenized stock.
  `BTC_USDT` maps to `base BTC`, `quote USDT`, so `symbol = BTCUSDT` and user rules apply unchanged.
  `contractSize` becomes `quantityMultiplier`. A row with a missing or bad `contractSize` is skipped
  with a WARN rather than failing the refresh.
- **`MexcFuturesRestClient`** is futures-only. MEXC spot is a different API (no envelope, other
  DTOs) and will get its own client. `contractDetail()` unwraps `{success, code, data}`:
  `success:false` → `MexcApiException`, non-2xx → `ExchangeApiException`. `depth(symbol, limit)`
  returns the **raw body**, envelope included, for the fetcher to classify.
- **`MexcFuturesStreamProtocol`** (`wss://contract.mexc.com/edge`) sends one
  `{"method":"sub.depth","param":{"symbol":…}}` per instrument. Its constructor rejects any
  `subscribe-chunk-size` other than 1 and any `stream-topic` other than `{symbol}`. Delivered pushes
  start `{"symbol":"…` (channel last), so the symbol is read at a fixed offset. Frames starting
  `{"channel":"` are control frames: a `rs.sub.depth` ack (no symbol echoed, debug), `pong`, or
  `rs.error` (WARN). A depth push in the documented channel-first order is still routed. Heartbeat
  is `TextPing(15s, {"method":"ping"})`; MEXC drops a connection after 60s without one. 300 streams
  per connection (no server cap observed) → 3 connections, so one dropped socket resyncs ~300 books.

---

## 10. Configuration

| Key | Bound by | Holds |
|---|---|---|
| `screener.exchanges.<exchange>.enabled` | `ExchangesProperties` (core) | venue on/off; `isEnabled(venue)` gates discovery, streaming and the registry checks |
| `screener.exchanges.<exchange>.snapshot-queue` | core | `max-batch-size`, `flush-interval`, `batch-timeout`; required only when an adapter creates a queue |
| `screener.exchanges.<exchange>.venues.<MARKET>.rest` | core | `base-url`, `codec-buffer-size-mb`, `connect-timeout`, `response-timeout` |
| `screener.exchanges.<exchange>.venues.<MARKET>.*` | core | `stream-url`, `stream-topic` (`{symbol}` template), `max-streams-per-connection`, `min-/max-connections`, `subscribe-chunk-size`, `heartbeat-interval-seconds` |
| `screener.exchanges.<exchange>.venues.<MARKET>.snapshot` | the adapter (`BinanceSnapshotProperties`, `MexcSnapshotProperties`) | fetcher pricing / pacing; core ignores this key and the adapter ignores core's |
| `screener.discovery.source-timeout`, `.excluded-symbols` | `DiscoveryProperties` | per-source fetch deadline; the exchange-agnostic exclusion list |
| `screener.ticker.refresh-interval` | `@Scheduled` | universe refresh delay (PT4H) |
| `screener.disruptor.shard-count`, `.ring-buffer-size` | `DisruptorProperties` | 2 shards (power of two), 65536 slots |
| `screener.orderbook.price-filter-threshold` | `OrderbookProperties` | 0.1; not yet per venue |
| `screener.websocket.reconnect-*` | `WebSocketProperties` | reconnect backoff; not yet per venue |

`application-local.yml` is gitignored, so a change made only there is never committed.

---

## 11. Adding an exchange

**Needs no core edit:** a new `adapter/<name>/` package with one `@Configuration`, plus a YAML block,
a `Venue` constant (and an `Exchange` constant). Through that config the adapter contributes:

| Seam | What the adapter supplies |
|---|---|
| Discovery | an `InstrumentSource` bean with hardcoded eligibility; `quantityMultiplier` if the wire is not base asset |
| Transport | a `VenueStreamBinding` → `StreamProtocol` (frames, routing, heartbeat kind); core owns connections, chunking, reconnect |
| Sync | a `VenueStrategyBinding` → `DepthSyncStrategy` + its `BookSyncContext`, following §7 |
| Recovery | REST: a `SnapshotFetcher` handed to `SnapshotQueueFactory`, plus a `snapshot-queue` block. Anything else: its own `RecoverySink` |
| Config | `venues.<MARKET>` transport keys, plus any adapter-bound `snapshot` keys |

Add the `Venue` constant together with its adapter. An enabled venue with no stream binding fails
startup, and an allocated book with no strategy throws.

**Not yet exercised:**
- **In-stream snapshot / resubscribe-to-recover** (Bybit's model): the SPI allows it (a custom
  `RecoverySink`, `EventType` as provenance), but no adapter has done it.
- **Binary frames** (MEXC spot's Protobuf): the pipeline is text end to end. `DepthEvent.rawJson` is a
  `String`, `StreamProtocol.route` takes a `String`, and `StreamConnection.onMessage(ByteBuffer)`
  only warns. This is the first SPI change MEXC spot needs.
- **A rate limit shared by two venues of one exchange**: each fetcher paces itself. If MEXC spot
  depth shares the futures window, the fetchers would need a shared per-exchange budget.

---

## 12. Known gaps

- **No startup retry for discovery.** If a source fails at startup, its venues wait for the next
  refresh (4h). Fix: retry on a short interval while any enabled venue has never had a successful
  fetch.
- **No dynamic subscribe/unsubscribe.** A refresh updates the registry and allocates slots, but
  live subscriptions keep the startup universe. Fixing it needs the reverse
  `id → (connection, topic)` direction.
- **No reset lane.** `BookSlot` has no `resetRequested` flag, so nothing outside the shard thread
  can invalidate a book. That rules out handling a dropped frame, a reconnect (books stay `SYNCED`
  across one and rely on the next gap check), a staleness timeout or an unsubscribe.
- **Blocking ingress.** `WS_MSG` should move to `tryNext()` and set the reset flag on failure, so a
  slow shard cannot stall a connection. REST events keep the blocking claim. This is coupled with
  the reset lane.
- **No staleness watchdog.** A subscription that silently stops leaves its book `SYNCED` and
  frozen.
- **No snapshot epoch.** A stale `REST_MSG` / `REST_FAILED` still in the ring can reach a book that
  has since recovered and re-queued. It self-corrects through the buffer replay's sequence check.
- **No per-instrument snapshot cooldown.** A symbol whose snapshot fails persistently re-asks on
  every diff. Add one only if the failure counter shows it.
- **Health is a log, not a surface.** Missing: connection up/down and reconnect counts, batch
  latency, dropped-event counters, MEXC sub-ack counting. None of it is queryable.
- **No read-side storage seam.** `OrderBookClassifier` reads `getBids()` / `getAsks()` directly, so
  replacing `TreeMap<Double, …>` with primitive arrays touches several classes.
- **Config not fully per venue**: `price-filter-threshold` and reconnect backoff (§10).
- **Contract size fixed at first registration** until restart (§3.1).
- **`/api/monitoring/orderbook` is abandoned**: it returns a fixed string. Nothing reads a live book's
  full contents from another thread (decision 9).

---

## 13. Decisions

Settled; the reasoning is kept so they are not relitigated.

1. **Venue, not exchange, is the adapter unit.** One set of beans per venue, even when two venues
   share a class.
2. **Identity is a dense `int`**; `(venue, nativeSymbol)` is the durable form. `Market`, not `Venue`,
   stays in the DB and the public API.
3. **One SPI entry point.** There is no snapshot/delta split in the interface, and `EventType` carries
   provenance only.
4. **No normalized cross-exchange DTO.** Strategies parse and apply in one streaming pass.
5. **Sync state lives in the opaque `BookSyncContext`**, which has no methods. Reset is the
   adapter's concern until a core caller (the reset lane) exists.
6. **Recovery is a per-venue `RecoverySink` returning `boolean`.** Refusal bounds how many books
   buffer at once, and it is the only back-off.
7. **Adapters register through binding beans, never `@Component` scanning.** Core names no adapter
   class, and a duplicate or missing binding fails at startup.
8. **Strict `<` for `IGNORE`** on Binance spot (`u < lastUpdateId`) and MEXC (`end < lastVersion`),
   contrary to Binance's docs. The documented `<=` stops spot books from ever syncing, and
   re-applying absolute quantities is harmless.
9. **No endpoint reads a full order book.** Observability comes from aggregate counters, not level
   dumps.
10. **User rules are exchange-independent**, keyed `(symbol, market)` with `symbol = base + quote`.
    One rule applies on every exchange. No migration was needed: for Binance,
    `base + quote == nativeSymbol`.
11. **Classification state and feed entries are keyed by `feedKey`** (`EXCHANGE:MARKET:SYMBOL`), so
    two exchanges' books never share state.
12. **String keys, not dense arrays,** in the classifier and feed stores. They are precomputed once
    per instrument.
13. **The WebSocket payload carries `exchange`**, and `symbol` is the normalized form. Clients key
    on `(exchange, market, symbol)`.
14. **Snapshot requests are never retried or delayed by core.** A failure becomes `REST_FAILED` →
    `PENDING`.
15. **Every book state change happens on the shard thread**, including a snapshot failure, which is
    a ring event rather than an HTTP-thread write.
16. **No core request budget.** Core queues; each venue's fetcher prices and paces (Binance weight,
    MEXC fixed interval). Discovery traffic is covered by a reserve (Binance) or not counted (MEXC).
17. **Eligibility filters are hardcoded per adapter**, since they define what the pipeline can
    handle. There is no `discovery` block per exchange.
18. **One exclusion list for every exchange**, in `base + quote` form, applied by core.
19. **Books hold base-asset quantity.** Venue units are converted at parse time through
    `quantityMultiplier`, so the classifier stays venue-agnostic.
20. **REST clients are per API, not per exchange.** Binance spot and futures are one API with two
    prefixes; MEXC spot and futures are two APIs.
21. **MEXC snapshots are paced at a fixed 4 req/s**, with one persistent send clock and the cooldown
    checked at send time. Probing upward would gain ~10% of cold start, while one trip costs a
    failed slot plus a 3s pause.
22. **MEXC TradFi perpetuals are excluded.** Stocks, ETFs, indices, commodities and forex are thin
    trackers of traditional markets that go quiet out of trading hours. Binance's equivalents are
    `TRADIFI_PERPETUAL`, already outside its `PERPETUAL` filter.

---

## 14. Tests

Test packages mirror the main ones (package-private access). All live under
`src/test/java/dev/abu/screener_backend/marketdata/`:

| Package | Covers |
|---|---|
| root | `InstrumentTest`, `InstrumentRegistryTest`, `InstrumentUniverseServiceTest` (claims, isolation, timeouts, exclusion, id order) |
| `spi/` | `StreamProtocolRegistryTest`; `FakeRecoverySink`, shared by both adapters' sync suites |
| `core/book/` | `OrderBookTest` (venue-free: levels, clear, distance sweep) |
| `core/recovery/` | `SnapshotRequestQueueTest` (accept/refuse, batching, one outcome per slot, timeout) |
| `core/stream/` | `StreamConnectionTest`, `StreamManagerTest`, `SubscriptionIndexTest` |
| `adapter/binance/` | `BinanceDepthSyncStrategyTest` (+ `SyncTestSupport`), `BinanceSnapshotFetcherTest`, `WeightGuardTest`, `BinanceStreamProtocolTest`, `BinanceInstrumentSourceTest`, `BinanceRestClientTest`, `BinanceSnapshotPropertiesTest`, `BinanceAdapterConfigTest` |
| `adapter/mexc/` | `MexcFuturesSyncStrategyTest` (+ `MexcSyncTestSupport`), `MexcSnapshotFetcherTest` (virtual time via `ManualScheduler`), `MexcFuturesStreamProtocolTest`, `MexcInstrumentSourceTest`, `MexcFuturesRestClientTest`, `MexcSnapshotPropertiesTest`, `MexcAdapterConfigTest` |

The sync suites drive real exchange-shaped JSON, in the delivered field order, through the real
`JsonParser` and the real `onEvent`. The harness builds a `DepthEvent` and has no dispatch logic of
its own. Tests that load `application-local.yml` skip when it is absent.
