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
  processes later (see §9.3), but that is not the near-term target.

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

Each venue supplies its own protocol, codec, sync strategy, snapshot source, and request budget.
Every existing `if (market == ...)` branch collapses into polymorphism. We stop paying for the
fiction that "MEXC" is one coherent thing.

Initial market coverage is deliberately narrow: **SPOT and LINEAR_PERP only**. Inverse contracts
are quantity-denominated in the base asset, which breaks notional comparison downstream — a
separate decision for a later phase (§10).

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
    int    id;              // dense, assigned at registration, never reused in-process
    Venue  venue;           // BINANCE_FUTURES, BYBIT_LINEAR, ...
    String nativeSymbol;    // exactly what the exchange expects ("BTCUSDT_UMCBL")
    String canonical;       // "BTC/USDT" — cross-venue grouping
    String base, quote;     // "BTC", "USDT"
    double tickSize;        // needed for checksum venues (§3.1 model D)
    double stepSize;
}
```

### 2.3 Why the dense `int id` matters (this is the real payoff, not just disambiguation)

- **Shard routing becomes `id & (shardCount - 1)`** — no hashing, no modulo, and perfectly even
  distribution instead of hash-luck. It also removes a latent bug: `Math.abs(Integer.MIN_VALUE)`
  is negative, so the current expression can throw `ArrayIndexOutOfBoundsException` for an
  unlucky symbol string.
- **Hot-path maps become arrays indexed by id.** `OrderBook[] books`, per-shard classifier state
  arrays. This deletes both the `ConcurrentHashMap` lookup *and* the
  `symbol + ":" + market.name()` string concatenation — which currently allocates on **every
  single depth message** inside `OrderBookStore.getOrCreate`.
- **`DepthEvent` shrinks** to `int instrumentId` + `EventType` + payload; `Market` and `String
  symbol` fields disappear.
- **Interning becomes unnecessary.** `BinanceStreamClient.onMessage` currently does
  `message.substring(...).intern()` per message. With a per-connection `topic → int` index
  (§4.3) the hot path never materialises a symbol `String` at all.

Ids are assigned at registration, dense, and **never reused within a process run**. Delistings
leave a hole in the array rather than recycling an id — recycling risks a stale in-flight event
being applied to the wrong instrument. Arrays are grown copy-on-write with generous headroom;
growth happens on the (rare, cold) discovery path.

### 2.4 Honest callout: this cannot be contained to the market-data module

Re-keying necessarily reaches into modules explicitly declared out of scope:

- `feed/OrderBookFeedStore` keys and `OrderBookUpdate` payload
- `analysis/UserClassificationContext` and the classifier's per-symbol state maps
- `analysis/rule/` persistence — a **Flyway migration** adding an exchange/venue column to the
  rule-target table, plus a backfill setting existing rows to Binance
- The client WebSocket payload shape, and therefore **the frontend contract**
  (`.claude/docs/for-frontend/`)

None of this is *hard*, but it is wide. It should happen **once, deliberately, before any second
exchange exists** — never incrementally per-exchange. This is the single strongest argument for
the phased ordering in §11.

---

## 3. The synchronisation seam — where the real difficulty is

### 3.1 Taxonomy the abstraction must cover

The SPI shape is determined by the range of sync models in the wild, not by Binance:

| Model | Mechanism | Recovery action | Examples |
|---|---|---|---|
| **A** | REST snapshot + buffered deltas, sequence-range validated | fetch REST snapshot | Binance spot (`U`/`u`), Binance futures (`pu`), MEXC spot |
| **B** | Snapshot arrives **in-stream** as the first frame, then deltas | **resubscribe the topic** | Bybit (`type: snapshot\|delta`), Bitget |
| **C** | Full top-N pushed every tick, no sequencing at all | none — the next frame heals it | `books5`-style / `@depth20@100ms` streams |
| **D** | Deltas + periodic CRC32 checksum over top-N | resubscribe or refetch | OKX, Bitget, Kraken |

**The generalisation that matters: recovery is not always "fetch a REST snapshot."**

`OrderBookProcessor.process` currently hardcodes `snapshotFetchQueue.enqueue(ob)` as the *only*
response to a desync. Bybit has no REST snapshot in the flow at all — recovery is
unsubscribe/resubscribe on the connection. Model C never desyncs. So:

```java
enum RecoveryAction { NONE, FETCH_REST_SNAPSHOT, RESUBSCRIBE, AWAIT_STREAM_SNAPSHOT }
```

returned by the strategy, executed by a `RecoveryCoordinator` that knows how each venue satisfies
each action.

### 3.2 What `OrderBook` currently conflates

`binance/orderbook/OrderBook.java` today does five things. Only two are universal:

| Concern | Universal? |
|---|---|
| TreeMap storage of bid/ask levels, `firstSeenMillis` lifecycle | **yes** |
| Mid-price computation + `±priceFilterThreshold` sweep (`computeDistance`) | **yes** |
| JSON parsing (`applyLevelsDirectly`, `parseSnapshotEvent`, `parseUField`) | no — venue-specific |
| Binance sequence rules (`U`/`u`/`pu`, `lastUpdateId`) | no — venue-specific |
| Diff buffering (`diffBuffer`, `MAX_BUFFER_SIZE`) | no — models B and C never buffer |

### 3.3 Proposed SPI

```java
/** Stateless singleton, one per venue. */
interface DepthSyncStrategy {
    BookSyncContext newContext();                 // per-book cursor state, allocated once
    Verdict onFrame(OrderBook book, BookSyncContext ctx, CharSequence raw);
    Verdict onSnapshot(OrderBook book, BookSyncContext ctx, CharSequence raw); // model A only
    default boolean verify(OrderBook book, long expectedChecksum) { return true; } // model D
}

record Verdict(OrderBookResult result, RecoveryAction action) { }
```

Two deliberate choices, both load-bearing:

**(a) The strategy owns parsing *and* application in a single streaming pass.**
The obvious OO move — a normalised `DepthUpdate` DTO between codec and book — is explicitly
rejected. It costs either an allocation per message or a mutable holder plus a second iteration,
across ~50 000 messages/second. Instead, `OrderBook` exposes `applyLevel(side, price, qty)` and
the strategy calls it *from inside* its `JsonParser` loop — exactly the shape
`applyLevelsDirectly` already has today. Zero-allocation preserved; the seam stays clean.

**(b) `lastUpdateId` moves out of `OrderBook` into `BookSyncContext`.**
Sequence cursors are venue-specific: Binance needs `lastUpdateId`; Bybit needs a seq plus a
"have I seen the snapshot yet" flag; OKX needs the last checksum. Core `OrderBook` must know
nothing about any of them. The diff buffer moves there too, since models B and C don't have one.

`BookSyncContext` is created once per book (`strategy.newContext()`), so it is allocation-free at
steady state. Core never inspects its contents — it is opaque.

### 3.4 What stays in core `OrderBook`

Bids/asks storage, `applyLevel`, `clear`, `bestBid`/`bestAsk`, the mid-price filter sweep,
`firstSeenMillis`/`distance` bookkeeping, and the three-state lifecycle (`PENDING` →
`SNAPSHOT_REQUESTED`/`RECOVERING` → `SYNCED`). That set is genuinely universal across all four
models.

---

## 4. Transport seam

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

Three concrete problems this fixes, each of which will bite otherwise:

### 4.1 Connection count must be **derived**, not configured

`connection-count-spot: 2` / `connection-count-futures: 3` are hand-picked today, and
`orderbook-sync-algorithm.md` §2 explicitly notes *"There's no per-connection stream-count cap
enforced in code."* That is survivable at Binance's ~1024-stream ceiling. It is not survivable at
MEXC's per-connection subscription cap of roughly **30** — 700 instruments needs ~25 connections,
not 3.

> `connections = clamp(ceil(streamCount / maxStreamsPerConnection), configuredMin, configuredMax)`

The configured value becomes a floor/override, not the authority.

### 4.2 Heartbeats are not uniform

`setConnectionLostTimeout(0)` + periodic `sendPing()` is a Binance-shaped solution:

- **Binance** — protocol-level WebSocket ping.
- **Bybit** — application-level `{"op":"ping"}` roughly every 20s, or the connection is dropped.
- **MEXC** — application-level `{"method":"PING"}`.

`Heartbeat` becomes a per-venue strategy (interval + frame producer), not a fixed behaviour.

### 4.3 Routing-key extraction differs per venue

`message.charAt(2) == 'r'` (subscribe-ack discrimination) and `indexOf("\"s\":\"")` (symbol
extraction) are Binance-shaped. Elsewhere:

- **Bybit** — inside `topic: "orderbook.50.BTCUSDT"`
- **MEXC** — inside `c: "spot@public.increase.depth.v3.api@BTCUSDT"`

Each venue gets its own fast substring scan. The result is resolved against a **per-connection
`SubscriptionIndex`** (`topic string → int instrumentId`), populated at subscribe time — so the
hot path yields an `int` and never interns a `String`.

*(Optional later micro-optimisation: hash the topic bytes in place without materialising a
`String` at all. Not a day-one requirement.)*

### 4.4 Missing today, mandatory at N venues: a connect/subscribe **pacer**

Binance permits ~5 incoming messages/second per connection and rate-limits connection attempts
per IP. Booting ~25 MEXC connections + 3 Binance + N Bybit simultaneously at startup will trip
IP-level limits and produce a confusing cascade of reconnects. One pacer per **exchange** (not
per venue — the limit is IP/account-scoped), applied to both `connect()` and subscribe frames.

### 4.5 Binary / compressed frames

Some venues (Bitget, Huobi-lineage) deliver gzip/deflate-compressed binary frames. The transport
must support an `onMessage(ByteBuffer)` path with per-venue decompression, not only
`onMessage(String)`.

---

## 5. Request-budget seam

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

**Snapshot queue follows the same generalisation.** `SnapshotFetchQueue`'s hardcoded
`dispatchSpot()` / `dispatchFutures()` pair becomes one queue **per venue**, each with its own
capacity and dispatch rate from config — and model-B/C venues simply don't register one.

The existing reasoning for a *small bounded* queue generalises cleanly and should be preserved
verbatim in the new design (see `orderbook-sync-algorithm.md` §6):

1. It bounds how many books simultaneously hold diff buffers (memory during the startup ramp).
2. It caps the blast radius when a rate-limit delay stalls a dispatch cycle.

Only the numbers become per-venue.

The 5-second post-response delay (`orderbook-sync-algorithm.md` §7) is likewise a *model-A*
concern and should live in the model-A snapshot path, parameterised per venue — not in core.

---

## 6. Storage — the actual scaling wall

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

Also worth measuring early: the ring slot currently holds the whole frame as a `String`. Moving
to reusable byte buffers is a plausible later step — not a day-one requirement, but the
`CharSequence` in the `DepthSyncStrategy` signature (rather than `String`) keeps the door open.

---

## 7. Robustness properties that don't matter at one venue and are mandatory at five

### 7.1 Ring-buffer backpressure

`rb.next()` **blocks** when a shard's ring buffer is full. With five venues publishing into shared
`ProducerType.MULTI` buffers, one slow shard stalls a WebSocket reader thread → that connection's
TCP receive buffer fills → the exchange drops a connection carrying *hundreds* of streams. A local
CPU hiccup becomes a mass disconnect.

> Use `tryNext()` for `DIFF` events; on failure, explicitly mark the book desynced — recoverable
> and deterministic — rather than blocking the reader.
> Keep blocking `next()` for `SNAPSHOT` events; they are rare and must not be lost.

Dropping a diff costs one resync. Blocking a reader costs a connection. The trade is clear, but
it must be *explicit* — silent corruption is the one unacceptable outcome.

Shard count should also scale with cores (`min(availableProcessors - reserved, N)`, configurable)
rather than staying at the current default of 2.

**Shard globally by instrument id, not per exchange.** Per-exchange shards would leave Binance
shards saturated and others idle. Processing is CPU-bound and bounded, so mixing venues in a shard
is safe and balances far better.

### 7.2 Staleness watchdog

A subscription that silently stops delivering is **invisible today** — the book sits `SYNCED`
with frozen data indefinitely. Track `lastMessageAtMs` per instrument, compare against the
venue's expected message interval, and trigger recovery at N× overdue. Across five exchanges this
*will* happen.

### 7.3 Real subscription churn

`BinanceWebSocketManager.onTickersRefreshed` currently logs *"dynamic re-subscription not yet
implemented"* — the 4-hourly refresh updates `TickerRegistry` but not live subscriptions. With
five venues the listing/delisting rate is 5×, and this becomes a genuine correctness problem
(delisted instruments hold books forever; new instruments are invisible until restart).

The pool needs `subscribe(Set<Instrument>)` / `unsubscribe(Set<Instrument>)` with per-connection
capacity accounting, driven by an `InstrumentUniverseChanged(added, removed)` event. Removal must
also drop the book, release the feed entry, and free the instrument slot.

**This is explicitly in scope for the foundation phase, not deferred.**

### 7.4 Per-venue health surface

At one exchange you eyeball logs; at five you need numbers. `/api/monitoring/orderbook` becomes
venue-dimensioned:

- books by state (`PENDING` / recovering / `SYNCED`) per venue
- resync rate per minute per venue
- WebSocket connections up/down, last reconnect, reconnect attempt counters
- snapshot queue depth and dispatch latency per venue
- ring-buffer utilisation and dropped-event counters per shard
- oldest `lastMessageAtMs` per venue

`OrderBookStore.logSyncCount` (the current `spot=… fut=…` line) generalises into this registry.

---

## 8. Discovery

Per-venue `InstrumentSource.fetch()` returns a raw instrument list. A core
`InstrumentUniverseService` merges across venues, applies global policy (USDT-quoted, active
status, the exclusion set), assigns/reuses ids, and publishes
`InstrumentUniverseChanged(added, removed)` — replacing `TickersRefreshedEvent`.

### 8.1 One current rule has to go

`TickerService.buildTickerMap` requires an active USDT `PERPETUAL` futures contract and **drops
spot-only symbols entirely**. That is a Binance-era assumption and it directly contradicts the
requirement that a ticker existing only on Bitget still be tracked. Spot and futures universes
become **independent per venue**, with inclusion policy expressed in config rather than hardcoded
in a service.

The `EXCLUDED_SYMBOLS` set (stablecoin/gold pairs) stays, but moves to config and becomes
global-plus-per-exchange-overridable.

### 8.2 Carry `canonical` + `(base, quote)` from day one

Nothing consumes them in this phase. Cross-venue grouping ("show me BTC/USDT across all
exchanges") is obviously coming, it is free to populate at discovery time, and it is expensive to
backfill once ids and persistence exist.

### 8.3 Failure behaviour is preserved

The existing rule — a failed refresh logs and retains the previous universe, never crashes —
generalises per venue: one exchange's discovery failure must not disturb the others' instrument
sets.

---

## 9. Configuration and packaging

### 9.1 Config shape

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
          snapshot: { mode: IN_STREAM }
          # ...
```

Bound as `Map<String, ExchangeProperties>` in `config/`, consistent with the existing
`@ConfigurationProperties` record convention.

The **`enabled` flag is not cosmetic** — it is the safe-rollout mechanism (ship an adapter dark,
enable in production independently) and the horizontal-split escape hatch (§9.3).

### 9.2 Package layout

```
exchange/                      ← core, exchange-agnostic
  Venue.java
  Instrument.java, InstrumentRegistry.java, InstrumentUniverseService.java
  spi/       InstrumentSource, StreamProtocol, DepthSyncStrategy, SnapshotSource,
             RequestBudget, VenueAdapter
  stream/    ConnectionPool, StreamClient, SubscriptionPlanner, SubscriptionIndex, Pacer
  ingress/   DisruptorShardManager, DepthEvent (int instrumentId), DepthEventHandler
  book/      OrderBook, OrderBookStore, PriceLevelEntry, BookSyncContext
  recovery/  RecoveryCoordinator, SnapshotRequestQueue, budget impls
  health/    SyncHealthRegistry

exchange/binance/              ← adapter: protocol, strategies, instrument source, budget
exchange/bybit/                ← adapter
exchange/mexc/                 ← adapter
```

`VenueAdapter` is the single registration point: one Spring bean per venue exposing all of its SPI
pieces. Core injects `List<VenueAdapter>` and wires the pipeline generically.

> **The test of success**: adding an exchange touches only a new `exchange/<name>/` package and a
> YAML block. If it requires editing anything under `exchange/` core, the abstraction leaked.

### 9.3 The horizontal-split escape hatch

If one JVM eventually cannot hold the full universe, running N processes with disjoint
`enabled` venue sets should Just Work. Nothing in this design resists it — **provided** venue
pipelines stay free of shared mutable state beyond the instrument registry. That is a property
worth defending in review, not an accident to be relied on.

---

## 10. What we deliberately do **not** build

| Rejected | Why |
|---|---|
| A normalised cross-exchange message DTO | Costs an allocation per message plus lowest-common-denominator semantics. Raw frame → venue-owned streaming parse → direct book mutation instead. **Explicitly agreed.** |
| A unified sequence-number model | Don't force `U`/`u`/`pu`/`seq`/`checksum` into one field set. Opaque per-venue `BookSyncContext`; core never inspects it. |
| A `Market` enum stretched to inverse / options / dated futures | Start with `SPOT` + `LINEAR_PERP`. Inverse contracts are base-denominated, which breaks notional comparison downstream — a separate decision. |
| Per-exchange Disruptor shards | Creates hot/cold imbalance (Binance dominates). Shard globally by instrument id. |
| Id recycling on delisting | A stale in-flight event could be applied to the wrong instrument. Leave holes. |

---

## 11. Phasing

| Phase | Work | Exit criterion |
|---|---|---|
| **P0 — Identity** | `Venue`, `Instrument`, `InstrumentRegistry` with dense int ids. Re-key `OrderBookStore`, shard routing, `DepthEvent`, feed store, classifier state, rule targets (+ Flyway migration + backfill), WS payload, frontend contract. **Binance remains the only implementation; behaviour identical.** | Sync counts and feed output byte-identical to pre-P0 for the same ticker set. |
| **P1 — Abstraction** | Extract the SPI. Move Binance sync into `BinanceSpotSyncStrategy` / `BinanceFuturesSyncStrategy`. Generic `ConnectionPool` + `StreamProtocol` + `RequestBudget` + `RecoveryCoordinator` + per-venue snapshot queues. Config restructured to `screener.exchanges.*`. Still Binance-only. | Pure refactor; parity-testable against P0. |
| **P2 — Deferred debt** | Dynamic subscribe/unsubscribe on universe change (§7.3). Staleness watchdog (§7.2). `tryNext()` backpressure policy (§7.1). Venue-dimensioned health surface (§7.4). Storage accessor seam (§6). Connect/subscribe pacer (§4.4). | Binance runs at parity, plus a delisting/listing cycle observably handled without restart. |
| **P3 — Second venue: Bybit** | First real adapter under the SPI. | Bybit books reach `SYNCED` and stay there; no core edits required. |
| **P4 — Breadth** | MEXC, Bitget. Checksum (`verify`) hook if a venue needs it. Binary/compressed frame support. | Near-mechanical if P3 was honest. |
| **P5 — Density** | Primitive-array order book, driven by measured heap/GC. | Heap per book materially reduced at equivalent depth. |

### 11.1 Why Bybit — not MEXC — must be venue #2

MEXC is easier. That is precisely the problem.

MEXC spot is **model A** — Binance-shaped. It would pass through a Binance-shaped abstraction *by
luck*, validating nothing. We would then build on a false foundation and discover the abstraction
was wrong at venue #3, after committing to it.

Bybit is **model B**: no REST snapshot in the flow, snapshot delivered in-stream, resubscribe as
the recovery action. It exercises `RecoveryAction`, the absent-snapshot-queue path, the
application-level heartbeat, and the topic-based routing key — every axis on which the SPI could
be wrong.

> **If the SPI survives Bybit, it survives everything else.**

---

## 12. Open questions for later phases

1. **Cross-venue asset view** — the client will eventually want "BTC/USDT across all exchanges."
   `canonical` supports it, but the feed/classification model for it is undesigned.
2. **Per-venue price-filter threshold** — should tier-2 exchanges use a tighter band than ±10% to
   save memory? Currently one global `price-filter-threshold`.
3. **Per-venue depth limits** — some venues cap WebSocket depth (e.g. top-50 topics). Does a
   truncated book need a distinct state, or is a narrower filter band equivalent?
4. **Frame representation** — when (not whether) to move ring slots from `String` to reusable
   byte buffers. Should be measurement-driven after P4.
5. **Discovery cadence** — the current 4-hour refresh is fine for one exchange; per-venue
   intervals may be warranted once churn multiplies.

---

## Summary of the load-bearing decisions

1. **Venue = `(exchange, market)`** is the adapter unit — not exchange.
2. **Dense `int` instrument id** replaces `String symbol` keys everywhere in the hot path — for
   disambiguation, but mostly for array-indexed lookup and allocation-free routing.
3. **The strategy owns parse-and-apply in one streaming pass.** No intermediate DTO, ever.
4. **Sequence cursors and diff buffers live in `BookSyncContext`, not `OrderBook`.**
5. **Recovery is a first-class enum**, not a hardcoded "fetch REST snapshot."
6. **Connection count is derived** from stream count and per-venue caps.
7. **The storage accessor seam goes in now**, even though `TreeMap` stays for a while.
8. **Backpressure drops diffs and marks desync; it never blocks a reader thread.**
9. **P0's re-keying reaches into `analysis/`, `feed/`, `ws/`, and the frontend contract** — this is
   accepted, and it is why it happens exactly once, before any second exchange.
10. **Bybit is venue #2**, deliberately, because it is the hardest shape.
