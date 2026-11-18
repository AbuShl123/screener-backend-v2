# Large-Trade Detection on MEXC: How It Works and Why

An explainer for anyone working on MEXC trade alerts, or wanting to understand the trading mechanics
behind trade detection in general. It covers what MEXC sends, how it differs from Binance, how we
group fills into orders, which deals we ignore and why, and why timing is harder on MEXC. Section 1
answers general domain questions (why orders sweep, what "levels swept" means, notional vs price
impact) that apply to every exchange.

**Companion to** `how-large-trades-are-detected.md` (the Binance explainer: basics, the detector's
design, thresholds, the replay fixture, future ideas). Read its §1–2 first if terms like *taker*,
*notional* or *sweep* are new; this document repeats only what MEXC needs.

**Status (2026-11-18)**: Phase 5 of the trade-alerts work, MEXC first. Slice 0 (capture and
analysis), slice 1 (transport: both `TRADE` streams connect and route), slice 2 (decoding,
filtering, sweep grouping, the spot frame-end close, `levels`) and slice 3 (the futures idle close
on a shard tick) are done. Slice 4: the thresholds moved to `trade-thresholds.yml` with a per-venue
default, and were re-derived over 10.7 h through the US open for a ~120 alerts/h budget across the
whole feed (§6). Still open: the live check. Both streams stay off by default until then.

**Detailed sources**: `external-docs/mexc/mexc-trades-empirical.md` (the wire contract and every
measured number), `.claude/plans/trade-mexc-plan.md` (implementation plan; "Capture findings" holds
the decisions). Numbers here come from a 4.0 h capture of the whole MEXC universe (505 spot pairs,
538 futures contracts, 2026-11-18 07:39–11:40 UTC, Asia afternoon and Europe morning, **no US open
and no sharp market move**), so they are a first calibration.

---

## 1. Trading mechanics: questions and answers

### 1.1 What one order looks like in the book

An order book holds **resting orders** (makers) at different prices. A trade happens when someone
sends an order that crosses the book: the **taker** (aggressor). A market buy of $50K against this
ask side:

```
asks (resting sell orders)           taker: BUY $50K
  100.03   order #9  $30K   ◄── 3. takes $8K of it (the rest stays in the book)
  100.02   order #8  $12K   ◄── 2. takes all of it
  100.01   order #7  $20K   ◄── 1. takes all of it
  100.01   order #6  $10K   ◄── 1. takes all of it    (two resting orders at one price)
```

One decision of one trader produces **4 fills** on **3 price levels**. The detector's job is to
report one $50K buy, not four small trades. Re-joining the fills into the order is **sweep
coalescing**. It matters because no single fill above reaches a $30K threshold; the order does.

### 1.2 Why does one order fill at several prices? Who decides?

The exchange's **matching engine** decides, by **price-time priority**:

1. **Price first.** A buy fills against the cheapest ask. When the quantity there runs out, the rest
   continues at the next cheapest price, then the next.
2. **Time within a price.** Each price level is a **queue** of separate resting orders from
   different people, filled in the order they were placed. Several fills at one price are several
   people's orders in that queue (orders #6 and #7 above).

The trader chooses only the **size** and the **order type**: a *market* order ("fill now at any
price") or a *limit* order ("fill, but no worse than X"). Everything else follows from the book. If
the order is larger than what rests at the best price, it walks to worse prices: a **sweep**. If the
best level is large enough, the whole order fills there: one level, possibly many fills.

**Why would anyone accept worse prices?** Because they are in a hurry:

- a breakout or news trade, where waiting costs more than the slippage;
- **stop-losses and liquidations**, which are market orders by design (forced flow);
- plain impatience.

Patient large players avoid sweeping: they split orders over time (TWAP, iceberg orders) or post
limit orders and wait to be filled. Their size shows up as **walls in the depth feed**, not as trade
alerts. The two signals complement each other: depth shows patient money waiting, trades show urgent
money acting.

### 1.3 Is the last level fully taken? Is "swept 3 levels" accurate?

Every level **except the last** is emptied completely. The last is taken only as far as the order
needs; whatever remains of it stays in the book and becomes the new best price. Emptying the last
level exactly is a coincidence. This is the same on Binance and on every exchange.

"Swept 3 levels" therefore means **the order traded at 3 prices**, which is how traders read it. The
frontend contract defines `levels` as "price levels taken" (`websocket-feed-api.md` §3.8.2), which is
accurate. "Hit" or "traded through" 3 levels are equally correct wordings.

A related case: a taker **limit** order larger than everything available up to its limit price
fills what it can, and the rest becomes a new resting order (a maker). Only the part that traded is
seen in the trade stream, which is right: that is the aggressive part.

### 1.4 What does a sweep do to the price?

After a buy sweep from 100.01 to 100.03, the asks below 100.03 are gone. The best ask is now 100.03
(or higher, if that level was emptied too), the spread widens and the mid-price rises at once. Then
other participants react: market makers may refill the asks and the price drifts back, or the move
triggers more buying, stops or liquidations and continues.

**More levels does not always mean more movement.** `levels` counts prices traded at, not how far
the price moved. Levels can be one tick apart in a dense book or far apart in a thin one. The real
measure is the **price impact**: `|lastPrice / firstPrice − 1|`. The frontend contract tells clients
to compute and show it (§3.8.2): a large `levels` with a small impact means a dense book; few levels
with a large impact means a thin one.

### 1.5 Which matters more: a huge order on 1–2 levels, or a moderate one that swept many?

They tell a trader different things, and both are useful:

| | Huge notional, 1–2 levels | Moderate notional, many levels / high impact |
|---|---|---|
| What happened | A lot of money hit a level deep enough to absorb it | An urgent order hit a thin book |
| Immediate price effect | small | large: a visible wick on the chart |
| Typical reading | A big participant. If it hit a wall without moving the price, that is **absorption**: the passive side is strong (support or resistance) | Urgency or forced flow (stops, liquidations). Can start a cascade in a thin book; often partly reverts |
| Usually lasts longer? | often: more money behind it | often a short-term move |

**For our alerts**, notional stays the trigger: it measures how much money acted, and the threshold
ladder is tuned on it. Levels and impact are context that helps a trader read an alert, and the
`TRADE` message already carries `firstPrice`, `lastPrice`, `levels` and `avgPrice` for it. An alert
triggered by impact itself ("any order that moved the price more than 0.5%") would be a separate
feature, possibly worth it later for thin MEXC contracts.

### 1.6 Can two orders sweep at the same time on one symbol?

**Several orders in one frame: yes, often.** A MEXC spot frame collects 100 ms of a symbol's deals
(usually 1 deal, p99 8, up to 172), so a busy frame often holds several taker orders, from different
traders and sometimes on opposite sides.

**Orders sweeping in parallel, with their fills mixed together: no.** The matching engine handles one
symbol's orders **one at a time**. If two traders send market buys at the same moment, the engine
queues them, fills the first one completely through every level it reaches, and only then starts
the second. Inside the engine, two orders' fills on one symbol never mix.

The capture agrees:

- **Spot (exact, from order ids):** across 64,201 real taker orders, no order's fills were
  interleaved with another order's in wire order. Each order is one unbroken run.
- **Futures (indirect, no order id):** trade ids come from one sequence assigned one by one. A
  sweep's fills carry consecutive ids, and once each frame is reversed the ids never go backwards
  for a symbol. That is what a serial engine produces.

So a spot frame looks like `[A1 A2 A3] [B1 B2] [C1]`, never `A1 B1 A2`. The detector relies on this
by keeping **one open sweep per instrument**. B1 has a different taker id, so it closes A and opens
B; C1 closes B; the end of the frame closes C. That gives three sweeps, each judged on its own.

**The real risk is orders placed back to back, not mixed together.** Suppose trader 1's buy ends at
100.03 and trader 2's buy starts in the same millisecond at 100.03 or higher:

- Spot: no problem. A different taker id always means a different order.
- Futures: every check of rule A matches (same side, same time, consecutive id, price equal or
  higher), so the two are **merged into one sweep**. These are rule A's ~4% of wrong continuations
  (§4.3). The stream has no information to separate them, and Binance futures has the same limit.

Sweeps on **different symbols** do happen at the same time. That is fine: frames are per symbol and
each instrument has its own sweep state, so they never meet.

---

## 2. What MEXC sends, compared to Binance

### 2.1 One event is one fill against one resting order

**Binance `aggTrade`** bundles fills:

- spot: one frame = **one taker order at one price**;
- futures: one frame = same-price, same-side fills over up to ~100 ms (it can merge several orders).

So the order of §1.1 arrives on Binance as 3 events, one per price, and within one order the price
always moves **strictly** in the aggressor's direction:

```
aggTrade 100.01  $30K   (orders #6 and #7 merged)
aggTrade 100.02  $12K
aggTrade 100.03   $8K
```

**MEXC** (both venues) sends **one deal per resting order filled**. The same order is 4 deals:

```
deal 100.01  $10K   (vs #6)
deal 100.01  $20K   (vs #7)   ← same price, still the same order
deal 100.02  $12K
deal 100.03   $8K
```

An **equal price inside one order is normal** on MEXC: 32% of steps between consecutive fills of one
spot order. Binance's strict price rule would cut this order after the first deal; on MEXC it splits
38% of multi-fill orders. This is the first MEXC difference, and the reason the continuation rule
allows an equal price on venues that report maker fills (`TradeDecoder.reportsMakerFills()`).

It is also why an alert needs **both** counts: `fills` (deals summed) and `levels` (distinct prices).
On Binance they are equal, because each event of one sweep has its own price. On MEXC they are not:
the capture saw sweeps of up to 35 fills on 23 levels. The frontend contract's `levels` field does
not change; only where the backend takes it from does (`TradeAlert.levels()` instead of `fills()`).

### 2.2 MEXC spot: Protobuf, 100 ms batches, an order id

Channel `spot@public.aggre.deals.v3.api.pb@100ms@<SYMBOL>`, binary Protobuf like spot depth (body
field 314). Each deal has a price, a quantity (base asset), a side (1 = buy, aggressor side), a time,
a `tradeId` string and, on real fills only, an undocumented numeric trade sequence (field 6).

The `tradeId` encodes both orders of the fill:

```
751623968833597440X1_751623688012374016X1
└── taker order id ─┘ │ └── maker order id ┘ │
                      └ taker's fill #1      └ maker's fill #1
```

A captured XRPUSDT frame:

```
0.9861  343.22  sell  t=…542  751623968833597440X1_751623688012374016X1
0.9861  341.63  sell  t=…542  751623968833597440X2_751623765225304064X1
```

Same taker id, fill number 1 → 2, different makers: one sell order of about $675 that hit two
resting orders at one price.

- Deals are batched per symbol into **100 ms frames**. A frame is sent only if the symbol traded in
  that window. A deal waits about 49 ms on average before it is sent (p99 98 ms). Frames carry 1 deal
  typically, p99 8, at most 172.
- All fills of one order share one time `t`, have rising fill numbers, and are never interleaved
  with another order's fills.
- Field 6 is unique and numeric, so it serves as the trade id (`firstTradeId`) and the alert id.

### 2.3 MEXC futures: JSON, newest first, in contracts, no order id

Channel `sub.deal` on `contract.mexc.com/edge`:

```json
{"symbol":"BTC_USDT","data":[
  {"p":64670.8,"v":1,"T":1,"O":1,"M":2,"t":1794983337848,"i":"17067813085"},
  {"p":64670.8,"v":1,"T":1,"O":1,"M":2,"t":1794983337847,"i":"17067813084"}],
 "channel":"push.deal","ts":1794983337848}
```

| field | meaning |
|---|---|
| `p` | price |
| `v` | quantity in **contracts**. Base quantity = `v × contractSize` (`Instrument.quantityMultiplier()`), as on futures depth. BTC_USDT's contract is 0.0001 BTC, so `v=1` above is about $6.47 |
| `T` | aggressor side, 1 = buy |
| `t` | trade time (ms) |
| `i` | trade id: a numeric string from one sequence shared by all contracts. Fills of one sweep get consecutive values |
| `M` | self-trade flag: 1 = both sides are the same account (§3.2) |
| `O` | open / close / no position change (not used) |
| `ts` | when MEXC sent the frame |

- **`data` is newest first.** The decoder reverses it: the detector needs time order.
- **No batching interval**: `ts − t` is 0 ms at the median. Each fill is pushed as it is matched.
- **No order id.** Which fills belong to one order has to be inferred (§4.2).

---

## 3. Most MEXC deals are not real trading: filtered deals

The second big difference from Binance: most of what MEXC streams does not come from anyone deciding
to buy or sell. A decoder drops such deals by venue policy and reports each to `sink.onFiltered`. A
filtered deal never opens, continues or is judged as a sweep; it is only counted (the health log's
`filt` column).

### 3.1 Spot `X0_X0` deals: synthetic, 93.5% of spot notional

Deals whose `tradeId` is `<A>X0_<A+1>X0`: fill number 0 on both sides, and the maker order id is the
taker id + 1. They are **85% of spot deals and 93.5% of spot notional.** Evidence that they are
synthetic:

- both orders were created back to back by one system (maker id = taker id + 1 in 99.98%);
- they never carry the trade sequence (field 6);
- none of their order ids ever appears in a real fill (0 of 972,858);
- they fire on a **500 ms clock per symbol** (the median symbol has 94% of its gaps on multiples of
  500 ms), at a fixed phase in the cycle;
- buys and sells are exactly balanced (50.1% buys);
- 39 pairs trade **only** `X0_X0`.

This looks like a bot generating volume, the exchange's or a market maker's. No money changes hands
between independent participants. Unfiltered, spot would raise **198 alerts instead of 10** in 4 h,
all BTC/ETH/XRP/SOL deals of up to $503K on a 500 ms rhythm. **Dropped.**

### 3.2 Futures `M=1`: self-trades

MEXC documents `M` as "self-transact": the same account on both sides. `M=1` is **72% of futures
deals and 11% of futures notional**; 99.9% of them have `O=3` (no position change). Most are tiny,
clock-driven, alternating-side trades. The large ones (up to $3.69M) are off the clock. They were
first suspected to be liquidations, but they do not cluster across symbols, and the docs say
self-trade. A self-trade changes no ownership whatever its size. Unfiltered, futures alerts rise by
16%, mostly BTC and ETH. **Dropped.**

`M` belongs to the taker order: within one sweep it changed on only 133 of 110,366 steps, so dropping
`M=1` deals practically never breaks a real sweep.

### 3.3 What remains is small

| | spot | futures |
|---|---|---|
| deals/s (all) | 41.1 | 68.7 |
| of which filtered | 35.1 (`X0_X0`) | 49.6 (`M=1`) |
| real taker flow | **$1.4M per hour** across 505 pairs | $180M per hour |
| largest real order in 4 h | **$96K** | $2.07M |

The visible MEXC spot volume is almost all synthetic. Real spot trading is tiny, which drives the
thresholds (§6).

---

## 4. Sweep coalescing on MEXC

### 4.1 The continuation rule

A deal **continues** the instrument's open sweep when it has the **same side** and:

1. **both taker-order ids are known**: they are equal. Nothing else is checked (MEXC spot);
2. **otherwise**: same trade time `t`, the next trade id (`firstTradeId == lastTradeId + 1`), and a
   price **equal to the previous fill or further in the aggressor's direction** (MEXC futures, called
   *rule A*). On venues that don't report maker fills (Binance) the price must move strictly, as
   today.

Otherwise the open sweep is **closed** (judged against the threshold) and the deal opens a new one.

### 4.2 Spot: exact

The taker id is in every real deal, so spot coalescing is exact: same id, same order. The id is
parsed from the `tradeId` string into a `long` without allocating.

### 4.3 Futures: rule A, calibrated on spot

Futures has no order id, so rule A infers the order. Spot was used to score it: there, the true
grouping is known from the order id, so the id-free rules could be applied to spot fills and checked
against the truth.

| rule | continue recall | continue precision | multi-fill orders recovered exactly |
|---|---|---|---|
| Binance's: side, `t`, next id, price **strictly** toward the aggressor | 0.677 | 1.000 | 61.5% |
| **A**: side, `t`, next id, price toward the aggressor **or equal** | 1.000 | 0.958 | **99.4%** |
| B: A with `t` allowed to rise by 1 ms | 1.000 | 0.925 | 98.6% |

Rule A's 4% of wrong continuations are two **different** orders in the same millisecond with
consecutive ids. The stream has no information to separate them (Binance futures has the same limit).

**The open 1 ms question.** In the BTC frame of §2.3, the two deals have the same side and price and
consecutive ids, but `t` differs by 1 ms (…847 → …848). On spot, an order's fills always share `t`.
On futures, steps between same-side consecutive fills by time difference are 103,055 at 0 ms, 5,918
at 1 ms, 1,092 at 2 ms, 811 at 3 ms. The 1 ms count is about 5× the 2 ms count where spot shows a
smooth falloff, so roughly 4,000 of those steps look like one order whose fills crossed a
millisecond: futures appears to stamp each fill separately. Allowing 1 ms (rule B) changes about a
quarter of the futures alerts (+74 / −42 of 445 in 4 h). The plan keeps **exact `t`**: the tolerance
can't be proven from the stream, and exact `t` matches spot and Binance.

### 4.4 Why coalescing matters even more on MEXC

Because MEXC reports every resting order separately, orders are more fragmented than on Binance. At
the futures thresholds of §6: 81% of alerts are multi-fill, **57% exist only because of coalescing**
(no single fill reached the threshold), and 74% took more than one price level.

---

## 5. Timing: when can a sweep be judged?

"Slowness" in the MEXC docs means three different things. Only the last one is a real problem.

### 5.1 Spot batching (accepted)

Spot deals wait up to 100 ms in their batch, about 49 ms on average. Nothing can be done about it
on this channel, and for a screener it is fine. MEXC also offers a `@10ms` variant, to revisit only if
users notice.

### 5.2 Futures pushes per fill, so one sweep arrives in pieces

Futures has no batching window: each fill is pushed as it is matched. MEXC's push gateway still
splits one order across several frames: **58.8% of multi-fill futures sweeps arrive in more than one
frame** (spot: 0.037%). This was measured by grouping all futures fills with rule A and checking
whether each group's fills came in one frame or several.

The pieces are close together. Rule A requires the same `t`, so all fills of such a sweep were
**matched in the same millisecond**: the order executes at once, only delivery is split. The pieces
are at most **213 ms apart by `ts`** (p99 144 ms). So if a frame brought 3 fills of a sweep, any
continuation is due within about 200 ms.

Consequence: on futures a sweep can't be closed at the end of a frame; the next frame may continue
it.

### 5.3 The close delay (the real problem)

The detector can't see that an order is finished. Until now, the proof has been the **instrument's
next trade that does not continue the sweep**:

```
fill  fill  fill  ........ (silence) ........  next deal → "the sweep is over" → judge → alert
                  └──────── close delay ──────┘
```

On Binance, liquid symbols trade constantly, so the next trade comes within milliseconds. On MEXC,
after 72–85% of deals are dropped as noise, **real deals are sparse**, even on large caps. Delay from
a sweep's last fill to the next real deal, for alerts at the §6 thresholds:

| venue | instrument activity (real deals/s) | alerts in 4 h | p50 | p90 | max |
|---|---|---|---|---|---|
| spot | 0.1–1 | 9 | 5.8 s | 23 s | 24 s |
| spot | < 0.1 | 1 | 56 s | — | 56 s |
| futures | ≥ 1 | 63 | 0.16 s | 1.9 s | 5 s |
| futures | 0.1–1 | 213 | 1.8 s | 14.5 s | 51 s |
| futures | < 0.1 | 170 | 9.2 s | **76 s** | **726 s** |

38% of futures alerts come from contracts with fewer than 0.1 real deals per second (for example SUI:
39 alerts at 0.09 deals/s). Waiting 76 s, or 12 minutes, makes an alert useless. (On Binance, thin
symbols alerted 3 times in 5 h at the same floor, so this was deferred there.)

Letting filtered deals close sweeps (they arrive on a steady clock) would cut the futures p90 to about
15 s: better, not good enough. The decisions:

**Spot: close the sweep at the end of its frame.** An order's fills almost always arrive in one frame
(only 0.04% of multi-fill orders spanned two, and none was large). So after the decoder has reported
a frame's deals, whatever sweep is still open is judged at once. Inside the frame, the taker id
still separates orders: a deal with a different id closes the previous order. The close delay on
spot becomes zero. The rare order split across two frames becomes two smaller sweeps.

**Futures: an idle timer.** Fills of one sweep are at most 213 ms apart by `ts`, so **about 1 s of
silence** means the order has ended. A shard tick checks every open sweep every 250 ms and judges the
ones with no new fill for `sweep-idle-close-ms` (1500 ms for `MEXC_FUTURES`), so the worst close
delay is about 1.75 s. The tick is a timer thread that only puts a `TICK` event into each shard's
ring; the close itself runs on the shard thread, in order with that shard's frames, so it never
races a fill. A frame handled just before a tick has already refreshed its sweep's last-fill time,
so the tick leaves that sweep open.

**The timer's catch: our own clock.** `ts` is MEXC's send time; the timer runs on our receive time.
Network lag spikes in the capture stretched receive gaps inside one sweep to **3.8 s** (p99.9 1.2 s).
A timer that is too tight would cut a sweep in two during a spike. The idle value has to trade that
rare split against alert delay. The plan starts at 1500 ms with a 250 ms tick, and counts **idle
splits** (a deal that would have continued a sweep the timer already closed) to show live whether
the value is too short.

---

## 6. Size and thresholds

Notional per deal / per sweep (spot: real taker orders; futures: `M=2` sweeps under rule A):

| venue | median | 1 in 100 | 1 in 1,000 | 1 in 10,000 | largest |
|---|---|---|---|---|---|
| spot | $9 / $11 | $981 / $1.3K | $5.0K / $6.3K | $26.9K / $36.3K | $67.8K / $95.9K |
| futures | $100 / $225 | $44.3K / $67.2K | $259K / $406K | $763K / $957K | $1.88M / $2.07M |

The thresholds serve a budget of about **120 alerts/h for the whole feed**, Binance and MEXC
together, since every alert goes to every user. The policy is the same on every exchange:

- **A floor that means "large" in absolute terms**: $30K on spot, $100K on futures (leveraged, and
  far more volume). A thin market keeps the floor and alerts only when a large trade really happens;
  it is never given a lower floor just to produce alerts.
- **A cap of 3 alerts/h per symbol** (and at most 7.5 in any full hour): a busy symbol's threshold is
  raised up the ladder until it fires about once every 20 minutes.

Over 10.7 h of capture (2026-11-18 07:39–17:31 UTC, through the US open):

| venue | floor | alerts/h | worst full hour | symbols alerting | overrides |
|---|---|---|---|---|---|
| spot | $30K | 2.5 | 5 | 5 | 0 |
| futures | $100K | 18 | 38 | 31 | 5 |

- **Spot is rare, by design.** Real taker flow is $1.4M/h across 505 pairs; ETH, BTC, NEAR, SOL and
  XRP see orders of $30K or more.
- **Futures**: $100K plus overrides `BTC_USDT` 2M, `ETH_USDT` 1.5M, `SOL_USDT` 500K, `XRP_USDT` 500K,
  `ZEC_USDT` 300K. Keys are native symbols (`BTC_USDT`, not `BTCUSDT`). Contracts with an alert drop
  from 55 at a $30K floor to 31; a $50K `MEXC_FUTURES` default would add ~22 alerts/h.
- The futures floor is a per-venue default, `venue-default-min-notional.MEXC_FUTURES: 100000`; spot
  uses the global $30K. All thresholds live in `src/main/resources/trade-thresholds.yml`, derived by
  `trade-capture/analysis/thresholds.py` (`mexc-trades-empirical.md` §5.1 has the floor × cap grid).

---

## 7. The whole path for one frame

```
MEXC spot frame (Protobuf, 100 ms batch)       MEXC futures frame (JSON, newest first)
  for each deal, in wire order                   reverse to oldest first
    X0_X0?  → onFiltered, skip                     M=1?  → onFiltered, skip
    taker id = left half of tradeId                qty = v × contractSize, takerId = -1
    trade id = field 6                             trade id = i
            │                                              │
            └────────────── TradeDetector.onTrade ─────────┘
                 does it continue the open sweep?
                   both taker ids known → equal ids?                    (spot: exact)
                   otherwise → same side, same t, next id,
                               price equal or toward the aggressor      (futures: rule A)
                 yes → add it: fills++, levels++ if the price changed
                 no  → judge the open sweep against the threshold, open a new one
            │                                              │
     end of frame: judge the open sweep        idle 1.5 s (shard tick): judge the open sweep
                            │
                  TradeAlertStore → TradeChannel → /ws  (TRADE message, unchanged shape)
```

Alert ids keep the Binance form `VENUE:NATIVESYMBOL:firstTradeId`, e.g.
`MEXC_FUTURES:BTC_USDT:17067813085`.

---

## 8. Summary: Binance vs MEXC

| | Binance | MEXC spot | MEXC futures |
|---|---|---|---|
| Encoding | JSON, 1 event per frame | Protobuf, 100 ms batches | JSON array, newest first |
| One event is | one order at one price (spot); same-price fills over ~100 ms (futures) | one fill vs one resting order | one fill vs one resting order |
| Quantity | base | base | contracts × `contractSize` |
| Equal price inside one sweep | never | normal | normal |
| Coalescing | rule: side, `T`, ids, strict price | **exact**, by taker order id | rule A: side, `t`, next id, non-strict price |
| Noise dropped | none | `X0_X0` bot deals (93.5% of notional) | `M=1` self-trades (11% of notional) |
| Real flow | large | tiny (largest order $96K in 4 h) | moderate, lumpy |
| Floor | $30K spot, $100K futures | $30K | $100K |
| A sweep closes on | the next trade (comes fast) | end of its frame | idle timer (1.5 s) |

---

## 9. Limitations and open questions

- **Short capture**: most numbers here come from the first 4 h (no US open); the thresholds come
  from 10.7 h through the US open. One weekday, no sharp move, no liquidation cascade, no Asia
  session or weekend. Re-derive with `thresholds.py` on longer data.
- **Same-millisecond orders on futures** with consecutive ids merge into one sweep (~4% of rule A's
  continuations). The stream can't separate them.
- **The 1 ms tolerance** on futures (§4.3) is unresolved; exact `t` is the conservative choice.
- **Large real deals on thin contracts** may still be market-maker trades between two of their own
  accounts. The stream can't show that.
- **`coalescedOnly`** (an admin and metrics flag, not sent to users) is judged per price level: true
  when no single level's summed notional reached the threshold. Otherwise one large order filling
  several resting orders at one price would count as "coalescing-only". On Binance a level is one
  event, so nothing changes there.
- Not tested: the `10ms` spot variant, weekends, a REST cross-check of the deal streams.

---

## 10. Glossary

| term | meaning |
|---|---|
| deal | MEXC's word for one trade event: one fill against one resting order |
| fill | one taker order's execution against one resting order |
| level | one price at which an order traded; a sweep's `levels` = distinct prices |
| sweep | one taker order whose fills span one or more levels |
| price-time priority | matching order: best price first, then oldest order at that price |
| price impact | `|lastPrice / firstPrice − 1|`, how far one order moved the price |
| absorption | a large order hits a level without moving the price: the resting side held |
| `X0_X0` deal | MEXC spot synthetic deal on a 500 ms clock, dropped |
| self-trade (`M=1`) | MEXC futures deal with the same account on both sides, dropped |
| filtered deal | a deal a decoder drops by venue policy; counted, never judged |
| rule A | futures continuation: side, `t`, next id, price equal or toward the aggressor |
| close delay | time from a sweep's last fill until something proves it ended |
| idle timer | closes a sweep after `sweep-idle-close-ms` with no new fills (shard tick; MEXC futures) |
| `contractSize` | base quantity of one futures contract (`Instrument.quantityMultiplier()`) |

## 11. Where things are

| what | where |
|---|---|
| wire contract and all numbers | `external-docs/mexc/mexc-trades-empirical.md` |
| implementation plan | `.claude/plans/trade-mexc-plan.md` |
| transport | `marketdata/adapter/mexc/`: `MexcSpotStreamProtocol`, `MexcFuturesStreamProtocol`, `MexcAdapterConfig` |
| decoders (slice 2) | `MexcSpotDealsDecoder`, `MexcFuturesDealDecoder`, `MexcProtobufWire` |
| detection (shared with Binance) | `marketdata/core/trade/`: `TradeDetector`, `TradeState`, `TradeThresholds` |
| frontend contract | `.claude/docs/for-frontend/websocket-feed-api.md` §3.8 |
| capture and analysis | `C:\git\trade-capture\` (`capture_mexc.py`, `analysis\mexccap.py`, `analysis\mexc_analyze.py`, `analysis\out\mexc\report.md`) |
