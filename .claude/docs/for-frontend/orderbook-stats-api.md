# Orderbook Statistics API

Admin-only diagnostics describing the health and shape of the local orderbook fleet.

Both endpoints sit under `/api/monitoring/**` and require a Bearer JWT carrying `ROLE_ADMIN`.
Any other authenticated user gets `403`.

- `GET /api/monitoring/orderbook` — descriptive statistics over the whole fleet
- `GET /api/monitoring/orderbook/history` — the fleet trend over the last hour
- `GET /api/monitoring/orderbook/books` — the per-book table behind them

**"Size" always means `bids + asks`**, never one side.

---

## Read this before plotting anything

Two properties of the data will mislead a dashboard that ignores them.

**1. `overall` pools two different populations.** Spot books are fed at 1 update/s, futures at
1/500 ms, and the two have different depth characteristics. The pooled sample is therefore
*bimodal*, and on a bimodal sample the mean, the median and especially the skewness are misleading —
the "skew" you would render is mostly the gap between two clusters, not the shape of either. The
response returns `byMarket.SPOT` and `byMarket.FUTURES` unconditionally. **Prefer them.** `overall`
exists because "the average orderbook size" is a question people ask, not because it is the best
answer.

**2. Size is censored on both ends.** A REST snapshot delivers at most 1 000 levels per side (so
2 000 total is a hard ceiling), and every level outside ±10 % of mid-price is swept away. Liquid
symbols pile up against that ceiling, which by itself manufactures left skew. That is a property of
our pipeline, not of the market — don't report it as a market finding.

**3. Numbers are sampled, not live.** Orderbooks are owned by their shard's Disruptor consumer
thread and are never read cross-thread. The consumer publishes an immutable snapshot per book about
once a second; rates are derived by a sampler every 5 seconds. Hence `sampleAgeMs`. If
`shardsStale` is non-empty, those shards' books are frozen — usually because that shard is receiving
(almost) no events, which is itself an incident worth surfacing.

**4. Undefined ≠ zero.** Any statistic that is undefined for the sample size comes back as `null`,
never `0` or `NaN`. `stdDev`/`variance` need `n ≥ 2`; `skewness`/`shape` need `n ≥ 3` and a non-zero
spread. Render `null` as "—", not as `0`.

---

## `GET /api/monitoring/orderbook`

### Query parameters

| Param | Type | Default | Meaning |
|---|---|---|---|
| `market` | `SPOT` \| `FUTURES` | *(none)* | Restricts the whole population. Omitted = all books |
| `state` | `SYNCED` \| `PENDING` \| `SNAPSHOT_REQUESTED` \| `ALL` | `SYNCED` | Which books enter the **size sample**. State *counts* always cover every book |
| `bins` | int | `0` | Histogram bin count. `0` = Freedman–Diaconis. Clamped to `[5, 100]` |
| `outliers` | int | `10` | Max **named** outliers per tail (`0` = counts only). Capped server-side |

`state` defaults to `SYNCED` because a `PENDING` book holds zero levels: including it would report a
mean that mostly measures how many books happen to be resyncing. Nothing is hidden — `totals.byState`
always counts all of them. Pass `state=ALL` to override.

### Response

```jsonc
{
  "generatedAt": "2026-07-30T09:15:22.104Z",
  "sampleAgeMs": 340,              // age of the newest shard publication
  "shardsStale": [],               // shard indexes frozen for > 3x the publish interval

  "totals": {
    "books": 1042,
    "byState":  { "PENDING": 29, "SNAPSHOT_REQUESTED": 12, "SYNCED": 1001 },
    "byMarket": { "SPOT": 402, "FUTURES": 640 },
    "syncedRatio": 0.9607,
    "totalLevels": 1873420,
    "updatesPerSecond": 41230.5,   // diffs applied across the fleet (EWMA)
    "staleBooks": 3,               // SYNCED but idle longer than the stale threshold
    "emptyBooks": 1,               // SYNCED holding zero levels
    "resyncsLastHour": 47
  },

  "overall":  { /* StatBlock over size — see caveat 1 */ },
  "byMarket": { "SPOT": { /* StatBlock */ }, "FUTURES": { /* StatBlock */ } },

  "histogram": {
    "bins": 22,
    "binWidth": 148.0,
    "lower": 12,
    "upper": 3268,
    "method": "FREEDMAN_DIACONIS",
    "buckets": [ { "from": 12, "to": 160, "count": 5 } /* … */ ]
  },

  "sizeOutliers": {
    "lowerFence": 618.5, "upperFence": 2913.0,
    "extremeLowerFence": 45.0, "extremeUpperFence": 3486.5,
    "count": 17, "lowCount": 14, "highCount": 3, "fraction": 0.017,
    "extremeCount": 3,
    "low":  [ { "symbol": "XYZUSDT", "market": "SPOT",    "size": 12,   "state": "SYNCED" } ],
    "high": [ { "symbol": "BTCUSDT", "market": "FUTURES", "size": 3268, "state": "SYNCED" } ]
  },

  "updateRate": { /* StatBlock over per-book updatesPerSecond */ }
}
```

The fleet **trend over time** is not in this response — it is a separate endpoint,
[`/orderbook/history`](#get-apimonitoringorderbookhistory). This response is ~8 KB and safe to poll;
the trend ring is ~120 KB at its cap and changes once every 5 s.

### StatBlock

The same record wherever it appears, computed identically.

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

| Field | Definition |
|---|---|
| `mean` | `Σx / n` |
| `p10`…`p95`, `median` | **R-7 / linear interpolation** — the numpy `percentile` and Excel `PERCENTILE.INC` default. `median` is exactly `p50` |
| `variance`, `stdDev` | **Sample** (Bessel, `n-1`). `null` when `n < 2` |
| `iqr` | `p75 - p25` |
| `mad` | `median(\|xᵢ - median\|)`. Outlier-proof spread: if `mad × 1.4826` is far below `stdDev`, outliers are inflating σ |
| `skewness` | Adjusted Fisher–Pearson G1 (= `scipy.stats.skew(bias=False)`). `null` when `n < 3` or spread is zero. **Negative = left skew = a long thin tail of _small_ books** |
| `shape` | `SYMMETRIC` (\|g1\| < 0.5), `MODERATE_{LEFT,RIGHT}_SKEW` (< 1), `HIGH_{LEFT,RIGHT}_SKEW` (≥ 1). `null` when `skewness` is |

### Histogram

Buckets are half-open `[from, to)` except the last, which is closed so the maximum lands inside it.
Counts always sum to `n`.

`method` tells you how `bins` was chosen:

| Value | Meaning |
|---|---|
| `FREEDMAN_DIACONIS` | Default: `width = 2·iqr·n^(-1/3)`, `bins = ceil(range/width)` clamped to `[5, 100]` |
| `STURGES` | Fallback when `iqr == 0`: `ceil(log2(n)) + 1` |
| `EXPLICIT` | You passed `bins` |
| `SINGLE_VALUE` | Every book the same size — one bucket, `binWidth: 0` |
| `NONE` | Empty sample — `buckets: []`, `lower`/`upper` are `null` |

### Outliers

Tukey fences on the **overall** size sample:

- outlier: outside `[p25 - 1.5·iqr, p75 + 1.5·iqr]`
- extreme: outside `[p25 - 3·iqr, p75 + 3·iqr]`

`count = lowCount + highCount`. `low` is sorted smallest-first, `high` largest-first, each capped at
`outliers` entries. All fence fields are `null` when the sample is empty.

---

## `GET /api/monitoring/orderbook/history`

The fleet-level trend — one point per collector tick (5 s), retained for one hour (720 points), oldest
first. This is what you plot as a time series: sync progress after a restart, level growth, fleet
update rate, staleness spikes.

**It is always fleet-wide.** The collector accumulates it on a schedule with no request in scope, so
it honours no `market` or `state` filter. Don't overlay it on a filtered stat block and expect the
two to agree.

### Query parameters

| Param | Type | Default | Meaning |
|---|---|---|---|
| `minutes` | int | `60` | Trailing window. `0` or less returns the whole retained ring |

### Response

```jsonc
{
  "generatedAt": "2026-07-30T09:15:22.104Z",
  "sampleIntervalMs": 5000,     // spacing between consecutive points
  "windowMs": 3600000,          // the ring's full capacity in wall-clock time
  "available": 720,             // points currently retained
  "returned": 180,              // points after `minutes` was applied
  "points": [
    { "at": "2026-07-30T08:15:00Z", "books": 1040, "synced": 998,
      "totalLevels": 1861002, "meanSize": 1864.7, "medianSize": 1877.0,
      "updatesPerSecond": 40980.1, "staleBooks": 2 }
  ]
}
```

`meanSize` / `medianSize` are over **SYNCED books only** and are `null` for any point where nothing
had synced yet — the first few ticks after a restart. Render `null` as a gap, not as `0`.

### Sizing

720 points ≈ 120 KB. Fetch it on the cadence a chart needs (`?minutes=15` ≈ 30 KB), not on every
dashboard poll.

---

## `GET /api/monitoring/orderbook/books`

The per-book table — raw material for client-side plotting, and the drill-down for update-frequency
questions.

### Query parameters

| Param | Type | Default | Meaning |
|---|---|---|---|
| `market` | `SPOT` \| `FUTURES` | *(none)* | Restrict to one market |
| `state` | as above | `SYNCED` | Restrict by sync state |
| `symbol` | string | *(none)* | Exact symbol filter, case-insensitive |
| `sort` | `SIZE` \| `UPDATE_RATE` \| `IDLE` \| `RESYNCS` \| `SYMBOL` | `SIZE` | Sort key |
| `order` | `ASC` \| `DESC` | `DESC` | Sort direction |
| `limit` | int | `200` | Max rows, clamped to 2000 |

`limit=2000&sort=SYMBOL` returns the whole fleet: ~1 000 rows ≈ 200 KB. The payload is bounded by
book *count*, not by level count, so it stays predictable.

### Response

```jsonc
{
  "generatedAt": "2026-07-30T09:15:22.104Z",
  "sampleAgeMs": 340,
  "totalMatching": 1042,        // rows matching the filters, before `limit`
  "returned": 200,
  "books": [
    { "symbol": "BTCUSDT", "market": "FUTURES", "state": "SYNCED",
      "size": 3268, "bids": 1641, "asks": 1627,
      "updates": 1842031, "updatesPerSecond": 9.8,
      "idleMs": 0, "resyncs": 2 }
  ]
}
```

| Field | Meaning |
|---|---|
| `updates` | Cumulative diffs applied since JVM start (monotonic — diff it yourself for a custom window) |
| `updatesPerSecond` | EWMA-smoothed, 30 s half-life, so one slow tick doesn't make it jump |
| `idleMs` | Time since `updates` was last **observed** to change. **Resolution is the 5 s sample interval, not milliseconds** — that is why it is `idleMs` and not `lastUpdateAgeMs`. A fresh book reports `0` until it has been sampled twice |
| `resyncs` | Cumulative resyncs since JVM start. Sort by this to find books that keep losing sequence |

### Useful queries

| Question | Query |
|---|---|
| Which books have gone quiet? | `?sort=IDLE&order=DESC&limit=20` |
| Which books keep desyncing? | `?state=ALL&sort=RESYNCS&order=DESC&limit=20` |
| Which are stuck unsynced? | `?state=PENDING&sort=SYMBOL&order=ASC&limit=2000` |
| Everything, for a client-side plot | `?limit=2000&sort=SYMBOL&order=ASC` |

---

## Errors

Standard `ApiError` shape. An unparseable enum value (`market`, `state`, `sort`, `order`) or a
non-numeric `bins`/`outliers`/`limit` yields `400`. Out-of-range numbers are **clamped**, not
rejected. A missing `ROLE_ADMIN` yields `403`.
