# Screener Backend — Project Guide

## Project Overview

A high-throughput, low-latency cryptocurrency market screener backend. The core responsibility is maintaining accurate local order books across all active Binance tickers (spot and futures) and classifying price levels by importance for delivery to subscribed end-users.

Target scale: ~500 futures tickers + a subset of those on spot = potentially 1000+ concurrent depth streams, hundreds of thousands of diff messages per second.

---

## Goals and Non-Negotiables

- **Throughput**: Handle the full firehose of Binance depth updates across all streams without dropping or falling behind
- **Accuracy**: Local order books must stay correctly synchronized with Binance at all times; stale books must not feed the classifier
- **Low Latency**: Minimize the path from WebSocket message receipt to order classification
- **GC Efficiency**: Minimize object allocation in hot paths to avoid GC pauses disrupting throughput
- **Concurrency**: Processing must be lock-free or minimally contended — no shared mutable state between independent tickers
- **Scalability**: Architecture must support future growth (more tickers, more markets, user-facing output, additional analysis types)

---

## Technology Stack

### Spring Boot (MVC — not WebFlux)
Classical Spring Boot with the servlet/MVC stack. The reactive model of WebFlux is not required here because the data processing pipeline is owned by java-websocket + LMAX Disruptor, which operate independently of Spring's request handling. Spring MVC is simpler and sufficient.

`WebClient` from `spring-webflux` is used within the MVC context for non-blocking outbound HTTP calls (REST snapshot fetching, ticker enumeration). This is a deliberate hybrid — WebFlux as a client library only.

**Rule**: Do NOT use Spring's built-in `WebSocketClient`. All WebSocket connections to Binance go through java-websocket.

### WebClient (spring-webflux)
Used exclusively for outbound HTTP calls to Binance REST APIs. Non-blocking, composable, suitable for concurrent snapshot fetching with rate limiting.

### java-websocket
Manages all WebSocket connections to Binance depth streams. Chosen for low-level control, lightweight footprint, and absence of abstractions that add latency. One connection manages many streams; multiple connections run concurrently.

**Rule**: WebSocket `onMessage` callbacks must do minimal work — parse only what is strictly necessary, publish the raw payload to the Disruptor ring buffer, and return immediately. No heavy computation, no blocking, no logging at INFO level inside callbacks.

### LMAX Disruptor
Lock-free, cache-line-friendly ring buffer for asynchronous processing of incoming depth updates. The WebSocket thread publishes events in O(1); consumer threads process them independently.

**Configuration**:
- **2 shards** (configurable via `application.yml`)
- Ticker-to-shard assignment: `Math.abs(symbol.hashCode()) % shardCount` — stable, deterministic
- Each shard: one `Disruptor` instance + one dedicated consumer `EventHandler` thread
- Ring buffer size: power of 2 (e.g., 65536 per shard — configurable)
- Producer type: `ProducerType.MULTI` (multiple WebSocket threads publish concurrently)
- Wait strategy: `BlockingWaitStrategy` (sufficient given reduced stream rates; switch to `YieldingWaitStrategy` if latency becomes a concern)

**Rule**: Ring buffer size must always be a power of 2.

**Rule**: All updates for a given ticker always go to the same shard. Never let a ticker's events split across shards — the consumer is single-threaded per shard and orderbooks are not thread-safe.

### Jackson Streaming API (JsonParser)
Use Jackson's streaming `JsonParser` for high-frequency message parsing (diff depth updates). Do not deserialize full JSON into POJOs for each depth event — this creates per-message object allocation that compounds under load.

Full POJO deserialization via `ObjectMapper` is acceptable for low-frequency calls: ticker list fetching, REST snapshots, configuration.

**Guideline**: In the Disruptor consumer, parse diff updates by streaming through the JSON and extracting only the fields needed (`b`, `a`, `U`, `u`, `pu`). Skip everything else.

### Primitive `double` for Prices and Quantities
All prices and quantities are stored and processed as primitive `double`. Parse Binance string values with `Double.parseDouble()`.

**Do NOT use `BigDecimal` for market data.** At high message rates, `BigDecimal` allocation creates significant GC pressure. For spread-proximity calculation and order classification, `double`'s 15-digit precision is more than sufficient — this is a screener, not an execution or accounting system.

---

## Architecture Overview

```
Binance Spot WebSocket Connections (java-websocket)
  Multiple connections, ≤1024 streams each
  @depth streams for eligible spot tickers (1 update/second)
          │
Binance Futures WebSocket Connections (java-websocket)
  Multiple connections, ≤1024 streams each
  @depth streams for all futures tickers (1 update/500ms)
          │
          ▼
  ┌───────────────────────────────┐
  │      LMAX Disruptor Layer     │
  │  2 sharded ring buffers       │
  │  symbol hashed to shard       │
  └───────────┬───────────────────┘
              │
  ┌───────────▼───────────────────┐
  │   Consumer Threads (2x)       │
  │   Parse diff JSON (streaming) │
  │   Validate sync state         │
  │   Apply orderbook updates     │
  │   Run order classification    │
  └───────────┬───────────────────┘
              │
  ┌───────────▼───────────────────┐
  │      Orderbook Store          │
  │  TreeMap<Double,             │
  │    PriceLevelEntry>           │
  │  per (symbol, market) pair    │
  └───────────────────────────────┘
              │
  (Future) User WebSocket Server
  Push classified events to subscribers
```

---

## Ticker Management

### Inclusion Rules
On startup, fetch exchange info from both Binance Spot and Futures APIs and apply the following filter:

| Ticker exists in | Include? | Markets subscribed |
|------------------|----------|--------------------|
| Futures only | Yes | Futures only |
| Both spot and futures | Yes | Both spot AND futures |
| Spot only | **No** | — |

All three of the following conditions must be satisfied for a futures ticker to be included:

1. **USDT-quoted** — quote asset must be `USDT` (e.g. `ETHUSDT` is included; `ETHBTC` is excluded). This applies to both the futures and spot sides.
2. **PERPETUAL contract** — only perpetual futures contracts are tracked; quarterly/delivery contracts are excluded.
3. **TRADING status** — `status = TRADING` in the futures market.

**Result**: There will always be more futures stream subscriptions than spot subscriptions.

### Refresh Strategy
- Refresh ticker list every 3–4 hours (configurable)
- On refresh:
  - New tickers: open subscriptions, begin snapshot sync flow
  - Delisted or non-TRADING tickers: cleanly close subscriptions, remove orderbooks from memory, release buffer capacity

---

## WebSocket Connection Management

### Stream Type
**`@depth`** streams — spot delivers 1 update/second, futures delivers 1 update/500ms. These rates provide sufficient resolution for a screener while keeping message volume manageable.

### Connection Limits
Binance enforces a maximum of **1024 streams per WebSocket connection**. Stream count must be tracked per connection; a new connection must be opened before the limit is reached.

### Separate Pools
Maintain independent connection pools for spot and futures:
- Spot connections: `wss://stream.binance.com:9443/stream`
- Futures connections: `wss://fstream.binance.com/stream`

### Failure Isolation
Each connection manages its own lifecycle independently. A dropped connection must not affect other connections. On disconnect:
1. Attempt reconnection with exponential backoff
2. On reconnect, re-subscribe to all streams that belonged to this connection
3. Trigger re-sync for all affected tickers (re-enter the snapshot fetch queue)
4. Resume diff buffering immediately on reconnect (before snapshot arrives)

---

## Orderbook Management

### Data Structure
Each `(symbol, market)` pair maintains an independent local orderbook:
- **Bids**: `TreeMap<Double, PriceLevelEntry>` — natural descending iteration via `descendingKeySet()` or `firstKey()` for best bid
- **Asks**: `TreeMap<Double, PriceLevelEntry>` — natural ascending order, `firstKey()` for best ask

`TreeMap` gives O(log n) insert/update/delete and O(1) best bid/ask access. Its sorted structure also simplifies the 30% filter sweep.

`PriceLevelEntry` is a mutable value object holding two fields:
```java
class PriceLevelEntry {
    double quantity;
    long firstSeenMillis;
}
```

Quantity updates on existing levels mutate `entry.quantity` in place — zero object allocation on the hot path. `firstSeenMillis` is set once on insertion and never changed.

**Known overhead**: `TreeMap<Double, PriceLevelEntry>` boxes the `Double` key (~48–80 bytes per node). Accepted for now. Future optimization: replace with a primitive-friendly sorted structure (e.g., custom sorted double array or Eclipse Collections primitive map).

### Price Level Lifetime Tracking
Users can see how long a specific price level has existed in the orderbook. Binance diff streams do not provide this — it is calculated locally.

**Rules**:
- **Insert**: price key not present in the map → create `new PriceLevelEntry(qty, System.currentTimeMillis())`
- **Update**: price key already present → set `entry.quantity = newQty`, leave `firstSeenMillis` untouched
- **Remove** (qty == 0 or 30% sweep): `TreeMap.remove(price)` — timestamp discarded automatically
- **Re-add**: if a previously removed level reappears, treat it as a fresh insert with a new timestamp — the level genuinely left the book and returned

**Lifetime calculation** (for UI delivery):
```
lifetimeMillis = System.currentTimeMillis() - entry.firstSeenMillis
```

**Re-sync behaviour**: when an orderbook is reset and rebuilt from a new snapshot, all existing entries are discarded and timestamps reset. Lifetime reflects only the current continuous presence of a level, not historical appearances.

### Price Level Filtering (30% Threshold)
To bound memory usage, price levels further than 30% from the current market price are not retained.

**Mid-price formula**:
```
midPrice = (bestBid + bestAsk) / 2.0
```

**On each diff update batch**:
1. Apply all zero-quantity removals first
2. Apply all non-zero updates, filtering any new level where `abs(level - midPrice) / midPrice > 0.30`
3. Recalculate midPrice using updated best bid/ask
4. Sweep existing levels: remove any that now fall outside the 30% window

**Rule**: Recalculate midPrice **after** applying the update batch, not before. The incoming update may contain the new best bid/ask.

### Zero-Quantity Removal
Per Binance protocol: any update with `quantity = 0.0` means that price level no longer exists and must be removed from the `TreeMap`. This is mandatory — failure to remove zero-quantity levels corrupts the orderbook.

---

## Orderbook Synchronization

### Standard Binance Algorithm
For each `(symbol, market)` pair, follow Binance's documented local order book process:

1. Subscribe to `@depth@100ms` stream — begin buffering updates immediately
2. Dispatch REST snapshot request:
   - Spot: `GET /api/v3/depth?symbol=X&limit=1000`
   - Futures: `GET /fapi/v1/depth?symbol=X&limit=1000`
3. Snapshot response arrives — note its `lastUpdateId`
4. Discard all buffered updates where `u < lastUpdateId`
5. Find the first buffered update satisfying `U <= lastUpdateId + 1 <= u`
6. Apply that update and all subsequent buffered updates in sequence
7. Orderbook is now synchronized — enter live mode

**Futures only**: Additionally validate `pu` continuity between consecutive updates. If a gap is detected, the orderbook is corrupted — discard it and re-sync.

**Critical timing note**: Buffering must begin the moment the snapshot **request is dispatched**, not when the response arrives. The REST call can take 100–500ms during which hundreds of diffs may arrive. All of those diffs must be available for step 4–6 above.

### Startup Snapshot Throttling
Binance enforces API weight limits (spot: 6000/min, futures: 2400/min). Each snapshot request costs 50 weight (spot) or 20 weight (futures). Fetching all snapshots simultaneously would trigger a ban.

**Strategy**:
1. Maintain a rate-limited snapshot fetch queue
2. Dispatch snapshot requests up to the weight budget per minute
3. Buffer diff updates only for tickers whose snapshot request has been dispatched
4. **Drop** diff updates for tickers whose snapshot has not yet been requested (no baseline to sync against — buffering without a snapshot is wasteful)
5. After ~1 minute (weight limit resets), continue fetching snapshots for the next batch
6. Repeat until all tickers are synchronized

**Accepted tradeoff**: Full orderbook coverage takes several minutes at startup. This is a known limitation. Future work may explore higher-weight API access or snapshot caching strategies.

### Orderbook Sync State Machine
Each `(symbol, market)` orderbook tracks an explicit state:

| State | Meaning |
|-------|---------|
| `PENDING` | Snapshot not yet requested; diff updates are dropped |
| `SNAPSHOT_REQUESTED` | Request dispatched; buffering diff updates |
| `SYNCED` | Live — applying updates in real time |

Any sync failure (sequence gap, parse error, or empty buffer after snapshot) resets the orderbook to `PENDING` and re-enqueues it for a fresh snapshot.

**Rule**: Only `SYNCED` orderbooks produce classification output. All other states are silent.

---

## Order Classification

### Importance Tiers
Each price level in a `SYNCED` orderbook is classified on two axes: **proximity to spread** and **notional value (USD)**.

| Tier | Label | Characteristics |
|------|-------|-----------------|
| 1 | Purple | Closest to spread, highest notional |
| 2 | Red | Near spread, high notional |
| 3 | Yellow | Moderate proximity or moderate notional |
| 4 | Green | Further from spread, lower notional |
| 5 | Gray | Below interest threshold |

Classification thresholds are **user-configurable** — each user defines their own proximity and notional cutoffs. The system must support per-user rule sets efficiently.

### Computation
- `notional = price × quantity` (primitive double arithmetic)
- `proximity = abs(levelPrice - midPrice) / midPrice`
- `lifetimeMillis = System.currentTimeMillis() - entry.firstSeenMillis` (available for UI delivery)
- Classification runs inside the Disruptor consumer thread after each orderbook update

### Why Full Orderbook Is Retained
Levels far from the spread may be irrelevant under default settings but critical for users with wide custom thresholds (e.g., a user who wants alerts on any $1M+ order within 15% of spread). The full orderbook, bounded only by the 30% filter, must be available for per-user rule evaluation.

---

## Coding Standards

### Performance Rules
- No object allocation in hot paths: WebSocket `onMessage` callbacks and Disruptor consumer `onEvent` loops
- No `BigDecimal` for market data
- No `String.format()` or string concatenation in high-frequency code paths — use logging frameworks with lazy message evaluation (`log.debug("{}", value)` not `log.debug("val: " + value)`)
- No per-message logging at INFO or above inside consumers or WebSocket callbacks
- Intern or pool `String` symbols where they appear repeatedly in hot paths

### Concurrency Rules
- Orderbook `TreeMap` instances are accessed only by their assigned shard's consumer thread — do not synchronize them
- Use `volatile` or `AtomicReference` for sync state flags accessed from multiple threads
- Never block inside a WebSocket callback
- Never share mutable state between shards

### Configuration
All tunable values must be externalized to `application.yml`:
- Disruptor shard count
- Ring buffer size per shard
- WebSocket streams per connection limit
- Price filter threshold (30%)
- Ticker refresh interval
- Snapshot fetch rate limit budget
- Orderbook depth limit for REST snapshots

### Error Handling
- WebSocket disconnects: reconnect with exponential backoff, re-sync affected tickers
- REST snapshot failures: retry with backoff, do not silently drop
- Malformed or unexpected JSON from Binance: log at WARN, skip the message, never crash the consumer thread
- Binance 429/418 responses: detect, back off, resume after cooldown
- Continuity gaps in futures `pu` sequence: log, reset orderbook to `PENDING`, re-sync

---

## Future Work (Planned)

### Near-Term
- **User WebSocket Server**: Push classified price level events to subscribed clients. Requires session management, per-user rule application, and efficient fan-out. This is a separate design effort — do not couple it to the orderbook core prematurely.
- **Klines Streams**: Subscribe to candlestick data for additional analysis signals. Less demanding than depth streams; can share the existing connection infrastructure.
- **User Management**: Registered users, subscription plans, stored preferences and classification rules.

### Medium-Term
- **Security**: Authentication and authorization (JWT or similar). Designed after the core data pipeline is stable and tested.
- **Business Layer**: Subscription tiers, usage limits, alerting rules.

### Long-Term / Scalability
- **Distributed Deployment**: If a single JVM cannot sustain all tickers, partition ticker sets across multiple instances with a coordination layer.
- **Primitive Orderbook Store**: Replace `TreeMap<Double, PriceLevelEntry>` with a memory-efficient primitive-friendly sorted structure to reduce boxed object overhead across 1000+ orderbooks.
- **Snapshot Optimization**: Higher rate limit access, snapshot caching, or pre-warming strategies to reduce startup synchronization time.
- **Additional Exchanges**: The architecture is Binance-first. Core interfaces (orderbook, classifier, stream manager) must not hard-code Binance assumptions — leave extension points clean.

### Known Technical Debt (Accepted for Now)
- `TreeMap<Double, PriceLevelEntry>` boxing overhead on keys — acceptable until memory profiling shows a problem
- Sequential startup sync (several minutes to full coverage) — accepted; snapshot optimization is future work
- 2 shards (configurable) — accepted until production load data informs a smarter default
