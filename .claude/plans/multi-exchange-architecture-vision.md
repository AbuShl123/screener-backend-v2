# Multi-Exchange Architecture — Vision

**Status**: architectural vision. No code has been written against this document.
**Scope**: instrument discovery → WebSocket transport → order book synchronisation → in-memory
storage. This document stops at *"every tracked instrument on every enabled exchange has an
accurate, in-sync local order book."* Classification, per-user rules, the feed, and the client
WebSocket server are **out of scope** as features — but §2 explains why they cannot be left
entirely untouched.

**Related reading**: `.claude/docs/orderbook-sync-algorithm.md` (the current Binance-only
pipeline, which this document generalises), `CLAUDE.md` (hot-path rules — they continue to
apply unchanged).

---

## 0. Goal and constraints

Today the screener tracks ~700 Binance instruments (USDT-quoted, futures-required, spot
optional). The goal is to track a comparable universe on **Bybit, MEXC, Bitget and beyond**,
with two hard requirements:

1. **Additive** — adding an exchange must be a new package plus a YAML block. If it requires
   editing shared/core code, the abstraction has leaked and the design has failed.
2. **Non-regressive** — adding exchange N+1 must not be able to break exchange N. Isolation is
   structural, not a matter of care.

And two standing constraints inherited from the existing system:

- **Hot-path austerity** (`CLAUDE.md`): no per-message allocation, no `BigDecimal`, streaming
  `JsonParser` only, no INFO logging per message. Multiplying the venue count multiplies the
  message rate; these rules get *more* important, not less.
- **One JVM** — for now. The design must not *prevent* splitting by exchange into separate
  processes later (see §10.3), but that is not the near-term target.

Rough scale target: ~700 instruments × ~5 exchanges × up to 2 markets ≈ **3 000–5 000 live
order books**, on the order of 20 000–50 000 depth messages/second.

---

## 1. The core reframing: the unit is a **venue**, not an exchange

"Exchange" is the wrong granularity, and the current codebase already demonstrates why.

Binance spot and Binance futures differ in URL, sequence-validation rule (`U == lastUpdateId+1`
vs `pu == lastUpdateId`), snapshot endpoint, snapshot weight cost, weight ceiling, and stream
suffix. Today that divergence is handled with `if (market == Market.SPOT)` branches scattered
across `OrderBook.applyLiveDiff`, `BinanceConnectionPool.start`, `SnapshotFetchQueue`
(duplicated `dispatchSpot`/`dispatchFutures` methods), and `BinanceWebSocketManager`.

Other exchanges are worse, not better:

- **MEXC** spot and futures are effectively *different products* — different API domain, different
  subscription protocol, different message envelope.
- **Bybit** discriminates by `category` (`spot` / `linear` / `inverse`), which changes topic
  shape and endpoint.
- **Bitget** futures symbols carry a product-type suffix that spot symbols do not.

> **Decision**: the adapter unit is a **Venue** = `(exchange, market)`.
> `BINANCE_SPOT`, `BINANCE_FUTURES`, `BYBIT_SPOT`, `BYBIT_LINEAR`, `MEXC_SPOT`, …

Each venue supplies its own protocol, codec, sync strategy, snapshot source, recovery mechanism,
and request budget. Every existing `if (market == ...)` branch collapses into polymorphism. We
stop paying for the fiction that "MEXC" is one coherent thing.

**One venue, one set of adapter beans — even when two venues share an implementation class.**
Bybit spot and Bybit linear can be served by the same `DepthSyncStrategy` class, but they get one
configured *instance* each. It costs nothing, keeps every per-instrument lookup uniform, and the
first time the two diverge in a single config value the seam already exists.

Initial market coverage is deliberately narrow: **SPOT and LINEAR_PERP only**. Inverse contracts
are quantity-denominated in the base asset, which breaks notional comparison downstream — a
separate decision for a later phase (§11).

---

## 2. Identity — the change with the largest blast radius

### 2.1 The problem

Everything in the hot path is keyed on `String symbol`:

| Location | Current key |
|---|---|
| `OrderBookStore` | `symbol + ":" + market.name()` — e.g. `"BTCUSDT:SPOT"` |
| `DisruptorShardManager.getRingBuffer` | `Math.abs(symbol.hashCode()) % shardCount` |
| `DepthEvent` | `String symbol` + `Market market` |
| `OrderBookFeedStore` | `String key` |
| `OrderBookClassifier` | per-`(symbol, market)` `SymbolState` maps |
| `/api/rules` persistence | `(symbol, market)` columns |

`BTCUSDT` exists on Binance, Bybit, MEXC and Bitget. The key is now ambiguous. Native formats
also diverge (`BTCUSDT`, `BTC_USDT`, `BTCUSDT_UMCBL`), so the raw exchange string is not even a
stable identifier across venues for the *same* asset pair.

### 2.2 The instrument model

```java
Instrument {
    int    id;              // dense, runtime-only, assigned at registration
    Venue  venue;           // BINANCE_FUTURES, BYBIT_LINEAR, ...
    String nativeSymbol;    // exactly what the exchange expects ("BTCUSDT_UMCBL")
    String canonical;       // "BTC/USDT" — cross-venue grouping
    String base, quote;     // "BTC", "USDT"
    double tickSize;        // needed for checksum venues (§4.1 model D)
    double stepSize;
}
```

`(venue, nativeSymbol)` is the **durable** identity. The `int id` is a runtime index derived from
it — see §2.5.

### 2.3 Why the dense `int id` matters (this is the real payoff, not just disambiguation)

- **Shard routing becomes `id & (shardCount - 1)`** — no hashing, no modulo, and perfectly even
  distribution instead of hash-luck. It also removes a latent bug: `Math.abs(Integer.MIN_VALUE)`
  is negative, so the current expression can throw `ArrayIndexOutOfBoundsException` for an
  unlucky symbol string. Shard count must be validated as a power of two at config-binding time,
  not silently degraded back to `%`.
- **Hot-path maps become arrays indexed by id** (§2.4). This deletes both the
  `ConcurrentHashMap` lookup *and* the `symbol + ":" + market.name()` string concatenation —
  which currently allocates on **every single depth message** inside `OrderBookStore.getOrCreate`.
- **`DepthEvent` shrinks** to `int instrumentId` + `EventType` + payload; `Market` and `String
  symbol` fields disappear.
- **Interning becomes unnecessary.** `BinanceStreamClient.onMessage` currently does
  `message.substring(...).intern()` per message — a native call into the JVM string table, under
  a lock, at tens of thousands of messages per second. With a per-connection routing index (§5.3)
  the hot path resolves an `int` and never interns anything.

### 2.4 `BookSlot` — the per-instrument runtime record

Every event needs three things together: the book to mutate, the venue-specific cursor state, and
the strategy that knows how to apply the frame. Holding those in three parallel arrays means three
array loads landing on three unrelated cache lines. They belong in one record:

```java
final class BookSlot {
    OrderBook         book;
    BookSyncContext   ctx;             // opaque to core; created by strategy.newContext()
    DepthSyncStrategy strategy;        // resolved once, at registration, from the venue
    volatile boolean  resetRequested;  // the reset lane — §3.4
}

BookSlot[] slots;   // indexed by instrument id; grown copy-on-write on the discovery path
```

One array load, one dereference, everything on the same line. This — not `OrderBook[]` — is the
central hot-path data structure.

Two consequences follow, both simplifications:

- **Slots are populated at registration, not lazily on first diff.** Today
  `OrderBookProcessor.process` branches `SNAPSHOT → store.get` versus `DIFF → store.getOrCreate`
  and null-checks the result. With slots allocated during discovery, that branch, the
  `computeIfAbsent`, the key concatenation and the null check all disappear. `slots[id]` is
  unconditionally present.
- **Strategy selection is not a hot-path decision.** "Which sync algorithm applies" is resolved
  once, when the instrument is registered and its venue is known. The consumer never walks
  `id → Instrument → Venue → strategy`; it reads `slot.strategy`.

The classifier's per-shard state generalises the same way: an array indexed by global instrument
id, sparse from any one shard's perspective. At 5 000 instruments × a handful of shards that is a
few tens of thousands of references — irrelevant memory, and it keeps indexing uniform everywhere.

### 2.5 Id assignment, holes, and what must never be persisted

Ids come from a counter in `InstrumentRegistry`, assigned the first time a `(venue, nativeSymbol)`
pair is registered. They are not derived from anything — not a hash, not a position in a sorted
list — because both would produce gaps or reshuffle on refresh.

- **Dense**: the space is `[0, everRegistered)` for the life of the process.
- **Stable**: re-registering the *same* `(venue, nativeSymbol)` returns the *same* id. A symbol
  delisted and later relisted keeps its identity.
- **Never transferred**: an id is never handed to a *different* instrument. Delisting leaves a
  hole in `slots[]` rather than freeing the index — a stale in-flight event applied to the wrong
  instrument is silent corruption, and no memory saving is worth it. Over months of 4-hourly
  refreshes this is a few hundred holes on a few thousand instruments.

> **The int id never reaches the database.** It is a process-local array index, meaningless across
> restarts. `/api/rules` and every other persisted reference stores `(venue, native_symbol)`.
> The registry maps that pair to an id at startup.

**Logging keeps its names via a cold path.** Once identity is an int, every
`log.warn("[{}/{}] …", symbol, market)` in the sync code degrades to `[4127]`. The registry
exposes a `describe(int id)` used *only* from debug/warn/monitoring paths. Without this, the
temptation is to put `String symbol` back on `OrderBook` "just for logging", quietly undoing the
work.

### 2.6 Honest callout: this cannot be contained to the market-data module

Re-keying necessarily reaches into modules explicitly declared out of scope:

- `feed/OrderBookFeedStore` keys and `OrderBookUpdate` payload
- `analysis/UserClassificationContext` and the classifier's per-symbol state maps
- `analysis/rule/` persistence — a **Flyway migration** adding a venue column to the rule-target
  table, plus a backfill setting existing rows to Binance
- The client WebSocket payload shape, and therefore **the frontend contract**
  (`.claude/docs/for-frontend/`)

None of this is *hard*, but it is wide. It should happen **once, deliberately, before any second
exchange exists** — never incrementally per-exchange. This is the single strongest argument for
the phased ordering in §12.

---

## 3. The pipeline end to end

Four lanes carry data or control into a book. Three are in-band through the ring buffer; one is
an out-of-band flag. Everything below assumes a single shard's consumer thread owns every
`BookSlot` whose `id & (shardCount - 1)` selects that shard.

```
  discovery                                                        (cold, minutes)
      │  registers (venue, nativeSymbol) → id, fills slots[id]
      ▼
  ┌──────────────┐   frame    ┌──────────────┐  DepthEvent  ┌──────────────┐
  │ StreamClient │──────────▶ │  ring buffer │────────────▶ │   consumer   │
  │ (per conn.)  │  WS_MSG    │  (per shard) │              │  (per shard) │
  └──────────────┘            └──────────────┘              └──────┬───────┘
                                     ▲                             │ slots[id]
                                     │ API_SNAPSHOT                ▼
                              ┌──────────────┐              ┌──────────────┐
                              │  snapshot    │              │   strategy   │
                              │  queue       │◀─────────────│   .onEvent   │
                              └──────────────┘ RecoverySink └──────┬───────┘
                                     ▲                             │ book SYNCED
                              ┌──────────────┐                     ▼
                              │  resubscribe │              ┌──────────────┐
                              │  (transport) │              │  classifier  │
                              └──────────────┘              └──────────────┘
```

### 3.1 Cold path — discovery and registration

Each venue's `InstrumentSource` reports its tradable universe.
`InstrumentUniverseService` merges across venues, applies global inclusion policy, and asks
`InstrumentRegistry` for an id per `(venue, nativeSymbol)`. For every new id it allocates
`slots[id]`, constructing the `OrderBook`, calling `strategy.newContext()` once, and pinning the
venue's strategy into the slot. It then publishes `InstrumentUniverseChanged(added, removed)`, on
which the transport layer subscribes the added instruments and unsubscribes the removed ones
(§8.3).

Everything after this point is hot.

### 3.2 Ingress — from frame to ring slot

A `StreamClient` belongs to exactly one venue and one connection. When a frame arrives on its
reader thread it does the least possible work:

1. **Discard control frames.** Subscription acks, pongs, error envelopes — recognised by the
   venue's `StreamProtocol`.
2. **Locate the routing token.** A bounded scan for the venue's routing key: `"s":"…"` for
   Binance, the `topic` value for Bybit, `c` for MEXC. The token is used *whole* — there is no
   reason to parse `BTCUSDT` back out of `orderbook.1000.BTCUSDT` when the full topic is already
   a unique key.
3. **Resolve it to an int** against the connection's `SubscriptionIndex` (`token → instrumentId`),
   populated at subscribe time. Per-connection, so it is small, ambiguity-free (a connection
   serves one venue), and cache-resident.
4. **Publish** a `DepthEvent { instrumentId, EventType.WS_MSG, payload }` into
   `ringBuffers[id & (shardCount - 1)]`.

No `intern()`, no book lookup, no parsing beyond the routing token. The dominant cost on this
thread is the frame `String` the WebSocket library allocated before the callback ran; the routing
scan is noise beside it, which is why a straightforward substring scan is the right answer and
byte-level tricks are not worth their complexity yet (§7).

### 3.3 The two in-band event types

`EventType` encodes **provenance** — where the payload came from, and therefore which parse path
applies — not semantics:

| Type | Origin | Backpressure policy |
|---|---|---|
| `WS_MSG` | a stream frame | `tryNext()`; a drop is recoverable (§8.1) |
| `API_SNAPSHOT` | a REST snapshot response | blocking `next()`; rare, must not be lost |

Provenance, not semantics, because "is this a snapshot or a delta" is a **venue-specific read**.
Bybit answers it from a `type` field inside the payload; Binance answers it from where the bytes
came from. A single event type discriminator plus a venue-owned parse covers both, whereas
splitting the *interface* on snapshot-versus-delta bakes Binance's answer into core.

Sniffing the shape instead of carrying the type — testing for `lastUpdateId` versus `U` — is
rejected: slower, fragile, and the backpressure policy needs the distinction anyway.

### 3.4 The reset lane

Four situations require telling a book "your state is invalid, start over" from a thread that does
**not** own it:

1. the ring buffer is full and a `WS_MSG` was dropped — signalled from the reader thread;
2. a connection reconnected — every book on it must restart, signalled from the reader thread's
   open callback;
3. the staleness watchdog fired (§8.2) — signalled from a scheduler thread;
4. an instrument was unsubscribed on a universe change — signalled from the discovery thread.

None of these can be delivered as a ring event: case 1 arises precisely *because* the ring is
full, and none of the signalling threads may touch `BookSyncContext`, which is single-thread-owned
by the consumer and holds the diff buffer and sequence cursors.

> The signal is the `volatile boolean resetRequested` on `BookSlot`. Any thread sets it. The
> consumer tests and clears it at the top of every event, before dispatching to the strategy, and
> on a set flag drives the book back to `PENDING` and discards the context's buffered state.

One flag, allocation-free, covers all four cases. There is precedent: `OrderBook.state` is already
`volatile` for exactly this reason — the snapshot scheduler writes it today.

### 3.5 Consumption — slot, strategy, classify

The consumer's loop is uniform across every venue:

```java
BookSlot slot = slots[event.instrumentId];
if (slot.resetRequested) { slot.resetRequested = false; reset(slot); }
slot.strategy.onEvent(slot.book, slot.ctx, event);
if (slot.book.getState() == SYNCED) classifier.process(slot.book);
event.clear();
```

The lookup lives **here**, in the consumer, not inside the strategy. The consumer needs the
`OrderBook` regardless — it hands it to the classifier on the next line — so the lookup is free,
it is written once instead of once per venue, and it keeps strategies from holding a reference to
the slot array.

### 3.6 Recovery — the fourth lane

When a strategy detects that its book is unrecoverable from the current frame — a sequence gap, a
failed checksum, a parse error, a reset flag — it marks the book `PENDING` and asks its venue's
`RecoverySink` to fix it. What happens next is entirely venue-defined:

- a model-A venue enqueues the instrument in that venue's snapshot queue; the response returns as
  an `API_SNAPSHOT` event on the ring, in sequence with the diffs;
- a model-B venue asks the transport to unsubscribe and resubscribe the topic; the fresh in-stream
  snapshot returns as an ordinary `WS_MSG`, and the strategy recognises it from the payload;
- a model-C venue does nothing — the next frame heals the book by itself.

In every case the recovered payload re-enters through a lane that already exists. Recovery adds no
new path into the book, which is what keeps ordering trivially correct: the consumer thread
remains the only writer.

---

## 4. The synchronisation seam — where the real difficulty is

### 4.1 Taxonomy the abstraction must cover

The SPI shape is determined by the range of sync models in the wild, not by Binance:

| Model | Mechanism | Recovery | Examples |
|---|---|---|---|
| **A** | REST snapshot + buffered deltas, sequence-range validated | fetch REST snapshot | Binance spot (`U`/`u`), Binance futures (`pu`), MEXC spot |
| **B** | Snapshot arrives **in-stream** as the first frame, then deltas | resubscribe the topic | Bybit (`type: snapshot\|delta`), Bitget |
| **C** | Full top-N pushed every tick, no sequencing at all | none — the next frame heals it | `books5`-style / `@depth20@100ms` streams |
| **D** | Deltas + periodic CRC32 checksum over top-N | resubscribe or refetch | OKX, Bitget, Kraken |

**The generalisation that matters: recovery is not always "fetch a REST snapshot."**
`OrderBookProcessor.process` currently hardcodes `snapshotFetchQueue.enqueue(ob)` as the only
response to a desync. Bybit has no REST snapshot in the flow at all; model C never desyncs at all.

### 4.2 What `OrderBook` currently conflates

`binance/orderbook/OrderBook.java` today does five things. Only two are universal:

| Concern | Universal? |
|---|---|
| TreeMap storage of bid/ask levels, `firstSeenMillis` lifecycle | **yes** |
| Mid-price computation + `±priceFilterThreshold` sweep (`computeDistance`) | **yes** |
| JSON parsing (`applyLevelsDirectly`, `parseSnapshotEvent`, `parseUField`) | no — venue-specific |
| Binance sequence rules (`U`/`u`/`pu`, `lastUpdateId`) | no — venue-specific |
| Diff buffering (`diffBuffer`, `MAX_BUFFER_SIZE`) | no — models B and C never buffer |

### 4.3 The SPI

```java
/** One configured instance per venue. All per-book state lives in BookSyncContext. */
interface DepthSyncStrategy {
    BookSyncContext newContext();                                    // cold: once per book
    void onEvent(OrderBook book, BookSyncContext ctx, DepthEvent e); // hot: every event
}

/** One implementation per venue. The strategy's only collaborator. */
interface RecoverySink {
    void requestRecovery(int instrumentId);
}
```

That is the whole surface. Four choices in it are load-bearing:

**(a) One entry point, because the strategy is the only thing that can classify a payload.**
Splitting the interface into snapshot and delta entry points would be splitting on Binance's
discriminator. Bybit's lives inside the payload; model C has no distinction at all. The strategy
receives the event, reads the provenance it needs from `EventType`, reads whatever else it needs
from the bytes, and decides. Venues that need no second path are not left implementing dead
methods — there is no second path to implement.

The same argument disposes of a separate checksum hook: on OKX and Bitget the checksum arrives
*inside* the delta frame, so verifying it is part of applying the frame, not a distinct call.

**(b) The strategy owns parsing *and* application in a single streaming pass.**
The obvious OO move — a normalised `DepthUpdate` DTO between codec and book — is explicitly
rejected. It costs either an allocation per message or a mutable holder plus a second iteration,
across tens of thousands of messages per second. Instead, `OrderBook` exposes
`applyLevel(side, price, qty)` and the strategy calls it *from inside* its `JsonParser` loop —
exactly the shape `applyLevelsDirectly` already has today. Zero-allocation preserved; the seam
stays clean.

**(c) Sequence cursors and diff buffers live in `BookSyncContext`, not `OrderBook`.**
Binance needs `lastUpdateId`; Bybit needs a seq plus a "have I seen the snapshot yet" flag; OKX
needs the last checksum; model C needs nothing. Core `OrderBook` must know about none of them, and
the diff buffer moves there too since models B and C have no use for one. The context is created
once per book, so it is allocation-free at steady state, and core never inspects its contents — it
is opaque.

**(d) Recovery is expressed as a venue-specific collaborator, not a shared vocabulary.**
The strategy calls `sink.requestRecovery(id)` and is done. It does not know whether that means a
REST fetch, a resubscribe, or nothing at all — and it does not know which snapshot queue,
connection registry, or REST client is involved. That keeps the strategy free of any dependency on
the transport or fetch layers (which would otherwise cycle back through the ring buffer into the
strategy) and trivially unit-testable against a fake sink.

The sink earns its place a second time because **the strategy is not the only caller**. The same
"how does this venue recover" knowledge is needed by the reader thread when a frame is dropped
under backpressure (§8.1), by the staleness watchdog (§8.2), and by the reset lane in general. A
per-venue sink serves all of them through one interface; anything the strategy owned privately
would serve only itself.

### 4.4 What stays in core `OrderBook`

Bids/asks storage, `applyLevel`, `clear`, `bestBid`/`bestAsk`, the mid-price filter sweep,
`firstSeenMillis`/`distance` bookkeeping, and the three-state lifecycle (`PENDING` →
`SNAPSHOT_REQUESTED`/`RECOVERING` → `SYNCED`). That set is genuinely universal across all four
models.

---

## 5. Transport seam

Generalise `BinanceConnectionPool` / `BinanceStreamClient` into a core `ConnectionPool` plus a
per-venue `StreamProtocol`:

```java
interface StreamProtocol {
    URI     endpoint();
    int     maxStreamsPerConnection();
    List<String> buildSubscribeFrames(List<Instrument> batch);
    List<String> buildUnsubscribeFrames(List<Instrument> batch);
    boolean isControlFrame(CharSequence msg);                       // ack / pong / error
    int     resolveInstrumentId(CharSequence msg, SubscriptionIndex idx);
    Heartbeat heartbeat();                                          // WS ping vs JSON app-ping
    boolean binaryFrames();                                         // gzip/deflate venues
}
```

Four concrete problems this fixes, each of which will bite otherwise:

### 5.1 Connection count must be **derived**, not configured

`connection-count-spot: 2` / `connection-count-futures: 3` are hand-picked today, and
`orderbook-sync-algorithm.md` §2 explicitly notes *"There's no per-connection stream-count cap
enforced in code."* That is survivable at Binance's ~1024-stream ceiling. It is not survivable at
MEXC's per-connection subscription cap of roughly **30** — 700 instruments needs ~25 connections,
not 3.

> `connections = clamp(ceil(streamCount / maxStreamsPerConnection), configuredMin, configuredMax)`

The configured value becomes a floor/override, not the authority.

### 5.2 Heartbeats are not uniform

`setConnectionLostTimeout(0)` + periodic `sendPing()` is a Binance-shaped solution:

- **Binance** — protocol-level WebSocket ping.
- **Bybit** — application-level `{"op":"ping"}` roughly every 20s, or the connection is dropped.
- **MEXC** — application-level `{"method":"PING"}`.

`Heartbeat` becomes a per-venue strategy (interval + frame producer), not a fixed behaviour.

### 5.3 Routing must resolve in both directions

`message.charAt(2) == 'r'` (subscribe-ack discrimination) and `indexOf("\"s\":\"")` (symbol
extraction) are Binance-shaped. Elsewhere the routing token sits inside `topic:
"orderbook.50.BTCUSDT"` (Bybit) or `c: "spot@public.increase.depth.v3.api@BTCUSDT"` (MEXC). Each
venue gets its own bounded scan, and the token is matched whole (§3.2).

Both directions are needed, and it is much cheaper to build both now than to retrofit the second:

- **`token → instrumentId`** — the `SubscriptionIndex`, consulted on every inbound frame.
- **`instrumentId → (connection, topic)`** — consulted when a single instrument must be
  unsubscribed and resubscribed. This is *the* recovery mechanism for model-B venues, and it is
  also what universe-change churn (§8.3) needs to remove one instrument without disturbing the
  other 1023 on the connection.

*(Optional later micro-optimisation: hash the token bytes in place without materialising a
`String`. Not a day-one requirement — see §7.)*

### 5.4 Missing today, mandatory at N venues: a connect/subscribe **pacer**

Binance permits ~5 incoming messages/second per connection and rate-limits connection attempts
per IP. Booting ~25 MEXC connections + 3 Binance + N Bybit simultaneously at startup will trip
IP-level limits and produce a confusing cascade of reconnects. One pacer per **exchange** (not
per venue — the limit is IP/account-scoped), applied to both `connect()` and subscribe frames.

### 5.5 Binary / compressed frames

Some venues (Bitget, Huobi-lineage) deliver gzip/deflate-compressed binary frames. The transport
must support an `onMessage(ByteBuffer)` path with per-venue decompression, not only
`onMessage(String)`.

---

## 6. Request-budget and snapshot seams

`WeightGuard` encodes two Binance-specific assumptions: an `x-mbx-used-weight-1m` **response
header**, and a **wall-clock-minute** reset boundary. Bybit and MEXC provide neither — they are
plain requests/second caps that must be tracked locally.

```java
interface RequestBudget {
    long delayMillisRequired(int cost);
    void observe(HttpHeaders headers, long serverSentAtMs);
}
```

Two implementations cover the field:

- **`HeaderFeedbackBudget`** — Binance. Reuses the existing `WeightGuard` logic verbatim,
  including the anchor-to-server-`Date`-header trick and the stale-response discard rule.
- **`LocalTokenBucketBudget`** — everyone else. Purely local accounting.

One instance per venue, installed as that venue's `WebClient` filter (generalising
`WeightLimitFilter`).

### 6.1 The snapshot queue is generic and parameterised, not forked per exchange

`SnapshotFetchQueue`'s hardcoded `dispatchSpot()` / `dispatchFutures()` pair becomes **one
`SnapshotRequestQueue` class, instantiated once per model-A venue**. The mechanics are genuinely
universal: a bounded pending set, a scheduled drain, removal on response, re-enqueue on error,
and — critically — publishing the response into the ring buffer as `API_SNAPSHOT` rather than
writing the book from the HTTP thread.

What varies per venue is parameters, not structure: the REST client and URL template (behind a
`SnapshotSource`), the depth limit, the request cost, the queue capacity, the dispatch interval,
and the post-response settle delay (the 5-second wait described in `orderbook-sync-algorithm.md`
§7 — a *model-A* concern that lives in the model-A path, parameterised per venue, never in core).
Model-B and model-C venues simply never construct one.

The existing reasoning for a *small bounded* queue generalises cleanly and should be preserved:

1. It bounds how many books simultaneously hold diff buffers (memory during the startup ramp).
2. It caps the blast radius when a rate-limit delay stalls a dispatch cycle.

Only the numbers become per-venue. One thing does change structurally: the pending set is keyed by
**instrument id**, not symbol. `SnapshotFetchQueue` keys by `ob.symbol` today, which collides the
moment two venues list the same pair.

---

## 7. Storage — the actual scaling wall

This is where "we'll optimise later" needs pushing back on.

`TreeMap<Double, PriceLevelEntry>` costs roughly **90–110 bytes per level**: a boxed `Double`
key, a `TreeMap.Entry` node (five references plus colour), and the `PriceLevelEntry` object. At
3 000–5 000 books, with a ±10% band on a liquid pair easily holding 500–2 000 levels, that is
**5M+ live levels — on the order of 500 MB–1 GB of long-lived, pointer-chasing heap** in the maps
alone. On top of that sits the per-message `String` garbage the WebSocket library produces.

The recommendation is **not** "build the primitive book now." It is:

> **Put the seam in now, while there is exactly one implementation.**

Every consumer — strategies, classifier, monitoring — must go through `applyLevel` /
`forEachLevel` / `bestBid` / `bestAsk` rather than touching the maps. Today
`OrderBookClassifier` reaches straight into `getBids()`/`getAsks()`, and `snapshotBids()` /
`snapshotAsks()` hand out `TreeMap` copies.

Once five venues and the classifier depend on `TreeMap` semantics, swapping in parallel
`double[] price` / `double[] qty` / `long[] firstSeen` arrays becomes a multi-week rewrite instead
of a one-class change. `CLAUDE.md` already lists the primitive book under Future Work; multi-venue
scale is what converts it from *nice* to *necessary*.

The other standing allocation is the frame itself: the ring slot holds the whole message as a
`String`, and the WebSocket library allocated it before our code ran. Moving to reusable byte
buffers is a plausible later step and would subsume the routing-scan micro-optimisation of §5.3 —
both are measurement-driven, and neither is worth doing while the `String` allocation upstream
dominates. Keeping `CharSequence` rather than `String` in the strategy signature is what keeps
that door open at zero cost today.

---

## 8. Robustness properties that don't matter at one venue and are mandatory at five

### 8.1 Ring-buffer backpressure

`rb.next()` **blocks** when a shard's ring buffer is full. With five venues publishing into shared
`ProducerType.MULTI` buffers, one slow shard stalls a WebSocket reader thread → that connection's
TCP receive buffer fills → the exchange drops a connection carrying *hundreds* of streams. A local
CPU hiccup becomes a mass disconnect.

> Use `tryNext()` for `WS_MSG`; on failure, set `resetRequested` on the instrument's slot (§3.4)
> — recoverable and deterministic — rather than blocking the reader.
> Keep blocking `next()` for `API_SNAPSHOT`; those are rare and must not be lost.

Dropping a frame costs one resync. Blocking a reader costs a connection. The trade is clear, but
it must be *explicit* — silent corruption is the one unacceptable outcome. Note that the reader
thread does **not** touch the book or its context to signal this; it only sets the flag, and the
consumer acts on it when it next runs.

Shard count should also scale with cores (`min(availableProcessors - reserved, N)`, configurable)
rather than staying at the current default of 2, and must be a power of two (§2.3).

**Shard globally by instrument id, not per exchange.** Per-exchange shards would leave Binance
shards saturated and others idle. Processing is CPU-bound and bounded, so mixing venues in a shard
is safe and balances far better.

### 8.2 Staleness watchdog

A subscription that silently stops delivering is **invisible today** — the book sits `SYNCED`
with frozen data indefinitely. Track `lastMessageAtMs` per instrument, compare against the
venue's expected message interval, and at N× overdue set `resetRequested` and call the venue's
`RecoverySink`. Across five exchanges this *will* happen.

### 8.3 Real subscription churn

`BinanceWebSocketManager.onTickersRefreshed` currently logs *"dynamic re-subscription not yet
implemented"* — the 4-hourly refresh updates `TickerRegistry` but not live subscriptions. With
five venues the listing/delisting rate is 5×, and this becomes a genuine correctness problem
(delisted instruments hold books forever; new instruments are invisible until restart).

The pool needs `subscribe(Set<Instrument>)` / `unsubscribe(Set<Instrument>)` with per-connection
capacity accounting, driven by an `InstrumentUniverseChanged(added, removed)` event, using the
`instrumentId → (connection, topic)` direction of §5.3. Removal must also release the feed entry
and clear the slot — leaving the id permanently retired (§2.5).

**This is explicitly in scope for the foundation phase, not deferred.**

### 8.4 Per-venue health surface

At one exchange you eyeball logs; at five you need numbers. `/api/monitoring/orderbook` becomes
venue-dimensioned:

- books by state (`PENDING` / recovering / `SYNCED`) per venue
- resync rate per minute per venue, and recovery requests issued per venue
- WebSocket connections up/down, last reconnect, reconnect attempt counters
- snapshot queue depth and dispatch latency per venue
- ring-buffer utilisation and dropped-event counters per shard
- oldest `lastMessageAtMs` per venue

`OrderBookStore.logSyncCount` (the current `spot=… fut=…` line) generalises into this registry,
and resolves instrument names through the registry's cold-path `describe(id)` (§2.5).

---

## 9. Discovery

Per-venue `InstrumentSource.fetch()` returns a raw instrument list. A core
`InstrumentUniverseService` merges across venues, applies global policy (USDT-quoted, active
status, the exclusion set), assigns/reuses ids, allocates slots, and publishes
`InstrumentUniverseChanged(added, removed)` — replacing `TickersRefreshedEvent`.

### 9.1 One current rule has to go

`TickerService.buildTickerMap` requires an active USDT `PERPETUAL` futures contract and **drops
spot-only symbols entirely**. That is a Binance-era assumption and it directly contradicts the
requirement that a ticker existing only on Bitget still be tracked. Spot and futures universes
become **independent per venue**, with inclusion policy expressed in config rather than hardcoded
in a service.

The `EXCLUDED_SYMBOLS` set (stablecoin/gold pairs) stays, but moves to config and becomes
global-plus-per-exchange-overridable.

### 9.2 Carry `canonical` + `(base, quote)` from day one

Nothing consumes them in this phase. Cross-venue grouping ("show me BTC/USDT across all
exchanges") is obviously coming, it is free to populate at discovery time, and it is expensive to
backfill once ids and persistence exist.

### 9.3 Failure behaviour is preserved

The existing rule — a failed refresh logs and retains the previous universe, never crashes —
generalises per venue: one exchange's discovery failure must not disturb the others' instrument
sets, and must never be interpreted as a mass delisting.

---

## 10. Configuration and packaging

### 10.1 Config shape

```yaml
screener:
  exchanges:
    binance:
      enabled: true
      venues:
        spot:
          stream-url: wss://stream.binance.com/ws
          rest-url:   https://api.binance.com
          depth-stream: "@depth"
          max-streams-per-connection: 1024
          budget: { type: HEADER_FEEDBACK, header: x-mbx-used-weight-1m, threshold: 5800 }
          snapshot:
            mode: REST
            path: /api/v3/depth
            limit: 1000
            cost: 50
            queue-size: 10
            dispatch-ms: 6000
            settle-delay-ms: 5000
        futures:
          stream-url: wss://fstream.binance.com/ws
          rest-url:   https://fapi.binance.com
          depth-stream: "@depth@500ms"
          # ...
    bybit:
      enabled: false
      venues:
        linear:
          snapshot: { mode: IN_STREAM }   # no snapshot queue; recovery is resubscribe
          # ...
```

Bound as `Map<String, ExchangeProperties>` in `config/`, consistent with the existing
`@ConfigurationProperties` record convention.

The **`enabled` flag is not cosmetic** — it is the safe-rollout mechanism (ship an adapter dark,
enable in production independently) and the horizontal-split escape hatch (§10.3).

### 10.2 Package layout

```
exchange/                      ← core, exchange-agnostic
  Venue.java
  Instrument.java, InstrumentRegistry.java, InstrumentUniverseService.java
  spi/       InstrumentSource, StreamProtocol, DepthSyncStrategy, SnapshotSource,
             RecoverySink, RequestBudget, VenueAdapter
  stream/    ConnectionPool, StreamClient, SubscriptionPlanner, SubscriptionIndex, Pacer
  ingress/   DisruptorShardManager, DepthEvent (int instrumentId), DepthEventHandler
  book/      OrderBook, PriceLevelEntry, BookSyncContext, BookSlot, BookSlotTable
  recovery/  SnapshotRequestQueue, budget impls, StalenessWatchdog
  health/    SyncHealthRegistry

exchange/binance/              ← adapter: protocol, strategies, instrument source,
exchange/bybit/                  recovery sink, budget
exchange/mexc/
```

`VenueAdapter` is the single registration point: one Spring bean per venue exposing all of its SPI
pieces — protocol, strategy, recovery sink, instrument source, budget, optional snapshot source.
Core injects `List<VenueAdapter>` and wires the pipeline generically.

> **The test of success**: adding an exchange touches only a new `exchange/<name>/` package and a
> YAML block. If it requires editing anything under `exchange/` core, the abstraction leaked.

### 10.3 The horizontal-split escape hatch

If one JVM eventually cannot hold the full universe, running N processes with disjoint
`enabled` venue sets should Just Work. Nothing in this design resists it — **provided** venue
pipelines stay free of shared mutable state beyond the instrument registry. That is a property
worth defending in review, not an accident to be relied on.

---

## 11. What we deliberately do **not** build

| Rejected | Why |
|---|---|
| A normalised cross-exchange message DTO | Costs an allocation per message plus lowest-common-denominator semantics. Raw frame → venue-owned streaming parse → direct book mutation instead. **Explicitly agreed.** |
| A unified sequence-number model | Don't force `U`/`u`/`pu`/`seq`/`checksum` into one field set. Opaque per-venue `BookSyncContext`; core never inspects it. |
| A shared vocabulary of recovery actions | Recovery differs per venue in mechanism, cost and collaborator. A per-venue `RecoverySink` expresses it directly; an intermediate enum only adds a dispatch every caller must translate back. |
| Snapshot/delta as separate SPI entry points | Which payload is a snapshot is a venue-specific read. One entry point plus `EventType` provenance covers every model without leaving dead methods on adapters that have no second path. |
| `String symbol` anywhere on the hot path | Identity is an `int` from ingress to feed. Names are resolved only on cold paths (logging, monitoring, API) via the registry. |
| A `Market` enum stretched to inverse / options / dated futures | Start with `SPOT` + `LINEAR_PERP`. Inverse contracts are base-denominated, which breaks notional comparison downstream — a separate decision. |
| Per-exchange Disruptor shards | Creates hot/cold imbalance (Binance dominates). Shard globally by instrument id. |
| Handing a retired id to a different instrument | A stale in-flight event could be applied to the wrong instrument. Leave holes. |

---

## 12. Phasing

| Phase | Work | Exit criterion |
|---|---|---|
| **P1 — Identity** | `Venue`, `Instrument`, `InstrumentRegistry` with dense int ids; `BookSlot` table replacing `OrderBookStore`; slots populated at registration. Re-key shard routing, `DepthEvent`, feed store, classifier state, rule targets (+ Flyway migration + backfill), WS payload, frontend contract. **Binance remains the only implementation; behaviour identical.** | Sync counts and feed output byte-identical to pre-P1 for the same ticker set. |
| **P2 — Abstraction** | Extract the SPI. Move Binance sync into `BinanceSpotSyncStrategy` / `BinanceFuturesSyncStrategy` with `BookSyncContext`. Generic `ConnectionPool` + `StreamProtocol` + `RequestBudget` + per-venue `RecoverySink` + parameterised `SnapshotRequestQueue`. Config restructured to `screener.exchanges.*`. Still Binance-only. | Pure refactor; parity-testable against P1. |
| **P3 — Deferred debt** | Reset lane (§3.4) and `tryNext()` backpressure policy (§8.1). Dynamic subscribe/unsubscribe on universe change (§8.3), including the reverse routing index (§5.3). Staleness watchdog (§8.2). Venue-dimensioned health surface (§8.4). Storage accessor seam (§7). Connect/subscribe pacer (§5.4). | Binance runs at parity, plus a delisting/listing cycle observably handled without restart. |
| **P4 — Second venue: Bybit** | First real adapter under the SPI. | Bybit books reach `SYNCED` and stay there; no core edits required. |
| **P5 — Breadth** | MEXC, Bitget. Checksum-carrying deltas if a venue needs them. Binary/compressed frame support. | Near-mechanical if P4 was honest. |
| **P6 — Density** | Primitive-array order book, driven by measured heap/GC. | Heap per book materially reduced at equivalent depth. |

### 12.1 Why Bybit — not MEXC — must be venue #2

MEXC is easier. That is precisely the problem.

MEXC spot is **model A** — Binance-shaped. It would pass through a Binance-shaped abstraction *by
luck*, validating nothing. We would then build on a false foundation and discover the abstraction
was wrong at venue #3, after committing to it.

Bybit is **model B**: no REST snapshot in the flow, snapshot delivered in-stream, resubscribe as
the recovery action. It exercises the `RecoverySink` seam, the absent-snapshot-queue path, the
reverse routing index, the application-level heartbeat, the in-payload snapshot discriminator, and
topic-based routing — every axis on which the SPI could be wrong.

> **If the SPI survives Bybit, it survives everything else.**

---

## 13. Open questions for later phases

1. **Cross-venue asset view** — the client will eventually want "BTC/USDT across all exchanges."
   `canonical` supports it, but the feed/classification model for it is undesigned.
2. **Per-venue price-filter threshold** — should tier-2 exchanges use a tighter band than ±10% to
   save memory? Currently one global `price-filter-threshold`.
3. **Per-venue depth limits** — some venues cap WebSocket depth (e.g. top-50 topics). Does a
   truncated book need a distinct state, or is a narrower filter band equivalent?
4. **Frame representation** — when (not whether) to move ring slots from `String` to reusable
   byte buffers, which also subsumes the in-place routing-token hash. Measurement-driven after P5.
5. **Reset coalescing** — is a `volatile boolean` sufficient, or does a monotonic epoch counter
   earn its keep for diagnosing repeated resets under sustained backpressure?
6. **Discovery cadence** — the current 4-hour refresh is fine for one exchange; per-venue
   intervals may be warranted once churn multiplies.

---

## Summary of the load-bearing decisions

1. **Venue = `(exchange, market)`** is the adapter unit — not exchange. One set of adapter beans
   per venue, even when two venues share an implementation class.
2. **Dense `int` instrument id** replaces `String symbol` keys everywhere in the hot path — for
   disambiguation, but mostly for array-indexed lookup and allocation-free routing. The id is a
   runtime index; `(venue, nativeSymbol)` is the durable identity and the only thing persisted.
3. **`BookSlot` is the hot-path record** — book, sync context, strategy and reset flag together,
   in one array indexed by id, populated at registration rather than lazily.
4. **The strategy has one entry point** and owns parse-and-apply in a single streaming pass. No
   intermediate DTO, ever; no snapshot/delta split in the interface.
5. **Sequence cursors and diff buffers live in `BookSyncContext`, not `OrderBook`** — opaque to
   core.
6. **Recovery is a per-venue `RecoverySink`**, called by the strategy, the backpressure path and
   the watchdog alike; recovered payloads re-enter through existing lanes only.
7. **Out-of-band resets travel by a volatile flag on the slot**, never by touching consumer-owned
   state from another thread.
8. **Connection count is derived** from stream count and per-venue caps, and routing resolves in
   both directions.
9. **The storage accessor seam goes in now**, even though `TreeMap` stays for a while.
10. **Backpressure drops frames and requests recovery; it never blocks a reader thread.**
11. **P1's re-keying reaches into `analysis/`, `feed/`, `ws/`, and the frontend contract** — this
    is accepted, and it is why it happens exactly once, before any second exchange.
12. **Bybit is venue #2**, deliberately, because it is the hardest shape.
