# Plan: Orderbook Management (Phase 3)

## Context

Phases 1 (WebSocket connections) and 2 (LMAX Disruptor pipeline) are complete. The consumer
currently counts events and drops them. Phase 3 implements the full orderbook lifecycle: snapshot
synchronization, state management, diff application, and 30% price filter. Order classification
is explicitly deferred to Phase 4.

---

## Key Design Decisions

### 1. DepthEvent Stays a Generic Envelope

`DepthEvent` gains one new field: `EventType type` (DIFF | SNAPSHOT). No snapshot-specific fields
(`lastUpdateId`, etc.) are added to the event object.

- **DIFF events**: `rawJson` contains the WebSocket depth update payload from Binance
- **SNAPSHOT events**: `rawJson` contains the REST snapshot response body (raw JSON string)
- The consumer passes `rawJson` to the OrderBook unchanged; the OrderBook owns all field extraction
  per event type

**Why not add snapshot fields to DepthEvent**: It pollutes a generic envelope with format-specific
knowledge. The OrderBook is the correct owner of all parsing logic. Keeping DepthEvent as a thin
envelope also means no wasted work — for PENDING orderbooks, the consumer checks state and returns
before touching `rawJson` at all.

### 2. Snapshot Data Flows Through the Ring Buffer

When a REST snapshot response arrives on the Reactor/WebClient thread, `SnapshotFetchQueue`
publishes a `SnapshotEvent` into the ring buffer rather than writing to the OrderBook directly.
The consumer thread processes the SnapshotEvent in sequence with diffs, making it the sole writer
to any OrderBook's `TreeMap`. No synchronization is needed on the orderbook data structures.

### 3. State Machine — Five States

| State | Meaning | On diff arrival |
|-------|---------|-----------------|
| `PENDING` | Created; never been queued | Transition to QUEUED, return NEEDS_SNAPSHOT |
| `QUEUED` | In SnapshotFetchQueue; HTTP not yet dispatched | DROP silently, return DROPPED |
| `SNAPSHOT_REQUESTED` | HTTP dispatched; buffering active | Append to per-orderbook ArrayDeque |
| `SYNCED` | Live; diffs applied in real time | Parse and apply; validate sequence |
| `STALE` | Lost sync (connection drop or pu gap) | DROP silently, return DROPPED |

**QUEUED is not in CLAUDE.md's original table — it is a critical addition.** The problem it solves:
if all ~898 orderbooks transitioned straight from PENDING to SNAPSHOT_REQUESTED on their first diff,
they would all start buffering immediately. At startup, the last-dispatched futures ticker waits
~5.5 minutes in the queue. At 10 diffs/second, its buffer would hold ~3,300 raw JSON strings —
roughly 1 MB per ticker, potentially 900 MB total across all 898 orderbooks. QUEUED prevents this:
diffs are buffered **only after** the HTTP request is actually dispatched, not when enqueued.

**SYNCING (from CLAUDE.md) is intentionally omitted.** Snapshot application — parsing the snapshot
JSON, discarding stale buffered diffs, applying the Binance sync algorithm, and transitioning to
SYNCED — all happen synchronously within a single `onEvent()` call when the SnapshotEvent is
processed by the consumer. There is no observable intermediate state; the transition from
SNAPSHOT_REQUESTED to SYNCED (or STALE on failure) is atomic within that call.

### 4. Snapshot Dispatch Trigger — Natural Flow

No explicit startup listener for snapshot fetching. The flow emerges naturally:

1. WebSocket streams open; diffs begin flowing into the ring buffer
2. First diff for each ticker reaches the consumer; OrderBook is created lazily (PENDING)
3. `onDiff()` on a PENDING orderbook transitions it to QUEUED and returns NEEDS_SNAPSHOT
4. Consumer calls `snapshotFetchQueue.enqueue(ob)`
5. SnapshotFetchQueue drains at the rate-limited pace; calls `ob.markSnapshotDispatched()` before
   firing the HTTP request
6. REST response arrives on Reactor thread; SnapshotFetchQueue publishes SnapshotEvent to ring
7. Consumer processes SnapshotEvent: applies Binance sync algorithm → SYNCED

Re-sync (STALE path) reuses the same mechanism: consumer calls `enqueue()` again and the queue
handles it identically.

### 5. Rate Limiting and Startup Sync Timing

| Market | Weight limit/min | Snapshot cost | Max dispatches/min | At 80% budget | Dispatch rate |
|--------|-----------------|---------------|--------------------|---------------|---------------|
| Spot | 6,000 | 50 weight | 120/min | 96/min | **1 per 625ms** |
| Futures | 2,400 | 20 weight | 120/min | 96/min | **1 per 625ms** |

Spot and futures use independent `@Scheduled` tasks, each at 625ms fixed rate, targeting separate
`ConcurrentLinkedQueue` instances. The 80% budget leaves headroom for ticker refresh API calls and
any other REST traffic.

**Expected startup sync time** (tickers synced concurrently across both markets):
- Spot: 367 ÷ 1.6/sec ≈ **3.8 minutes**
- Futures: 531 ÷ 1.6/sec ≈ **5.5 minutes**
- Total (parallel): **~5.5 minutes** (futures-dominated)

### 6. Per-Orderbook Buffer Size — Memory Analysis

Number of concurrently buffering orderbooks at any instant ≈ `dispatch_rate × REST_latency`:

| Scenario | REST latency | Concurrent per market | Diffs buffered per OB | Total buffer memory |
|----------|--------------|-----------------------|-----------------------|---------------------|
| Best case | 50ms | ~0 | 0–1 | ~0 KB |
| Middle | 500ms | ~1 | ~5 | ~3 KB |
| Worst case | 1,000ms | ~2 | ~10 | ~12 KB |

Memory impact is negligible in all scenarios. A safety bound of **500 entries** is set on each
per-orderbook buffer. If exceeded, log WARN and mark the orderbook STALE for re-sync — this
indicates a pathological condition (e.g., REST calls taking many seconds).

### 7. OrderBook Owns All Logic — Consumer Is a Router

The `OrderBook` owns: state transitions, Binance sync algorithm, buffer management, 30% filter,
and `TreeMap` mutation. It communicates outcomes to the consumer via a result enum. The consumer
routes events and acts on results; it contains no orderbook domain logic.

This makes `OrderBook` independently unit-testable and keeps `DepthEventHandler` minimal.

### 8. 30% Price Filter Application Points

Applied at two moments:
1. **During snapshot loading**: after populating bids/asks from the snapshot, sweep any level
   where `|price − midPrice| / midPrice > 0.30`
2. **After each SYNCED diff batch**: recalculate midPrice from updated best bid/ask, then sweep
   levels that drifted outside the 30% window

Mid-price: `(bids.firstKey() + asks.firstKey()) / 2.0`

Bids `TreeMap` uses `Comparator.reverseOrder()` so `firstKey()` returns the best bid (highest).
Asks `TreeMap` uses natural order so `firstKey()` returns the best ask (lowest).

**Rule (from CLAUDE.md)**: Recalculate midPrice **after** applying the update batch. The incoming
update may contain the new best bid/ask.

### 9. Jackson Streaming for All JSON Parsing

`JsonParser` (Jackson streaming API) is used for both diff and snapshot parsing. No intermediate
POJO objects per message on the hot path. A single shared `JsonFactory` instance is used.

---

## Component Map

```
binance/
└── orderbook/
    ├── EventType.java                ← new enum: DIFF | SNAPSHOT
    ├── OrderBookState.java           ← new enum: PENDING | QUEUED | SNAPSHOT_REQUESTED | SYNCED | STALE
    ├── OrderBookResult.java          ← new enum: OK | NEEDS_SNAPSHOT | NEEDS_RESYNC | DROPPED
    ├── PriceLevelEntry.java          ← new: mutable value object (quantity + firstSeenMillis)
    ├── OrderBook.java                ← new: full orderbook logic, owns all state transitions
    ├── OrderBookStore.java           ← new @Component: ConcurrentHashMap<key, OrderBook>
    ├── OrderBookProcessor.java       ← new @Component: event router, injected into DepthEventHandler
    └── snapshot/
        └── SnapshotFetchQueue.java   ← new @Component: rate-limited dispatch + ring buffer publish
```

Modified files:
- `binance/disruptor/DepthEvent.java` — add `EventType type`; add `clear()` method
- `binance/disruptor/DepthEventHandler.java` — delegate to `OrderBookProcessor`; remove counter
- `binance/disruptor/DisruptorShardManager.java` — inject and pass `OrderBookProcessor` to handlers

---

## Detailed Class Specifications

### `EventType`

```java
public enum EventType {
    DIFF,
    SNAPSHOT
}
```

---

### `DepthEvent` (modified)

Add field:
```java
public EventType type;
```

Add method (called by consumer at end of every `onEvent`):
```java
public void clear() {
    type    = null;
    symbol  = null;
    market  = null;
    rawJson = null;
}
```

Remove nothing else — `symbol`, `market`, `rawJson` stay as public fields.

The `eventCount` stat tracking in `DepthEventHandler` is removed; `DepthEventHandler` is now a
pure delegation shim.

---

### `OrderBookState`

```java
public enum OrderBookState {
    PENDING,             // created; snapshot never queued; diffs dropped
    QUEUED,              // in SnapshotFetchQueue; HTTP not yet dispatched; diffs dropped
    SNAPSHOT_REQUESTED,  // HTTP dispatched; diffs appended to diffBuffer
    SYNCED,              // live; diffs parsed and applied immediately
    STALE                // lost sync; re-enqueued; diffs dropped
}
```

---

### `OrderBookResult`

```java
public enum OrderBookResult {
    OK,              // event processed normally (applied, buffered, or silently dropped)
    NEEDS_SNAPSHOT,  // PENDING → QUEUED transition; consumer must call enqueue()
    NEEDS_RESYNC,    // SYNCED or SNAPSHOT_REQUESTED failure; consumer must call enqueue()
    DROPPED          // event dropped for a known, non-actionable reason
}
```

---

### `PriceLevelEntry`

Mutable value object. Updated in-place on qty changes to avoid allocation on the hot path.

```java
public class PriceLevelEntry {
    public double quantity;
    public final long firstSeenMillis;

    public PriceLevelEntry(double quantity, long firstSeenMillis) {
        this.quantity = quantity;
        this.firstSeenMillis = firstSeenMillis;
    }
}
```

`firstSeenMillis` is set on insertion and never changed. Re-added levels get a fresh timestamp.

---

### `OrderBook`

Core domain object. All methods except `markSnapshotDispatched()` are called exclusively by the
consumer thread for this orderbook's shard. `state` is `volatile` because `markSnapshotDispatched()`
is called from the `SnapshotFetchQueue` scheduler thread.

**Fields:**
```java
final String symbol;
final Market market;
volatile OrderBookState state;               // written by both consumer and SnapshotFetchQueue threads
final TreeMap<Double, PriceLevelEntry> bids; // Comparator.reverseOrder() — firstKey() = best bid
final TreeMap<Double, PriceLevelEntry> asks; // natural ascending — firstKey() = best ask
final ArrayDeque<String> diffBuffer;         // raw JSON diff strings; only populated in SNAPSHOT_REQUESTED
long lastUpdateId;                           // consumer thread only
long lastPu;                                 // futures only; consumer thread only
static final int MAX_BUFFER_SIZE = 500;
static final double FILTER_THRESHOLD = 0.30;
```

**Constructor:**
```java
public OrderBook(String symbol, Market market) {
    this.symbol  = symbol;
    this.market  = market;
    this.state   = OrderBookState.PENDING;
    this.bids    = new TreeMap<>(Comparator.reverseOrder());
    this.asks    = new TreeMap<>();
    this.diffBuffer = new ArrayDeque<>();
}
```

---

#### `markSnapshotDispatched()` — called by SnapshotFetchQueue scheduler thread

```java
public void markSnapshotDispatched() {
    this.state = OrderBookState.SNAPSHOT_REQUESTED;  // volatile write
}
```

Transitions QUEUED → SNAPSHOT_REQUESTED. Safe: `state` is volatile; the consumer reads it on the
next event and begins buffering diffs from that point.

---

#### `onDiff(String rawJson)` — called by consumer thread

```
switch (state):

  PENDING:
    state = QUEUED  // volatile write
    return NEEDS_SNAPSHOT

  QUEUED:
    return DROPPED

  STALE:
    return DROPPED

  SNAPSHOT_REQUESTED:
    if (diffBuffer.size() >= MAX_BUFFER_SIZE):
      log.warn("Diff buffer overflow for {}/{} — forcing re-sync", symbol, market)
      diffBuffer.clear()
      state = STALE
      return NEEDS_RESYNC
    diffBuffer.addLast(rawJson)
    return OK

  SYNCED:
    parse rawJson using JsonParser:
      extract long U, u
      if market == FUTURES: extract long pu
      extract bid levels: List of (double price, double qty) pairs
      extract ask levels: List of (double price, double qty) pairs

    validate U sequence:
      if (U != lastUpdateId + 1):
        log.warn("Sequence gap {}/{}: expected U={}, got U={}", symbol, market, lastUpdateId+1, U)
        diffBuffer.clear()
        state = STALE
        return NEEDS_RESYNC

    if (market == FUTURES):
      if (pu != lastUpdateId):
        log.warn("pu gap {}/{}: expected pu={}, got pu={}", symbol, market, lastUpdateId, pu)
        diffBuffer.clear()
        state = STALE
        return NEEDS_RESYNC

    applyLevelUpdates(bids, parsedBids)
    applyLevelUpdates(asks, parsedAsks)
    apply30PercentFilter()   // uses updated best bid/ask for midPrice
    lastUpdateId = u
    if (market == FUTURES): lastPu = u
    return OK
```

---

#### `applySnapshot(String rawJson)` — called by consumer thread when SnapshotEvent arrives

```
parse rawJson using JsonParser:
  extract long snapshotLastUpdateId
  extract bid levels: List of (double price, double qty)
  extract ask levels: List of (double price, double qty)

// --- Binance sync algorithm ---

// Step 1: Discard buffered diffs with u < snapshotLastUpdateId
while (!diffBuffer.isEmpty()):
  peek first entry; parse only the u field
  if (u < snapshotLastUpdateId): diffBuffer.pollFirst()
  else: break

// Step 2: Validate sync entry point
// First remaining diff must satisfy: U <= snapshotLastUpdateId + 1 <= u
if (diffBuffer.isEmpty()):
  log.warn("Empty buffer after snapshot for {}/{} — re-syncing", symbol, market)
  state = STALE
  return NEEDS_RESYNC

peek firstDiff; parse U and u
if (firstDiff.U > snapshotLastUpdateId + 1):
  log.warn("No valid sync point for {}/{} — re-syncing", symbol, market)
  diffBuffer.clear()
  state = STALE
  return NEEDS_RESYNC

// Step 3: Load snapshot into TreeMaps
bids.clear()
asks.clear()
for each (price, qty) in snapshotBids: if (qty > 0) bids.put(price, new PriceLevelEntry(qty, now))
for each (price, qty) in snapshotAsks: if (qty > 0) asks.put(price, new PriceLevelEntry(qty, now))
apply30PercentFilter()   // snapshot may include levels outside 30% — filter now

lastUpdateId = snapshotLastUpdateId
if (market == FUTURES): lastPu = snapshotLastUpdateId

// Step 4: Apply qualifying buffered diffs in sequence
for each bufferedDiffJson in diffBuffer:
  parse U, u, pu (futures), bid levels, ask levels

  if (market == FUTURES and pu != lastUpdateId):
    log.warn("pu gap in buffered diff for {}/{} — re-syncing", symbol, market)
    bids.clear(); asks.clear(); diffBuffer.clear()
    state = STALE
    return NEEDS_RESYNC

  applyLevelUpdates(bids, parsedBids)
  applyLevelUpdates(asks, parsedAsks)
  apply30PercentFilter()
  lastUpdateId = u
  if (market == FUTURES): lastPu = u

diffBuffer.clear()
state = SYNCED
log.info("OrderBook SYNCED: {}/{} — {} bid levels, {} ask levels", symbol, market, bids.size(), asks.size())
return OK
```

---

#### `applyLevelUpdates(map, levels)` — private

```
for each (price, qty) in levels:
  if (qty == 0.0):
    map.remove(price)
  else:
    entry = map.get(price)
    if (entry == null):
      map.put(price, new PriceLevelEntry(qty, System.currentTimeMillis()))
    else:
      entry.quantity = qty   // mutate in-place — zero allocation
```

Zero-quantity levels must be removed — this is mandatory per Binance protocol. Failure to remove
them corrupts the orderbook.

---

#### `apply30PercentFilter()` — private

Called **after** applying updates so midPrice reflects the post-update best bid/ask.

```
if (bids.isEmpty() || asks.isEmpty()): return

double midPrice = (bids.firstKey() + asks.firstKey()) / 2.0
double lower    = midPrice * (1.0 - FILTER_THRESHOLD)   // midPrice × 0.70
double upper    = midPrice * (1.0 + FILTER_THRESHOLD)   // midPrice × 1.30

bids.entrySet().removeIf(e -> e.getKey() < lower || e.getKey() > upper)
asks.entrySet().removeIf(e -> e.getKey() < lower || e.getKey() > upper)
```

`removeIf` is O(n) but n is bounded by the filter itself — orderbooks never grow beyond 60% of
midPrice range. Future optimization: replace with `TreeMap.headMap`/`tailMap` range clears for
O(log n + k) complexity.

---

### `OrderBookStore`

```java
@Component
public class OrderBookStore {
    private final ConcurrentHashMap<String, OrderBook> books = new ConcurrentHashMap<>();

    public OrderBook getOrCreate(String symbol, Market market) {
        return books.computeIfAbsent(symbol + ":" + market.name(),
            k -> new OrderBook(symbol, market));
    }

    public OrderBook get(String symbol, Market market) {
        return books.get(symbol + ":" + market.name());
    }

    public int size() { return books.size(); }
}
```

`getOrCreate` is called by the consumer thread (one per shard). Because each symbol always hashes
to the same shard, `computeIfAbsent` is never called concurrently for the same key. ConcurrentHashMap
is used to safely handle reads from the `SnapshotFetchQueue` thread. Keys are `"SYMBOL:MARKET"` strings.

---

### `OrderBookProcessor`

Spring-managed bean. Injected into `DisruptorShardManager`, which passes it to each `DepthEventHandler`
constructor. Consumer threads call `process()` for every ring buffer event.

```java
@Component
@RequiredArgsConstructor
public class OrderBookProcessor {
    private final OrderBookStore store;
    private final SnapshotFetchQueue snapshotFetchQueue;

    public void process(DepthEvent event) {
        // SNAPSHOT events: orderbook must already exist (created when first diff arrived).
        // DIFF events: create lazily on first arrival for this symbol+market.
        OrderBook ob = (event.type == EventType.SNAPSHOT)
            ? store.get(event.symbol, event.market)
            : store.getOrCreate(event.symbol, event.market);

        if (ob == null) {
            // SnapshotEvent arrived for an unknown symbol — ticker removed mid-flight. Drop.
            return;
        }

        OrderBookResult result = (event.type == EventType.SNAPSHOT)
            ? ob.applySnapshot(event.rawJson)
            : ob.onDiff(event.rawJson);

        if (result == OrderBookResult.NEEDS_SNAPSHOT || result == OrderBookResult.NEEDS_RESYNC) {
            snapshotFetchQueue.enqueue(ob);
        }
    }
}
```

---

### `SnapshotFetchQueue`

Rate-limited dispatcher. Maintains two independent queues (spot, futures) and two `@Scheduled`
tasks dispatching one request each per 625ms. The scheduler thread is the only writer to
`ob.state` outside the consumer — this is safe because `state` is volatile.

**Fields:**
```java
private final BinanceRestClient restClient;
private final DisruptorShardManager shardManager;
private final Queue<OrderBook> spotQueue    = new ConcurrentLinkedQueue<>();
private final Queue<OrderBook> futuresQueue = new ConcurrentLinkedQueue<>();
```

---

#### `enqueue(OrderBook ob)`

Called by consumer thread. Sets state to QUEUED (covering both PENDING→QUEUED for first-time sync
and STALE→QUEUED for re-syncs) before adding to the queue.

```java
public void enqueue(OrderBook ob) {
    ob.state = OrderBookState.QUEUED;  // volatile write — diffs start dropping immediately
    if (ob.market == Market.SPOT) {
        spotQueue.offer(ob);
    } else {
        futuresQueue.offer(ob);
    }
}
```

The volatile write ensures the consumer thread sees QUEUED on the very next event for this
orderbook, preventing any diff from slipping through to a buffer while the orderbook waits for
dispatch. `ConcurrentLinkedQueue.offer()` is thread-safe and non-blocking.

---

#### `@Scheduled(fixedRate = 625) dispatchSpot()`

```
ob = spotQueue.poll()
if (ob == null): return   // queue empty; budget conserved

ob.markSnapshotDispatched()   // QUEUED → SNAPSHOT_REQUESTED (volatile write)

restClient.getSpot("/api/v3/depth?symbol=" + ob.symbol + "&limit=1000", String.class)
  .subscribe(
    rawJson -> publishSnapshotEvent(ob, rawJson),
    error   -> {
      log.warn("Snapshot fetch failed for {}/SPOT: {}", ob.symbol, error.getMessage())
      enqueue(ob)   // re-enqueue; rate limiter provides natural backoff
    }
  )
```

#### `@Scheduled(fixedRate = 625) dispatchFutures()`

Identical to `dispatchSpot()`, using `/fapi/v1/depth?symbol={}&limit=1000` and `futuresQueue`.

---

#### `publishSnapshotEvent(OrderBook ob, String rawJson)` — private

Called from Reactor thread (WebClient response callback). Publishes into the ring buffer so the
consumer thread applies the snapshot in sequence with diffs.

```java
private void publishSnapshotEvent(OrderBook ob, String rawJson) {
    RingBuffer<DepthEvent> rb = shardManager.getRingBuffer(ob.symbol);
    long seq = rb.next();
    try {
        DepthEvent event = rb.get(seq);
        event.type    = EventType.SNAPSHOT;
        event.symbol  = ob.symbol;
        event.market  = ob.market;
        event.rawJson = rawJson;
    } finally {
        rb.publish(seq);   // must always publish — failure permanently claims the slot
    }
}
```

`DisruptorShardManager.getRingBuffer(String symbol)` is already public. No additional exposure
needed. The Disruptor was configured with `ProducerType.MULTI` so Reactor threads publishing
here is safe alongside WebSocket threads publishing diffs.

---

### `DepthEventHandler` (modified)

Replaced counting logic with `OrderBookProcessor` delegation. Stats logging removed; if throughput
metrics are needed later, add a `LongAdder` in `OrderBookProcessor`.

```java
public class DepthEventHandler implements EventHandler<DepthEvent> {
    private final int shardIndex;
    private final OrderBookProcessor processor;

    public DepthEventHandler(int shardIndex, OrderBookProcessor processor) {
        this.shardIndex = shardIndex;
        this.processor  = processor;
    }

    @Override
    public void onEvent(DepthEvent event, long sequence, boolean endOfBatch) {
        processor.process(event);
        event.clear();
    }
}
```

---

### `DisruptorShardManager` (modified)

Inject `OrderBookProcessor` and thread it through to each handler constructor:

```java
@Component
@RequiredArgsConstructor
public class DisruptorShardManager {
    private final DisruptorProperties props;
    private final OrderBookProcessor orderBookProcessor;   // new injection

    @PostConstruct
    public void start() {
        for (int i = 0; i < props.shardCount(); i++) {
            // ... (existing setup) ...
            DepthEventHandler handler = new DepthEventHandler(i, orderBookProcessor);  // pass processor
            // ...
        }
    }
    // ... rest unchanged ...
}
```

`@Scheduled logStats()` method is removed (no more `eventCount` in the handler). If aggregate
throughput logging is desired, re-add a counter in `OrderBookProcessor` independently.

---

## Threading Model Summary

| Thread | What it does | What it writes |
|--------|-------------|----------------|
| Consumer thread N | Processes ring buffer events for shard N | `OrderBook.bids`, `OrderBook.asks`, `OrderBook.diffBuffer`, `OrderBook.lastUpdateId`, `OrderBook.lastPu`, `OrderBook.state` (PENDING→QUEUED, SNAPSHOT_REQUESTED→SYNCED, SYNCED→STALE) |
| SnapshotFetchQueue scheduler | Polls queue; dispatches HTTP; sets `markSnapshotDispatched()` | `OrderBook.state` (QUEUED→SNAPSHOT_REQUESTED); also writes `OrderBook.state` via `enqueue()` (STALE→QUEUED) |
| Reactor/WebClient thread | HTTP response callback | Publishes SnapshotEvent to ring buffer only — does NOT touch OrderBook directly |

**Why this is safe**: `OrderBook.state` is `volatile` — all cross-thread reads and writes are
immediately visible. `bids`, `asks`, `diffBuffer`, `lastUpdateId`, `lastPu` are accessed only by
the consumer thread for that shard — no synchronization needed. The TreeMaps require no locking.

---

## Startup Sequence

```
Spring context initializes
  └─ @PostConstruct DisruptorShardManager.start()
       └─ 4 Disruptors started; consumers alive and waiting
       └─ OrderBookProcessor injected and ready

  └─ @Scheduled SnapshotFetchQueue tasks begin ticking
       └─ Queues empty at this point; tasks return immediately

Context fully ready
  └─ TickerRefreshScheduler fires
       └─ TickerService.refreshTickers()
            └─ TickersRefreshedEvent published
                 └─ BinanceWebSocketManager.startPools()
                      └─ WebSocket connections open; @depth@100ms streams active
                           └─ Diffs flow into ring buffer
                                └─ Consumer: first diff for symbol X, market Y
                                     └─ OrderBookStore.getOrCreate() → new OrderBook(PENDING)
                                          └─ ob.onDiff() → PENDING→QUEUED, return NEEDS_SNAPSHOT
                                               └─ snapshotFetchQueue.enqueue(ob) → added to queue
                                                    [625ms tick fires]
                                                    └─ ob.markSnapshotDispatched() → SNAPSHOT_REQUESTED
                                                         └─ REST call dispatched
                                                              [response arrives on Reactor thread]
                                                              └─ publishSnapshotEvent() → SnapshotEvent in ring
                                                                   └─ Consumer: ob.applySnapshot()
                                                                        └─ Binance sync algorithm
                                                                             └─ state = SYNCED
                                                                                  └─ Live diffs applied
```

---

## Binance Sync Algorithm (Reference)

**Spot** (`GET /api/v3/depth?symbol=X&limit=1000`):
1. Snapshot dispatched → start buffering diffs (SNAPSHOT_REQUESTED)
2. Snapshot arrives; note `lastUpdateId = N`
3. Discard buffered diffs where `u < N`
4. Find first buffered diff satisfying `U <= N + 1 <= u`
5. Apply from that diff onward; for each: `U` must equal `lastUpdateId + 1`
6. Continuous from here in SYNCED state

**Futures** (`GET /fapi/v1/depth?symbol=X&limit=1000`), additional checks:
- First diff applied after snapshot: `pu` must equal snapshot's `lastUpdateId`
- Each subsequent diff: `pu` must equal previous diff's `u`
- Any `pu` gap → STALE → re-sync

---

## 30% Filter — Summary

| Trigger | Action |
|---------|--------|
| Snapshot loaded (step 3 of sync algorithm) | Sweep all levels outside 30% of snapshot mid-price |
| After every SYNCED diff applied | Recalculate mid-price from updated best bid/ask; sweep outliers |

The filter bounds memory: without it, a single orderbook could accumulate tens of thousands of
levels. With it, each orderbook holds at most the levels within ±30% of midPrice.

---

## Implementation Order

1. `EventType.java`
2. `DepthEvent.java` — add `type` field, add `clear()` method
3. `OrderBookState.java`
4. `OrderBookResult.java`
5. `PriceLevelEntry.java`
6. `OrderBook.java`
7. `OrderBookStore.java`
8. `OrderBookProcessor.java`
9. `SnapshotFetchQueue.java`
10. `DepthEventHandler.java` — replace counter logic with processor delegation
11. `DisruptorShardManager.java` — inject `OrderBookProcessor`, pass to handler constructors
12. `CURRENT_STATE.md` — update component list and implementation status

---

## What Is Explicitly Deferred

| Item | Phase |
|------|-------|
| Order classification (Purple/Red/Yellow/Green/Gray tiers) | Phase 4 |
| User WebSocket server (push classified events) | Future |
| Ticker refresh reconciliation (add/remove tickers at runtime) | After Phase 4 |
| Switch `BlockingWaitStrategy` → `YieldingWaitStrategy` | Pre-load-test |
| Primitive orderbook store (eliminate `Double` boxing) | Long-term optimization |
| `TreeMap.headMap`/`tailMap` range clears for 30% sweep | Long-term optimization |
| Snapshot caching / pre-warming | Long-term optimization |
| Klines streams | Future |
| Authentication / JWT | Future |

---

## Verification Checklist

1. App starts — Disruptors and snapshot scheduler are running before any WebSocket events arrive
2. First 100ms of stream activity — OrderBooks created in PENDING, immediately QUEUED
3. SnapshotFetchQueue drains at ~1.6/sec per market — verify via log output
4. `markSnapshotDispatched()` fires — orderbook enters SNAPSHOT_REQUESTED; next diffs are buffered
5. Snapshot REST response arrives — SnapshotEvent published into ring buffer (not direct write)
6. `applySnapshot()` runs — log shows SYNCED with bid/ask counts > 0
7. SYNCED diffs applied — `lastUpdateId` advances monotonically (verify via debug logging)
8. Futures `pu` continuity enforced — induced gap triggers STALE → re-enqueue → re-sync
9. 30% filter active — levels beyond ±30% of midPrice absent from TreeMaps after each update
10. Re-sync path end-to-end — SYNCED → STALE → QUEUED → SNAPSHOT_REQUESTED → SYNCED
11. Buffer overflow guard — at MAX_BUFFER_SIZE, orderbook transitions STALE and re-syncs cleanly
12. Memory stable after 10 minutes of full-load operation — no unbounded growth in heap
13. No `InsufficientCapacityException` on any ring buffer shard under production tick volume
