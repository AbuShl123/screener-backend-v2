# MEXC Trade Alerts — Implementation Plan

Phase 5 of `.claude/plans/trade-alerts-plan.md`, MEXC first: large-trade alerts for MEXC spot and
futures, through the same detector, store and `TradeChannel` as Binance. Trade alerts matter most on
smaller exchanges, so MEXC ships before Bybit.

**Read first**:
- `.claude/docs/how-large-trades-are-detected-on-mexc.md`: the explainer. What MEXC sends, why deals
  are filtered, how sweeps are grouped and closed, the trading mechanics behind it.
- `external-docs/mexc/mexc-trades-empirical.md`: the wire contract and every measured number.
- `.claude/plans/trade-detection-plan.md`: §2 is the detector design, §10 has the Binance findings this
  plan mirrors.
- `.claude/docs/market-data-pipeline-architecture.md` §13.

Slice 0 was an offline capture and analysis. It answered every question that needed market data.
Each app slice implements decisions already taken from that data and checks itself against it.

| Slice | What | Status |
|---|---|---|
| 0 | Capture and analysis (offline) | done |
| 1 | Transport: both `TRADE` streams connect, subscribe and route | done |
| 2 | Decode and detect; spot closes sweeps at frame end; spot replay fixture | done |
| 3 | Idle close: shard tick for MEXC futures; futures replay fixture | done |
| 4 | Thresholds file, per-venue floor, MEXC thresholds re-derived; live check; streams on by default | in progress |

---

## 1. Starting point

- The Binance trade path is complete: `TradeDecoder` SPI → `TradeDetector` (sweep coalescing,
  thresholds) → `TradeAlertStore` → `TradeChannel`. Everything after the decoder is venue-agnostic
  except the sweep continuation rule (2.4).
- Both MEXC `TRADE` streams are bound, configured and off by default (slice 1). With no decoder bound,
  the handler counts their events as unhandled.
- `MarketEvent` carries binary frames in `rawBytes`, but `TradeDecoder.decode` takes only a `String`.
- `Instrument.quantityMultiplier()` holds futures `contractSize`; the depth path already multiplies by it.
- The MEXC universe is USDT-quoted only, so `price × qty` is notional in USDT, as on Binance.
- A sweep closes only when its instrument's next trade arrives. `TradeAlertProperties` has one global
  `default-min-notional`.

### What differs from Binance (`mexc-trades-empirical.md`)

| | Binance aggTrade | MEXC spot | MEXC futures |
|---|---|---|---|
| Encoding | JSON, 1 trade per frame | Protobuf (body 314), 100 ms batches | JSON, `data` array **newest first** |
| One event is | one taker order at one price (spot) | one fill against one resting order | one fill against one resting order |
| Quantity | base | base | **contracts** × `contractSize` |
| Taker-order id | none | **exact** (`tradeId` left half) | none |
| Numeric trade id | `f` / `l`, contiguous per symbol | field 6, on real fills only | `i`, a sequence shared across contracts |
| Equal price in one sweep | never (new order) | normal (32% of steps) | normal |
| Deals to drop | none | `X0_X0` synthetic: 85% of deals, 93.5% of notional | `M=1` self-trades: 72% of deals, 11% of notional |
| One sweep's fills arrive | together | in one frame (99.96%) | split across frames in 59% of multi-fill sweeps, ≤ 213 ms apart |
| Real flow | large | $1.4M/h; largest order in 4 h $96K | $180M/h |

---

## 2. Design

### 2.1 Flow

```
TRADE event (shard thread)
  └─ MarketEventHandler TRADE arm
       ├─ rawBytes != null ? decoder.decode(slot, rawBytes, sink) : decoder.decode(slot, rawJson, sink)
       │    └─ MEXC decoder: parse, filtered deals → sink.onFiltered (2.5), the rest oldest first
       │         └─ TradeDetector.onTrade(slot, price, qty, buy, time, firstId, lastId, takerOrderId)
       │              └─ continuation rule (2.4) → on close: threshold → TradeAlertStore → TradeChannel
       └─ detector.onFrameEnd(slot)        closes the open sweep if the venue closes at frame end (2.6)

TICK event (shard thread, every tick-interval-ms)
  └─ MarketEventHandler, before the slot lookup
       └─ for each slot of this shard: detector.closeIfIdle(slot)   venues with an idle close (2.6)
```

### 2.2 SPI and core changes

| Type | Change |
|---|---|
| `TradeDecoder` | Adds `default boolean decode(InstrumentSlot slot, ByteBuffer frame, TradeSink sink)` returning `false`. Binary decoders override it. Same absolute-index rule as `MexcSpotFrameReader`: never move `position` / `limit`. |
| `TradeDecoder` | Adds `default boolean reportsMakerFills()` returning `false`. `true` means one event is one fill against one resting order, so an equal price can continue a sweep (2.4). |
| `TradeDecoder` | Adds `default boolean closesSweepAtFrameEnd()` returning `false`. `true` means one taker order's fills always arrive in one frame, so the sweep still open after a frame is complete (2.6). |
| `TradeSink.onTrade` | Gains `long takerOrderId`: `-1` when the venue has none (Binance, MEXC futures). |
| `TradeSink` | Gains `onFiltered(InstrumentSlot slot)`, called once per deal a decoder drops by venue policy (2.5), for the health log. |
| `TradeState` | Gains `takerOrderId`, `levels`, `levelNotional` / `maxLevelNotional` (2.8), `lastFillAtMs` and `closedByIdle` (2.6), and the flags `makerFills` and `closeAtFrameEnd`. `InstrumentSlot` copies both flags from the decoder when it creates the state. |
| `TradeDetector` | Gains `onFrameEnd(slot)` and `closeIfIdle(slot)` (2.6). Takes a clock (`LongSupplier`, default `System::currentTimeMillis`) for `lastFillAtMs`, the idle check and `detectedAtMs`, so the replay test can drive it. |
| `MarketEventHandler` | The `TRADE` arm picks the overload by payload: `rawBytes` if set, else `rawJson`. It then calls `onFrameEnd`. It depends on `TradeDetector` (core) instead of `TradeSink`: frame end and idle close are core calls, not part of the decoder SPI. It handles `TICK` events (2.6). |

### 2.3 Transport (adapter) — built in slice 1

- `MexcSpotStreamProtocol` takes the channel prefix from its constructor: depth
  `spot@public.aggre.depth.v3.api.pb@100ms@`, trade `spot@public.aggre.deals.v3.api.pb@100ms@`.
- `MexcFuturesStreamProtocol` takes the channel name (`depth` / `deal`) and builds `sub.<ch>`,
  `push.<ch>` and `rs.sub.<ch>` from it.
- `MexcAdapterConfig` binds a `VenueStreamBinding` for each `TRADE` stream. Slice 2 adds one
  `VenueTradeDecoderBinding` per venue.
- YAML: `venues.SPOT.streams.TRADE` (`MEXC_SPOT_TRADE_ENABLED`, 30 per connection, up to 30
  connections) and `venues.FUTURES.streams.TRADE` (`MEXC_FUTURES_TRADE_ENABLED`, 300 per connection,
  chunk size 1). Both are off until slice 4 verifies.

### 2.4 Sweep continuation rule

A trade continues the open sweep when it has the same side and:

1. **Both taker-order ids are known**: they are equal. Nothing else is checked. This covers MEXC spot
   exactly.
2. **Otherwise**, the Binance rule (`trade-detection-plan.md` §10.3): same `tradeTimeMs` and, when
   both ids are known, `firstTradeId == lastTradeId + 1`. There is one change for venues whose
   `reportsMakerFills()` is true: the price may also stay **equal**, not only move toward the
   aggressor. This is *rule A*. On spot it recovers 99.4% of multi-fill orders exactly; Binance's
   strict rule recovers 61.5%.

The time must be **exactly equal**. Futures shows extra 1 ms steps that look like one order crossing a
millisecond (about 4,000 in 4 h). Allowing them would change about a quarter of futures alerts, and
that cannot be proven from the stream. Exact time matches spot and Binance. Binance's behaviour must
not change: its replay test is the guard.

### 2.5 Filtering deals that are not real trading

The decoder decides, because only the adapter knows the format. Each dropped deal goes to
`sink.onFiltered` instead of `onTrade`: it never opens, continues or closes a sweep and is never
judged.

- **Spot `X0_X0` deals** (taker and maker fill numbers both 0): drop all. They are synthetic paired
  orders on a per-symbol 500 ms clock. Unfiltered, they would make 198 spot alerts in 4 h instead
  of 10.
- **Futures `M=1`**: drop all. `M=1` is a self-trade (same account on both sides). `M` belongs to the
  taker order, so dropping it practically never breaks a real sweep. `O` is not used.

### 2.6 Closing a sweep

The next trade is too slow a signal on MEXC. Once filtered deals are dropped, real deals are sparse.
38% of futures alerts come from contracts with < 0.1 real deals/s, where the next deal comes at p90
76 s and at worst 12 min. Each venue gets the close that fits its data.

**Spot: at the end of the frame.** `MexcSpotDealsDecoder.closesSweepAtFrameEnd()` is `true`. After
every decode of a `TRADE` frame, successful or not, the handler calls `detector.onFrameEnd(slot)`. If
`TradeState.closeAtFrameEnd` is set and a sweep is open, it is closed and judged. Inside a frame, the
taker id still separates orders. An order's fills share one frame in 99.96% of multi-fill orders,
and none of the 0.04% exceptions was large. Such an order becomes two smaller sweeps, which is
accepted. Spot's close delay becomes zero.

**Futures: idle timer (shard tick).** This is slice 4 of `trade-detection-plan.md`, built here.
- Futures pushes each fill as it is matched (`ts − t` p50 0 ms). A sweep often arrives in several
  frames (59% of multi-fill sweeps), so it can't close at frame end. Its pieces are at most 213 ms
  apart by `ts` (p99 144 ms).
- `TradeState.lastFillAtMs` is set from the detector's clock on every trade that opens or continues a
  sweep. It is the shard's processing time, so ring lag delays fills and ticks alike.
- A scheduler in `DisruptorShardManager` (one daemon thread) publishes one event per shard every
  `tick-interval-ms`, with `EventSource.TICK`, no stream kind and `instrumentId = -1`. It claims with
  `tryNext()`: a tick that doesn't fit a full ring is skipped, because the next one follows. It runs
  only when some venue has an idle close configured.
- **Threading invariant**: the scheduler thread only publishes ticks. It never reads or writes a
  slot or a `TradeState`. Every close, idle or not, runs on the shard's consumer thread, in ring order
  with that shard's trades, so the sweep state keeps a single owner and needs no locks or `volatile`.
  The tick is one more producer on rings that are already `ProducerType.MULTI`, and its claim never
  blocks.
- `MarketEventHandler` handles `TICK` before the slot lookup. It walks its shard's ids
  (`id = shardIndex; id += shardCount`) and calls `detector.closeIfIdle(slot)` on slots with a
  `TradeState`.
- `closeIfIdle` closes and judges an open sweep when its venue has an idle close and
  `now − lastFillAtMs ≥ sweep-idle-close-ms`. It sets `closedByIdle`.
- **Starting values**: tick 250 ms, idle 1500 ms for `MEXC_FUTURES`, so the worst close delay is
  about 1.75 s. The idle must stay well above the gap between a sweep's pieces on *our* clock. Lag
  spikes in the capture stretched that gap to 3.8 s (p99.9 1.2 s). Too short an idle splits sweeps;
  too long delays alerts.
- **Split check**: a deal arriving after an idle close that would have continued the closed sweep
  (2.4 against the closed state, which stays in place until the next `open`) is counted as an
  **idle split** per venue. That is the live measure of whether the idle value is too short.
- Venues without an idle close (Binance, MEXC spot) behave as today: Binance's sweeps close on the
  next trade. Binance can be given an idle close later by config alone.

### 2.7 Decoders (adapter)

**`MexcSpotDealsDecoder`** (binary):
- Moves the wire primitives out of `MexcSpotFrameReader` into a shared package-private
  `MexcProtobufWire`, adding a 64-bit varint read for `time` and field 6.
- Walks body 314, item by item, in wire order (time order). `tradeType` 1 is a buy.
- Splits `tradeId` (`<A>X<n>_<B>X<m>`) without allocating. The digits before the first `X` are the
  taker-order id (it fits a `long`) and the digits after it are the fill number. A deal whose taker
  and maker fill numbers are both 0 is an `X0_X0` deal (2.5).
- Field 6 is the trade id: `firstTradeId == lastTradeId`. Real fills always carry it; a real fill
  without it fails the frame.
- `reportsMakerFills()` and `closesSweepAtFrameEnd()` are `true`.
- Validates the whole frame before reporting anything, so a failed frame reports nothing. The frames
  are small, so a second walk is cheap.

**`MexcFuturesDealDecoder`** (JSON):
- Jackson streaming. `data` is newest first, but the sink needs oldest first. It collects the
  primitives into a small reusable per-thread buffer (a `ThreadLocal` read once per frame,
  allocation-free after warm-up) and reports them in reverse. Frames usually carry one deal and at
  most 50.
- Quantity is `v × slot.instrument().quantityMultiplier()`. The trade id is `i`, a numeric string
  parsed in place. `takerOrderId` is `-1`. `T` 1 is a buy.
- `reportsMakerFills()` is `true`; `closesSweepAtFrameEnd()` is `false`.
- Validates every item before reporting. Required fields: `p`, `v`, `T`, `t`, `i`, `M`. Others
  (`O`, `cts`) are skipped.

### 2.8 Thresholds, alerts, delivery

- **A budget for the whole feed.** `TradeChannel` sends every alert to every session, so Binance and
  MEXC share one budget of about **120 alerts/h**. The policy is the same on every exchange:
  - **A floor that is large in absolute terms**: $30K on spot, $100K on futures (leveraged, and far
    more volume). A thin market keeps the floor and alerts only when a large trade happens. It is
    never given a lower floor to produce alerts: an alert is worth showing for its size, not its
    frequency. MEXC spot is therefore rare (2.5/h).
  - **A cap of 3 alerts/h per symbol**: a busy symbol gets the lowest ladder step at which it
    averages at most 3/h over the capture and stays at or under 7.5 in every full hour.
  - With a $30K futures floor the budget can't be met: Binance futures alone is 105/h even at a 2/h
    cap. At cap 10 the busy contracts dominate (ETH, HYPE, XRP, DOGE ~8/h each on MEXC), so a
    higher floor alone barely helps.
- **Per-venue floor.** `TradeAlertProperties` gains `venue-default-min-notional`, a per-venue default
  between the symbol overrides and the global default. `TradeThresholds` looks up the symbol
  override, then the venue default, then the global default. The global default is the spot floor;
  both futures venues set $100K.
- **Thin futures contracts lose the $30K–100K band.** Contracts with an alert drop from 55 to 31 on
  MEXC futures. A `MEXC_FUTURES` default of $50K would add ~22 alerts/h. Revisit when users report.
- **Thresholds live in their own file.** The thresholds are data derived from the captures, and they
  make up most of the `trade-alerts` block. They move to `src/main/resources/trade-thresholds.yml`,
  which keeps the `screener.trade-alerts` prefix. `application.yml` imports it:
  ```yaml
  spring:
    config:
      import: classpath:trade-thresholds.yml, optional:file:./trade-thresholds.yml
  ```
  Spring merges the file into the same prefix, so `TradeAlertProperties` binds and validates it
  unchanged, and profiles and environment variables can still override a value. Behaviour settings
  (`recent-capacity`, `replay-size`, `sweep-idle-close-ms`, `tick-interval-ms`) stay in
  `application.yml`.
  - The `optional:file:` entry lets a `trade-thresholds.yml` in the server's working directory
    override the bundled one, so a re-tune needs a restart, not a rebuild. The later import wins per
    property, and maps merge across files. A server file can change or add an override but cannot
    remove a bundled one: to drop it, set it to the venue's default.
  - The analysis scripts print their venues' blocks in this file's exact format, so a re-derived block
    replaces the old one as a whole and the change is reviewed as a diff.
  ```yaml
  # trade-thresholds.yml (MEXC part; derived over 10.7 h, 2026-11-18 07:39–17:31 UTC)
  screener:
    trade-alerts:
      default-min-notional: 30000          # spot, every exchange
      venue-default-min-notional:          # optional; a venue's own default
        BINANCE_FUTURES: 100000
        MEXC_FUTURES: 100000
      min-notional:                        # optional; per venue, native symbols
        MEXC_FUTURES:
          BTC_USDT: 2000000
          ETH_USDT: 1500000
          SOL_USDT: 500000
          XRP_USDT: 500000
          ZEC_USDT: 300000
  ```
  Alerts/h over the captures (average, worst full hour): MEXC spot 2.5 (5), MEXC futures 18 (38),
  Binance spot 39 (46), Binance futures 52 (50); about 111 in total. Binance's capture is 5.1 h of
  US session on 2026-11-17; in its 25-minute sell-off the same thresholds gave about 400/h.
- **Alert id**: `VENUE:NATIVESYMBOL:firstTradeId` works unchanged (field 6 / `i`). It looks like
  `MEXC_FUTURES:BTC_USDT:17067813085`, which is fine because the contract calls the id opaque.
- **`levels` is not `fills` on MEXC.** The contract's `levels` (§3.8.2) is "price levels taken", and
  `TradeChannel` fills it from `alert.fills()`. That is right on Binance, where each event of a sweep
  has its own price. It is wrong on MEXC, where 5 resting orders filled at one price are 5 fills on 1
  level (the capture saw up to 35 fills on 23 levels). Traders care about levels.
  - `TradeState.levels` starts at 1 and is incremented when a continuing fill's price differs from
    `lastPrice`. Both counts are kept: `fills` stays for the fills-per-sweep metric.
  - `TradeAlert` gains `levels`, and `TradeChannel` sends it. The `TRADE` message shape does not
    change.
- **`coalescedOnly` is judged per level**: true when no single price level's summed notional
  reached the threshold. `TradeState` sums the notional of the current level (`levelNotional`, reset
  when the price changes) and keeps the maximum (`maxLevelNotional`). On Binance a level is one fill,
  so the flag is unchanged there. On MEXC it stops calling one large order "coalescing-only" just
  because it filled against several resting orders at one price.
- The frontend contract already lists `MEXC` as an `exchange` (§3.6). §3.8 needs its text updated:
  "Binance spot and futures" becomes the actual list, and the §3.8.2 limits paragraph gains the MEXC
  notes (spot sweeps are exact by order id; futures uses rule A, so same-millisecond orders with
  consecutive ids merge).
- Health log: MEXC rows appear by themselves. `dec/s` exceeds frames/s on MEXC (batched frames). A
  `filt` column shows dropped deals (slice 2) and an `isplit` column shows idle splits (slice 3).

---

## 3. Slice 0 — capture and analysis (offline)

**Capture: built.** `C:\git\trade-capture\capture_mexc.py`:
- whole MEXC universe, `MexcInstrumentSource` filters;
- data in `C:\git\trade-capture\data-mexc\`, the same line format as the Binance capture
  (`r` = local receive time, `v` = venue, `d` = the frame), with spot frames base64-encoded;
- `mexc_frames.py` decodes both venues and prints a sanity summary of an hour file.

**Questions**:

1. **Spot `X0_X0`.** Does the 500 ms clock pattern hold over hours and across symbols? Do any large
   `X0_X0` deals fall off the clock? What share of alerts at candidate thresholds would they make?
   Drop or keep?
2. **Futures `M=1`.** Split by clock / off-clock and by `O`. Do the large off-clock ones cluster across
   symbols (liquidations)? How large are they? Which filter?
3. **Futures continuation rule.** Candidates: side + `t` + consecutive `i` + non-strict price; the
   same with `|Δt| ≤ 1 ms`; no ids. Calibrate on spot taker fills, where the order id gives the ground
   truth, then report how often the rules disagree on futures alerts.
4. **Notional per symbol and thresholds**, after the Q1/Q2 filters. Same policy as Binance (floor,
   ~10 alerts/h per symbol cap, the ladder). Alert counts at 10K/20K/30K floors.
5. **Close delay** (last fill → the symbol's next deal) for alerting sweeps, by liquidity bucket.
6. **Rates**: frames/s and deals/s per venue, deals per frame, for health baselines.
7. **Replay fixtures**: built in slices 2 and 3 (§5, §6), once the close mechanisms were decided.

### Capture findings

The first 4.0 h of the capture (2026-11-18 07:39–11:40 UTC, no US open) is analyzed. The numbers are
in `external-docs/mexc/mexc-trades-empirical.md`; re-run with `python mexc_analyze.py` in
`C:\git\trade-capture\analysis\`.

| Q | Answer | Design consequence |
|---|---|---|
| 1 | `X0_X0` deals are synthetic paired orders on a per-symbol 500 ms clock: maker id = taker id + 1, no trade sequence, never in a real fill. 93.5% of spot notional. Unfiltered, 198 spot alerts instead of 10. | 2.5: drop them all. |
| 2 | `M` is **self-trade** (MEXC REST docs: "self-transact"). 99.9% `O=3`. Large `M=1` deals are off the clock and do not cluster across symbols: self-trades, not liquidations. Unfiltered, +16% futures alerts. `M` belongs to the taker order (it changes inside 133 of 110K sweep steps). | 2.5: drop every `M=1` deal. The decoder requires `p, v, T, t, i, M`. |
| 3 | Spot: an order's fills always share `t`, ids are contiguous, and the price is non-strict (equal on 32% of steps). Binance's strict rule would split 38% of multi-fill orders. Rule A recovers 99.4% of them on spot. Futures shows extra Δt = 1 ms steps (~4,000 in 4 h). Allowing them changes ~25% of the alert set (+74 / −42 of 445). | 2.4: rule A with exact `t`. |
| 4 | Real spot taker flow is $1.4M/h. Largest order in 4 h: $96K. Futures `M=2`: $180M/h. | 2.8: a per-venue default in `TradeAlertProperties`. Values re-derived over 10.7 h in slice 4: spot $30K (2.5/h), futures $100K + 5 overrides (18/h). |
| 5 | Close delay is long on MEXC. Futures alerts on contracts with < 0.1 real deals/s are 38% of all futures alerts: p90 76 s, max 12 min. Spot sweeps almost never span frames (0.04% of orders, no alert). Futures sweeps span frames in 59% of multi-fill sweeps, pieces ≤ 213 ms apart by `ts`. | 2.6: spot closes at frame end; futures gets the idle timer (slice 3). |
| 6 | Spot 25 frames/s, 41 deals/s (35 filtered). Futures 61 frames/s, 69 deals/s (50 filtered). | `filt` exceeds real deals on both venues. |
| 7 | — | Fixtures in slices 2 and 3. |

---

## 4. Slice 1 — transport (done)

Both stream protocols are parameterized by channel (2.3), the YAML blocks and `MexcAdapterConfig`
stream bindings exist, and the streams are off by default. Commit `3e1623c`.

---

## 5. Slice 2 — decode, detect, spot frame-end close (done)

Built as below. The spot fixture is 08:10–08:18 UTC of the capture on XRPUSDT, BTCUSDT, ETHUSDT and
SOLUSDT (1,692 frames, 827 KB), at test thresholds $300 and XRPUSDT $600: 34 alerts (22 multi-fill,
17 with equal-price fills, 10 multi-level, among them the $96K BTC order of 12 fills over 7 levels),
4,154 filtered `X0_X0` deals of which 851 would alert unfiltered, and one XRP order spanning two
frames whose first frame alerts alone. Built with `make_mexc_fixture.py` in
`C:\git\trade-capture\analysis\`. The local verify below is still open.

**Goal**: MEXC spot and futures deals are decoded, filtered and coalesced. Spot alerts fire at the
end of the frame. Futures alerts are correct but still wait for the next deal; slice 3 removes the
wait.

1. **Spot replay fixture** (`C:\git\trade-capture\analysis\`, Python, before the Java test):
   - A `make_mexc_fixture.py` next to `make_fixture.py`, built on `mexccap.py`. It cuts a window of
     about 1 MB from the capture into `src/test/resources/trade/mexc-spot-deals-replay.jsonl`
     (capture lines unchanged, spot frames base64) and writes
     `mexc-spot-deals-replay-expected.json` in the Binance fixture's format (`trade-detection-plan.md`
     §10.6).
   - The expected alerts follow this plan's semantics exactly: drop `X0_X0`, group by taker id,
     close at the end of each frame, test thresholds chosen for the fixture (independent of
     `application.yml`).
   - The window must contain multi-fill orders with equal prices and multi-level orders. It must
     also contain an `X0_X0` deal that would alert without the filter, and, if one exists nearby, an
     order spanning two frames.
2. **SPI changes** (2.2) and the handler's overload choice and `onFrameEnd` call. The Binance decoder
   keeps the defaults: `takerOrderId = -1`, `reportsMakerFills()` and `closesSweepAtFrameEnd()` false.
3. **`MexcProtobufWire`** extraction; `MexcSpotFrameReader` rebased on it, depth tests unchanged.
4. **`MexcSpotDealsDecoder`**, **`MexcFuturesDealDecoder`** (2.7) with the filters (2.5); a
   `VenueTradeDecoderBinding` for each venue in `MexcAdapterConfig`.
5. **`TradeDetector`**: continuation rule (2.4); `onFrameEnd` (2.6); `TradeState` fields; `levels`
   and per-level `coalescedOnly` (2.8); the clock. `TradeAlert.levels`, and `TradeChannel` sends it.
6. **Metrics**: filtered count per venue, `filt` column in the health log.

**Tests**:

| Test | Covers |
|---|---|
| `MexcSpotDealsDecoderTest` | A captured frame decodes to the right prices, quantities, sides, times, trade ids and taker-order ids. `X0_X0` deals go to `onFiltered`. A truncated or malformed item returns `false` with no sink call. The frame buffer's `position` and `limit` are unchanged. |
| `MexcFuturesDealDecoderTest` | A multi-deal frame is reported oldest first. `v` is multiplied by `contractSize`. `M=1` deals go to `onFiltered`. A bad item or a missing required field returns `false` with no sink call. |
| `TradeDetectorTest` | Equal taker ids continue across an equal price. Different taker ids close the sweep even with the same `t`. With `makerFills`, an equal price continues and a reversal still closes; a 1 ms time step closes. Without it, the Binance behaviour is unchanged. `levels` counts distinct prices: 5 fills at one price give `levels = 1`, `fills = 5`. `coalescedOnly` is per level: one $40K order filled as four $10K fills at one price, threshold $30K, is not coalescing-only. `onFrameEnd` closes the open sweep only when `closeAtFrameEnd` is set. |
| `TradeChannelTest` | `levels` comes from `alert.levels()`, not `fills()`. |
| `TradeReplayTest` | The Binance fixture is unchanged. The MEXC spot fixture finds exactly its expected alerts in order, through decoder → detector with `onFrameEnd` after each frame. |
| `MarketEventHandlerTest` | A binary `TRADE` event reaches the `ByteBuffer` overload, a text one the `String` overload; `onFrameEnd` follows each decode. |

**Verify (local, both TRADE streams on)**: MEXC spot alerts appear in
`/api/monitoring/trade-alerts` with `levels ≤ fills`. None of them is an `X0_X0` deal. `filt` is
near 35/s spot and 50/s futures. Binance alert rates are unchanged.

---

## 6. Slice 3 — idle close (shard tick)

**Goal**: a MEXC futures sweep is judged about 1.5 s after its last fill, not when the next real deal
arrives. Replaces slice 4 of `trade-detection-plan.md`.

Built as below. The tick lives in `ShardTicker`, owned by `DisruptorShardManager`, on a fixed delay
(not a fixed rate, so a pause is not followed by a burst of ticks); `tick-interval-ms` stays under
`screener.trade-alerts`. The health log gains `idle` (sweeps the idle close judged) and `isplit`
columns, and a `ticks skipped=` note on the shards line when a full ring dropped ticks. The futures
fixture is 09:18–09:33 UTC of the capture on ZEC_USDT plus five thin contracts (WLFI, HBAR, SPX,
STRK, ALGO; 1,465 frames, 391 KB), at test thresholds $5K and ZEC_USDT $30K: 121 alerts (56
multi-fill, 31 split across frames, 27 closed by the idle close, 3 that only the idle close emits
within the window), 405 filtered self-trades of which one would alert, 33 1 ms steps, and one idle
split. Built with `make_mexc_futures_fixture.py` in `C:\git\trade-capture\analysis\`; the expected
alerts carry `closedBy` and `detectedAtMs`, and the replay checks both. The local verify below is
still open.

1. **Futures replay fixture** (Python, as in slice 2): `mexc-futures-deal-replay.jsonl` and its
   `-expected.json`.
   - The expected alerts follow this plan exactly: drop `M=1`, rule A with exact `t`, oldest first,
     and the idle close modelled on the records' `r`. Before each record, every open sweep with
     `r − lastFill_r ≥ idle` is closed.
   - The window must contain newest-first multi-deal frames, a sweep split across frames, an `M=1`
     deal that would alert without the filter, a 1 ms step that closes a sweep, and an alert on a
     thin contract that only the idle close emits before the window ends.
2. **Config**: `sweep-idle-close-ms` (per venue) and `tick-interval-ms` in `TradeAlertProperties`,
   validated > 0; YAML values from 2.6.
3. **Tick**: `EventSource.TICK`, the scheduler in `DisruptorShardManager`, and the handler's tick
   branch (2.6).
4. **Detector**: `lastFillAtMs`, `closeIfIdle`, `closedByIdle` and the idle-split count (2.6).
5. **Metrics**: `isplit` column in the health log, per venue.

**Tests**:

| Test | Covers |
|---|---|
| `TradeDetectorTest` | With an idle close configured, `closeIfIdle` closes a sweep idle for ≥ the limit and not before. A venue without one is never closed by it. A deal that would have continued an idle-closed sweep counts one idle split and opens a new sweep. |
| `MarketEventHandlerTest` | A `TICK` event visits exactly this shard's slots and never touches the slot lookup. |
| `DisruptorShardManagerTest` (or the publisher's) | Ticks reach every shard. A full ring skips the tick without blocking. No scheduler runs when no venue has an idle close. |
| `TradeReplayTest` | The MEXC futures fixture finds exactly its expected alerts. The clock is set to each record's `r`, and `closeIfIdle` runs on every slot before the record. |

**Verify (local)**: `detectedAtMs − tradeTimeMs` for MEXC futures alerts stays under about 2 s outside
lag spikes. `isplit` stays near zero. If it isn't, raise the idle.

---

## 7. Slice 4 — thresholds and live check

**Goal**: thresholds live in their own file, MEXC gets thresholds derived from a capture that covers
busy hours, and both `TRADE` streams are on by default.

Built: steps 1–4, their tests, and the docs of §8 that cover them. `trade-thresholds.yml` carries
the values of 2.8 for Binance and MEXC, derived for the whole-feed budget. A context test boots
`TradeAlertProperties` alone to check that the import resolves. Open: the verify below, then step 5.

1. **Thresholds file** (2.8). Create `trade-thresholds.yml` and add the import to `application.yml`.
   Move `default-min-notional` and the Binance `min-notional` block over with the values unchanged.
   `application.yml` keeps the behaviour settings.
2. **Per-venue default** (2.8): `venue-default-min-notional` in `TradeAlertProperties`, validated
   > 0 like `min-notional`. `TradeThresholds` looks up symbol, then venue, then global, with no
   allocation.
3. **Re-derive the thresholds** (done). Over the MEXC capture to 17:31 UTC (10.7 h, three hours past
   the US open) the 4 h policy gave 143 futures alerts/h, 282 in the busiest hour; with Binance that
   is ~430/h for one shared feed. The policy changed to the whole-feed budget of 2.8, applied to
   Binance too. `C:\git\trade-capture\analysis\thresholds.py` loads both captures, applies the
   floors, the 3/h cap and the 7.5-per-hour check, and prints the `min-notional` blocks in
   `trade-thresholds.yml`'s format (`--grid` prints floor × cap per venue).
   `mexc-trades-empirical.md` §5.1 has the numbers.
4. Update the frontend contract's §3.8 for MEXC (2.8).
5. Turn both `TRADE` streams on by default once the check below passes.

**Open, not blocking**: the floors are a first guess at what users find large. User feedback may move
the futures floor ($50K on both futures venues would be ~128/h for futures alone) or call for a
per-user minimum in the frontend, which would let the shared feed stay more generous.

**Tests**:

| Test | Covers |
|---|---|
| `TradeAlertPropertiesTest` | The shipped `application.yml` and `trade-thresholds.yml`, loaded together, bind the Binance and MEXC thresholds and the behaviour settings. `venue-default-min-notional` rejects a value ≤ 0. |
| `TradeThresholdsTest` | Lookup order: a symbol override beats the venue default, which beats the global default. A venue default for one venue does not apply to another. |

**Verify**: after an hour, compare alerts/hour per venue and the top symbols in
`/api/monitoring/trade-alerts/summary` with the slice 4 re-derivation for a similar time of day
(spot about 2.5/h on ETH, BTC, NEAR, SOL, XRP; futures about 18/h, up to ~38 in busy hours; Binance
39 spot + 52 futures). Spot check a
few multi-fill alerts against the MEXC trade list, including `levels`. Confirm that no alert is an
`X0_X0` or `M=1` deal. Check once that a `trade-thresholds.yml` next to the jar overrides a bundled
value.

---

## 8. Docs (as the code lands)

- `CLAUDE.md`:
  - Module Map: MEXC trade decoders; the shard tick.
  - The Streams bullet: `TRADE` bound for Binance and MEXC, plus the MEXC deal channels.
  - The `StreamKind` note "bound for Binance only".
  - The Disruptor bullet: `EventSource.TICK`.
  - Conventions, Config bullet: trade-alert thresholds live in `trade-thresholds.yml`, imported by
    `application.yml`, and a file of that name next to the jar overrides them.
- `deployment-guide.md`: the optional server-side `trade-thresholds.yml` (where it goes, that a
  restart applies it, that it cannot remove a bundled override).
- `.claude/plans/trade-detection-plan.md` §10.2: the YAML block now lives in `trade-thresholds.yml`.
- `.claude/docs/market-data-pipeline-architecture.md` §12 and §13: binary trade decoders, maker-fill
  venues, frame-end and idle close, the tick.
- `.claude/docs/how-large-trades-are-detected-on-mexc.md`: status, and any number the code or the live
  check changes.
- `.claude/docs/how-large-trades-are-detected.md`: §6.3 and §7.4 (the idle timer exists, configured
  per venue), §6.4 "Binance only".
- `.claude/plans/trade-detection-plan.md` §7: points here.
- `.claude/plans/trade-alerts-plan.md`: Phase 5 row.
- `.claude/docs/for-frontend/websocket-feed-api.md` §3.8 (slice 4).

## 9. Out of scope

- Bybit (`publicTrade`), the next venue in Phase 5. The 2.2 SPI changes are shaped for it: its
  trades are also maker fills with string ids.
- An idle close for Binance. The mechanism allows it by config; its close delays were acceptable
  (`trade-detection-plan.md` §10.4).
- The 1 ms time tolerance on futures (2.4).
- Showing filtered deals to users. They are counted and never judged.
- The `10ms` spot variant. 100 ms adds at most ~100 ms of latency against futures' ~0, which is
  fine for a screener. Revisit if users notice.
