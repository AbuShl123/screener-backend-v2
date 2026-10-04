# Pipeline Benchmark — 2.5-hour Binance-only run, 2026-09-08

**What this file is**: the measured baseline for the Binance-only pipeline as it stands after the
P2-step-2 sync SPI refactor, taken from a single uninterrupted 2h33m local run. It records what the
numbers were, what caused them, and what each one implies for the multi-exchange work.

**Why keep it**: three of the conclusions below (recovery throughput, the heap-growth mechanism, the
weight-budget ceiling) are design constraints for P3/P4, not trivia. They are expensive to
re-derive and easy to re-litigate from intuition. The run also happens to contain a real
mass-disconnect event, which is not something we can reproduce on demand.

**Related reading**: `.claude/docs/multi-exchange-progress.md` §7–§8 (the sync algorithm these numbers
exercise), `.claude/plans/multi-exchange-architecture-vision.md` §6–§8 (the seams these numbers
size).

---

## 0. Run identity and method

| | |
|---|---|
| Date | 2026-09-08 22:48:29 → 2026-09-09 01:21:24 (+05:00) |
| Duration | 9175 s = **2 h 33 m**, single continuous process, PID 15308 |
| Code | working tree at commit `43546b4` ("Replace the sync-count log with a pipeline health line"), branch `feature/multi-exchange`. Launched from `target/classes` via IntelliJ. |
| Profile | `local` |
| Runtime | Java 21.0.10, Spring Boot 4.0.6, G1, `-Xmx512m` / `-Xms508m`, 24 logical CPUs, 32 GB RAM |
| Universe | 878 instruments — 353 Binance spot + 525 Binance futures |
| Transport | 2 spot connections, 3 futures connections; spot `@depth` (1 s), futures `@depth@500ms` |
| Shards | 2, ring buffer 65536 slots each |
| Snapshot queue | 10 deep per market, 6 s dispatch, 5 s settle delay, `limit=1000` |
| Location | Tashkent, consumer internet. **Not** the Japan production box. |

**Data sources**: `gc.log` (1463 lines, `-Xlog:gc`) and a full console capture (2554 lines, 290
`PipelineHealthLogger` samples at 30 s, `DEBUG` enabled on the sync strategies).

**Caveat that shapes everything below**: the WebSocket disconnects in this run are a local-network
artefact and do not occur on the production machine in Japan. That makes them a *gift*, not a
defect — they are the only mass-failure event we have observed, and the pipeline's response to them
is the most valuable thing in the dataset. Read the disconnects as a fault-injection experiment we
did not have to write.

---

## 1. Verdict

**The pipeline is healthy, and — unlike the earlier 24-minute sample — it has now been stressed.**

- Spot: **zero resyncs in 2 h 33 m**, 100.00% book availability.
- Futures: 96.02% post-ramp availability; **all** 1083 resyncs attributable to 16 WebSocket
  disconnects, none to the sync logic.
- Zero `ERROR` lines, zero Full GCs, zero diff-buffer overflows, zero slow drains, zero ring
  backpressure, zero rate-limit or snapshot-fetch failures.
- 0.085% of wall time in stop-the-world GC.
- Every failure event recovered fully and autonomously.

The rewritten `check()` predicates (progress doc §4.5/§4.6) are validated against roughly 7.7
million real Binance sequence-numbered events across 878 books: not one desync arose from a
sequence rule.

What the run *does* establish, that the short run could not, is a hard throughput limit on
**recovery** — see §4. That is the finding with consequences.

---

## 2. Steady-state numbers

Post-ramp (from 22:55, excluding the initial 6-minute ramp): 278 samples, 139 minutes.

| Metric | Value |
|---|---|
| Spot book availability | **100.00%** (0 windows below 353/353) |
| Futures book availability | **96.02%** (23 windows below 525/525, 11.5 min of 139) |
| Spot resyncs, whole run | **0** |
| Futures resyncs, whole run | 1083 |
| Ingest rate | mean **844 msgs/s**, range 611–1026 |
| Shard balance | shard0 410 /s, shard1 422 /s — **3.1% skew** |
| Ring free | never below **65535 of 65536** |
| Feed drain | max **34 ms** of the 100 ms budget; `slow=0` throughout |
| CPU | 366 CPU-s over 1514 s wall = **24% of one core**, 1.0% of the machine |

### Implications

- **`id & (shardCount - 1)` routing is confirmed even.** 3.1% skew over 878 dense ids. The vision's
  §2.3 claim ("perfectly even distribution instead of hash-luck") holds empirically. No reason to
  revisit shard routing at P4.
- **Ingest is nowhere near a bottleneck.** The ring never dropped below 1 slot in use out of 65536,
  and the whole application costs a quarter of one core. The §8.1 concern about a blocking
  `rb.next()` stalling a reader thread is real in principle but is *many* orders of magnitude away
  at Binance-only scale. It becomes relevant at 5 venues because of message volume, not because
  anything here is close.
- **`ring-buffer-size: 65536` is enormously oversized.** Not worth changing (the slots hold one
  reference each), but do not treat it as a tuned value when sizing rings for other venues.
- **Observed 844 msgs/s against a theoretical ~1400** (353×1/s + 525×2/s). Binance only emits a
  frame when the window contained a change, and an illiquid 525-symbol futures universe is mostly
  quiet. Corroborated in §6.3: all 525 futures symbols proved liveness by resyncing. Still not
  *directly* observable — see §8.

---

## 3. The GC picture, and why the sawtooth grew

### 3.1 Headline

| Metric | Value |
|---|---|
| Pauses | 1050 |
| Total STW | 7.83 s = **0.085% of wall time** |
| `Pause Young` | n=637, avg 10.0 ms, p99 30.9 ms, **max 35.3 ms** |
| `Pause Remark` | n=208, avg 6.7 ms, max 22.3 ms |
| `Pause Cleanup` | n=208, avg 0.23 ms |
| **Full GC** | **0** |
| Concurrent mark cycles | 207 — one per 45 s |
| Allocation rate | **~4.9 MB/s**, flat for the entire run |

### 3.2 The sawtooth: same slope, bigger tooth

The heap traces a repeating tooth: rise from a floor, collect, return to the same floor. Over the
run the tooth got taller and wider but **the floor never moved and the slope never changed**:

| Window | heap cap | peak | floor | period | slope (= alloc rate) |
|---|---|---|---|---|---|
| 10–30 min | 179 M | 162 M | 104 M | 11.2 s | 5.20 MB/s |
| 30–60 min | 182 M | 165 M | 105 M | 12.5 s | 4.80 MB/s |
| 60–90 min | 221 M | 200 M | 106 M | 17.0 s | 5.11 MB/s |
| 90–120 min | 221 M | 200 M | 105 M | 20.1 s | 4.77 MB/s |
| 120–153 min | 225 M | 203 M | 106 M | 19.2 s | 4.95 MB/s |

58 M over 11.2 s and 97 M over 19.2 s are the *same* 5 MB/s. G1 enlarged the young generation, so
more allocation fits between collections; nothing about the application changed.

**The floor — the post-collection live set — is the leak test, and it is flat at 104–106 M from
minute 25 to minute 153.** There is no leak.

### 3.3 What enlarged the heap: the disconnect storm

Heap capacity crept 179 → 182 M over 38 quiet minutes, then moved in a single burst:

```
23:48:25   cap 182 -> 184M      <- first disconnect at 23:48:23
23:48:55       184 -> 192M
23:49:25       192 -> 196M
23:50:19       196 -> 202M
23:51:26       202 -> 205M      <- live set peaks at 121M
23:51:50       205 -> 206M
23:52:02       206 -> 209M
23:53:03       209 -> 212M
23:54:21       212 -> 216M
23:55:55       216 -> 218M
23:57:24       218 -> 221M      <- last book re-synced at 23:57:38
           [ flat at 221M for 65 minutes ]
01:02:06       221 -> 225M      <- tail of the isolated 01:00:21 reconnect
```

**Every heap expansion in the run falls inside a recovery burst.** The mechanism: books in
`RECOVERING` hold a diff buffer, and those buffers plus in-flight 1000-level snapshot JSON survive
long enough (seconds to minutes, against an 11–19 s young-GC period) to be promoted. Post-GC
occupancy rose 105 → 121 M during the storm. G1 responded to the rising live set and promotion
pressure by expanding, and never gave the memory back because `MaxHeapFreeRatio=70` only shrinks
below 30% occupancy — the heap sits at 47%.

Allocation *rate* barely moved during the storm, which confirms the expansion was driven by
**promotion**, not by throughput:

| Phase | alloc rate |
|---|---|
| quiet, pre-storm (60 min) | 4.94 MB/s |
| reconnect storm + recovery | 5.33 MB/s |
| quiet, post-storm (60 min) | 4.88 MB/s |
| isolated reconnect + recovery | 5.19 MB/s |
| quiet, tail (21 min) | 4.93 MB/s |

### 3.4 Implications

- **A rising sawtooth ceiling with a flat floor is not a leak** and should not be investigated as
  one. Recorded so the next person watching the IntelliJ heap chart does not spend a day on it.
- **The bounded snapshot queue is empirically load-bearing for memory, exactly as vision §6.1
  claims.** Only `RECOVERING` books buffer diffs, and only 10 per market can be `RECOVERING` at
  once: 10 × `MAX_BUFFER_SIZE` 500 × ~2 KB ≈ 10 MB, plus snapshot bodies. **A total futures outage
  cost 16 MB of transient heap.** Unbounded, the same event would have been ~500 books × 500 diffs
  ≈ half a gigabyte. Any future change that widens the queue must re-derive this number.
- **~4.9 MB/s at 844 msgs/s is ~6 KB per message** — which is approximately the inbound frame,
  twice (java-websocket's UTF-8 `byte[]` plus the `String` it hands the callback). The parse-and-
  apply path contributes almost nothing on top. **The hot-path austerity rules are working**, and
  vision §7 is right that the remaining allocation is the frame itself. Corollary: byte-buffer
  frame reuse is the only optimisation that would move this number; nothing inside `applyLevel`
  will.
- **Pauses grew as a direct cost of the larger young gen** (3.9 ms avg / 9.1 ms max in the first
  24 minutes → 10.0 ms / 35.3 ms now). With `MaxGCPauseMillis` at its 200 ms default, G1 has no
  reason to prefer smaller-and-faster. 35 ms against a 100 ms broadcast loop is harmless; it is
  noted so the growth is not mistaken for degradation later.
- **Density is fine and P6 is not urgent.** 105 M live for 878 books, flat over 2.5 hours.
  Extrapolating to ~5000 books gives roughly 350–400 MB of live heap — well under vision §7's
  feared 500 MB–1 GB *for the level maps alone*. Keep the read-side accessor seam on the P3 list;
  leave the primitive-array rewrite deferred.
- **`-Xmx512m` will not survive P4+.** Budget ~1 GB and re-measure once a second venue is live.

---

## 4. The disconnect storm — the most valuable part of the run

### 4.1 What happened

Sixteen `code=1006` remote closes, **all on futures, none on spot**:

```
22:49:39   1 drop during the initial ramp        -> 13 resyncs
23:48:23 -+
   ...    | 14 drops in 4 m 50 s across all 3 futures connections
23:53:13 -+                                      -> ~1040 resyncs
01:00:21   1 isolated drop                       -> 175 resyncs
```

Coverage during the storm, from the 30-second health line:

```
23:48:38   fut=368/525   resyncs=175
23:49:08   fut=201/525   resyncs=217
23:49:38   fut= 25/525   resyncs=226     <- 95% of futures coverage gone
23:50:38   fut= 17/525   resyncs=100     <- floor: 3%
23:51:38   fut= 84/525   resyncs= 33
23:53:38   fut=148/525   resyncs=136     <- last drop's damage lands
23:55:38   fut=348/525   resyncs=  0
23:57:38   fut=524/525   resyncs=  0     <- fully recovered
```

**Nine minutes end to end. Fully autonomous. No errors, no intervention, no operator action.** Spot
was untouched throughout — 353/353 for the entire storm.

### 4.2 The clean measurement: 119 seconds per connection

The isolated 01:00:21 drop is the uncontaminated data point. One futures connection carrying 175
streams dies; 175 books resync; coverage returns to 525/525 at ~01:02:20.

> **One 175-stream futures reconnect costs ~119 seconds of degraded coverage.**

The mechanism is arithmetic, and it does not depend on how many books need help. The snapshot queue
holds 10 per market and dispatches on a 6 s `fixedRate`; each response carries a 5 s settle delay,
so a seat turns over roughly every 6 s. That is **10 books per 6 s = 1.67 books/s**, invariant. The
startup ramp confirms the same constant independently: it climbed exactly 50 books per 30 s window,
per market, for its entire duration.

175 / 1.67 = 105 s of pure queue time, plus discovery lag and the settle delay → 119 s observed.

### 4.3 The stability threshold

> **The pipeline absorbs one 175-stream futures reconnect per ~2 minutes. Past that rate, coverage
> collapses and stays collapsed until the disconnects stop.**

The storm delivered one every 22 s — **5.7× over capacity**. 14 drops × 105 s of recovery demand =
~1470 s of work pushed into a 290 s window. Coverage bottoming at 3% was not a malfunction; it was
the queue draining as fast as it is allowed to while the damage arrived seven times faster.

### 4.4 You cannot buy your way out with queue depth

The depth of 10 is not a guess — it is what Binance's weight budget permits:

- Spot: 10 requests / 6 s = 100/min × weight 50 (`/api/v3/depth?limit=1000`) = **5000 weight/min**
  against the configured `spot-weight-threshold: 5800` (Binance's cap is 6000). **86% of budget.**
- Futures: the same 100/min against `futures-weight-threshold: 2200` (cap 2400) — similarly close.

Raising `*-snapshot-queue-size` therefore trades directly against the rate limiter, and would be
absorbed by `WeightGuard` as delay rather than delivering faster recovery.

**The lever that does exist is the depth limit.** `limit=1000` → `limit=500` roughly halves the
per-request weight and so doubles the recovery rate at unchanged spend. With
`price-filter-threshold: 0.1`, 500 levels very likely already covers ±10% on most pairs — every
level beyond the band is swept by `computeDistance()` immediately after the snapshot is applied,
so on those pairs the extra 500 levels are fetched, parsed and discarded. **This should be measured
before P4** (count surviving levels per book after a snapshot apply); if it holds, it is the
cheapest available improvement to the 119 s number.

### 4.5 Implications for the roadmap

- **P3's reset lane (vision §3.4 case 2) is now justified by evidence, not principle.** Today a
  reconnect is discovered *book by book, reactively*, when each one's next diff fails its `pu`
  check. Signalling all 175 books at the reconnect itself will not make the queue faster, but it
  removes the discovery lag and — more importantly — it is the hook any burst policy must attach
  to. It should stay first in the P3 order.
- **Recovery throughput is a first-class design constraint, not a tuning detail.** A second venue
  brings its own weight budget, so aggregate capacity scales; but it also brings its own
  connections to lose, so *per-venue* recovery time does not improve. Any model-A venue inherits
  this shape. Vision §6.1 correctly makes queue parameters per-venue — this run says those
  parameters deserve deliberate choice per venue, not copying Binance's.
- **Model-B venues (Bybit) sidestep this entirely.** Recovery is a resubscribe, with no REST
  request and no weight cost, so a mass reconnect there is bounded by the subscribe pacer rather
  than by a fetch budget. Worth remembering when P4 makes the SPI's recovery seam feel abstract:
  the two models have materially different failure economics, which is exactly why `RecoverySink`
  is per-venue.
- **The health line proved sufficient to diagnose all of this.** Synced-per-venue plus
  resyncs-per-venue plus msgs/s were enough to separate "sequence rule is broken" from "the network
  dropped" without any additional instrumentation. Connection up/down counters (vision §8.4) would
  have made the storm self-evident rather than inferred — that is the next number worth adding.

---

## 5. Latent issue found while checking the weight arithmetic

`SnapshotFetchQueue` uses `@Scheduled(fixedRate = 6000)` and removes a slot only when its **HTTP
response arrives** — but every response carries a mandatory `.delayElement(Duration.ofSeconds(5))`.

That leaves roughly **one second of slack**. If the round-trip to Binance ever exceeds ~1 s, the
next tick re-dispatches slots that are still in flight, spending double weight against a budget
already at 86% — which `WeightGuard` would then absorb as delay, slowing recovery precisely when
recovery matters most.

It did not fire in this run (no weight delays, no duplicate traffic, no `Snapshot fetch failed`
lines), and on the Japan box the RTT makes it very unlikely. But it is a real edge on a slow or
congested link, and the fix belongs in **P2 step 3**: the queue should track *in-flight* separately
from *pending*, and dispatch only the pending set.

---

## 6. Smaller findings

### 6.1 The futures sync-point trade-off, measured

`sync point missed` fired **40 times out of 1077 logged desyncs — 3.7%**.

This is the trade-off documented in progress doc §4.6: futures regime 1 uses the doc-conformant
`U <= lastUpdateId`, which rejects an otherwise perfectly contiguous event where
`U == lastUpdateId + 1`, costing a wasted resync. The doc predicted it would be "rare" because it
requires the snapshot to land on a 500 ms aggregation boundary. **3.7% of recoveries is the price**,
now measured rather than assumed. The decision stands; the number is recorded so it can be traded
knowingly if recovery throughput ever becomes critical.

### 6.2 The `PENDING`-retries-on-every-diff property is load-bearing in production

Debug counts: `snapshot refused` **1036** vs `snapshot requested` **41**. Ninety-six percent of
`recover()` sink calls were refused; nearly all successful queue entries came from the unlogged
`PENDING` retry path in `onEvent`.

Progress doc §4.3 lists "PENDING retries on every diff" as a load-bearing property and justifies it
by the startup ramp. This run shows it is equally load-bearing during a mass reconnect: without it,
~1000 books would have parked permanently after a single refused request. Any future refactor that
makes recovery request-once-and-wait would turn a nine-minute self-heal into a permanent outage
requiring a restart.

### 6.3 Every futures book proved liveness

All **525** distinct futures symbols appear in at least one resync debug line over the run. Combined
with 353/353 spot holding `SYNCED` continuously, this is indirect but complete evidence that no
subscription was silently dead — which is otherwise the pipeline's one invisible failure mode
(see §8).

### 6.4 `nativeSymbol` is not ASCII

Five futures instruments carry CJK names — genuine Binance meme listings, not mojibake, each ending
in a normal ASCII `USDT` suffix. They synced and resynced normally.

**Implication**: the vision's §5.3 "optional later micro-optimisation — hash the routing token bytes
in place without materialising a String" must not assume one byte per character, and neither may any
future venue's subscribe-frame builder or routing-token scanner. Recorded here because this is
precisely the kind of assumption that passes every test and fails on five symbols in production.

### 6.5 Only futures connections dropped

Zero spot disconnects in 2.5 hours; sixteen on futures across three connections. Given both pools
run the same client code over the same local network, the asymmetry most likely reflects
`fstream.binance.com` routing or the ~3× higher message volume on futures. Not actionable, but worth
knowing that two venues on the *same exchange* are not equally exposed — which is itself an argument
for the vision's per-venue framing.

### 6.6 `WebSocket error on session N: null`

Four occurrences, all from `ScreenerWebSocketEndpoint.onError` logging `error.getMessage()` on what
is almost certainly an abrupt browser disconnect. Harmless, but `null` as the entire diagnostic is
useless. Log the exception class name (and `toString()`), not just the message.

---

## 7. Action list, in priority order

| # | Action | Basis |
|---|---|---|
| 1 | **P3 reset lane** — signal all books on a connection at reconnect, rather than discovering the gap book-by-book | §4.5 — the only structural improvement to a 9-minute mass recovery |
| 2 | **Measure surviving levels after a snapshot apply; if ±10% is covered by 500, drop `limit` to 500** | §4.4 — doubles recovery rate at zero weight cost |
| 3 | **Fix the `fixedRate` / in-flight overlap in `SnapshotFetchQueue`** (P2 step 3) | §5 — 1 s of slack against an 86%-full weight budget |
| 4 | **Set `-Xms512m`** | §3 — stops the perpetual concurrent marking (one cycle per 45 s) and the storm-driven expansion; free |
| 5 | **Add connection up/down + reconnect counters to the health line** (vision §8.4) | §4.5 — would have made the storm self-evident instead of inferred |
| 6 | **Budget ~1 GB heap for P4+ and re-measure** | §3.4 |
| 7 | Log the exception type in `ScreenerWebSocketEndpoint.onError` | §6.6 |
| 8 | Keep P6 (primitive book) deferred; keep the read-side accessor seam in P3 | §3.4 |

---

## 8. What this run does **not** establish

- **The feed and broadcaster are effectively untested.** `drain max=34ms` was measured against a
  handful of transient sessions. The per-user classification pass — the second half of the hot path
  — was idle for the entire run. Nothing here says anything about 100+ concurrent users with custom
  rules, and that is now the largest unmeasured area.
- **Silent subscription death remains invisible.** A book frozen at `SYNCED` with a dead
  subscription appears in every number above as perfectly healthy. §6.3 gives this run a *post hoc*
  alibi (every book resynced at least once, so every book was live), but that is an accident of the
  disconnects, not instrumentation. The staleness watchdog (vision §8.2) is still the missing
  measurement.
- **Dynamic subscribe/unsubscribe never ran.** The 4-hour universe refresh did not fire inside a
  2.5-hour run, so the listing/delisting path is as untested as it was before.
- **This is not production conditions.** Consumer internet in Tashkent, IntelliJ-hosted JVM, a
  24-core desktop. The disconnect behaviour in particular is local; the Japan box does not see it.
  Treat §4 as a fault-injection result, not as an expected production failure rate.
- **No second venue exists yet.** Every conclusion about scaling to 3000–5000 books is
  extrapolation from a single-exchange, 878-book measurement.
