# Orderbook Fleet — Historical (CSV) Findings

What a full 10.5-hour capture says, and how it revises the conclusions drawn from two
point-in-time samples.

This is the companion to `.claude/docs/orderbook-monitoring-findings.md`, which analysed
`GET /api/monitoring/orderbook` at a single instant and closed with eight open questions (§12)
that only historical data could settle. **This document answers all eight.** Where the two
disagree, this one is the newer and far better-powered measurement — §9 lists the corrections
owed to both sibling docs, and three of them reverse a recommendation.

`.claude/docs/orderbook-monitoring.md` remains the reference for how the feature works and what
every column means; nothing here contradicts its design, and its §8.5 caveats (restarts, gaps,
warm-up) were applied throughout.

Tooling: `tools/analyze_orderbook_csv.py` (+ the shared loader `tools/obcsv.py`). It regenerates
every number and figure below from the raw CSVs:

```bash
python tools/analyze_orderbook_csv.py --dir monitoring-data/monitoring-data --out monitoring-analysis
```

---

## 0. The capture, and what it does *not* cover

| | |
|---|---|
| Window | **2026-08-01 20:59 Z → 2026-08-02 07:30 Z**, 10.5 h |
| Coverage | The **entire JVM run from cold start** — the fleet boots inside the capture |
| Volume | 559,062 book-rows (631 ticks × 886 books), 1,264 fleet ticks, 2,528 shard rows |
| Fleet | 886 books — 359 SPOT, 527 FUTURES; **stable for all 631 ticks** |
| Deployment | `-Xmx1g` on a 2 GB VPS, `shard-count: 2`, `price-filter-threshold: 0.1` |

**Integrity is perfect.** Zero JVM restarts (no cumulative counter ever stepped backwards), zero
gaps beyond 1.5× cadence in any of the three files, and 882 of 886 books present at every single
tick — the other four are SPOT books that registered one tick after startup. **No ticker churn
occurred**, which answers §12 Q8's composition half: the 886-book figure is stable enough to plan
capacity against, at least over half a day.

Because the JVM boots inside the window, the first hour is genuine cold-start behaviour rather
than a mid-run artefact. Everything fitted below drops it (`--warmup-hours 1.0`).

**The one thing this capture cannot tell you: peak-session load.** 21:00–07:30 UTC is the Asian
session and the US close — it excludes the London and New York opens entirely. Every load figure
in §6 is therefore an off-peak figure, and the volatility question (§12 Q6) is only partly
answered. A capture spanning 12:00–20:00 UTC is the missing half.

---

## 1. Zero resyncs, now at 200× the exposure

```
cumulative resyncs, max over all books : 0
sum of positive per-book resync deltas : 0
fleet resyncsLastHour, max over 1264 ticks : 0
exposure: 886 books x 10.5 h = 9,303 book-hours
diffs applied over the capture (sum of counter deltas): 27,880,892
```

The static analysis reported zero resyncs at one instant. This is the same result measured
**continuously across 9,303 book-hours and 27.9 million applied diffs**, with every intermediate
sample retained rather than inferred from endpoints. Every failure path funnels through
`resync()` — sequence gap, futures `pu` discontinuity, parse error, empty diff buffer after a
snapshot, no valid sync point, diff-buffer overflow. None fired, once, on any book, at any time.

This upgrades the sync state machine from "correct at the two moments we looked" to "correct over
a continuous multi-hour run covering a full cold start and 28 M messages". It is the strongest
correctness result the project has.

The corollary from the static analysis stands and now stands harder: `resyncsLastHour`,
`staleBooks` and `shardsStale` remain **completely unexercised as fault detectors**. A zero from
them is not evidence they work.

---

## 2. Books saturate — and fleet growth is ten symbols, not the fleet

**This is §12 Q1, and the answer is "both, depending on which book you mean."**

![growth](../../monitoring-analysis/growth.png)

Fleet `totalLevels` is still rising at hour 10.5, but the rise is decelerating hard:

| linear fit from | slope |
|---|---|
| h > 1 | **+5,114** levels/h |
| h > 2 | +4,947 |
| h > 4 | +4,313 |
| h > 6 | +2,625 |
| h > 8 | **+1,968** |

A saturating model `L(t) = L∞ − A·e^(−t/τ)` fits substantially better than a straight line
(RMSE 2,363 vs 3,685):

```
L_inf = 426,833 levels    A = 80,429    tau = 7.30 h
observed at end 405,144 -> 5.4% remaining to the asymptote
projection: 12h 411,298   24h 423,832   48h 426,721   72h 426,828
```

So the ±10 % band does saturate, with a ~7.3 h time constant, and the fleet was ~95 % of the way
there when the capture ended. **The capacity numbers are a steady state, not a floor** — but they
need a ~5 % uplift and a full day to fully settle, not the 11 hours the static analysis had.

The decomposition is the more interesting half. Comparing each book's hour-1–2 mean against its
final-hour mean:

| | |
|---|---|
| fleet total | 364,420 → 405,327 (**+11.2 %**) |
| median book | **+0.5 %** |
| books that *shrank* | **410 of 886** |
| share of all fleet growth from the top 10 growers | **96.6 %** |

| book | early | late | growth | % |
|---|---|---|---|---|
| BTCUSDT/FUTURES | 19,080 | 29,991 | +10,912 | +57 % |
| ETHUSDT/FUTURES | 14,249 | 21,539 | +7,290 | +51 % |
| BTCUSDT/SPOT | 4,201 | 11,180 | +6,979 | **+166 %** |
| ETHUSDT/SPOT | 3,536 | 8,671 | +5,135 | **+145 %** |
| BNBUSDT/FUTURES | 3,626 | 7,294 | +3,668 | +101 % |

**The typical book saturated within the first hour and has been oscillating ever since.** The
"fleet is still growing" signal comes entirely from a handful of majors whose bands are so wide
in absolute terms that filling them takes many hours of price wandering. In the final two hours
326 books were still growing, 299 were shrinking and 261 were flat — churn, not drift, for
everything outside the top 10.

Practical consequence: **fleet-level `totalLevels` is a misleading capacity signal.** It tracks
BTC and ETH. Adding 500 mid-cap symbols will not move it the way the last 10.5 hours suggest,
because mid-caps saturate almost immediately.

---

## 3. Memory: 230 B/level, not 653 — and heap is *not* the binding constraint

**This is §12 Q2, and it reverses the static analysis's headline conclusion.**

![heap](../../monitoring-analysis/heap.png)

The 653 B/level figure in the static findings was a single `usedHeapBytes` reading divided by
`totalLevels`. The left panel shows why that cannot work: over the run the sawtooth spans
**128 MiB** peak-to-trough, so a single reading lands somewhere arbitrary and the derived
"bytes per level" is mostly a statement about where GC happened to be.

Regressing the **10-minute rolling minimum** (the live-set estimate) against `totalLevels`:

```
heap_live = 86 MiB + 230 B/level
bootstrap 95% CI on the slope: [199, 257] B/level  (2000 resamples)
```

The slope is stable across every envelope window tried (5/10/20/30 min → 232/230/232/226 B/level),
which is the main reason to believe it.

**Two caveats that matter for how you use this number:**

1. `totalLevels` only spans 356,811–406,224 — a 14 % range. The *slope* is well constrained; the
   *intercept* is a long extrapolation back to zero and should not be quoted as "fixed overhead"
   with any confidence.
2. 230 B/level is the **marginal** cost of one more level. It is not the total attribution: some
   of the 86 MiB baseline is certainly orderbook-related (map skeletons, symbol strings, the
   registry). A pessimistic bound that charges the *entire* live set to levels gives
   **473 B/level**.

Forecast, bracketing both:

| scale | levels | marginal model | pessimistic bound | % of 1 GiB `-Xmx` |
|---|---|---|---|---|
| today | 405 k | 0.17 GiB | 0.18 GiB | 17 % / 18 % |
| 2× | 810 k | 0.26 GiB | 0.36 GiB | 26 % / 36 % |
| 3× (a 2nd exchange) | 1.22 M | 0.34 GiB | 0.53 GiB | 34 % / **53 %** |
| 5× | 2.03 M | 0.52 GiB | 0.89 GiB | 52 % / **89 %** |
| 8× | 3.24 M | 0.78 GiB | 1.43 GiB | 78 % / **143 %** |

Compare the static analysis's forecast, which had 3× at **74 %** of `-Xmx` and 5× at **124 %** —
i.e. "you run out of heap before CPU, and a second exchange nearly fills the box." **That is
wrong.** Even on the pessimistic bound, a second exchange sits at ~53 % of the current heap, and
on the marginal model at ~34 %.

**Three things follow, and they change the roadmap:**

- **Heap is not the scaling constraint at 3×.** Action 4 of the static findings ("treat heap, not
  CPU, as the scaling constraint") is retracted.
- **The primitive-friendly orderbook store is much weaker as a capacity argument.** At ~653 B/level
  it bought "a whole exchange without touching the VPS". At 230 B/level marginal it buys far less,
  and the box has room anyway. It may still be worth doing for GC pressure and cache locality —
  the 128 MiB sawtooth over 10 minutes is real allocation churn — but that is a *latency and GC*
  argument, not the capacity argument that was made for it. Re-justify before building.
- **The real constraint is now unmeasured** — see §6.

---

## 4. The shard imbalance is structural, permanent, and widening

**This is §12 Q3, and the answer is unambiguous: structural.**

![shards](../../monitoring-analysis/shards.png)

| metric | shard 0 | shard 1 | max/min (mean) | max/min (range) |
|---|---|---|---|---|
| books | 447 | 439 | **1.018×** | 1.018–1.018 |
| totalLevels | 162,810 | 225,025 | 1.381× | 1.295–1.445 |
| updatesPerSecond | 363 | 376 | 1.038× | 1.000–1.109 |
| **levelVisitsPerSecond** | **197,718** | **301,171** | **1.522×** | **1.395–1.666** |

**Shard 1 is the busier shard on 100.0 % of the 1,143 measured ticks.** Not a transient. And the
gap is *growing* monotonically:

| hour (UTC) | 21 | 22 | 23 | 00 | 01 | 02 | 03 | 04 | 05 | 06 | 07 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| work ratio | 1.43 | 1.45 | 1.46 | 1.46 | 1.47 | 1.52 | 1.57 | 1.57 | 1.58 | 1.59 | **1.60** |

The mechanism is visible in §2: shard 1 owns BTCUSDT/FUTURES (largest book on all 1,143 of its
ticks) and ETHUSDT/FUTURES, and *those are precisely the two books still growing*. The imbalance
widens because the hash put the fleet's growth on one thread. Extrapolating the saturation fit,
it should stabilise near ~1.6–1.65× rather than keep climbing.

Book count stays pinned at 1.018× throughout — a textbook demonstration of `ShardLoad`'s own
warning, now with 1,143 samples instead of one.

**The static analysis's recommendation still holds, for its stated reason.** Don't add shards: the
imbalance is single-book granularity (BTCUSDT/FUTURES alone is 10.1 % of fleet work), so more
modulo buckets re-roll the dice rather than load them fairly. What has changed is that we now know
it is permanent, so if a thread ever *does* approach saturation, work-aware assignment is the only
fix that will work — and the data to drive it (`size × rate` per book) is exactly what this
capture already produces.

---

## 5. `stale-threshold-ms`: keep 120 s — the 45 s recommendation was wrong

**This is §12 Q4, and it reverses action 1 of the static findings.**

![idle](../../monitoring-analysis/idle.png)

The static analysis saw a max idle of 55 s across two daytime samples and recommended lowering
the threshold from 120 s to 45 s. Over 505,020 SYNCED observations spanning 9.5 hours:

| population | p50 | p90 | p99 | p99.9 | p99.99 | **max** |
|---|---|---|---|---|---|---|
| ALL | 0 s | 5 s | 20 s | 60 s | 100 s | **180 s** |
| SPOT | 0 s | 10 s | 35 s | 70 s | 113 s | **180 s** |
| FUTURES | 0 s | 0 s | 5 s | 15 s | 20 s | **65 s** |

The observed maximum is **180 s — 3.3× the 55 s the recommendation was derived from.** What each
candidate threshold would actually fire on:

| threshold | observations | % of all | distinct books | distinct ticks |
|---|---|---|---|---|
| 30 s | 2,179 | 0.43 % | 151 | 505 |
| **45 s** | **1,016** | **0.20 %** | **128** | **418** |
| 60 s | 411 | 0.081 % | 80 | 264 |
| 90 s | 68 | 0.014 % | 22 | 63 |
| **120 s** | **11** | **0.0022 %** | **4** | **11** |
| 180 s | 0 | 0 % | 0 | 0 |

At 45 s the metric would have flagged **128 distinct books on 418 separate ticks** during a run
with zero faults — pure false-positive noise from illiquid spot symbols. The books it would flag
are exactly what you would expect: `USTCUSDT/SPOT`, `BANDUSDT/SPOT`, `QTUMUSDT/SPOT` and friends,
all with mean update rates of **0.01–0.03/s** and books of ~70–200 levels. They are not broken;
they are quiet.

Meanwhile 120 s is **not** dead as the static analysis claimed — it fired on 11 observations
across 4 books, 1.4 % of fleet ticks having a non-zero `staleBooks`. It sits just above the
p99.99 and below the observed max, which is a defensible place for a fault threshold.

**The design doc's original justification was right and the static analysis's rebuttal was
wrong.** 30 s *does* flag normal illiquidity; the two-sample capture simply didn't contain the
tail. **Keep `stale-threshold-ms: 120000`.**

The distribution does suggest a real improvement, though: **idleness is almost entirely a SPOT
phenomenon.** Futures p99.99 is 20 s and its max is 65 s — a futures book idle for 90 s is a
genuine anomaly that the shared 120 s threshold will never catch. A per-market threshold
(SPOT 120 s, FUTURES 60 s) would give a strictly better detector at both ends. That is a small,
well-evidenced change to `MonitoringProperties` and `OrderBookStatsCollector`.

Hour-of-day makes no material difference across the covered hours (p99 sits at 15–25 s in every
one of them), but note again that this window has no London or New York session in it.

---

## 6. Load, headroom, and the metric that is now missing

**This is §12 Q6, partly answered.**

| metric | mean | p05 | p50 | p95 | max | peak/mean |
|---|---|---|---|---|---|---|
| updatesPerSecond | 739 | 694 | 730 | 804 | **1,097** | 1.49 |
| levelVisitsPerSecond | 498,888 | 449,608 | 507,999 | 532,911 | **592,213** | 1.19 |
| totalLevels | 387,835 | 364,742 | 392,371 | 405,490 | 406,224 | 1.05 |

Nominal ceiling is `359×1 + 527×2 = 1,413` diffs/s. The static analysis observed 768 (54 %) and
concluded there was "46 % headroom on paper". The mean here agrees (739, 52 %) — but the **peak
reached 1,097 diffs/s, 77.7 % of the ceiling**, in a single calm overnight window with no
volatility event in it.

**That is a much tighter picture than "46 % headroom".** Real headroom above the observed peak is
1.29×, not 1.85×, and this window excludes both major trading sessions.

Work growth over the run decomposes cleanly:

```
first 30 min of the measured window -> last 30 min
  levels  362,028 -> 405,656   (+12.1%)
  rate        729 ->     733   ( +0.5%)
  work    452,097 -> 528,290   (+16.9%)
```

**Work grew 17 % while message rate was flat.** This is `levelVisitsPerSecond` earning its keep
exactly as designed: a message-count-based monitor would have reported this fleet as perfectly
steady while its actual consumer-thread load rose by a sixth.

Work concentration is unchanged from the static sample, now time-averaged:

| population | share of work | share of levels |
|---|---|---|
| top 1 % (8 books) | 28.8 % | 19.9 % |
| top 5 % (44) | 49.5 % | 36.1 % |
| top 25 % (221) | 79.1 % | 64.1 % |
| Gini | **0.725** | 0.554 |

`r(log size, log rate) = +0.747` — size and rate still compound. BTCUSDT/FUTURES alone is 10.1 %
of fleet work; BTC + ETH futures together are 17.6 %.

### The gap this opens

§3 removed heap as the 3× constraint. Peak diff rate has 1.29× headroom on an off-peak window.
So what actually binds first?

**We cannot currently tell, and that is the most actionable finding here.** The monitoring feature
measures how much work the consumers are *asked* to do (`levelVisitsPerSecond`) but nothing about
whether they are *keeping up*. There is no CPU metric, no consumer latency, and — most usefully —
**no Disruptor ring-buffer occupancy**. `RingBuffer.remainingCapacity()` per shard is one cheap
call on the collector's 5 s tick, off the hot path entirely, and it is the direct backpressure
signal: a consumer falling behind shows up as remaining capacity collapsing, long before anything
else in the fleet response moves. Given that shard 1 permanently carries 1.6× shard 0's load,
knowing how close either is to saturation is the difference between "3× is fine" and "3× melts."

**Recommendation: add per-shard ring-buffer remaining capacity to `ShardLoad` and the CSV.** It is
the cheapest metric in the feature and currently the only one that could answer the scaling
question.

---

## 7. The bid tilt is a fill artifact, not a bug

**This is §12 Q7. The static analysis flagged that if the tilt tracked price direction it would
implicate `computeDistance()`'s mid-price sweep. It doesn't.**

The sweep band is symmetric in absolute price — `computeDistance` uses
`lower = mid*(1-threshold)`, `upper = mid*(1+threshold)` and applies both bounds to both sides
(`OrderBook.java:379-406`), so it cannot manufacture a side preference.

The observed tilt `(bids - asks) / size`:

- fleet median **+0.030**, ranging +0.007 to +0.077 over the run (the static sample's +0.022 was
  one point on a moving quantity, not a constant);
- 654 of 886 books positively tilted;
- strongly autocorrelated — 0.944 at 1 min, 0.704 at 10 min, 0.436 at 1 h — so it drifts on an
  hours timescale rather than fluctuating;
- but **only 24.8 % of its variance is a common market factor** (PC1 across 886 tilt series),
  with 81 % of books positively correlated to the fleet median. Mostly idiosyncratic.

The decisive evidence is that tilt tracks each book's *own fill*, not the market:

| book | mean size | mean bids | mean asks | mean tilt | r(size, tilt) |
|---|---|---|---|---|---|
| BTCUSDT/SPOT | 8,041 | 5,651 | 2,390 | **+0.370** | **+0.907** |
| ETHUSDT/SPOT | 6,480 | 4,282 | 2,198 | **+0.307** | +0.699 |
| BTCUSDT/FUTURES | 25,383 | 13,911 | 11,472 | +0.090 | +0.777 |
| HYPEUSDT/FUTURES | 6,337 | 3,168 | 3,169 | −0.000 | — |

BTCUSDT/SPOT went from tilt +0.21 to +0.45 *as it grew from 4.2 k to 11.2 k levels* — the same
book, same symmetric band, correlation +0.907 between its size and its tilt.

The mechanism: a book is seeded by a REST snapshot capped at 1,000 levels/side and then fills
outward only as live diffs touch previously-unseen prices. Which prices get touched depends on
where the mid has wandered. A book still filling therefore carries an imprint of recent price
travel, and the deepest books — the ones with the widest absolute bands and the longest fill
times — carry the largest imprint. Books that have finished filling (HYPEUSDT/FUTURES, tilt
−0.000) show none.

**Read as expected behaviour of a partially-filled book, not as a defect.** It is worth
re-checking on a capture where the majors have fully saturated: if a saturated BTCUSDT/SPOT still
sits at +0.37, that would be a real microstructure claim rather than a fill artifact — and worth a
second look at the sweep.

---

## 8. What this capture settles, question by question

| §12 question | answer |
|---|---|
| 1. Do books still grow after 11 h? | **Saturating**, τ 7.3 h, ~5 % to go. But the *median* book saturated in hour 1; 96.6 % of fleet growth is 10 symbols. |
| 2. Real bytes per level? | **230 B/level marginal** (95 % CI 199–257), 473 B/level pessimistic bound. Not 653. |
| 3. Is the shard imbalance stable? | **Structural and widening** — 1.43× → 1.60×, same shard busier on 100 % of ticks. |
| 4. Right `stale-threshold-ms`? | **Keep 120 s.** Observed max idle 180 s. 45 s would fire on 128 healthy books. Consider a per-market split. |
| 5. Does anything resync? | **No.** Zero across 9,303 book-hours and 27.9 M diffs. |
| 6. Volatility headroom? | Partly. Peak 1,097 diffs/s = **77.7 % of ceiling** off-peak. Real headroom is 1.29×, not 1.85×. Session hours not covered. |
| 7. Bid tilt — microstructure or bug? | **Neither: a fill artifact.** Tracks each book's own filling (r up to +0.91), not price and not the sweep. |
| 8. Composition churn? | **None.** 886 books stable for all 631 ticks; 882 present at every tick, 4 registered one tick late. |

---

## 9. Corrections owed to the sibling docs

### 9.1 `orderbook-monitoring-findings.md` (the static analysis)

| Location | Says | Should say |
|---|---|---|
| §9, §11.4 | ~653 B/level; heap binds before CPU; 3× = 74 % of `-Xmx`, 5× = 124 % | **230 B/level marginal**, 473 B/level pessimistic; 3× = 34–53 %; **heap does not bind at 3×** |
| §2, §11.1 | Lower `stale-threshold-ms` to 45000; observed max idle 55 s; 120 s "cannot fire" | **Keep 120000.** Observed max idle **180 s**; 120 s fired on 4 books / 11 ticks; 45 s would fire on 128 healthy books |
| §7 | 54 % of the diff ceiling ⇒ "46 % headroom" | Mean 52 %, but **peak 77.7 %** — real headroom 1.29×, and no trading session is in the window |
| §8.1 | Bid tilt +2.2 %, stable, read as microstructure | Fleet median **+3.0 %**, varies +0.7 % to +7.7 %; it is a **fill artifact** (r(size,tilt) up to +0.91) |
| §4 | 1.6× shard imbalance, "not urgent, may be transient" | Confirmed **permanent** (100 % of ticks) and **widening** 1.43 → 1.60. Still not urgent; the "don't add shards" conclusion stands |
| §11.3 | Revisit shards only if the CSVs show it sustained | It **is** sustained. Revisit gate is now solely "does a thread approach saturation" — which needs §6's new metric |

### 9.2 `orderbook-monitoring.md` (the design doc)

Its reasoning is intact; two reference values move:

| Location | Says | Should say |
|---|---|---|
| §3.2 | `stale-threshold-ms` raised to 120 s because 30 s flagged illiquidity | **Correct, and now evidenced** — this reinstates the claim the static findings disputed |
| §5.1 caveat 2 | largest observed ~13,900 levels | **~30,000** (BTCUSDT/FUTURES peaked at 29,991 in this capture) |

---

## 10. What to act on

1. **Retract the heap-bound capacity story.** A second exchange fits comfortably. Re-justify the
   primitive-friendly orderbook store on GC/latency grounds (the 128 MiB/10 min sawtooth) or shelve
   it — the capacity argument that motivated it does not survive at 230 B/level.
2. **Add per-shard `RingBuffer.remainingCapacity()`** to `ShardLoad`, the fleet response and
   `shards-*.csv`. One call per shard per 5 s collector tick, off the hot path. It is the only
   missing piece needed to answer "what binds at 3×", and shard 1's permanent 1.6× load makes it
   the metric that matters most.
3. **Keep `stale-threshold-ms: 120000`** — do not apply the static analysis's action 1. Optionally
   split it per market (SPOT 120 s / FUTURES 60 s); the futures distribution tops out at 65 s, so
   the shared threshold is blind to genuine futures stalls.
4. **Capture a London/NY session** before trusting any headroom number. This window peaked at
   77.7 % of the nominal diff ceiling *overnight*.
5. **Stop reading fleet `totalLevels` as a fleet-capacity signal.** It tracks BTC and ETH filling
   their bands. Per-book median size is the number that generalises to "what happens if we add
   500 symbols".
6. **Write the `DescriptiveStats` unit tests.** Unchanged from the static findings' action 5 —
   still the highest-value gap, still unaddressed.

---

## 11. Reproducing this

```bash
# full analysis + four figures + report.txt
python tools/analyze_orderbook_csv.py

# custom capture directory / output / warm-up trim
python tools/analyze_orderbook_csv.py --dir path/to/csvs --out out/ --warmup-hours 1.5 --no-plots
```

`tools/obcsv.py` is the reusable loader — `load_all()`, `add_measured_rate()` (restart-safe
counter deltas), `detect_restarts()`, `tick_grid()` (gap report), `lower_envelope()` and `gini()`.
Import it for ad-hoc work rather than re-deriving the §8.5 caveats.

Requires `pandas`, `numpy`, `matplotlib`; `scipy` is optional and only used for the saturation fit.
Figures land in `monitoring-analysis/` (gitignored), palette per `.claude` dataviz reference
(categorical slots 1–3, validated for CVD separation).
