# OrderBook Sync Algorithm

## Overview

Each `(symbol, market)` pair maintains an independent `OrderBook` instance. A local orderbook must stay perfectly in sync with Binance's authoritative state. Because Binance only streams incremental diffs (not full book snapshots on the stream), a synchronization protocol is required at startup and after any connection loss.

---

## Why We Can't Sync All Orderbooks at Once

Synchronization requires a REST snapshot from Binance (`GET /api/v3/depth` for spot, `GET /fapi/v1/depth` for futures). Each snapshot call costs API weight:

| Market  | Snapshot weight cost | Hard limit/min | Guard threshold |
|---------|----------------------|----------------|-----------------|
| Spot    | 50 weight            | 6 000          | 5 800           |
| Futures | 20 weight            | 2 400          | 2 200           |

With ~500 futures + additional spot tickers, firing all snapshot requests simultaneously would consume thousands of weight units instantly and result in an IP ban (HTTP 429 / 418).

Additionally, only orderbooks in `SNAPSHOT_REQUESTED` state buffer diffs. If all orderbooks buffered from startup, each would accumulate diffs before a snapshot ever arrived — exhausting heap memory quickly at scale.

---

## State Machine

Three states:

| State                | Diff handling          | Snapshot handling |
|----------------------|------------------------|-------------------|
| `PENDING`            | Dropped                | N/A               |
| `SNAPSHOT_REQUESTED` | Buffered in diffBuffer | Triggers sync     |
| `SYNCED`             | Applied live           | N/A (ignored)     |

Re-sync failures (sequence gaps, parse errors, empty buffer after snapshot) call `resync()`, which clears the diff buffer and returns to `PENDING`. The orderbook then waits for the next diff to re-trigger the enqueue flow.

---

## Snapshot Fetch Queue and Rate Limiting

`SnapshotFetchQueue` maintains two bounded `ConcurrentHashMap`s — one for spot, one for futures — each capped at a configurable size (`spot-snapshot-queue-size: 10`, `futures-snapshot-queue-size: 10` in `application.yml`).

A `@Scheduled` method fires every `spot-snapshot-dispatch-rate-ms` (6 000 ms) and dispatches HTTP snapshot requests for all entries currently in the queue **concurrently**. At each firing, at most 10 requests go out per market. That's a maximum of ~100 requests per minute — well within Binance limits.

**Why bounded queue size matters for memory**: Only orderbooks that have been enqueued transition to `SNAPSHOT_REQUESTED` and start buffering diffs. The other ~490 orderbooks stay `PENDING` and drop all diffs. This ensures at most 10 diff buffers are active per market at any moment, bounding memory usage during the multi-minute startup ramp.

**Why bounded queue size also protects against weight-limit delays**: `WeightGuard`/`WeightLimitFilter` enforce Binance weight limits by delaying a request up to a full minute when the budget is nearly exhausted. If the queue were large (e.g. 30 tickers dispatched at once) and a weight-limit delay triggered mid-dispatch, all 30 snapshot requests would be held for up to a minute before completing. During that minute, all 30 orderbooks would keep buffering diffs — not catastrophic, but undesirable: larger buffers mean more replay work on sync and higher peak memory. Keeping the queue small (10) makes a delayed-dispatch scenario much more contained.

**Enqueue flow** (from `OrderBookProcessor`):
1. Consumer thread receives a diff for a `PENDING` orderbook.
2. `onDiff()` returns `NEEDS_SNAPSHOT`.
3. Processor calls `snapshotFetchQueue.enqueue(ob)`.
4. If enqueue succeeds: calls `ob.markSnapshotRequested()` (transitions to `SNAPSHOT_REQUESTED`), then **replays the same diff** into `ob.onDiff()` so that very first diff is buffered.
5. If enqueue fails (queue full): orderbook stays `PENDING`, diff is dropped.

---

## The 3-Second Delay

`SnapshotFetchQueue.dispatchSpot()` / `dispatchFutures()` apply `.delayElement(Duration.ofSeconds(3))` after the HTTP response arrives, before publishing the snapshot to the Disruptor ring buffer.

**Why**: The REST request completes in ~50–200 ms. If the snapshot is processed immediately, `diffBuffer` may contain only a handful of events, and their `u` values may all be less than the snapshot's `lastUpdateId`, leaving the buffer empty after the discard step. An empty buffer after snapshot = re-sync. With a 3-second delay, additional diffs accumulate in the buffer before the snapshot is applied — approximately 3 more diffs for spot (1 update/second) and 6 more diffs for futures (1 update/500 ms) — making it far more likely that valid sync-point diffs are available.

---

## Snapshot Publishing via Disruptor

When the WebClient Reactor thread receives the snapshot HTTP response, it does **not** write directly to the `OrderBook`. Instead, it publishes a `DepthEvent` with `type = EventType.SNAPSHOT` to the same shard's `RingBuffer` that handles diffs for that symbol.

This is critical for correctness: diffs and snapshots for the same symbol all flow through the same single-threaded Disruptor consumer. There is no concurrency between diff application and snapshot application for any given orderbook.

---

## `applySnapshot()` — Detailed Algorithm

Called by the consumer thread when a `SNAPSHOT` event arrives for a `SNAPSHOT_REQUESTED` orderbook.

```
1. Parse snapshot JSON → snapshotId (lastUpdateId), snapshotBids[], snapshotAsks[]
   - If parse fails → resync()

2. Discard stale buffered diffs:
   while diffBuffer.peekFirst().u < snapshotId:
       diffBuffer.pollFirst()
   
   Note: Binance docs for SPOT say discard where u <= snapshotId, but in practice
   snapshotId equals the u of the most recent buffered event, causing the entire buffer
   to be discarded. The implementation uses strict u < snapshotId (same as futures
   docs) to keep at least one overlap event.
   
   If diffBuffer is empty after this step → resync()

3. Validate sync point on the first remaining diff:
   U = first buffered diff's U field (first update ID)
   u = first buffered diff's u field (last update ID)
   Condition: snapshotId must be in [U, u]
   i.e.  U <= snapshotId <= u
   If condition fails → resync()

4. Load snapshot into TreeMaps:
   bids.clear(); asks.clear()
   Insert all snapshot levels with firstSeenMillis = System.currentTimeMillis()
   (Skip levels with qty = 0)

5. Apply first buffered diff (special case):
   Parse bid/ask levels only (no U/u/pu validation — we already validated the range)
   Apply level updates to bids/asks
   Set lastUpdateId = that diff's u field

6. Apply remaining buffered diffs via applyLiveDiff() (full sequence validation)
   If any returns non-OK → resync()

7. state = SYNCED
```

---

## `applyLiveDiff()` — Detailed Algorithm

Called for every diff in `SYNCED` state and for buffered diffs (steps 6+) during snapshot application.

```
1. Parse JSON fields: U, u, pu, bids[], asks[]

2. Sequence validation:
   SPOT:    if U != lastUpdateId + 1  → log warn → resync()
   FUTURES: if pu != lastUpdateId     → log warn → resync()
   
   These checks detect missed events or duplicate delivery.
   - U (uppercase): first update ID in this diff event
   - u (lowercase): last update ID in this diff event  
   - pu (futures only): the u value of the immediately preceding event
   - lastUpdateId: the u value of the previously applied event

3. Apply level updates to bids TreeMap and asks TreeMap:
   for each level in bids/asks:
     if qty == 0.0:
       map.remove(price)                         // level removed from book
     else if map.containsKey(price):
       entry.quantity = qty                      // in-place mutation, no allocation
     else:
       map.put(price, new PriceLevelEntry(qty, System.currentTimeMillis()))  // new level

4. Apply 30% filter:
   midPrice = (bids.firstKey() + asks.firstKey()) / 2.0
   lower = midPrice * 0.70
   upper = midPrice * 1.30
   Remove all entries from bids/asks outside [lower, upper]

5. lastUpdateId = u
```

---

## Sequence ID Fields — Reference

| Field         | Scope    | Meaning                                                         |
|---------------|----------|-----------------------------------------------------------------|
| `lastUpdateId`| Snapshot | The update ID at which Binance captured the snapshot            |
| `U` (uppercase) | Diff   | First update ID covered by this diff batch                      |
| `u` (lowercase) | Diff   | Last update ID covered by this diff batch                       |
| `pu`          | Diff (futures only) | The `u` value of the immediately preceding diff event |

**SPOT continuity rule**: Each diff's `U` must equal the previous diff's `u + 1`. A gap means one or more updates were lost.

**FUTURES continuity rule**: Each diff's `pu` must equal the previous diff's `u`. Binance explicitly provides this field to make gap detection unambiguous.

---

## Diff Buffer Overflow Guard

`MAX_BUFFER_SIZE = 500` entries. If a `SNAPSHOT_REQUESTED` orderbook accumulates 500 diffs without a snapshot arriving (e.g., the snapshot HTTP call is stuck), the buffer is cleared and state returns to `PENDING` (via `resync()`). This prevents unbounded heap growth in pathological cases.

---

## Price Level Lifecycle

| Event                              | Action                                      |
|------------------------------------|---------------------------------------------|
| New price, qty > 0                 | `map.put(price, new PriceLevelEntry(qty, now))` |
| Existing price, qty > 0            | `entry.quantity = qty` (in-place, zero alloc) |
| Any price, qty == 0                | `map.remove(price)` — mandatory per Binance protocol |
| Level drifts >30% from mid-price   | Swept by `apply30PercentFilter()` after each diff |
| Re-sync (resync() called)          | `bids.clear()`, `asks.clear()` — timestamps reset |
| Level returns after removal        | Fresh insert with new `firstSeenMillis` |

`PriceLevelEntry.firstSeenMillis` records when a price level first appeared in the current continuous presence. UI can expose `System.currentTimeMillis() - entry.firstSeenMillis` as the level's age.

---

## Summary of Non-Obvious Design Decisions

1. **3-second snapshot delay** — prevents the buffer from being empty when the snapshot is applied, avoiding immediate re-sync loops.
2. **Snapshot published via Disruptor** — keeps all orderbook mutations single-threaded per shard; snapshot and diffs are never concurrent.
3. **Strict `u < snapshotId` discard (not `u <= snapshotId`)** — the spot docs say `<=` but this empties the buffer in practice; strict `<` retains the overlap event needed for sync.
4. **First diff special-cased in applySnapshot** — U/u/pu are not validated for the first diff because its range overlap with snapshotId was already verified in step 3.
5. **`NEEDS_SNAPSHOT` replays the current diff** — after transitioning to `SNAPSHOT_REQUESTED`, `OrderBookProcessor` replays the triggering diff so it isn't lost.
6. **Queue capacity serves two purposes** — limits how many orderbooks hold diff buffers simultaneously (memory), and caps the blast radius if a weight-limit delay triggers mid-dispatch (at most 10 buffers grow for up to a minute, not hundreds).
