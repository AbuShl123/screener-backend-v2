# P2 Step 2 — Sync SPI + tests (implementation plan)

**Status**: plan. No code written yet.
**Parent**: `.claude/plans/p2-sync-spi-extraction.md` §5 step 2 — *the keystone*.
**Prerequisite (done)**: step 1, the package move (`f9ed834`).
**Related**: `.claude/plans/multi-exchange-architecture-vision.md` §3–§4, `.claude/docs/orderbook-sync-algorithm.md`,
`CLAUDE.md` hot-path rules (unchanged and still binding).

**Goal**: every Binance-specific sync decision moves behind `DepthSyncStrategy` + `BookSyncContext` +
`RecoverySink`; `OrderBook` becomes venue-agnostic storage; the sync algorithm gets its first tests.
**Binance stays the only implementation. Observable behaviour unchanged** (with two deliberate,
enumerated exceptions in §7.3).

---

## 0. The three questions this plan answers first

**Is `DepthSyncStrategy` generic?** Yes. It is a core, exchange-agnostic interface in
`exchange/spi/`, and nothing in it names Binance, a snapshot, a REST call or a sequence number. Its
whole surface is two methods (§2).

**What implements it for Binance?** Three classes, not two 200-line copies. Reading `OrderBook`
end to end confirms the parent plan's §2.3 finding: **spot and futures differ in exactly one
predicate** — `U != lastUpdateId + 1` versus `pu != lastUpdateId`, both inside `applyLiveDiff`.
Everything else (`applySnapshot`, `discardInvalidDiffsFromBuffer`, the `[U,u]` sync-point check, the
first-event special case, buffer handling) is byte-identical. So:

| Class | Size | Role |
|---|---|---|
| `BinanceDepthSyncStrategy` (abstract) | ~200 lines | the whole model-A algorithm; one abstract `isSequenceGap(...)` |
| `BinanceSpotSyncStrategy` | ~12 lines | `U != lastUpdateId + 1` |
| `BinanceFuturesSyncStrategy` | ~12 lines | `pu != lastUpdateId` |
| `BinanceSyncContext` | ~8 lines | `lastUpdateId` + `diffBuffer` — the state `OrderBook` gives up |

Two **configured instances**, one per venue, per vision §1 ("one venue, one set of adapter beans,
even when two venues share an implementation class").

**Where do they live?** `exchange/binance/` — flat, alongside `BinanceRestClient` / `WeightGuard`,
per parent §4's destination table. New P2 classes are born in their final home so nothing moves
twice. (If `exchange/binance/` gets crowded once steps 3–5 add the protocol, sink, source and budget,
a `sync/` subpackage can be carved then — see §10.3.)

---

## 1. File inventory

### New — `exchange/spi/` (core, exchange-agnostic)

| File | Purpose |
|---|---|
| `DepthSyncStrategy.java` | the SPI, two methods |
| `BookSyncContext.java` | marker interface; opaque to core, never inspected |
| `RecoverySink.java` | one method, returns `boolean` (parent §2.1) |
| `SyncStrategyRegistry.java` | `Venue → DepthSyncStrategy`, resolved once at slot allocation |

### New — `exchange/binance/`

`BinanceDepthSyncStrategy.java`, `BinanceSpotSyncStrategy.java`, `BinanceFuturesSyncStrategy.java`,
`BinanceSyncContext.java`, `BinanceAdapterConfig.java` (the `@Bean` that builds the registry).

### Modified

| File | Change |
|---|---|
| `book/OrderBook.java` | loses `venue`, `lastUpdateId`, `diffBuffer`, `onDiff`, `applySnapshot`, all JSON parsing, `MAX_BUFFER_SIZE`, `markSnapshotRequested`. Gains `applyLevel`, `clearLevels`, `markPending/markRecovering/markSynced`, public `computeDistance`. ~400 → ~130 lines |
| `book/OrderBookState.java` | `SNAPSHOT_REQUESTED` → `RECOVERING` |
| `book/BookSlot.java` | record gains `ctx` and `strategy` |
| `book/BookSlotTable.java` | `allocate()` resolves strategy + calls `newContext()` |
| `ingress/DepthEventHandler.java` | absorbs slot resolution + the missing-slot counter; uses `shardIndex` |
| `ingress/DisruptorShardManager.java` | passes `BookSlotTable` into each handler instead of `OrderBookProcessor` |
| `recovery/SnapshotFetchQueue.java` | `implements RecoverySink`; **internals unchanged**; `enqueue` renamed to `requestRecovery` |
| `monitoring/MonitoringController.java` | none in code — but its `state` JSON value changes (§7.3) |

### Deleted

- `ingress/OrderBookProcessor.java` — dissolves into `DepthEventHandler`.
- `book/OrderBookResult.java` — the enum existed only to carry "consumer, please enqueue" back to
  `OrderBookProcessor`. The strategy now holds the sink and acts directly; the return channel has no
  remaining caller.

### New tests

`exchange/binance/BinanceDepthSyncStrategyTest.java`, `exchange/book/OrderBookTest.java`,
plus small test fixtures (§8.1).

---

## 2. The SPI

```java
package dev.abu.screener_backend.exchange.spi;

/** One configured instance per venue. Stateless — all per-book state lives in BookSyncContext. */
public interface DepthSyncStrategy {

    /** Cold: called once per book, at slot allocation. */
    BookSyncContext newContext();

    /** Hot: called for every event routed to this instrument, on the shard's consumer thread. */
    void onEvent(BookSlot slot, DepthEvent event);
}

/** Opaque per-book sync state. Core stores it and never inspects it. */
public interface BookSyncContext {}

/** How one venue repairs a desynced book. The strategy's only collaborator. */
public interface RecoverySink {
    /** @return false if the venue refused (queue at capacity) — the book must NOT start buffering. */
    boolean requestRecovery(BookSlot slot);
}
```

Four choices worth defending:

**(a) `BookSlot`, not `(book, ctx, event)`.** The vision's three-argument signature predates
`BookSlot` existing. The slot already holds everything the strategy needs *and* everything the sink
needs behind it (`instrument().nativeSymbol()` for the URL, `instrument().venue()` for queue
selection). One argument, one dereference — and P3's `resetRequested` flag lands on the slot without
touching this signature again.

**(b) `RecoverySink` takes the slot, not an `int`.** An `int` would force the sink to hold the
`BookSlotTable` to get back to the symbol — a dependency it does not otherwise need, and a second
array load on a path that already has the slot in hand.

**(c) `requestRecovery` returns `boolean`.** Load-bearing, not cosmetic. `SnapshotFetchQueue` can
refuse at capacity, and that refusal is what bounds how many books simultaneously hold a 500-entry
diff buffer during the startup ramp. A `void` sink would leave the book buffering against a snapshot
nobody is fetching.

**(d) `BookSyncContext` has no methods.** Core stores it in the slot and hands it back. A `reset()`
method has no caller until P3's reset lane; per the parent plan's governing rule, it is not written
today.

**Known package cycle**: `spi` → `book`/`ingress` (for `BookSlot`, `DepthEvent`), and `book` → `spi`
(for the two new `BookSlot` fields). Java permits it and it compiles; it is inherent to putting the
hot-path record and the SPI in different packages, and is accepted deliberately rather than worked
around with a third package.

---

## 3. Binance implementation

### 3.1 Context

```java
final class BinanceSyncContext implements BookSyncContext {
    long lastUpdateId;
    final ArrayDeque<String> diffBuffer = new ArrayDeque<>();   // raw diff JSON, RECOVERING only
}
```

Allocated once per book at `BookSlotTable.allocate()`. Allocation-free at steady state.

### 3.2 Dispatch — one place that calls the sink

The single most important structural rule in this step:

> `recover(slot, ctx)` — the method that clears the buffer, drives the book to `PENDING` and calls
> the sink — is invoked **at most once per event**, from the top-level `onEvent` dispatch. Every
> internal helper returns a `boolean` and never calls the sink itself.

This is what today's code achieves accidentally, by returning `OrderBookResult` up to
`OrderBookProcessor`. Getting it wrong is the easiest way to introduce a double-enqueue: today
`applySnapshot`'s buffer-drain loop calls `applyLiveDiff`, which calls the *state-only* `resync()`,
and only the outer return value reaches the processor's single `enqueue`.

```java
@Override
public void onEvent(BookSlot slot, DepthEvent e) {
    BinanceSyncContext ctx = (BinanceSyncContext) slot.ctx();
    boolean ok = (e.type == EventType.REST_MSG)
            ? applySnapshot(slot, ctx, e.rawJson)
            : handleDiff(slot, ctx, e.rawJson);
    if (!ok) recover(slot, ctx);
}

private boolean handleDiff(BookSlot slot, BinanceSyncContext ctx, String rawJson) {
    switch (slot.book().getState()) {
        case PENDING -> {
            // Ask first; only start buffering if the venue accepted.
            if (slot.book() /* still PENDING */ != null && sink.requestRecovery(slot)) {
                slot.book().markRecovering();
                ctx.diffBuffer.addLast(rawJson);       // parent §6.1: buffer the triggering diff
            }
            return true;                                // refused → stay PENDING, drop, no recover()
        }
        case RECOVERING -> {
            if (ctx.diffBuffer.size() >= MAX_BUFFER_SIZE) {
                log.warn("[{}] Diff buffer overflow — forcing re-sync", slot.instrument().logName());
                return false;                           // → recover(); triggering diff discarded
            }
            ctx.diffBuffer.addLast(rawJson);
            return true;
        }
        case SYNCED -> { return applyLiveDiff(slot, ctx, rawJson); }
    }
    return true;
}

private void recover(BookSlot slot, BinanceSyncContext ctx) {
    ctx.diffBuffer.clear();
    slot.book().markPending();
    if (sink.requestRecovery(slot)) slot.book().markRecovering();
}
```

Cross-check against today, path by path:

| Today | New |
|---|---|
| `PENDING` → `NEEDS_SNAPSHOT` → processor enqueues → `markSnapshotRequested()` → **re-feeds the diff** so it buffers | `PENDING` branch: sink accepts → `markRecovering()` → buffer the diff. The awkward double-dispatch is gone; the buffered content is the same |
| `PENDING` → enqueue **refused** → returns; book stays `PENDING`, diff dropped, no buffering | same; `return true` skips `recover()` |
| overflow → `resync()` sets `PENDING` + clears → `NEEDS_RESYNC` → enqueue → `markSnapshotRequested()`; **trigger discarded** | `return false` → `recover()`; trigger discarded |
| gap / parse error in `applyLiveDiff` → same as overflow | same |
| snapshot failure at any step → `resync()` → `NEEDS_RESYNC` → enqueue | `applySnapshot` returns `false` → `recover()` |
| inner `applyLiveDiff` during buffer drain → sets `PENDING`, **no enqueue**; outer result drives the single enqueue | inner returns `false`, outer returns `false`, one `recover()` |

### 3.3 The one abstract method

```java
/** @return true if this frame is not the expected successor of {@code lastUpdateId}. */
protected abstract boolean isSequenceGap(long U, long pu, long lastUpdateId, String logName);
```

```java
// BinanceSpotSyncStrategy
protected boolean isSequenceGap(long U, long pu, long lastUpdateId, String logName) {
    if (U == lastUpdateId + 1) return false;
    log.debug("[{}] Sequence gap: expected U={}, got U={}", logName, lastUpdateId + 1, U);
    return true;
}
```

Futures is the same shape on `pu != lastUpdateId`. Both keep today's exact debug lines, and both
are called from the same place: inside the parse loop, on first sight of `b`/`a`, guarded by
`sequenceValidated` — Binance guarantees `U`/`u`/`pu` precede `b`/`a`. That ordering assumption moves
verbatim, comment included.

Passing `logName` keeps the two subclasses free of any field, so they stay pure predicates. It is a
`String` already precomputed on `Instrument`, so it costs nothing.

### 3.4 Parse-and-apply in one pass

The strategy calls `book.applyLevel(...)` **from inside** its `JsonParser` loop — no intermediate
DTO, no second iteration, exactly what `applyLevelsDirectly` does today (vision §4.3(b)). The
`getStringCharacters` + `JavaDoubleParser` shape is preserved character for character.

### 3.5 The snapshot path stays two-pass — deliberately

`applySnapshot` currently parses into two temporary `ArrayDeque<double[]>` and only clears and loads
the `TreeMap`s **after** the sync-point validation passes. It is tempting to stream snapshot levels
straight into the book through `applyLevel`. **Do not.** That would destroy a book's contents before
learning the snapshot is unusable, changing what the classifier sees between the snapshot arriving
and the resync completing. This is the cold path — one allocation burst per book per resync — so the
existing two-pass shape moves verbatim.

---

## 4. `OrderBook` after surgery

Keeps (all genuinely universal per vision §4.4): `instrumentId`, `logName`, `volatile state`,
`filterThreshold`, the two `TreeMap`s, `PriceLevelEntry` lifecycle, the mid-price filter sweep,
`getBids`/`getAsks`/`snapshotBids`/`snapshotAsks`.

New surface:

```java
public void applyLevel(boolean isBid, double price, double qty, long nowMillis);
public void clearLevels();
public void computeDistance();          // was private
public void markPending();
public void markRecovering();
public void markSynced();
```

Three decisions inside that:

**`boolean isBid`, not a `Side` enum** (parent §7 open question 2). The call site already has the
branch as `"b".equals(field)`; a boolean threads it through with no new type, and it is exactly what
the P6 primitive book will use to pick between two array pairs. Named locals keep the call sites
readable.

**`nowMillis` is a parameter, not a `System.currentTimeMillis()` call inside.** The strategy reads
the clock once per message and passes it down, instead of once per new level as today. Fewer clock
calls on the hot path, deterministic `firstSeenMillis` within one message, and — the reason it is
worth mentioning — it makes `firstSeenMillis` assertable in a test without introducing a `Clock`
indirection into the hot path. See §7.3.

**Three named transitions, not a `setState` setter.** They enumerate the legal set and stay
greppable. State remains `volatile` (monitoring reads it from a Tomcat thread), but per parent §2.5
**only the strategy writes it** now. The stale class javadoc claiming the `SnapshotFetchQueue`
scheduler thread writes `state` is deleted — it never did; the only writer was
`OrderBookProcessor`, on the consumer thread.

The `venue` field goes with no external readers to fix (parent §2.8), and with it the last
`if (market == …)` branch in the storage layer.

---

## 5. The consumer loop

`OrderBookProcessor` disappears; `DepthEventHandler` becomes the whole loop:

```java
@Override
public void onEvent(DepthEvent event, long sequence, boolean endOfBatch) {
    BookSlot slot = slots.get(event.instrumentId);
    if (slot == null) {
        noteMissingSlot(event.instrumentId);        // plain long counter — single-threaded now
        event.clear();
        return;
    }
    slot.strategy().onEvent(slot, event);
    classifier.process(slot.instrument(), slot.book());
    event.clear();
}
```

Two notes:

**The classifier call stays unconditional.** Vision §3.5 sketches
`if (book.getState() == SYNCED) classifier.process(...)`. **That sketch is wrong for this codebase**
and must not be copied: `OrderBookClassifier.classifyOne` handles the non-`SYNCED` case itself by
emitting a `DROP` update and dropping the symbol to `LOW` activity. Gating on `SYNCED` in the
consumer would silently strip every desync notification out of the feed. See §7.1.

**The missing-slot counter becomes per-shard.** It is `AtomicLong` today only because
`OrderBookProcessor` was a shared singleton. One handler per shard, single-threaded, so plain `long`
fields suffice, and the log line gains the shard index — which finally gives `shardIndex` the caller
it has been missing (parent §2.8).

---

## 6. Wiring

```java
// exchange/binance/BinanceAdapterConfig
@Bean
SyncStrategyRegistry syncStrategyRegistry(SnapshotFetchQueue sink) {
    return SyncStrategyRegistry.of(Map.of(
            Venue.BINANCE_SPOT,    new BinanceSpotSyncStrategy(sink),
            Venue.BINANCE_FUTURES, new BinanceFuturesSyncStrategy(sink)));
}
```

`SyncStrategyRegistry` is a tiny core class wrapping an `EnumMap<Venue, DepthSyncStrategy>` whose
`forVenue` throws on a missing venue — so a venue added to the enum without a strategy fails loudly
at first allocation rather than NPE-ing on the hot path. A concrete map bean rather than
`List<DepthSyncStrategy>` injection avoids putting a `venue()` method on the SPI that only Spring
wiring would call. P4 replaces this `@Bean` with one driven by `List<VenueAdapter>`; that is the
known and intended seam, and it is the only line P4 must edit here.

`BookSlotTable.allocate` resolves both at registration — never on the hot path:

```java
DepthSyncStrategy strategy = strategies.forVenue(instrument.venue());
staging[id] = new BookSlot(instrument,
                           new OrderBook(instrument, props.priceFilterThreshold()),
                           strategy.newContext(),
                           strategy);
```

**Bean graph.** Today: `DisruptorShardManager → OrderBookProcessor → SnapshotFetchQueue → @Lazy
DisruptorShardManager`. After: `DisruptorShardManager → BookSlotTable → SyncStrategyRegistry →
strategies → SnapshotFetchQueue → @Lazy DisruptorShardManager`. Same cycle, same existing `@Lazy`
break, one hop longer. No new `@Lazy` needed — verify at startup.

---

## 7. Parity traps

### 7.1 The classifier's non-`SYNCED` branch is not a no-op

Covered in §5. This is the highest-consequence trap in the step: the vision's own pseudo-code
contradicts it, and getting it wrong produces a silent feed regression (stale rows never dropped)
that no unit test in this step would catch. Pin it with an explicit assertion that a book which
desyncs still reaches the classifier.

### 7.2 Behaviour preserved verbatim, including the parts that look wrong

- The **strict `u < snapshotId`** discard in `discardInvalidDiffsFromBuffer`, deliberately contrary
  to Binance's spot documentation (spot books never sync under the documented `u <= snapshotId`).
  The comment moves with the code; test case 8 finally defends it with something other than prose.
- The `[U,u]` window check on the first buffered diff, the empty-buffer-after-discard resync, the
  first-event special case that skips sequence validation, `MAX_BUFFER_SIZE = 500`.
- **A `REST_MSG` arriving while the book is `PENDING` or `SYNCED` is not guarded.** Today it runs
  the full `applySnapshot`, finds an empty buffer after the discard step, and resyncs — so a late or
  duplicate snapshot knocks a `SYNCED` book back to `PENDING`. Arguably wrong; **out of scope**.
  Preserve it and pin it with a test so a future fix is a deliberate, visible change.

### 7.3 Deliberate deviations — the complete list

Only two, both immaterial, both stated up front so review is not a scavenger hunt:

1. **`firstSeenMillis` granularity.** One clock read per message instead of one per new level, so
   levels created by the same diff share a timestamp (they differ by <1 ms today). Snapshot loading
   already used a single shared `now`, so that path is unchanged.
2. **`/api/monitoring/orderbook` reports `"RECOVERING"` instead of `"SNAPSHOT_REQUESTED"`.** An
   admin-only diagnostic endpoint; no frontend contract under `.claude/docs/for-frontend/` names it.
   The parent plan's "P2 touches no contract" line refers to the feed and public REST payloads,
   which are genuinely untouched.

### 7.4 Hot-path austerity (unchanged, still binding)

No allocation in `onEvent` beyond the existing `PriceLevelEntry` on first sight of a level and the
`ArrayDeque` node when buffering. `newContext()` runs once per book at allocation. The parse loop
keeps `getStringCharacters` + `JavaDoubleParser`; no strings materialised, no `BigDecimal`, no
per-message logging above `debug`. The `(BinanceSyncContext) slot.ctx()` cast is a checkcast on a
monomorphic call site — free after JIT.

---

## 8. Tests — the deliverable, not an afterthought

There is currently **not one test for the market-data pipeline**, while every other module has some.
That is structural: the sync logic is welded to a `TreeMap`, a `volatile` field and a Spring-managed
queue. A strategy taking `(BookSlot, DepthEvent)` with a fake sink is a plain object drivable from
canned JSON, which is the strongest standalone justification for this step.

### 8.1 Fixtures

```java
final class FakeRecoverySink implements RecoverySink {
    boolean accept = true;
    final List<Integer> requests = new ArrayList<>();
    public boolean requestRecovery(BookSlot slot) {
        requests.add(slot.instrument().id());
        return accept;
    }
}
```

Plus a `SyncTestSupport` helper: `slot(Venue)` (builds `Instrument.of(...)` + `OrderBook` +
context + strategy), `diff(U, u, pu, bids, asks)`, `snapshot(lastUpdateId, bids, asks)`, and
`feed(slot, EventType, json)`. JSON is built as strings — the point is to exercise the real parser.

### 8.2 Cases

Parent §3's eight, plus the four this reading of the code added:

| # | Case | Expected |
|---|---|---|
| 1 | snapshot + ordered diffs | `SYNCED`, levels correct |
| 2 | spot `U` gap while `SYNCED` | `PENDING`→`RECOVERING`, one recovery request, buffer cleared |
| 3 | futures `pu` gap while `SYNCED` | same |
| 4 | `snapshotId` outside first buffered diff's `[U,u]` | resync, one request |
| 5 | empty buffer after `discardInvalidDiffsFromBuffer` | resync |
| 6 | 501st diff while `RECOVERING` | resync; **triggering diff discarded** |
| 7 | diff in `PENDING`, sink **refuses** | stays `PENDING`, nothing buffered, no `RECOVERING` |
| 8 | strict `u < snapshotId` discard (`u == snapshotId` is **kept**) | book syncs — the documented `<=` rule would leave it `PENDING` |
| 9 | diff in `PENDING`, sink accepts | `RECOVERING`, **triggering diff is in the buffer** (§3.2 row 1) |
| 10 | malformed diff JSON while `SYNCED` | resync, exactly **one** recovery request (the double-`recover` trap, §3.2) |
| 11 | `REST_MSG` arriving while `SYNCED` | knocked back to `PENDING` — pins §7.2's preserved quirk |
| 12 | snapshot whose drain loop hits a gap mid-buffer | resync, exactly **one** request |

Cases 10 and 12 are the ones that would catch a double-enqueue regression; neither is in the parent
plan's list.

### 8.3 `OrderBookTest` — storage semantics, independent of any venue

Zero quantity removes a level; a repeat `applyLevel` updates quantity in place and **preserves**
`firstSeenMillis`; `computeDistance` stores a **fraction** (0.05 = 5%), not a percentage; the
±`filterThreshold` sweep removes far levels from both sides; `clearLevels` empties both maps.
These are the invariants the classifier silently depends on and nothing currently asserts.

### 8.4 The parity question, and the one honest way to answer it

Everything above tests the code **after** the refactor. It proves the new code self-consistent, not
that it matches the old.

> **Recommended: land a characterization test against today's `OrderBook` first, in its own
> commit, before touching anything.**

`OrderBook.onDiff` / `applySnapshot` are drivable today with an eight-line test harness replicating
`OrderBookProcessor.process` (call, inspect `OrderBookResult`, fake the enqueue handshake). Write
cases 1–12 against that, watch them go green on unmodified code, then port the *same assertions* to
the new API in the SPI commit. The assertions are the parity evidence; only the driving changes.

Cost: one extra commit and a rewrite of the test bodies (not the expectations). Given that this is
the least-tested and highest-risk code in the application, and that P4 cannot be attempted safely
without a regression net here, it is worth it. Skipping it means the answer to "is it at parity?"
is a live-Binance smoke test and a careful read of §3.2's table — which is the situation this step
exists to end.

---

## 9. Commit sequence

| # | Commit | Notes |
|---|---|---|
| A | `EventType`: `DIFF` → `WS_MSG`, `SNAPSHOT` → `REST_MSG` | parent step 1b, still outstanding. Three call sites, compiler-verified, zero behaviour change. Landing it first keeps a rename out of the keystone diff |
| B | Characterization tests against today's `OrderBook` | §8.4. Recommended; skippable at the cost stated there. Test-only — no `src/main` changes |
| C | **Sync SPI + strategies + `OrderBook` surgery + wiring + ported tests** | atomic; see below |

Commit C is genuinely atomic: removing the parse path from `OrderBook` breaks the strategy's call
site immediately, so nothing compiles in between. The parent plan's suggested split — (2a) move
`lastUpdateId` + `diffBuffer` into a context object still owned by `OrderBook`, then (2b) extract
the strategy — buys one reviewable seam for one commit of pure churn. **Default: keep C whole.**
Revisit only if the diff comes out materially larger than the ~600 lines projected here.

Suggested order *within* C, each stage keeping the tree closer to compiling: SPI interfaces →
`BinanceSyncContext` → `BinanceDepthSyncStrategy` + subclasses (against the new `OrderBook` surface,
not yet written) → `OrderBook` surgery → `BookSlot`/`BookSlotTable`/registry/config →
`DepthEventHandler` + `DisruptorShardManager` → delete `OrderBookProcessor` and `OrderBookResult` →
port tests.

---

## 10. Decisions taken, and what is still open

### 10.1 Settled here (parent plan's open questions)

- **Splitting step 2** (parent §7.1) → keep whole; §9.
- **`Side` representation** (parent §7.2) → `boolean isBid`; §4.

### 10.2 Explicitly out of scope

Everything the parent plan defers, restated so C does not drift: the reset lane / `resetRequested`
and `tryNext()` backpressure (P3); the read-side storage seam — `OrderBookClassifier` keeps calling
`getBids()`/`getAsks()`, `MonitoringController` keeps calling `snapshotBids()`, including its
pre-existing `ConcurrentModificationException` risk (P3); per-venue snapshot queues and the
`@Scheduled` → explicit-scheduler change (step 3); `RequestBudget` (step 4); `StreamProtocol`
(step 5); config consolidation (step 6); docs (step 7). No Flyway migration, no feed or public REST
payload change.

Also unchanged, though tempting while in the file: `parseUField` and `parseUpperUField` still walk
the same buffered diff twice (parent §2.8). It is cold path — snapshot apply only — and folding them
into one pass is a real logic change. Leave it; step 3 or later.

### 10.3 Still open

1. **`exchange/binance/` flat vs. a `sync/` subpackage.** Flat for now (four new files). Steps 3–5
   add roughly six more; revisit at step 5 when the final population is known, and move once.
2. **Where `MAX_BUFFER_SIZE` is configured.** It moves to `BinanceDepthSyncStrategy` as a constant,
   matching today. It is venue-shaped and belongs under
   `screener.exchanges.binance.venues.<market>.*` — but that block does not exist until step 6, and
   inventing half of it now guarantees a rewrite. Constant now, config in step 6.
3. **Should `recover()` log at `warn` when the sink refuses?** Today a refusal is completely silent,
   so a persistently full queue is invisible. A rate-limited counter belongs in P3's health surface
   rather than a per-event log line — but the refusal path is now a named method with an obvious
   place to hang it. Note and defer.

---

## 11. Verification checklist

- [ ] `./mvnw clean package` green, including the new tests.
- [ ] App starts; bean graph resolves with no new `@Lazy` (§6).
- [ ] `sync count: spot=… fut=…` climbs to the same plateau as before, in comparable time.
- [ ] `/api/monitoring/orderbook` returns populated books; `state` reads `RECOVERING` mid-sync.
- [ ] Feed output over `/ws` unchanged for the same ticker set.
- [ ] `grep -rn "Venue\.BINANCE" exchange/book exchange/ingress` → **no hits**. This is the
      structural exit criterion for the step: no venue branch left in core storage or ingress.
