# US3 — Layered Order Clusters: implementation brief

**For**: Claude Code, working on `screener-backend-v2` (backend) and the Screener frontend repo.
**Scope**: two tickets of User Story 3 — `[BE] Implement layered order-cluster detector` and
`[FE] Render cluster ladder and alerts`.
**Status when this was written**: nothing of US3 is implemented. Everything it depends on is.

Read this file first, then the sources it points at. Where this file and the code disagree, the
code wins; say so rather than silently following this file.

---

## 1. The project in short

A cryptocurrency market screener. One Spring Boot 4 / Java 21 JVM keeps a synchronised in-memory
order book for every tracked instrument across Binance, MEXC and Bybit (spot and perpetual
futures), classifies order-book levels as they change, and pushes the results to subscribed users
over its own WebSocket server. Around that core sit accounts, a free trial, subscription billing
and per-user entitlement.

The pipeline is not request-response. Exchange depth streams land in sharded LMAX Disruptor ring
buffers; each instrument is routed to `id & (shardCount - 1)` and its book is owned by exactly one
shard consumer thread. A `FeedBroadcaster` drains the feed stores every 100 ms and sends each
session its messages.

Scale target: ~3,000 instruments, 4,500+ messages per second, on one 4 GB machine, with derived
output ready within 10 ms at p99.

---

## 2. Read these before writing code

In the backend repo:

| File | Why |
|---|---|
| `CLAUDE.md` | Conventions, module map, hot-path rules, build and test commands |
| `.claude/plans/team-plans/order-cluster-plan.md` | The design discussion this brief implements. Sections 3, 4 and 6 are the substance |
| `.claude/docs/market-data-pipeline-architecture.md` | Core/adapter layers, threading and sharding |
| `.claude/docs/for-frontend/websocket-feed-api.md` | The `/ws` contract that gains a `CLUSTER` type |
| `analysis/OrderBookClassifier`, `analysis/SymbolState` | The per-shard, per-instrument pattern to mirror |
| `marketdata/core/ingress/MarketEventHandler` | Where the detector is called (`onDepth`) |
| `marketdata/core/ingress/DisruptorShardManager` | Where a per-shard instance is constructed |
| `marketdata/core/book/OrderBook`, `PriceLevelEntry` | The data the detector walks |
| `feed/trade/TradeChannel`, `marketdata/core/trade/TradeAlertStore` | The closest existing example of a store plus channel |
| `feed/FeedChannel`, `feed/Envelope`, `feed/FeedBroadcaster` | The delivery contract a new channel implements |
| `feed/depth/OrderBookFeedStore` | The upsert/coalesce store pattern clusters copy |

---

## 3. What already exists

- `MarketEventHandler` is already the dispatcher, switching on `StreamKind`; `onDepth` already
  calls `classificationModule.process(...)` after the sync strategy. The cluster call goes
  immediately after that line.
- `FeedChannel`, `Envelope` and `FeedBroadcaster` exist. Adding a signal means adding one channel
  bean; the broadcaster does not change.
- `TradeChannel` already delivers a second message type (`TRADE`) over the same envelope, so the
  pattern for a third type is established.
- Books carry everything the detector needs: all levels within `screener.orderbook.price-filter-threshold`
  (0.1 by default), each `PriceLevelEntry` holding `quantity`, `distance` and `firstSeenMillis`.

Nothing in streams, sync strategies, recovery, adapters, auth, billing or entitlement is touched by
this work.

---

## 4. The feature

One large order is often split across many consecutive price levels. Each individual level is
small, so the classifier rates every one of them tier 0 and the user sees nothing, even though
together they are one large wall.

Real example (Binance spot, ARB/USDT):

```
0.0700 - $120K
0.0701 - $113K
0.0702 - $101K
0.0703 - $100K
...
0.0740 - $102K      ~41 levels, ~$4.1M combined
```

The hard part is detection, not plumbing. Two facts make a naive rule useless:

1. **No tick size is stored.** `Instrument` has no tick size, so "consecutive" can only mean
   adjacent keys in the `TreeMap`, which may have empty ticks between them.
2. **Thick books look like clusters.** Near the mid on BTC, ETH and SOL futures, levels are
   routinely $0.5–2M each and fairly uniform. "40 similar levels summing to $4M" is an ordinary BTC
   book. Without a stand-out test the detector fires constantly on majors and is worthless.

---

## 5. Detection specification

### 5.1 Run building

For each side, walk outward from the best price, bounded by `maxDistance`. Extend the current run
while the next level is *similar* and the price gap is tolerable; close the run otherwise. Judge a
closed run against the minimums below.

- **Similarity**: a level joins the run while its notional is within `similarityTolerance` of the
  run's running median notional (proposed ±40%).
- **Gap tolerance**: no tick size is available, so use the run's own typical step. Break the run
  when the relative price step to the next level exceeds `gapFactor` times the run's median
  relative step (proposed 3.0).
- **Stand-out ratio**: the run's mean level notional must be at least `standoutRatio` times the
  median level notional of that side inside the scanned window (proposed 3.0). This is the test
  that keeps thick books quiet; do not drop it.
- **Minimums**: `minLevels` (proposed 8), `minTotalNotional` per liquidity class, and the run must
  start within `maxDistance` of the mid (proposed 0.05).

Liquidity class comes from `DefaultClassificationRule.isHighLiquidity(symbol)`, which already
splits majors from the rest. Give clusters their own thresholds under `screener.clusters.*`; do
**not** reuse the tier rules, which are tuned for single levels, and do not feed cluster totals
into the existing tier ranking.

### 5.2 Hysteresis, lifetime and re-emission

A cluster sitting near the threshold flickers as quantities jitter, which would produce an alert
storm.

- Enter at `minTotalNotional`, exit below `exitFactor` times it (proposed 0.8).
- Do not emit a newly found cluster until it has been present for `minLifetimeMillis`
  (proposed 3000).
- Re-emit an existing cluster only when its total moved by more than `updateThreshold`
  (proposed 15%) or its price bounds changed.
- Cluster identity across runs is price-range overlap with the previous run's clusters: overlap of
  at least 50% of the shorter range means "the same cluster, updated".

### 5.3 Performance

This runs on the shard consumer thread, in the hot path, for every depth event. `CLAUDE.md`'s
hot-path rules apply in full.

- **Throttle per instrument**: run detection at most every `throttleMillis` (proposed 750), tracked
  in the instrument's own state. Clusters are slow-moving; this costs nothing in usefulness and
  caps the cost regardless of diff rate.
- **Bound the walk** by `maxDistance`, with the same early break the classifier uses.
- **No allocation while scanning.** Track the current run in primitive fields. Allocate only when
  emitting a cluster that actually changed.
- **No `BigDecimal`**, no per-message logging above `debug`, no sharing of the book's `TreeMap`
  across threads.
- Keep the walk separate from the classifier's loop. Fusing them is tempting but couples two
  features; measure first.

A book that is not `SYNCED`, or has an empty side, drops its clusters.

---

## 6. Architecture

| # | Decision |
|---|---|
| D1 | One `OrderClusterDetector` per shard, created by `DisruptorShardManager` exactly like `OrderBookClassifier`, called from `MarketEventHandler.onDepth` right after `classificationModule.process(...)`. Per-instrument state in a map keyed by `feedKey`, touched only by the owning shard thread |
| D2 | A cluster is **state, not an event**. It grows, shrinks and gets pulled, and its disappearance is itself information. Model it like the depth feed: one upserted entry per instrument, removed when empty. The UI derives the "alert" from a cluster it did not hold before; the backend emits no separate alert event |
| D3 | `ClusterFeedStore` mirrors `OrderBookFeedStore`: one entry per `feedKey` holding current `bidClusters[]` / `askClusters[]`, 100 ms coalescing, a snapshot map, at most K clusters per side (proposed 3) |
| D4 | `ClusterChannel implements FeedChannel`, drains the store once per tick and gives every session the same bodies. There are no per-user cluster rules in v1, so there is no `ruleKey` filter. Entitlement is already enforced at `@OnOpen` |
| D5 | The payload carries the full ladder. A cluster is rare, so ~40 levels (~2 KB) is cheap. Levels are copied **on the shard thread** at emit time, because the `TreeMap` cannot be read from anywhere else |
| D6 | Two flags: `screener.clusters.enabled` (run the detector) and `screener.clusters.delivery-enabled` (push to clients). Detection with delivery off is the shadow-tuning mode |

---

## 7. Wire contract

An upsert carries the instrument's current clusters:

```json
{ "type": "CLUSTER", "exchange": "BINANCE", "market": "SPOT", "symbol": "ARBUSDT",
  "data": {
    "bidClusters": [
      { "priceFrom": 0.0700, "priceTo": 0.0740, "levelCount": 41,
        "totalNotional": 4120000, "totalQuantity": 57200000,
        "distance": 0.031, "firstSeenMillis": 1759667423000,
        "levels": [[0.0700, 1714285], [0.0701, 1611983]] }
    ],
    "askClusters": []
  } }
```

Removal is the same envelope with `"data": null`.

- `levels` are compact `[price, quantity]` pairs, ordered from `priceFrom` to `priceTo`.
- `distance` is the fractional distance of the nearest edge from the mid price (0.031 = 3.1%),
  matching the convention already used for levels.
- `SNAPSHOT` entries are exactly these envelopes, one per instrument that currently has clusters.
  A reconnecting client therefore sees exactly the clusters that exist now, and recovery needs no
  new mechanism.
- Clusters are **state**: on a snapshot the client replaces its cluster state, unlike `TRADE`
  entries, which are history and are merged.

---

## 8. Backend tasks

### BE-1 — Detector (`analysis/cluster/`)

New: the detector, per-instrument state, run builder, rules, hysteresis, and
`ClusterDetectionProperties` bound to `screener.clusters.*`.
Changed: `DisruptorShardManager` (construct one per shard), `MarketEventHandler` (one call in the
depth arm).

Done when:

- A synthetic uniform ladder (the ARB case) is detected with correct bounds, level count and total.
- A synthetic thick BTC-like book, uniform and large, produces **no** cluster. This is the test that
  matters most.
- A run with a wide price gap in the middle splits into two runs, and neither reaches `minLevels`
  if it should not.
- A cluster oscillating around the threshold emits once, not repeatedly (hysteresis plus the
  update threshold).
- An unsynced book, an empty side and a book below `minLevels` all produce nothing and allocate
  nothing.
- Detection respects the throttle: a burst of 100 diffs inside one throttle window triggers at most
  one detection run.
- `PipelineMetrics` reports detector time per run and the active cluster count.

### BE-2 — Delivery

New: `ClusterFeedStore`, `ClusterChannel`, the cluster DTO records.
Changed: `.claude/docs/for-frontend/websocket-feed-api.md` gains the `CLUSTER` type (envelope,
`data` shape, snapshot semantics, the note that it is state and not history).
Also: an admin monitoring endpoint listing current clusters, next to
`/api/monitoring/trade-alerts`, for threshold tuning.

Done when:

- A connected session receives a `CLUSTER` upsert within one broadcaster tick of detection.
- The cluster's disappearance arrives as `"data": null`.
- A reconnecting session's `SNAPSHOT` contains one `CLUSTER` entry per instrument that has
  clusters, and none for instruments that do not.
- With `delivery-enabled: false` the detector still runs and the channel emits nothing.

### Configuration to add

```yaml
screener:
  clusters:
    enabled: true
    delivery-enabled: false        # shadow mode until thresholds are tuned
    throttle-millis: 750
    max-distance: 0.05
    min-levels: 8
    similarity-tolerance: 0.4      # each level within ±40% of the run's median notional
    gap-factor: 3.0                # break the run past 3× its median relative step
    standout-ratio: 3.0            # run mean ≥ 3× the side's median level notional
    exit-factor: 0.8               # hysteresis: leave below 0.8 × enter threshold
    min-lifetime-millis: 3000
    update-threshold: 0.15         # re-emit when the total moves more than 15%
    max-clusters-per-side: 3
    min-total-notional:
      high-liquidity: 2000000      # BTC, ETH and the other majors
      default: 300000
```

Every number above is a **proposal**, not a measured value. They are first-cut thresholds and will
be noisy; the plan's Phase 3 is a shadow-tuning week with `delivery-enabled: false` before the
feature is switched on.

---

## 9. Frontend tasks

This work lives in the frontend repository (React, TypeScript, Vite, TailwindCSS, TanStack Query,
Zustand, react-i18next). It can start against mocked messages before the backend lands, because the
contract in section 7 is fixed.

### FE-1 — Handle the message type

- Add `CLUSTER` to the WebSocket message handler next to `DEPTH` and `TRADE`.
- Treat it as **state**: an upsert replaces that instrument's clusters, `"data": null` removes
  them, and a `SNAPSHOT` replaces the whole cluster map. Do not merge it like trade history.
- Keep clusters in their own store slice, keyed the same way depth is keyed
  (`exchange + market + symbol`).
- Ignore unknown fields rather than failing, as the existing handler does for other types.

### FE-2 — Alert and active list

- When an instrument gains a cluster it did not have before, raise an alert in the same style as
  existing spike and trade alerts. The backend sends no alert event, so this transition is derived
  on the client.
- Do not alert on `SNAPSHOT` entries: a reconnect must not replay every existing cluster as new.
  This mirrors how `TRADE` snapshot entries are merged without notifying.
- Show a list of currently active clusters, with instrument, side, price range, total notional and
  distance from mid, sortable and filterable by exchange in the same way the screener already is.
- When a cluster disappears, remove it from the list quietly. Whether a removal deserves its own
  notification is an open product question; do not add one without asking.

### FE-3 — Ladder detail view

- Opening a cluster shows the ladder: one row per level, rendered as horizontal depth bars scaled
  to the largest level in the cluster, in price order from `priceFrom` to `priceTo`.
- Header: instrument, side, price range, level count, total notional, total quantity, distance from
  mid, and how long the cluster has existed (derived from `firstSeenMillis`).
- Bid clusters and ask clusters must be visually distinguishable and consistent with the colours
  the order-book view already uses.
- The view updates in place as upserts arrive, and shows a clear "this cluster is gone" state
  rather than freezing or closing abruptly when it is removed.
- It must stay responsive under a stream of updates: no main-thread task longer than 50 ms
  (NFR-19), so memoise and avoid re-rendering the whole ladder on every tick.

Done when: a mocked sequence of upsert, update, remove and reconnect-with-snapshot produces the
expected UI in each case, and no alert fires on the snapshot.

---

## 10. Rules for this codebase

- **Hot path**: `marketdata/core/*`, the adapters, `feed/` and the `analysis/` classifier process
  the full firehose. No allocation in the loops, no `BigDecimal`, no per-message logging above
  `debug`, books are single-thread-owned. Auth, billing and REST code are ordinary Spring MVC and
  must not inherit that austerity.
- **Config**: every tunable goes to `application.yml` under `screener.*` and binds to a
  `@ConfigurationProperties` record in `config/`. No literals in services.
- **No database work** is needed for this feature: clusters are in-memory state. Do not add a
  Flyway migration.
- **Tests**: `./mvnw test -Dtest=<Class>` for a clean result. `./mvnw test` always ends with one
  known failure, `ScreenerBackendApplicationTests.contextLoads`, caused by a missing `DB_URL` in the
  test environment. It is unrelated to this work; ignore it and treat any other failure as real.
- **Docs**: update `.claude/docs/for-frontend/websocket-feed-api.md` as part of BE-2. Plans live in
  `.claude/plans/`.

---

## 11. Out of scope

Per-user cluster rules, cluster history or persistence, a "cluster tier" for consistent colouring,
and any change to the existing tier ranking or the depth feed contract. A level inside a cluster
may also appear in the depth top-5 on its own merit; that overlap is accepted in v1.

---

## 12. Questions to confirm with the team

Proposed answers are in section 5 so the work is not blocked, but these are product decisions and
should be checked with the client (an active trader) rather than settled in code:

1. Does a missing level break a run, and how large a gap is too large?
2. Only uniform ladders, or also steadily increasing or decreasing ones?
3. Should a cluster being pulled or filled be announced, or just vanish?
4. How long must a cluster exist before it is announced? This trades latency against flicker and
   against being fooled by spoofing.
5. All venues from day one, or Binance first?
6. Concrete defaults for the thresholds in section 8, to be recalibrated after the shadow week.
