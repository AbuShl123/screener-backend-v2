# Per-User Classification — Phase C: Runtime Wiring (Connect-Time Rule Loading)

## Scope

**Building now**: the runtime hot-path integration that makes per-user classification rules
actually shape what a connected client sees. Concretely:

- `UserClassificationRule` — a per-user lookup table of per-`(symbol, market)` override rules.
- `UserClassificationContext` — a per-user "session" bundle: rule + personal feed store +
  per-symbol HIGH/LOW state.
- `UserFeedRegistry` (`@Component`) — owns the lifecycle of contexts, **refcounted per user**,
  and fans the active-context array out to every shard.
- Extract `SymbolState`/`Scratch` from `OrderBookClassifier` into a package-private top-level
  class so a context can hold its own state map.
- Refactor `OrderBookClassifier` into a **two-pass** classifier: the always-on default pass
  followed by a per-user pass driven by a `volatile UserClassificationContext[]` array.
- `DisruptorShardManager` keeps references to all per-shard classifiers and exposes
  `setActiveUserContexts(...)`.
- `OrderBookBroadcaster` **merge**: per session, send the user's personal feed for configured
  symbols and the global feed (filtered) for everything else.
- `ClassificationRuleService.buildRuntimeRule(userId)` — the Phase-A-anticipated translation
  from persisted rows into an immutable runtime rule, called **at WebSocket connect time**.
- WebSocket endpoint/session wiring: load + register a context on `@OnOpen`, discard on
  `@OnClose`/`@OnError`.

**Not building now** (Phase D): live propagation of rule edits to already-connected users.
Rules are loaded **only at connect time**. Editing rules while connected requires a reconnect
(in fact, requires *all* of a user's sessions to disconnect — see the refcount note below) to
take effect. No atomic context swap, no rebuild-on-edit, no fresh-SNAPSHOT-on-edit.

See [per-user-classification-vision.md](per-user-classification-vision.md) for the overall
design, [per-user-classification-phase-a.md](per-user-classification-phase-a.md) for the
persistence/REST layer this consumes, and [per-user-classification-phase-b.md](per-user-classification-phase-b.md)
for the rule abstraction this builds on.

---

## Grounding: what Phase B already gave us

Phase C is purely additive wiring on top of clean seams Phase B left:

- `OrderBookClassifier.selectTopK(levels, scratch, rule, highLiquidity)` is already
  rule-driven: tier computation delegates to `rule.computeTier(...)` and the early-break uses
  `rule.maxDistance(highLiquidity)`. `highLiquidity` is sourced once per book from
  `defaultRule.isHighLiquidity(symbol)`.
- The state machine, top-K scratch buffers, change detection, and `submit*` helpers are already
  **parameterized** on `Scratch`/`working[]`. The only piece still bound to a field is the feed
  store (`this.feedStore`).
- All distances are **fractions** end-to-end (`0.05` = 5%). `DefaultClassificationRule` uses
  `0.05`/`0.025`; `ThresholdClassificationRule` and the Phase-A validation cap
  (`maxDistance ∈ (0, priceFilterThreshold]`) are fractions too. **No conversion anywhere in
  Phase C.**
- `OrderBook.computeDistance()` populates `PriceLevelEntry.distance` after each diff; the
  classifier only *reads* it. The same distance value feeds both the default and user rules — no
  per-rule recomputation.
- `OrderBookFeedStore` is just `snapshotMap` + `pendingRef` with multi-writer / single-drainer
  safety and no Spring dependencies beyond the `@Component` annotation — so it can be
  instantiated as a plain object for personal stores.

---

## Design Decisions (settled)

| Decision | Choice | Rationale |
|---|---|---|
| Context keying / multi-session | **Per-user, refcounted** | A user may open several connections (tabs) with the same `userId`. One context per `userId` is shared by all that user's sessions; the user pass runs once and both sessions read the same personal feed. The context is discarded only when the user's **last** session closes. Dedups hot-path work and matches the vision's `userId` keying. |
| Personal feed store | **Reuse `OrderBookFeedStore`** (plain `new` instance) | It is just two maps with no Spring deps. A separate `UserOrderBookFeedStore` would duplicate the coalescing logic for no current benefit. One coalescing implementation to maintain. |
| `UserClassificationRule` shape | Plain lookup table; **does not implement `ClassificationRule`** | The classifier fetches the per-key leaf (`ThresholdClassificationRule`) and passes it to `selectTopK`. Unset keys are never classified in the user pass — they reach the user via the broadcaster merge from the global feed. This realizes "delegate to default for unset keys" without a delegating rule object. |
| `ctx.states` map type | **`ConcurrentHashMap`** | One context is shared across all shards. A given key is always single-shard (hash-stable), so each `SymbolState` is single-threaded — but the **map structure** is touched by multiple shard threads, so `computeIfAbsent` needs a concurrent map. The default classifier's own `states` stays a plain `HashMap` (single thread). |
| `SymbolState` location | Extract to **package-private top-level** in `analysis/` | A context must declare `Map<String, SymbolState>`; the type can't stay private to `OrderBookClassifier`. |
| Per-shard context visibility | Every shard holds **all** contexts | A user's configured symbols hash across shards, so every shard classifier must be able to match any configured key. One shared array reference, set on every classifier. |
| Rule load timing | **Connect time only**, on the `@OnOpen` Tomcat thread | Off the hot path; a blocking DB read here is fine. Live edits are Phase D. |
| Reuse on second connect | Second session for an already-connected user **reuses** the loaded context (no DB reload, no rebuild) | Consistent with connect-time-only loading: an edit takes effect only after all of a user's sessions have closed and a fresh connect reloads. |

---

## New / Changed Components

| Class | Type | Change |
|---|---|---|
| `SymbolState` (+ nested `Scratch`) | package-private class, `analysis/` | **Extracted** out of `OrderBookClassifier` (no behavior change) |
| `UserClassificationRule` | POJO, `analysis/` | **New** — `Map<String, ThresholdClassificationRule> byKey`, `configuredKeys()`, `ruleFor(key)` |
| `UserClassificationContext` | POJO, `analysis/` | **New** — `userId`, `UserClassificationRule`, `OrderBookFeedStore` (plain instance), `ConcurrentHashMap<String, SymbolState> states` |
| `UserFeedRegistry` | `@Component`, `analysis/` | **New** — refcounted per-user lifecycle; pushes context array to shards + exposes active set to broadcaster |
| `OrderBookClassifier` | per-shard, non-bean | **Refactor** — `classifyOne(...)` helper; `volatile UserClassificationContext[] activeUserContexts`; two-pass `process()` |
| `DisruptorShardManager` | `@Component` | **Change** — store `OrderBookClassifier[]`; add `setActiveUserContexts(...)` |
| `OrderBookBroadcaster` | `@Component` | **Change** — keyed global bodies; per-context drain; per-session merge; merged snapshot for custom sessions |
| `ClassificationRuleService` | `@Service` | **Add** `buildRuntimeRule(userId): Optional<UserClassificationRule>` (the Phase A "seam") |
| `ScreenerWebSocketEndpoint` | `@ServerEndpoint` | **Change** — call `registry.onUserConnect/onUserDisconnect`; store context on the session |
| `UserWebSocketSession` | POJO | **Change** — hold a nullable `UserClassificationContext` |

No `SecurityConfig` change — `/ws` already validates the JWT query param.
No DB migration — Phase C only *reads* the Phase A rows.

---

## `UserClassificationRule`

A plain lookup table built off the hot path from a user's persisted rows.

```
final Map<String, ThresholdClassificationRule> byKey;   // key = "SYMBOL:MARKET"
final Set<String> configuredKeys;                        // = byKey.keySet(), cached

Set<String>                  configuredKeys()  // O(1) hot-path membership check
ThresholdClassificationRule  ruleFor(String key)
```

It intentionally does **not** implement `ClassificationRule`. The classifier looks up the leaf
rule for a configured key and passes that leaf to `selectTopK`. Keys absent from `byKey` are
never touched by the user pass; the user receives the global/default classification for them via
the broadcaster merge.

---

## `UserClassificationContext`

Plain POJO. One instance per connected user **who has at least one rule**. Users with no rules
get **no context** (`session.context == null`) and consume the global feed directly — zero extra
hot-path overhead.

```
final UUID                              userId;
final UserClassificationRule            rule;
final OrderBookFeedStore                feedStore;   // plain `new OrderBookFeedStore()`
final ConcurrentHashMap<String, SymbolState> states; // this user's HIGH/LOW + scratch per key
```

`states` only ever contains the user's configured keys (the only keys the user pass classifies),
so it stays small. `ConcurrentHashMap` because multiple shard threads insert into it (one per
configured symbol, each on its own shard). Each `SymbolState` value is single-threaded (its key
is pinned to one shard).

The `sessionCount` is **not** a field on the context — it lives in the registry, which owns the
lifecycle.

---

## `UserFeedRegistry` — refcounted, per-user

`@Component`. Source of truth for active contexts across all shards. All mutation happens inside
one `synchronized` block; connect/disconnect run on Tomcat threads and are rare, so lock cost is
irrelevant.

State:
```
Map<UUID, UserClassificationContext> contexts;   // userId -> context
Map<UUID, Integer>                   refCounts;   // userId -> live session count
volatile UserClassificationContext[] active;     // snapshot read by broadcaster + pushed to shards
```

```
onUserConnect(UUID userId) -> UserClassificationContext | null
  synchronized:
    if contexts has userId:
      refCounts[userId]++                       // REUSE — no DB reload, no rebuild
      return contexts[userId]
    Optional<UserClassificationRule> r = ruleService.buildRuntimeRule(userId)
    if r is empty: return null                  // default-only session, no context
    ctx = new UserClassificationContext(userId, r, new OrderBookFeedStore(), new ConcurrentHashMap)
    contexts[userId] = ctx; refCounts[userId] = 1
    rebuildActiveArray()                         // recompute `active`
    shardManager.setActiveUserContexts(active)
    return ctx

onUserDisconnect(UUID userId)
  synchronized:
    if userId not in refCounts: return
    if --refCounts[userId] == 0:
      contexts.remove(userId); refCounts.remove(userId)
      rebuildActiveArray()
      shardManager.setActiveUserContexts(active)
    // context dereferenced -> GC ("discarded immediately" on the LAST session)

activeContexts() -> UserClassificationContext[]  // returns `active`; read by broadcaster each tick
```

`rebuildActiveArray()` allocates a fresh array from `contexts.values()`; it is never mutated in
place, so both the shard classifiers and the broadcaster can read it without locking.

---

## `OrderBookClassifier` — two-pass refactor

The state machine, top-K selection, change detection, and `submit*` helpers stay **behaviorally
identical**. The refactor extracts the per-`(rule, state, feedStore)` work into one method and
adds the user loop.

**Add:**
```
private volatile UserClassificationContext[] activeUserContexts = EMPTY;   // empty array

void setActiveUserContexts(UserClassificationContext[] ctxs) { this.activeUserContexts = ctxs; }
```

**Extract** the current `process(ob)` body into:
```
private void classifyOne(OrderBook ob, String key, SymbolState state,
                         ClassificationRule rule, OrderBookFeedStore feedStore,
                         boolean highLiquidity)
```
This contains the entire existing logic: the not-SYNCED → DROP path, the empty-book → DROP path,
`selectTopK` ×2, the LOW-skip, `applyNewOrders` ×2, and the ADD/UPDATE/DROP submission — all
against the **passed-in** `state`, `rule`, and `feedStore`. The three `submit*` helpers (and the
DROP helper) take the target `feedStore` as a parameter.

**Rewrite** `process(ob)`:
```
process(ob):
  key = symbol + ":" + market
  hl  = defaultRule.isHighLiquidity(symbol)                 // computed ONCE per book

  // Pass 1 — default, always:
  classifyOne(ob, key, defaultStates.computeIfAbsent(key,…), defaultRule, feedStore, hl)

  // Pass 2 — per user, only if any context is active:
  UserClassificationContext[] ctxs = activeUserContexts;     // single volatile read
  for (ctx : ctxs):
    if (ctx.rule.configuredKeys().contains(key)):            // O(1) HashSet
      ThresholdClassificationRule leaf = ctx.rule.ruleFor(key)
      SymbolState st = ctx.states.computeIfAbsent(key, …)
      classifyOne(ob, key, st, leaf, ctx.feedStore, hl)
```

Why this is safe and cheap on the hot path:
- One `volatile` read per `process()` — one fence, no lock; the array is swapped atomically,
  never mutated in place.
- When no custom users are connected, `ctxs` is empty and the loop body never runs.
- `configuredKeys().contains(key)` is O(1).
- DROP-on-desync is handled automatically: because `classifyOne` runs per context and contains
  the not-SYNCED / empty-book DROP transitions, a configured book that leaves SYNCED also drops
  out of the user's personal feed (resetting that context's `SymbolState`). **The vision's
  hot-path pseudocode omits this; folding the full state machine into `classifyOne` covers it for
  free.**

`SymbolState`/`Scratch` are extracted to a package-private top-level class so
`UserClassificationContext.states` can name the type. Construction (`new SymbolState()`) still
happens inside the classifier.

---

## `DisruptorShardManager`

Keep references to the per-shard classifiers and fan the context array out to all of them.

```
private OrderBookClassifier[] classifiers;     // NEW field, populated in start()

// inside start(), per shard:
classifiers[i] = new OrderBookClassifier(feedStore, defaultRule);
disruptor.handleEventsWith(new DepthEventHandler(i, orderBookProcessor, classifiers[i]));

public void setActiveUserContexts(UserClassificationContext[] ctxs) {
    for (OrderBookClassifier c : classifiers) c.setActiveUserContexts(ctxs);  // same array ref
}
```

Every shard gets the **same** array reference because a user's symbols are spread across shards;
each shard must be able to match any configured key.

---

## `OrderBookBroadcaster` — the merge

Per 100ms tick, mirror today's "drain once, build once, fan out" pattern but extend it to
personal feeds.

```
drain():
  if sessions empty: return
  globalPending = feedStore.drainPending()          // global
  // Per-context drain — ONCE per context, not per session (a context may back multiple sessions)
  for ctx : registry.activeContexts():
    ctxPending[ctx] = ctx.feedStore.drainPending()

  // Lazy body building (built on first session that needs them):
  globalBodies = null   // Map<String,String>  key -> JSON body   (keyed so it can be filtered)
  ctxBodies    = null   // Map<ctx, Map<String,String>>

  for session : sessions:
    if !session.running: continue
    ctx = session.context                            // nullable

    if session.status == NEED_SNAPSHOT:
      body = (ctx == null)
               ? snapshot(globalSnapshot)
               : snapshot( merge(globalSnapshot filtered by ctx.configuredKeys,
                                  ctx.feedStore.getSnapshot()) )
      resetSeq; enqueue [injectSeq(body)]; status = READY
      continue

    // READY:
    if ctx == null:
      if globalPending not empty: enqueue per-session seq'd globalBodies            // today's path
    else:
      batch = []
      for (key,body) in ctxBodies[ctx]:                  enqueue with seq            // personal feed
      for (key,body) in globalBodies:
        if !ctx.configuredKeys().contains(key): batch += injectSeq(body)            // filtered global
      if batch not empty: enqueue batch
```

Key points / changes from today:
- **`buildAllUpdateBodies` must return a keyed structure** (`Map<String,String>` or
  `List<(key,body)>`), not a bare `List<String>`, so the global drain can be filtered per custom
  session. The key is `symbol + ":" + market`, matching `configuredKeys`.
- Drain each personal store **once per context per tick** (not per session) so multiple sessions
  sharing a context don't lose each other's data — same reasoning as the single global drain.
- **Merged snapshot** for a custom `NEED_SNAPSHOT` session: global snapshot filtered to exclude
  `configuredKeys`, unioned with the context's personal snapshot. Snapshots are rare (connect /
  explicit `SNAPSHOT_REQUEST`), so building a small merged map per custom session is acceptable.
- `seq` stays strictly per-session (unchanged). Each session injects its own seq across its merged
  batch.
- When `registry.activeContexts()` is empty (no custom users), the only added cost is an
  emptiness check.

The broadcaster reads `registry.activeContexts()` (a `volatile` array snapshot); it never mutates
registry state.

---

## DB seam: `ClassificationRuleService.buildRuntimeRule`

The Phase A doc explicitly left this seam. Add (read-only, runs on the `@OnOpen` Tomcat thread):

```
@Transactional(readOnly = true)
Optional<UserClassificationRule> buildRuntimeRule(UUID userId):
  rows = ruleRepository.findByUserId(userId)
  if rows empty: return Optional.empty()
  group rows by "SYMBOL:MARKET"
  for each group: ThresholdClassificationRule.of( rows -> List<TierThreshold> )
  return Optional.of( new UserClassificationRule(byKey) )
```

Reuses the same grouping the existing `getRules(...)` already does. Input is already validated at
write time (Phase A), so no re-validation here. The rows store fractions for `maxDistance`, which
flow straight into `ThresholdClassificationRule` unchanged.

---

## WebSocket wiring

`ScreenerWebSocketEndpoint` autowires `UserFeedRegistry`.

**`@OnOpen`** (after the existing JWT validation yields `userId`):
1. `UserClassificationContext ctx = registry.onUserConnect(userId);`  // may be null
2. `UserWebSocketSession userSession = new UserWebSocketSession(session, userId, ctx);`
3. store in `userProperties`, `startSendLoop()`, `broadcaster.addSession(userSession)`.

**`@OnClose` / `@OnError`** (both idempotent, as today):
1. `userSession.disconnect()`
2. `broadcaster.removeSession(userSession)`
3. `registry.onUserDisconnect(userId)`

`UserWebSocketSession` gains a `final UserClassificationContext context` field (nullable),
exposed via a getter for the broadcaster. No other session changes.

Ordering note: registering the context before `addSession` is harmless — the shards may classify
into the personal feed before the broadcaster knows about the session; that data simply waits in
the personal `pendingRef` until the first drain after `addSession`.

---

## Concurrency & correctness summary

| Concern | Resolution |
|---|---|
| Context shared across shards, `states` mutated by many threads | `states` is `ConcurrentHashMap`; each key is single-shard so each `SymbolState` is single-threaded |
| Personal feed store written by many shards, drained by broadcaster | `OrderBookFeedStore` is already multi-writer / single-drainer safe |
| Active-context array read on hot path while connect/disconnect writes | `volatile` array, swapped atomically, never mutated in place |
| Configured book leaves SYNCED | `classifyOne` runs the full state machine per context → emits personal DROP, resets context state |
| Global feed filtering per custom user | global bodies built keyed; skip keys in `ctx.configuredKeys()` |
| Multiple sessions, same user | per-user refcount in registry; context discarded on the last disconnect |
| Disconnect cleanup | array re-pushed; context dereferenced → GC; no incremental teardown |

---

## Accepted limitations (record in `CURRENT_STATE.md`)

- **Cold-start snapshot gap**: a just-registered context's personal store is empty until the next
  diff classifies each configured symbol (sub-second for SYNCED books). The first merged snapshot
  may omit those symbols (global filtered out, personal not yet populated); they appear via UPDATE
  within a tick or two.
- **`TOP_LEVELS = 5` still caps custom users**: a user with wide/lenient thresholds still receives
  only the top 5 per side by `(tier, notional, distance)`.
- **Edits need a full reconnect of all the user's sessions**: because a second connect reuses the
  already-loaded context, a rule edit takes effect only after every session for that user has
  disconnected and a fresh connect reloads from the DB. Live propagation is Phase D.

---

## Package Layout (Phase C additions)

```
src/main/java/dev/abu/screener_backend/analysis/
├── SymbolState.java                  ← new (extracted from OrderBookClassifier; package-private)
├── UserClassificationRule.java       ← new (POJO)
├── UserClassificationContext.java    ← new (POJO)
├── UserFeedRegistry.java             ← new (@Component)
└── OrderBookClassifier.java          ← refactor (classifyOne + activeUserContexts + two-pass)

src/main/java/dev/abu/screener_backend/analysis/rule/
└── ClassificationRuleService.java    ← add buildRuntimeRule(userId)

src/main/java/dev/abu/screener_backend/binance/disruptor/
└── DisruptorShardManager.java        ← store classifiers; setActiveUserContexts(...)

src/main/java/dev/abu/screener_backend/feed/
└── OrderBookBroadcaster.java         ← keyed global bodies; per-context drain; merge; merged snapshot

src/main/java/dev/abu/screener_backend/ws/
├── ScreenerWebSocketEndpoint.java    ← onUserConnect/onUserDisconnect wiring
└── UserWebSocketSession.java         ← nullable UserClassificationContext field

src/test/java/dev/abu/screener_backend/analysis/
├── UserClassificationRuleTest.java   ← new
└── UserFeedRegistryTest.java         ← new (refcount lifecycle)
```

---

## Implementation Steps

1. **Translation, off hot path** — `ClassificationRuleService.buildRuntimeRule(userId)` +
   `UserClassificationRule`. Unit-test rows → rule grouping and `ruleFor`/`configuredKeys`.
2. **Extract `SymbolState`/`Scratch`** to a package-private top-level class (pure move, no
   behavior change). Confirm the existing build/tests still pass.
3. **`UserClassificationContext`** (reusing `OrderBookFeedStore`, `ConcurrentHashMap` states).
4. **Refactor `OrderBookClassifier`** — `classifyOne(...)`, `volatile activeUserContexts`,
   `setActiveUserContexts`, two-pass `process()`, parameterize `submit*` on the feed store.
   Verify the **default feed is byte-identical** when no contexts are active (same
   characterization approach as Phase B).
5. **`DisruptorShardManager`** — store `classifiers[]`, add `setActiveUserContexts(...)`.
6. **`UserFeedRegistry`** — refcounted connect/disconnect, `activeContexts()`. Unit-test the
   refcount lifecycle (connect twice / disconnect once keeps the context; second disconnect
   removes it).
7. **WS wiring** — endpoint `onUserConnect/onUserDisconnect`; `UserWebSocketSession` context field.
8. **Broadcaster merge** — keyed global bodies, per-context drain, per-session merge, merged
   snapshot for custom `NEED_SNAPSHOT` sessions.
9. **Integration check** — one default client + one custom client: confirm custom tiers on
   configured symbols, default tiers elsewhere, DROP on desync of a configured symbol, and clean
   teardown (context gone) when the custom client disconnects. `contextLoads()` still passes.
10. **Update `CURRENT_STATE.md`** — new classes, the `OrderBookClassifier`/`DisruptorShardManager`/
    `OrderBookBroadcaster` changes, the `buildRuntimeRule` seam, the status table (Phase C done,
    Phase D pending), and the accepted limitations above.

Steps 1–3 are independent. Step 4 depends on 2–3. Step 5 depends on 4. Step 6 depends on 1+3+5.
Step 7 depends on 6. Step 8 depends on 3+6.

---

## What Phase C Does NOT Do

- No live propagation of rule edits to connected users — that is **Phase D** (rebuild rule →
  atomic context swap → fresh SNAPSHOT). Phase C loads rules only at connect time, and a second
  connect for the same user reuses the already-loaded context.
- No DB schema changes — Phase C only reads the Phase A `classification_rules` rows.
- No `SecurityConfig` change — `/ws` JWT validation is unchanged.
- No change to the orderbook sync pipeline, the Disruptor event flow, or `OrderBookFeedStore`'s
  internals (it is reused as-is for personal stores).
- No removal of the global default feed — it always runs and serves every default-rules user and
  every unconfigured `(symbol, market)` for custom-rules users.
