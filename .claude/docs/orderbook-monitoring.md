# Orderbook Fleet Monitoring

How the running screener observes its own orderbook fleet: what is measured on the hot path, how it
escapes the Disruptor consumer threads safely, what is derived from it, what the three admin
endpoints return, and how the on-disk CSV capture works — **including exactly how to read those CSV
files**, which is the part with no other documentation anywhere.

This document describes the code as implemented. It supersedes
`.claude/plans/orderbook-statistics-endpoint.md` wherever the two disagree (§10 lists the
divergences). The frontend contract at `.claude/docs/for-frontend/orderbook-stats-api.md` was written
against the first iteration and is **stale in several places** — §10.3 lists them.

---

## 1. Why this exists at all

The endpoint this replaced, `GET /api/monitoring/orderbook?symbol=…&market=…`, dumped every price
level of one book by doing `new TreeMap<>(bids)` on a Tomcat thread while the shard's Disruptor
consumer was mutating that exact map. Per `CLAUDE.md` the books are single-thread-owned; a concurrent
copy of a `TreeMap` under mutation can yield a corrupted copy, a `ConcurrentModificationException`,
or an infinite loop. That endpoint, `OrderBook.snapshotBids()` and `OrderBook.snapshotAsks()` are all
**deleted**.

What replaced it answers aggregate questions instead: how big are books, how much do they vary, how
many are synced, how fast are they updating, which are broken, and how is work distributed across
shards. Nothing reads a live orderbook cross-thread any more.

**"Size" always means `bids.size() + asks.size()`** — never one side. This holds throughout the
feature, the endpoints and the CSVs.

---

## 2. What is measured, and where

### 2.1 The hot-path footprint: two counters

`binance/orderbook/OrderBook.java` gained exactly two plain, non-volatile, consumer-thread-owned
fields:

```java
private long diffsApplied;   // successful applyLiveDiff() calls
private int  resyncs;        // resync() calls
```

- `diffsApplied++` sits at the end of `applyLiveDiff`, **after** sequence validation and
  `computeDistance()` — so it counts only diffs that were actually applied, not diffs that arrived.
  Note it also counts buffered diffs replayed by `applySnapshot` (step 5 calls `applyLiveDiff` in a
  loop), so a freshly synced book starts with a small non-zero count.
- `resyncs++` sits inside `resync()`. Every failure path funnels through it: sequence gap, `pu`
  discontinuity, parse error, empty diff buffer after a snapshot, no valid sync point, and — new in
  this change — **diff-buffer overflow**, which previously inlined the same body and so escaped the
  counter.

That is the entire cost of the monitoring feature on the hot path: one load-add-store into an
L1-resident line on a thread-confined object per applied diff, plus one per resync. **No allocation,
no volatile write, no clock read per message.** `applyLevelsDirectly` — the per-price-level loop — is
untouched.

Deliberately *not* instrumented (do not re-add without reading this):

| Rejected | Why |
|---|---|
| `levelChanges`, one increment per price level | Costs little but answers nothing on its own: `levelChangesPerSecond ≈ updatesPerSecond × mean levels per diff`. It would propagate into totals, the per-book row, a sort key and the collector's EWMA. `levelVisitsPerSecond` (§3.3) captures the same intent for free. |
| `lastUpdateMillis` / hoisting the clock read out of `applyLevelsDirectly` | Not free: `System.currentTimeMillis()` currently fires only when a level is *newly created*. A diff that only resizes or removes existing levels pays zero clock reads today and would pay one per diff after the hoist. Staleness is derived instead (§3.2). |
| `syncedAtMillis` / `syncedForMs` | One more field to publish and keep coherent, answering nothing that was asked. |

### 2.2 Publication: one volatile store per book per second

A `long` written by one thread and read by another can be read torn (JLS 17.7), and four independently
read fields would give a reader an incoherent mixture of instants. Making `diffsApplied` volatile
would put a StoreLoad fence on every diff — rejected.

Instead the consumer publishes an immutable record on a slow cadence:

```java
// OrderBook — consumer thread only
private volatile BookStats stats;

public void publishStats(long now) {
    stats = new BookStats(symbol, market, state, bids.size(), asks.size(), diffsApplied, resyncs, now);
}
public BookStats getStats() { return stats; }   // safe from any thread
```

`binance/orderbook/BookStats` is the **only** legal cross-thread view of an orderbook:

| Field | Meaning |
|---|---|
| `symbol`, `market` | identity |
| `state` | `PENDING` / `SNAPSHOT_REQUESTED` / `SYNCED` at publish time |
| `bids`, `asks` | level counts; `size()` = their sum |
| `diffsApplied` | cumulative since JVM start |
| `resyncs` | cumulative since JVM start |
| `publishedAtMillis` | wall clock at publish; `0` = never published (`neverPublished()`) |

The single volatile store of an immutable record is a release; the reader's volatile load is the
matching acquire, so a reader always sees **one coherent instant**.

A book is constructed with a seed `BookStats(… PENDING, 0, 0, 0, 0, publishedAtMillis = 0)`, so
`getStats()` never returns null.

### 2.3 The publish trigger: a 256-event counter mask, not `endOfBatch`

`binance/disruptor/DepthEventHandler`:

```java
private static final int CLOCK_CHECK_MASK = 0xFF;

private void maybePublishStats() {
    if ((++eventsSeen & CLOCK_CHECK_MASK) != 0) return;
    long now = System.currentTimeMillis();
    if (now - lastStatsPublishMillis >= publishIntervalMs) {
        lastStatsPublishMillis = now;
        statsPublisher.publishShard(shardIndex, now);
    }
}
```

`eventsSeen` and `lastStatsPublishMillis` are plain fields confined to that handler's consumer thread.
The handler is constructed with the publisher and `publish-interval-ms` by `DisruptorShardManager`
(which now also takes `MonitoringProperties`; note its constructor arity changed from 4 to 6, which is
why `UserFeedRegistryTest.FakeShardManager` was updated).

**Why not `endOfBatch`:** Disruptor sets `endOfBatch` on the last event available at poll time. When
the consumer keeps up with its producers — the steady state the design targets — each poll returns a
single event and `endOfBatch` is true on *every* message. A clock read behind that gate degrades to
one `currentTimeMillis()` per message precisely when throughput is highest. The mask amortises
unconditionally: one clock read per 256 events regardless of batching, with the publish cadence still
time-based.

`CLOCK_CHECK_MASK` is a constant, not a `screener.*` tunable — it only has to be small enough that a
healthy shard clears it several times per publish interval. A shard too quiet to clear it is an
incident, and is reported as one via `shardsStale`.

### 2.4 The shard walk

`monitoring/OrderBookStatsPublisher implements ShardStatsPublisher` (the interface lives in
`binance/orderbook/` so `binance/` never imports `monitoring/` — the same inversion used for
`OrderBookProcessor` → `SnapshotFetchQueue`):

```java
public void publishShard(int shardIndex, long now) {
    for (OrderBook book : store.books()) {
        if (Math.abs(book.getSymbol().hashCode()) % shardCount == shardIndex) {
            book.publishStats(now);
        }
    }
}
```

Two things matter here:

1. **The ownership test must stay identical to `DisruptorShardManager.getRingBuffer`.** `publishStats`
   reads live `TreeMap.size()`, so visiting a book owned by another shard is a data race. If the
   mapping ever changes, change it here in the same commit.
2. **The shard walks every one of its books, rather than each book publishing when it updates.** A
   book that has stopped receiving diffs is precisely the one worth seeing; self-publishing would
   freeze a dead book's stats at its last healthy value and hide the outage.

`OrderBookStore.books()` was added for this: an unmodifiable, weakly-consistent view of
`ConcurrentHashMap.values()`. Safe to iterate from any thread; the `OrderBook` instances it yields are
not — off the owning consumer thread the only legal call is `getStats()`.

Cost at the 1 s default: ~1 000 small allocations and ~1 000 volatile stores per second plus one map
walk per shard per second, against a 300 k msg/s firehose. This is a deliberate, bounded, documented
exception to "no allocation on the consumer thread"; the justification is in the `publishStats`
Javadoc so a future reader does not "fix" it.

**Residual limitation, surfaced not hidden:** publication is driven by the consumer thread, so a shard
receiving (almost) no events never runs the gate and its books' stats freeze. Responses therefore
carry `sampleAgeMs` and `shardsStale` (shards last published longer ago than 3× the publish interval)
rather than silently serving frozen numbers.

---

## 3. What is derived: `OrderBookStatsCollector`

A `@Scheduled(fixedRateString = "${screener.monitoring.orderbook.sample-interval-ms}")` bean, default
5 s. Ordinary Spring code — hot-path rules do not apply. It never touches an orderbook's maps; it
reads `getStats()` only.

Its mutable working state (`previous`, the history deque, the resync ring) is confined to the
scheduler thread. Readers see only `snapshot()`, one `volatile CollectorSnapshot` replaced wholesale
each tick. No locks on the read path.

### 3.1 `updatesPerSecond` — EWMA over counter deltas

Per book, against its own previous observation:

```
dt      = max(1, now - prev.atMillis)
delta   = stats.diffsApplied - prev.diffsApplied      (clamped at 0; defensive)
instant = delta * 1000 / dt
alpha   = 1 - exp(-ln2 * dt / rate-half-life-ms)      (default half-life 30 s)
rate    = alpha * instant + (1 - alpha) * prev.rate
```

A book seen for the first time has no baseline: `rate = 0`, `idleMs = 0`. It takes a few ticks for the
EWMA to converge — **the first ~30–60 s of any capture understates rates.**

### 3.2 `idleMs` — staleness without a per-diff timestamp

```
idleMs = (delta > 0) ? 0 : prev.idleMs + dt
```

Time since `diffsApplied` was last **observed** to change. Its resolution is `sample-interval-ms`
(5 s), not milliseconds — which is exactly why the field is called `idleMs` and not
`lastUpdateAgeMs`. Do not rename it back without first restoring a per-diff timestamp.

`staleBooks` counts SYNCED books whose `idleMs` exceeds `stale-threshold-ms`. That threshold was
raised from the planned 30 s to **120 s** after observing real traffic: the quietest healthy symbols
legitimately go tens of seconds without a diff, so a low threshold measures illiquidity, not faults.
Calibrate it from the observed distribution (`/orderbook/books?sort=IDLE&order=DESC`).

### 3.3 `levelVisitsPerSecond` — the metric added during implementation

```
fleetLevelVisitsPerSecond = Σ over books ( updatesPerSecond × size )
```

This is **the pipeline's real unit of work** and the most useful number the feature produces. Every
applied diff calls `computeDistance()`, which walks that book's *entire* level set to re-derive
mid-price, sweep out-of-band levels and update `distance`. Cost therefore scales with
`size × rate`, not with message count — message count understates the load of a liquid symbol by
orders of magnitude. It is the number to extrapolate when sizing for more symbols or more exchanges.

It is reported fleet-wide (`totals.levelVisitsPerSecond`), per shard (`ShardLoad`), and per history
point.

### 3.4 `resyncsLastHour`

A ring of `{atMillis, cumulativeFleetResyncs}` pairs, one per tick, trimmed to one hour (and to
`history-size` entries). The answer is `current - oldestRetained`. While the ring is still filling
this is "since the oldest retained sample", i.e. **an underestimate for the first hour after
startup**.

### 3.5 The fleet trend ring

Appended on its own, slower cadence — `history-interval-ms` (30 s), **not** `sample-interval-ms`
(5 s). Ticks that do not record history skip the sort and list copy entirely, which at the defaults is
five ticks out of every six. `publishedHistory` is only rebuilt when a point is actually appended.

One `HistoryPoint`:

| Field | Meaning |
|---|---|
| `at` | sample time (`OffsetDateTime`, UTC) |
| `books` | total books |
| `synced` | books in SYNCED |
| `totalLevels` | Σ size over **all** books |
| `meanSize`, `medianSize` | over **SYNCED books only**; `null` until at least one book has synced |
| `updatesPerSecond` | fleet Σ of per-book EWMA rates |
| `levelVisitsPerSecond` | Σ `size × rate` (§3.3) |
| `staleBooks` | stale SYNCED books at that instant |
| `usedHeapBytes` | `Runtime.totalMemory() - freeMemory()` at sample time |

`usedHeapBytes` sawtooths with GC. **Read the lower envelope across many points** as the live-set
estimate; regressing that against `totalLevels` is what turns "how many levels" into "how many
gigabytes at N exchanges".

Ring capacity is `history-size` points; retention in wall-clock time is
`historySize × historyIntervalMs` = 2 880 × 30 s = **24 hours** (~550 KB of heap).

---

## 4. Request-time computation

`OrderBookStatsService` runs on the Tomcat thread: collect ~1 000 sizes into a `double[]`, sort once,
a few linear passes. Sub-millisecond. No caching, no `@Transactional`, no DB. Everything it reads is
already-published and immutable, so it cannot race with the consumers.

### 4.1 `DescriptiveStats` — the pinned definitions

Pure, dependency-free, no Spring, no state. Every definition is pinned to a named convention so the
numbers can be reproduced independently:

| Field | Definition |
|---|---|
| `mean` | `Σx / n` |
| percentiles (`p10 p25 p50 p75 p90 p95`, `median`) | **R-7 / linear interpolation** — numpy `percentile` and Excel `PERCENTILE.INC` default: `h = (n-1)·p`, `x[⌊h⌋] + (h-⌊h⌋)·(x[⌊h⌋+1] - x[⌊h⌋])`. `median` is exactly `p50` |
| `variance`, `stdDev` | **Sample** (Bessel, `n-1`); `null` when `n < 2` |
| `iqr` | `p75 - p25` |
| `mad` | `median(|xᵢ - median|)`. Outlier-proof spread: if `mad × 1.4826 ≪ stdDev`, outliers are inflating σ |
| `skewness` | Adjusted Fisher–Pearson G1: `n/((n-1)(n-2)) · Σ((xᵢ-x̄)/s)³` = `scipy.stats.skew(bias=False)`. `null` when `n < 3` **or** `s == 0` |
| `shape` | `|g1| < 0.5` → `SYMMETRIC`; `< 1` → `MODERATE_{LEFT,RIGHT}_SKEW`; `≥ 1` → `HIGH_{LEFT,RIGHT}_SKEW`. Negative G1 = long thin tail of *small* values |
| Tukey fences | outlier outside `[p25 - 1.5·iqr, p75 + 1.5·iqr]`; extreme outside `[p25 - 3·iqr, p75 + 3·iqr]` |
| Freedman–Diaconis | `width = 2·iqr·n^(-1/3)`, `bins = ceil(range/width)` clamped to `[5, 100]`; Sturges `ceil(log2 n) + 1` when `iqr == 0` |

Every value is rounded to 4 decimals (`DescriptiveStats.round`). Undefined statistics are `null`,
never `0` or `NaN` — an undefined statistic must not be mistakable for a computed one.

Deliberately not computed: `coefficientOfVariation`, `madScaled`, `kurtosisExcess`,
`bimodalityCoefficient`, gini/level-concentration, and the `p1`/`p5`/`p99` tails.

### 4.2 The histogram, and why it has a bounded domain

**This is the largest deviation from the plan and it exists because the first version produced an
unreadable chart.** Book size is heavily right-skewed: in the reference run the IQR was ~286 levels
while the range reached ~13 900 — a ratio of ~49. FD derives bin *width* from the IQR (the body) but
bin *count* from the range (the tail), so covering the full range at body resolution demanded ~234
bins. Clamping to `[5, 100]` did not reduce the demand; it inflated the width until the body collapsed
into two buckets. The result was 100 buckets of which ~80 were empty and the first two held 57 % of
the fleet.

The fix bounds the **domain** rather than the bin width. `HistogramDomain`:

| Value | Upper bound | Use |
|---|---|---|
| `P99` | 99th percentile — **the default** | Keeps virtually the whole distribution, cuts the resolution-destroying tail, lets FD stay under the cap on its own |
| `FENCE` | `p75 + 1.5·iqr` | Tighter view of the body; sends every statistical outlier to overflow (~8 % of the sample in the reference run) |
| `FULL` | sample maximum | Historical behaviour; correct only for a distribution that is not heavy-tailed |

The bound is clamped into `[sampleMin, sampleMax]`, so it never buckets empty space to the right and
never falls below the minimum.

Observations above the bound are **not dropped and not crammed into the last bucket** — they are
counted in `overflowCount`, with the true maximum reported as `sampleMax`. Therefore:

```
Σ buckets[i].count + overflowCount == n
```

Buckets are half-open `[from, to)` except the last, which is closed so `v == upper` lands inside it.
`method` reports how `bins` was chosen: `EXPLICIT`, `FREEDMAN_DIACONIS`, `STURGES`, `SINGLE_VALUE`
(degenerate: zero range, one bucket, `binWidth 0`) or `NONE` (empty sample: no buckets, `lower`,
`upper`, `sampleMax` all `null`).

When plotting, draw `overflowCount` as a single annotated off-scale bar beyond `upper`. Do not
silently discard it.

### 4.3 Outliers

Tukey fences over the size sample, with named offenders: `low` sorted smallest-first, `high` sorted
largest-first, each capped at `min(outliers, max-outliers-listed)`. `count = lowCount + highCount`;
`fraction = count / n`; `extremeCount` uses the 3·IQR fences. All fence fields are `null` for an empty
sample.

### 4.4 Shard load

`ShardLoad` per shard, using the same `Math.abs(symbol.hashCode()) % shardCount` mapping:
`books`, `totalLevels`, `updatesPerSecond`, `levelVisitsPerSecond`, `largestBook` (`"SYMBOL/MARKET"`,
`null` if the shard has none), `largestBookSize`, `sampleAgeMs`.

The hash distributes **book count** almost perfectly and says nothing about **work**. In the reference
sample the largest book held 52× the median book's levels, so which shard it lands on is decided by a
hash and moves the balance materially. **Compare `levelVisitsPerSecond` across shards** — not `books`,
not `updatesPerSecond`.

`byShard` honours whatever `market` filter the caller passed, exactly like `totals`. A shard's thread
serves both markets, so for a true balance picture **omit `market`**.

---

## 5. The endpoints

All three are ADMIN-only — `/api/monitoring/**` is gated by `hasRole("ADMIN")` in `SecurityConfig`.
Any other authenticated user gets `403`. Unparseable enum or numeric params yield `400`; out-of-range
numbers are **clamped**, not rejected.

### 5.1 `GET /api/monitoring/orderbook` — fleet statistics

| Param | Type | Default | Meaning |
|---|---|---|---|
| `market` | `SPOT`\|`FUTURES` | *(none)* | Restricts the whole population. Omit to read `byShard` as a real load figure |
| `state` | `SYNCED`\|`PENDING`\|`SNAPSHOT_REQUESTED`\|`ALL` | `SYNCED` | Which books enter the **size sample**. State *counts* always cover every book |
| `bins` | int | `0` | Histogram bin count; `0` = Freedman–Diaconis. Clamped `[5, 100]` |
| `outliers` | int | `10` | Max **named** outliers per tail (`0` = counts only), capped by `max-outliers-listed` |
| `domain` | `P99`\|`FENCE`\|`FULL` | `P99` | Histogram domain (§4.2) |

Response (`OrderBookStatsResponse`):

```jsonc
{
  "generatedAt": "2026-08-02T09:15:22.104Z",
  "sampleAgeMs": 340,          // age of the NEWEST shard publication
  "shardsStale": [],           // shards frozen for > 3x publish-interval-ms

  "totals": {
    "books": 1042,
    "byState":  { "PENDING": 29, "SNAPSHOT_REQUESTED": 12, "SYNCED": 1001 },
    "byMarket": { "SPOT": 402, "FUTURES": 640 },
    "syncedRatio": 0.9607,
    "totalLevels": 1873420,
    "updatesPerSecond": 1230.5,
    "levelVisitsPerSecond": 2841003.2,   // the real work metric — see §3.3
    "staleBooks": 3,
    "emptyBooks": 1,                     // SYNCED holding zero levels
    "resyncsLastHour": 47,
    "usedHeapBytes": 3221225472,
    "maxHeapBytes": 8589934592
  },

  "overall":  { /* StatBlock over size */ },
  "byMarket": { "SPOT": { /* StatBlock */ }, "FUTURES": { /* StatBlock */ } },

  "histogram": {
    "bins": 22, "binWidth": 148.0,
    "lower": 12, "upper": 3268, "sampleMax": 13904,
    "domain": "P99", "method": "FREEDMAN_DIACONIS",
    "buckets": [ { "from": 12, "to": 160, "count": 5 } ],
    "overflowCount": 10
  },

  "sizeOutliers": { "lowerFence": …, "upperFence": …, "extremeLowerFence": …,
                    "extremeUpperFence": …, "count": 17, "lowCount": 14, "highCount": 3,
                    "fraction": 0.017, "extremeCount": 3,
                    "low":  [ { "symbol": "…", "market": "SPOT",    "size": 12,   "state": "SYNCED" } ],
                    "high": [ { "symbol": "…", "market": "FUTURES", "size": 3268, "state": "SYNCED" } ] },

  "updateRate":        { /* StatBlock over per-book updatesPerSecond, pooled */ },
  "updateRateByMarket": { "SPOT": { /* StatBlock */ }, "FUTURES": { /* StatBlock */ } },

  "byShard": [ { "shard": 0, "books": 521, "totalLevels": 940112,
                 "updatesPerSecond": 615.2, "levelVisitsPerSecond": 1402771.0,
                 "largestBook": "BTCUSDT/FUTURES", "largestBookSize": 13904,
                 "sampleAgeMs": 310 } ]
}
```

**Three interpretation caveats, documented on the response record itself:**

1. **`overall` pools two populations.** Spot (1 s) and futures (500 ms) books differ in cadence and
   depth; their medians differ by roughly 3×. `byMarket` is returned unconditionally and is the block
   to trust. Splitting does *not* remove the skew — each market is separately, heavily right-skewed,
   because liquidity across symbols is itself heavy-tailed.
2. **Size is bounded by the price filter, not by snapshot depth.** The seeding REST snapshot carries
   at most 1 000 levels per side, but live diffs insert new price levels indefinitely and the only
   thing that removes one is the mid-price sweep discarding it outside
   ±`screener.orderbook.price-filter-threshold` (0.1 = ±10 %). Books therefore routinely exceed 2 000
   levels (largest observed ~13 900) and grow until the band saturates. **There is no upper censoring
   and no manufactured left skew** — this corrects the earlier claim still present in the frontend doc.
3. **`mean` and `stdDev` are close to useless here.** With skewness above 10, σ is inflated several-fold
   by a handful of books; `stdDev` vs `mad × 1.4826` typically differs by 3–5×. Read `median` and
   `iqr`; treat `mean ± stdDev` as meaningless.

Prefer `updateRateByMarket` to `updateRate`: spot and futures have different hard ceilings (1/s and
2/s), so the pooled block is a mixture of two differently-truncated populations and its `shape` in
particular means nothing.

### 5.2 `GET /api/monitoring/orderbook/history` — the fleet trend ring

**A separate endpoint, which the plan did not have.** At its 2 880-point cap the ring is ~600 KB of
JSON — far more than every statistic in the fleet response combined — while changing only once every
30 s. A dashboard polling current fleet health should not carry a trend it redraws once a minute.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `minutes` | int | `60` | Trailing window; `0` or less returns the whole retained ring |

```jsonc
{
  "generatedAt": "2026-08-02T09:15:22.104Z",
  "pointIntervalMs": 30000,     // history-interval-ms — NOT sample-interval-ms
  "windowMs": 86400000,         // historySize × historyIntervalMs
  "available": 2880,
  "returned": 120,
  "points": [ { "at": "…", "books": 1040, "synced": 998, "totalLevels": 1861002,
                "meanSize": 1864.7, "medianSize": 1877.0,
                "updatesPerSecond": 1198.1, "levelVisitsPerSecond": 2741002.5,
                "staleBooks": 2, "usedHeapBytes": 3011225472 } ]
}
```

**Always fleet-wide.** The collector accumulates it on a schedule with no request in scope, so it
honours no `market` or `state` filter. Do not overlay it on a filtered stat block and expect the two
to agree.

`meanSize`/`medianSize` are over SYNCED books only and are `null` for points where nothing had synced
yet (the first ticks after a restart). Render `null` as a gap, not as `0`.

### 5.3 `GET /api/monitoring/orderbook/books` — the per-book table

| Param | Type | Default | Meaning |
|---|---|---|---|
| `market` | `SPOT`\|`FUTURES` | *(none)* | Restrict to one market |
| `state` | as above | `SYNCED` | Restrict by sync state |
| `symbol` | string | *(none)* | Exact filter, case-insensitive |
| `sort` | `SIZE`\|`UPDATE_RATE`\|`IDLE`\|`RESYNCS`\|`SYMBOL` | `SIZE` | Sort key |
| `order` | `ASC`\|`DESC` | `DESC` | Direction |
| `limit` | int | `200` | Clamped to `[1, max-books-returned]` (2000) |

Ties break on symbol then market, so paging is deterministic. `totalMatching` is the count before
`limit`.

Row fields: `symbol`, `market`, `state`, `size`, `bids`, `asks`, `updates` (cumulative
`diffsApplied`), `updatesPerSecond` (EWMA), `idleMs` (5 s resolution), `resyncs` (cumulative).

The payload is bounded by book *count*, not level count: `limit=2000&sort=SYMBOL` is the whole fleet,
~1 000 rows ≈ 200 KB.

| Question | Query |
|---|---|
| Which books have gone quiet? | `?sort=IDLE&order=DESC&limit=20` |
| Which keep desyncing? | `?state=ALL&sort=RESYNCS&order=DESC&limit=20` |
| Which are stuck unsynced? | `?state=PENDING&sort=SYMBOL&order=ASC&limit=2000` |
| Everything, for a client-side plot | `?limit=2000&sort=SYMBOL&order=ASC` |

---

## 6. Configuration

`config/MonitoringProperties` binds `screener.monitoring`. Defaults from `application.yml`:

```yaml
screener:
  monitoring:
    orderbook:
      publish-interval-ms: 1000      # consumer republish cadence (hot-path gate)
      sample-interval-ms: 5000       # collector tick; also the resolution of idleMs
      history-interval-ms: 30000     # trend-ring append cadence (decoupled from the tick)
      rate-half-life-ms: 30000       # EWMA half-life for per-book updatesPerSecond
      history-size: 2880             # ring length → 2880 × 30 s = 24 h, ~550 KB heap
      stale-threshold-ms: 120000     # SYNCED + idle longer than this ⇒ "stale"
      default-histogram-bins: 0      # 0 = Freedman–Diaconis
      max-outliers-listed: 10
      max-books-returned: 2000
      recorder:
        enabled: ${SCREENER_RECORDER_ENABLED:false}
        books-interval-ms: 60000
        fleet-interval-ms: 30000
        directory: ${SCREENER_RECORDER_DIR:/var/lib/screener/monitoring-data}
        retention-days: 3
```

`MonitoringProperties` is registered in `WebClientConfig`'s `@EnableConfigurationProperties` list —
add new property records there, as the project already does.

One non-obvious companion change:

```yaml
spring:
  task:
    scheduling:
      pool:
        size: 4
```

Spring's default is a **single** thread shared by every `@Scheduled` method — the stats collector, the
ticker refresh, payment reconciliation, `OrderBookStore.logSyncCount` and the CSV recorder. One slow
disk write would then delay payment reconciliation and the collector's sampling. Do not reduce this
back to 1 while the recorder exists.

---

## 7. The CSV recorder — capture

`monitoring/OrderBookCsvRecorder`, gated by
`@ConditionalOnProperty(prefix = "screener.monitoring.orderbook.recorder", name = "enabled", havingValue = "true")`
— when disabled the bean is not created at all.

### 7.1 Why in-process rather than an external poller

Everything it writes is available over the endpoints, so a cron'd `curl` could do the same job. It
lives in the server because for an unattended multi-day capture that is simply better: no admin
credentials sitting in a cron environment, no access-token expiry to work around, no second process to
keep alive, and no chance of the capture and the server disagreeing about what "now" means. The
trade-off accepted in exchange is that the recorder dies with the JVM — which is fine, because **a gap
in the CSV is itself a recorded fact about the night**.

### 7.2 Enabling it

```bash
SCREENER_RECORDER_ENABLED=true
SCREENER_RECORDER_DIR=/var/lib/screener/monitoring-data     # optional; this is the default
```

The directory is created if absent (`Files.createDirectories` on every append). On the deployment
target that path must be writable by the service user (`www-data` per `deploy-backend.ps1`). A local
run can point it at `./monitoring-data`, which `.gitignore` already excludes.

On first write of each file the recorder logs `Recording orderbook CSV to <absolute path>` at INFO —
that line is how you confirm the capture is live and where it landed.

### 7.3 Schedules and failure behaviour

| Task | Fixed rate | Initial delay | Writes |
|---|---|---|---|
| `recordBooks()` | `books-interval-ms` (60 s) | same as the rate | `books-<date>.csv` |
| `recordFleet()` | `fleet-interval-ms` (30 s) | same as the rate | `fleet-<date>.csv` **and** `shards-<date>.csv`, then the retention sweep |

Both wrap everything in `try/catch(Exception)` and only `log.warn` — an uncaught exception from a
`@Scheduled` method makes Spring stop rescheduling it, which would silently end the capture. A disk
problem therefore shows up as warn lines plus **missing rows**, never as a dead task.

The two tasks are independent, so `at` values in `books-*.csv` never line up exactly with those in
`fleet-*.csv` (see §8.6 for the join).

### 7.4 What each task queries

- **books**: `statsService.books(market=null, state=ALL, symbol=null, sort=SYMBOL, order=ASC, limit=max-books-returned)`.
  `state=ALL` is deliberate — a book falling out of SYNCED overnight is exactly what a long capture is
  for, and the default `SYNCED` filter would make it silently vanish from the file.
- **fleet + shards**: `statsService.fleetStats(market=null, state=ALL, bins=0, outliers=0, domain=P99)`.
  Only `totals` and `byShard` are written; the stat blocks, histogram and outliers are recomputed
  offline from the per-book rows instead.

### 7.5 File naming, rotation, retention

- Files are `<prefix>-<UTC date>.csv`, e.g. `books-2026-08-02.csv`. The date comes from
  `LocalDate.now(ZoneOffset.UTC)` **at append time**, so files rotate at UTC midnight and each file
  contains only rows from that UTC day.
- The header is written **only when the file is created**. Appending to an existing file never
  re-emits it. A restart mid-day appends to the same file with no marker — the discontinuity in
  cumulative counters is the only signal (§8.5).
- The retention sweep runs from `recordFleet()`, guarded to once per UTC day, and deletes **every
  `*.csv` in the directory** whose last-modified time is older than `retention-days`. Two consequences:
  it will delete unrelated `.csv` files you park in that directory, and today's file always survives
  because it is being modified. `retention-days: 0` disables the sweep entirely.

### 7.6 Volume

At ~1 000 books and the default cadences: `books-*.csv` is ~85 bytes/row → ~85 KB per write → **~120
MB/day**; `fleet-*.csv` ~150 bytes/row and `shards-*.csv` `shard-count` rows per tick, both negligible.
Steady-state disk is `retention-days × ~120 MB` ≈ 360 MB at the default 3 days (~1.7 GB at 14). Size
`retention-days` against the deployment's **disk**, not its RAM: the write itself is ~0.5 MB of
short-lived garbage per tick on the scheduler pool, never the hot path.

---

## 8. The CSV recorder — how to read the files

**This section is the reference for anyone analysing a capture.** All three files are long-format with
a leading timestamp, so each loads with a bare `pd.read_csv` and needs no reshaping.

### 8.1 General format rules

- UTF-8, `\n` line endings, comma-separated, **no quoting and no escaping**. This is safe because
  every field is a symbol (`[A-Z0-9]`), an enum, a number, or `SYMBOL/MARKET` — none can contain a
  comma. If a future field could, the writer must gain quoting first.
- `at` is an ISO-8601 instant with a `Z` suffix and millisecond precision, e.g.
  `2026-08-02T09:15:22.104Z`. It is the response's `generatedAt`, so **every row written by one tick
  carries the identical `at`** — use it as the tick/group key.
- Numbers are Java `Double.toString` / `Long.toString` output. Most rate fields are pre-rounded to 4
  decimals, but **scientific notation is possible** for very small values (`1.0E-4`). `pandas` parses
  it; naive `float()`-free string handling may not.
- `largestBook` is written as an **empty field** (`,,`) when a shard has no books — read as `NaN`, not
  as the string `"null"`.
- Rows are appended as whole blocks per tick with a `BufferedWriter` that is closed each write, so a
  torn final line is unlikely but not impossible after a hard kill; `on_bad_lines='warn'` is cheap
  insurance.

### 8.2 `books-<date>.csv` — one row per book per tick

```
at,symbol,market,state,size,bids,asks,updates,updatesPerSecond,idleMs,resyncs
```

| Column | Type | Meaning / gotchas |
|---|---|---|
| `at` | ISO instant | Tick key; identical across all rows of one tick |
| `symbol` | string | e.g. `BTCUSDT` |
| `market` | `SPOT`\|`FUTURES` | **`symbol` alone is not a key — `(symbol, market)` is.** The same symbol exists in both markets |
| `state` | `PENDING`\|`SNAPSHOT_REQUESTED`\|`SYNCED` | `state=ALL` is captured, so unsynced books appear with `size 0` |
| `size` | int | `bids + asks` |
| `bids`, `asks` | int | Per-side level counts |
| `updates` | long | **Cumulative `diffsApplied` since JVM start.** Monotonic within one JVM run; a *decrease* between consecutive ticks means a restart |
| `updatesPerSecond` | double | Collector EWMA (30 s half-life), rounded to 4 dp. Understated for the first ~30–60 s of a book's life and after a restart |
| `idleMs` | long | Time since `updates` was last **observed** to change. **Resolution 5 s**, quantised — expect values like 0, 5000, 10000… A fresh book reports `0` until sampled twice |
| `resyncs` | int | Cumulative since JVM start. Same restart caveat as `updates` |

Row count per tick ≈ the whole fleet (capped at `max-books-returned` = 2000). A tick with fewer rows
than its neighbours means books were still being registered, not that books were filtered out.

### 8.3 `fleet-<date>.csv` — one row per tick

```
at,books,synced,pending,snapshotRequested,totalLevels,updatesPerSecond,levelVisitsPerSecond,staleBooks,emptyBooks,resyncsLastHour,usedHeapBytes,maxHeapBytes
```

| Column | Type | Meaning / gotchas |
|---|---|---|
| `at` | ISO instant | Also the `at` of the same tick's `shards` rows — an exact join key |
| `books` | int | Whole fleet (`market` unfiltered, `state=ALL`) |
| `synced`, `pending`, `snapshotRequested` | int | State counts; sum to `books` |
| `totalLevels` | long | Σ size over all books — the memory driver |
| `updatesPerSecond` | double | Σ per-book EWMA rates |
| `levelVisitsPerSecond` | double | Σ `size × rate` — **the real work metric** (§3.3) |
| `staleBooks` | int | SYNCED with `idleMs > stale-threshold-ms` (120 s). Reads as illiquidity as much as fault — calibrate before alarming |
| `emptyBooks` | int | **SYNCED** books holding zero levels (not PENDING ones) |
| `resyncsLastHour` | long | Underestimates during the first hour after startup (§3.4) |
| `usedHeapBytes` | long | `totalMemory - freeMemory` at that instant. **Sawtooths with GC** — never read a single value |
| `maxHeapBytes` | long | `-Xmx`; constant for a run, and the denominator for headroom |

### 8.4 `shards-<date>.csv` — one row per shard per tick

```
at,shard,books,totalLevels,updatesPerSecond,levelVisitsPerSecond,largestBook,largestBookSize
```

| Column | Type | Meaning / gotchas |
|---|---|---|
| `at` | ISO instant | Same tick as the `fleet` row |
| `shard` | int | `0 .. shard-count-1`; `shard-count` rows per tick |
| `books` | int | Books whose `Math.abs(symbol.hashCode()) % shardCount` lands here |
| `totalLevels` | long | Σ size on the shard |
| `updatesPerSecond` | double | Σ per-book rates on the shard |
| `levelVisitsPerSecond` | double | **The field to compare across shards.** Equal `books` does not mean equal work |
| `largestBook` | `SYMBOL/MARKET` | Empty when the shard has no books |
| `largestBookSize` | int | That book's level count; `0` for an empty shard |

Note `ShardLoad.sampleAgeMs` is **not** written to CSV — staleness of a shard's publication is only
visible over the API.

### 8.5 Restarts, gaps and warm-up — read this before trusting a delta

Four artefacts will bite an unwary analysis:

1. **Cumulative counters reset on JVM restart.** `updates` and `resyncs` are since-JVM-start. A
   restart mid-file produces a step down. Always mask negative diffs rather than clipping them to
   zero, or the restart tick looks like an enormous burst in the opposite direction.
2. **Gaps mean downtime.** Missing ticks are the JVM being down, the scheduler being starved, or a
   disk failure (check for `Failed to record … CSV` warns). Never `ffill` across a gap; reindex to the
   expected cadence and leave `NaN`.
3. **EWMA warm-up.** Every `updatesPerSecond` in the first ~1–2 minutes of a run is low. Drop the
   first few minutes of any capture before fitting anything.
4. **Sampling lag.** Rows reflect `BookStats` published up to `publish-interval-ms` (1 s) ago and rates
   derived up to `sample-interval-ms` (5 s) ago. That is irrelevant at minute cadence but matters if
   you ever shorten the recorder intervals toward the collector's.

### 8.6 Loading recipes (pandas)

```python
import pandas as pd, glob

def load(prefix, directory="monitoring-data"):
    frames = [pd.read_csv(p, parse_dates=["at"]) for p in sorted(glob.glob(f"{directory}/{prefix}-*.csv"))]
    df = pd.concat(frames, ignore_index=True)
    return df.sort_values("at").reset_index(drop=True)

books  = load("books")
fleet  = load("fleet")
shards = load("shards")
```

**True per-interval update rate** (independent of the EWMA, restart-safe):

```python
books = books.sort_values(["symbol", "market", "at"])
g = books.groupby(["symbol", "market"], sort=False)
d_updates = g["updates"].diff()
dt        = g["at"].diff().dt.total_seconds()
books["rate_measured"] = (d_updates / dt).where(d_updates >= 0)   # NaN across restarts
```

**Book growth over the capture** — the question `books-*.csv` exists to answer:

```python
growth = (books[books.state == "SYNCED"]
          .pivot_table(index="at", columns=["symbol", "market"], values="size")
          .resample("5min").mean())
growth.max() - growth.iloc[0]        # per-book growth
```

**Bytes per level** — the capacity number. Use the *lower envelope* of `usedHeapBytes`, because a raw
value sits somewhere random on the GC sawtooth:

```python
f = fleet.set_index("at")
live = f["usedHeapBytes"].rolling("10min").min()          # approximate live set
import numpy as np
slope, intercept = np.polyfit(f["totalLevels"], live, 1)  # bytes per level, bytes of baseline
```

**Shard balance** — is the hash mapping distributing *work*, not just books:

```python
w = shards.pivot(index="at", columns="shard", values="levelVisitsPerSecond")
(w.max(axis=1) / w.min(axis=1)).describe()   # 1.0 = perfect; sustained >1.5 argues for more shards
b = shards.pivot(index="at", columns="shard", values="books")
(b.max(axis=1) / b.min(axis=1)).describe()   # ~1.0 always — this is the metric that misleads
```

**Sync health over time:**

```python
fleet.set_index("at")[["synced", "pending", "snapshotRequested"]].plot.area()
fleet.set_index("at")["resyncsLastHour"].plot()          # churn; ignore the first hour of a run
```

**Which books went quiet, and when:**

```python
quiet = books[(books.state == "SYNCED") & (books.idleMs > 120_000)]
quiet.groupby(["symbol", "market"]).size().sort_values(ascending=False).head(20)
```

### 8.7 What the CSVs deliberately do not contain

Price levels, per-level lifetimes, classification output, and any per-user data. The capture is about
fleet shape and capacity, not about market content. `sampleAgeMs`, `shardsStale`, the stat blocks, the
histogram and the outlier report are all recomputable offline from `books-*.csv` and are not
duplicated into the files.

---

## 9. Plotting helper

`tools/plot_orderbook_histogram.py` (needs `matplotlib`, `numpy`) draws the fleet-stats response:

```bash
curl -H "Authorization: Bearer $TOKEN" \
     "http://localhost:8080/api/monitoring/orderbook?state=SYNCED" > stats.json
curl -H "Authorization: Bearer $TOKEN" \
     "http://localhost:8080/api/monitoring/orderbook/books?limit=2000&sort=SYMBOL" > books.json

python tools/plot_orderbook_histogram.py stats.json --books books.json --out hist.png
```

Three panels: the server histogram as returned; the same clipped to a percentile (default `p95 × 1.5`)
where the fleet actually lives; and — with `--books` — a log-x rebin split by market, which is the
honest view of a three-decade spread and shows visually why `overall` should not be trusted.

Note the script predates the bounded-domain histogram (§4.2) and still clips client-side; its docstring
describes the old full-range behaviour. `hist.png` in the repo root is a sample of its output. Neither
file is required by the application.

---

## 10. Divergences, and known gaps

### 10.1 Implemented differently from the plan

| Plan | As built | Why |
|---|---|---|
| `history` embedded in the `/orderbook` response | Its own `GET /orderbook/history` endpoint with a `minutes` window | The ring dwarfs every statistic in the fleet response and changes far more slowly |
| History appended every collector tick (5 s), 720 points = 1 h | Appended every `history-interval-ms` (30 s), 2 880 points = 24 h | Rates need a fast sample, a multi-hour trend does not; the JSON payload is what gets expensive at long retention |
| Histogram over the full range, bins clamped `[5, 100]` | Bounded **domain** (`P99` default, `FENCE`, `FULL`) with explicit `overflowCount` and `sampleMax` | Clamping the count on a 49:1 range/IQR sample inflated bin width until the body collapsed into two buckets |
| `stale-threshold-ms: 30000` | `120000` | 30 s flagged normal illiquidity as a fault |
| — | `levelVisitsPerSecond` everywhere | The mid-price sweep makes work scale with `size × rate`, not message count |
| — | `usedHeapBytes` / `maxHeapBytes` in totals and history | Turns level counts into a memory forecast |
| — | `byShard` / `ShardLoad`, `updateRateByMarket` | Hash balances book count, not work; pooled update rates mix two truncated populations |
| — | `HistogramDomain`, `BookSort`, `SortOrder`, `StateFilter`, `Histogram`, `ShardLoad` as separate DTO files | Plan assumed nested records |
| — | `OrderBookCsvRecorder` + `Recorder` config + `spring.task.scheduling.pool.size: 4` | The whole on-disk capture feature (§7) was not in the plan |
| — | Diff-buffer overflow routed through `resync()` | It was duplicating `resync()`'s body and so escaped the counter |

### 10.2 Gaps in the current state

- **No tests exist for any of this.** The plan called for `DescriptiveStatsTest`, `HistogramTest`,
  `OrderBookStatsCollectorTest` and a `MonitoringControllerTest`; none were written.
  `DescriptiveStats` is pure, static and dependency-free precisely so it can be unit-tested against
  numpy/scipy reference vectors — that is the highest-value gap to close.
- `MonitoringController` has a **misplaced Javadoc block**: the `/orderbook/books` documentation sits
  immediately above `getOrderBookHistory`, so two Javadoc comments stack on the history method and the
  books endpoint has none.
- `.gitignore`'s new `/monitoring-data/` entry is commented as `tools/monitoring/poll-books.*`, a
  script that does not exist — the in-process recorder replaced it.

### 10.3 Stale statements in sibling docs

`CLAUDE.md` and `CURRENT_STATE.md` were updated for the trio but describe the **first** iteration:
they say the ring is 720 points / 1 hour at a 5 s cadence. It is 2 880 points / 24 hours at 30 s.

`.claude/docs/for-frontend/orderbook-stats-api.md` is stale in more places and should be regenerated
before a dashboard is built against it:

- history: says `sampleIntervalMs: 5000` / 720 points / 1 hour / ~120 KB; the field is
  `pointIntervalMs`, the values are 30 000 / 2 880 / 24 h / ~600 KB.
- history points omit `levelVisitsPerSecond` and `usedHeapBytes`.
- totals omit `levelVisitsPerSecond`, `usedHeapBytes`, `maxHeapBytes`.
- the response omits `updateRateByMarket` and `byShard` entirely.
- histogram omits `domain`, `sampleMax` and `overflowCount`, and asserts "counts always sum to `n`",
  which is only true together with `overflowCount`. The `domain` query param is undocumented.
- caveat 2 claims size is censored at 2 000 levels by the snapshot cap and that this manufactures left
  skew. It does not — see §5.1 caveat 2. The real distribution is heavily **right**-skewed.
