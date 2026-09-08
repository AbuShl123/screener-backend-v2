# P2 — Finish the Binance Wiring

**Goal**: bring the tree from "WIP, does not compile" to *"`./mvnw clean package` is green and the
app runs against live Binance with books syncing and the feed flowing."*

**Non-goal**: no second venue, no `VenueAdapter`, no `StreamProtocol`, no `RequestBudget`, no
generic `SnapshotRequestQueue`. Those are P2 steps 3–5 and stay untouched. The only structural
piece added here is the smallest thing that makes `BookSlot` complete — a `Venue → DepthSyncStrategy`
lookup.

**Assessed at**: commit `fb5f15d`, branch `feature/multi-exchange`, working tree carrying an
unrelated CORS edit in `SecurityConfig` and the untracked `OrderBookOld.java`.

---

## 0. What the assessment actually found

`.claude/docs/multi-exchange-progress.md` is accurate about the architecture and about §4 (the sync
algorithm). Its **gap list is slightly pessimistic** — two items it lists as outstanding have
already landed:

| Progress doc claim | Reality in code |
|---|---|
| §6.2.3 "subclass constructors still take `SnapshotFetchQueue`" | **Already fixed.** Both `BinanceSpotSyncStrategy` and `BinanceFuturesSyncStrategy` take `RecoverySink`. A `FakeRecoverySink` can be injected today. |
| §6.2.4 / §5 "`resetContext` should not be abstract" | **Already done.** There is no abstract `resetContext`; `recover()` calls the shared `BinanceSyncContext.reset()`, which clears all three fields. |

Everything else in §6.1 / §6.2 holds. The confirmed compile failure is exactly three files:

```
DisruptorShardManager:58     DepthEventHandler(int, OrderBookProcessor, OrderBookClassifier) — needs 4 args now
OrderBookProcessor:48–59     OrderBook.applySnapshot / onDiff / markSnapshotRequested, SnapshotFetchQueue.enqueue — all removed
MonitoringController:130–131 OrderBook.snapshotBids() / snapshotAsks() — removed
```

The **test tree is also broken** and the progress doc understates it: `SyncTestSupport`,
`OrderBookTest` and `OrderBookSyncCharacterizationTest` all drive the deleted `OrderBook` API
(`new OrderBook(Instrument, double)`, `onDiff`, `applySnapshot`, `markSnapshotRequested`, and a
reflective read of the `diffBuffer` field that now lives on `BinanceSyncContext`). So `./mvnw test`
cannot even reach a red/green verdict until step 5.

And the runtime landmine confirmed: `BookSlotTable.allocate` builds
`new BookSlot(instrument, new OrderBook(...), null, null)`. Even after the tree compiles,
`slot.strategy().onEvent(...)` NPEs on the first depth message. **Step 1 is what makes the app
actually work; steps 2–4 are what make it compile.**

---

## 1. `SyncStrategyRegistry` — the missing wiring *(do this first)*

The one piece of new structure. Keep it deliberately small: a `Venue → DepthSyncStrategy` map, not
a `VenueAdapter`.

- New `exchange/spi/SyncStrategyRegistry` (or `exchange/SyncStrategyRegistry`) exposing
  `DepthSyncStrategy forVenue(Venue)`, throwing on an unmapped venue rather than returning null —
  an unmapped venue is a startup bug, not a runtime condition.
- Populate it from a `@Configuration` in `exchange/binance/` (`BinanceAdapterConfig`) that maps
  `BINANCE_SPOT → BinanceSpotSyncStrategy` and `BINANCE_FUTURES → BinanceFuturesSyncStrategy`.
  This is where the vision's "one set of adapter beans per venue" starts; keeping the map
  construction in the *adapter* package, not in core, is what makes Bybit additive later.
- The two strategies are already `@Component`s autowiring the single `RecoverySink` bean
  (`SnapshotFetchQueue`), so they need no change. If you prefer explicit `@Bean`s in
  `BinanceAdapterConfig`, drop the `@Component`s — but do not have both.

Then **`BookSlotTable.allocate` completes the slot**:

```
new BookSlot(instrument, new OrderBook(threshold), strategy, strategy.newContext())
```

with `strategy = registry.forVenue(instrument.venue())`.

> **Bean-cycle check.** This adds
> `BookSlotTable → SyncStrategyRegistry → BinanceSpotSyncStrategy → RecoverySink(SnapshotFetchQueue)
> → DisruptorShardManager → BookSlotTable`. It is closed by the `@Lazy` already on
> `SnapshotFetchQueue`'s `DisruptorShardManager` — but that `@Lazy`'s comment names
> `OrderBookProcessor` in the cycle, and that class is about to be deleted. **Update the comment to
> describe the new cycle**, or the next person removes the `@Lazy` and the context fails to start.
>
> If you would rather not give `BookSlotTable` a new collaborator, the alternative is to resolve the
> strategy in `InstrumentUniverseService.apply` and pass it into `allocate(Instrument, DepthSyncStrategy)`.
> Same result; `allocate` is the single construction point either way. Pick one and don't split it.

---

## 2. `DepthEventHandler` and `DisruptorShardManager`

`DepthEventHandler` still declares an unused `OrderBookProcessor` field, and `DisruptorShardManager`
still constructs it with the old three-arg shape and still injects `OrderBookProcessor`.

- Drop the `OrderBookProcessor` field and the `DisruptorShardManager` dependency on it; inject
  `BookSlotTable` into `DisruptorShardManager` and pass it to each handler.
- **Restore the null-slot guard.** `OrderBookProcessor` carried a rate-limited `missingSlots`
  counter with a 60s-throttled warn; `DepthEventHandler` currently dereferences `slots.get(id)`
  with no check. A null slot should be impossible, which is exactly why it must be counted rather
  than crash the consumer thread. Carry that logic over verbatim — it is the only part of
  `OrderBookProcessor` worth keeping.
- On a null slot: count, drop, `event.clear()`, return. Do not classify.
- Everything else in the handler stays: `strategy.onEvent(slot, event)` → `classifier.process(
  slot.instrument(), slot.book())` → `event.clear()`. Note the classifier already gates on
  `state != SYNCED` internally and emits a `DROP`, so calling it unconditionally is correct and
  matches pre-refactor behaviour.

---

## 3. Deletions

- `exchange/ingress/OrderBookProcessor.java` — dissolved into step 2.
- `exchange/book/OrderBookResult.java` — no remaining caller once `OrderBookProcessor` and
  `OrderBookOld` are gone.
- `exchange/book/OrderBookOld.java` — the untracked reference copy of the pre-refactor algorithm.
  It is untracked, so it will not appear in the diff; delete it explicitly. Its content is already
  preserved in git history at `75b6a90` and described in `multi-exchange-progress.md` §4/§5 —
  nothing is lost.
- Fix the two stale javadoc references to `OrderBookProcessor` in `BookSlot` and
  `SnapshotFetchQueue` while you are there (the latter is the `@Lazy` comment from step 1).

---

## 4. `MonitoringController` — restore the read accessors

`OrderBook` lost `snapshotBids()` / `snapshotAsks()`. Put them back as **defensive copies**
(`new TreeMap<>(bids)`), not as raw `getBids()` exposure:

- The controller runs on a Tomcat thread while a consumer thread mutates the maps, so it must copy.
  Copying does not remove the `ConcurrentModificationException` hazard the existing javadoc already
  admits — that is a P3 problem and belongs with the read-side storage seam. Keep the javadoc
  caveat; don't pretend it's fixed.
- Restoring them on `OrderBook` (rather than copying in the controller) is the right call for §7 of
  the vision: monitoring reads through an `OrderBook` accessor, so a future primitive-array store
  swaps one class.

The classifier's direct `getBids()`/`getAsks()` reach-in stays as-is — the read half of the storage
seam is P3/P6, explicitly out of scope here.

---

## 5. Tests — the largest single chunk

All three files under `src/test/.../exchange/book/` must be rewritten, and `./mvnw test` is dead
until they are. **This is the step to budget real time for.** The critical constraint, from
progress-doc decision #8:

> The characterization suite is **no longer a parity oracle.** The algorithm was rewritten, not
> ported. Several existing expectations encode behaviour that was *deliberately* changed. Do not
> port them mechanically; re-derive each case from progress-doc §4 and justify every changed
> expectation individually.

### 5.1 New harness

Move the suite to `src/test/.../exchange/binance/`, mirroring where the strategies live.

- `FakeRecoverySink implements RecoverySink` — a settable accept/refuse verdict plus a call counter.
  The "refused at capacity" path is the one the real queue exercises constantly at startup, so it
  needs first-class test support.
- A harness that builds a real `BookSlot` (`Instrument.of(...)`, `new OrderBook(FILTER)`, the real
  strategy, `strategy.newContext()`) and feeds `DepthEvent`s with `type = WS_MSG` / `REST_MSG`.
  The dispatch logic the old `Harness` simulated is now **inside** `onEvent` — the harness should
  just build the event and call `strategy.onEvent(slot, event)`, nothing more. If the harness is
  re-implementing state transitions, it is wrong.
- `bufferSize` moves from reflecting on `OrderBook.diffBuffer` to reading
  `BinanceSyncContext.diffBuffer` — package-private, so putting the test in the same package
  removes the reflection entirely. Same for `lastUpdateId` and `syncPointFound`.
- Keep the JSON builders (`diff`, `snapshot`, `lvl`, `levels`) as they are — they exercise the real
  `JsonParser` + `JavaDoubleParser` path and Binance's `U`/`u`/`pu`-before-`b`/`a` field ordering,
  which the `check()` hand-off contract depends on.

### 5.2 Expectations that must change

Work through the existing `@DisplayName`s against progress-doc §4 and §5. The ones known to have
inverted:

| Existing case | New expectation | Source |
|---|---|---|
| "a snapshot arriving while SYNCED knocks the book back (preserved quirk)" | The snapshot is **dropped**; the book stays `SYNCED`, untouched. | §4.3 state guard |
| "a resync clears the diff buffer but leaves the price levels in place" | `recover()` **clears the levels** too. | §4.9 |
| "diffs buffered before the snapshot are retained after SYNCED, not cleared" | The buffer is **fully drained** (`pollFirst`). | §4.8 |
| "syncing on a single buffered diff leaves distances uncomputed and far levels unswept" | `computeDistance()` **runs** on the snapshot path; far levels are swept, distances set. | §4.8 |
| "a snapshot newer than every buffered diff empties the buffer and resyncs" | An empty / all-stale buffer is **success** — book syncs and waits for a suitable stream event. | §5 |
| "the first buffered event is applied without sequence validation" | No special case any more; the first buffered event goes through the same `check()`. | §4.8 |
| "a snapshot older than the first buffered diff's `[U,u]` window resyncs" | Re-derive: pre-validation of the snapshot against the buffer window is gone. Outcome now depends on whether any buffered event satisfies the per-event predicate. | §5 |
| "a diff whose `u` equals snapshotId is KEPT" | **Unchanged** — the strict `u < lastUpdateId` discard survives, deliberately contrary to Binance's spot docs. Keep this test and its comment. | §4.5, decision #9 |

### 5.3 Cases worth adding

The rewrite introduced behaviour nothing currently pins:

- **`PENDING` retries on every diff** when the sink refuses — with a queue of 10 against ~1400
  books, refusal *is* the normal startup path, and a book that stops re-asking parks forever (§4.3).
- **At most one `requestRecovery` per event** — assert the `FakeRecoverySink` counter across a
  gap, a buffer overflow, and a malformed snapshot. `recover()` having exactly one call site is the
  invariant this protects (§4.10.1).
- **Futures two-regime `check()`** — hunting (`lastUpdateId ∈ [U,u]` → sets `syncPointFound`) versus
  locked (`pu == lastUpdateId`), and specifically that **`u` advances the cursor in regime 2 too**.
  Not advancing it was a real bug during development that made every futures book resync every two
  events (§4.6).
- **`check()` leaves the parser before `b`/`a`** — assert levels actually land after a valid diff.
  The failure mode is a book that silently drifts while still reporting `SYNCED`, which no other
  test would catch (§4.4, §4.10.5).
- **Invariant 3** — a book in `PENDING` or `RECOVERING` has empty levels. This is what licenses the
  single-pass snapshot apply.

### 5.4 `OrderBookTest`

Rewrite venue-free. It currently gets levels in through real diffs because that was the only path;
now it can drive `applyLevel` / `clearLevels` / `computeDistance` directly. Assertions to keep:
zero-qty removes a level, an update preserves `firstSeenMillis`, `computeDistance` sweeps outside
±`filterThreshold` and stores a **fraction**, not a percentage.

---

## 6. Observability — do this before the smoke test, not after

Progress-doc §6.2 item 1 is called "the highest-value fix in the list" and it is right: **a desync
is currently completely silent.** `check()` logs nothing on `DE_SYNCED`, `recover()` logs nothing at
all. A book stuck in a resync loop produces no output except a `sync count:` line that fails to
climb — which is precisely the failure mode you are about to go looking for in step 7. Without
this, an unsuccessful smoke test is uninterpretable.

Three small changes:

1. **Log the desync.** `debug` on `DE_SYNCED` naming the mismatch (spot: expected
   `U <= lastUpdateId + 1`, got `U=…`; futures: `pu` gap), plus a `debug`/`warn` in `recover()` with
   `slot.instrument().logName()`. Per hot-path rules: lazy `{}` placeholders, `debug` level, never
   string concatenation.
2. **Move the buffer-overflow log out of `BinanceSyncContext`.** It currently prints
   `BUFFER OVERFLOW!!! 500 diffs buffered` with no instrument — useless. The strategy has
   `slot.instrument().logName()` in hand; log it there and have `bufferDiff` just return `false`.
3. **Document the `check()` parser hand-off contract** — one javadoc block on the abstract method:
   *called positioned just inside the diff object; must read its sequence fields and return leaving
   the parser before `b`/`a`, without consuming `END_OBJECT`*. The failure mode is silent book
   drift, and nothing in code says this today.

Deliberately **not** in scope: the snapshot request generation/epoch (§6.2 item 6) — it self-corrects
via buffer replay and belongs with P3's reset lane.

---

## 7. Verification

### 7.1 Build

```
./mvnw clean package
```

Green, including the rewritten sync suite. Do not proceed on a compiling-but-untested tree — step 5
is the only thing standing between "it starts" and "the sequence rules are right".

### 7.2 Live smoke test against Binance

Run with real DB coordinates (`-Dspring.profiles.active=local`). What to expect, in order:

1. `Instrument universe updated — N tracked (N added, 0 removed)` — roughly 1400 (≈700 spot +
   ≈700 futures) with the shipped `spot-requires-futures: true` policy.
2. `WebSocket pools started — spot: … futures: …`.
3. `sync count: spot=… fut=…` climbing.

> **Ramp expectation — do not mistake this for a hang.** Queue size is 10 per market
> (`spot/futures-snapshot-queue-size`), dispatch every 6s, and each response carries a 5s
> `delayElement` before the slot is released. That is roughly 10 snapshots per ~6s per market, both
> markets in parallel: **plateau is on the order of 7–12 minutes from start**, not seconds. Mass
> `requestRecovery` refusal during the ramp is the designed behaviour (§4.3), and with step 6's
> logging you will see books re-asking on each diff rather than parking.

4. `GET /api/monitoring/orderbook?symbol=BTCUSDT&market=FUTURES` — populated bids/asks, `distance`
   values as fractions in `[0, 0.1]`, `state: SYNCED`. Hit it mid-ramp too and confirm `RECOVERING`
   is observable.
5. `/ws` feed for a connected user — classified levels flowing, top-5 per side.

### 7.3 Failure triage

| Symptom | Look at |
|---|---|
| NPE on first depth message | Step 1 — `BookSlot` still carrying `null` strategy/ctx |
| Context fails to start with a cycle | Step 1's `@Lazy` on `SnapshotFetchQueue` |
| `sync count` stuck at 0, no other output | Step 6 was skipped — you have no signal |
| `sync count` climbs then collapses | Futures regime-2 cursor not advancing on `u` (§4.6), or `check()` over-consuming the parser (§4.4) |
| Books `SYNCED` but the feed is empty | Classifier, not sync — check `distance` is fractional and `computeDistance` ran on the snapshot path |
| Many books `SYNCED` with `distance == 0.0` everywhere | `computeDistance()` missing on the snapshot path (§4.8) |

---

## 8. Order of work

1. `SyncStrategyRegistry` + `BinanceAdapterConfig` + `BookSlotTable.allocate` *(§1)*
2. `DepthEventHandler` / `DisruptorShardManager` + null-slot counter *(§2)*
3. Delete `OrderBookProcessor`, `OrderBookResult`, `OrderBookOld`; fix stale javadoc *(§3)*
4. `snapshotBids`/`snapshotAsks` on `OrderBook` *(§4)* → **`./mvnw compile` green here**
5. Logging + the `check()` javadoc *(§6)* — cheap, and it makes step 7 interpretable
6. Test rewrite *(§5)* → **`./mvnw clean package` green here**
7. Live smoke test *(§7)*

Steps 1–4 are a couple of hours. Step 6 is the real work. Step 5 is 30 minutes and pays for itself
the first time step 7 misbehaves.

---

## 9. After this lands

Update `.claude/docs/multi-exchange-progress.md`: mark P2 step 2 C **Done**, strike the two §6.2
items that were already fixed (subclass ctor, `resetContext`), and drop the §6.1 blocking section.
Then P2 steps 3–5 (`SnapshotRequestQueue`, `RequestBudget`, `StreamProtocol`) in any order, then
step 6 config consolidation — `screener.orderbook.*` and `screener.websocket.*` still sit outside
`screener.exchanges.*`, and `spot/futures-snapshot-queue-size` is read via `@Value` rather than
through `OrderbookProperties`, which is a convention deviation worth folding in at that point.

`.claude/docs/orderbook-sync-algorithm.md` still describes the pre-P1 pipeline and is actively
misleading — it should be rewritten or have its stale chapters deleted before anyone uses it to
reason about sync.
