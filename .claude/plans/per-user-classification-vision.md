# Per-User Order Book Classification — Architecture Vision

## Goal

Allow users to define custom classification thresholds for specific tickers. Unconfigured tickers fall back to the global default rule. The design must impose zero overhead on the hot path when no custom-rule users are connected, and minimal overhead when they are.

> **Keying granularity:** all per-user state and overrides are keyed by `(symbol, market)`,
> not by symbol alone — written as the string `"BTCUSDT:FUTURES"`. A user may want different
> thresholds on spot vs futures for the same coin (spot books are thinner), and this matches
> the classifier's existing `ob.getSymbol() + ":" + ob.getMarket()` key. Where this document
> says "symbol" below, read it as the `(symbol, market)` key unless stated otherwise.
>
> **Persistence + REST (Phase A)** is specified separately in
> [per-user-classification-phase-a.md](per-user-classification-phase-a.md). This document
> covers the runtime/hot-path design (Phases B–D).

---

## Development Phases

The feature is built end-to-end in four slices. Each phase is independently shippable and
verifiable; later phases never require revisiting earlier ones.

| Phase | Scope | Status |
|---|---|---|
| **A** | DB table, JPA entity, repository, validation, and REST CRUD for user rules. Entirely off the hot path — nothing reads these rows at runtime yet. | ✅ Done — see [per-user-classification-phase-a.md](per-user-classification-phase-a.md) |
| **B** | The **rule / state-machine / feed split**: extract the `ClassificationRule` interface, move the current thresholds into `DefaultClassificationRule`, add the immutable `ThresholdClassificationRule`, and refactor `OrderBookClassifier` to take a `ClassificationRule` and drive its early-break from `rule.maxDistance(highLiquidity)`. **Pure refactor — the global feed must be byte-for-byte identical before and after; no user contexts exist yet.** | ⏭️ Next — see [per-user-classification-phase-b.md](per-user-classification-phase-b.md) |
| **C** | Runtime wiring: `UserClassificationRule`, `UserClassificationContext`, `UserOrderBookFeedStore`, `UserFeedRegistry`, the `volatile activeUserContexts` array + two-pass hot path, the broadcaster merge, and loading a user's rules **at WebSocket connect time**. | Not started |
| **D** | Live propagation of rule edits to already-connected users (rebuild rule → atomic context swap → fresh SNAPSHOT). Until D ships, editing rules requires a reconnect to take effect. | Not started |

Phase B is deliberately the safe slice: because no per-user path exists yet, the default feed
can be diffed before/after to prove the refactor changed no behavior.

---

## Core Principle: Separate the Rule from the State Machine

The current `OrderBookClassifier` conflates three things:
- **The classification rule** — `computeTier()`, threshold constants
- **The activity state machine** — HIGH/LOW tracking per symbol, change detection
- **Feed submission** — deciding ADD / UPDATE / DROP, calling `feedStore.submit()`

These must be split. The rule is the only thing that differs between the default and a user's view. The state machine logic and feed submission logic are identical regardless of which rule is applied.

---

## New Abstraction: `ClassificationRule`

A pure, stateless interface with two methods:

```
int    computeTier(double notional, double distance, boolean highLiquidity)
double maxDistance(boolean highLiquidity)
```

- `computeTier(...)` returns 0 (invisible) or 1–4 (visible tier). No side effects, no state.
- `maxDistance(...)` returns the widest distance any tier in this rule can match — the
  classifier uses it to drive the early-break in its top-K loop (see the refactored
  `OrderBookClassifier` section for why this must be dynamic, not a constant).

**Why `highLiquidity` is a parameter on both methods.** High-liquidity handling is a
*default-rule* concept only, but the classifier is rule-agnostic: it computes the boolean
once per `process()` call (via `defaultRule.isHighLiquidity(symbol)`) and threads it into
whatever rule is active without knowing or caring which rule that is. The default rule uses
the boolean; the user rule ignores it. Keeping the boolean in the interface signature is
cheaper than passing the symbol and re-checking a `Set` per level, and it keeps `classify()`
free of any rule-specific branching.

**`DefaultClassificationRule`** — `@Component` singleton. Contains the current tier thresholds exactly as they exist today in `OrderBookClassifier.computeTier()`, **including the high-liquidity exception**. The `HIGH_LIQUIDITY_TICKERS` set, its tighter threshold table, and the `isHighLiquidity(symbol)` check currently live in `OrderBookClassifier`; they are default-rule behavior and must travel into this class. `maxDistance(highLiquidity)` returns the widest `max_distance` of the relevant table — `0.025` for high-liquidity tickers, `0.05` otherwise — which matches the per-table tier-4 distance and preserves today's early-break behavior exactly.

**`UserClassificationRule`** — plain POJO, not a Spring bean (introduced in **Phase C**). Holds a `Map<String, ThresholdClassificationRule> byKey` of per-key overrides keyed by `"symbol:market"` (e.g. `"BTCUSDT:FUTURES"`), and exposes `ruleFor(key)` plus a cached `Set<String> configuredKeys()` for O(1) hot-path lookup. **As built, it deliberately does NOT implement `ClassificationRule` and does NOT delegate to `DefaultClassificationRule`**: the classifier only runs the user pass for configured keys; unconfigured keys are never touched by the user pass and instead reach the user via the broadcaster merge from the global feed. Created when a user connects (and, in Phase D, rebuilt when they update their settings).

**`ThresholdClassificationRule`** — the per-key user override leaf (introduced in **Phase B** as a standalone, unit-testable rule; first *consumed* in Phase C). Built off the hot path from a user's persisted tier rows into immutable primitive arrays — `int[] tiers`, `double[] minNotionals`, `double[] maxDistances` — sorted highest-tier-first and evaluated by the same highest-first loop the default rule uses today. Returns `0` when an order matches no tier. Because the user supplies absolute thresholds, it **ignores** the `highLiquidity` parameter, and its `maxDistance(...)` returns the precomputed largest entry of its `maxDistances` array regardless of the boolean.

---

## Why HIGH/LOW State Cannot Be a Single Shared Bean

HIGH/LOW activity state is per *(symbol × rule)*. A user with more lenient thresholds may see a symbol as HIGH (tier ≥ 1 exists) while the default rule sees it as LOW. Each classification context — default and per-user — must maintain its own `SymbolState` map independently.

The **logic** of the state machine is shared (same class). The **state** is not.

---

## Refactored `OrderBookClassifier` (per-shard, not a Spring bean)

Stays exactly as it is architecturally: one instance per Disruptor shard, owned by `DisruptorShardManager`, never shared between shards.

Changes:
- `classify()` gains a `ClassificationRule` parameter; tier computation delegates to it instead of calling the private `computeTier()` directly.
- The private `computeTier()`, the threshold constants, `HIGH_LIQUIDITY_TICKERS`, and the `isHighLiquidity` check are removed — they move to `DefaultClassificationRule`. The per-symbol `highLiquidity` boolean is obtained via `defaultRule.isHighLiquidity(symbol)`, computed once per `process()` call.
- **The `maxDist` early-break must come from the active rule, not a constant.** Today `classify()` hardcodes `maxDist = highLiquidity ? 0.025 : 0.05` and breaks once the top-K buffer is full and `distance > maxDist`. A user whose widest tier allows e.g. `maxDistance = 0.20` would have their levels silently truncated by that break. The `ClassificationRule` interface therefore exposes `maxDistance(highLiquidity)`, and the early-break uses `rule.maxDistance(highLiquidity)`. For the default rule this still yields `0.025`/`0.05` per the high-liquidity table, so default behavior is unchanged; for a user rule it yields the widest configured tier distance. The break stays correct because anything beyond the widest tier distance can only be tier-0, which only ever fills leftover slots and is already out-ranked by closer tier-0 levels.
- Adds a `volatile UserClassificationContext[] activeUserContexts` field, initialized to an empty array.
- `process(ob)` is extended to iterate `activeUserContexts` after the default classification pass (see hot path below).

The existing scratch-buffer design (pre-allocated `workBids`/`workAsks`, primitive top-K arrays) is preserved and extended — each `UserClassificationContext` carries its own `Map<String, SymbolState>` whose entries contain identical scratch buffers per symbol.

---

## `UserClassificationContext`

Plain POJO (not a Spring bean). One instance per connected user who has at least one custom rule. Bundles everything that belongs to a single user's classification "session":

- `userId` — String
- `UserClassificationRule rule` — their thresholds
- `UserOrderBookFeedStore feedStore` — their personal feed store (same structure as the global `OrderBookFeedStore`)
- `Map<String, SymbolState> states` — HIGH/LOW + scratch buffers per `"symbol:market"` key, for this user's view of the world

When a user disconnects, this object is discarded entirely. On reconnect, a fresh context is created and the client receives a full SNAPSHOT — no stale state survives across disconnections.

---

## Hot Path: Processing Multiple Classification Contexts

On each `process(ob)` call in `OrderBookClassifier`:

```
Step 1 — Default pass (always):
  classify(ob.bids, ob.asks, defaultStates[key], defaultRule)
  → compute ADD / UPDATE / DROP
  → submit to defaultFeedStore

Step 2 — Per-user passes (only if activeUserContexts is non-empty):
  for each ctx in activeUserContexts:              // volatile array read
    if ctx.rule.configuredKeys().contains(key):    // key = "symbol:market"
      classify(ob.bids, ob.asks, ctx.states[key], ctx.rule)
      → compute ADD / UPDATE / DROP for this user
      → submit to ctx.feedStore
    // else: skip — this (symbol, market) uses the global default feed for this user
```

**Why this is safe on the hot path:**
- One `volatile` read per `process()` call — a single memory fence, no lock.
- The array is replaced atomically, never mutated in place — safe to iterate without synchronization.
- When zero users with custom rules are connected, the inner loop body never executes.
- The `configuredKeys` check is a `HashSet.contains()` — O(1).

---

## `UserFeedRegistry` (Spring `@Component`)

Owns the lifecycle of user classification contexts across all shards.

**`onUserConnect(userId, userRule)`**:
1. Constructs a `UserClassificationContext` for this user.
2. Calls `disruptorShardManager.addUserContext(ctx)` which rebuilds and atomically swaps the `volatile` context array on every shard.

**`onUserDisconnect(userId)`**:
1. Calls `disruptorShardManager.removeUserContext(userId)` — same atomic swap pattern.
2. The evicted `UserClassificationContext` is dereferenced; GC handles cleanup.

Writes to the shard arrays happen on the WebSocket session thread (connect/disconnect), which is rare. A `synchronized` block around the array rebuild on each shard is acceptable — cost does not matter on this path.

---

## Feed Store Model: Global Default + Personal Stores

**`OrderBookFeedStore`** (existing) — unchanged. Continues to be the global default feed. All currently-connected users with no custom rules consume this store.

**Personal feed store** — one per connected user with custom rules, created inside
`UserClassificationContext` when the user connects and discarded when they disconnect.
**As built (Phase C), this is a plain `new OrderBookFeedStore()` instance, not a separate
`UserOrderBookFeedStore` class** — `OrderBookFeedStore` is dependency-free (just a pending map +
snapshot map with multi-writer/single-drainer safety), so reusing it avoids duplicating the
coalescing logic. The originally-proposed dedicated `UserOrderBookFeedStore` class was therefore
never created.

Users with **no custom rules at all** are never assigned a `UserClassificationContext`. They consume the global default feed directly — zero extra overhead on the hot path.

---

## Broadcasting: Merge Strategy

The `OrderBookBroadcaster` handles both default-rules users and custom-rules users in its 100ms drain loop.

**Default-rules users** — exactly as today: drain `defaultFeedStore`, send all updates.

**Custom-rules users** — on each drain tick:
1. Send updates from `ctx.feedStore` (covers only configured symbols, with their custom tiers).
2. Send updates from `defaultFeedStore` **filtered**: for each update in the default drain, skip it if `ctx.rule.configuredKeys().contains(update.symbol() + ":" + update.market())` — that `(symbol, market)`'s data came from step 1.

This "merge" ensures the client always sees exactly one authoritative update per symbol per tick: custom-tier data from their personal feed for configured symbols, default-tier data from the global feed for everything else. No duplicates, no gaps.

The filter check is O(1) per symbol per user (HashSet). The drain result from `defaultFeedStore` is computed once and shared across all sessions in the loop — the per-user filtering is a thin pass over an already-drained map.

---

## Why This Model Works

| Concern | How it's addressed |
|---|---|
| No overhead when no custom users connected | `volatile` array read → empty → loop body skipped |
| Most users use default for most symbols | `configuredKeys` check skips classify(); global feed handles it |
| User with lenient rules sees more symbols as HIGH | Each user has their own `SymbolState` map; their HIGH/LOW is independent |
| No allocation in hot path | Same scratch-buffer design as now, one `SymbolState` set per user per symbol, reused every tick |
| No stale state on reconnect | Context discarded on disconnect; client receives SNAPSHOT on reconnect |
| Thread safety | `volatile` array replaced atomically; no lock on consumer thread |
| GC efficiency | `UserClassificationContext` eviction is a single dereference; no incremental cleanup needed |

---

## Components Summary

| Class | Type | Phase | Responsibility |
|---|---|---|---|
| `ClassificationRule` | Interface | B | Contract: `computeTier(notional, distance, highLiquidity) → int` + `maxDistance(highLiquidity) → double` |
| `DefaultClassificationRule` | `@Component` singleton | B | Current tier thresholds + high-liquidity table + `isHighLiquidity`; fallback for unconfigured symbols |
| `ThresholdClassificationRule` | POJO | B (built/tested), C (consumed) | Immutable per-`(symbol, market)` override: primitive tier arrays, highest-first eval, ignores `highLiquidity` |
| `OrderBookClassifier` | Per-shard, non-bean | B (rule param), C (context loop) | Refactored: `selectTopK(...)`/`classifyOne(...)` take a `ClassificationRule`; Phase C adds the two-pass user context loop |
| `UserClassificationRule` | POJO | C | Per-`(symbol, market)` override map + `configuredKeys` set; delegates to default for unset keys |
| `UserClassificationContext` | POJO | C | Bundles user ID + rule + personal feed store + per-symbol HIGH/LOW states (`ConcurrentHashMap`) |
| `SymbolState` (+ nested `Scratch`) | package-private | C | Extracted from `OrderBookClassifier` so a context can declare `Map<String, SymbolState>` |
| Personal feed store | reused `OrderBookFeedStore` | C | **Phase C reuses a plain `new OrderBookFeedStore()` per custom-rule user — no dedicated `UserOrderBookFeedStore` class was built** |
| `UserFeedRegistry` | `@Component` | C | Lifecycle: create/discard contexts on connect/disconnect; updates shard arrays atomically |
| `OrderBookBroadcaster` | `@Component` | C | Extended: merge global feed (filtered) + personal feed per custom-rules user |

---

## What This Does NOT Cover

- REST API endpoints for saving / updating / deleting user classification rules — specified in [per-user-classification-phase-a.md](per-user-classification-phase-a.md) (**Phase A**).
- Persistence of user rules (database layer) — also Phase A.
- How `UserFeedRegistry` learns that a WebSocket session opened/closed (depends on the WebSocket server implementation, which is a separate phase).
- Live propagation of rule edits to already-connected users. Phase A + initial runtime wiring load rules **only at WebSocket connect time**; editing rules while connected requires a reconnect to take effect. Live update (rebuild + atomic context swap + fresh SNAPSHOT) is deferred to a later phase (**Phase D**).
