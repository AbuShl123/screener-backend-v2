# Trade Detection — Implementation Plan

Phase 2 of `.claude/plans/trade-alerts-plan.md`: decode Binance `aggTrade` frames on the shard
thread and detect large executed trades, including sweeps that arrive as several fragments.
**Delivery stays out of scope.** Alerts end in an in-memory store, a log line and an admin debug
endpoint, so each slice can be checked on a live run without a client.

**Read first**: `.claude/plans/trade-alerts-plan.md` (domain model, decisions D2–D5),
`.claude/plans/trade-streams-transport-plan.md` (what is wired today and the §1.3 sample frames),
`.claude/docs/market-data-pipeline-architecture.md` §13.

The work is split into slices. Each slice gets its own prompt, ships on its own, and ends with a
check. Slice 0 is an offline capture and analysis of raw trade frames. It answers every question
that needs market data: the starting thresholds (slice 2), the coalescing rule (slice 3) and whether
slice 4 is needed at all. The app slices then implement decisions already taken from that data and
check themselves against it.

---

## 1. Starting point

- `BINANCE_SPOT/TRADE` and `BINANCE_FUTURES/TRADE` stream, routed to instruments and counted per
  stream. `MarketEventHandler` has an empty `TRADE` arm.
- Observed rate (local run, 2026-11-17, ~21:00 UTC, a quiet hour): spot 25–70 frames/s over 351
  symbols, futures 190–450 frames/s over 519 symbols. That is about 15% of ring traffic. Even 10×
  in a volatile market is small next to depth, so the trade arm's cost is not a concern.
- Binance sends one trade per aggTrade frame. Other venues batch several trades per frame (Bybit
  `publicTrade`, MEXC), so the decoder contract must allow 0..n trades per frame.
- Binance's universe is USDT-quoted only (`BinanceInstrumentSource`), so `price × qty` is
  notional in USDT with no conversion.

---

## 2. Design

### 2.1 Flow

```
TRADE event (shard thread)
  └─ MarketEventHandler TRADE arm
       └─ slot.tradeDecoder().decode(slot, rawJson, tradeDetector)   // adapter: parse only
            └─ tradeDetector.onTrade(slot, price, qty, buy, time, firstId, lastId)  // core: 0..n calls
                 ├─ metrics: decoded count
                 ├─ slice 2: threshold check per trade
                 └─ slice 3: sweep accumulator in slot.trade() → threshold check on close
                      └─ TradeAlertStore.add(alert)   // rare path; allocation allowed
```

Decoding runs on the shard consumer thread, like depth. An instrument's trades all reach one
thread in order, so the per-instrument trade state needs no locks, the same guarantee the order
books rely on.

### 2.2 New and changed types

| Type | Package | Role |
|---|---|---|
| `TradeDecoder` | `marketdata/spi/` | `boolean decode(InstrumentSlot slot, String rawJson, TradeSink sink)`. Parses one frame and calls `sink.onTrade` once per trade in it. Returns `false` on a parse failure, having reported nothing. Stateless, so one instance can serve several venues. |
| `TradeSink` | `marketdata/spi/` | `void onTrade(InstrumentSlot slot, double price, double qty, boolean aggressorBuy, long tradeTimeMs, long firstTradeId, long lastTradeId)`. Ids are `-1` when a venue has no numeric trade id (Bybit's are strings), and core must not depend on them being present. |
| `VenueTradeDecoderBinding` | `marketdata/spi/` | `(Venue venue, TradeDecoder decoder)`, modeled on `VenueStrategyBinding`. |
| `TradeDecoderRegistry` | `marketdata/spi/` | Collects the bindings and rejects duplicates. `forVenue(venue)` returns `null` for a venue without one. |
| `BinanceAggTradeDecoder` | `adapter/binance/` | One instance serves both Binance venues (2.3). |
| `TradeDetector` | `marketdata/core/trade/` (new) | The core `TradeSink`: metrics, then detection. A single bean shared by the shards. Its own fields are only metrics (`LongAdder`s) and the store; per-instrument state lives in the slot. |
| `TradeState` | `marketdata/core/trade/` | Slice 3. Mutable per-instrument primitives for the open sweep. |
| `TradeAlert`, `TradeAlertStore`, `TradeThresholds` | `marketdata/core/trade/` | Slice 2. |
| `InstrumentSlot` | `core/slot/` | Gains `TradeDecoder tradeDecoder` (slice 1) and `TradeState trade` (slice 3), set at allocation in `InstrumentSlotTable`. |

`core/trade/` is hot path. Add it to the hot-path package list in `CLAUDE.md`.

### 2.3 `BinanceAggTradeDecoder`

- Jackson streaming over the flat object, like `BinanceDepthSyncStrategy`. A loop that switches on
  the field name: field order differs between venues (futures puts `a` before `s`), so nothing may
  depend on position.
- Reads `p` and `q` with `JavaDoubleParser` on the parser's char buffer, plus `T` (trade time), `m`,
  `f` and `l`. Skips everything else, including spot `M`, futures `nq` and `st`. Names are
  case-sensitive (`T` vs `E`, `m` vs `M`); a switch on exact strings handles that.
- Side: `m = true` means the buyer was the maker, so the aggressor **sold**: `aggressorBuy = !m`.
- Uses `q`, the full executed quantity. Futures `nq` is believed to exclude RPI fills; slice 0
  found `nq ≤ q` always and a difference on 0.6% of events (§10.1).
- Tracks which required fields were seen. If one is missing, it returns `false` and makes no sink
  call.

### 2.4 Handler arm

```java
case TRADE -> onTrade(slot, event);

private void onTrade(InstrumentSlot slot, MarketEvent event) {
    TradeDecoder decoder = slot.tradeDecoder();
    if (decoder == null) { noteUnhandledKind(event.stream); return; }   // TRADE bound without a decoder
    if (!decoder.decode(slot, event.rawJson, tradeDetector)) {
        metrics.recordTradeParseFailure(slot.instrument().venue());
    }
}
```

`MarketEventHandler` takes the `TradeDetector` in its constructor, the same way it takes the
classifier. Trades do not depend on the book's sync state: a trade is processed whether or not the
book is `SYNCED`.

### 2.5 Thresholds (slice 2)

```yaml
screener:
  trade-alerts:
    default-min-notional: 30000          # USDT
    min-notional:                        # per venue, per native symbol; optional
      BINANCE_FUTURES:
        BTCUSDT: 1000000
    recent-capacity: 500                 # alerts kept in TradeAlertStore
```

- Bound to a `TradeAlertProperties` record in `config/`. The values above only show the shape;
  `application.yml` ships the default and per-symbol entries from the slice 0 analysis (§10.2, Q1).
- `TradeThresholds.minNotional(Instrument)` does two map lookups (`EnumMap<Venue, Map<String,
  Double>>`, then the default). At under 1K trades/s that costs nothing, and Phase 6's per-user
  overrides layer onto one central lookup rather than a value copied into every slot.

### 2.6 Alerts and the store (slice 2)

- `TradeAlert` record: `Instrument instrument`, `boolean aggressorBuy`, `double vwap`, `double qty`,
  `double notional`, `double firstPrice`, `double lastPrice`, `int fills`, `long firstTradeId`,
  `long lastTradeId`, `long tradeTimeMs`, `long detectedAtMs`. In slice 2 `fills` is 1 and all three
  prices are equal. The ids (`-1` when the venue has none) identify the alert in the replay test and
  make a spot check against the venue's trade list exact.
- `TradeAlertStore`: a bounded ring of the last `recent-capacity` alerts, written by the shard
  threads and read by the REST thread. Alerts are rare, so a `synchronized` `ArrayDeque` is enough.
  It also keeps a count per `(venue, symbol)` since startup, for tuning (`ConcurrentHashMap.merge`,
  rare path).
- One `log.debug` line per alert. Not INFO: a $30K default can fire several times a second on the
  majors during a volatile market.
- Where the store finally lives (core or `feed/`) and the replay rules (Q4) are Phase 3 decisions.

### 2.7 Metrics and health log

`PipelineMetrics` gains, per venue: decoded trades, parse failures and (slice 2) alert count. The
notional distribution is not a live metric: slice 0 measures it offline, per symbol, which is what
threshold choice needs.

`PipelineHealthLogger` prints a second table, only for venues that decoded trades in the interval:

```
  trades            dec/s  fail  alerts
  binance/spot         51     0      11
  binance/futures     340     0     118
```

`fail` and `alerts` are counts over the 30 s interval, not rates. `dec/s` equals `trade/s` on
Binance (one trade per frame); a gap means frames are failing to decode.

---

## 3. Slice 0 — capture and analysis (offline, no app code)

Records raw trade frames for the whole Binance universe and answers, from that data, every question
the later slices depend on. The capture is kept: later ideas (other coalescing rules, Phase 4 burst
windows) are tested by re-running the analysis, not by another live run.

### 3.1 Capture

- A standalone script outside the repo. The data goes in a durable directory, not a temp dir.
  Built: `C:\git\trade-capture\capture.py`, data in `C:\git\trade-capture\data\`. Its README
  describes the line format, the side files and the caveats. The analysis scripts are in
  `C:\git\trade-capture\analysis\` (README there: how to re-run after more capture hours).
- **Universe**: every symbol the app trades on both Binance venues. Take the list from the app's
  `/api/tickers`, or from `exchangeInfo` with the same filters as `BinanceInstrumentSource` and
  `screener.discovery.excluded-symbols`, so the numbers match what the app will see.
- **Transport**: `@aggTrade` on `stream.binance.com/ws` (spot) and `fstream.binance.com/market/ws`
  (futures), with the per-connection stream and subscribe limits from the `streams.TRADE` YAML.
- **Format**: gzipped JSONL, rotated hourly. Each line holds the local receive time (ms), the venue
  and the raw frame unchanged.
- **Gaps**: reconnect on disconnect and log each outage (venue, connection, start, end). The
  analysis treats a jump in `a` within a symbol as a break, never as a sweep boundary.
- **Duration**: a few hours that include a busy period, at least the US open (~13:30–16:00 UTC),
  plus a quiet stretch for contrast. Thresholds taken from a quiet hour alone fire too often in a
  volatile market. Local Binance connections drop occasionally; if the outage log shows long gaps,
  run the capture on the server instead.
- Volume is small: about 500 frames/s × ~200 B is roughly 350 MB/hour before gzip.

### 3.2 Questions

1. **Spot sweeps.** Do the fragments of one taker order share `T` and have contiguous ids
   (`next.f == prev.l + 1`)? Do they arrive back to back?
2. **Futures aggregation.** As remembered from the docs, futures aggregates fills by price and
   taker side over 100 ms, so one event may merge several taker orders. Confirm: `E − T`
   distribution, whether frames come in 100 ms bursts, and whether a multi-level sweep shares `T`.
3. **Order.** Is `a` (agg trade id) strictly increasing per symbol within a connection?
4. **`nq` vs `q`** on futures: is `nq ≤ q` always, and how often do they differ?
5. **Notional per symbol**: p50, p99, p99.9 and max of `p × q`, per venue and symbol.
6. **Alert rate per threshold**: alerts/hour per venue and per symbol for candidate defaults (say
   10K, 30K, 100K) and for candidate per-symbol overrides, under both the single-trade rule
   (slice 2) and the coalescing rule (slice 3). How many alerts exist only because of coalescing,
   and how many fills those sweeps have.
7. **Close delay**: for each sweep that would alert, the time from its last fill to the next trade
   on the same symbol (the moment slice 3 can close it). Distribution per venue, with illiquid
   symbols reported separately.

### 3.3 Output

A **Capture findings** section appended to this plan with:

- the answers to 1–7;
- the slice 2 thresholds: the default and the per-symbol overrides, written as the YAML block;
- the slice 3 continuation rule;
- a go / no-go for slice 4, from the close-delay distribution;
- a **replay fixture**: real frames from the capture, kept under ~1 MB, covering a few symbols and
  including several multi-fill sweeps that would alert. It goes to
  `src/test/resources/trade/binance-aggtrade-replay.jsonl`, together with the alerts the analysis
  script finds in it under the test thresholds, single-trade and coalesced. Slices 2 and 3 replay
  it through the Java code and must find the same alerts.

**Fixture format** (read by `TradeReplayTest`, which skips while the files are absent):

- `src/test/resources/trade/binance-aggtrade-replay.jsonl`: capture lines unchanged
  (`{"r":…,"v":"BINANCE_FUTURES","c":…,"g":…,"d":{…raw frame…}}`), in capture order. The test
  feeds `d` to `BinanceAggTradeDecoder` for the slot of `(v, d.s)`.
- `src/test/resources/trade/binance-aggtrade-replay-expected.json`:

  ```json
  {
    "thresholds": {
      "defaultMinNotional": 20000,
      "minNotional": { "BINANCE_FUTURES": { "BTCUSDT": 200000 } }
    },
    "singleTrade": [
      { "venue": "BINANCE_FUTURES", "symbol": "SOLUSDT", "side": "SELL",
        "firstTradeId": 3653778376, "lastTradeId": 3653778393, "fills": 1,
        "tradeTimeMs": 1794931200123, "qty": 150.5, "notional": 21070.0 }
    ],
    "coalesced": [ ]
  }
  ```

  - `thresholds` are test thresholds chosen for the fixture (enough alerts, some near the
    boundary), independent of the shipped YAML. `minNotional` keys are venue, then native symbol.
  - An alert is judged as `notional ≥ minNotional`, with `notional = float(p) × float(q)` (one
    fill) or the sum over the fills in frame order (a sweep). `side` is `SELL` when `m` is true.
    `firstTradeId` is the first fill's `f` and `lastTradeId` the last fill's `l`; `tradeTimeMs` is
    `T`; `qty` is the sum of `q`.
  - The test matches alerts by `(venue, symbol, firstTradeId, lastTradeId)`; side, fills and
    trade time must be equal, and qty and notional equal to a relative 1e-9.
  - `coalesced` holds the alerts under the §10.3 rule, in emission order; slice 3 adds its
    assertion. `alternativeRules.side_T_ids.coalesced` holds them for the same rule without the
    price check. Alerts also carry `vwap`, `firstPrice` and `lastPrice`, which the test may ignore.

---

## 4. Slice 1 — decoder

**Goal**: every Binance trade frame decoded on the shard thread; no detection.

1. `spi/`: `TradeDecoder`, `TradeSink`, `VenueTradeDecoderBinding`, `TradeDecoderRegistry`.
2. `adapter/binance/`: `BinanceAggTradeDecoder` (2.3). `BinanceAdapterConfig` registers one
   binding per Binance venue, sharing one decoder instance. Update its class Javadoc.
3. `InstrumentSlot` gains `tradeDecoder`; `InstrumentSlotTable.allocate` fills it from the registry.
4. `core/trade/TradeDetector`: in this slice `onTrade` only records the decoded count.
5. `MarketEventHandler`: the `TRADE` arm (2.4). The handler is built by `DisruptorShardManager`,
   so pass the detector through there.
6. `PipelineMetrics` counters and the `PipelineHealthLogger` trades table (2.7), without the
   `alerts` column yet.

**Tests**

| Test | Covers |
|---|---|
| `BinanceAggTradeDecoderTest` (new) | Spot and futures frames from transport plan §1.3 decode to the right price, qty, time and ids; `m:true` → sell, `m:false` → buy; fields in a different order decode the same; a frame missing `p` or `q` returns `false` with no sink call; a non-numeric price returns `false`. |
| `TradeDecoderRegistryTest` (new) | Lookup by venue; duplicate venue fails; unknown venue returns `null`. |
| `MarketEventHandlerTest` | A `TRADE` event reaches the decoder with its slot and does not reach the sync strategy or classifier; a slot without a decoder is counted as unhandled; a parse failure is counted; the event is cleared. |
| `BinanceAdapterConfigTest` | Both Binance venues have a decoder binding. |

**Verify (local run, both TRADE streams on)**: `dec/s` matches `trade/s` per Binance row; `fail` is
0; ring free and depth rates unchanged.

---

## 5. Slice 2 — single-trade threshold

**Goal**: the first alerts, end to end inside the backend. Each aggTrade event is judged alone,
on purpose: this undercounts sweeps, and the count is the baseline slice 3 is compared against.

**Status**: built and verified on a live run. `application.yml` ships the §10.2 thresholds, and
`TradeReplayTest` passed on the fixture (§10.6). Slice 3 then replaced the per-trade check, so
`singleTrade` now serves only as the check on the coalescing-only flag.

1. `TradeAlertProperties` + YAML block (2.5), with the thresholds from the slice 0 findings.
2. `TradeThresholds`, `TradeAlert`, `TradeAlertStore` (2.5, 2.6).
3. `TradeDetector.onTrade`: after the decoded count, `notional ≥ minNotional(instrument)` → build an
   alert → `store.add`, alert counter, debug log.
4. Admin debug endpoints in `MonitoringController` (already admin-only under
   `/api/monitoring/**`):
   - `GET /api/monitoring/trade-alerts?venue=&symbol=&limit=`: recent alerts, newest first.
   - `GET /api/monitoring/trade-alerts/summary`: alert count per venue and symbol since startup,
     sorted descending. This answers "which symbols need an override".
5. `alerts` column in the health log.

**Tests**: `TradeThresholdsTest` (default, override, override for one venue does not leak into the
other); `TradeAlertStoreTest` (capacity bound, newest first, per-symbol counts);
`TradeDetectorTest` (below / at / above threshold, side carried through); `TradeReplayTest`
(new: the slice 0 fixture through `BinanceAggTradeDecoder` and `TradeDetector` finds exactly the
single-trade alerts the analysis found); a binding test for `TradeAlertProperties` against the
shipped YAML; a `MonitoringController` test for both endpoints.

**Verify**: alerts appear in the endpoint with sensible values (spot check a few against the
Binance UI trade list). After an hour, compare alerts/hour per venue and the top symbols in the
summary with the slice 0 single-trade simulation for a similar time of day; a large mismatch means
the capture was not representative or the code differs from the script.

---

## 6. Slice 3 — sweep coalescing

**Goal**: one taker order that walks several price levels is judged as one trade.

**Status**: built. `TradeReplayTest` finds exactly the fixture's `coalesced` alerts, in emission
order. Slice 2's live check was run before this slice landed; slice 3's own Verify is still open.

1. `TradeState` (`core/trade/`) in the slot, created with the slot whenever the venue has a trade
   decoder (`null` otherwise). Fields: `open`, `aggressorBuy`, `tradeTimeMs`, `firstTradeId`,
   `lastTradeId`, `sumQty`, `sumNotional`, `maxFillNotional`, `firstPrice`, `lastPrice`, `fills`.
   Add `lastFillAtMs` (local clock) with slice 4.
2. `TradeDetector.onTrade` replaces the slice 2 per-trade check:
   - If a sweep is open and the trade **continues** it, accumulate.
   - Otherwise **close** the open sweep (judge `sumNotional` against the threshold, alert with
     `fills`, `vwap = sumNotional / sumQty`, first and last price), then open a new sweep with
     this trade.
3. **Continuation rule** (§10.3): same side, same `tradeTimeMs`, the price moves strictly in the
   aggressor's direction (buy: `price > lastPrice`, sell: `price < lastPrice`), and, when both ids
   are known, `firstTradeId == lastTradeId + 1`. Without ids (future venues), side, time and price.
   After a reconnect the ids jump, so the id check closes the old sweep, as the analysis's break did.
4. **Coalescing-only flag**: `TradeAlert.coalescedOnly` is `maxFillNotional < minNotional`, true
   when no single fill would have alerted. Always false for one fill.
5. **Stable id**: `TradeAlert.id()` is `VENUE:SYMBOL:firstTradeId`
   (`BINANCE_FUTURES:BTCUSDT:8285985083`), the same across restarts and replays. Clients key alerts
   by it. A venue without numeric ids falls back to `VENUE:SYMBOL:t<tradeTimeMs><B|S>`, which is not
   unique for two same-side orders in one millisecond; Phase 5 decides its identity.
6. Metrics, for alerted sweeps only: fills per sweep (`1, 2–3, 4–10, >10`) and the coalescing-only
   count, both per venue. The health log's trades table gains `coal` and `fills 1/2-3/4-10/>10`
   columns. `/api/monitoring/trade-alerts` shows `id` and `coalescedOnly`.

**Known gap**: a sweep closes only when the instrument's next trade arrives. On an illiquid symbol
that can be seconds or minutes, and `detectedAtMs` includes the wait. At the shipped thresholds this
stays rare and short (§10.4), so slice 4 is deferred. A sweep still open when its instrument leaves
the universe is never judged.

**Tests** (`TradeDetectorTest`, synthetic sequences): one large trade alerts when the next trade
closes it, not before, and not when another instrument trades; ten contiguous fills, each below the
threshold, summing above it → one alert, `fills = 10`, correct VWAP, first/last price and ids,
coalescing-only; the same fills summing below → no alert; a side change, a time change, an id gap,
an equal price and a price reversal each close the sweep; ids `-1` fall back to side + time + price;
the fill and coalescing-only metrics. `TradeReplayTest`: the fixture finds exactly the `coalesced`
alerts in the expected file, in emission order (§10.6); each alert's coalescing-only flag agrees with
the `singleTrade` list (no single-trade alert inside its id range); ids are unique.

**Verify**: compare alerts/hour with the slice 0 coalesced simulation (§10.1 Q6: spot 95, futures
336 per hour on average) and the `coal` share with Q6's 46% spot / 62% futures; inspect a few
multi-fill alerts against the Binance trade list.

---

## 7. Slice 4 — shard tick and stale-sweep flush (deferred, §10.4)

Not part of Phase 2. Build it with Phase 4, whose burst windows need the same tick, or earlier if
Phase 6 lets users set thresholds low enough to alert on thin symbols.

- A scheduler publishes one `TICK` event per shard every `tick-interval` (starting idea 250 ms).
  `MarketEventHandler` handles it before the slot lookup (no instrument).
- On a tick, the handler scans its shard's slots (`id & mask == shard`, about a thousand checks)
  and closes sweeps whose `lastFillAtMs` is older than `sweep-idle-close-ms`.
- Both values are tunables; starting values tick 250 ms, idle 1000 ms (§10.4). This is a core edit
  to the publisher and handler; Phase 4's burst windows reuse the same tick for expiry and
  cooldown.
- Detailed when started.

---

## 8. Docs (per slice, as the code lands)

- `CLAUDE.md`: Module Map (`core/trade/`, `TradeDecoder` SPI), the hot-path package list, the
  Streams/Disruptor bullets ("dropped by the consumer until its decoder lands" → decoded and
  detected).
- `.claude/docs/market-data-pipeline-architecture.md`: §12 trade row, §13 recipe (a trade-like
  kind adds a decoder binding, not a sync strategy).
- `.claude/plans/trade-alerts-plan.md`: Phase 2 row points here; Q5 and the Q1 starting thresholds
  answered from slice 0; Q1 tuning notes from slice 2.

---

## 9. Out of scope

- Delivery: `TradeChannel`, replay on connect, entitlement, frontend contract (Phase 3).
- Burst detection (Phase 4), except that slice 4's tick is built to serve it and the slice 0
  capture can be reused to test burst windows.
- Bybit and MEXC decoders (Phase 5). The SPI is shaped for them (0..n trades per frame, optional
  ids) but nothing else is.
- Per-user thresholds (Phase 6).
- Allocation of the Jackson parser per frame: the depth path does the same today; revisit for
  both together if it shows up in a profile.

---

## 10. Capture findings (slice 0)

**Data**: 2026-11-17 15:35–20:44 UTC. Spot covers 5.15 h and futures 5.11 h (163 s of futures
outages excluded, all before 15:55). 2.27M spot and 9.88M futures events over the full universe
(351 spot, 519 futures symbols). It includes a sell-off (BTC −1.75% between 15:40 and 15:50, on
about 5× normal volume) and the quieter US-afternoon hours after it. It has no Asia session and no
weekend, so rates and thresholds are still one afternoon's. The capture keeps running. Scripts:
`C:\git\trade-capture\analysis\` (its README covers re-running). `analysis/out/report.md` has every
table for the derived policy, `out/configured/report.md` the same tables for the thresholds in
`application.yml` (`analyze.py --thresholds`), `out/hourly.md` the hour-by-hour view and
`out/volume_scaled.md` the §10.5 test. Re-run after more hours and update the numbers here.

### 10.1 Answers

1. **Spot sweeps.** One taker order's fragments share side and `T`, have contiguous ids, and
   arrive together: 95.0% of multi-fill sweeps sit in one `E`, receive spread p99 3 ms. Raw trades
   (REST, `verify_raw_trades.py`) confirm that one spot aggTrade is one taker order at one price:
   every aggTrade holds a single raw-trade time, and `T` equals it. **Same side + same `T` does not
   identify one order, though**: 43.8% of such steps print at the *same price* as the previous
   fragment. By the aggregation rule that is a second order in the same millisecond. Another 0.6%
   reverse the price. Spot ids are always contiguous, so they never separate orders.
2. **Futures aggregation.** Confirmed with raw trades. A futures aggTrade is a run of consecutive
   raw trades with one price and side, closed by a price or side change or after about 100 ms.
   9–26% of futures aggTrades hold fills from several moments, so they merge several taker
   orders; `T` is the first fill's time. Consequences:
   - The last fragment of a sweep is sent only when its window closes. Multi-fill sweeps span
     p90 150 ms of `E` (only 6.1% sit in one `E`), and `E − T` is p90 150 ms. Futures alerts
     therefore land at least ~150 ms after the trade.
   - A multi-level sweep shares `T`: 99.2% of same-`T` steps move toward the aggressor, and none
     repeat a price.
   - Ground truth: taker orders rebuilt from raw trades, 4 symbols, about 2 min each, at a $30K
     threshold. Same-`T` coalescing alerts on 97% of large orders (single events: 88%), and 89%
     of its alerts contain one order at or above the threshold. The other 11% are several orders
     that Binance's own window merged, which aggTrade cannot separate. Allowing `T` to differ by
     up to 100 ms reaches 100% recall but only 74% precision, so the rule keeps exact `T`.
3. **Order.** `a` rises strictly per symbol within a socket on both venues, with no jumps. Futures
   `f ≠ prev.l + 1` in 1,595 of 9.88M pairs, mostly gaps of 1 (ids never published). Reconnects
   lost 435K futures aggTrades over 163 s of local outages.
4. **`nq` vs `q`.** `nq ≤ q` always. They differ on 0.52% of events (`nq = 0` on 0.15%), worth 0.3%
   of notional; 114 of 60,740 events at or above $30K would fall below it by `nq`. Use `q`. `st` is
   always 1.
5. **Notional.** Per event / per coalesced sweep:

   | venue | p50 | p99 | p99.9 | p99.99 | max |
   |---|---|---|---|---|---|
   | spot event / sweep | 23 / 34 | 6.7K / 8.3K | 36K / 48K | 162K / 320K | 816K / 2.0M |
   | futures event / sweep | 50 / 55 | 17.8K / 24K | 122K / 183K | 443K / 980K | 25.0M / 25.0M |

   Large trades are concentrated: at a flat $30K, BTC + ETH make 84% of alerts on both venues,
   and only 43 of 351 spot and 133 of 519 futures symbols reached $30K at all.
6. **Alert rates** (alerts/hour, coalesced with §10.3's rule): a flat $30K gives spot 627 and
   futures 10,559, so a flat threshold is unusable on futures. The §10.2 YAML gives spot 55 and
   futures 223. Under the same YAML, single-event judging (slice 2) gives 27 and 93: **52% of spot
   and 60% of futures alerts exist only because of coalescing**, mostly sweeps of 4+ fills (counts
   by 2–3 / 4–10 / >10 fills: spot 24 / 54 / 69, futures 75 / 270 / 339). Of all alerted sweeps,
   28–34% are a single fill and 26–31% have more than 10 fills (max 257). The rate swings with the
   market about 5–8×: 231 / 757 per hour in the sell-off, 26–42 / 126–194 in the quieter hours.
7. **Close delay** (last fill → next trade of the symbol). For alerting sweeps at the §10.2
   thresholds: liquid symbols (≥ 1 event/s) p90 ≤ 360 ms, p99 ≤ 2.6 s, max 4.6 s. Mid-liquidity
   spot (0.1–1/s) p90 0.2 s, p99 6.3 s, max 11.2 s, with 0.8% over 10 s. Futures mid-liquidity max
   3.2 s. Thin symbols (< 0.1 event/s, 229 on spot) alerted 3 times at $30K in 5 h. At $10K, 6% of
   their sweeps would wait more than 10 s (p99 23 s); at $5K 12% (p99 85 s).

### 10.2 Slice 2 thresholds

The floor is $30K. A symbol whose coalesced alert rate at the floor is above 10/h gets the lowest
step of a fixed ladder (50K, 75K, 100K, 150K, 200K, 300K, 500K, …, 5M) that brings it to 10/h or
less. The list below was derived from the first 1.8 h of the capture, where the sell-off was 23%
of the covered time, plus `PENGUUSDT` futures from the later hours.

**Kept over the re-derived list.** The same rule over all 5.1 h, where the sell-off is 8% of the
time, lowers about half the overrides by one ladder step (spot ETH 150K, SOL 100K; futures SOL
300K, HYPE and XRP 150K; 22 futures overrides instead of 28). That reflects how much of the data is
quiet, not a change in the market. The shipped list already holds every symbol to the cap in every
full hour, and the lower one only adds alerts, most of them in the busiest hours (`hourly.py`):

| alerts/h (spot / futures) | sell-off 15:35–16:00 | 16:00 | quieter 17:00–20:44 | whole capture |
|---|---|---|---|---|
| this list | 231 / 757 | 67 / 252 | 26–42 / 126–194 | 55 / 223 |
| re-derived over 5.1 h | 333 / 1,095 | 106 / 387 | 38–52 / 156–249 | 77 / 309 |

`PENGUUSDT` futures became active only after the first 1.8 h: at the floor it alerted 21.5/h over
the capture, and 47 in its worst hour. Derived hour by hour, its threshold would have been 75–100K
from 18:00 on. At 50K it stays under the cap over the capture and under 25/h in every full hour.

A symbol's own best threshold moves with the market. Derived from each hour alone, it moves 1–2
ladder steps across the quieter hours. In the sell-off it rises 1–4 steps (spot BNB 30K → 200K,
futures HYPE 150K → 750K). Any static list therefore swings about 5–8× in alert volume with the
market. Revisit the list once the capture covers an Asia session and a weekend.

```yaml
screener:
  trade-alerts:
    default-min-notional: 30000
    min-notional:
      BINANCE_SPOT:
        BTCUSDT: 500000
        ETHUSDT: 200000
        SOLUSDT: 150000
        BNBUSDT: 75000
        NEARUSDT: 50000
        XRPUSDT: 50000
        ZECUSDT: 50000
      BINANCE_FUTURES:
        BTCUSDT: 5000000
        ETHUSDT: 3000000
        SOLUSDT: 500000
        ZECUSDT: 300000
        HYPEUSDT: 200000
        XRPUSDT: 200000
        1000PEPEUSDT: 150000
        AAVEUSDT: 150000
        BNBUSDT: 150000
        NEARUSDT: 150000
        DOGEUSDT: 100000
        ADAUSDT: 75000
        AVAXUSDT: 75000
        ENAUSDT: 75000
        GRAMUSDT: 75000
        LINKUSDT: 75000
        SUIUSDT: 75000
        UNIUSDT: 75000
        ASTERUSDT: 50000
        BCHUSDT: 50000
        BREVUSDT: 50000
        FILUSDT: 50000
        INJUSDT: 50000
        MMTUSDT: 50000
        PENGUUSDT: 50000
        PUMPUSDT: 50000
        TRUMPUSDT: 50000
        WLDUSDT: 50000
```

Other policies, derived over all 5.1 h (alerts/h spot / futures, overrides in brackets): floor 30K
cap 10/h → 77 / 309 (4 / 22); floor 20K → 108 / 433 (7 / 31); cap 5/h → 53 / 191 (8 / 31); floor
50K → 42 / 197 (3 / 12). `analyze.py --floor --cap` prints any other combination, and
`analyze.py --thresholds <application.yml>` evaluates a configured list.

### 10.3 Slice 3 continuation rule

A trade continues the open sweep when it has the **same side and the same `T`**, its **price moves
strictly in the aggressor's direction** (buy: `price > lastPrice`, sell: `price < lastPrice`),
and, when both ids are known, `firstTradeId == lastTradeId + 1`.

- The price check separates orders that share a millisecond on spot (an equal price is always a
  new order). It cuts spot alerts at $30K by 14% and changes nothing on futures.
- The id check never mattered in the data (2 sweeps in 7.8M), but it costs nothing. Keep it as a
  guard. Without ids, the rule is side + `T` + price.
- Trade-off: same-millisecond child orders of one algorithm become separate alerts. The fixture
  has two ~$34.2K XRP spot buys in one millisecond, which this rule reports as two orders and
  `side_T_ids` as one $78.8K sweep. Summing several orders over time is the Phase 4 burst
  window's job.

### 10.4 Slice 4: deferred

With the §10.2 thresholds, sweeps close fast enough without a tick: 99% of liquid-symbol alerts
within 2.6 s, the worst case 11.2 s on a mid-liquidity spot symbol. Thin symbols almost never
alert at $30K (3 alerts in 5 h). The tick becomes worth building with Phase 4 (bursts need it
anyway) or with Phase 6, once users can set thresholds below about $10K on thin coins. Starting
values when built: tick 250 ms, idle close 1000 ms of receive time. The idle must stay well above
the futures window, because a futures sweep's last fragment arrives about 150 ms after the others
(receive spread p99 290 ms, spikes to 5.8 s). A shorter idle would split futures sweeps.

### 10.5 Volume-scaled thresholds (later)

Across symbols, the threshold the cap rule picks follows the symbol's hourly notional volume `V`
closely: `x ≈ 10^a · V^b`, with b = 0.81 (spot) and 0.82 (futures), R² 0.95 / 0.97, typical error
±1.3–1.4×. That puts x at about $8–9K for $1M/h of volume, $350K at $100M/h and $2.3M at $1B/h. The
fit held from 1.8 h to 5.1 h of data.

**It does not carry over to changes over time.** Judging each sweep against
`max(30K, 10^a · V^b)`, with `V` the symbol's own trailing volume (`volume_scaled.py`), alerts more
than the static list in every hour except spot in the sell-off:

| alerts/h (spot / futures) | sell-off | 16:00 | quieter 17:00–20:44 |
|---|---|---|---|
| static list (§10.2) | 231 / 757 | 67 / 252 | 26–42 / 126–194 |
| trailing 60 min volume | 217 / 806 | 63 / 301 | 48–225 / 282–454 |
| trailing 15 min volume | 203 / 793 | 106 / 414 | 66–107 / 318–459 |

When a symbol's volume drops, its large orders do not shrink with it. In the 20:00 hour BTCUSDT
spot traded $28M/h (its capture average is $80M/h), the scaled threshold fell to 134K, and it
alerted 119 times in 44 minutes against the static 500K. The fit describes how symbols differ,
not how one symbol changes. 10 futures symbols exceed 10/h under it (BTC, ETH, ADA and AAVE at
20–28/h).

So the "adaptive thresholds from rolling volume" idea in `trade-alerts-plan.md` §4 needs more than
the formula. Options to test on the capture before building it: a slow volume baseline (24 h or
longer) instead of a trailing hour, a per-symbol calibration factor on top of the fit, or the fit
used only to seed thresholds for symbols without an override (new listings), with the static list
kept for the rest. D2 keeps Phase 2 static.

### 10.6 Replay fixture

`src/test/resources/trade/binance-aggtrade-replay.jsonl` holds 3,450 capture lines (770 KB) from
2026-11-17 17:05:21–17:06:21 UTC, a burst after the sell-off: SOLUSDT and XRPUSDT on both
venues, plus NEARUSDT, JASMYUSDT, THEUSDT and DYMUSDT futures. Test thresholds: floor 20K, and
100K for `BINANCE_FUTURES` `SOLUSDT` only (SOLUSDT spot alerts at 20K, so a leaking override would
show). Expected alerts: 97 `singleTrade`; 91 `coalesced` (32 multi-fill, 14 only because of
coalescing, up to 13 fills, 23 sells); 90 under `side_T_ids`, and the two rules differ on 5 spot
alerts. No sweep is still open at the end. `TradeReplayTest` passes against slice 2. To regenerate
exactly: `python make_fixture.py --at 1794935121208 --length 60`.
