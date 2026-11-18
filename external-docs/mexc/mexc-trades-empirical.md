# MEXC Trade Streams: Empirically Tested

> **Empirically tested** on 2026-11-18 from a single residential IP, against spot
> `wss://wbs-api.mexc.com/ws` (`spot@public.aggre.deals.v3.api.pb@100ms@<SYMBOL>`) and futures
> `wss://contract.mexc.com/edge` (`sub.deal`).
> - **Reconnaissance** (~06:28–06:33 UTC): 10 symbols per venue for 5 minutes. It fixed the wire contract.
> - **Capture** (slice 0 of `.claude/plans/trade-mexc-plan.md`): the whole universe, 505 spot pairs and
>   538 contracts, **07:39–11:40 UTC (4.0 h)**, one 4 s futures outage. 592K spot and 989K futures deals.
>   That window is Asia afternoon and Europe morning. **It has no US open and no sharp market move**, so
>   rates and thresholds are a first calibration, not final.
>
> Every number below comes from the capture unless it is marked as reconnaissance. Where this file
> disagrees with the MEXC docs, this file wins. Scripts: `C:\git\trade-capture\analysis\mexccap.py`
> (extraction) and `mexc_analyze.py` (all tables, → `out/mexc/report.md`).

## TL;DR

| Question | Docs | Measured |
|---|---|---|
| Spot channel | `spot@public.aggre.deals.v3.api.pb@(100ms\|10ms)@<SYMBOL>` | As documented. Same `SUBSCRIPTION` frame and the same 30-per-connection limit as depth |
| Spot body field | 314 `publicAggreDeals` (proto repo) | **314**, on every push. Wrapper `channel=1`, `symbol=3`, `sendTime=6` in that order, as on depth |
| Spot deal fields | `price`, `quantity`, `tradeType`, `time` (docs); proto adds `tradeId=5` | **1 price, 2 quantity (strings), 3 tradeType, 4 time, 5 tradeId (string), and an undocumented 6** (varint trade sequence) on real fills only |
| Futures push `data` | one object | **An array, newest deal first.** Each item has `p, v, T, O, M, t` plus undocumented `i` (numeric-string trade id) and `cts` (always equal to `t`) |
| Futures `v` unit | not stated | **Contracts.** Base quantity is `v × contractSize`, as on futures depth |
| Side | 1 buy, 2 sell | **Aggressor side** on both venues |
| What one deal is | "raw trade information" | **One fill against one resting order.** One taker order at one price can be several deals |
| Taker-order identity | none | **Spot: exact**, the left half of `tradeId`. Futures: none; side + same `t` + consecutive `i` + price non-strictly toward the aggressor (§3) |
| Spot `X0_X0` deals | not documented | **Synthetic paired orders on a 500 ms clock**: 85% of spot deals, **93.5% of spot notional**. Drop (§4.1) |
| Futures `M` | REST: "self-transact, 1:yes, 2:no"; WS: "auto-transact" | **`M=1` is a self-trade** (both sides the same account): 72% of futures deals, 11% of notional. Drop (§4.2) |
| Real flow | — | **Spot is tiny**: the largest real taker order in 4 h was $96K. Futures `M=2` carries $180M/h (§5) |
| Close delay | — | **Long on MEXC**: 38% of futures alerts come from contracts with < 0.1 real deals/s, p90 76 s. An idle timer is needed (§6) |

## 1. Spot: `spot@public.aggre.deals.v3.api.pb@100ms@<SYMBOL>`

### Frame as delivered

```
1: "spot@public.aggre.deals.v3.api.pb@100ms@XRPUSDT"
3: "XRPUSDT"
6: 1794983354590                                   # sendTime
314 {                                              # publicAggreDeals
  1 { 1: "0.9861"  2: "343.22"  3: 2  4: 1794983354542  5: "751623968833597440X1_751623688012374016X1"  6: 11737827034 }
  1 { 1: "0.9861"  2: "341.63"  3: 2  4: 1794983354542  5: "751623968833597440X2_751623765225304064X1"  6: 11737827035 }
  …
  2: "spot@public.aggre.deals.v3.api.pb@100ms"     # eventType
}
```

- Subscribe and acks are as on depth (`mexc-spot-depth-empirical.md`, `mexc-ws-limits-empirical.md`): text
  ack `{"id":0,"code":0,"msg":"<channels>"}`, rejections only visible in `msg`, `{"method":"PING"}` keepalive.
- 100 ms batches: 1 deal per frame typical, p99 8, at most 172. `sendTime − time` p50 49 ms, p99 98 ms
  (reconnaissance).
- Deals within a frame and across frames of one symbol are in time order.
- `symbol` precedes the body, so `MexcSpotFrameReader.symbolRange` routes deal frames unchanged.

### `tradeId` (field 5) and field 6

`tradeId` is `<A>X<n>_<B>X<m>`. Two forms:

| Form | Deals | Notional | Field 6 | Reading |
|---|---|---|---|---|
| `<A>X<n>_<B>X<m>`, n ≥ 1 | 14.7% | 6.5% | always | `A` = taker order id, `n` = its fill number; `B` = the resting order, `m` = its fill number |
| `<A>X0_<A+1>X0` | 85.3% | 93.5% | never | synthetic, see §4.1 |

Over 64,201 real taker orders (86,890 fills):
- every fill of one order has the same `t`, the same side, `n` rising by 1, and field 6 rising by 1;
- price moves only toward the aggressor or stays equal. It is **equal on 32% of steps**, because each
  resting order at a level is its own deal;
- no order's fills are interleaved with another order's in wire order;
- 4 multi-fill orders spanned two frames (0.04%); none of them was large.

Field 6 is not contiguous per symbol: it is a sequence shared by several symbols. Consecutive orders
of one symbol can still have contiguous field 6 values (965 such pairs in the same millisecond), so
field 6 alone does not separate orders. It is numeric and unique, so it can serve as the alert id.

## 2. Futures: `sub.deal`

```
→ {"method":"sub.deal","param":{"symbol":"BTC_USDT"}}
← {"channel":"rs.sub.deal","data":"success","ts":1794983318045}       # no symbol echoed
← {"symbol":"BTC_USDT","data":[{"p":64670.8,"v":1,"T":1,"O":1,"M":2,"t":1794983337848,"i":"17067813085","cts":"1794983337848"},
                               {"p":64670.8,"v":1,"T":1,"O":1,"M":2,"t":1794983337847,"i":"17067813084","cts":"1794983337847"}, …],
   "channel":"push.deal","ts":1794983337848}
```

- **Field order matches our depth pushes**: `symbol` first, `channel` near the end. The
  `MexcFuturesStreamProtocol` routing (symbol-led frame, fixed offset) works unchanged.
- **`data` is an array, newest first.** After reversing each frame, `i` never goes backwards per
  symbol (0 cases in 989K deals). 1 deal per frame typical, p99 4, at most 50.
- `ts − t` p50 0 ms: pushed as it happens, no batching interval. So **a sweep often spans frames**: 59%
  of multi-fill sweeps arrive in more than one frame. Consecutive fills of one sweep are at most
  213 ms apart by `ts` (p99 144 ms).
- `v` is an integer contract count on every deal. `BTC_USDT` `contractSize` is 0.0001, `PEPE_USDT` 10,000,000.
- `i` is a numeric string from a sequence shared across contracts. Consecutive fills of one sweep are
  consecutive.
- `O` (docs): 1 open position, 2 close position, 3 position no change. On `M=2` deals: 25% / 27% / 48%.
- `M` (docs): REST "self-transact, 1:yes, 2:no", WS "Is it auto-transact?". Both translate 自成交,
  a self-trade. See §4.2.

## 3. Sweeps: which deals are one taker order

**Spot** has the taker-order id, so it is exact. **Futures** has no order id. A rule has to group the
fills, and spot is where id-free rules can be scored against the truth:

| Rule (a deal continues the open sweep when…) | Continue recall | Continue precision | Multi-fill orders recovered exactly |
|---|---|---|---|
| Binance's: same side, same `t`, contiguous ids, price **strictly** toward the aggressor | 0.677 | 1.000 | 61.5% |
| **A**: same side, same `t`, contiguous ids, price toward the aggressor **or equal** | 1.000 | 0.958 | **99.4%** |
| B: A with `t` allowed to rise by 1 ms | 1.000 | 0.925 | 98.6% |

- Binance's rule splits 38% of MEXC multi-fill orders, because of the equal prices.
- Rule A's 4% of wrong continuations are different orders in one millisecond with contiguous ids. The
  stream cannot separate them, as on Binance futures.
- **Futures and Δt = 1 ms.** On spot, adjacent *different* orders with contiguous ids fall off smoothly
  with Δt (965, 830, 556, 350 at 0–3 ms). On futures `M=2`, same-side contiguous steps by Δt are
  103,055 / 5,918 / 1,092 / 811. The 1 ms bin is ~5× the 2 ms bin instead of ~1.5×, so roughly 4,000
  of the 5,900 one-millisecond steps look like one order whose fills crossed a millisecond. On spot
  this never happens: an order's fills always share `t`. Futures apparently stamps each fill.
- The choice matters at the alert level. At the derived thresholds (§5), rule A raises 445 futures alerts
  in 4 h. B raises 477: 74 alerts that A doesn't have, and 42 of A's alerts are missing. Judging
  single deals raises 214, and Binance's rule 400.

## 4. Synthetic and self-trade deals

### 4.1 Spot `X0_X0`: synthetic, drop

| Evidence | Value |
|---|---|
| Maker order id = taker order id + 1 | 99.98% of `X0_X0` deals: both orders were created back to back by one system |
| Field 6 (trade sequence) | never present |
| `X0_X0` order ids that ever appear in a real fill | 0 of 972,858 |
| Gaps between a symbol's `X0_X0` times that are multiples of 500 ms (±10 ms) | median symbol 94% (p10 82%) |
| Share in the symbol's 2 busiest 10 ms phase slots | median 88% (4% if uniform) |
| Buy share | 50.1% |
| Share of the symbol's notional | median symbol 95%; 39 pairs trade **only** `X0_X0` |

The phase is per symbol and can shift (BTCUSDT sat at ~385 ms of the cycle before 08:00 UTC and ~199 ms
after). 179 of the 188 `X0_X0` deals ≥ $30K were within 10 ms of their symbol's clock slots; the rest
were on the earlier phase.

These are not taker orders. Unfiltered at the §5 thresholds they would make **198 spot alerts in 4 h
instead of 10**, all BTC/ETH/XRP/SOL deals of up to $503K on a 500 ms rhythm.

### 4.2 Futures `M=1`: self-trades, drop

| | `M=1` | `M=2` |
|---|---|---|
| Deals / notional | 72.2% / 10.9% | 27.8% / 89.1% |
| `O=3` (position no change) | 99.9% of deals | 48% |
| Gaps on the 500 ms grid, median symbol | 84% | 8% (~4% by chance) |
| Deals ≥ $30K / ≥ $1M | 251 / 21 | 4,418 / 9 |

- **`M` belongs to the taker order**: inside id-contiguous same-`t` runs it changes on 133 of 110,366
  steps.
- Most `M=1` deals are tiny clock-driven trades with alternating sides. The large ones are **off the
  clock** (83 of 91 ≥ $100K) and mostly BTC/ETH/SOL/XRP, up to $3.69M. Some are repeated fixed sizes
  (several $731,926 BTC `O=1` buys).
- The reconnaissance suspected the large off-clock ones were liquidations. They are not: MEXC documents
  `M` as self-trade, and they do not cluster across symbols.
- A self-trade changes no ownership, whatever its size. Unfiltered at the §5 thresholds, futures alerts
  rise from 445 to 517 (+16%), mostly BTC and ETH.
- Filtering a self-trade fill out of the middle of a real sweep breaks the id contiguity there. That
  happened at most 133 times in 4 h, with no measurable effect on alerts.

## 5. Size, thresholds and rates

Notional per deal / per sweep (spot: real taker orders; futures: `M=2` sweeps under rule A):

| venue | median | 1 in 100 | 1 in 1,000 | 1 in 10,000 | largest |
|---|---|---|---|---|---|
| spot | $9 / $11 | $981 / $1.3K | $5.0K / $6.3K | $26.9K / $36.3K | $67.8K / $95.9K |
| futures | $100 / $225 | $44.3K / $67.2K | $259K / $406K | $763K / $957K | $1.88M / $2.07M |

Alerts per hour, with Binance's policy (floor, then raise a symbol's threshold up the ladder until it
fires ≤ 10/h):

| venue | floor | flat floor: alerts/h | symbols alerting | with the 10/h cap | overrides |
|---|---|---|---|---|---|
| spot | $5K | 24 | 14 | 24 | 0 |
| spot | $10K | 8 | 7 | 8 | 0 |
| spot | $30K | 2.5 | 4 | 2.5 | 0 |
| futures | $10K | 2,813 | 82 | 232 | 16 |
| futures | $20K | 1,655 | 54 | 155 | 10 |
| futures | $30K | 1,112 | 42 | **111** | 5 |

- **Spot alerts are rare at any floor.** The visible spot volume is almost all `X0_X0`. Real taker flow
  is $1.4M/h across 505 pairs. At $10K the alerting pairs are XRP, BTC, ETH, ZEC, SOL, NEAR, LTC.
- **Futures at the $30K floor**: overrides `BTC_USDT` 750K, `ETH_USDT` 500K, `SOL_USDT` 300K, `ZEC_USDT` 200K,
  `XRP_USDT` 150K. 75–148 alerts/h by hour. 81% of alerts are multi-fill, 57% exist only because of
  coalescing, and 74% took more than one price level. Fills go up to 35 per sweep and levels up to 23,
  so `fills ≠ levels` matters.
- **Many alerts come from thin contracts.** At $30K, 170 of 446 futures alerts (28 contracts) come from
  contracts with < 0.1 real deals/s. Examples: SUI 39 alerts at 0.09 deals/s, DOT 18 at 0.03, ADA 9 at
  0.05. Real `M=2` flow on MEXC is sparse and lumpy even on large caps.

### 5.1 Over 10.7 h, through the US open, and the shipped thresholds

The tables above are the first 4 h. Over the capture to 2026-11-18 17:31 UTC (10.7 h, three hours past
the US open), the $30K futures floor with the 10/h cap gives **143 alerts/h on average and 282 in the
busiest hour**, more than the 4 h estimate. Futures volume is driven by the busy contracts: at cap 10,
ETH, HYPE, XRP and DOGE each fire ~8/h, so a higher floor alone barely helps (still 34/h at $150K).

The shipped thresholds follow a different policy, set for a whole-feed budget of ~120 alerts/h across
Binance and MEXC (`trade-mexc-plan.md` §2.8): a floor that is large in absolute terms ($30K spot, $100K
futures, the same on every exchange), and a cap of **3 alerts/h** per symbol on average and at most 7.5
in any full hour. Derived by `trade-capture/analysis/thresholds.py`:

| venue | floor | alerts/h | worst full hour | symbols alerting | overrides |
|---|---|---|---|---|---|
| spot | $30K | 2.5 | 5 | 5 | 0 |
| futures | $100K | 18 | 38 | 31 | 5 |

- Futures overrides: `BTC_USDT` 2M, `ETH_USDT` 1.5M, `SOL_USDT` 500K, `XRP_USDT` 500K, `ZEC_USDT` 300K.
  The most frequent: ZEC 2.4/h, SOL 2.1, BTC 1.7, SUI 1.2, DOGE 1.1.
- Spot alerts on ETH, BTC, NEAR, SOL and XRP; a thin market is not pushed to a lower floor just to
  produce alerts.
- The cost: futures contracts with an alert drop from 55 (at $30K) to 31. A `MEXC_FUTURES` default of
  $50K would add ~22 alerts/h.

Floor × cap, futures (average alerts/h, worst full hour in brackets):

| floor | cap 10 | cap 5 | cap 3 | cap 2 |
|---|---|---|---|---|
| $30K | 138 (243) | 79 (137) | 51 (93) | 35 (63) |
| $50K | 78 (142) | 52 (96) | 41 (74) | 31 (58) |
| $100K | 35 (74) | 22 (50) | **18 (38)** | 13 (30) |
| $150K | 29 (62) | 15 (37) | 12 (24) | 8 (16) |

Rates (whole universe):

| venue | frames/s | deals/s | deals per frame p50 / p99 / max | filtered deals/s |
|---|---|---|---|---|
| spot | 25.1 | 41.1 | 1 / 8 / 172 | 35.1 (`X0_X0`) |
| futures | 60.8 | 68.7 | 1 / 4 / 50 | 49.6 (`M=1`) |

## 6. Close delay

A sweep is judged when the symbol's next deal proves it ended. On MEXC the next *real* deal is often
far away. Venue time from a sweep's last fill to the next deal that reaches the detector, for alerts
at the §5 thresholds:

| venue | symbol activity (real deals/s) | alerts | p50 | p90 | max | p90 / max if filtered deals also close |
|---|---|---|---|---|---|---|
| spot | 0.1–1 | 9 | 5.8 s | 23 s | 24 s | 8.4 s / 8.4 s |
| spot | < 0.1 | 1 | 56 s | — | 56 s | 56 s |
| futures | ≥ 1 | 63 | 0.16 s | 1.9 s | 5 s | 0.9 s / 4.9 s |
| futures | 0.1–1 | 213 | 1.8 s | 14.5 s | 51 s | 10.8 s / 15.7 s |
| futures | < 0.1 | 170 | 9.2 s | 76 s | **726 s** | 15.2 s / 16 s |

- **Spot needs no timer if a sweep closes at the end of its frame.** An order spans two frames in 0.04%
  of multi-fill orders, and no alert at any floor from $5K up did.
- **Futures needs an idle timer.** Fills of one sweep are at most 213 ms apart by `ts`, so 1 s of
  silence safely ends a sweep. Locally, receive gaps inside a sweep reached 3.8 s (p99.9 1.2 s)
  because of this connection's lag spikes, so a timer on the local clock has to allow for that.

## Not tested

- The US open, weekends, a sharp move or a liquidation cascade (the capture keeps running).
- Whether large `M=2` deals on thin contracts are independent participants. They could be
  cross-account market-maker trades, which the stream cannot show.
- The `10ms` spot variant.
- REST cross-check of the deal stream (spot `/api/v3/trades` has `id: null` and an `isRpi` flag;
  futures `/api/v1/contract/deals` has the same fields as the push).
