# Orderbook Statistics Endpoint Plan

Replace the content-dumping `GET /api/monitoring/orderbook` with **descriptive statistics over the
whole orderbook fleet** — distribution of book sizes, sync health, update frequency, staleness, and
histogram-ready data.

---

## 1. Problem

`MonitoringController.getOrderBook(symbol, market)` returns every price level of one book. Two things
are wrong with it:

1. **It answers a question nobody asks.** Nobody reads 2 000 price levels by eye. What we actually
   lack is *aggregate* knowledge: how big are books typically, how much do they vary, how many are
   synced, how fast are they updating, which ones are broken.
2. **It is a genuine data race.** `book.snapshotBids()` does `new TreeMap<>(bids)` from the Tomcat
   thread while the shard's Disruptor consumer is mutating that exact `TreeMap`. Per CLAUDE.md the
   books are "single-thread-owned by their shard's consumer — never synchronized, never shared across
   shards". A concurrent copy of a `TreeMap` under mutation can produce a corrupted copy, a
   `ConcurrentModificationException`, or (with an unlucky rebalance) an infinite loop on a Tomcat
   thread. Deleting this endpoint removes a latent production hazard.

Nothing consumes the endpoint: a repo-wide grep for `OrderBookResponse` / `LevelView` /
`monitoring/orderbook` finds no test, no frontend file (`screener-frontend-new/src/lib/api` included),
and no doc contract under `.claude/docs/for-frontend/`. **We are free to redefine it outright** — no
deprecation window needed.

---

## 2. What the endpoint should answer

Mapping the requirements to concrete output. "Size" always means `bids.size() + asks.size()` — never
per side, exactly as specified.

| # | Requirement | Delivered as |
|---|---|---|
| 1 | Average orderbook size | `mean` in the stat block |
| 2 | Median | `median` (= `p50`), plus full percentile ladder |
| 3 | How skewed | `skewness` (adjusted Fisher–Pearson G1) + `shape` label |
| 4 | Min / max | `min`, `max`, `range` |
| 5 | Outlier count | Tukey fences: `outliers.count` (1.5·IQR) and `extremeCount` (3·IQR), plus the named offenders |
| 6 | Histogram data | Server-computed `histogram.buckets` **and** the raw per-book sizes via `/orderbook/books` |
| 7 | How many synced | `totals.synced` / `pending` / `snapshotRequested` / `syncedRatio`, split per market |
| 8 | Update frequency + last update | `updatesPerSecond` (per book and fleet) and `idleMs`, both derived from a single per-diff counter — **see §4** |
| 9 | Other useful statistics | Robust spread (`mad`), stale/empty book counts, resync churn, per-book update-rate distribution, fleet trend history |

### 2.1 The one analytical decision that matters: **split SPOT from FUTURES**

Spot and futures books are fed by different streams at different cadences (1 s vs 500 ms) and have
different depth characteristics. Pooling them produces a **bimodal** distribution, and on a bimodal
sample the mean, median and skewness are all misleading — the "skew" you measure is mostly the gap
between two clusters, not the shape of either.

So the response reports the full stat block **three times**: `overall`, `byMarket.SPOT`,
`byMarket.FUTURES`. `overall` is kept because it is what the requirement literally asks for, but the
per-market blocks are the ones to trust, and they are returned unconditionally so the reader never has
to ask for the honest version.

Second caveat to record in the response docs: book size is **censored on both ends**. A REST snapshot
delivers at most 1 000 levels per side (hard cap → 2 000), and `computeDistance()` sweeps away
everything outside ±`price-filter-threshold` (0.1) of mid-price. Liquid symbols therefore pile up
against a ceiling, which by itself manufactures left skew. This is a property of our pipeline, not of
the market — worth knowing before over-interpreting `skewness`.

---

## 3. Proposed API

Two endpoints, both under the existing ADMIN-only `/api/monitoring/**` gate.

### 3.1 `GET /api/monitoring/orderbook` — fleet statistics

Query params (all optional):

| Param | Default | Meaning |
|---|---|---|
| `market` | *(none)* | `SPOT` / `FUTURES` — restricts the sample. Omitted = all books; `byMarket` is returned either way |
| `state` | `SYNCED` | `SYNCED` / `ALL` — which books enter the size sample. Default `SYNCED` because a `PENDING` book has 0 levels and would drag every statistic toward zero. State *counts* always cover all books |
| `bins` | `0` (auto) | Histogram bin count. `0` → Freedman–Diaconis, capped to `[5, 100]` |
| `outliers` | `10` | Max named outliers per tail (0 = counts only) |

```jsonc
{
  "generatedAt": "2026-07-30T09:15:22.104Z",
  "sampleAgeMs": 340,              // age of the newest shard publication (§4.2)
  "shardsStale": [],               // shard indexes whose stats are older than 3× publish interval

  "totals": {
    "books": 1042,
    "byState":   { "SYNCED": 1001, "SNAPSHOT_REQUESTED": 12, "PENDING": 29 },
    "byMarket":  { "SPOT": 402, "FUTURES": 640 },
    "syncedRatio": 0.9607,
    "totalLevels": 1873420,
    "updatesPerSecond": 41230.5,            // diffs applied across the fleet
    "staleBooks": 3,                        // SYNCED but no diff for > stale-threshold-ms
    "emptyBooks": 1,                        // SYNCED with size 0
    "resyncsLastHour": 47
  },

  "overall":  { /* StatBlock over size */ },
  "byMarket": { "SPOT": { /* StatBlock */ }, "FUTURES": { /* StatBlock */ } },

  "histogram": {
    "bins": 22, "binWidth": 148.0, "lower": 12, "upper": 3268,
    "method": "FREEDMAN_DIACONIS",
    "buckets": [ { "from": 12, "to": 160, "count": 5 }, /* … */ ]
  },

  "sizeOutliers": {
    "lowerFence": 618.5, "upperFence": 2913.0,
    "extremeLowerFence": 45.0, "extremeUpperFence": 3486.5,
    "count": 17, "lowCount": 14, "highCount": 3, "fraction": 0.0170,
    "extremeCount": 3,
    "low":  [ { "symbol": "XYZUSDT", "market": "SPOT", "size": 12, "state": "SYNCED" } ],
    "high": [ { "symbol": "BTCUSDT", "market": "FUTURES", "size": 3268, "state": "SYNCED" } ]
  },

  "updateRate": { /* StatBlock over per-book updatesPerSecond */ },

  "history": [                        // bounded ring, oldest → newest
    { "at": "2026-07-30T08:15:00Z", "books": 1040, "synced": 998,
      "totalLevels": 1861002, "meanSize": 1864.7, "medianSize": 1877,
      "updatesPerSecond": 40980.1, "staleBooks": 2 }
  ]
}
```

**StatBlock** — one reusable record, computed identically wherever it appears:

```jsonc
{
  "n": 1001, "sum": 1873420,
  "mean": 1871.5, "median": 1894.0,
  "stdDev": 421.6, "variance": 177746.6,
  "min": 12, "max": 3268, "range": 3256,
  "p10": 902, "p25": 1602, "p50": 1894, "p75": 2180, "p90": 2402, "p95": 2560,
  "iqr": 578.0, "mad": 241.0,
  "skewness": -0.83, "shape": "MODERATE_LEFT_SKEW"
}
```

### 3.2 Statistic definitions (implement exactly these — no hand-waving)

| Field | Definition |
|---|---|
| `mean` | Σx / n |
| percentiles | **R-7 / linear interpolation** on the sorted sample (numpy & Excel `PERCENTILE.INC` default): `h = (n-1)·p`, `x[⌊h⌋] + (h-⌊h⌋)·(x[⌊h⌋+1] - x[⌊h⌋])`. `median` = `p50` |
| `variance` / `stdDev` | **Sample** (Bessel, `n-1`). `null` when `n < 2` |
| `iqr` | `p75 - p25` |
| `mad` | `median(|xᵢ - median|)` — outlier-proof spread; if `mad × 1.4826 ≪ stdDev`, outliers are inflating σ |
| `skewness` | Adjusted Fisher–Pearson G1: `n/((n-1)(n-2)) · Σ((xᵢ-x̄)/s)³`. `null` when `n < 3` |
| `shape` | From G1: `|g1| < 0.5` → `SYMMETRIC`; `0.5 ≤ |g1| < 1` → `MODERATE_{LEFT,RIGHT}_SKEW`; `≥ 1` → `HIGH_{LEFT,RIGHT}_SKEW`. Negative G1 = left/negative skew (long thin tail of *small* books) |
| Tukey fences | outlier: outside `[p25 - 1.5·iqr, p75 + 1.5·iqr]`; extreme: outside `[p25 - 3·iqr, p75 + 3·iqr]` |
| Freedman–Diaconis | `width = 2·iqr·n^(-1/3)`; `bins = ceil(range / width)`, clamped to `[5, 100]`. If `iqr == 0`, fall back to Sturges `ceil(log2(n)) + 1` |

Deliberately **not** computed: `coefficientOfVariation`, `madScaled`, `kurtosisExcess`,
`bimodalityCoefficient`, `gini` / level-concentration, and the `p1` / `p5` / `p99` tails. None of them
costs the hot path anything — they were cut because the response already carries three StatBlocks per
subject and each extra field is one more number a reader has to decide to ignore.

Every `n`-guarded field is nullable rather than `NaN`/`0` — an undefined statistic must not be
mistakable for a computed one.

### 3.3 `GET /api/monitoring/orderbook/books` — the per-book table

This is requirement #6's raw material (every book's size, for client-side plotting) and the
drill-down for #8.

Params: `market`, `state`, `symbol` (exact filter), `sort` (`SIZE` | `UPDATE_RATE` | `IDLE` |
`RESYNCS` | `SYMBOL`, default `SIZE`), `order` (`DESC` default), `limit` (default 200, max
`max-books-returned` = 2000).

```jsonc
{
  "generatedAt": "…", "sampleAgeMs": 340, "totalMatching": 1042, "returned": 200,
  "books": [
    { "symbol": "BTCUSDT", "market": "FUTURES", "state": "SYNCED",
      "size": 3268, "bids": 1641, "asks": 1627,
      "updates": 1842031, "updatesPerSecond": 9.8,
      "idleMs": 0, "resyncs": 2 }
  ]
}
```

`limit=2000&sort=SYMBOL` returns the whole fleet — ~1 000 rows ≈ 200 KB, perfectly reasonable for an
admin call, and it is the only payload the endpoint family will ever return that scales with fleet
size (it is bounded by book *count*, not by level count — the old endpoint was bounded by level count
for a *single* book, which is the wrong bound).

### 3.4 What is removed

- `MonitoringController.getOrderBook(symbol, market)` and the `OrderBookResponse` / `LevelView` records.
- `OrderBook.snapshotBids()` / `snapshotAsks()` — their only caller was that endpoint, and they are
  the data race from §1.

No level-content peek is retained. If single-book level inspection is ever wanted again, it must be
served from a snapshot the *consumer thread* publishes, never by copying a live `TreeMap` — that is a
separate feature, out of scope here.

---

## 4. Getting the data out of the hot path safely

This is the only part with real design risk. The books live on Disruptor consumer threads that
process hundreds of thousands of messages per second; the endpoint runs on a Tomcat thread.

### 4.1 Counters — two plain fields, consumer-owned

Add to `OrderBook` (written **only** by the owning shard's consumer, exactly like `bids`/`asks`):

```java
long diffsApplied;   // successful applyLiveDiff calls
int  resyncs;        // cumulative resync() calls
```

That is the entire hot-path footprint of this feature: **one field increment per diff** — a
load-add-store into an L1-resident line on a thread-confined object, off the critical dependency
chain, ~1 cycle — plus one increment per actual `resync()`. Zero allocation, zero volatile, zero
locking, **nothing per price level, and no clock read**. `applyLevelsDirectly` is not touched at all.

Everything in §3 derives from these two counters plus `bids.size()`, `asks.size()` and `state` — all
O(1) reads taken once per publish, not per message.

**Rejected instrumentation. Do not re-add without reading this table.**

| Rejected | Why |
|---|---|
| `levelChanges` — one increment per price level | Cut for lack of *value*, not for cost. The increment is only ~0.1–0.3 % of the 150–400 ns each level already burns in `applyLevelsDirectly` (three `nextToken()`s, two `JavaDoubleParser` parses, a `TreeMap` get + put/remove at ~11 comparisons with `Double` boxing on both). But `levelChangesPerSecond` ≈ `updatesPerSecond × mean levels per diff`, it answers none of requirements #1–#9 on its own, and it would propagate into `Totals`, the per-book row, a sort key and the collector's EWMA. If it is ever genuinely wanted: accumulate in a local, have `applyLevelsDirectly` return the count, and fold it once per side — never store to a field inside the loop |
| `lastUpdateMillis`, and hoisting the clock read out of `applyLevelsDirectly` | The hoist is **not** the free win it looks like. `System.currentTimeMillis()` today fires only when a level is *newly created* (`OrderBook.java:271`); a diff that merely resizes or removes existing levels — a large share of the traffic — pays **zero** clock reads now and would pay one per diff after the hoist. Staleness does not need a per-diff timestamp anyway: §4.3 derives it from `diffsApplied` at sample resolution |
| `syncedAtMillis` / `syncedForMs` | Not asked for by any of the nine requirements; one more field to publish and to keep coherent |

### 4.2 Publication — one volatile store per book per second

Counters written by one thread and read by another need a happens-before edge, and a 64-bit
non-volatile `long` can even be read torn. Making `diffsApplied` itself `volatile` would put a
`StoreLoad` fence on every diff — ~300 k/second on the hot path — rejected.

Instead the consumer **publishes an immutable snapshot on a slow cadence**:

```java
// OrderBook — consumer thread only
private volatile BookStats stats = BookStats.EMPTY;

void publishStats(long now) {                    // called by the shard's consumer thread
    stats = new BookStats(symbol, market, state, bids.size(), asks.size(),
                          diffsApplied, resyncs, now);
}
public BookStats getStats() { return stats; }    // safe from any thread
```

The single volatile store of an immutable record is a release; the reader's volatile load is the
matching acquire, so **all** fields are visible and mutually consistent — the reader sees one coherent
instant, never a mix.

Trigger, in `DepthEventHandler.onEvent` — a **counter-masked** clock read, *not* `endOfBatch`:

```java
private static final int CLOCK_CHECK_MASK = 0xFF;      // consult the clock every 256th event

if ((++eventsSeen & CLOCK_CHECK_MASK) == 0) {          // plain fields, thread-confined to this handler
    long now = System.currentTimeMillis();
    if (now - lastStatsPublishMillis >= publishIntervalMs) {
        lastStatsPublishMillis = now;
        statsPublisher.publishShard(shardIndex, now);
    }
}
```

**Why not `endOfBatch`.** Disruptor sets `endOfBatch` on the last event available at poll time, so
whenever the consumer keeps up with its producers — the steady state the whole design targets — each
poll returns a single event and `endOfBatch` is true on **every** message. A clock read behind that
gate is not "amortised over a whole batch"; it is ~150 k `System.currentTimeMillis()` calls per second
per shard, and it degrades to that precisely when throughput is highest. The mask amortises
unconditionally: one clock read per 256 events (~0.002 % of a core) regardless of batching, and the
publish cadence stays time-based rather than volume-based.

`CLOCK_CHECK_MASK` is a constant, not a `screener.*` tunable — it is an implementation detail of the
gate, and it only has to be small enough that a healthy shard clears it several times per publish
interval. With 1 000+ books at 1–2 diffs/s each, a shard sees ≥ 500 events/s, so 256 holds the 1 s
cadence with margin. A shard too quiet to clear it is already an incident, and is reported as one by
`shardsStale`.

`publishShard` walks `store.books()` filtered by `Math.abs(symbol.hashCode()) % shardCount ==
shardIndex` — the same mapping `DisruptorShardManager.getRingBuffer` uses, so it touches exactly the
books this thread owns — and calls `publishStats(now)` on each.

Cost at the default 1 s interval: ~1 000 small allocations and ~1 000 volatile stores **per second**,
plus one map walk per shard per second. Against a 300 k msg/s firehose this is noise. It is a
deliberate, bounded, documented exception to "no allocation on the consumer thread" — write that
justification in the class Javadoc so a future reader does not "fix" it.

**Why walk every book instead of letting each book publish when it updates:** a book that has stopped
receiving diffs is precisely the one we most need to see (requirement #8 — staleness). Self-publishing
on update would freeze a dead book's stats at its last healthy value and hide the outage. The shard
walk refreshes silent books too.

**Residual limitation, surfaced not hidden:** publication is driven by the consumer thread, so a shard
receiving (almost) no events never runs the gate and its books' stats freeze. The response therefore
reports `sampleAgeMs` and lists any `shardsStale` (published longer ago than 3× the interval) rather
than silently serving stale numbers.

### 4.3 Rates and staleness — `OrderBookStatsCollector`

A `@Scheduled(fixedRateString = "${screener.monitoring.orderbook.sample-interval-ms}")` bean (default
5 s) in `monitoring/`. Ordinary Spring code — the hot-path rules do not apply here.

Each tick it reads every book's `getStats()` and, against its own previous sample:

- `updatesPerSecond = Δ diffsApplied / Δt`, EWMA-smoothed (α from a configurable half-life) so a
  single slow tick does not make the number jump;
- `idleMs` — time since `diffsApplied` was last *observed to change*. This is what buys the hot path
  its freedom from clock reads: a book whose counter has not moved between two samples is idle, and
  the 5 s sample interval resolves the 30 s `stale-threshold-ms` six times over. It is named `idleMs`
  and not `lastUpdateAgeMs` precisely because its resolution is `sample-interval-ms`, not
  milliseconds — do not rename it back without first restoring a per-diff timestamp;
- `staleBooks` — SYNCED books whose `idleMs` exceeds `stale-threshold-ms`;
- `resyncsLastHour` from a bounded ring of resync counts;
- appends one fleet-level point to a bounded history ring (default 720 × 5 s = 1 hour), which is what
  `history` in the response serves.

State is exposed as a single `volatile Map<String, BookRate>` replaced wholesale each tick — same
publish discipline, no locks on the read path.

### 4.4 Request-time computation

`OrderBookStatsService` (Tomcat thread) collects sizes into an `int[]`, sorts it, and computes the
stat blocks. For ~1 000 books that is a 1 000-element sort plus three linear passes — well under a
millisecond. No caching, no `@Transactional`, no DB. Everything it reads is an already-published
immutable snapshot, so it cannot race with the consumers.

---

## 5. Files

### New — `monitoring/`

| File | Role |
|---|---|
| `OrderBookStatsPublisher.java` | `publishShard(shardIndex, now)`; owns the shard→book filter. Called from the consumer thread |
| `OrderBookStatsCollector.java` | `@Scheduled` sampler: per-book rates, EWMA, staleness, fleet history ring |
| `OrderBookStatsService.java` | Request-time assembly of the response from published stats + collector rates |
| `DescriptiveStats.java` | Pure, dependency-free statistics: percentiles (R-7), mean/variance, G1 skewness, MAD, Tukey fences, Freedman–Diaconis. Static, no Spring — the only unit-testable maths in the feature |
| `dto/OrderBookStatsResponse.java` | Fleet response record + nested `Totals`, `Histogram`, `OutlierReport`, `HistoryPoint` |
| `dto/StatBlock.java` | The reusable descriptive-statistics record (§3.1) |
| `dto/OrderBookListResponse.java` | `/orderbook/books` response + `BookRow` |

### Changed

| File | Change |
|---|---|
| `binance/orderbook/OrderBook.java` | +`long diffsApplied` / `int resyncs`, +`volatile BookStats stats`, +`publishStats(now)` / `getStats()`; increment the two counters in `applyLiveDiff` and `resync`; **remove** `snapshotBids` / `snapshotAsks`. `applyLevelsDirectly` is left untouched |
| `binance/orderbook/BookStats.java` *(new, in `orderbook/`)* | Immutable published snapshot record. Lives next to `OrderBook` because the hot path constructs it |
| `binance/orderbook/OrderBookStore.java` | + `Collection<OrderBook> books()` (unmodifiable values view) |
| `binance/disruptor/DepthEventHandler.java` | + counter-masked publish gate; `eventsSeen` / `lastStatsPublishMillis` fields; publisher + interval injected |
| `binance/disruptor/DisruptorShardManager.java` | Pass the publisher and interval into each handler |
| `monitoring/MonitoringController.java` | Replace `getOrderBook` with `getOrderBookStats` + `getOrderBooks`; delete `OrderBookResponse` / `LevelView` |
| `config/MonitoringProperties.java` *(new)* | `@ConfigurationProperties("screener.monitoring")` |
| `application.yml` | New `screener.monitoring.orderbook.*` block |
| `CLAUDE.md`, `CURRENT_STATE.md` | Update the `monitoring/` row and the endpoint list |
| `.claude/docs/for-frontend/` | New `orderbook-stats-api.md` if a dashboard will consume this |

Dependency direction stays clean: `binance/` never imports `monitoring/`. The publisher is injected
into `DepthEventHandler` behind a small interface (`ShardStatsPublisher`) declared in
`binance/orderbook/`, implemented in `monitoring/` — same inversion the project already uses for
`OrderBookProcessor` → `SnapshotFetchQueue`.

### Config

```yaml
screener:
  monitoring:
    orderbook:
      # How often each shard's consumer republishes its books' stats snapshot (hot-path cadence).
      publish-interval-ms: 1000
      # How often the collector samples published stats to derive rates and append history.
      # Also the resolution of `idleMs` — keep it well below stale-threshold-ms.
      sample-interval-ms: 5000
      # EWMA half-life for the smoothed per-book update rate.
      rate-half-life-ms: 30000
      # Fleet history ring length (720 × 5 s = 1 h).
      history-size: 720
      # A SYNCED book with no diff for this long counts as stale.
      stale-threshold-ms: 30000
      # 0 = choose bin count by Freedman–Diaconis.
      default-histogram-bins: 0
      max-outliers-listed: 10
      max-books-returned: 2000
```

---

## 6. Phases

**Phase 1 — instrumentation.** The two counters, `BookStats`, `publishStats`, `OrderBookStore.books()`,
the counter-masked publish gate, `OrderBookStatsPublisher`. No API change yet. Verify by logging fleet
totals on a timer and confirming no throughput regression.

**Phase 2 — collector.** `OrderBookStatsCollector` with rates, staleness, resync churn, history ring.

**Phase 3 — statistics + API.** `DescriptiveStats`, DTOs, `OrderBookStatsService`, both endpoints.

**Phase 4 — removal + docs.** Delete the old endpoint, `snapshotBids`/`snapshotAsks`, and the DTOs;
update `CLAUDE.md` / `CURRENT_STATE.md`; write the frontend contract doc.

Phases 1–2 are independently valuable (fleet health in the logs, for the cost of one increment per
diff) and can merge before Phase 3 exists.

---

## 7. Tests

- **`DescriptiveStatsTest`** — the core. Fixed vectors with expectations computed independently
  (numpy `percentile` R-7, `scipy.stats.skew(bias=False)`) hard-coded into the test. Edge cases:
  `n = 0, 1, 2, 3` (nullable fields), all-identical values (`iqr = 0`, `stdDev = 0`, FD falls back to
  Sturges), a known-outlier sample asserting the Tukey counts.
- **`HistogramTest`** — bucket edges are half-open `[from, to)` with the max landing in the last
  bucket; counts sum to `n`; `bins` clamped to `[5, 100]`.
- **`OrderBookStatsCollectorTest`** — injected clock; two synthetic `BookStats` generations produce the
  expected `updatesPerSecond`; EWMA converges; a book whose `diffsApplied` is unchanged past the
  threshold is counted stale and its `idleMs` grows by one sample interval per tick; the history ring
  evicts at capacity.
- **`OrderBookTest`** (extend, if present) — `diffsApplied` and `resyncs` advance correctly across a
  snapshot-then-diffs sequence, including a sequence-gap resync.
- **`MonitoringControllerTest`** — MockMvc slice: response shape, `state=ALL` vs default `SYNCED`,
  `limit` clamping, ADMIN-only (403 for a plain user).

---

## 8. Open items (deliberate non-blockers)

1. **`state=SYNCED` as the size-sample default** — a `PENDING` book has zero levels; including it
   would report a mean that mostly measures how many books are currently resyncing. Counts of every
   state are always returned, so nothing is hidden. Flip with `state=ALL`.
2. **`overall` retained despite the bimodality argument** — requirement #1 asks for "the average
   orderbook size", full stop. It is returned, with `byMarket` beside it unconditionally and a note in
   the response docs that the pooled block mixes two populations. A computed `bimodalityCoefficient`
   (and the `kurtosisExcess` it needs) was dropped as one statistic too many: the split is the answer,
   the coefficient was only a way of restating it.
3. **Level-lifetime statistics** (`firstSeenMillis` is already tracked, so mean level age per book is
   available) are **excluded**: computing them fleet-wide is O(total levels) ≈ 1.9 M iterations, and it
   would have to run on the consumer thread inside the publish tick. If wanted, add it later as a
   per-book opt-in on `/orderbook/books?symbol=…`, computed during that book's next publish.
4. **Classifier output statistics** (how many levels survive classification, tier distribution) are a
   natural sibling but belong to `analysis/`, not here.
5. **Push (the consumer publishes) rather than pull (the collector reads the books directly).** A
   cross-thread read of `TreeMap.size()` is a plain `int` load — atomic per the JLS, at worst a few
   milliseconds stale, which for a monitoring endpoint would be perfectly adequate — and going that
   way would delete `BookStats`, `OrderBookStatsPublisher`, `ShardStatsPublisher` and the
   `DepthEventHandler` change outright. Push is kept for two reasons: a non-volatile `long
   diffsApplied` is *not* guaranteed free of word tearing (JLS 17.7), and the published record hands
   the reader one coherent instant instead of four independently-stale fields. Worth revisiting if the
   publisher's code volume ever starts to look disproportionate to what it protects.
