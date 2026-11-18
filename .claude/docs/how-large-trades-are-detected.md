# Large-Trade Detection: How It Works and Why

An explainer for anyone joining the trade-alerts work. It covers what we detect and how,
the data behind each decision, what the approach cannot do, and the ideas we have parked for later.
No prior knowledge of trading mechanics or of this codebase is assumed.

**Status (2026-11-18)**: Binance spot and futures only. Detection runs inside the backend, with
sweep coalescing. Every `/ws` session receives each alert as a `TRADE` message, plus the last 100
in every `SNAPSHOT` (Phase 3, `websocket-feed-api.md` §3.8); an admin debug endpoint shows them too.
Phase 2 (slices 0–3) is done; slice 3's comparison against the capture on a long live run is still
open.

**Detailed sources**: `.claude/plans/trade-alerts-plan.md` (vision, phases, decisions D1–D6),
`.claude/plans/trade-detection-plan.md` (implementation plan; §10 holds every measured number).
This document summarizes them and adds the reasoning from the design discussions.
**MEXC**: `how-large-trades-are-detected-on-mexc.md` covers how MEXC differs, plus general trading
mechanics (why orders sweep, levels vs price impact).

---

## 1. Trades in five minutes

### 1.1 Resting orders vs. trades

An exchange's **order book** lists **resting orders**: people offering to buy (bids) or sell
(asks) at a given price. Our screener already shows the large ones ("walls"). A resting order is
only an intention. It costs nothing to place or cancel, and many are never filled.

A **trade** happens when someone accepts a resting order: they send an order that crosses the
spread and executes immediately. That person is the **taker** (or **aggressor**). The owner of
the resting order is the **maker**. A trade is real money changing hands, so it is a different and
complementary signal from walls.

### 1.2 Side and size

- Every trade has a buyer and a seller. The **side** of a trade is the **aggressor's** side: a
  "taker buy" consumed asks, a "taker sell" consumed bids. Side is not the same as "bullish" or
  "bearish". Show it as "aggressor bought / sold".
- The size of a trade is its **notional**: `price × quantity`, in quote currency. All Binance
  instruments we track are quoted in USDT, so notional is in USDT (≈ USD) with no conversion.
- A trade's distance from the mid-price is **not** a measure of importance (unlike walls). Trades
  happen at the best bid/ask by definition.

### 1.3 What Binance sends: `aggTrade`

We subscribe to Binance's `aggTrade` stream. Each message (a **frame**) looks like this:

```json
{"e":"aggTrade","E":1794935141806,"s":"XRPUSDT","a":735275123,
 "p":"0.95720000","q":"13481.90000000","f":1752809922,"l":1752809933,
 "T":1794935141806,"m":false}
```

| field | meaning |
|---|---|
| `s` | symbol |
| `p`, `q` | price and quantity |
| `T` | trade time (ms), when the match happened |
| `E` | event time (ms), when Binance sent the frame |
| `m` | "buyer is maker". `true` means the **seller** was the aggressor, so it is a **sell** |
| `f`, `l` | first and last raw trade id inside this aggregate |
| `a` | id of the aggregate itself |

An `aggTrade` is not one fill. Binance bundles several raw fills into one frame:

- **Spot**: one frame = **one taker order at one price**. If an order fills against five makers at
  the same price, that's one frame.
- **Futures**: one frame = consecutive fills at **one price and one side over up to ~100 ms**.
  It can therefore contain **several different takers' orders** merged together.

Both facts were checked against Binance's raw trade data (`trade-detection-plan.md` §10.1).

---

## 2. Sweeps and bursts

These two terms come up everywhere. They are different things and are handled by different parts
of the system.

### 2.1 Sweep

A **sweep** is **one large taker order that is too big for the best price level**, so it
consumes several price levels in a row. Binance prints one `aggTrade` per level, so one order
arrives as several frames (**fragments** or **fills**).

A real example from our capture, XRPUSDT spot, all six frames sharing one trade time `T`:

| # | price | qty | side | notional |
|---|---|---|---|---|
| 1 | 0.9572 | 13,481.9 | BUY | $12,905 |
| 2 | 0.9573 | 20,885.6 | BUY | $19,994 |
| 3 | 0.9574 | 1,402.1 | BUY | $1,342 |
| 4 | 0.9574 | 35,769.6 | BUY | $34,246 |
| 5 | 0.9574 | 5,365.4 | BUY | $5,137 |
| 6 | 0.9574 | 5,365.4 | BUY | $5,137 |

Frames 1–3 are one buyer walking up three price levels: **$34.2K in one order**, but no single
frame is that large. Frame 4 repeats the price 0.9574. On spot, one order at one price is always a
single frame, so frame 4 must be **a different order** that happened in the same millisecond.
Frames 5 and 6 are further separate orders.

If we judged each frame alone, the $34.2K sweep would be invisible. **Coalescing** means gluing a
sweep's fragments back together and judging the total.

### 2.2 Burst

A **burst** is **many separate orders, usually from different people, in the same direction over
seconds**, adding up to a large amount. Examples: an algorithm buying in small slices, or a wave
of liquidations. No single order is large, and the orders don't share a timestamp.

| | sweep | burst |
|---|---|---|
| who | one taker order | many orders, possibly many people |
| time span | one millisecond (spot), ~100–150 ms delivery (futures) | seconds |
| detected by | sweep coalescing (Phase 2, slice 3) | rolling time window (Phase 4, not built) |

Coalescing deliberately does **not** merge separate orders, even same-direction orders in the same
millisecond. Summing orders over time is the burst detector's job.

---

## 3. How the work was organized

The trade-alerts feature is split into phases (`trade-alerts-plan.md` §4):

| phase | what | status |
|---|---|---|
| 1 | Transport: subscribe to `aggTrade`, route frames to the right shard | done |
| 2 | Decode trades and detect large ones (this document) | done |
| 3 | Delivery to users over the WebSocket, frontend contract | done |
| 4 | Burst detection | planned |
| 5 | Bybit and MEXC | planned |
| 6 | Per-user thresholds | planned |

Phase 2 is split into slices (`trade-detection-plan.md`):

| slice | what | status |
|---|---|---|
| 0 | Capture raw trade data offline and analyze it to answer every design question | done |
| 1 | `BinanceAggTradeDecoder`: parse frames on the shard thread, count them | done |
| 2 | Judge each frame alone against a threshold; alert store; admin endpoint | done, with the real thresholds in `application.yml` |
| 3 | Sweep coalescing: judge whole sweeps instead of single frames | done; live comparison with the capture open |
| 4 | Timer that closes idle sweeps | deferred (§6.3) |

### 3.1 Why we captured data first (slice 0)

Several design questions could only be answered by looking at real market data: how fragments of
one order look, which threshold is sensible, and whether a timer is needed. Instead of guessing and
tuning on a live system, we recorded raw frames and analyzed them offline.

- **Capture**: a standalone Python script (`C:\git\trade-capture\capture.py`) recorded every
  `aggTrade` frame for the app's whole Binance universe (351 spot and 519 futures symbols) to
  gzipped files. The latest analyzed window is 2026-11-17 15:35–20:44 UTC (about 5.1 h): 2.27M
  spot and 9.88M futures events, including a sell-off (BTC −1.75% on ~5× normal volume) and the
  quieter hours after it. The capture keeps running, so the analysis can be repeated on more hours.
- **Analysis**: scripts in `C:\git\trade-capture\analysis\` (README there). Full tables are in
  `analysis/out/report.md`, per-symbol numbers in `out/per_symbol.csv`.
- **Ground truth**: for a few symbols the analysis also downloaded Binance's **raw** trades (one
  row per individual fill) and rebuilt the real taker orders, so the coalescing rule could be scored
  against what actually happened.

The capture is kept so that later ideas (other rules, burst windows) can be tested by re-running
scripts rather than by another live experiment.

---

## 4. How detection works

### 4.1 Flow

```
Binance aggTrade frame
  → shard consumer thread (the same thread that owns this instrument's order book)
  → BinanceAggTradeDecoder: parse price, qty, side, time, ids          (adapter: venue-specific)
  → TradeDetector.onTrade(...)                                          (core: venue-agnostic)
       → per-instrument sweep state: continue the open sweep, or close it and open a new one
       → on close: sum of notional ≥ threshold for this instrument?  → alert
  → TradeAlertStore (last N alerts in memory) + admin endpoint /api/monitoring/trade-alerts
  → TradeChannel (broadcaster thread, every 100 ms): new alerts → TRADE to every /ws session;
    last 100 replayed in every SNAPSHOT
```

Every trade of one instrument is processed by one thread, in order, so the per-instrument state
needs no locks. The trade path doesn't depend on whether the order book is in sync.

### 4.2 The coalescing rule

A new trade **continues** the open sweep when all of these hold:

1. **Same side** as the sweep.
2. **Same trade time `T`**: fragments of one order are matched in the same millisecond.
3. **Price moves strictly in the aggressor's direction**: for a buy, higher than the previous
   fragment. For a sell, lower. "Strictly" means direction only, not a fixed step. If levels in the
   book are empty, the next fragment can be several ticks away.
4. **Contiguous ids**, when the venue provides them: `firstTradeId == previous lastTradeId + 1`.

Otherwise the open sweep is **closed** (judged against the threshold) and the new trade **opens**
a new sweep.

Why each condition:

- **Side + `T`** is the natural signature of one matching event.
- **The price check** is needed because side + `T` alone over-merges on spot: 44.5% of
  same-side, same-`T` steps print at the **same price** as the previous fragment. Spot puts one
  order at one price in one frame, so an equal price is always a new order. The check cuts spot
  alerts at $30K by 17% and changes nothing on futures.
- **The id check** never mattered in the data (1 sweep in 3.2M) but costs nothing and guards
  against gaps. Venues without numeric ids (Bybit) use side + `T` + price only.
- We tried allowing `T` to differ by up to 100 ms on futures. Recall rose to 100% but precision
  fell to 74%, so the rule keeps exact `T`.

### 4.3 Spot vs. futures

The same rule and code serve both venues. Core has no venue-specific logic. The data behaves
differently, though:

| | spot | futures |
|---|---|---|
| one frame = | one order at one price | same-price, same-side fills over ≤ ~100 ms; may be several orders |
| sweep fragments arrive | together (94.8% in one `E`, spread p99 4 ms) | spread out; the last one is sent when Binance's window closes (`E − T` p90 150 ms) |
| large orders caught | all multi-level orders separate cleanly | 97% with coalescing (88% judging single frames) |
| alerts that are one real order | almost all | 89%; the other 11% are several orders Binance merged, which the stream can't separate |

Coalescing matters **more** on futures, not less (see 4.5).

### 4.4 Thresholds

Each instrument has one minimum notional: `screener.trade-alerts` in `application.yml`, bound to
`TradeAlertProperties` and looked up by `TradeThresholds`. A $30K default, plus per-symbol overrides
for busy symbols (7 spot, 28 futures).

**How the values were chosen** (`trade-detection-plan.md` §10.2): a flat $30K is unusable. It
produces ~630 spot and **~10,600 futures alerts per hour**, mostly on BTC and ETH. So we set an
**alert budget**:

- The floor is $30K for every symbol.
- If a symbol would fire more than **10 alerts per hour** at $30K, raise it to the next step of a
  fixed ladder (50K, 75K, 100K, 150K, 200K, 300K, 500K, … 5M) until it fires 10/h or less.
- Result: about **55 spot and 223 futures alerts per hour** over 5.1 h of data. Examples: BTC
  futures $5M, ETH futures $3M, BTC spot $500K, SOL futures $500K.
- The list was computed from the first 1.8 h and checked against 5.1 h, which added only
  PENGUUSDT futures. Re-deriving it over all 5.1 h would lower about half the overrides by one
  step, only because most of that time is quiet. That would add ~40% more alerts in busy hours, so
  the list was kept.

**Thresholds and coalescing are independent in code.** Coalescing decides what counts as one trade.
The threshold decides whether it is big enough. The sweep's summed notional is compared with the
threshold. They are linked in calibration: the budget was computed on **coalesced** alert counts.
Judging single frames (slice 2) with the same thresholds gives only ~27 spot / ~93 futures alerts
per hour.

The thresholds live in YAML, not Java, so changing them needs a restart, not a rebuild.

### 4.5 What the data says about size

Notional per event / per coalesced sweep:

| venue | median | 1 in 100 | 1 in 1,000 | 1 in 10,000 | largest |
|---|---|---|---|---|---|
| spot event / sweep | $23 / $34 | $6.7K / $8.3K | $36K / $48K | $162K / $320K | $816K / $2.0M |
| futures event / sweep | $50 / $55 | $17.8K / $24K | $122K / $183K | $443K / $980K | $25.0M / $25.0M |

- Most trades are tiny, and large ones are rare and concentrated: at a flat $30K, BTC + ETH make
  84% of alerts. Only 43 of 351 spot and 133 of 519 futures symbols ever reached $30K.
- **52% of spot and 60% of futures alerts exist only because of coalescing.** No single frame of
  those sweeps crossed the threshold. Most such sweeps have 4 or more fragments, and the largest had
  257. This is why slice 3 matters and why slice 2 is only a baseline.
- Alert rates swing with the market about 5–8×: 231 spot / 757 futures per hour in the sell-off,
  26–42 / 126–194 in the quieter hours.

### 4.6 What an alert contains

`TradeAlert`: instrument, aggressor side, total quantity, total notional, **VWAP** (volume-weighted
average price, `sumNotional / sumQty`), **first and last price** of the sweep, number of **fills**,
the **coalescing-only** flag, first and last trade id, trade time `T`, and `detectedAtMs` (local
clock when the alert fired).

- **First and last price** are **ours**, not Binance's: each frame has a single price, and only
  coalescing can say how far one order walked. In the example of §2.1 the sweep moved the price
  0.9572 → 0.9574 (about 2 bps).
- **Coalescing-only flag** (`coalescedOnly`). The sweep state remembers its largest single fill.
  The flag is true when no single fill reached the threshold by itself, so the alert exists only
  because the fills were summed. In §2.1 (with a $20K threshold) the three-fill sweep is
  coalescing-only: its largest fill was $19,994. Frame 4 ($34,246) would have alerted on its own. A
  one-fill alert is never coalescing-only, because its one fill is the whole sweep. Useful for
  tuning and possibly for the frontend ("block" vs. "sweep").
- **Stable id**: `VENUE:SYMBOL:firstTradeId`, e.g. `BINANCE_FUTURES:BTCUSDT:8285985083`. A trade id
  belongs to one sweep only, so the id is unique and the same across restarts and replays. Clients
  will key alerts by it (§7.2). A venue without numeric trade ids falls back to
  `VENUE:SYMBOL:t<T><B|S>`, which is not unique for two same-side orders in one millisecond; Phase 5
  decides a better identity when such a venue gets a decoder.

The health log's trades table shows, per venue and per 30 s, the alert count, how many of them are
coalescing-only (`coal`), and the alerts split by fills per sweep (1, 2–3, 4–10, >10). `coal` divided
by `alerts` is the live counterpart of the "52% / 60% only because of coalescing" figure in §4.5.

---

## 5. How we know the code is right: the replay fixture

A **test fixture** is fixed input data saved in the repository, so a test runs against the same
data every time. Ours is **one minute of real market data**:

- `src/test/resources/trade/binance-aggtrade-replay.jsonl`: 3,450 captured frames (770 KB) from
  2026-11-17 17:05:21–17:06:21 UTC, a busy minute after the sell-off. SOLUSDT and XRPUSDT on both
  venues, plus NEARUSDT, JASMYUSDT, THEUSDT and DYMUSDT futures. Each line is a capture record
  (`v` = venue, `d` = the Binance frame, unchanged).
- `src/test/resources/trade/binance-aggtrade-replay-expected.json`: the **answer key**. The Python
  analysis read the same frames and listed every alert it found: 97 under single-frame judging
  (`singleTrade`) and 91 under the coalescing rule (`coalesced`, 32 of them multi-fill, 14 only
  because of coalescing).

`TradeReplayTest` feeds every frame in order through the real Java code (`BinanceAggTradeDecoder` →
`TradeDetector` → `TradeAlertStore`) and checks that it finds **exactly** the same coalesced alerts:
none missing, none extra, same side, fills, time, quantity and notional.

Why it is valuable:

- **Two independent implementations must agree on real data**: Python for analysis, Java for
  production. A disagreement means one of them has a bug.
- Real data includes cases that hand-written tests miss: field order differing between venues,
  interleaved symbols, odd numbers.
- Built-in traps: the fixture uses its own test thresholds ($20K floor, $100K for SOLUSDT
  **futures** only). SOLUSDT **spot** alerts at $20K, so an override wrongly leaking across venues
  fails the test. The §2.1 XRP example is in the fixture too.
- Test thresholds are independent of `application.yml`, so retuning production never breaks it.

The test checks that the detector raises exactly the `coalesced` alerts, in the same order. The
`singleTrade` list is the independent check of the coalescing-only flag: an alert is
coalescing-only exactly when no single-frame alert falls inside its id range. The test also checks
that alert ids are unique. To regenerate the fixture exactly: `python make_fixture.py --at 1794935121208 --length 60` in
`C:\git\trade-capture\analysis\`.

---

## 6. Limitations

### 6.1 Static thresholds go stale

The thresholds are measured from about 5 hours of data from one afternoon (no Asia session, no
weekend) and maintained by hand.
Markets change:

- A **new listing** gets the $30K default. A hyped coin trading hundreds of millions on day one
  would flood users with alerts.
- A coin's volume can rise or fall by an order of magnitude within weeks.
- **Delisted or renamed** symbols leave dead entries in the list.
- A symbol can turn busy in hours: PENGUUSDT futures was quiet in the first 1.8 h of the capture
  and then alerted up to 47 times an hour at the $30K default.
- The capture covered a sell-off and quieter US-afternoon hours, but only the last 25 minutes of
  the US open.

Keeping them current means re-running the analysis and editing YAML. Section 7.3 describes the
planned fix. Not every change is a problem: the alert rate rising in a volatile market is arguably
correct, because more large trades really happen.

### 6.2 "Interesting" is not validated

The thresholds come from a **noise budget**: at most ~10 alerts per symbol per hour, never below
$30K. That guarantees every alert is in the far tail for its symbol (roughly the largest 1 in 1,000
to 1 in 10,000 trades) and that the volume is readable. It does **not** show that traders find
these alerts useful. That is an open product question (§7.6).

### 6.3 Alerts wait for the next trade

A sweep can only be closed when the instrument's **next** trade arrives, because only that trade
proves the sweep is over. Every alert waits for it, including a single large fill that would have
alerted at once under slice 2:

- Liquid symbols (≥ 1 trade/s): p90 under 360 ms, p99 under 2.6 s, worst seen 4.6 s.
- Mid-liquidity spot symbols (0.1–1 trades/s): p90 0.2 s, p99 6.3 s, worst 11.2 s.
- Thin symbols (< 0.1 trades/s) alerted 3 times at $30K in 5 hours. At $10K, 6% of their sweeps
  would wait over 10 s; at $5K 12%, sometimes over a minute.

At the shipped thresholds this is acceptable, so the idle timer (slice 4) is deferred. The real
delay is measurable live as `detectedAtMs − tradeTimeMs`. On futures there is an additional
unavoidable ~150 ms, because Binance sends a sweep's last fragment only when its window closes.

### 6.4 Other limitations

- **Futures merging**: ~11% of futures alerts are several orders that Binance's own 100 ms window
  merged. The stream contains no information to separate them.
- **Same-millisecond child orders are split**: an algorithm firing two $34K orders in one
  millisecond produces two alerts, not one $68K alert. This is deliberate (§2.2). Combining them
  is the burst detector's job.
- **Price movement is inside the sweep only**: first → last price says how far the order itself
  pushed the book, not what the market did afterwards.
- **Data loss on disconnect**: trades missed during a WebSocket reconnect are lost. Locally, Binance
  connections drop occasionally (163 s of futures outage in the capture). On the server this is
  rare.
- **Binance and MEXC**: MEXC's decoders are built and its streams are off by default until its
  live check (`how-large-trades-are-detected-on-mexc.md`); Bybit comes later in Phase 5. The decoder
  SPI allows several trades per frame, binary frames, missing numeric ids, a venue taker-order id,
  and deals dropped by venue policy.
- **In memory only**: the alert store holds the last 500 alerts and is lost on restart.

---

## 7. Future improvements and ideas

### 7.1 Show price movement in the UI

Each sweep alert has a first and last price. The UI can show how far the order moved the price and
make big moves look more urgent. Large-caps will usually show 0–5 bps. Thin coins can show 1% or
more, and that is where the indicator stands out. It is meaningful only because of coalescing: a
single frame has one price, so a one-fill alert always shows 0. The Phase 3 contract sends
`firstPrice` and `lastPrice` and the client computes the impact (`websocket-feed-api.md` §3.8.2).
A ready server-side number (e.g. `impactBps`) is worth adding as a new `data` field once a server
rule needs it ("only alert if impact > X bps"), so both use one definition.

### 7.2 Early alerts with updates

Instead of waiting for the sweep to close (§6.3), alert **the moment the sweep's running total
crosses the threshold**, then send **updates** as more fragments arrive, and a final message on
close. The frontend shows the updates as part of the same trade, not as new alerts. This covers
both a large first fragment and sweeps that cross the threshold only partway.

Not built now, because:

- The alert stops being a single event and gets a lifecycle (opened → updated → final). The JSON
  contract, frontend merge logic, replay-on-connect and the store all have to support that.
- With per-user thresholds (Phase 6), each user's alert would open at a different fragment.
- The benefit is small where most alerts happen. On spot, a sweep's fragments arrive within a few
  milliseconds, so the alert and its update would be almost simultaneous. The wait is for the next
  unrelated trade. It mostly matters on mid-liquidity symbols, where the idle timer (§7.4) is a
  simpler fix.

**Preparation**: every alert already has a **stable id** (`VENUE:SYMBOL:firstTradeId`, §4.6). Phase 3
sends it from day one and the frontend keys alerts by it. Adding updates later is then just another
message with the same id, with no contract change. Decide between this and the idle timer
once `detectedAtMs − tradeTimeMs` on live data or user feedback says the delay matters.

### 7.3 Volume-scaled (adaptive) thresholds

Across symbols, the threshold the budget policy picks follows each symbol's trading volume closely:

```
threshold ≈ c · V^b     V = hourly notional volume, b ≈ 0.81 (spot), 0.82 (futures)
```

The fit explains 95–97% of the variation, with a typical error of ±1.3–1.4×. That is about $8–9K
for $1M/h of volume, $350K at $100M/h and $2.3M at $1B/h. Computing each instrument's threshold
from its volume could reproduce the override list automatically, cover new listings, and drop the
manual list.

**But the fit compares symbols, not hours.** Tested on the capture with each symbol's trailing
15 or 60 min volume (`volume_scaled.py`, plan §10.5), it produced more alerts than the static list
in almost every hour: 282–459 futures alerts/h in quiet hours against 126–194, and almost no
relief in the sell-off. A symbol's large orders do not shrink when its volume drops. In one quiet hour BTC
spot's volume fell to about a third of its average, its scaled threshold fell from $500K to $134K,
and it alerted 119 times in 44 minutes. A usable version needs a slow baseline, a per-symbol
correction factor, or both, and has to be tested on the capture first.

Where the volume could come from:

- **Kline streams**: a teammate is building price/volume spike detection on KLINE streams for
  several exchanges. Its rolling volume per instrument is exactly this input, so adaptive thresholds
  become cheap once that lands. Keep the volume somewhere the trade side can read it.
- **`/ticker/24hr` REST**: Binance returns 24 h quote volume for all symbols in one call. Thresholds
  could be recomputed on a cold scheduled thread with no hot-path change.
- **The trade arm itself** could keep rolling volume in the per-instrument state. It works for any
  venue but is hot-path state.

Design caution: adapt **slowly** (a 24 h or 7 d window). A threshold that follows volume minute by
minute would keep the alert rate flat and hide the fact that the market got busier, which is itself
information. The formula was fitted on hourly volume, so it needs a re-fit if the input is 24 h
volume. Phase 2 stays static (decision D2). This is a candidate for a follow-up phase before
per-user thresholds.

### 7.4 Idle-sweep timer (slice 4)

A per-shard tick (starting idea: every 250 ms) closes sweeps that have received nothing for ~1 s,
bounding the delay of §6.3. The idle time must stay well above the futures window, or futures
sweeps get split. Planned together with Phase 4, whose burst windows need the same tick, or earlier
if per-user thresholds let users alert on thin coins.

### 7.5 Burst detection (Phase 4)

Same-side volume per instrument over a rolling window of N seconds, with its own threshold and a
rule against repeated alerts for one long run. Open questions are in `trade-alerts-plan.md` Q2–Q3.
The capture can be reused to test window lengths offline.

### 7.6 Find out what traders find interesting

What traders typically care about, beyond raw dollar size:

- **Relative size**: $100K is noise on BTC and huge on a small-cap. Size as a share of the symbol's
  volume is the natural measure, which §7.3 provides.
- **Price impact**: a $2M trade absorbed without moving the price and a $60K sweep that pushed it
  0.8% tell different stories (§7.1).
- **Interaction with walls**: the screener already classifies large resting orders. "A large sell
  just hit the big bid wall: eaten or held?" is a signal a plain trade feed can't give. It is
  related to the parked idea of labeling a vanished wall as **filled** vs. **pulled**.
- **Clusters**: bursts (§7.5).
- **Liquidations**: Binance futures publishes forced liquidations on a separate stream, a signal
  traders follow closely. Not in the plan yet.
- **Personal focus**: a BTC scalper and an altcoin trader want very different thresholds (Phase 6).

How to find out, from cheapest to most work:

1. **Ask traders**: show beta users an hour of alerts from the capture and ask which they would
   have acted on.
2. **Measure offline**: for every alert in the capture, compare the price 10 s / 1 min / 5 min later
   with the price after random moments. If alerts are followed by moves more often than chance, the
   signal has value. It needs only a new analysis script.
3. **Watch usage** after launch: which thresholds users set in Phase 6, which alerts they open.

Plan: ship the budget-based thresholds as v1 and use real user feedback to steer the next version.

### 7.7 Remaining roadmap

- **Phase 3, delivery (done)**: `TradeChannel` on the existing `FeedBroadcaster`, last-100 replay in
  every `SNAPSHOT`, behind the socket's existing entitlement gate; contract in
  `websocket-feed-api.md` §3.8. Still to do: re-run the threshold analysis on a longer capture
  that includes a full US open (§6.1).
- **Phase 4**: burst detection (§7.5), as its own `BURST` message type.
- **Phase 5**: Bybit (`publicTrade`) and MEXC decoders.
- **Phase 6**: per-user thresholds per ticker per exchange, with live updates to connected
  sessions.

---

## 8. Glossary

| term | meaning |
|---|---|
| maker / taker (aggressor) | owner of the resting order / the one whose order executes immediately against it |
| side | the aggressor's side: taker buy or taker sell |
| notional | `price × quantity`, in USDT |
| aggTrade / frame / event | one Binance `aggTrade` message |
| fragment / fill | one aggTrade that is part of a larger order |
| sweep | one taker order that consumed several price levels |
| coalescing | gluing a sweep's fragments back into one trade |
| burst | many separate same-side orders over seconds (Phase 4) |
| `T` / `E` | trade time / time Binance sent the frame |
| VWAP | volume-weighted average price of a sweep |
| bps | basis point, 0.01% |
| close delay | time from a sweep's last fill until the next trade proves it ended |
| fixture | fixed real data checked into tests, with its expected results |
| p90 / p99 | the value 90% / 99% of cases stay under |

## 9. Where things are

| what | where |
|---|---|
| decoder | `marketdata/adapter/binance/BinanceAggTradeDecoder` |
| SPI | `marketdata/spi/TradeDecoder`, `TradeSink`, `VenueTradeDecoderBinding`, `TradeDecoderRegistry` |
| detection | `marketdata/core/trade/`: `TradeDetector`, `TradeState` (the open sweep, held in the instrument's slot), `TradeThresholds`, `TradeAlert`, `TradeAlertStore` |
| config | `screener.trade-alerts` in `application.yml` → `config/TradeAlertProperties` |
| delivery | `feed/trade/TradeChannel` (`TRADE` messages, replay of `screener.trade-alerts.replay-size`); contract `.claude/docs/for-frontend/websocket-feed-api.md` §3.8 |
| admin endpoints | `GET /api/monitoring/trade-alerts?venue=&symbol=&limit=` (each alert with `id`, `threshold` and `coalescedOnly`), `GET /api/monitoring/trade-alerts/summary` |
| health log | trades table in `PipelineHealthLogger`: decoded/s, failures, alerts, coalescing-only alerts (`coal`) and alerts by fills per sweep, per 30 s |
| tests | `TradeReplayTest`, `TradeDetectorTest`, `TradeThresholdsTest`, `TradeAlertStoreTest`, `BinanceAggTradeDecoderTest`, `TradeChannelTest`, `FeedBroadcasterTest` |
| capture and analysis | `C:\git\trade-capture\` (`capture.py`, `analysis\`, `analysis\out\report.md`) |
| plans | `.claude/plans/trade-alerts-plan.md`, `.claude/plans/trade-detection-plan.md` |
