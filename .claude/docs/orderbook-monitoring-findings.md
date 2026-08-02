# Orderbook Fleet — First Production Findings

What the monitoring endpoints actually said after the first unattended production run, and what
follows from it for the screener.

This is an **analysis** document, not a design document. `.claude/docs/orderbook-monitoring.md`
describes how the feature works and what every field means; this one records what the numbers were,
how to read them, and which of the design's stated assumptions survived contact with production.
Where the two disagree about an observed value, this document is the newer measurement (§9 lists the
corrections owed to the design doc).

Scope: `GET /api/monitoring/orderbook` and `GET /api/monitoring/orderbook/books` only. **The
trend ring (`/orderbook/history`) and the on-disk CSV capture are deliberately not covered** — §10
lists the questions they exist to answer.

---

## 0. The capture

| | |
|---|---|
| Source | `https://tc-screener.com`, production |
| Captured | 2026-08-02 08:05:12 UTC (a second capture; the first was 08:00:26 UTC) |
| JVM uptime | **~10.85 h** (median of per-book `updates / updatesPerSecond`; p25 9.41 h, p75 11.91 h) |
| Fleet | **886 books** — 359 SPOT, 527 FUTURES |
| Deployment | `-Xms512m -Xmx1g` on a 2 GB VPS (`deployment-guide.md`), `shard-count: 2`, `price-filter-threshold: 0.1` |
| Tool | `tools/analyze_orderbook_monitoring.py` |

The tool logs in, fetches the fleet endpoint in six parameter variants plus the whole per-book
table, saves the raw JSON, recomputes every statistic client-side, writes three figures and a text
report. `--raw-dir` re-analyses a saved capture without refetching. Output lands in
`monitoring-analysis/` (gitignored).

**`DescriptiveStats` is verified correct.** Recomputing the pooled size block from the raw
`/books` rows against the server's own `overall` block: n 886 vs 886, median 255.0 vs 254.5, mean
460.7 vs 460.6, skewness 15.323 vs 15.331. The R-7 percentile convention, the Bessel-corrected
variance and the adjusted Fisher–Pearson G1 all reproduce independently. The residual difference is
the ~1 s of drift between the `/orderbook` and `/books` requests, not a definitional disagreement.

---

## 1. The pipeline is healthy, and the health metrics are currently measuring nothing

```
by state          : {PENDING: 0, SNAPSHOT_REQUESTED: 0, SYNCED: 886}   syncedRatio 100.00%
stale / empty     : 0 stale, 0 empty
resyncs           : 0 total, across 0 of 886 books, since JVM start
resyncsLastHour   : 0
sampleAgeMs       : 424 ms        shardsStale: none
```

**Zero resyncs across 886 books over ~10.85 hours** is the strongest single result in this capture.
Every failure path funnels through `resync()` — sequence gap, futures `pu` discontinuity, parse
error, empty diff buffer after a snapshot, no valid sync point, and diff-buffer overflow (routed
through `resync()` specifically so it would be counted). None of them fired. The sync state machine,
the buffering-from-dispatch rule and the weight-budgeted snapshot fetching are correct in
production, not merely in principle.

The corollary is that `resyncsLastHour`, `staleBooks` and `shardsStale` are all pinned at their
healthy values and have never been exercised against a real fault. Keep them — they cost nothing —
but do not read a zero as evidence they *work*. The first real incident is also their first test.

`sampleAgeMs` in the low hundreds of ms with no stale shards confirms the 256-event
`CLOCK_CHECK_MASK` gate clears comfortably on both shards at this traffic level.

---

## 2. `stale-threshold-ms` is miscalibrated by roughly 4×

Idle time across all 886 SYNCED books:

| statistic | value |
|---|---|
| median | 0 s |
| p75 | 0 s |
| p90 | 0 s |
| p99 | 10 s |
| **max** | **55 s** |
| books idle > 30 s | 3 (0.3 %) |
| books idle > 60 s | **0** |
| books idle > 120 s | **0** |

Remember `idleMs` has `sample-interval-ms` (5 s) resolution, so "median 0 s" means "observed to
change within the last collector tick", not "changed this millisecond".

The threshold is **120 000 ms**. Nothing in an 886-book fleet comes within half of it. As configured
it cannot detect anything short of a total stream outage — by which point `shardsStale` and
`syncedRatio` would have fired anyway, so it contributes no independent signal.

The design doc records that the planned 30 s was raised to 120 s because "30 s flagged normal
illiquidity as a fault". **That does not hold against this sample**: 30 s would flag three books, and
60 s would flag none. Either the fleet composition changed since that call, or it was made during the
first minute or two of a run when both the EWMA and `idleMs` are still warming up and every book
looks quiet.

**Recommendation: `stale-threshold-ms: 45000`.** It sits comfortably above the observed p99 (10 s),
leaves headroom over the observed max (55 s) once the quietest spot symbols are accounted for, and
is low enough to actually fire before a partial outage becomes obvious elsewhere. Re-derive it from
the CSV distribution rather than from these two samples (§10).

---

## 3. Work concentration — the finding with the most consequences

`levelVisitsPerSecond` (`size × rate`, summed) is the pipeline's real unit of work, because every
applied diff makes `computeDistance()` walk that book's entire level set. The fleet total is
**539,058 level visits/s** against only **768 diffs/s**.

### 3.1 A handful of books are the fleet

| rank | book | size | rate | level visits/s | share of fleet work |
|---|---|---|---|---|---|
| 1 | BTCUSDT/FUTURES | 30,751 | 1.982/s | 60,933 | **11.2 %** |
| 2 | ETHUSDT/FUTURES | 21,735 | 1.987/s | 43,196 | 7.9 % |
| 3 | BNBUSDT/FUTURES | 7,490 | 1.923/s | 14,406 | 2.7 % |
| 4 | HYPEUSDT/FUTURES | 6,747 | 1.950/s | 13,155 | 2.4 % |
| 5 | BTCUSDT/SPOT | 11,416 | 0.987/s | 11,263 | 2.1 % |

| population | share of level visits | share of levels |
|---|---|---|
| top 1 % of books (8) | **31.5 %** | 23.7 % |
| top 5 % (44) | 51.5 % | 39.4 % |
| top 10 % (88) | 62.7 % | 49.0 % |
| top 25 % (221) | 80.1 % | 66.8 % |
| Gini | **0.737** | 0.576 |

BTC + ETH futures alone are **19.1 %** of everything the pipeline does.

### 3.2 Size and rate compound rather than cancel

Pearson correlation of `log(size)` against `log(rate)`:

| population | r |
|---|---|
| all SYNCED | **+0.701** |
| SPOT | +0.472 |
| FUTURES | +0.581 |

The deep books are also the busy books. Work is therefore *more* skewed than either factor alone —
Gini 0.737 on level visits against 0.576 on levels.

**This is the justification for `levelVisitsPerSecond` existing, stated in numbers.** Message count
would have reported BTCUSDT/FUTURES as 0.26 % of the load (1.98 of 768 diffs/s). It is 11.2 %. The
metric the design doc calls "the most useful number the feature produces" understates by 43× if you
use the obvious alternative.

---

## 4. Shard balance: the hash balances count and fails on work

| metric | shard 0 | shard 1 | max/min |
|---|---|---|---|
| books | 447 | 439 | **1.018×** |
| totalLevels | 166,770 | 241,343 | **1.447×** |
| updatesPerSecond | 376.4 | 391.6 | 1.040× |
| **levelVisitsPerSecond** | **207,510** | **331,508** | **1.598×** |
| largest book | BNBUSDT/FUTURES (7,488) | BTCUSDT/FUTURES (30,744) | |

Book count is near-perfect. Actual consumer-thread load differs by **60 %**, and the cause is
directly visible in the last row: BTCUSDT/FUTURES and ETHUSDT/FUTURES — 19.1 % of all work between
them — both hashed to shard 1. The balance of the whole system is decided by
`"BTCUSDT".hashCode() % 2`.

`updatesPerSecond` is balanced to 1.04× while real work is at 1.60×. This is exactly the trap
`ShardLoad`'s Javadoc warns about, confirmed with production data.

Two conclusions:

1. **Not urgent.** 269,529 level visits/s per consumer thread is not saturating a core, and a 1.6×
   spread between two unsaturated threads costs nothing.
2. **More shards will not fix it.** The imbalance is single-book granularity — one symbol is 11 % of
   the fleet. Splitting into 4 or 8 modulo buckets re-rolls the dice; it does not make the dice
   fairer. If rebalancing is ever needed it has to be **work-aware assignment** (e.g. greedy
   bin-packing on observed `size × rate`, recomputed rarely and applied only at startup or behind a
   quiesce, since a book's `TreeMap` is owned by its shard's thread). That is a real design change,
   not a config bump, and the data does not currently justify paying for it.

---

## 5. Book size — heavier than documented, and `mean` is worse than advertised

SYNCED books, size = `bids + asks`:

| population | n | median | IQR | mean | stdDev | mad×1.4826 | p90 | p99 | max | skew | shape |
|---|---|---|---|---|---|---|---|---|---|---|---|
| pooled | 886 | 254 | 280 | 461 | 1,438 | 191 | 738 | 3,762 | **30,756** | 15.3 | HIGH_RIGHT_SKEW |
| SPOT | 359 | 122 | 113 | 246 | 798 | 73 | 353 | 1,894 | 11,399 | 11.5 | HIGH_RIGHT_SKEW |
| FUTURES | 527 | 346 | 299 | 607 | 1,730 | 179 | 894 | 4,032 | 30,756 | 13.9 | HIGH_RIGHT_SKEW |

**The largest book is 30,756 levels.** The design doc quotes "the largest observed was ~13,900" —
the real figure is **2.2× that**, and 121× the fleet median. The doc's reasoning was right (books
grow until the ±10 % band saturates, there is no upper censoring) but its magnitude is stale.

**σ-inflation is worse than documented.** `stdDev / (mad × 1.4826)`:

| population | factor |
|---|---|
| pooled | **7.5×** |
| SPOT | **10.9×** |
| FUTURES | **9.7×** |

The doc says "typically 3–5×". It is 7–11×. `mean ± stdDev` here is not merely unreliable, it is
actively misleading: the pooled mean of 461 sits at the **79th percentile** of the distribution, so
"the average book" is larger than four fifths of all books. Read `median` and `iqr`; the guidance
was correct and should be stated more strongly.

Splitting by market helps but does not rescue anything — each market is separately, heavily
right-skewed (skew 11.5 and 13.9), because liquidity across symbols is itself heavy-tailed. The
spot/futures median gap is **2.8×** (122 vs 346), matching the doc's "roughly 3×".

Tukey fences over the pooled sample: 75 outliers (8.5 %), **all high, none low**, 37 extreme.

---

## 6. The bounded histogram domain was the right call, and `P99` is the right default

| domain | bins | binWidth | upper | sampleMax | overflow | empty bins | bins holding the modal half |
|---|---|---|---|---|---|---|---|
| `FULL` | 100 | 307.3 | 30,743 | 30,743 | 0 | **82** | **1** |
| `FENCE` | 15 | 54.9 | 839 | 30,743 | 75 | 0 | 4 |
| `P99` | 65 | 57.7 | 3,765 | 30,756 | 9 | 27 | 4 |

`FULL` reproduces the exact failure that motivated the change: **82 of 100 bins empty and half the
fleet inside a single bucket.** The chart is unreadable, precisely as described.

`P99` holds essentially the same bin width as `FENCE` (57.7 vs 54.9) while covering **4.5× the
range**, and pays only 9 books of overflow to do it. `FENCE` covers the body at good resolution but
its 15 bins are too few to show its shape, and it discards 75 books (8.5 %) to overflow.

The default is correct as chosen, and this table is the evidence.

---

## 7. Update rates confirm the stream contract — with 46 % headroom on paper

| population | n | median | mean | p10 | p90 | max | Σ |
|---|---|---|---|---|---|---|---|
| SPOT | 359 | 0.366 | 0.430 | 0.135 | 0.856 | **0.998** | 154.3 |
| FUTURES | 527 | 1.101 | 1.165 | 0.648 | 1.793 | **1.979** | 613.8 |
| pooled | 886 | 0.836 | 0.867 | 0.232 | 1.655 | 1.979 | 768.0 |

Both hard ceilings are respected exactly — spot tops out at 0.998/s against its 1/s stream, futures
at 1.979/s against its 2/s stream. No book exceeds its stream's cadence, which is itself a
correctness check on the diff-application path.

But almost nothing runs at the ceiling:

- SPOT: **19 of 359** books at ≥95 % of 1/s; **242** below half of it.
- FUTURES: **27 of 527** books at ≥95 % of 2/s; **212** below half of it.

Nominal worst case is `359×1 + 527×2 = 1,413` diffs/s. Observed is **768** — **54 % of the
theoretical maximum**. Binance only emits a diff when the book actually changed, so the slack is
real, but it is *market-condition-dependent slack*, not structural headroom. A market-wide
volatility event pushes the fleet toward 1,413 diffs/s and level visits toward ~1.0 M/s on the same
book sizes — and book sizes rise in volatility too, so the true spike is worse than linear.

**The pooled `updateRate` block reports `SYMMETRIC`, and that is meaningless.** It is the accidental
shape of a bimodal mixture of two differently-truncated populations. The doc's instruction to read
only `updateRateByMarket` is confirmed; the pooled block's `shape` field should be treated as noise.

---

## 8. Two secondary observations

### 8.1 Books are consistently, slightly bid-heavy

`(bids - asks) / size` over SYNCED books:

| population | median | bid-heavy | ask-heavy | exactly even |
|---|---|---|---|---|
| all | **+0.0222** | 557 | 293 | 36 |
| SPOT | +0.0101 | 191 | 147 | 21 |
| FUTURES | +0.0280 | 366 | 146 | 15 |

p10 −0.077, p90 +0.115; only 25 books (2.8 %) exceed ±0.20.

The price filter is symmetric (±10 % of mid), so a symmetric market would give a median of 0. A
persistent +2.2 % tilt means the bid side accumulates slightly more *distinct price levels* inside
the band — consistent with ask-side liquidity being sparser in level terms at these depths. It is
small, stable across both captures, and stronger in futures than spot. Read as market
microstructure, not as a bug — **unless** it turns out to track price direction over time, which
would instead implicate the mid-price sweep in `computeDistance()`. That is a CSV question (§10).

### 8.2 The fleet moved 1.2 % in 4.8 minutes

The two captures, 08:00:26 Z and 08:05:12 Z:

| | 08:00:26 Z | 08:05:12 Z | Δ |
|---|---|---|---|
| totalLevels | 403,225 | 408,113 | +4,888 (+1.2 %) |
| largest book | 30,434 | 30,756 | +322 |
| levelVisitsPerSecond | 544,395 | 539,058 | −1.0 % |

**Two points cannot separate drift from fluctuation.** If that +1.2 % were monotone growth it would
imply ~1.4 M levels/day, which is absurd; so it is almost certainly ordinary churn around a
saturated band. But "almost certainly" is not a measurement, and whether books are still growing
after 11 hours directly determines whether the capacity numbers in §9 are a steady state or a
floor. This is the single most important thing the CSV capture settles.

---

## 9. Capacity: memory binds before CPU

Current per-book averages: 461 levels, 608 level visits/s, 0.30 MB heap.

```
heap              : 0.27 GB used of 1.07 GB max (24.8%)
totalLevels       : 408,113
crude bytes/level : ~653 B   <- single GC-sawtooth reading, an UPPER bound
```

| scale | levels | level visits/s | heap | % of `-Xmx` |
|---|---|---|---|---|
| today (886 books) | 0.41 M | 0.54 M/s | 0.27 GB | 25 % |
| 2× books | 0.82 M | 1.08 M/s | 0.53 GB | 50 % |
| 3× books (a second exchange) | 1.22 M | 1.62 M/s | 0.80 GB | **74 %** |
| 5× books | 2.04 M | 2.70 M/s | 1.33 GB | **124 %** |

**You run out of heap before you run out of CPU.** Two consumer threads currently carry 269,529
level visits/s each; at 3× that is ~809 k/s per thread, which is demanding but plausible on the
existing design — whereas 3× heap is 74 % of `-Xmx` *before* GC headroom and before any
non-orderbook allocation. At 5× you are over the cap outright.

Two things follow:

- `CLAUDE.md`'s target of "~500 futures + a spot subset = 1000+ concurrent depth streams" is
  approximately what this 2 GB VPS can hold. At 886 books the **box** is the constraint, not the
  design. Growing the fleet meaningfully means a bigger `-Xmx` (and a bigger VPS) or fewer bytes per
  level.
- This is the concrete, quantified case for the "primitive-friendly order-book store to shed
  `TreeMap<Double,…>` boxing overhead" already listed under Future Work. At ~653 B/level, boxed
  `Double` keys plus `TreeMap.Entry` node overhead are a large share; halving it buys a whole
  exchange without touching the VPS.

The 653 B/level figure is an **upper bound from a single reading** sitting somewhere random on the GC
sawtooth. The honest number needs the lower envelope regressed against `totalLevels` — §10.

---

## 10. Corrections owed to the design doc

`.claude/docs/orderbook-monitoring.md` should be amended in these places. None of its *reasoning* is
wrong; the reference values are stale or were taken during warm-up.

| Location | Says | Should say |
|---|---|---|
| §5.1 caveat 2 | largest observed ~13,900 | **~30,756** |
| §5.1 caveat 3 | `stdDev` vs `mad × 1.4826` differ by 3–5× | **7–11×** (7.5× pooled, 10.9× spot, 9.7× futures) |
| §4.2 | IQR ~286, range ~13,900, ratio ~49:1 | IQR **280**, range **30,756**, ratio **~110:1** |
| §10.1 / §6 | `stale-threshold-ms` raised to 120 s because 30 s flagged illiquidity | not reproducible — observed max idle is **55 s**; see §2 |
| §3.2 | "the quietest healthy symbols legitimately go tens of seconds without a diff" | true, but the ceiling is ~55 s, not minutes |

---

## 11. What to act on now

1. **Lower `stale-threshold-ms` to 45000.** At 120 s the metric cannot fire. Confirm against the CSV
   idle distribution before committing.
2. **Update the five stale reference values in the design doc** (§10). The 13,900 figure in
   particular is load-bearing — it is the number the histogram-domain rationale is argued from.
3. **Do not add shards to address the 1.6× imbalance.** It is single-book granularity; more modulo
   buckets re-roll it rather than fix it. Revisit only if the CSVs show it sustained *and* a thread
   approaches saturation.
4. **Treat heap, not CPU, as the scaling constraint** in any second-exchange planning, and validate
   the bytes-per-level figure properly before sizing anything on it.
5. **Write the `DescriptiveStats` unit tests.** §10.2 of the design doc names this as the
   highest-value gap. The client-side reproduction in `tools/analyze_orderbook_monitoring.py`
   already agrees with the server to 3 decimal places on a real 886-point sample — that agreement is
   a ready-made reference vector, and it makes the tests cheap to write now.

---

## 12. Open questions — what the CSV capture is for

Everything above is **two point-in-time samples 4.8 minutes apart from a single ~11-hour JVM run**.
The rates are trustworthy (EWMA warm-up is long past), and anything about the *shape* of the fleet
at one instant is solid. But every question about **variability, drift, and the tails over time** is
unanswerable from static data, and those are exactly the questions that decide the actions in §11.

`books-*.csv`, `fleet-*.csv` and `shards-*.csv` — read
`.claude/docs/orderbook-monitoring.md` §8 before loading them, especially §8.5 on restarts, gaps and
warm-up.

### The questions, in rough priority order

1. **Do books still grow after 11 hours, or has the ±10 % band saturated?**
   §8.2 saw +1.2 % in 4.8 minutes and cannot tell drift from churn. If `totalLevels` still trends
   upward at hour 11, every capacity number in §9 is a floor rather than a steady state, and the
   heap forecast is optimistic. → `books-*.csv` pivoted on `(symbol, market)`, `growth.max() -
   growth.iloc[0]`, and `fleet-*.csv` `totalLevels` over the full window.

2. **What is the real bytes-per-level, and therefore the real second-exchange forecast?**
   The 653 B/level in §9 is one reading on the GC sawtooth. The answer is the *lower envelope* of
   `usedHeapBytes` (a 10-minute rolling min) regressed against `totalLevels` — slope is bytes per
   level, intercept is fixed baseline. This is the number that decides whether a primitive-friendly
   store is worth building. → `fleet-*.csv`.

3. **Is the 1.6× shard imbalance stable, or does it wander?**
   One sample cannot distinguish "BTC is permanently on shard 1, so the split is structurally 1.6×"
   from a transient. Sustained >1.5× is what would justify a work-aware assignment; a wandering
   ratio means the current mapping is adequate. → `shards-*.csv`,
   `w.max(axis=1) / w.min(axis=1)` on `levelVisitsPerSecond`, and the same on `books` as the control
   that should stay ~1.0.

4. **What is the true idle distribution over a full day, and hence the right `stale-threshold-ms`?**
   §2 recommends 45 s from a sample whose max is 55 s. Overnight and weekend illiquidity are exactly
   when quiet symbols go quietest, and neither is in this capture. The threshold should be set from
   the p99.9 of `idleMs` across a multi-day window, not from two daytime samples. → `books-*.csv`,
   `idleMs` percentiles by hour-of-day.

5. **Does anything ever resync — and what does a resync look like?**
   Zero resyncs in 11 hours means the counters have never been exercised. A multi-day capture that
   catches even a handful shows which symbols are fragile, whether resyncs cluster in time (a
   Binance-side event) or by symbol (a book-specific problem), and whether the recovery is clean. If
   a multi-day capture *also* shows zero, that is a stronger correctness result than this one. →
   `books-*.csv` `resyncs` deltas, masking negatives across restarts; `fleet-*.csv`
   `resyncsLastHour`.

6. **How far does a volatility event actually push the fleet?**
   §7 shows 54 % of the nominal diff ceiling at rest, but size and rate both rise together in
   volatility (§3.2, r = +0.70), so the peak is super-linear in a way one calm sample cannot bound.
   The peak `levelVisitsPerSecond` over a multi-day window — and what the heap did during it — is
   the only honest headroom figure. → `fleet-*.csv` maxima, joined to `books-*.csv` to see which
   symbols drove it.

7. **Does the +2.2 % bid tilt persist, and does it track price direction?**
   §8.1 read it as microstructure. If it is stable and direction-independent across days, that
   reading holds. If it swings with the market, it implicates the mid-price sweep instead and is a
   bug. → `books-*.csv`, `(bids - asks) / size` over time.

8. **Do rate and size vary by hour-of-day, and does the fleet composition change?**
   Ticker refresh adds and removes symbols. Ticks with fewer rows than their neighbours, and books
   appearing or disappearing from `books-*.csv`, show the churn — which also tells you whether the
   886-book figure is stable enough to plan capacity against.
