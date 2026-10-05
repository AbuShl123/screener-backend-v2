# Order Book Depth Data Communication Plan

## Overview

This document covers how the screener backend classifies orderbook price levels and delivers that classified data to connected clients over WebSocket. It spans Phase 4 (order classification) and Phase 5 (user-facing WebSocket server), because the two are tightly coupled: classification determines what gets sent, and the delivery contract shapes what classification must produce.

---

## Business Requirements

1. **Only meaningful orderbooks are shown.** An orderbook is "active" if it contains at least one bid or ask at tier-4 or higher. Orderbooks where every level is tier-5 are never sent to clients and are not displayed in the UI.

2. **Display exactly 5 bids and 5 asks per orderbook.** Even though the backend retains the full filtered orderbook (up to ±30% from mid-price), clients receive only the 5 most important bids and 5 most important asks. Importance is defined by the classification tier (see Sorting Rules below).

3. **Clients are notified of order appearances and disappearances.** If a tier-4+ order appears in an orderbook that was previously all-tier-5, the client is notified. If an orderbook drops back to all-tier-5, the client is notified and can alert the user about the removed orders. Individual order notifications within the visible top-5 are derived by the frontend by diffing the previous and current state.

4. **Updates are delivered in real time.** A new tier-1 order must reach the client within ~100ms of the Binance depth update arriving. Data is pushed via WebSocket — not polled by the client.

5. **Users have no control over which tickers are shown.** Clients subscribe to the screener feed, not to specific tickers. They receive data for whichever tickers happen to have interesting orders at any moment.

---

## Tier Notation

| Tier   | Former label | Relative importance |
|--------|--------------|---------------------|
| tier-1 | Purple       | Highest             |
| tier-2 | Red          | High                |
| tier-3 | Yellow       | Moderate            |
| tier-4 | Green        | Low but visible     |
| tier-5 | Gray         | Below threshold; not sent |

---

## Orderbook Requirements for Delivery

### Sorting Rules (top-5 selection)

Both bids and asks are ranked independently. Sort by:

1. **Tier ascending** — tier-1 beats tier-2 beats tier-3 beats tier-4
2. **Proximity to spread ascending** — closer to best bid/ask is more important within the same tier
3. **Notional descending** — larger `price × quantity` breaks remaining ties

Take the first 5 entries from each ranked list. These are the 5 bids and 5 asks sent to clients.

### Active / Inactive threshold

- **Active**: orderbook has ≥ 1 bid or ask at tier-4 or higher after applying the top-5 selection
- **Inactive**: all levels are tier-5 — orderbook is excluded from the feed entirely

### Level fields sent per entry

Each of the 5 bids / 5 asks carries:
- `price` (double)
- `quantity` (double)
- `tier` (1–4)
- `firstSeenMillis` — the wall-clock timestamp (epoch ms) when this price level first appeared in the current continuous presence. Frontend computes age as `Date.now() - firstSeenMillis`, which stays accurate as long as the level is displayed.

---

## WebSocket Message Protocol

All messages are JSON. Every message has a `seq` field — a per-connection integer incremented by 1 for each message sent by the backend.

### Message types

**`SNAPSHOT`** — sent immediately when a client connects (or in response to `SNAPSHOT_REQUEST`). Contains the full current active set.

```json
{
  "type": "SNAPSHOT",
  "seq": 1,
  "data": [
    {
      "symbol": "BTCUSDT",
      "market": "FUTURES",
      "bids": [{ "price": 68000.0, "quantity": 1.5, "tier": 2, "firstSeenMillis": 1716300000000 }, ...],
      "asks": [{ "price": 68050.0, "quantity": 0.8, "tier": 1, "firstSeenMillis": 1716300003100 }, ...]
    },
    ...
  ]
}
```

**`ADD`** — a ticker transitions from inactive (all tier-5) to active (≥ 1 tier-4+). The full top-5 is included so the client can immediately display it and fire appearance notifications.

```json
{
  "type": "ADD",
  "seq": 14,
  "symbol": "ETHUSDT",
  "market": "SPOT",
  "bids": [...],
  "asks": [...]
}
```

**`UPDATE`** — an already-active ticker's top-5 changed (levels moved tiers, new levels appeared, existing levels disappeared within the visible set). Full new top-5 is included. Frontend replaces its stored state for this ticker and diffs old vs. new to derive per-order notifications.

```json
{
  "type": "UPDATE",
  "seq": 15,
  "symbol": "BTCUSDT",
  "market": "FUTURES",
  "bids": [...],
  "asks": [...]
}
```

**`DROP`** — a ticker transitions from active to inactive (all levels fell to tier-5). No level data is needed; the frontend already holds the last known top-5 and can derive removal notifications from it.

```json
{
  "type": "DROP",
  "seq": 16,
  "symbol": "XRPUSDT",
  "market": "FUTURES"
}
```

**`SNAPSHOT_REQUEST`** — sent by the **client** (not the backend) when it detects a sequence gap. The backend responds with a fresh `SNAPSHOT` message and resets the connection's `seq` counter to 1.

### Sequence number safety net

Each backend message carries a monotonically increasing `seq` (per-connection, starts at 1 with the initial `SNAPSHOT`). The frontend checks that each incoming `seq` equals the previous `seq + 1`. On any gap, the frontend sends `SNAPSHOT_REQUEST` and the backend responds with a full `SNAPSHOT` at `seq: 1`.

WebSocket over TCP does not drop or reorder frames within an established connection, so a sequence gap in practice indicates a reconnect without an explicit snapshot request. The frontend should also always send `SNAPSHOT_REQUEST` on every (re)connect before processing any other messages.

---

## Architecture: Data Processing vs. Network Layer

### Principle

The Disruptor consumer thread (data processing) and the sender thread (network) must never block each other. The consumer thread is on the critical latency path — stalling it would cause the ring buffer to back-pressure all diff processing across the entire shard.

### Disruptor consumer thread (classification layer)

Runs classification on every diff applied to a `SYNCED` orderbook. Responsibilities:

1. Apply the diff to `bids` / `asks` TreeMaps (already implemented in Phase 3)
2. Classify each price level (tier-1 through tier-5)
3. Select top-5 bids and top-5 asks using the sorting rules above
4. Determine the message type: ADD, UPDATE, or DROP
5. Write the result into two shared data structures (see below) — O(1), non-blocking

### Two shared data structures

**`snapshotMap`: `ConcurrentHashMap<String, ActiveOrderBook>`**

Key: `"SYMBOL:MARKET"`. Value: the full top-5 snapshot for that ticker (the last ADD or UPDATE state).

- Consumer thread writes on every ADD or UPDATE
- Consumer thread removes the key on every DROP
- Sender thread reads this map atomically when a new client connects or when a `SNAPSHOT_REQUEST` arrives
- This map always reflects the latest known active state — it is the authoritative source for initial snapshots

**`pendingRef`: `AtomicReference<ConcurrentHashMap<String, PendingUpdate>>`**

Key: `"SYMBOL:MARKET"`. Value: ADD / UPDATE / DROP for that ticker since the last drain cycle.

- Consumer thread writes via `pendingRef.get().put(key, update)` — never blocks
- Sender thread atomically swaps the map every 100ms: `pendingRef.getAndSet(new ConcurrentHashMap<>())`, then processes the taken snapshot
- Because it is a map (not a queue), multiple consumer writes between drain cycles are coalesced — only the latest result per ticker is retained
- This is the intended behaviour: if ETHUSDT was classified 3 times in one 100ms window, only the final state is sent

### Sender thread

A single dedicated thread. Two responsibilities:

1. **100ms drain loop**: every 100ms, swap `pendingRef`, build outgoing messages from the taken map, broadcast to all connected clients
2. **New client handler**: when a client connects, read `snapshotMap`, build and send the `SNAPSHOT` message, assign `seq: 1`

Both responsibilities run on the same thread so there is no concurrency within the sender itself. The sender is never on the critical path of the consumer thread.

### Why 100ms is sufficient

- Binance `@depth` streams deliver at most 1 update/second (spot) or 1 update/500ms (futures) per ticker
- A 100ms drain captures every update within one window — no update is ever older than 100ms when it reaches the client
- From the user's perspective, 100ms is imperceptible; the feed feels instantaneous
- Multiple tickers updating within the same 100ms window are batched into a single drain cycle, which is efficient and keeps message volume manageable

---

## Known Edge Cases

### Redundant UPDATE after initial SNAPSHOT (harmless)

If a client connects at t=50ms and the next drain fires at t=100ms, the drain may include a pending UPDATE for a ticker whose state is already reflected in the snapshot sent at t=50ms. The client receives a redundant UPDATE with identical data. This is idempotent — the frontend replaces its stored state with the same values and no visual change occurs. Known minor behaviour, not a bug.

### DROP message carries no level data

The DROP message only contains `symbol` and `market`. The frontend holds the last ADD or UPDATE state locally and derives which orders disappeared from that copy. This keeps DROP messages small and avoids re-serialising data the client already has.

### SNAPSHOT_REQUEST resets seq to 1

The backend sends a fresh full SNAPSHOT with `seq: 1` in response to SNAPSHOT_REQUEST, restarting the counter for that connection. The client discards all buffered state and replaces it with the snapshot contents.

---

## Multi-User Classification (Future Work)

Phase 4 implements a single default classification rule applied by the consumer thread. In a future phase, users will be able to define custom importance thresholds (proximity cutoffs, notional cutoffs). When that happens:

- The default classification produces the feed described in this document
- Per-user classification will need to run additional rule sets, either inside the consumer thread (for a small number of users) or offloaded to user-specific worker threads (for scale)
- The sender thread will need per-user send logic rather than a single broadcast

This is explicitly out of scope for Phase 4 and Phase 5. The classification logic must be behind a clean interface (not inlined in the consumer) so it can be extended without restructuring the pipeline.

---

## What Is Not Yet Decided

- Exact classification thresholds (what notional and proximity values separate tier-1 from tier-2, etc.) — to be defined during Phase 4 implementation
- WebSocket server technology (Spring WebSocket, raw `javax.websocket`, Netty) — to be chosen during Phase 5
- Per-user session management, authentication, and subscription model — Phase 5+
