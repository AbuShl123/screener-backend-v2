# Multi-Exchange Migration — State of Play

**Last updated**: 2026-09-08, working tree on top of commit `fb5f15d`, branch
`feature/multi-exchange`.

**What this file is**: the single accurate record of where the multi-exchange migration stands —
what is built, how it is built, and what is left before a second exchange can be added.

**Scope reminder**: the target is *"every tracked instrument on every enabled exchange has an
accurate, in-sync local order book, and adding an exchange is a new package plus a YAML block."*
Classification, per-user rules, the feed and the client WebSocket server are out of scope as
features — they are only touched where identity re-keying forced it.

Where this document and the code disagree, the code is right.

**Related reading**

| File | Status |
|---|---|
| `.claude/plans/multi-exchange-architecture-vision.md` | The north star. Still accurate as design intent. |
| `.claude/docs/orderbook-sync-algorithm.md` | **Stale.** Describes the pre-P1 Binance-only pipeline. §4 below replaces its sync chapters. |
| `.claude/plans/p1-*.md`, `.claude/plans/p2-*.md` | Historical. Delivered or superseded; read them for reasoning, not for current shape. |
| `.claude/docs/pipeline-benchmark-2026-09-08.md` | Measured baseline: a 2.5h run including a real mass-disconnect event. Sizes recovery throughput, heap and GC. |

---

## 1. Phase status

| Phase | Scope | Status |
|---|---|---|
| **P1 — Identity** | `Venue`, `Instrument`, `InstrumentRegistry`, dense int ids, `BookSlotTable` replacing the string-keyed store, re-keyed `DepthEvent` / feed / classifier | **Done** |
| **P2 step 1** | Package move to `exchange/{stream,ingress,book,recovery}` | **Done** |
| **P2 step 1b** | `EventType` → `WS_MSG` / `REST_MSG` (provenance, not semantics) | **Done** |
| **P2 step 2** | Sync SPI (`DepthSyncStrategy` / `BookSyncContext` / `RecoverySink`); Binance spot + futures strategies; `OrderBook` reduced to pure storage; `SyncStrategyRegistry` + `BinanceAdapterConfig` wiring; sync test suite | **Done — builds green, verified live** |
| **P2 step 3** | Per-venue `SnapshotRequestQueue` + `SnapshotSource` abstraction | Not started |
| **P2 step 4** | `RequestBudget` (`HeaderFeedbackBudget` / `LocalTokenBucketBudget`); WebClients keyed by venue | Not started |
| **P2 step 5** | `StreamProtocol`, `Heartbeat`, generic `ConnectionPool` | Not started |
| **P2 step 6** | Config consolidation — fold `screener.orderbook.*` / `screener.websocket.*` into `screener.exchanges.*` | Partially pre-done (`ExchangesProperties` exists and carries per-venue stream/REST/connection config) |
| **P3** | Reset lane, `tryNext()` backpressure, dynamic subscribe/unsubscribe, staleness watchdog, venue health surface, read-side storage seam | Not started |
| **P4** | Bybit — the first real second venue | Not started |
| **P5–P6** | MEXC/Bitget breadth; primitive-array book | Not started |

**Live verification of the current tree**: run against real Binance, **353 spot + 525 futures books
reach `SYNCED` in roughly 6 minutes** and hold. That is the designed ramp — the snapshot queue is
10 deep per market, dispatching every 6s with a 5s settle delay per response — not a stall.

---

## 2. Identity — done and stable

- **`Venue = (Exchange, Market)`** is the adapter unit: `Venue.BINANCE_SPOT`, `Venue.BINANCE_FUTURES`.
  `Market` remains the persistence- and API-facing type; `Venue.of(exchange, market)` bridges the two.
  This is what let identity land without a Flyway migration or a frontend contract change.
- **`Instrument`** is a record carrying `id`, `venue`, `nativeSymbol`, `base`, `quote`, `canonical`,
  plus two precomputed strings: `feedKey` (`SYMBOL:MARKET`, byte-identical to the old key because the
  per-user rule map is keyed on it) and `logName` (`VENUE/SYMBOL`, log lines only).
- **`InstrumentRegistry`** hands out dense ids: stable across refreshes, never transferred to a
  different instrument (a delisting leaves a hole), never persisted. `describe(int)` is the cold-path
  name lookup that keeps logs readable without putting `String symbol` back on the hot path.
- **`BookSlotTable`** is a copy-on-write `BookSlot[]` indexed by id. Slots are allocated at discovery,
  never lazily. Publication order is an invariant: **register all → allocate → publish → fire
  `InstrumentUniverseChangedEvent` → transport subscribes.** A subscribe frame going out first could
  let a reader resolve an id past the end of the published array.
- **Shard routing is `id & (shardCount - 1)`**, with `shard-count` validated as a power of two at
  startup in `DisruptorShardManager.start`. Both producers — the WebSocket reader thread and the
  snapshot queue's Reactor thread — route through that one expression.
- **`InstrumentUniverseService`** replaced `TickerService` and is now an exchange-agnostic merger
  over `InstrumentSource` beans (`exchange/spi/`). A source declares the venues it is authoritative
  for and returns all of them from one `fetch()`; core validates claims at startup (one source per
  venue, one exchange per source), skips sources whose venues are disabled
  (`ExchangesProperties.isEnabled`), fetches sources concurrently under
  `screener.discovery.source-timeout`, and owns id order, the diff and publication order. Failure is
  **isolated per source**: a failed, timed-out, mis-keyed or newly-empty source keeps its venues'
  previous universe and contributes zero removals. Binance ships one `BinanceInstrumentSource`
  spanning spot + futures (spot inclusion needs the futures list), with its policy in the
  adapter-owned `BinanceDiscoveryProperties` bound to `screener.exchanges.binance.discovery`
  (`quote-asset`, `futures-contract-type`, `spot-requires-futures`, `excluded-symbols`). See
  `.claude/plans/universe-discovery-generalization.md`.
- **`SubscriptionIndex`** is a per-connection `nativeSymbol → instrumentId` map. It is what removed
  `String.intern()` from the reader callback.

---

## 3. The SPI and its wiring

Four types in `exchange/spi/`, all deliberately tiny:

```java
public interface DepthSyncStrategy {
    BookSyncContext newContext();                   // cold: once per book, at slot allocation
    void onEvent(BookSlot slot, DepthEvent event);  // hot: every event, on the shard's thread
}

public interface BookSyncContext { }                // marker; opaque to core, never inspected

public interface RecoverySink {
    boolean requestRecovery(BookSlot slot);         // false = venue refused (queue at capacity)
}

public record VenueStrategyBinding(Venue venue, DepthSyncStrategy strategy) { }
```

`BookSlot` is `record BookSlot(Instrument instrument, OrderBook book, DepthSyncStrategy strategy,
BookSyncContext ctx)` — one array load and one dereference gives the consumer everything it needs.

**How an adapter registers itself.** `SyncStrategyRegistry` (core) collects every
`VenueStrategyBinding` bean on the classpath into an `EnumMap<Venue, DepthSyncStrategy>` and throws
on a duplicate binding or an unmapped venue — both are startup bugs, not runtime conditions.
`BinanceAdapterConfig` (adapter package) contributes the two Binance bindings and constructs the
strategies itself; the strategy classes are deliberately **not** `@Component`s, so core never names
an adapter class. `BookSlotTable.allocate` resolves the strategy once per book:

```java
DepthSyncStrategy strategy = strategyRegistry.forVenue(instrument.venue());
staging[id] = new BookSlot(instrument, new OrderBook(threshold), strategy, strategy.newContext());
```

Strategy selection is therefore a cold-path decision. The consumer never walks
`id → Instrument → Venue → strategy`; it reads `slot.strategy()`.

**Consumer loop** (`DepthEventHandler`), uniform across venues:

```java
BookSlot slot = slots.get(event.instrumentId);
if (slot == null) { noteMissingSlot(...); event.clear(); return; }   // rate-limited counter
slot.strategy().onEvent(slot, event);
classificationModule.process(slot.instrument(), slot.book());        // gates on state internally
event.clear();
```

**Bean cycle.** `BookSlotTable → SyncStrategyRegistry → VenueStrategyBinding beans
(BinanceAdapterConfig) → SnapshotFetchQueue → DisruptorShardManager → BookSlotTable`, closed by the
`@Lazy` on `SnapshotFetchQueue`'s `DisruptorShardManager`. The comment there names the current cycle;
removing that `@Lazy` fails the context at startup.

**`OrderBook` is now pure storage.** It holds `volatile OrderBookState state`, `filterThreshold` and
the two `TreeMap`s, exposing `applyLevel(isBid, price, qty, millis)`, `clearLevels()`,
`computeDistance()`, `markPending/markRecovering/markSynced()` and `getBids()/getAsks()`. No venue,
no `lastUpdateId`, no diff buffer, no JSON parsing, no knowledge of Binance. `OrderBookState` is
`PENDING → RECOVERING → SYNCED`.

**`SnapshotFetchQueue implements RecoverySink`.** Two maps keyed by instrument id, `@Scheduled`
drain, 5s settle delay, response published into the ring buffer as `REST_MSG` rather than written to
the book from the HTTP thread. Still Binance-shaped (hardcoded `/api/v3/depth` and `/fapi/v1/depth`,
`dispatchSpot`/`dispatchFutures` pair) — that is P2 step 3.

---

## 4. The Binance sync strategy, in detail

This is the part most worth understanding before touching anything.

> **The algorithm was rewritten, not ported.** The strategies are *not* byte-identical to the
> pre-refactor `OrderBook` code. Snapshot handling and the `check()` predicates were tightened and
> simplified along the way. The flow remains correct and follows Binance's official documentation —
> the changes made it more logical and more accurate, and each is called out below. The one
> deliberate departure *from the docs* is the spot `IGNORE` comparison (§4.5), which is documented
> in code and must not be "corrected" away.

### 4.1 Files and shape

| File | Role |
|---|---|
| `binance/BinanceSyncContext.java` | Per-book sync state. One instance per book, from `newContext()`. |
| `binance/BinanceDepthSyncStrategy.java` | Abstract base. Dispatch, parsing, level application, snapshot handling, recovery — everything venue-common. |
| `binance/BinanceSpotSyncStrategy.java` | Implements `check()` for spot. |
| `binance/BinanceFuturesSyncStrategy.java` | Implements `check()` for futures. |

The subclasses contain **only** sequence validation. Every byte of parsing, buffering, snapshot
application and recovery lives once, in the base. Context reset is a single shared
`BinanceSyncContext.reset()` — not an abstract hook — so a future field cannot be reset on one venue
only.

### 4.2 The context

```java
static final int MAX_BUFFER_SIZE = 500;
final ArrayDeque<String> diffBuffer;   // raw diff JSON, buffered only while RECOVERING
long lastUpdateId;                     // -1 = no sync point established
boolean syncPointFound;                // futures-only; false = still hunting the first valid event
```

`bufferDiff(diff, logName)` appends, returning `false` when full — that is the overflow signal. It
takes `logName` because the context has no identity of its own and an unattributed overflow line is
useless.

`lastUpdateId` means slightly different things on the two venues, deliberately:

- **spot** — the `u` of the last applied event, or the snapshot's `lastUpdateId` if none applied yet.
- **futures** — the same, but only meaningful together with `syncPointFound`. While
  `syncPointFound == false` it holds the snapshot's `lastUpdateId` and is being *searched for* inside
  incoming `[U, u]` ranges. Once true it is the strict predecessor cursor the next event's `pu` must
  equal.

### 4.3 Dispatch — `onEvent`

One flat if-chain at the top of `BinanceDepthSyncStrategy.onEvent`, and **the only place that calls
`recover()`**.

| Book state | Event | Action |
|---|---|---|
| *not* `RECOVERING` | `REST_MSG` | **Dropped silently.** A late, duplicate or superseded snapshot must never touch a book that is not waiting for one. |
| `RECOVERING` | `REST_MSG` | `handleSnapshot()`. Success → `markSynced()`. Failure → `recover()`. |
| `SYNCED` | `WS_MSG` | `handleDiff()`. Failure → `recover()`. |
| `RECOVERING` | `WS_MSG` | `ctx.bufferDiff()`. Buffer full → `recover()`. |
| `PENDING` | `WS_MSG` | `requestRecovery()`. Accepted → `markRecovering()` **and buffer the triggering diff**. Refused → drop the diff, stay `PENDING`, retry on the next one. |

Two properties are load-bearing:

1. **At most one recovery request per event.** Every helper returns `boolean` and none of them
   touches the sink.
2. **`PENDING` retries on every diff.** With queue size 10 against ~880 books, refusal is the
   *normal* startup path. A book that could not get into the queue must re-ask on its next diff or it
   parks forever. This is what makes the 6-minute ramp work rather than strand books.

### 4.4 `check()` — the one thing each venue implements

```java
protected abstract CheckResult check(JsonParser p, BinanceSyncContext ctx, String logName);
protected enum CheckResult { OK, IGNORE, DE_SYNCED }
```

- **`OK`** — valid successor; `ctx.lastUpdateId` has been advanced; apply it.
- **`IGNORE`** — the event predates the book's cursor. Drop it, stay synced, apply nothing.
- **`DE_SYNCED`** — a real gap. `handleDiff` returns `false` and `onEvent` recovers.

A boolean could not express `IGNORE`, which is why the SPI's one abstract method returns a tri-state.

**Parser hand-off contract** (documented in javadoc on the abstract method): `check()` is called with
the parser on the diff object's `START_OBJECT`. It reads only the sequence fields it needs, breaking
out as soon as it has them, and **must leave the parser before the `b`/`a` fields, without consuming
`END_OBJECT`**. `applyDiff` resumes from exactly that position. This works because Binance guarantees
`U`, `u` and `pu` precede `b` and `a`. An implementation that over-consumes leaves `applyDiff` with
no levels to read: nothing throws, and the book drifts silently while still reporting `SYNCED`. No
other layer can detect this, so a test pins it.

Both implementations throw `IllegalStateException` if the sequence fields are absent before
`END_OBJECT` — a loud failure if Binance ever reorders its frame fields, rather than silent drift.

### 4.5 Spot validation

```java
if (u < ctx.lastUpdateId)            return IGNORE;
else if (ctx.lastUpdateId + 1 >= U) { ctx.lastUpdateId = u; return OK; }
else                                 return DE_SYNCED;
```

Combined, `OK` holds exactly when `u >= lastUpdateId` **and** `U <= lastUpdateId + 1`, i.e.
`lastUpdateId ∈ [U-1, u]`.

**One predicate covers both roles** — finding the first post-snapshot event and validating every
event after it. The old code used the snapshot window `[U, u]` to find the first event and then
switched to a strict `U == lastUpdateId + 1`. Binance's docs give the same rule for both; the strict
form is merely what is *normally* true on spot, not what is required.

**Why accepting an overlapping event is safe.** `U < lastUpdateId + 1` means the event's range
overlaps ground already applied. Re-applying cannot corrupt the book, because Binance depth levels
carry **absolute quantities, not deltas**, and the `IGNORE` guard means any accepted event has
`u >= lastUpdateId` — its values are at least as new as what is in the book. Last write wins, and the
last write is the newest.

**The strict `<` in the `IGNORE` test is deliberate and contrary to Binance's spot docs**, which say
to discard `u <= lastUpdateId`. In practice the snapshot's `lastUpdateId` very often equals the `u`
of a buffered event, and under the documented rule spot books never sync at all. The explanatory
comment lives in `BinanceSpotSyncStrategy`.

### 4.6 Futures validation

Futures needs **two different rules**, which is why `syncPointFound` exists.

**Regime 1 — hunting the sync point (`syncPointFound == false`):**

```java
if (u < ctx.lastUpdateId)       return IGNORE;
else if (U <= ctx.lastUpdateId) { ctx.lastUpdateId = u; ctx.syncPointFound = true; return OK; }
else                            return DE_SYNCED;
```

i.e. `lastUpdateId ∈ [U, u]` — the snapshot id must fall *inside* the event's range. Note the absence
of the `+1` that spot has; this follows Binance's futures documentation exactly.

> **Known trade-off**: an event with `U == lastUpdateId + 1` is a perfectly contiguous continuation
> and is nonetheless rejected, costing a wasted resync. It requires the snapshot to land exactly on a
> 500ms aggregation boundary, so it is rare. Doc-conformance was chosen over the looser
> `U <= lastUpdateId + 1`.

**Regime 2 — locked (`syncPointFound == true`):**

```java
if (ctx.lastUpdateId == pu) { ctx.lastUpdateId = u; return OK; }
else                         return DE_SYNCED;
```

Plain `pu` continuity. `u` is parsed in **both** regimes — the cursor must advance on every applied
event, regime 2 included. Not advancing it was a real bug during development: every futures book
resynced every two events. A test pins it.

`U` is only parsed while hunting; once locked, the field scan skips it.

### 4.7 Applying a diff — `handleDiff`

```
create parser → nextToken() → check(p, ctx, logName)
   DE_SYNCED → return false                      (onEvent recovers)
   IGNORE    → return true                       (nothing applied)
   OK        → applyDiff(p, book) → book.computeDistance() → return true
any exception → log warn → return false          (onEvent recovers)
```

`applyDiff` walks the remaining fields, routing `b`/`a` into `applyLevel` and skipping everything
else. `applyLevel` reads `getStringCharacters` / `getStringOffset` / `getStringLength` and parses with
`JavaDoubleParser` — no `String` materialised per level, no `BigDecimal`, no allocation except a
`PriceLevelEntry` on first sight of a price. The clock is read **once per message** and passed down,
so every level created by one diff shares a `firstSeenMillis`.

`computeDistance()` runs after every applied diff: recomputes the mid-price, sweeps every level
outside ±`filterThreshold`, and stores `distance` as a **fraction** (0.05 = 5%) on the survivors.

### 4.8 Applying a snapshot — `handleSnapshot`

Reached only when the book is `RECOVERING`.

```
1. applySnapshot(slot, rawJson)
      streams "bids"/"asks" straight into the live book via applyLevel,
      reads "lastUpdateId"; on any parse failure logs and returns -1
   -1 → return false                             (onEvent recovers)
2. book.computeDistance()                        ← sweeps the 1000-level snapshot immediately
3. ctx.lastUpdateId = snapshot lastUpdateId
4. drain: while buffer non-empty → pollFirst() → handleDiff(slot, diff)
      any false → clear the buffer → return false (onEvent recovers)
5. return true                                   (onEvent marks the book SYNCED)
```

Four points, all of them improvements over the pre-refactor path:

- **The snapshot streams directly into the live book in a single pass, with no `clearLevels()`
  first.** The old code parsed into an intermediate collection, validated, then cleared and loaded —
  a two-pass design that existed to avoid destroying a live book before the snapshot validated. With
  the `REST_MSG`-only-in-`RECOVERING` guard plus invariant 3 (§4.10) the book is always already
  empty, so there is nothing to protect. If a partial parse leaves junk behind, step 1 returns `-1`
  and `recover()` clears it.
- **`computeDistance()` on the snapshot path.** The old code never swept there, so a book could reach
  `SYNCED` with 1000 unswept levels all reporting `distance == 0.0`, and the classifier (which
  compares `distance` against `maxDistance`) would see every level as at-mid for one pass.
- **The buffer is genuinely drained** (`pollFirst`), unlike the old code, which iterated without
  removing and left residue behind after syncing.
- **Buffered diffs go through the full `handleDiff`, including `check()`.** There is no special
  "first buffered event skips validation" case any more — the generic predicate handles the first
  event and the rest identically, and the double-parse of the first buffered diff is gone.

**An empty or all-stale buffer is success, not a resync.** The book syncs on the snapshot and waits
for a suitable event from the stream. A missing sync point in the buffer does not mean one will not
arrive on the next frame; resyncing immediately threw away a good snapshot.

### 4.9 Recovery

```java
private void recover(BookSlot slot, BinanceSyncContext ctx) {
    ctx.reset();                                  // buffer, lastUpdateId = -1, syncPointFound = false
    slot.book().clearLevels();
    slot.book().markPending();
    boolean queued = recoverSink.requestRecovery(slot);
    if (queued) slot.book().markRecovering();
    log.debug("[{}] recovering - snapshot {}", slot.instrument().logName(),
              queued ? "requested" : "refused, will retry on next diff");
}
```

- **One call site**, from `onEvent`. Never from a helper, never nested.
- **The price levels are cleared.** The old `resync()` cleared only the diff buffer and left stale
  levels in place until the next snapshot overwrote them. Clearing is what licenses the single-pass
  snapshot apply. The feed is unaffected either way — `OrderBookClassifier` gates on state and emits
  a `DROP`.
- **`requestRecovery` is called before `markRecovering`**, and the ordering is load-bearing: the
  queue's scheduler thread can dispatch the moment the slot lands in its map, before the consumer
  sets the state. It is only safe because the response is published into the ring buffer rather than
  written to the book directly.

**Desyncs are no longer silent.** Each `DE_SYNCED` branch logs the specific mismatch at `debug`
(spot: expected `U <= …`, got `U=…`; futures: sync point missed / `pu` gap), and `recover()` logs
whether the snapshot was requested or refused. Buffer overflow logs from the context with the
instrument name passed in. All lazy `{}` placeholders at `debug`/`warn`, per hot-path rules.

### 4.10 Invariants a reviewer should check

1. `recover()` is invoked from `onEvent` and nowhere else — at most one sink call per event.
2. Every helper (`handleDiff`, `handleSnapshot`, `applySnapshot`) returns a `boolean` and never
   touches the sink.
3. **A book in `PENDING` or `RECOVERING` has empty price levels.** Held by initial construction,
   `recover()`'s `clearLevels()`, and the fact that the `PENDING → RECOVERING` transition can only run
   on a book one of those two already emptied. `applySnapshot`'s lack of a defensive clear depends
   entirely on this.
4. Only the shard's consumer thread writes `OrderBook.state`. It stays `volatile` because
   `PipelineHealthLogger` (scheduler thread) reads it.
5. `check()` leaves the parser positioned before `b`/`a`.
6. `lastUpdateId == -1` means "no sync point"; on futures that must coincide with
   `syncPointFound == false`.

### 4.11 Worked traces

**Cold start.** Book `PENDING`, empty. First diff → `requestRecovery` → accepted → `RECOVERING`, diff
buffered. More diffs buffer. Snapshot arrives ~5–11s later → levels loaded, swept, cursor set, buffer
drained (stale entries `IGNORE`d, the first in-range one establishes the sync point) → `SYNCED`.

**Cold start, queue full.** First diff → refused → diff dropped, book stays `PENDING`. The next diff
(≤1s on spot, ≤500ms on futures) asks again. Repeats until the queue drains. No book is stranded.
This is the dominant path for the first several minutes of the ramp.

**Steady state (spot).** Every diff: `check` → `U <= lastUpdateId + 1` and `u >= lastUpdateId` →
apply → sweep → cursor advances to `u`.

**Sequence gap (futures).** `pu != lastUpdateId` → `DE_SYNCED` → `recover()`: context reset, levels
cleared, `PENDING`, re-queued → `RECOVERING`. Subsequent diffs buffer until the new snapshot lands.

**Buffer overflow.** The 501st diff while `RECOVERING` → `bufferDiff` false → `recover()`. The
triggering diff is discarded and any in-flight snapshot request is superseded.

**Late/duplicate snapshot.** `REST_MSG` on a `SYNCED` book → dropped by the state guard, book
untouched. The old code ran the full snapshot path and knocked a healthy book back to `PENDING`.

### 4.12 Test coverage

`src/test/.../exchange/binance/` — `BinanceDepthSyncStrategyTest` (23 cases over four nested groups:
reaching `SYNCED`, sequence validation, snapshot handling, the recovery handshake), plus
`SyncTestSupport` and `FakeRecoverySink`. `BinanceAdapterConfigTest` pins the venue→strategy wiring.

The suite is **not** a parity oracle against the old algorithm — cases whose expectation was
deliberately changed are labelled `CHANGED —` in their `@DisplayName` and each is justified against
the sections above. It drives real JSON through the real `JsonParser`, including Binance's field
ordering, and reads `BinanceSyncContext`'s package-private cursor fields directly instead of
reflecting. The harness contains no dispatch logic of its own: it builds a `DepthEvent` and calls
`strategy.onEvent(slot, event)`. If it ever grows a state transition again, it has stopped testing
the real thing.

`OrderBookTest` is venue-free — it drives `applyLevel` / `clearLevels` / `computeDistance` directly.

---

## 5. What adding a second exchange requires today

The vision's test of success is: *adding an exchange touches only a new `exchange/<name>/` package
and a YAML block.* Measured against that, here is what is already additive and what is not.

**Already additive** — a new venue can supply these without any core edit:

| Seam | Mechanism |
|---|---|
| Sync algorithm | `DepthSyncStrategy` + `BookSyncContext`, bound via a `VenueStrategyBinding` bean from the adapter's own `@Configuration` |
| Per-book sync state | `BookSyncContext` is opaque; core never inspects it |
| Recovery mechanism | `RecoverySink` — REST fetch, resubscribe or no-op, entirely the venue's choice |
| Identity | `Venue` + `Instrument` + dense ids; nothing in the hot path is Binance-shaped |
| Event provenance | `EventType` says where bytes came from, not what they mean |
| Per-venue transport config | `screener.exchanges.<exchange>.venues.<market>.*` via `ExchangesProperties`; connection count is derived from stream count and the venue's own cap |
| Discovery | An `InstrumentSource` bean from the adapter's config, with its own policy record under `screener.exchanges.<exchange>.discovery.*` |

**Not yet additive** — these still name Binance in core and are the remaining P2 work:

| Seam | Current state | Needed |
|---|---|---|
| Snapshot fetching | `SnapshotFetchQueue` hardcodes `/api/v3/depth` and `/fapi/v1/depth` and has a `dispatchSpot`/`dispatchFutures` pair | One `SnapshotRequestQueue` class parameterised per model-A venue behind a `SnapshotSource` (P2 step 3) |
| Request budget | `WeightGuard` / `WeightLimitFilter` assume Binance's `x-mbx-used-weight-1m` header and a wall-clock-minute reset | `RequestBudget` with `HeaderFeedbackBudget` + `LocalTokenBucketBudget`, one instance per venue (P2 step 4) |
| Transport | `BinanceConnectionPool` / `BinanceStreamClient` / `BinanceWebSocketManager` — Binance frame shapes, ack discrimination, protocol-level ping | `StreamProtocol` + `Heartbeat` + a generic `ConnectionPool`; the adapter supplies subscribe frames, control-frame detection and routing-token extraction (P2 step 5) |
| Config | `screener.orderbook.*` and `screener.websocket.*` still sit outside `screener.exchanges.*`; the snapshot queue sizes are read via `@Value` rather than through `OrderbookProperties` | Fold in under the venue block (P2 step 6) |

Once those four land, Bybit becomes a new package plus YAML. Bybit is venue #2 deliberately: it is
model B (in-stream snapshot, resubscribe-to-recover, application-level heartbeat, topic routing), so
it exercises every axis on which the SPI could be wrong. MEXC spot would pass a Binance-shaped
abstraction by luck and validate nothing.

---

## 6. Known gaps and deferred work

### 6.1 Deferred to P3 (robustness that only bites at N venues)

- **No reset lane.** `BookSlot` has no `resetRequested` flag. Four situations need to tell a book
  "your state is invalid" from a thread that does not own it: a dropped frame under backpressure, a
  reconnect, a staleness timeout, and an unsubscribe on universe change. None are handled today.
- **Ring-buffer backpressure blocks.** `DisruptorDepthMessageHandler` and `SnapshotFetchQueue` both
  use blocking `rb.next()`. `WS_MSG` should use `tryNext()` and set the reset flag on failure, so a
  slow shard cannot stall a reader thread and cost a whole connection.
- **No dynamic subscribe/unsubscribe.** `BinanceWebSocketManager.onUniverseChanged` still logs
  *"dynamic re-subscription not yet implemented"* — the 4-hourly refresh updates the registry but not
  live subscriptions. Needs the reverse `instrumentId → (connection, topic)` routing direction.
- **No staleness watchdog.** A subscription that silently stops delivering is invisible: the book
  sits `SYNCED` with frozen data indefinitely.
- **Health surface is a log line, not a registry.** `PipelineHealthLogger` emits one line every 30s
  — synced/tracked per venue, resyncs per interval per venue, msgs/s and free ring slots per shard,
  and the feed drain's worst tick — backed by `exchange/health/PipelineMetrics`. That covers churn,
  throughput, backpressure and delivery, which is enough to tell the current failure modes apart.
  Still missing: connections up/down and reconnect counts, snapshot queue depth and dispatch
  latency, dropped-event counters, and oldest `lastMessageAtMs` per venue — and none of it is
  queryable, only logged. `PipelineMetrics` is the seam those grow into.
- **No read-side storage seam.** `OrderBookClassifier` reaches straight into `getBids()`/`getAsks()`.
  Until reads go through accessors, swapping `TreeMap<Double,…>` for primitive parallel arrays (P6)
  is a multi-class rewrite rather than a one-class change.
- **No snapshot request generation/epoch.** A superseded in-flight snapshot can be consumed by a book
  that has since recovered and re-queued. It self-corrects — the buffer replay validates the sequence
  either way — but it is a real ordering hazard and belongs with the reset lane.

### 6.2 `/api/monitoring/orderbook` — deliberately abandoned

The endpoint still exists and still routes, but returns a plain string message instead of the book's
contents. `OrderBook.snapshotBids()` / `snapshotAsks()` are gone and are not coming back.

This is a decision, not an omission. Nothing should read a live order book's full contents: it means
copying two `TreeMap`s that a consumer thread is concurrently mutating (a standing
`ConcurrentModificationException` hazard), from a Tomcat thread, over thousands of levels, purely for
debugging. The `OrderBookResponse` / `LevelView` records are left in place should a bounded
top-N-with-state view ever be wanted, but that would arrive as part of the P3 health surface —
state counts and rates per venue, not raw level dumps.

### 6.3 Documentation

`.claude/docs/orderbook-sync-algorithm.md` still describes the pre-P1 Binance-only pipeline and is
actively misleading. §4 above supersedes its sync chapters; it should be rewritten or have its stale
chapters deleted before anyone uses it to reason about sync.

---

## 7. Decisions log

Settled, with the reasoning, so they are not relitigated:

1. **Venue, not exchange, is the adapter unit.** One set of adapter beans per venue, even when two
   venues share an implementation class.
2. **Identity is a dense `int`**; `(venue, nativeSymbol)` is the durable form and the only thing
   persisted. `Market` — not `Venue` — stays in the DB and the public API, which is what let P1 land
   without a Flyway migration or a payload change.
3. **One SPI entry point.** No snapshot/delta split in the interface: which payload is a snapshot is a
   venue-specific read. `EventType` carries provenance only.
4. **No normalised cross-exchange DTO.** The strategy parses and applies in a single streaming pass,
   calling `book.applyLevel` from inside the `JsonParser` loop.
5. **Sequence cursors and diff buffers live in `BookSyncContext`**, opaque to core.
6. **Recovery is a per-venue `RecoverySink` returning `boolean`.** The refusal is what bounds how many
   books simultaneously hold a 500-entry buffer during the startup ramp.
7. **`BookSyncContext` has no methods.** A `reset()` on the interface has no core caller until the P3
   reset lane; the Binance-side reset is a context concern, not part of the SPI.
8. **Adapters register through `VenueStrategyBinding` beans, never `@Component` scanning.** Core names
   no adapter class, and a duplicate or missing binding fails at startup rather than as an NPE on a
   consumer thread.
9. **The sync algorithm was rewritten, not ported.** It is correct and doc-conformant, but not
   byte-identical to the pre-refactor code; §4 is the specification, and the old characterization
   expectations are not an oracle.
10. **The strict `u < lastUpdateId` discard on spot** stays, contrary to Binance's docs, because the
    documented `<=` prevents spot books from ever syncing.
11. **No endpoint reads a full order book.** `/api/monitoring/orderbook` is abandoned; observability
    comes from aggregate state, not level dumps.
12. **Bybit is venue #2**, not MEXC — model B exercises every axis on which the SPI could be wrong.

---

## 8. Next actions, in order

1. **P2 step 3** — `SnapshotRequestQueue` parameterised per venue behind a `SnapshotSource`; drop the
   `dispatchSpot`/`dispatchFutures` fork and the hardcoded depth paths.
2. **P2 step 4** — `RequestBudget` per venue; generalise `WeightLimitFilter`; WebClients keyed by venue.
3. **P2 step 5** — `StreamProtocol` + `Heartbeat` + generic `ConnectionPool`; move Binance frame
   handling into the adapter package.
4. **P2 step 6** — fold `screener.orderbook.*` and `screener.websocket.*` into the venue config, and
   move the snapshot queue sizes off `@Value` onto a properties record.
5. **P3** — reset lane and `tryNext()` backpressure first (they are coupled), then dynamic
   subscribe/unsubscribe, the staleness watchdog, and the venue health surface.
6. **Rewrite or prune `.claude/docs/orderbook-sync-algorithm.md`.**
7. **P4 — Bybit.** The real test: if it lands as a new package plus a YAML block, the abstraction held.
