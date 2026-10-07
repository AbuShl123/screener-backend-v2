# P2 — SPI Extraction (analysis and approach)

**Status**: analysis + agreed approach. No P2 code written yet. This document records the design
reasoning and the commit sequence; the per-commit detail is worked out as each is picked up.

**Goal**: put every Binance-specific decision in the market-data pipeline behind a named seam —
`DepthSyncStrategy` + `BookSyncContext`, `RecoverySink`, `StreamProtocol`, `RequestBudget`, a
parameterised snapshot queue — and move the pipeline under `exchange/`. **Binance stays the only
implementation; observable behaviour unchanged.**

**Related reading**: `.claude/plans/multi-exchange-architecture-vision.md` (§3–§6, §10.2, §12 P2 row —
this document narrows and in three places corrects it), `.claude/plans/p1-instrument-identity.md`
(the phase this builds on, and the source of the deferral table), `.claude/docs/orderbook-sync-algorithm.md`
(the sync behaviour that must not change), `CLAUDE.md` (hot-path rules — unchanged and still binding).

---

## 0. The framing problem, and the rule that follows from it

P2 extracts an SPI with **no second venue to validate it against**. Bybit is P4. The vision's own
§12.1 argument — that MEXC would pass through a Binance-shaped abstraction *by luck*, validating
nothing — applies to P2 itself: Binance will fit any seam we draw around Binance.

> **Governing rule for the phase: extract only the seams an existing branch demands. An interface
> method with no implementation and no caller is a guess — don't write it.**

Applying it, the following are dropped from the vision's sketch:

| Dropped | Why |
|---|---|
| `StreamProtocol.buildUnsubscribeFrames()` | No caller until P3's dynamic subscribe/unsubscribe. |
| `StreamProtocol.binaryFrames()` | No caller until P5 (gzip/deflate venues). |
| `RequestBudget.delayMillisRequired(int cost)` | See §2.7 — Binance ignores it, and supplying a real cost is genuine plumbing with no consumer. |
| `budget: {type: …}` / `snapshot: {mode: …}` config discriminators | Same reasoning P1 used: config for absent abstractions guarantees a rewrite. See §6.3. |

### 0.1 Restate the exit criterion honestly

Not *"the SPI is proven"* — it cannot be, at one venue. The honest exit criterion is:

> Every Binance-specific decision sits behind a named seam, the sync core is covered by unit tests,
> and the running system is at parity with P1.

P4 will still edit these interfaces. Saying so up front is better than discovering it later and
reading it as a design failure.

---

## 1. Decisions taken

Agreed in discussion, before any code:

1. **The package move happens first, alone, and is committed on its own.** P1 deferred it to avoid
   burying a semantically dangerous diff in rename noise. That argument was about *mixing*, not
   ordering — a commit containing nothing but moves buries nothing.
2. **Hard-first ordering.** The sync SPI is the second commit, not the last. Its diff is most
   readable against an otherwise stable tree, and it is the commit the new tests exist to cover.
3. **Small, individually reviewable commits.** P2 is not landed as one change. Every step in §5
   compiles, runs, and is reviewable on its own. Where a step cannot be split without a
   non-compiling intermediate, that is called out explicitly (only step 2 has this property).
4. **`EventType` is renamed to express provenance, not semantics**: `DIFF` → `WS_MSG`,
   `SNAPSHOT` → `REST_MSG`. Not every in-stream message is a diff — on a model-B venue the
   in-stream frame may be the snapshot. The enum answers *where the bytes came from*, and therefore
   which parse path and which backpressure policy applies; *what the payload means* is a
   venue-specific read the strategy performs. This matches vision §3.3, which the current names
   contradict.
5. **Model-B venues consume only the WebSocket lane.** A venue with no REST snapshot in its flow
   never constructs a snapshot queue and never emits a `REST_MSG`; its strategy recognises the
   in-stream snapshot from the payload and recovers by asking its sink to resubscribe. Nothing in
   core needs to know which model a venue is — which is what settles §6.3.

---

## 2. What the current code forces

Findings from reading the P1 tree. Five change the shape the vision sketched; the rest are smaller
but load-bearing.

### 2.1 Recovery is a handshake, not fire-and-forget

The vision has `void requestRecovery(int instrumentId)`. But `OrderBookProcessor` reads:

```java
if (!snapshotFetchQueue.enqueue(slot)) return slot;   // queue at capacity → stay PENDING
slot.book().markSnapshotRequested();                  // only now transition to buffering
```

The queue can **refuse**, and the refusal is load-bearing: it is what bounds how many books
simultaneously hold a 500-entry diff buffer during the startup ramp (the first of the two reasons
`orderbook-sync-algorithm.md` §7 gives for a *small* bounded queue). A `void` sink loses it, and the
book would start buffering against a snapshot nobody is fetching.

> `RecoverySink.requestRecovery(...)` **returns `boolean`** — whether the venue accepted. Model-B
> and model-C sinks return `true` unconditionally.

### 2.2 The strategy takes the `BookSlot`, not `(book, ctx, event)`

The vision's three-argument signature predates `BookSlot` existing as a concrete record. It now
holds exactly what the strategy needs, and what the sink needs behind it
(`instrument().nativeSymbol()` for the snapshot URL, `instrument().venue()` for queue selection).

```java
interface DepthSyncStrategy {
    BookSyncContext newContext();              // cold: once per book, at allocation
    void onEvent(BookSlot slot, DepthEvent e); // hot: every event
}
```

One argument instead of three, one dereference, and P3's `resetRequested` flag lands on the slot
without changing the signature again.

### 2.3 Spot and futures differ in exactly one predicate

Verified by reading `OrderBook` end to end: `applySnapshot`, `discardInvalidDiffsFromBuffer`, the
`[U,u]` sync-point check, the first-event special case and the buffer handling are identical between
the two markets. The **only** divergence is in `applyLiveDiff`:

```java
venue == BINANCE_SPOT    → U  != lastUpdateId + 1  → gap
venue == BINANCE_FUTURES → pu != lastUpdateId      → gap
```

So this is **one abstract `BinanceDepthSyncStrategy` with an abstract sequence check plus two
~10-line subclasses**, not two duplicated 200-line strategies. One `BinanceSyncContext`
(`lastUpdateId` + `diffBuffer`) serves both venues. Two configured instances, per vision §1's
"one venue, one set of adapter beans, even when two venues share an implementation class."

### 2.4 The parse/store line is cleaner than "the strategy owns parsing"

`applyLevelsDirectly` does two separable things:

| Concern | Owner after P2 |
|---|---|
| bytes → numbers (`JsonParser` walk, `JavaDoubleParser`, field names `b`/`a`/`U`/`u`/`pu`) | strategy — Binance-shaped |
| numbers → storage (`PriceLevelEntry` lifecycle, `firstSeenMillis`, zero-qty removal) | `OrderBook` — universal |

The strategy calls `book.applyLevel(side, price, qty)` from *inside* its parser loop — no
intermediate DTO, no second iteration, exactly vision §4.3(b). This also lands the **write half** of
the P3 storage-accessor seam for free. The read half stays deferred: `OrderBookClassifier` still
reaches into `getBids()`/`getAsks()`, and `MonitoringController` still calls `snapshotBids()`.

### 2.5 `SNAPSHOT_REQUESTED` → `RECOVERING`, and only the strategy writes the state

`SNAPSHOT_REQUESTED` encodes a model-A assumption into a core enum. It is read in exactly three
places — `OrderBookClassifier` (the `SYNCED` gate), `BookSlotTable.logSyncCount`, and
`MonitoringController`'s response record — so the rename is trivial now and annoying later.

State stays a `volatile` field on `OrderBook` (monitoring reads it from another thread), but
`OrderBook` no longer *drives* transitions: the strategy does, since deciding "this book is now
in sync" is a venue-specific judgement.

Related correction: `OrderBook`'s class javadoc claims `markSnapshotRequested()` is called from the
`SnapshotFetchQueue` scheduler thread. It is not — the only caller is `OrderBookProcessor`, on the
consumer thread. Fix the comment rather than preserving it.

### 2.6 `@Scheduled` cannot survive a per-venue snapshot queue

`SnapshotFetchQueue`'s two drain methods carry `@Scheduled(fixedRateString = …)`, which only works
on singleton beans. One `SnapshotRequestQueue` *instance per model-A venue* means the drain loop
becomes an explicit `ScheduledExecutorService`. That is a real threading change (off Spring's
`TaskScheduler` pool) hiding inside what reads as a parameterisation — worth its own commit and its
own look at thread naming and shutdown.

The `.delayElement(Duration.ofSeconds(5))` settle delay, the queue capacity, the dispatch rate, the
depth limit and the URL template all become per-venue parameters behind a `SnapshotSource`.

### 2.7 `RequestBudget` — no `cost`, and no separate `serverSentAtMs`

```java
interface RequestBudget {
    long delayMillisRequired();
    void observe(HttpHeaders headers);
}
```

- **No `int cost`.** Binance's guard is threshold-vs-observed and ignores it. Supplying a real cost
  means threading a per-request weight through a WebClient `ExchangeFilterFunction` — request
  attributes, and a weight per endpoint (exchangeInfo 20, depth-1000 50 spot / 20 futures). That is
  genuine plumbing whose only consumer is a `LocalTokenBucketBudget` that does not exist. It arrives
  with the venue that needs it, and that venue will also settle how to plumb it.
- **No separate `serverSentAtMs`.** The Binance impl pulls both the `x-mbx-used-weight-1m` value and
  the `Date` header off the same `HttpHeaders`; a token-bucket impl ignores both. Coupling the SPI to
  Spring's `HttpHeaders` is acceptable — every client in the system is a `WebClient`.

`WeightGuard`'s logic moves verbatim into `HeaderFeedbackBudget`, including the anchor-to-server-Date
trick and the stale-response discard rule. `WeightLimitFilter` becomes a ~15-line generic
`RequestBudgetFilter`.

### 2.8 Smaller confirmations

| Finding | Consequence |
|---|---|
| `OrderBook.venue` has **zero external readers** | Removing it is fully contained to the class. |
| `OrderBookProcessor`'s snapshot re-feed (`onDiff` called twice on `NEEDS_SNAPSHOT`) | Disappears as an awkward double-dispatch once the strategy owns the transition — see the parity trap in §6.1. |
| `parseUField` and `parseUpperUField` each walk the same buffered diff separately | A free single-pass cleanup during extraction. Cold path (snapshot apply only), so optional. |
| `DepthEventHandler.shardIndex` is unused beyond a Lombok `@Getter` | Drop or use it when the handler collapses to the uniform loop. |
| `screener.binance.*` and `screener.exchanges.binance.*` both exist today | Confusing enough that the config consolidation (step 6) should not be skipped. |
| `snapshotBids()` copies a `TreeMap` a consumer thread is mutating → possible `ConcurrentModificationException` | Pre-existing, already noted in `MonitoringController`. Still deferred to P3's storage seam. |

---

## 3. The testability payoff — treat this as a deliverable

**There is not a single test for the market-data pipeline.** Every other module has one — auth,
billing, payment, entitlement, classification rules, monitoring. `binance/` has zero.

That is structural, not neglect: the sync logic is welded to a `TreeMap`, a `volatile` state field
and a Spring-managed queue, so the only verification available is P1's — run against live Binance and
watch `sync count: spot=… fut=…` settle.

Extracting `DepthSyncStrategy` changes that. A strategy taking `(BookSlot, DepthEvent)` with a fake
`RecoverySink` is a plain object drivable from canned JSON. The cases worth pinning are exactly the
ones nobody can currently verify:

1. Happy path — snapshot plus ordered diffs reaches `SYNCED`.
2. Spot `U` gap → reset to `PENDING`, recovery requested.
3. Futures `pu` gap → same.
4. Snapshot whose `snapshotId` falls outside the first buffered diff's `[U,u]` → resync.
5. Empty buffer after `discardInvalidDiffsFromBuffer` → resync.
6. Buffer overflow at `MAX_BUFFER_SIZE` (500) → resync.
7. Diff in `PENDING` with the sink **refusing** → stays `PENDING`, drops, does **not** buffer.
8. The deliberate deviation from Binance's spot docs at `discardInvalidDiffsFromBuffer`
   (`u < snapshotId`, strict) — today defended only by a code comment.

> This is the strongest standalone justification for the sync SPI, independent of multi-exchange:
> it converts the least-tested and highest-risk code in the application into something with a
> regression net. P4 cannot be attempted safely without it.

---

## 4. Target package layout

Deciding this is a prerequisite for the move commit, since files split several ways. Following
vision §10.2:

| Destination | Files |
|---|---|
| `exchange/` (existing) | `Exchange`, `Market`, `Venue`, `Instrument`, `InstrumentRegistry`, `InstrumentUniverseService`, `InstrumentUniverseChangedEvent` |
| `exchange/spi/` | `DepthSyncStrategy`, `BookSyncContext`, `RecoverySink`, `StreamProtocol`, `RequestBudget`, `SnapshotSource` *(all created during P2, born here)* |
| `exchange/book/` | `OrderBook`, `PriceLevelEntry`, `OrderBookState`, `OrderBookResult`, `BookSlot`, `BookSlotTable` |
| `exchange/ingress/` | `DepthEvent`, `EventType`, `DepthEventFactory`, `DepthEventHandler`, `DisruptorShardManager`, `DisruptorDepthMessageHandler`, whatever survives of `OrderBookProcessor` |
| `exchange/stream/` | `ConnectionPool`, `StreamClient`, `SubscriptionIndex`, `RawDepthMessageHandler` |
| `exchange/recovery/` | `SnapshotRequestQueue`, budget implementations |
| `exchange/binance/` | `BinanceRestClient`, `BinanceApiException`, `WeightGuard`, `api/dto/*`, `BinanceStreamProtocol`, the two sync strategies, the Binance sink / source / budget |

Rules for the move commit:

- **Zero semantic change.** Package declarations and imports only. IDE-automatable, compiler-verified
  end to end.
- **New P2 classes are born in their final home**, so nothing moves twice.
- **A file whose home is genuinely uncertain stays put** and moves in the commit that clarifies it —
  better than guessing and moving twice.

---

## 5. Work order

Each numbered item is one commit (step 2 may be two; see below). Each compiles, starts, and is
reviewable on its own.

### 1. Package move

Mechanical, per §4. Nothing else in the commit.
*Verify*: `./mvnw clean package` green, app starts, feed flows end to end.

### 1b. `EventType` rename

`DIFF` → `WS_MSG`, `SNAPSHOT` → `REST_MSG`. Three call sites. Compiler-verified, no behaviour change.
Kept separate from the move so each commit does exactly one thing; fold into step 1 if that reads
better in review.

### 2. Sync SPI + tests *(the keystone)*

- New: `DepthSyncStrategy`, `BookSyncContext`, `RecoverySink`; `BinanceDepthSyncStrategy` (abstract)
  and its spot/futures subclasses; `BinanceSyncContext`.
- `OrderBook` loses `venue`, `lastUpdateId`, `diffBuffer`, `onDiff`, `applySnapshot`, and all JSON
  parsing; gains `applyLevel`, `clearLevels`, a state setter. `SNAPSHOT_REQUESTED` → `RECOVERING`.
- `BookSlot` gains `ctx` and `strategy`, both resolved at `BookSlotTable.allocate()` from the
  instrument's venue.
- The consumer collapses to the uniform loop; `OrderBookProcessor` mostly dissolves into it.
- `SnapshotFetchQueue` implements `RecoverySink` with its **internals unchanged**.
- The §3 test suite lands here.

This one is atomic — removing the parse path from `OrderBook` breaks the strategy's call site
immediately, so nothing compiles in between. If it wants splitting, the only clean seam is
(2a) move `lastUpdateId` + `diffBuffer` into a context object still owned by `OrderBook`, then
(2b) extract the strategy — at the cost of one commit of pure churn. Default: keep it whole and lean
on the tests.

### 3. Recovery generalisation

`SnapshotRequestQueue` instantiated once per model-A venue + `SnapshotSource`; `@Scheduled` → explicit
scheduler (§2.6); settle delay, capacity, dispatch rate, depth limit become per-venue parameters.

### 4. `RequestBudget`

`RequestBudget` + `HeaderFeedbackBudget` + generic `RequestBudgetFilter`; WebClients keyed by venue;
`BinanceRestClient.getSpot/getFutures` → `get(Venue, path, type)`. Touches
`InstrumentUniverseService`'s two `exchangeInfo` calls as a signature change only — `InstrumentSource`
stays out of P2.

### 5. Transport

`StreamProtocol` (trimmed per §0) plus a per-venue `Heartbeat`; generic `ConnectionPool` /
`StreamClient`; `BinanceStreamProtocol` owning the `charAt(2) == 'r'` control-frame check, the
`"s":"` routing scan and `buildSubscribeFrame`; `BinanceWebSocketManager` iterates enabled venues
from config instead of hardcoding two pools.

### 6. Config consolidation

Fold `screener.binance.*`, `screener.orderbook.*-snapshot-*` and `screener.websocket.*` into
`screener.exchanges.binance.venues.*`. Delete `BinanceApiProperties` and `WebSocketProperties`.

### 7. Docs

`CLAUDE.md` module map and hot-path section; `.claude/docs/orderbook-sync-algorithm.md` (identity,
store, and the sync-strategy sections). `.claude/docs/for-frontend/*` needs no change — P2 touches no
payload.

Steps 3, 4 and 5 are independent of each other and may be reordered. Step 2 fixes `BookSlot`'s shape
and must precede all three.

---

## 6. Parity traps to defend

### 6.1 The `NEEDS_SNAPSHOT` / `NEEDS_RESYNC` asymmetry

Today, on `NEEDS_SNAPSHOT` the triggering diff is re-fed into the book so it lands in the buffer; on
`NEEDS_RESYNC` it is discarded. Under the SPI the awkward double-dispatch disappears — the strategy
is already holding the diff when it decides to transition.

> It must disappear as a **simplification**, not as a behaviour change. Buffer the triggering diff on
> the `PENDING` path; discard it on the resync path. Test case 7 pins the first half; the second
> needs its own.

### 6.2 The recovery refusal

Covered by §2.1. If `requestRecovery` returns `false`, the book stays `PENDING` and drops — it must
not enter `RECOVERING` and start buffering.

### 6.3 Everything the sync algorithm currently gets subtly right

The `[U,u]` window check, the strict `u < snapshotId` discard (deliberately contrary to Binance's spot
documentation), the empty-buffer-after-discard resync, the first-event special case that skips
sequence validation, and `MAX_BUFFER_SIZE`. All move verbatim. The §3 tests exist to prove they did.

### 6.4 Hot-path austerity

Unchanged from `CLAUDE.md` and P1 §5.4. Specifically: `applyLevel` must not allocate beyond the
existing `PriceLevelEntry` on first sight of a level; `newContext()` is called once per book at
allocation, so the context is allocation-free at steady state; and the strategy's parse loop keeps
using `getStringCharacters` + `JavaDoubleParser` rather than materialising strings.

---

## 7. Open questions

1. **Splitting step 2.** Default is to keep it whole (§5). Revisit if the diff turns out larger than
   expected once `OrderBook` is actually cut.
2. **`Side` representation on `applyLevel`.** `boolean isBid`, a `Side` enum, or two methods
   (`applyBid`/`applyAsk`). Cosmetic today; matters slightly for the P6 primitive book. Decide when
   writing step 2.
3. **Snapshot-mode discriminator in config.** Leaning resolved by decision §1.5: absence of a
   `snapshot:` block *is* the discriminator — a model-B venue simply has none, and nothing in core
   asks which model a venue is. Revisit at P4 if a silent YAML omission proves too quiet a failure
   mode; an explicit `mode:` can be added then without restructuring.
4. **Where `Heartbeat` lives.** Currently `screener.websocket.heartbeat-interval-seconds` is global.
   It becomes per-venue in step 6, but whether the *frame producer* belongs on `StreamProtocol` or as
   its own small interface is worth a look when step 5 is written.
5. **Reset lane interaction.** P3 adds `resetRequested` to `BookSlot` and `tryNext()` backpressure.
   Step 2 should leave room for it (the slot is already the strategy's argument) but must not
   implement it — it is a behaviour change, not a refactor.

---

## 8. Explicitly still out of scope

Unchanged from the vision's phasing, restated so P2 does not drift into them:

| Deferred | Phase |
|---|---|
| Reset lane (`resetRequested`), `tryNext()` backpressure | P3 |
| Dynamic subscribe/unsubscribe; the reverse `instrumentId → (connection, topic)` index | P3 |
| Staleness watchdog; venue-dimensioned health surface | P3 |
| Storage accessor seam on the **read** side (classifier, monitoring) | P3 |
| Connect/subscribe pacer | P3 |
| `InstrumentSource` SPI | P4 |
| Zero-allocation `SubscriptionIndex` | measurement-driven, unscheduled |
| Any Flyway migration, REST or WebSocket payload change | none — P2 touches no contract |
