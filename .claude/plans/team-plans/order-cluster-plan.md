# Layered Order-Cluster Detection — Design Notes and Plan

**Created**: 2026-10-05, branch `feature/multi-exchange`. Status: **design / pre-implementation**.
Captures the discussion so far: what the feature is, how it fits the pipeline, the architectural
decisions, open questions, and a proposed phasing and team split. Nothing here is built yet.

**Read first**
- `.claude/docs/multi-exchange-progress.md` — SPI, core pipeline, sync contract.
- `marketdata/core/ingress/DepthEventHandler` and `DisruptorShardManager` — where the detector is wired.
- `marketdata/core/book/OrderBook`, `PriceLevelEntry` — the data the detector walks.
- `analysis/OrderBookClassifier`, `SymbolState` — the per-shard, per-instrument pattern to mirror.
- `feed/OrderBookFeedStore`, `feed/OrderBookBroadcaster` — the delivery path clusters join, and
  `.claude/plans/feed-channels-impl-plan.md` — how it becomes `FeedBroadcaster` + `FeedChannel`.
- `.claude/plans/team-plans/spike-alerts-plan.md` — renames the event handler into a dispatcher
  (D3/D4) and introduces the common WS envelope and feed channels (D16/D17) that cluster delivery
  is built on; see §7.

---

## 1. The feature

One large order is sometimes split into many small orders on consecutive price levels. Each level
is small (e.g. ~$100K), so the classifier rates every one of them tier 0 and nothing is shown —
yet together they form one large wall.

Example (Binance spot, ARB/USDT, June 2026, reported by a user):

```
700 - $120K
701 - $113K
702 - $101K
703 - $100K
...
740 - $102K        → ~40 levels, ~$4M combined
```

Traders want these surfaced. The UI shows an alert first; the user then opens a detail view with
the full ladder.

---

## 2. Facts

- **The data is already there.** Every `SYNCED` book holds all levels within the price filter
  (`screener.orderbook.price-filter-threshold`, default 0.1). No new streams, no sync changes.
- **No tick size is stored.** `Instrument` has no tick size, so "consecutive" can only mean
  *adjacent keys in the TreeMap* — which may have empty ticks between them.
- **Books are single-thread-owned.** Only the shard's consumer thread may read the TreeMap. Any
  data the UI needs must be copied out on that thread; an on-demand REST "show me the levels"
  endpoint would have to go through the ring.
- **Thick books look like clusters.** Near the mid on BTC/ETH/SOL futures, levels are routinely
  $0.5–2M each and fairly uniform. "40 similar levels summing to $4M" is a normal BTC book.

---

## 3. Analysis

### 3.1 Detection is the hard part, not the plumbing

"Similar notional on consecutive levels" is necessary but not sufficient. The rule needs:

- **Run building** — walk one side outward from the best price, extend a run while the next level
  is "similar" and within the gap tolerance; close it otherwise.
- **Similarity** — e.g. each level within ±X% of the run's median notional.
- **Gap tolerance** — whether a missing level breaks the run (needs a definition without tick
  size: a max price gap relative to the run's typical step, or a max price span).
- **Stand-out ratio** — the cluster's average level notional must be ≥ k × the median level
  notional of that side. This is what keeps thick books from triggering constantly.
- **Minimums** — minimum level count, minimum total notional, maximum distance from mid.

### 3.2 Thresholds: neither the existing tiers nor a flat $1M

- A flat $1M is meaningless across symbols — notable on ARB, noise on BTC.
- The existing tier rules are tuned for *single levels*. Feeding a cluster total into them, or
  ranking clusters in the top-5, changes the meaning of the current feed contract.
- **Decision**: clusters get their own small rule set under `screener.clusters.*`, liquidity-aware
  (reuse `DefaultClassificationRule.isHighLiquidity`'s split — one threshold set per class), plus
  the stand-out ratio. Global only in v1; per-user cluster rules are later work.

### 3.3 Hysteresis and lifetime

A cluster near the threshold flickers in and out as quantities jitter. Without hysteresis the UI
gets an alert storm.

- Enter at a higher total than exit (e.g. enter ≥ T, exit < 0.8·T).
- Minimum lifetime before the first emit (e.g. present for ≥ N seconds) — open question Q-B4.
- Re-emit only on a meaningful change: total moved by > X%, or the price bounds changed. Otherwise
  every tick's per-level jitter would produce an update.

### 3.4 Performance — it is hot path

The detector runs on the shard consumer thread, after classification, for every event. It is a
second walk of the book. Hot-path rules from `CLAUDE.md` apply.

- **Throttle per instrument** — run detection at most every 500ms–1s (`lastRunMillis` in the
  instrument's state). Clusters are slow-moving structures; this costs nothing in usefulness and
  caps the cost regardless of diff rate.
- **Bound the walk** by the cluster max distance, the same early-break the classifier uses.
- **No allocation while scanning** — track the current run in primitive fields; allocate only when
  emitting a cluster that changed.
- **Keep it separate from the classifier's loop.** Fusing the two walks is tempting but couples two
  features; measure first, fuse only if the numbers demand it.

---

## 4. Architectural decisions (proposed)

### D1. One detector per shard, wired after classification

`OrderClusterDetector` is created per shard by `DisruptorShardManager`, like `OrderBookClassifier`,
and called from `MarketEventHandler.onEvent` (today's `DepthEventHandler`, renamed by spike-alerts
D3) in the **depth** branch of its dispatcher, right after `classificationModule.process(...)`.
Per-instrument state lives in a map keyed by `feedKey` (touched only by the owning shard thread).
A book that is not `SYNCED`, or has an empty side, drops its clusters.

### D2. Clusters are state, not one-shot alerts

A cluster sits in the book, grows, shrinks, and gets filled or pulled — its disappearance is a
signal too. So it is modelled like the existing depth feed: **one upserted entry per instrument,
removed when empty**, not a fire-and-forget event (contrast spike alerts, which are true events).

The UI derives the "alert" toast from a cluster appearing that it did not hold before; the backend
does not emit a separate alert event.

### D3. Own feed store, same pattern

`ClusterFeedStore` mirrors `OrderBookFeedStore`: one entry per `feedKey` holding the instrument's
current `bidClusters[]` / `askClusters[]`, 100ms coalescing with the same ADD/UPDATE/DROP table,
and a snapshot map. Up to K clusters per side per instrument (K configurable). On the wire, ADD and
UPDATE are both a `CLUSTER` upsert and DROP is `"data": null` (D4).

### D4. Delivery: a `ClusterChannel` on the common envelope

Clusters use the common WS envelope and feed channels from spike-alerts D16/D17:
- `type: "CLUSTER"`. An upsert carries the instrument's current clusters; `"data": null` removes
  them.
- `ClusterChannel` implements `FeedChannel`: it drains `ClusterFeedStore` once per tick and gives
  every session the same bodies. There are no per-user cluster rules, so there is no `ruleKey`
  filter.
- A client misses messages only by disconnecting (spike-alerts D8). Every (re)connect starts with a
  snapshot that includes one `CLUSTER` entry per instrument with clusters, so a reconnecting client
  sees exactly the clusters that exist now — recovery needs no new mechanism.
- Entitlement is already enforced at `@OnOpen` — nothing new.

### D5. Payload carries the full ladder, copied on the shard thread

The cluster is rare, so sending the full window (~40 levels ≈ 2KB) is cheap. The levels are copied
on the shard thread at emit time (the TreeMap cannot be read from elsewhere).

### D6. Feature flags

`screener.clusters.enabled` (run the detector at all) and `screener.clusters.delivery-enabled`
(push to clients — `ClusterChannel` emits nothing while it is off). Detection with delivery off is
the shadow-tuning mode (Phase 3).

### Draft cluster contract (to agree before FE/BE split)

```json
{ "type": "CLUSTER", "exchange": "BINANCE", "market": "SPOT", "symbol": "ARBUSDT",
  "data": {
    "bidClusters": [
      { "priceFrom": 0.0700, "priceTo": 0.0740, "levelCount": 41,
        "totalNotional": 4120000, "totalQuantity": 57200000,
        "distance": 0.031, "firstSeenMillis": 1759667423000,
        "levels": [[0.0700, 1714285], [0.0701, 1611983], "..."] }
    ],
    "askClusters": [] } }

{ "type": "CLUSTER", "exchange": "BINANCE", "market": "SPOT", "symbol": "ARBUSDT",
  "data": null }
```

`SNAPSHOT` entries are exactly these envelopes. Field names inside `data` and compact
`[price, qty]` levels are open (Q-T3).

---

## 5. Blast radius

| Kind | Where |
|---|---|
| **New** | `analysis/cluster/` (detector, per-instrument state, run builder, rules, hysteresis); `ClusterDetectionProperties` (`screener.clusters.*`); `ClusterFeedStore`; `ClusterChannel`; cluster DTO records |
| **Changed (wiring)** | `DisruptorShardManager` (create detector per shard), `MarketEventHandler` (one call in the depth branch) |
| **Changed (delivery)** | None beyond the new channel — `FeedBroadcaster` picks up `ClusterChannel` like any other channel; `websocket-feed-api.md` gains the `CLUSTER` type |
| **Changed (ops)** | `monitoring/` — admin view of current clusters for tuning; `PipelineMetrics` — detector time, active clusters |
| **Unchanged** | Streams, sync strategies, recovery, adapters, auth, billing, entitlement, payment |

---

## 6. Open questions

### Business / product

- **Q-B1. Gap tolerance.** Does 700, 701, 703 (702 missing) count as one cluster? How large a gap
  breaks a run?
- **Q-B2. Shape.** Only uniform ladders (similar notional), or also steadily increasing/decreasing
  ladders, or any dense range that stands out?
- **Q-B3. Disappearance.** Do traders want to be told when a cluster is pulled or filled (removal
  shown as a notification), or just see it vanish?
- **Q-B4. Minimum lifetime.** How long must a cluster exist before it is announced? (Trades off
  latency against flicker/spoof noise.)
- **Q-B5. Venue scope.** All venues (Binance spot, Binance futures, MEXC futures) from day one?
- **Q-B6. Overlap with the feed.** A level inside a cluster may itself be tier ≥ 1 and appear in the
  top-5 too — acceptable to show both?

### Detection semantics

- **Q-D1.** Concrete defaults: min level count, min total per liquidity class, similarity tolerance,
  stand-out ratio, max distance from mid. Settled in Phase 0, recalibrated in Phase 3.
- **Q-D2.** Max clusters per side per instrument (K), and how to rank them if more are found.
- **Q-D3.** Update threshold — how much must the total/bounds change to re-emit.

### Technical

- **Q-T1.** Detection throttle interval (500ms vs 1s) — confirm with a measurement of detector cost.
- **Q-T2.** Identity of a cluster across runs (for "same cluster updated" vs "new cluster") — by
  overlap of price ranges with the previous run's clusters?
- **Q-T3.** Cluster `data` payload: field names, compact `[price, qty]` levels.

---

## 7. Coordination with spike alerts

- Spike-alerts ticket 1 renames `DepthEventHandler` to `MarketEventHandler` and turns it into a
  dispatcher (D3/D4 there). The cluster call goes in the **depth** branch. Whichever lands second
  rebases onto the first — plan for it in sprint scheduling.
- Spike-alerts ticket 4 introduces the common envelope and `FeedChannel` (D16/D17 there). Cluster
  delivery (ticket 3 here) is built on it and starts after it lands.
- Clusters and spike alerts share the session's batches but are separate channels and types.

---

## 8. Phasing

### Phase 0 — Definition & payload
- Capture real books: ARB-like cases plus thick books (BTC/ETH futures).
- Prototype the run builder in a test harness against the captures.
- Answer §6 business questions with a trader / the reporting user; settle Q-D1 defaults.
- Agree the cluster `data` payload (Q-T3) → unblocks BE delivery and frontend. The envelope around
  it is fixed by spike-alerts ticket 4.

### Phase 1 — Detector
- `analysis/cluster/`: run builder, rules, hysteresis, throttle, per-instrument state.
- `ClusterDetectionProperties` + YAML; wiring in `DisruptorShardManager` / `MarketEventHandler`.
- Unit tests on synthetic books: uniform ladder, thick book (must not fire), gaps, flicker around
  the threshold, unsynced/empty book.
- Metrics: detector time per run, active cluster count.

### Phase 2 — Delivery (parallel with Phase 1, against a stub detector; after spike-alerts ticket 4)
- `ClusterFeedStore`; `ClusterChannel` emitting `CLUSTER` envelopes and snapshot entries.
- Admin monitoring endpoint listing current clusters.
- `CLUSTER` type added to `.claude/docs/for-frontend/websocket-feed-api.md`.

### Phase 3 — Shadow tuning (release step)
- After tickets 2 and 3 are merged: run in prod with `delivery-enabled=false` (or admin-only) for
  about a week.
- Review what fires via the monitoring endpoint; recalibrate thresholds; then enable delivery once
  the frontend (ticket 4) is released.
- Mandatory: first-cut thresholds will be noisy.

### Phase 4 — Frontend (ticket 4; starts once the payload is agreed, against mocks)
- `CLUSTER` handling, including snapshot entries.
- Alert toast when a cluster appears; list of active clusters.
- Detail view: the ladder rendered as depth bars, with price range, total and distance.

### Later
- Per-user cluster rules, cluster history/persistence, a "cluster tier" for consistent colouring.

---

## 9. Team split & estimate

All tickets belong to Epic 2 (Screener Signals & Visualization), story "Order clusters". Rough
estimates, one developer per ticket.

| # | Epic | Story | Jira ticket | Phase | Owner | Estimate (days) |
|---|---|---|---|---|---|---|
| 1 | 2 — Signals & Visualization | Order clusters | **[BE] Define cluster detection rules on captured books and agree the cluster payload** | 0 | BE-1 | 3–5 |
| 2 | 2 — Signals & Visualization | Order clusters | **[BE] Implement per-shard cluster detector on the depth pipeline (run builder, thresholds, hysteresis, throttling)** | 1 | BE-1 | 4–6 |
| 3 | 2 — Signals & Visualization | Order clusters | **[BE] Deliver clusters over WebSocket through a cluster feed channel, include them in snapshots, add admin monitoring endpoint** | 2 | BE-2 | 2–3 |
| 4 | 2 — Signals & Visualization | Order clusters | **[FE] Handle cluster messages, show cluster alerts and the active-cluster list, render the cluster ladder** | 4 | FE-1 | FE's own breakdown |

- Tickets 1 and 2 and the shadow calibration (Phase 3) go to the same person: whoever owns the
  algorithm should also tune it.
- Ticket 3 starts once ticket 1 has agreed the payload and spike-alerts ticket 4 has landed; it
  does not wait for ticket 2.
- Ticket 4 is one frontend ticket; the frontend breaks it down on its own.

Total: ~9–14 developer-days backend (~2–2.5 weeks elapsed), plus the shadow-tuning week before
delivery is switched on.
