# Phase 4 + 5 Implementation Plan: Classification & WebSocket Feed

## Overview

This document is a step-by-step coding guide for the classification pipeline and the outbound WebSocket feed. The companion design doc is `.claude/plans/depth-data-communication.md` — read it first for rationale. This document contains only what to build, where to put it, and what caveats to watch for.

---

## New Packages

| Package | Purpose |
|---------|---------|
| `dev.abu.screener_backend.analysis` | Per-shard order classifier |
| `dev.abu.screener_backend.feed` | Feed store, broadcaster, session stub, DTOs |

---

## Step 1 — DTOs (`feed` package)

Three new types; all plain data, no Spring annotations.

### `ClassifiedLevel` — record

All fields are primitives — Java record `equals()` compares them by value, which is correct and used by the classifier's change-detection logic.

```java
package dev.abu.screener_backend.feed;

public record ClassifiedLevel(
    double price,
    double quantity,
    int tier,             // 1 (highest) – 5 (not shown)
    long firstSeenMillis
) {}
```

### `FeedEventType` — enum

```java
package dev.abu.screener_backend.feed;

public enum FeedEventType { ADD, UPDATE, DROP }
```

### `OrderBookUpdate` — record

Single type used for both `snapshotMap` values and `pendingRef` values. Carries the event type alongside the level data, eliminating any separate wrapper class.

`bids` and `asks` are fixed-size arrays (`ClassifiedLevel[TOP_LEVELS]`). Slots beyond the actual level count are `null` — callers must iterate until null. For DROP events both arrays are empty (`new ClassifiedLevel[0]`).

```java
package dev.abu.screener_backend.feed;

import dev.abu.screener_backend.binance.websocket.Market;

public record OrderBookUpdate(
    String symbol,
    Market market,
    FeedEventType type,
    ClassifiedLevel[] bids,
    ClassifiedLevel[] asks
) {}
```

**Note on record `equals()`**: Java records use `Object.equals()` for array fields, which is reference equality — not deep equality. Never compare two `OrderBookUpdate` instances with `equals()`. The classifier's change-detection compares `ClassifiedLevel[]` arrays directly via `Arrays.equals()`, not via this record.

The broadcaster reads `snapshotMap` entries to build SNAPSHOT messages — it ignores the `type` field there (it's always ADD or UPDATE in the snapshot map, since DROP entries are removed).

---

## Step 2 — `OrderBookFeedStore` (`feed` package)

`@Component`. Shared data structure between the classifier (consumer threads) and the broadcaster (sender thread). Never exposes internal maps directly — all access goes through the three methods below.

### Fields

```java
private final ConcurrentHashMap<String, OrderBookUpdate> snapshotMap = new ConcurrentHashMap<>();
private final AtomicReference<ConcurrentHashMap<String, OrderBookUpdate>> pendingRef
        = new AtomicReference<>(new ConcurrentHashMap<>());
```

### `submit(String key, OrderBookUpdate update)` — called by classifier (consumer thread)

Writes to `pendingRef` with coalescing, then syncs `snapshotMap`.

**Coalescing rule** — the map stores the net effect since the last drain, so multiple classifier writes within one 100ms window are collapsed to a single entry per ticker:

| Existing in pendingRef | Incoming | Result |
|------------------------|----------|--------|
| — | ADD | ADD |
| — | UPDATE | UPDATE |
| — | DROP | DROP |
| ADD | UPDATE | UPDATE (client treats UPDATE as ADD for unknown symbols) |
| ADD | DROP | remove key entirely |
| UPDATE | UPDATE | UPDATE (with new data) |
| UPDATE | DROP | DROP |
| DROP | ADD | ADD |
| DROP | DROP | DROP |

ADD + UPDATE within the same 100ms window coalesces to UPDATE. The client is expected to upsert on UPDATE — if it has no existing entry for the symbol it treats UPDATE as ADD. This avoids an extra `OrderBookUpdate` allocation that a re-wrap to ADD would require.

Implementation: use `ConcurrentHashMap.merge(key, update, (existing, incoming) -> coalesce(existing, incoming))`. When `coalesce` returns `null`, `merge` removes the key (handles the ADD + DROP case).

**snapshotMap sync** — based directly on the incoming `update.type()`, not on the coalesced result in `pendingRef`:
- Incoming is ADD or UPDATE → `snapshotMap.put(key, update)`
- Incoming is DROP → `snapshotMap.remove(key)`

This avoids a second `pendingRef.get()` call. Re-reading `pendingRef` after the merge would race with the broadcaster's drain swap, potentially returning an empty map and incorrectly removing a live symbol from the snapshot. Basing the sync on `update.type()` directly is race-free and semantically correct: the snapshot map reflects current state, and the incoming update always carries the current state.

### `getSnapshot()` — called by broadcaster (sender thread, new-client path)

Returns an unmodifiable view of `snapshotMap` for building the initial SNAPSHOT message.

```java
public Map<String, OrderBookUpdate> getSnapshot() {
    return Collections.unmodifiableMap(snapshotMap);
}
```

### `drainPending()` — called by broadcaster (sender thread, 100ms loop)

Atomic swap; returns the map that was in `pendingRef` before the swap. The consumer thread immediately starts writing into the fresh empty map.

```java
public Map<String, OrderBookUpdate> drainPending() {
    return pendingRef.getAndSet(new ConcurrentHashMap<>());
}
```

**Known race**: the consumer can read `pendingRef.get()` before the broadcaster's drain swap and then call `merge()` into the already-drained map after the swap. That update is lost from the pending stream until the next diff arrives (within ≤500ms). Acceptable — this is a screener, not an execution system. Do not add a lock here; that would stall the consumer thread.

Note: the `snapshotMap` is no longer subject to this race — it is synced from `update.type()` directly, not from a second read of `pendingRef`.

---

## Step 3 — `UserWebSocketSession` (`feed` package)

Stub only — no real WebSocket session yet. All send logic is a no-op for now.

```java
package dev.abu.screener_backend.feed;

public class UserWebSocketSession {

    public enum Status { NEED_SNAPSHOT, READY }

    private volatile Status status = Status.NEED_SNAPSHOT;

    // Accessed only by the sender thread — no need for volatile or atomic.
    // Reset to 0 when status returns to NEED_SNAPSHOT; first sendData() makes it 1.
    private int seqNumber = 0;

    public Status getStatus() { return status; }

    public void setStatus(Status status) {
        if (status == Status.NEED_SNAPSHOT) seqNumber = 0;
        this.status = status;
    }

    /**
     * Called by the broadcaster. Increments seq, then transmits.
     * The broadcaster is responsible for embedding the correct seq in the json
     * by calling getAndIncrementSeq() before serializing.
     */
    public void sendData(String json) {
        // stub — real session.sendMessage(json) goes here in Phase 5
    }

    /** Returns current seqNumber then increments it. Called by broadcaster before serialization. */
    public int getAndIncrementSeq() {
        return ++seqNumber;
    }
}
```

`status` is `volatile` because in Phase 5 the WebSocket receive callback (a different thread) will set it back to `NEED_SNAPSHOT` when a `SNAPSHOT_REQUEST` arrives from the client. Enforcing the rule now costs nothing.

---

## Step 4 — `OrderBookBroadcaster` (`feed` package)

`@Component`. Runs the 100ms drain loop and handles new-client snapshots. All broadcaster logic runs on a single `@Scheduled` thread — no concurrency inside this class.

### Fields

```java
private final OrderBookFeedStore feedStore;
private final ObjectMapper objectMapper;          // Jackson, for JSON serialization
private final List<UserWebSocketSession> sessions = new CopyOnWriteArrayList<>();
```

Use `CopyOnWriteArrayList` so Phase-5 connection/disconnection (from another thread) doesn't require extra locking.

### `@Scheduled(fixedDelay = 100) void drain()`

```
1. if sessions is empty: return immediately (skip drainPending to avoid unnecessary allocation)
2. pending = feedStore.drainPending()
3. For each session:
   a. If session.getStatus() == NEED_SNAPSHOT:
        snapshot = feedStore.getSnapshot()
        json = buildSnapshotMessage(snapshot, session.getAndIncrementSeq())
        session.sendData(json)
        session.setStatus(READY)
        // do NOT also send the pending updates — they're already reflected in the snapshot
   b. Else if !pending.isEmpty():
        For each entry in pending.values():
          json = buildUpdateMessage(entry, session.getAndIncrementSeq())
          session.sendData(json)
```

**Serialization efficiency**: the same payload can be sent to multiple READY sessions. Pre-serialize the payload once per update (without seq), then per-session wrap it as `{"seq":<n>, ...payload}`. For the stub this optimization is not required — serialize fully per session. Leave a TODO comment for Phase 5.

### JSON message shapes

Build these as `Map<String, Object>` and pass through `objectMapper.writeValueAsString()`. When iterating `ClassifiedLevel[]`, stop at the first `null` slot.

**SNAPSHOT**:
```json
{ "type": "SNAPSHOT", "seq": 1, "data": [ { "symbol": "...", "market": "...", "bids": [...], "asks": [...] } ] }
```

**ADD / UPDATE**:
```json
{ "type": "ADD", "seq": 14, "symbol": "ETHUSDT", "market": "SPOT", "bids": [...], "asks": [...] }
```

**DROP**:
```json
{ "type": "DROP", "seq": 16, "symbol": "XRPUSDT", "market": "FUTURES" }
```

### `addSession(UserWebSocketSession session)` — called from Phase-5 WebSocket server

Adds to the `CopyOnWriteArrayList`. The session starts with `status = NEED_SNAPSHOT` so the next drain cycle will send it a full snapshot.

---

## Step 5 — `OrderBookClassifier` (`analysis` package)

One instance per Disruptor shard. The classifier's internal state is always accessed by its owning shard's consumer thread only — no synchronization needed on its internal maps.

### Constant

```java
public static final int TOP_LEVELS = 5;
```

Defined here because this is where the limit is enforced. The broadcaster uses it when iterating `ClassifiedLevel[]` arrays (stop at `TOP_LEVELS` or first null, whichever comes first).

### Inner types

```java
private enum ActivityLevel { LOW, HIGH }

private static class SymbolState {
    ActivityLevel level = ActivityLevel.LOW;
    ClassifiedLevel[] lastBids = new ClassifiedLevel[0];
    ClassifiedLevel[] lastAsks = new ClassifiedLevel[0];
}
```

### Fields

```java
private final OrderBookFeedStore feedStore;
private final Map<String, SymbolState> states = new HashMap<>();
```

### `void process(OrderBook ob)` — entry point from `DepthEventHandler`

```
key = ob.getSymbol() + ":" + ob.getMarket()
state = states.computeIfAbsent(key, _ -> new SymbolState())

if ob.getState() != SYNCED:
    if state.level == HIGH:
        drop = new OrderBookUpdate(ob.getSymbol(), ob.getMarket(), DROP, new ClassifiedLevel[0], new ClassifiedLevel[0])
        feedStore.submit(key, drop)
        state.level = LOW
        state.lastBids = new ClassifiedLevel[0]
        state.lastAsks = new ClassifiedLevel[0]
    return

bids = classify(ob.getBids())
asks = classify(ob.getAsks())

hasVisible = any non-null slot in bids or asks has tier <= 4

if hasVisible:
    if state.level == LOW:
        update = new OrderBookUpdate(ob.getSymbol(), ob.getMarket(), ADD, bids, asks)
        feedStore.submit(key, update)
        state.level = HIGH
    else if !Arrays.equals(bids, state.lastBids) OR !Arrays.equals(asks, state.lastAsks):
        update = new OrderBookUpdate(ob.getSymbol(), ob.getMarket(), UPDATE, bids, asks)
        feedStore.submit(key, update)
    state.lastBids = bids
    state.lastAsks = asks
else:
    if state.level == HIGH:
        drop = new OrderBookUpdate(ob.getSymbol(), ob.getMarket(), DROP, new ClassifiedLevel[0], new ClassifiedLevel[0])
        feedStore.submit(key, drop)
        state.level = LOW
    state.lastBids = new ClassifiedLevel[0]
    state.lastAsks = new ClassifiedLevel[0]
```

**Change-detection for UPDATE**: use `Arrays.equals(bids, state.lastBids)`. Since `ClassifiedLevel` is a record with all-primitive fields, its auto-generated `equals()` compares by value — `Arrays.equals()` calls it element-by-element. This is intentional: a level removed and re-added at the same price gets a new `firstSeenMillis`, which counts as a change (age counter resets on the frontend).

Do **not** use `OrderBookUpdate.equals()` for comparison — record `equals()` on array fields is reference equality only.

### `ClassifiedLevel[] classify(TreeMap<Double, PriceLevelEntry> levels)` — stub

Returns a `ClassifiedLevel[TOP_LEVELS]` array. Slots beyond the actual level count are `null`.

```java
// STUB: all levels assigned tier-4 so every synced book registers as active.
// Real thresholds (proximity %, notional USD) to be implemented in Phase 4.
// With this stub every diff causes an UPDATE — expected during development.
ClassifiedLevel[] result = new ClassifiedLevel[TOP_LEVELS];
int i = 0;
for (Map.Entry<Double, PriceLevelEntry> e : levels.entrySet()) {
    if (i == TOP_LEVELS) break;
    result[i++] = new ClassifiedLevel(
        e.getKey(),
        e.getValue().quantity,
        4,
        e.getValue().firstSeenMillis);
}
return result;
```

`PriceLevelEntry.distance` (% from mid-price, already computed by `apply30PercentFilter()`) is available for the real classifier — no need to recompute proximity.

**IMPORTANT**: `ob.getBids()` and `ob.getAsks()` return the live `TreeMap` references, not copies. The classifier must only call `process()` from the consumer thread that owns this shard. Never cache or pass these TreeMap references elsewhere.

---

## Step 6 — Changes to Existing Classes

### 6a — `OrderBook` — add public getters

`symbol`, `market`, `bids`, `asks` are currently package-private. Add:

```java
public String getSymbol() { return symbol; }
public Market getMarket()  { return market; }

/** Live bids TreeMap. Must only be accessed by this shard's consumer thread. */
public TreeMap<Double, PriceLevelEntry> getBids() { return bids; }

/** Live asks TreeMap. Must only be accessed by this shard's consumer thread. */
public TreeMap<Double, PriceLevelEntry> getAsks() { return asks; }
```

### 6b — `OrderBookProcessor` — change return type to `@Nullable OrderBook`

Current signature: `public void process(DepthEvent event)`
New signature: `public @Nullable OrderBook process(DepthEvent event)`

Return `ob` at the end of the method in all paths where `ob` is non-null. Return `null` only when `ob == null` (SNAPSHOT event for a book that doesn't exist yet). The caller (`DepthEventHandler`) passes the returned book to the classifier regardless of the `OrderBookResult` — the classifier itself decides whether the book is in a classifiable state.

### 6c — `DepthEventHandler` — add classifier field and call

```java
private final OrderBookClassifier classifier; // injected via constructor

@Override
public void onEvent(DepthEvent event, long sequence, boolean endOfBatch) {
    OrderBook ob = processor.process(event);
    if (ob != null) classifier.process(ob);
    event.clear();
}
```

### 6d — `DisruptorShardManager` — create and inject classifiers

Inject `OrderBookFeedStore` (no new circular dependency introduced).

In `@PostConstruct init()`, when constructing each `DepthEventHandler`, also construct one `OrderBookClassifier` for that shard:

```java
OrderBookClassifier classifier = new OrderBookClassifier(feedStore);
handlers[i] = new DepthEventHandler(i, orderBookProcessor, classifier);
```

`OrderBookClassifier` is **not** a Spring bean — it is constructed manually by `DisruptorShardManager`. One instance per shard, no shared state.

---

## Step 7 — CURRENT_STATE.md update

After implementation, update `CURRENT_STATE.md`:
- Add `analysis/OrderBookClassifier.java` to the project layout
- Add all three `feed/` classes (`ClassifiedLevel`, `FeedEventType`, `OrderBookUpdate`, `OrderBookFeedStore`, `UserWebSocketSession`, `OrderBookBroadcaster`)
- Mark "Order classification" as complete (stub) in the status table
- Note that `OrderBook` gained four public getters

---

## Implementation Order

1. DTOs (`ClassifiedLevel`, `FeedEventType`, `OrderBookUpdate`)
2. `OrderBookFeedStore`
3. `UserWebSocketSession`
4. `OrderBookBroadcaster`
5. `OrderBook` — add four getters
6. `OrderBookProcessor` — change return type
7. `OrderBookClassifier`
8. `DepthEventHandler` — add classifier field
9. `DisruptorShardManager` — wire classifier
10. Update `CURRENT_STATE.md`

Steps 1–4 are completely independent of each other and can be done in any order. Steps 5–9 are sequential — each depends on the previous being compilable.

---

## What This Does NOT Implement

- Real WebSocket server (Phase 5 — the `sendData()` stub is a deliberate placeholder)
- Real classification thresholds (stub always returns tier-4)
- Per-user classification rules
- `SNAPSHOT_REQUEST` handling from the client side (the `volatile` on `UserWebSocketSession.status` is the only preparation)
