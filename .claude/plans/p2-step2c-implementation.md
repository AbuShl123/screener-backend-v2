# P2 Step 2 Commit C — Implementation Plan

**Status**: plan. No code written yet.
**Parent**: `.claude/plans/p2-step2-sync-spi.md` §9 commit C — *the keystone*.
**Prerequisites**: step 1 package move (`f9ed834`), commit A `EventType` rename (`3d476de`),
commit B characterization tests (`46c6773`), plus **commit B2 below**, which is new and blocking.
**Related**: `.claude/plans/p2-sync-spi-extraction.md`,
`.claude/plans/multi-exchange-architecture-vision.md` §3–§4,
`.claude/docs/orderbook-sync-algorithm.md`, `CLAUDE.md` hot-path rules (unchanged, still binding).

**Goal**: every Binance-specific sync decision moves behind `DepthSyncStrategy` +
`BookSyncContext` + `RecoverySink`; `OrderBook` becomes venue-agnostic storage; the ported tests
are the parity evidence. Binance stays the only implementation.

---

## 0. What changed since the parent plan was written

Three corrections, all discovered by reading the current tree rather than by re-reading the plan.

### 0.1 The tree is red — the Jackson-3 defect was fixed, its tests were not

`75b6a90` ("Apply exception-scope related bug fix in OrderBook.java") widened every
`catch (IOException e)` in `OrderBook` to `catch (Exception e)`. On Jackson 3
`tools.jackson.core.JacksonException extends RuntimeException`, so those catch blocks were dead
for parse failures; they now work. But the two commit-B tests that *pin* the defect were not
updated and fail today:

```
[ERROR] OrderBookSyncCharacterizationTest.malformedDiffWhileSyncedThrows
[ERROR] OrderBookSyncCharacterizationTest.malformedSnapshotThrows
        Expected tools.jackson.core.JacksonException to be thrown, but nothing was thrown.
```

Commit B exists so that C can port assertions from a **green** baseline. A red baseline makes
"port the assertions" meaningless. §1 below is the fix, landed as its own test-only commit.

**Consequence for the parent plan**: `p2-step2-sync-spi.md` §7.2's third bullet (preserve the
throwing behaviour) and §8.2 case 10 (as annotated in the code) are obsolete. Case 10 is now
achievable exactly as originally specified — resync plus exactly one recovery request. This is a
simplification for C, not a complication.

### 0.2 Commit C has a compiling intermediate after all

The parent plan calls C "genuinely atomic — nothing compiles in between". That is only true if
`OrderBook`'s old surface is removed *before* the new one is added. Adding the new surface
**additively first** gives five intermediate stages that each compile and keep the existing tests
green, with all breakage confined to the last two. See §11. C is still **one commit**; the stages
are an authoring order, not a commit sequence.

### 0.3 Three behaviours worth preserving that neither plan mentions

Found by reading `OrderBook` end to end. Full detail in §10; summarised here because they change
what C must be careful about:

- `resync()` clears only the diff buffer, **never the price levels**.
- `computeDistance()` is **never called on the pure-snapshot path** — a book can reach `SYNCED`
  with `distance == 0.0` on every level and no far-level sweep.
- The buffer-drain loop in `applySnapshot` **iterates without draining**, and the inner
  `applyLiveDiff` clears that same deque mid-iteration on failure.

---

## 1. Commit B2 — restore a green baseline

**Test-only. No `src/main` change.** Title suggestion:
`Phase 2 step 2 commit B2 - realign characterization tests with the exception-scope fix`

### 1.1 Rewrite the two failing tests

Both live in `OrderBookSyncCharacterizationTest.SequenceGaps`. Remove the
`import tools.jackson.core.JacksonException;` and the `assertThrows` import, and delete the long
javadoc block describing the dead-catch defect — it documents a bug that no longer exists.

**`malformedDiffWhileSyncedThrows` → `malformedDiffWhileSyncedResyncs`** (plan case 10):

```java
@Test
@DisplayName("10 — a malformed diff while SYNCED resyncs with exactly one recovery request")
void malformedDiffWhileSyncedResyncs() {
    Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));
    assertEquals(OrderBookState.SYNCED, h.book.getState());

    String truncated = "{\"e\":\"depthUpdate\",\"U\":121,\"u\":130,\"pu\":120,\"b\":[[\"99.0\",";
    h.wsMsg(truncated);

    assertEquals(OrderBookState.SNAPSHOT_REQUESTED, h.book.getState());
    assertEquals(2, h.recoveryRequests, "exactly one resync — not one per nested failure");
    assertEquals(0, bufferSize(h.book));
}
```

Path: `onDiff` → `SYNCED` → `applyLiveDiff` → parse throws → `catch (Exception)` → `resync()` →
`NEEDS_RESYNC` → the harness enqueues once and calls `markSnapshotRequested()`.

**`malformedSnapshotThrows` → `malformedSnapshotResyncs`**:

```java
@Test
@DisplayName("a malformed snapshot resyncs instead of escaping applySnapshot")
void malformedSnapshotResyncs() {
    Harness h = new Harness(FUTURES, SyncTestSupport.FILTER);
    h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));
    assertEquals(1, h.recoveryRequests);

    h.restMsg("{\"lastUpdateId\":105,\"bids\":[[\"99.0\",");

    assertEquals(OrderBookState.SNAPSHOT_REQUESTED, h.book.getState());
    assertEquals(2, h.recoveryRequests);
    assertEquals(0, bufferSize(h.book));
}
```

Path: `parseSnapshotEvent` catches and returns `-1` → `applySnapshot` sees `snapshotId == -1` →
`resync()`.

### 1.2 Add two tests that pin §0.3's traps

These are the behaviours C is most likely to "clean up" by accident. Nothing asserts them today.

**Trap (a) — a resync must not clear the price levels.** Add to `SequenceGaps`:

```java
@Test
@DisplayName("a resync clears the diff buffer but leaves the price levels in place")
void resyncKeepsPriceLevels() {
    Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));

    h.wsMsg(diff(FUTURES, 121, 130, 999, "", ""));   // pu gap

    assertEquals(OrderBookState.SNAPSHOT_REQUESTED, h.book.getState());
    assertEquals(0, bufferSize(h.book));
    // Stale levels survive until the next snapshot's clear-and-load. The classifier is gated on
    // state, so this is invisible in the feed — but /api/monitoring/orderbook shows it, and the
    // snapshot path depends on the book NOT being emptied before validation succeeds.
    assertTrue(h.book.getBids().containsKey(99.0));
    assertTrue(h.book.getAsks().containsKey(101.0));
}
```

**Trap (b) — the pure-snapshot path never sweeps.** Add to `SnapshotValidation`:

```java
@Test
@DisplayName("syncing on a single buffered diff leaves distances uncomputed and far levels unswept")
void singleBufferedDiffSyncsWithoutComputingDistance() {
    Harness h = new Harness(FUTURES, SyncTestSupport.FILTER);
    h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));    // the only buffered diff

    // Step 4 polls it, so step 5's drain loop iterates zero times — and computeDistance() is
    // reached only from applyLiveDiff. The book syncs with an unswept, undistanced level set.
    h.restMsg(snapshot(105, levels(lvl(99, 1), lvl(50, 1)), levels(lvl(101, 1))));

    assertEquals(OrderBookState.SYNCED, h.book.getState());
    assertTrue(h.book.getBids().containsKey(50.0), "far level survives: no filter sweep ran");
    assertEquals(0.0, h.book.getBids().get(99.0).distance);

    // The next live diff heals both.
    h.wsMsg(diff(FUTURES, 111, 120, 110, "", ""));
    assertFalse(h.book.getBids().containsKey(50.0));
    assertTrue(h.book.getBids().get(99.0).distance > 0.0);
}
```

### 1.3 Exit

`./mvnw test -Dtest='OrderBookSyncCharacterizationTest,OrderBookTest'` green — 23 tests, 0
failures. Then `./mvnw clean package` green. Only after that does C start.

---

## 2. File inventory for commit C

### New — `exchange/spi/` (core, exchange-agnostic)

| File | Purpose |
|---|---|
| `DepthSyncStrategy.java` | the SPI — two methods |
| `BookSyncContext.java` | marker interface; opaque to core, never inspected |
| `RecoverySink.java` | one method, returns `boolean` |

### New — `exchange/` (core, concrete)

| File | Purpose |
|---|---|
| `SyncStrategyRegistry.java` | `Venue → DepthSyncStrategy`, resolved once at slot allocation |

> Deviation from parent §1: the registry goes in `exchange/` root, **not** `exchange/spi/`. It is a
> concrete core class rather than an SPI interface, its only consumer is `BookSlotTable`, and
> keeping it out of `spi/` shrinks the `spi ↔ book ↔ ingress` package cycle by one edge.

### New — `exchange/binance/` (flat; revisit a `sync/` subpackage at step 5)

`BinanceSyncContext.java`, `BinanceDepthSyncStrategy.java` (abstract),
`BinanceSpotSyncStrategy.java`, `BinanceFuturesSyncStrategy.java`, `BinanceAdapterConfig.java`.

### Modified

| File | Change |
|---|---|
| `book/OrderBook.java` | ~405 → ~140 lines. See §5 |
| `book/OrderBookState.java` | `SNAPSHOT_REQUESTED` → `RECOVERING` |
| `book/BookSlot.java` | record gains `ctx` and `strategy` |
| `book/BookSlotTable.java` | constructor takes `SyncStrategyRegistry`; `allocate()` resolves strategy + calls `newContext()` |
| `ingress/DepthEventHandler.java` | absorbs slot lookup and the missing-slot counter; uses `shardIndex` |
| `ingress/DisruptorShardManager.java` | injects `BookSlotTable` instead of `OrderBookProcessor` |
| `recovery/SnapshotFetchQueue.java` | `implements RecoverySink`; `enqueue` → `requestRecovery`; **internals unchanged** |

### Deleted

- `ingress/OrderBookProcessor.java` — dissolves into `DepthEventHandler`.
- `book/OrderBookResult.java` — existed only as a return channel to the processor. Verified: the
  only consumers are `OrderBookProcessor` and `SyncTestSupport`.

### Untouched (verified by grep, stated so review does not go looking)

`DepthEvent`, `EventType`, `DepthEventFactory`, `DisruptorDepthMessageHandler`, everything under
`exchange/stream/`, `InstrumentUniverseService`, `InstrumentRegistry`, `Instrument`, `Venue`, all
of `analysis/`, `feed/`, `ws/`, `MonitoringController` **source**. No Flyway migration, no feed or
public REST payload change.

---

## 3. The SPI

```java
package dev.abu.screener_backend.marketdata.spi;

/** One configured instance per venue. Stateless — all per-book state lives in BookSyncContext. */
public interface DepthSyncStrategy {

    /** Cold: called once per book, at slot allocation. */
    BookSyncContext newContext();

    /** Hot: called for every event routed to this instrument, on the shard's consumer thread. */
    void onEvent(BookSlot slot, DepthEvent event);
}

/** Opaque per-book sync state. Core stores it in the slot and never inspects it. */
public interface BookSyncContext {
}

/** How one venue repairs a desynced book. The strategy's only collaborator. */
public interface RecoverySink {
    /** @return false if the venue refused (queue at capacity) — the book must NOT start buffering. */
    boolean requestRecovery(BookSlot slot);
}
```

Four load-bearing choices, restated from parent §2 because they are the ones review should check:

**(a) `BookSlot`, not `(book, ctx, event)`.** The slot already holds everything the strategy needs
and everything the sink needs behind it (`instrument().nativeSymbol()` for the snapshot URL,
`instrument().venue()` for queue selection). One argument, one dereference — and P3's
`resetRequested` flag lands on the slot without touching this signature.

**(b) `RecoverySink` takes the slot, not an `int`.** An `int` would force the sink to hold the
`BookSlotTable` to get back to the symbol — a dependency it does not otherwise need, and a second
array load on a path that already has the slot in hand.

**(c) `requestRecovery` returns `boolean`.** `SnapshotFetchQueue` refuses at capacity, and that
refusal is what bounds how many books simultaneously hold a 500-entry diff buffer during the
startup ramp. A `void` sink would leave a book buffering against a snapshot nobody is fetching.

**(d) `BookSyncContext` has no methods.** A `reset()` has no caller until P3's reset lane. Per the
parent plan's governing rule — an interface method with no implementation and no caller is a guess
— it is not written today.

**Accepted package cycle**: `spi → book` (for `BookSlot`) and `spi → ingress` (for `DepthEvent`),
against `book → spi` (for the two new `BookSlot` fields). Java permits it; it is inherent to
putting the hot-path record and the SPI in different packages, and is accepted deliberately rather
than worked around with a fourth package.

---

## 4. Binance implementation

### 4.1 Context

```java
package dev.abu.screener_backend.marketdata.binance;

/** Package-private field access is deliberate: the strategy and its tests are in this package. */
final class BinanceSyncContext implements BookSyncContext {
    long lastUpdateId;
    final ArrayDeque<String> diffBuffer = new ArrayDeque<>();   // raw diff JSON; RECOVERING only
}
```

Allocated once per book at `BookSlotTable.allocate()`. Allocation-free at steady state.

### 4.2 The single-`recover()` rule

The most important structural rule in this step:

> `recover(slot, ctx)` — the method that clears the buffer, drives the book to `PENDING` and calls
> the sink — is invoked **at most once per event**, from the top-level `onEvent` dispatch. Every
> internal helper returns a `boolean` and never calls the sink itself.

Today's code achieves this accidentally, by routing `OrderBookResult` up to `OrderBookProcessor`,
which holds the only `enqueue` call. Cases 10 and 12 exist to catch a double-enqueue regression.

```java
@Override
public void onEvent(BookSlot slot, DepthEvent event) {
    BinanceSyncContext ctx = (BinanceSyncContext) slot.ctx();
    boolean ok = (event.type == EventType.REST_MSG)
            ? applySnapshot(slot, ctx, event.rawJson)
            : handleDiff(slot, ctx, event.rawJson);
    if (!ok) recover(slot, ctx);
}

private boolean handleDiff(BookSlot slot, BinanceSyncContext ctx, String rawJson) {
    OrderBook book = slot.book();
    return switch (book.getState()) {
        case PENDING -> {
            log.debug("[{}] Diff received: need snapshot", book.getLogName());
            // Ask first; start buffering only if the venue accepted.
            if (sink.requestRecovery(slot)) {
                book.markRecovering();
                ctx.diffBuffer.addLast(rawJson);        // the triggering diff IS buffered
            }
            yield true;                                 // refused → stay PENDING, drop, no recover()
        }
        case RECOVERING -> {
            if (ctx.diffBuffer.size() >= MAX_BUFFER_SIZE) {
                log.warn("[{}] Diff buffer overflow — forcing re-sync", book.getLogName());
                yield false;                            // → recover(); triggering diff discarded
            }
            ctx.diffBuffer.addLast(rawJson);
            yield true;
        }
        case SYNCED -> applyLiveDiff(slot, ctx, rawJson);
    };
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
| `PENDING` → `NEEDS_SNAPSHOT` → processor enqueues → `markSnapshotRequested()` → **re-feeds the diff** so `onDiff` buffers it | `PENDING` branch: sink accepts → `markRecovering()` → buffer the diff. The double-dispatch is gone; the buffered content is identical |
| `PENDING` → enqueue **refused** → returns; book stays `PENDING`, diff dropped, nothing buffered | same; `yield true` skips `recover()` |
| overflow → `resync()` (PENDING + clear) → `NEEDS_RESYNC` → enqueue → `markSnapshotRequested()`; **trigger discarded** | `yield false` → `recover()`; trigger discarded |
| gap or parse error in `applyLiveDiff` → same as overflow | same |
| any snapshot failure → `resync()` → `NEEDS_RESYNC` → enqueue | `applySnapshot` returns `false` → `recover()` |
| inner `applyLiveDiff` during the buffer drain → sets `PENDING` + clears, **no enqueue**; only the outer result reaches the processor | inner returns `false`, outer returns `false`, exactly one `recover()` |

Note the last row is also a **hazard removal**: today the inner `resync()` clears `diffBuffer`
while `applySnapshot`'s `for (String s : diffBuffer)` is iterating it. It escapes
`ConcurrentModificationException` only because the loop `return`s immediately. In the new shape
nothing clears the deque until `recover()` runs, after the loop has exited.

### 4.3 Method-by-method migration map

Everything below moves **verbatim** apart from the mechanical changes named in the right column.

| Today (`OrderBook`) | New (`BinanceDepthSyncStrategy`) | Mechanical change |
|---|---|---|
| `onDiff(String)` | `handleDiff(slot, ctx, rawJson) : boolean` | `OrderBookResult` → `boolean`; state read via `book.getState()` |
| `applySnapshot(String)` | `applySnapshot(slot, ctx, rawJson) : boolean` | `resync()` → `return false`; `bids.clear()/asks.clear()` → `book.clearLevels()`; the load loop → `book.applyLevel(...)` |
| `applyLiveDiff(String)` | `applyLiveDiff(slot, ctx, rawJson) : boolean` | venue `if/else` → `isSequenceGap(...)`; `computeDistance()` → `book.computeDistance()`; `lastUpdateId` → `ctx.lastUpdateId` |
| `applyLevelUpdatesFirstEvent(String)` | `applyFirstBufferedEvent(book, rawJson) : boolean` | `OrderBookResult.DROPPED` → `false` |
| `parseSnapshotEvent(...)` | same, private | none |
| `applyLevelsDirectly(p, map)` | `applyLevels(p, book, isBid, now)` | writes through `book.applyLevel(isBid, price, qty, now)` |
| `parseUField`, `parseUpperUField` | same, private | none (still two passes — parent §10.2 defers the merge) |
| `parseLevelsInto(p, deque)` | same, private | none |
| `discardInvalidDiffsFromBuffer(long)` | `discardInvalidDiffsFromBuffer(ctx, snapshotId)` | reads `ctx.diffBuffer` |
| `resync()` | *(deleted)* | replaced by `return false` + the single `recover()` |
| `MAX_BUFFER_SIZE = 500` | constant on `BinanceDepthSyncStrategy` | package-private, so the test can read it |

`applySnapshot`'s step order is preserved exactly: parse into two temporary
`ArrayDeque<double[]>` → discard invalid buffered diffs → validate `snapshotId ∈ [U,u]` of the
first buffered diff → **only then** `clearLevels()` and load → apply the first buffered event
without sequence validation → set `ctx.lastUpdateId = u` → drain the rest through `applyLiveDiff`
→ `markSynced()`.

> The snapshot path stays **two-pass** deliberately (vision §4.3 / parent §3.5). Streaming snapshot
> levels straight through `applyLevel` would destroy a book's contents before learning the snapshot
> is unusable, changing what the classifier sees between the snapshot arriving and the resync
> completing. It is cold path — one allocation burst per book per resync.

### 4.4 The one abstract method

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

// BinanceFuturesSyncStrategy
protected boolean isSequenceGap(long U, long pu, long lastUpdateId, String logName) {
    if (pu == lastUpdateId) return false;
    log.debug("[{}] pu gap: expected pu={}, got pu={}", logName, lastUpdateId, pu);
    return true;
}
```

Both keep today's exact debug lines. Both are called from the same place today's branch sits:
inside the parse loop, on first sight of `b`/`a`, guarded by `sequenceValidated`. Binance
guarantees `U`/`u`/`pu` precede `b`/`a` in the diff object; that assumption and its comment move
verbatim. Passing `logName` keeps the subclasses field-free — it is precomputed on `Instrument`,
so it costs nothing.

### 4.5 Parse-and-apply stays one pass

`applyLevels` calls `book.applyLevel(...)` **from inside** the `JsonParser` loop — no intermediate
DTO, no second iteration, exactly what `applyLevelsDirectly` does today. The
`getStringCharacters` + `getStringOffset` + `getStringLength` + `JavaDoubleParser` shape is
preserved character for character.

---

## 5. `OrderBook` after surgery

**Keeps** (universal per vision §4.4): `instrumentId`, `logName`, `volatile state`,
`filterThreshold`, the two `TreeMap`s, `PriceLevelEntry` lifecycle, the mid-price filter sweep,
`getBids`/`getAsks`/`snapshotBids`/`snapshotAsks`.

**Loses**: `venue` (zero external readers — verified), `lastUpdateId`, `diffBuffer`,
`MAX_BUFFER_SIZE`, `JSON_FACTORY`, `markSnapshotRequested`, `onDiff`, `applySnapshot`,
`applyLiveDiff`, `applyLevelUpdatesFirstEvent`, `parseSnapshotEvent`, `applyLevelsDirectly`,
`parseUField`, `parseUpperUField`, `parseLevelsInto`, `discardInvalidDiffsFromBuffer`, `resync`.
With `venue` goes the last `if (market == …)` branch in the storage layer.

**New surface**:

```java
public void applyLevel(boolean isBid, double price, double qty, long nowMillis);
public void clearLevels();
public void computeDistance();          // was private
public void markPending();
public void markRecovering();
public void markSynced();
```

```java
public void applyLevel(boolean isBid, double price, double qty, long nowMillis) {
    TreeMap<Double, PriceLevelEntry> map = isBid ? bids : asks;
    if (qty == 0.0) {
        map.remove(price);
        return;
    }
    PriceLevelEntry entry = map.get(price);
    if (entry == null) map.put(price, new PriceLevelEntry(qty, nowMillis));
    else               entry.quantity = qty;
}
```

Three decisions inside that:

**`boolean isBid`, not a `Side` enum** (parent §7 open question 2, settled). The call site already
has the branch as `"b".equals(field)`; a boolean threads it through with no new type, and it is
exactly what the P6 primitive book will use to pick between two array pairs.

**`nowMillis` is a parameter.** The strategy reads the clock once per message and passes it down,
instead of once per new level as today. Fewer clock calls on the hot path, deterministic
`firstSeenMillis` within one message, and — the reason it is worth doing — it makes
`firstSeenMillis` assertable without introducing a `Clock` indirection into the hot path.

**Three named transitions, not a `setState` setter.** They enumerate the legal set and stay
greppable. `state` stays `volatile` — `MonitoringController` reads it from a Tomcat thread and
`BookSlotTable.logSyncCount` from the Spring scheduler thread — but **only the strategy writes it**
now. Delete the class javadoc's claim that the `SnapshotFetchQueue` scheduler thread writes it: the
only writer was ever `OrderBookProcessor`, on the consumer thread. Delete the "Transitional debt:
venue" section with the field.

**`applyLevel` is reused for snapshot loading.** After `clearLevels()`, a `qty == 0` level becomes
`remove` on an absent key — equivalent to today's `if (level[1] > 0)` skip in step 3. This keeps
one write path into the maps, which is the write half of P3's storage-accessor seam landed for
free.

**`markPending()` must not touch the levels.** See §10 trap (a).

---

## 6. Slot, table, registry, wiring

```java
public record BookSlot(Instrument instrument,
                       OrderBook book,
                       BookSyncContext ctx,
                       DepthSyncStrategy strategy) {}
```

```java
// BookSlotTable.allocate — the strategy is resolved at registration, never on the hot path
DepthSyncStrategy strategy = strategies.forVenue(instrument.venue());
staging[id] = new BookSlot(instrument,
                           new OrderBook(instrument, props.priceFilterThreshold()),
                           strategy.newContext(),
                           strategy);
```

`BookSlotTable` gains `SyncStrategyRegistry` as a second constructor dependency
(`@RequiredArgsConstructor` already in place). `allocate()` is called only from
`InstrumentUniverseService.apply()`, on the discovery thread, before `publish()` — that ordering is
unchanged and still load-bearing.

```java
public final class SyncStrategyRegistry {
    private final EnumMap<Venue, DepthSyncStrategy> byVenue;

    public static SyncStrategyRegistry of(Map<Venue, DepthSyncStrategy> strategies) { … }

    public DepthSyncStrategy forVenue(Venue venue) {
        DepthSyncStrategy s = byVenue.get(venue);
        if (s == null) throw new IllegalStateException("No DepthSyncStrategy registered for " + venue);
        return s;
    }
}
```

Throwing on a missing venue means a venue added to the enum without a strategy fails loudly at
first allocation rather than NPE-ing on the hot path.

```java
// exchange/binance/BinanceAdapterConfig
@Bean
SyncStrategyRegistry syncStrategyRegistry(SnapshotFetchQueue sink) {
    return SyncStrategyRegistry.of(Map.of(
            Venue.BINANCE_SPOT,    new BinanceSpotSyncStrategy(sink),
            Venue.BINANCE_FUTURES, new BinanceFuturesSyncStrategy(sink)));
}
```

A concrete map bean rather than `List<DepthSyncStrategy>` injection avoids putting a `venue()`
method on the SPI that only Spring wiring would call. **P4 replaces this one `@Bean` with one
driven by `List<VenueAdapter>`** — the known and intended seam, and the only line P4 must edit here.

**Bean graph.** Today:
`DisruptorShardManager → OrderBookProcessor → {BookSlotTable, SnapshotFetchQueue}`, with
`SnapshotFetchQueue → @Lazy DisruptorShardManager`.
After: `DisruptorShardManager → BookSlotTable → SyncStrategyRegistry → strategies →
SnapshotFetchQueue → @Lazy DisruptorShardManager`. Same cycle, same existing `@Lazy` break, one hop
longer. **No new `@Lazy` should be needed — verify at startup rather than assuming.**

`SnapshotFetchQueue` gains `implements RecoverySink` and renames `enqueue` → `requestRecovery`.
Its two internal `enqueue(slot)` re-queue calls on error become `requestRecovery(slot)`.
Everything else in it is untouched; the per-venue split is step 3, not C.

---

## 7. The consumer loop

`OrderBookProcessor` disappears; `DepthEventHandler` becomes the whole loop:

```java
public class DepthEventHandler implements EventHandler<DepthEvent> {

    private static final long MISSING_SLOT_LOG_INTERVAL_MS = 60_000;

    private final int shardIndex;
    private final BookSlotTable slots;
    private final OrderBookClassifier classifier;

    /** Single-threaded per shard — plain longs, no atomics. */
    private long missingSlots;
    private long missingSlotsLoggedAt;

    @Override
    public void onEvent(DepthEvent event, long sequence, boolean endOfBatch) {
        BookSlot slot = slots.get(event.instrumentId);
        if (slot == null) {
            noteMissingSlot(event.instrumentId);
            event.clear();
            return;
        }
        slot.strategy().onEvent(slot, event);
        classifier.process(slot.instrument(), slot.book());
        event.clear();
    }
}
```

Two notes:

**The classifier call stays unconditional.** Vision §3.5 sketches
`if (book.getState() == SYNCED) classifier.process(...)`. **That sketch is wrong for this
codebase.** `OrderBookClassifier.classifyOne` handles the non-`SYNCED` case itself
(`OrderBookClassifier.java:140-146`): it emits a `DROP` update and drops the symbol to `LOW`
activity. Gating on `SYNCED` in the consumer would silently strip every desync notification out of
the feed — a regression no unit test in this step would catch.

**The missing-slot counter becomes per-shard.** It is `AtomicLong` today only because
`OrderBookProcessor` was a shared singleton. One handler per shard, single-threaded, so plain
`long` fields suffice — and the log line gains the shard index, which finally gives `shardIndex`
the caller it has been missing.

`DisruptorShardManager` swaps its `OrderBookProcessor` field for `BookSlotTable` and passes it into
each handler.

---

## 8. Tests

### 8.1 Layout — where each file ends up

`SyncTestSupport` currently lives in `exchange.book` and reaches into `OrderBook.diffBuffer` by
reflection. After C that state is `BinanceSyncContext.diffBuffer` in `exchange.binance`, so:

| Today | After C | Why |
|---|---|---|
| `exchange/book/OrderBookSyncCharacterizationTest` | `exchange/binance/BinanceDepthSyncStrategyTest` | it tests the Binance algorithm, which now lives there |
| `exchange/book/SyncTestSupport` (harness + JSON builders + reflection) | `exchange/binance/SyncTestSupport` + `exchange/binance/FakeRecoverySink` | the reflection helper **disappears** — `h.ctx().diffBuffer.size()` is a package-private field read |
| `exchange/book/OrderBookTest` | stays, **rewritten** | drives `applyLevel`/`clearLevels`/`computeDistance` directly; stops depending on `Harness` entirely |

`OrderBookTest` becoming venue-free is the structural exit criterion applied to the tests: after C
it must not import `Venue` or any Binance class. Two of its current cases —
`snapshotReplacesPreviousLevels` and `zeroQuantitySnapshotLevelsAreSkipped` — are sync-path tests
wearing a storage hat and **move to `BinanceDepthSyncStrategyTest`**.

### 8.2 New fixtures

```java
final class FakeRecoverySink implements RecoverySink {
    boolean accept = true;
    final List<Integer> requests = new ArrayList<>();

    @Override public boolean requestRecovery(BookSlot slot) {
        requests.add(slot.instrument().id());
        return accept;
    }
}
```

```java
static final class Harness {
    final BookSlot slot;
    final FakeRecoverySink sink = new FakeRecoverySink();
    private final DepthEvent event = new DepthEvent();   // reused, like the real ring slot

    OrderBook book()            { return slot.book(); }
    BinanceSyncContext ctx()    { return (BinanceSyncContext) slot.ctx(); }
    int recoveryRequests()      { return sink.requests.size(); }

    void wsMsg(String json)   { feed(EventType.WS_MSG, json); }
    void restMsg(String json) { feed(EventType.REST_MSG, json); }

    private void feed(EventType type, String json) {
        event.type = type; event.instrumentId = slot.instrument().id(); event.rawJson = json;
        slot.strategy().onEvent(slot, event);
        event.clear();
    }
}
```

The JSON builders (`diff`, `snapshot`, `lvl`, `levels`) and the `synced(...)` driver move across
unchanged. `synced(...)` still works because the new `PENDING` branch buffers the triggering diff
directly, producing the same buffer contents the old re-feed did.

### 8.3 Ported cases — the parity evidence

Every case from `OrderBookSyncCharacterizationTest` ports with **the same expectations**; only the
driving changes. Three mechanical substitutions across the file:

- `OrderBookState.SNAPSHOT_REQUESTED` → `OrderBookState.RECOVERING`
- `bufferSize(h.book)` → `h.ctx().diffBuffer.size()`
- `h.recoveryRequests` → `h.recoveryRequests()`; `h.sinkAccepts = false` → `h.sink.accept = false`

| # | Case | Expectation (unchanged) |
|---|---|---|
| 1 | snapshot + ordered diffs | `SYNCED`, levels correct, 1 request |
| 2 | spot `U` gap while `SYNCED` | `RECOVERING`, 2 requests, buffer cleared |
| 3 | futures `pu` gap while `SYNCED` | same |
| 4 | `snapshotId` outside first buffered diff's `[U,u]` | resync, 2 requests |
| 5 | empty buffer after discard | resync, 2 requests |
| 6 | 501st diff while `RECOVERING` | resync, trigger discarded, buffer cleared |
| 7 | diff in `PENDING`, sink refuses | stays `PENDING`, nothing buffered, 1 request |
| 8 | strict `u < snapshotId` (`u == snapshotId` kept) | book syncs |
| 9 | diff in `PENDING`, sink accepts | `RECOVERING`, triggering diff in the buffer |
| 10 | malformed diff while `SYNCED` | resync, **exactly one** request *(green as of B2)* |
| 11 | `REST_MSG` while `SYNCED` | knocked back — preserved quirk |
| 12 | gap part-way through the buffer drain | resync, **exactly one** request |
| — | first buffered event skips sequence validation | applies without checking `pu` |
| — | buffer left populated after `SYNCED` | residue retained |
| — | malformed snapshot | resync, 2 requests *(green as of B2)* |
| — | resync keeps price levels *(B2)* | stale levels survive |
| — | single-buffered-diff sync leaves distances uncomputed *(B2)* | far level survives, `distance == 0.0` |
| — | snapshot replaces previous levels *(moved from `OrderBookTest`)* | stale levels gone |
| — | zero-qty snapshot levels not loaded *(moved)* | level absent |

**Cases 10 and 12 are the double-`recover()` guards.** They are the reason §4.2's rule exists;
neither is in the parent plan's original list.

### 8.4 `OrderBookTest` — rewritten, venue-free

Same invariants, driven directly:

- `applyLevel(isBid, p, 0, now)` removes the level.
- A repeat `applyLevel` on an existing price updates `quantity` in place and **preserves**
  `firstSeenMillis` — assert `assertSame` on the `PriceLevelEntry`.
- `computeDistance()` stores a **fraction** (0.05 = 5%), not a percentage.
- `computeDistance()` sweeps levels beyond ±`filterThreshold` from **both** sides.
- `computeDistance()` is a no-op when either side is empty.
- `clearLevels()` empties both maps.

---

## 9. Deliberate deviations — the complete list

Stated up front so review is not a scavenger hunt. Three, all immaterial:

1. **`firstSeenMillis` granularity.** One clock read per message instead of one per new level, so
   levels created by the same diff share a timestamp (they differ by <1 ms today). The snapshot
   path already used a single shared `now`, so that path is unchanged.
2. **`/api/monitoring/orderbook` reports `"RECOVERING"` instead of `"SNAPSHOT_REQUESTED"`.** An
   admin-only diagnostic; nothing under `.claude/docs/for-frontend/` names it. "P2 touches no
   contract" refers to the feed and public REST payloads, which are genuinely untouched.
3. **The buffer-drain `ConcurrentModificationException` hazard disappears** (§4.2). Today the inner
   `applyLiveDiff` clears the deque `applySnapshot` is iterating; it survives only because the loop
   returns immediately. The new shape defers all clearing to `recover()`, after the loop exits.
   Behaviour under test is identical; the latent hazard is gone.

---

## 10. Parity traps to defend

### (a) `markPending()` must not clear the price levels

Today `resync()` clears **only** `diffBuffer`. The `TreeMap`s keep stale contents until the next
snapshot's step 3 `clearLevels()`. A naive "reset the book" implementation would clear levels and:
change what `/api/monitoring/orderbook` shows for a desynced book, and break the §4.3 rule that a
book is destroyed only *after* its snapshot validates. Pinned by B2's `resyncKeepsPriceLevels`.

### (b) `computeDistance()` is never called on the pure-snapshot path

`applySnapshot` steps 3 and 4 load the snapshot and apply the first buffered event without a filter
sweep. Only step 5's `applyLiveDiff` drain calls `computeDistance()` — and **that loop runs zero
times when the buffer held exactly one diff**, because step 4 polled it. So a book can reach
`SYNCED` with `distance == 0.0` on every level and no far-level sweep, for one classification pass.
Since the classifier compares `distance` against `maxDistance`, that pass sees every level as
at-mid.

> This looks like a bug and probably is. **Preserve it verbatim in C.** Fixing it is a behaviour
> change with a feed-visible effect; it gets its own commit and its own test after C, or the diff
> stops being a refactor. Pinned by B2's `singleBufferedDiffSyncsWithoutComputingDistance`.

### (c) The buffer-drain loop must not route through `handleDiff`

Step 5 calls `applyLiveDiff` **directly**, while the book is still `RECOVERING`. Routing the drain
through `handleDiff` would re-buffer each diff instead of applying it, and the book would never
sync. The state-based dispatch belongs only at the `onEvent` entry point.

### (d) The diff buffer is left populated after `SYNCED`

Step 5 iterates `diffBuffer` without draining it (pinned by `bufferIsNotClearedOnceSynced`).
`ctx.diffBuffer` must keep that residue: it is what case 11's `discardInvalidDiffsFromBuffer` walks
when a late `REST_MSG` arrives on a `SYNCED` book.

### (e) `requestRecovery` before `markRecovering` is load-bearing ordering

The queue's scheduler thread can dispatch the moment the slot lands in its map — before the
consumer thread sets the state. That is only safe because the response is published into the ring
buffer rather than written to the book directly. Keep both properties together; do not "tidy" the
state transition to happen first.

### (f) Behaviour preserved verbatim, including the parts that look wrong

- The **strict `u < snapshotId`** discard in `discardInvalidDiffsFromBuffer`, deliberately contrary
  to Binance's spot documentation (spot books never sync under the documented `u <= snapshotId`).
  The comment moves with the code; case 8 defends it.
- The `[U,u]` window check on the first buffered diff; the empty-buffer-after-discard resync; the
  first-event special case that skips sequence validation; `MAX_BUFFER_SIZE = 500`.
- **A `REST_MSG` arriving while `PENDING` or `SYNCED` is not guarded.** It runs the full
  `applySnapshot`, finds an empty buffer after the discard step, and resyncs — so a late or
  duplicate snapshot knocks a `SYNCED` book back. Arguably wrong; **out of scope**. Preserved and
  pinned by case 11 so a future fix is deliberate and visible.

### (g) Hot-path austerity (unchanged, still binding)

No allocation in `onEvent` beyond the existing `PriceLevelEntry` on first sight of a level and the
`ArrayDeque` node when buffering. `newContext()` runs once per book at allocation. The parse loop
keeps `getStringCharacters` + `JavaDoubleParser` — no strings materialised, no `BigDecimal`, no
per-message logging above `debug`. The `(BinanceSyncContext) slot.ctx()` cast is a checkcast on a
monomorphic call site — free after JIT.

---

## 11. Implementation order within C

Contrary to the parent plan's "nothing compiles in between", five of the seven stages compile and
keep the existing tests green. Breakage is confined to stages 6–7. C remains **one commit**; this
is an authoring order.

| Stage | Work | Tree state |
|---|---|---|
| 1 | `exchange/spi/` three interfaces + `exchange/SyncStrategyRegistry` | compiles; nothing references them |
| 2 | `OrderBookState`: `SNAPSHOT_REQUESTED` → `RECOVERING` (update the 9 test assertion sites). `OrderBook`: **add** `applyLevel`, `clearLevels`, `markPending/Recovering/Synced`, make `computeDistance` public — old methods still present | compiles, **tests green** |
| 3 | `BinanceSyncContext` + `BinanceDepthSyncStrategy` + the two subclasses, written against the new `OrderBook` surface | compiles, tests green; new code unreachable |
| 4 | `BookSlot` gains `ctx`/`strategy`; `BookSlotTable` takes the registry; `BinanceAdapterConfig`; `SnapshotFetchQueue implements RecoverySink` (`enqueue` → `requestRecovery`) | compiles, tests green |
| 5 | `DepthEventHandler` absorbs the loop; `DisruptorShardManager` injects `BookSlotTable`; **delete** `OrderBookProcessor` | compiles, tests green; the live path now runs through the strategy |
| 6 | Strip the old parse/sync code from `OrderBook`; **delete** `OrderBookResult` | **breaks the old tests** — expected |
| 7 | Move + port `OrderBookSyncCharacterizationTest` → `BinanceDepthSyncStrategyTest`; new `SyncTestSupport` + `FakeRecoverySink` in `exchange/binance`; rewrite `OrderBookTest` | green |

After stage 5 the application is already running on the new path with the old code dead — a good
point to smoke-test against live Binance before committing to the deletion in stage 6.

---

## 12. Verification checklist

- [ ] **B2 first**: `./mvnw test -Dtest='OrderBookSyncCharacterizationTest,OrderBookTest'` green
      before C starts.
- [ ] `./mvnw clean package` green, including the ported tests.
- [ ] App starts; bean graph resolves with **no new `@Lazy`** (§6).
- [ ] `grep -rn "Venue\.BINANCE" src/main/java/dev/abu/screener_backend/exchange/book src/main/java/dev/abu/screener_backend/exchange/ingress` → **no hits**. The structural exit criterion: no venue branch left in core storage or ingress.
- [ ] `grep -rn "JsonParser\|JavaDoubleParser" src/main/java/dev/abu/screener_backend/exchange/book` → **no hits**. Parsing is fully out of storage.
- [ ] `grep -rn "Venue\|binance" src/test/java/dev/abu/screener_backend/exchange/book` → **no hits**. `OrderBookTest` is venue-free.
- [ ] `sync count: spot=… fut=…` climbs to the same plateau as before, in comparable time.
- [ ] `/api/monitoring/orderbook` returns populated books; `state` reads `RECOVERING` mid-sync.
- [ ] Feed output over `/ws` unchanged for the same ticker set.

---

## 13. Out of scope, and the follow-ups C creates

Unchanged from the parent plan, restated so C does not drift: the reset lane / `resetRequested` and
`tryNext()` backpressure (P3); the **read**-side storage seam — `OrderBookClassifier` keeps calling
`getBids()`/`getAsks()`, `MonitoringController` keeps calling `snapshotBids()` including its
pre-existing `ConcurrentModificationException` risk (P3); per-venue snapshot queues and the
`@Scheduled` → explicit-scheduler change (step 3); `RequestBudget` (step 4); `StreamProtocol`
(step 5); config consolidation (step 6); docs (step 7). No Flyway migration, no feed or public REST
payload change.

Also unchanged though tempting while in the file: `parseUField` and `parseUpperUField` still walk
the same buffered diff twice. Cold path — snapshot apply only — and folding them into one pass is a
real logic change. Leave it.

**New follow-ups C surfaces, to be tracked, not done here:**

1. **Fix trap (b)** — call `computeDistance()` on the snapshot path so a book never syncs with
   uncomputed distances and an unswept level set. Own commit, own test, after C.
2. **Guard `REST_MSG` on a `SYNCED` book** (trap (f), third bullet). Own commit.
3. **Log or count the recovery refusal.** Today a refusal is completely silent, so a persistently
   full snapshot queue is invisible. A rate-limited counter belongs in P3's health surface — but
   after C the refusal path is a named method with an obvious place to hang it.
4. **`exchange/binance/` flat vs. a `sync/` subpackage.** Flat for now (five new files). Steps 3–5
   add roughly six more; revisit at step 5 when the final population is known, and move once.
