# Local OrderBook Lifecycle

## Overview

This document covers how the screener discovers which Binance symbols to track, connects to
Binance's WebSocket streams, routes messages through the Disruptor pipeline, and keeps each
`(symbol, market)` local orderbook in perfect sync with Binance's authoritative state.

**Scope boundary**: this document stops at a `SYNCED`, up-to-date `OrderBook` (bids/asks
`TreeMap`s). How those books are classified into tiers, fed to users, or broadcast over
WebSocket is out of scope — see the `analysis/` and `feed/` packages for that.

**Hot path warning**: everything described here — WebSocket message handling, Disruptor
`onEvent`, and orderbook mutation — runs on a small, fixed number of threads processing the
full Binance depth firehose (hundreds of thousands of diffs/sec at target scale). Code in this
path must not allocate per-message where avoidable, must not use `BigDecimal`, must parse JSON
via streaming (`JsonParser`), and must never log at INFO+ per message. See `CLAUDE.md` for the
full hot-path rules.

---

## 1. Ticker Discovery and Refresh

Files: `ticker/TickerService.java`, `TickerRegistry.java`, `TickerRefreshScheduler.java`, `Ticker.java`, `TickersRefreshedEvent.java`.

`TickerService.refreshTickers()` fetches `GET /api/v3/exchangeInfo` (spot) and
`GET /fapi/v1/exchangeInfo` (futures) concurrently via `Mono.zip`, blocking up to 30s.

**Inclusion rules** (`buildTickerMap`):
- Symbol must be USDT-quoted with an **active** (`TRADING`) USDT `PERPETUAL` futures contract —
  futures presence is mandatory.
- If the symbol also has an active USDT spot market, it's flagged `Ticker.hasSpot() = true`.
  Spot-only symbols (no futures contract) are excluded entirely.
- A small hardcoded `EXCLUDED_SYMBOLS` set filters out stablecoin/gold pairs (`USDCUSDT`,
  `FDUSDUSDT`, `DAIUSDT`, `PYUSDUSDT`, `USD1USDT`, `XAUTUSDT`, `PAXGUSDT`).

**Failure behavior**: if either REST call fails, the error is logged and the existing
`TickerRegistry` is left untouched — the app never crashes on a bad refresh.

**Registry**: `TickerRegistry` holds an `AtomicReference<Map<String, Ticker>>`, swapped
atomically via `replace()` — lock-free reads for consumers.

**Scheduling**: `TickerRefreshScheduler` calls `refreshTickers()` on
`@Scheduled(fixedDelayString = "${screener.ticker.refresh-interval}")`
(`screener.ticker.refresh-interval: PT4H`). `fixedDelay` (not `fixedRate`) is deliberate, so a
slow refresh can't overlap with the next tick.

**Startup propagation**: a successful refresh publishes `TickersRefreshedEvent`.
`BinanceWebSocketManager` listens for it: on the **first** event it starts the spot/futures
WebSocket connection pools (§2). On every subsequent event (i.e. the 4-hourly refresh finding
new/delisted symbols) it currently only logs that dynamic re-subscription isn't implemented —
**live WebSocket subscriptions are not updated when the ticker list changes after startup**,
only the registry is. Worth knowing if a symbol gets delisted or a new one is listed mid-run.

---

## 2. WebSocket Connection Pools

Files: `binance/websocket/{BinanceWebSocketManager,BinanceConnectionPool,BinanceStreamClient,Market}.java`, config: `WebSocketProperties` (`screener.websocket.*`).

Spot and futures each get an independent `BinanceConnectionPool`, built once at startup from
the first `TickersRefreshedEvent`:
- **Spot pool**: only tickers with `hasSpot() = true`, connecting to `spot-stream-url`
  (`wss://stream.binance.com/ws`) across `connection-count-spot` connections (default `2`).
- **Futures pool**: every discovered ticker, connecting to `futures-stream-url`
  (`wss://fstream.binance.com/ws`) across `connection-count-futures` connections (default `3`).

`BinanceConnectionPool.start()` splits the ticker list into `connectionCount` contiguous
batches (`i*size/count .. (i+1)*size/count`) and opens one `BinanceStreamClient` per batch,
each pointed at the same base URL (Binance's combined-stream path, not the `?streams=` query
form). There's no per-connection stream-count cap enforced in code — Binance's own connection
limits are respected purely by choosing `connection-count-*` large enough to keep each
connection's stream count under Binance's limit, not by a coded guard.

**Subscribing** (`BinanceStreamClient.onOpen`): the client's symbol batch is chunked into
groups of `subscribe-chunk-size` (default `400`), one `SUBSCRIBE` frame sent per chunk. Each
symbol is subscribed with a market-specific depth stream suffix (`Market.streamSuffix()`):
spot `@depth` (1000ms), futures `@depth@500ms`.

**Message dispatch** (`onMessage`): avoids full JSON parsing on the hot path — a single
`charAt(2) == 'r'` check distinguishes a `SUBSCRIBE` ack (`{"result":...}`) from a depth event
(`{"e":...}`), and the symbol is pulled out via a direct substring scan on `"s":"..."`
(interned, since the string repeats constantly) rather than deserializing the whole message.
Depth events are forwarded to `RawDepthMessageHandler.handle(symbol, market, rawJson)` — the
raw JSON string is not parsed further here; that happens later, in the Disruptor consumer.

**Reconnect**: on `onClose` (unless shutting down), the client schedules a reconnect with
exponential backoff: `reconnectInitialDelayMs * 2^min(attempts, 8)`, capped at
`reconnectMaxDelayMs` (default `100ms` → `30000ms`), on a per-market single-thread
`ScheduledExecutorService`.

**Heartbeat**: `setConnectionLostTimeout(0)` disables the underlying library's own
lost-connection detection; instead a periodic WebSocket ping is sent every
`heartbeat-interval-seconds` (default `120`) to stop Binance from idle-closing the connection
(code 1006).

---

## 3. Disruptor Pipeline

Files: `binance/disruptor/{DisruptorShardManager,DepthEventHandler,DisruptorDepthMessageHandler,DepthEvent,DepthEventFactory,EventType}.java`, config: `DisruptorProperties` (`screener.disruptor.*`).

`DisruptorShardManager` (`@PostConstruct`) creates `shard-count` (default `2`) independent
`Disruptor<DepthEvent>` instances, each:
- `ring-buffer-size` slots (default `65536`, must be a power of 2),
- `ProducerType.MULTI` (multiple WebSocket client threads publish into the same ring buffer),
- `BlockingWaitStrategy`,
- one dedicated consumer thread (`disruptor-shard-N`) running a single `DepthEventHandler`.

**Routing** (`getRingBuffer(symbol)`): `ringBuffers[Math.abs(symbol.hashCode()) % shardCount]`.
A given symbol always maps to the same shard, so all its diffs and snapshots are handled by
exactly one consumer thread — this is what lets `OrderBook`'s `TreeMap`s go unsynchronized.

**Producers** (two call sites publish `DepthEvent`s into a ring buffer):
1. `DisruptorDepthMessageHandler.handle()` — every WebSocket depth message becomes an
   `EventType.DIFF` event.
2. `SnapshotFetchQueue.publishSnapshotEvent()` — every completed REST snapshot response becomes
   an `EventType.SNAPSHOT` event, published to the *same* symbol's ring buffer.

Because both flow through the same shard, a snapshot and the diffs surrounding it are never
applied concurrently — there is no locking anywhere in `OrderBook`.

**Consumer** (`DepthEventHandler.onEvent`): calls `OrderBookProcessor.process(event)` to
apply the event to the orderbook (this document's concern), then — if a book resulted —
hands off to `OrderBookClassifier.process(ob)` (out of scope here), then clears the event
object (`event.clear()`, reused ring-buffer slot — no per-event allocation).

**`OrderBookProcessor.process()`** (`binance/orderbook/OrderBookProcessor.java`) — the routing
layer between Disruptor events and `OrderBook`:
- `DIFF` events: lazily create the `OrderBook` on first arrival (`OrderBookStore.getOrCreate`).
- `SNAPSHOT` events: the book must already exist (`OrderBookStore.get`); if not (e.g. a stray
  late response), the event is dropped.
- Dispatches to `ob.applySnapshot()` or `ob.onDiff()` per event type.
- If the result is `NEEDS_SNAPSHOT` or `NEEDS_RESYNC`, calls `snapshotFetchQueue.enqueue(ob)`
  and (if the queue accepted it) `ob.markSnapshotRequested()`. **Only `NEEDS_SNAPSHOT` replays
  the triggering diff** into `ob.onDiff()` afterward, so it isn't lost — `NEEDS_RESYNC` does
  not replay, since a resync means everything buffered so far is already known-bad and the
  book must wait for the next fresh diff to re-trigger buffering.
- If `enqueue()` fails (queue at capacity), the book is left in its current state (still
  `PENDING`) and the diff is effectively dropped for now.

---

## 4. Why We Can't Snapshot All Orderbooks at Once

Synchronization requires a REST snapshot from Binance (`GET /api/v3/depth?limit=1000` for spot,
`GET /fapi/v1/depth?limit=1000` for futures). Each snapshot call costs API weight:

| Market  | Snapshot weight cost | Hard limit/min | Guard threshold |
|---------|----------------------|----------------|-----------------|
| Spot    | 50 weight            | 6 000          | 5 800           |
| Futures | 20 weight            | 2 400          | 2 200           |

With ~500 futures + additional spot tickers, firing all snapshot requests simultaneously would
consume thousands of weight units instantly and risk an IP ban (HTTP 429 / 418).

Additionally, only orderbooks in `SNAPSHOT_REQUESTED` state buffer diffs. If all orderbooks
buffered from startup, each would accumulate diffs before a snapshot ever arrived — exhausting
heap memory quickly at scale.

---

## 5. State Machine

Three states (`OrderBookState`):

| State                | Diff handling          | Snapshot handling |
|-----------------------|------------------------|--------------------|
| `PENDING`             | Dropped                | N/A                |
| `SNAPSHOT_REQUESTED`  | Buffered in `diffBuffer` | Triggers sync    |
| `SYNCED`              | Applied live            | N/A (ignored)      |

Re-sync failures (sequence gaps, parse errors, empty buffer after snapshot) call `resync()`,
which clears the diff buffer and returns to `PENDING`. The orderbook then waits for the next
diff to re-trigger the enqueue flow.

---

## 6. Snapshot Fetch Queue and Rate Limiting

`SnapshotFetchQueue` maintains two bounded `ConcurrentHashMap`s — one for spot, one for futures
— each capped at a configurable size (`spot-snapshot-queue-size: 10`,
`futures-snapshot-queue-size: 10` in `application.yml`; these two are bound via `@Value`
directly on the `SnapshotFetchQueue` constructor, not part of the `OrderbookProperties` record).

A `@Scheduled` method fires every `spot`/`futures-snapshot-dispatch-rate-ms` (`6000` ms each)
and dispatches HTTP snapshot requests for all entries currently in the queue **concurrently**.
At each firing, at most 10 requests go out per market — a maximum of ~100 requests/minute per
market, well within Binance limits.

**Why bounded queue size matters for memory**: only orderbooks that have been enqueued
transition to `SNAPSHOT_REQUESTED` and start buffering diffs. The rest stay `PENDING` and drop
all diffs. This ensures at most 10 diff buffers are active per market at any moment, bounding
memory usage during the multi-minute startup ramp.

**Why bounded queue size also protects against weight-limit delays**: `WeightGuard` enforces
Binance weight limits by delaying a request until the next wall-clock minute boundary (plus a
1s buffer) when the budget is nearly exhausted. If the queue were large and a weight-limit
delay triggered mid-dispatch, all queued requests would be held for up to a minute before
completing — during which every one of those orderbooks keeps buffering diffs. Keeping the
queue small (10) bounds how many books can be caught in a delayed-dispatch scenario at once.

**Enqueue flow** (from `OrderBookProcessor`, detailed in §3):
1. Consumer thread receives a diff for a `PENDING` orderbook.
2. `onDiff()` returns `NEEDS_SNAPSHOT`.
3. Processor calls `snapshotFetchQueue.enqueue(ob)`.
4. If enqueue succeeds: `ob.markSnapshotRequested()` (→ `SNAPSHOT_REQUESTED`), then **replays
   the same diff** into `ob.onDiff()` so that very first diff is buffered.
5. If enqueue fails (queue full): orderbook stays `PENDING`, diff is dropped.

On a failed snapshot HTTP response, the orderbook is re-enqueued (retried on the next dispatch
cycle) rather than forced to resync.

---

## 7. The 5-Second Delay

`SnapshotFetchQueue.dispatchSpot()` / `dispatchFutures()` apply
`.delayElement(Duration.ofSeconds(5))` after the HTTP response arrives, before publishing the
snapshot event to the Disruptor ring buffer.

**Why**: the REST request itself completes in ~50–200 ms. If the snapshot were processed
immediately, `diffBuffer` might contain only a handful of events, and all of their `u` values
could be less than the snapshot's `lastUpdateId` — leaving the buffer empty after the discard
step (§9), forcing an immediate re-sync. With a 5-second delay, more diffs accumulate in the
buffer before the snapshot is applied — roughly 5 more for spot (1 update/sec) and ~10 more for
futures (1 update/500ms) — making it far more likely that valid sync-point diffs are available.

---

## 8. Snapshot Publishing via Disruptor

When the WebClient Reactor thread receives the snapshot HTTP response, it does **not** write
directly to the `OrderBook`. Instead it publishes a `DepthEvent` with `type = EventType.SNAPSHOT`
to the same shard's `RingBuffer` that handles diffs for that symbol (§3).

This is critical for correctness: diffs and snapshots for the same symbol all flow through the
same single-threaded Disruptor consumer. There is no concurrency between diff application and
snapshot application for any given orderbook, and therefore no locking in `OrderBook`.

---

## 9. `applySnapshot()` — Detailed Algorithm

Called by the consumer thread when a `SNAPSHOT` event arrives for a `SNAPSHOT_REQUESTED`
orderbook (`OrderBook.applySnapshot`).

```
1. Parse snapshot JSON → snapshotId (lastUpdateId), snapshotBids[], snapshotAsks[]
   - If parse fails → resync()

2. Discard stale buffered diffs:
   while diffBuffer.peekFirst().u < snapshotId:
       diffBuffer.pollFirst()

   Note: Binance docs for SPOT say discard where u <= snapshotId, but in practice
   snapshotId equals the u of the most recent buffered event, causing the entire buffer
   to be discarded. The implementation uses strict u < snapshotId (same as futures
   docs) to keep at least one overlap event.

   If diffBuffer is empty after this step → resync()

3. Validate sync point on the first remaining diff:
   U = first buffered diff's U field (first update ID)
   u = first buffered diff's u field (last update ID)
   Condition: snapshotId must be in [U, u]
   i.e.  U <= snapshotId <= u
   If condition fails → resync()

4. Load snapshot into TreeMaps:
   bids.clear(); asks.clear()
   Insert all snapshot levels with firstSeenMillis = System.currentTimeMillis()
   (Skip levels with qty = 0)

5. Apply first buffered diff (special case, applyLevelUpdatesFirstEvent):
   Parse bid/ask levels only (no U/u/pu validation — we already validated the range)
   Apply level updates to bids/asks
   NOTE: does NOT run the price-distance filter (step in applyLiveDiff §10.4) —
   filtering only starts from the second buffered diff onward.
   Set lastUpdateId = that diff's u field

6. Apply remaining buffered diffs via applyLiveDiff() (full sequence validation
   + price filter, §10)
   If any returns non-OK → resync()

7. state = SYNCED
```

---

## 10. `applyLiveDiff()` — Detailed Algorithm

Called for every diff in `SYNCED` state and for buffered diffs (step 6 above) during snapshot
application.

```
1. Parse JSON fields via streaming JsonParser: U, u, pu, bids[], asks[]
   (only b/a/U/u/pu are extracted — never a full POJO deserialize)

2. Sequence validation, checked once the parser reaches the first b/a field
   (Binance guarantees U/u/pu precede b/a in the diff object):
   SPOT:    if U != lastUpdateId + 1  → log → resync()
   FUTURES: if pu != lastUpdateId     → log → resync()

   These checks detect missed events or duplicate delivery.
   - U (uppercase): first update ID in this diff event
   - u (lowercase): last update ID in this diff event
   - pu (futures only): the u value of the immediately preceding event
   - lastUpdateId: the u value of the previously applied event

3. Apply level updates to bids TreeMap and asks TreeMap:
   for each level in bids/asks:
     if qty == 0.0:
       map.remove(price)                         // level removed from book
     else if map.containsKey(price):
       entry.quantity = qty                      // in-place mutation, no allocation
     else:
       map.put(price, new PriceLevelEntry(qty, System.currentTimeMillis()))  // new level

4. computeDistance() — price filter + distance stamping:
   midPrice = (bids.firstKey() + asks.firstKey()) / 2.0
   lower = midPrice * (1 - filterThreshold)
   upper = midPrice * (1 + filterThreshold)
   For each remaining bid/ask entry:
     if price outside [lower, upper]: remove it
     else: entry.distance = |price - midPrice| / midPrice   (fraction, not %)

5. lastUpdateId = u
```

`filterThreshold` is `screener.orderbook.price-filter-threshold` (default `0.1`, i.e. ±10% of
mid-price), injected per-`OrderBook` at construction (`OrderBookStore.getOrCreate`) — it is
**not** a hardcoded percentage. `distance` is stored as a fraction of mid-price everywhere in
the codebase (`0.05` = 5%), the same unit the classifier and per-user rules compare against.

---

## 11. Sequence ID Fields — Reference

| Field           | Scope                | Meaning                                                  |
|------------------|----------------------|-----------------------------------------------------------|
| `lastUpdateId`   | Snapshot              | The update ID at which Binance captured the snapshot      |
| `U` (uppercase)  | Diff                  | First update ID covered by this diff batch                |
| `u` (lowercase)  | Diff                  | Last update ID covered by this diff batch                 |
| `pu`             | Diff (futures only)  | The `u` value of the immediately preceding diff event      |

**SPOT continuity rule**: each diff's `U` must equal the previous diff's `u + 1`. A gap means
one or more updates were lost.

**FUTURES continuity rule**: each diff's `pu` must equal the previous diff's `u`. Binance
explicitly provides this field to make gap detection unambiguous.

---

## 12. Diff Buffer Overflow Guard

`OrderBook.MAX_BUFFER_SIZE = 500` entries. If a `SNAPSHOT_REQUESTED` orderbook accumulates 500
diffs without a snapshot arriving (e.g. the snapshot HTTP call is stuck), the buffer is cleared
and state returns to `PENDING` (via `resync()`, result `NEEDS_RESYNC` — not replayed, see §3).
This prevents unbounded heap growth in pathological cases.

---

## 13. Price Level Lifecycle

| Event                                | Action                                              |
|----------------------------------------|------------------------------------------------------|
| New price, qty > 0                    | `map.put(price, new PriceLevelEntry(qty, now))`      |
| Existing price, qty > 0               | `entry.quantity = qty` (in-place, zero alloc)        |
| Any price, qty == 0                   | `map.remove(price)` — mandatory per Binance protocol |
| Level drifts outside `±price-filter-threshold` of mid-price | Swept by `computeDistance()` after each live diff (not the first buffered diff during resync, see §9 step 5) |
| Re-sync (`resync()` called)           | `bids.clear()`, `asks.clear()` — timestamps reset    |
| Level returns after removal           | Fresh insert with new `firstSeenMillis`              |

`PriceLevelEntry.firstSeenMillis` records when a price level first appeared in the current
continuous presence. `PriceLevelEntry.distance` (set by `computeDistance()`) is the level's
fractional distance from mid-price, consumed downstream by classification.

---

## Summary of Non-Obvious Design Decisions

1. **Ticker refresh doesn't re-subscribe live connections** — `BinanceWebSocketManager` only
   acts on the *first* `TickersRefreshedEvent` to start the pools; the 4-hourly refresh after
   that updates `TickerRegistry` but not open WebSocket subscriptions.
2. **No coded per-connection stream cap** — Binance's connection-level stream limits are
   respected by choosing `connection-count-spot`/`connection-count-futures` appropriately, not
   by a guard in `BinanceConnectionPool`.
3. **5-second snapshot delay** — prevents the buffer from being empty when the snapshot is
   applied, avoiding immediate re-sync loops.
4. **Snapshot published via Disruptor** — keeps all orderbook mutations single-threaded per
   shard; snapshot and diffs are never concurrent, so `OrderBook` needs no locks.
5. **Strict `u < snapshotId` discard (not `u <= snapshotId`)** — the spot docs say `<=` but
   this empties the buffer in practice; strict `<` retains the overlap event needed for sync.
6. **First diff special-cased in `applySnapshot`** — U/u/pu are not validated for the first
   diff because its range overlap with `snapshotId` was already verified in step 3, and the
   price filter is skipped for it (only applied from the second buffered diff onward).
7. **`NEEDS_SNAPSHOT` replays the current diff, `NEEDS_RESYNC` does not** — after transitioning
   to `SNAPSHOT_REQUESTED` from `PENDING`, the triggering diff is replayed so it isn't lost;
   after a resync, the buffer is already known-bad, so there's nothing worth replaying.
8. **Queue capacity serves two purposes** — limits how many orderbooks hold diff buffers
   simultaneously (memory), and caps the blast radius if a weight-limit delay triggers
   mid-dispatch (at most 10 buffers grow for up to a minute, not hundreds).
9. **Price filter threshold is configurable, not a hardcoded 30%** — `price-filter-threshold`
   defaults to `0.1` (±10% of mid-price) and doubles as the unit for `PriceLevelEntry.distance`.
