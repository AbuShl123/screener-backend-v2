# MEXC Trade Alerts — Implementation Plan

Phase 5 of `.claude/plans/trade-alerts-plan.md`, MEXC first: large-trade alerts for MEXC spot and
futures, through the same detector, store and `TradeChannel` as Binance. Trade alerts matter most on
smaller exchanges, so MEXC ships before Bybit.

**Read first**: `external-docs/mexc/mexc-trades-empirical.md` (the wire contract and the
reconnaissance this plan rests on), `.claude/plans/trade-detection-plan.md` (§2 design, §10 the
Binance findings this plan mirrors), `.claude/docs/market-data-pipeline-architecture.md` §13.

As in Phase 2, slice 0 is an offline capture and analysis that answers every question needing market
data. The app slices implement decisions already taken from it and check themselves against it.

---

## 1. Starting point

- The Binance trade path is complete: `TradeDecoder` SPI → `TradeDetector` (sweep coalescing,
  thresholds) → `TradeAlertStore` → `TradeChannel`. Everything after the decoder is venue-agnostic
  except the sweep continuation rule (2.4).
- MEXC depth is live on both venues. Its stream protocols already route deal frames correctly: spot
  frames carry the same wrapper `symbol` (field 3), futures frames start with `"symbol"`.
- `MarketEvent` carries binary frames in `rawBytes`, but `TradeDecoder.decode` takes only a `String`.
- `Instrument.quantityMultiplier()` holds futures `contractSize`; the depth path already multiplies by it.
- The MEXC universe is USDT-quoted only, so `price × qty` is notional in USDT, as on Binance.

### What differs from Binance (reconnaissance, `mexc-trades-empirical.md`)

| | Binance aggTrade | MEXC spot | MEXC futures |
|---|---|---|---|
| Encoding | JSON, 1 trade per frame | Protobuf (body 314), 100 ms batches | JSON, `data` array **newest first** |
| One event is | one taker order at one price (spot) | one fill against one resting order | one fill against one resting order |
| Quantity | base | base | **contracts** × `contractSize` |
| Taker-order id | none | **exact** (`tradeId` left half) | none |
| Numeric trade id | `f` / `l`, contiguous per symbol | field 6, on real fills only | `i`, a sequence shared across contracts |
| Equal price in one sweep | never (new order) | normal | normal |
| Clock-driven deals | none seen | `X0_X0`: ~85–90% of deals, ~95% of notional | `M=1`: ~60–70% of deals |

---

## 2. Design

### 2.1 Flow

```
TRADE event (shard thread)
  └─ MarketEventHandler TRADE arm
       └─ rawBytes != null ? decoder.decode(slot, rawBytes, sink) : decoder.decode(slot, rawJson, sink)
            └─ MEXC decoder: parse, drop filtered deals (2.5), report the rest oldest first
                 └─ TradeDetector.onTrade(slot, price, qty, buy, time, firstId, lastId, takerOrderId)
                      └─ continuation rule (2.4) → threshold → TradeAlertStore → TradeChannel
```

### 2.2 SPI changes (core)

| Type | Change |
|---|---|
| `TradeDecoder` | Adds `default boolean decode(InstrumentSlot slot, ByteBuffer frame, TradeSink sink)` returning `false`. Binary decoders override it. Same absolute-index rule as `MexcSpotFrameReader`: never move `position` / `limit`. |
| `TradeDecoder` | Adds `default boolean reportsMakerFills()` returning `false`. `true` means one event is one fill against one resting order, so an equal price can continue a sweep (2.4). It is read once, at slot allocation. |
| `TradeSink.onTrade` | Gains `long takerOrderId`: `-1` when the venue has none (Binance, MEXC futures). |
| `TradeSink` | Gains `onFiltered(InstrumentSlot slot)`, called once per deal a decoder drops by venue policy (2.5), for the health log. |
| `MarketEventHandler` | The `TRADE` arm picks the overload by payload: `rawBytes` if set, else `rawJson`. |
| `TradeState` | Gains `takerOrderId` and the `makerFills` flag, which `InstrumentSlotTable` copies from the decoder at allocation. |

### 2.3 Transport (adapter)

- `MexcSpotStreamProtocol` takes the channel prefix from its constructor: depth
  `spot@public.aggre.depth.v3.api.pb@100ms@`, trade `spot@public.aggre.deals.v3.api.pb@100ms@`.
  Routing and ack handling stay the same.
- `MexcFuturesStreamProtocol` takes the channel name (`depth` / `deal`) and builds `sub.<ch>`,
  `push.<ch>` and `rs.sub.<ch>` from it.
- `MexcAdapterConfig` adds a `VenueStreamBinding` for each `TRADE` stream and one
  `VenueTradeDecoderBinding` per venue.
- YAML, off until slice 3 verifies:

  ```yaml
  SPOT:
    streams:
      TRADE:
        enabled: ${MEXC_SPOT_TRADE_ENABLED:false}
        stream-url: wss://wbs-api.mexc.com/ws
        max-streams-per-connection: 30   # server limit, as for depth
        min-connections: 1
        max-connections: 30              # ~508 pairs → 17
        subscribe-chunk-size: 30
        heartbeat-interval-seconds: 20
  FUTURES:
    streams:
      TRADE:
        enabled: ${MEXC_FUTURES_TRADE_ENABLED:false}
        stream-url: wss://contract.mexc.com/edge
        max-streams-per-connection: 300
        min-connections: 1
        max-connections: 8
        subscribe-chunk-size: 1          # sub.deal takes one symbol
        heartbeat-interval-seconds: 15
  ```

  Spot adds about 17 sockets next to depth's 17. Tested limits: no per-IP cap up to 300.

### 2.4 Sweep continuation rule

A trade continues the open sweep when it has the same side and:

1. **Both taker-order ids are known**: they are equal. Nothing else is checked. This covers MEXC spot exactly.
2. **Otherwise**, the Binance rule (`trade-detection-plan.md` §10.3), with one change for venues
   whose `reportsMakerFills()` is true: the price may also stay **equal**, not only move toward the
   aggressor. Same `tradeTimeMs` and, when known, `firstTradeId == lastTradeId + 1` as today.

For MEXC futures, whether to allow a small time difference (one sweep's last fill landed 1 ms
later) is slice 0's Q3. Binance's behaviour must not change: its replay test is the guard.

### 2.5 Filtering clock-driven deals

The decoder decides, because only the adapter knows the format. Each dropped deal goes to
`sink.onFiltered`, not `onTrade`: it never opens or continues a sweep and is never judged.
Working decisions, to be confirmed or changed by slice 0 (Q1, Q2):

- **Spot `X0_X0` deals** (no fill numbers): drop.
- **Futures `M=1`**: open. The choices are to drop all of them, keep all of them, or keep only
  those with `O ≠ 3`. A filter on the 500 ms clock is not on the list: it would be fragile and
  undocumented.

### 2.6 Decoders (adapter)

**`MexcSpotDealsDecoder`** (binary):
- Moves the wire primitives out of `MexcSpotFrameReader` into a shared package-private
  `MexcProtobufWire`, adding a 64-bit varint read for `time` and field 6.
- Walks body 314, item by item, in wire order (time order). `tradeType` 1 is a buy.
- Splits `tradeId` without allocating: the digits before the first `X` are the taker-order id (it
  fits a `long`), and the digits after it are the fill number. A deal whose taker and maker fill
  numbers are both 0 is an `X0_X0` deal (2.5).
- Field 6 is the trade id: `firstTradeId == lastTradeId`.
- Validates the whole frame before reporting anything, so a failed frame reports nothing. The
  frames are small, so a second walk is cheap.

**`MexcFuturesDealDecoder`** (JSON):
- Jackson streaming. `data` is newest first, but the sink needs oldest first. Collect the
  primitives into a small reusable per-thread buffer (a `ThreadLocal` read once per frame,
  allocation-free after warm-up), then report in reverse. Frames usually carry one deal and at
  most ~33.
- Quantity is `v × slot.instrument().quantityMultiplier()`. The trade id is `i`, a numeric string
  parsed in place. `takerOrderId` is `-1`.
- Validates every item before reporting. Required fields: `p`, `v`, `T`, `t`, plus `M` (and `O`)
  if the filter uses them.

### 2.7 Thresholds, alerts, delivery

- `screener.trade-alerts.min-notional` gets `MEXC_SPOT` and `MEXC_FUTURES` blocks. Keys are
  native symbols, so futures uses `BTC_USDT`. Values come from slice 0.
- Alert id: `VENUE:NATIVESYMBOL:firstTradeId` works unchanged (field 6 / `i`). It looks like
  `MEXC_FUTURES:BTC_USDT:17067813085`, which is fine because the contract calls the id opaque.
- **`levels` is not `fills` on MEXC.** The contract's `levels` (§3.8.2) is "price levels taken", and
  `TradeChannel` fills it from `alert.fills()`. That is right on Binance, where one aggTrade is one
  level, and wrong on MEXC, where 5 resting orders filled at one price are 5 fills on 1 level.
  - `TradeState` counts levels, incremented when the price changes, next to fills.
  - `TradeAlert` gains `levels`, and `TradeChannel` sends it.
  - The fills-per-sweep metric stays on fills.
  - `coalescedOnly` (no single fill would have alerted) is also true for one large order split
    across several resting orders at one price. Decide in slice 2 whether to judge it per level on
    MEXC.
- The `TRADE` message shape does not change. The frontend contract already lists `MEXC` as an
  `exchange` (§3.6). §3.8 needs its text updated: "Binance spot and futures" becomes the actual list,
  and the §3.8.2 limits paragraph gains the MEXC notes (spot sweeps are exact by order id; futures
  rule from slice 0).
- Health log: MEXC rows appear by themselves. `dec/s` exceeds frames/s on MEXC (batched frames),
  and a `filt` column shows dropped deals.

---

## 3. Slice 0 — capture and analysis (offline)

**Capture: built.** `C:\git\trade-capture\capture_mexc.py`:
- whole MEXC universe, `MexcInstrumentSource` filters;
- data in `C:\git\trade-capture\data-mexc\`, the same line format as the Binance capture, with spot
  frames base64-encoded;
- `mexc_frames.py` decodes both venues and prints a sanity summary of an hour file.

**Duration**: several hours, including the US open and an Asia session if possible. Thin symbols
need hours to show anything.

**Questions**:

1. **Spot `X0_X0`.**
   - Does the 500 ms clock pattern hold over hours and across symbols?
   - Do any large `X0_X0` deals fall off the clock?
   - What share of alerts at candidate thresholds would they make?
   - Decision: drop or keep.
2. **Futures `M=1`.**
   - Split by clock / off-clock and by `O`.
   - Do the large off-clock ones cluster across symbols within milliseconds and follow sharp moves
     (liquidations)? How large are they?
   - Decision: the 2.5 filter.
3. **Futures continuation rule.**
   - Candidates: side + `t` + consecutive `i` + non-strict price (the 2.4 rule); the same with
     `|Δt| ≤ 1 ms`; side + `t` + non-strict price without ids.
   - Calibrate on spot taker fills, where the order id gives the ground truth: apply each id-free
     rule to spot fills and measure recall and precision against the exact grouping. Then report
     how often the rules disagree on futures alerts.
4. **Notional per symbol and thresholds**, after the Q1/Q2 filters.
   - Same policy as Binance: floor $30K, cap ~10 alerts/h per symbol, the ladder.
   - Is $30K too high a floor for MEXC's thin symbols? Report alert counts at 10K/20K/30K floors.
5. **Close delay** (last fill → the symbol's next deal) for alerting sweeps, by liquidity bucket.
   This decides whether slice 4 of the detection plan (shard tick, idle-sweep flush) has to come
   with MEXC.
6. **Rates**: frames/s and deals/s per venue, deals per frame, for health baselines.
7. **Replay fixtures**, about 1 MB each:
   - `src/test/resources/trade/mexc-spot-deals-replay.jsonl` and
     `mexc-futures-deal-replay.jsonl`, each with an `-expected.json` in the Binance fixture's format
     (`trade-detection-plan.md` §3.3).
   - Each should include multi-fill sweeps with equal prices, a filtered deal that would alert
     without the filter, and the futures newest-first ordering.

**Output**: a "Capture findings" section appended here, `mexc-trades-empirical.md` updated with the
long-run numbers, and the YAML threshold block.

The analysis scripts go in `C:\git\trade-capture\analysis\` (MEXC modules next to the Binance ones,
built on `mexc_frames.py`).

---

### Capture findings

The first 4.0 h of the capture (2026-11-18 07:39–11:40 UTC, no US open) is analyzed. The numbers are
in `external-docs/mexc/mexc-trades-empirical.md`; re-run with `python mexc_analyze.py` in
`C:\git\trade-capture\analysis\`. Answers, and what each one changes in §2:

| Q | Answer | Design consequence |
|---|---|---|
| 1 | `X0_X0` deals are synthetic paired orders on a per-symbol 500 ms clock: maker id = taker id + 1, no trade sequence, never in a real fill. 93.5% of spot notional. Unfiltered, 198 spot alerts instead of 10. | 2.5 confirmed: drop them all. |
| 2 | `M` is **self-trade** (MEXC REST docs: "self-transact"). 99.9% `O=3`. Large `M=1` deals are off the clock and do not cluster across symbols: self-trades, not liquidations. Unfiltered, +16% futures alerts. `M` belongs to the taker order (it changes inside 133 of 110K sweep steps). | 2.5: drop every `M=1` deal. `O` is not needed, so the decoder requires only `p, v, T, M, t, i`. |
| 3 | Spot: an order's fills always share `t`, ids are contiguous, and the price is non-strict (equal on 32% of steps). Binance's strict rule would split 38% of multi-fill orders. Rule A (2.4 as written) recovers 99.4% of them on spot. Futures shows extra Δt = 1 ms steps (~4,000 in 4 h). Allowing them changes ~25% of the alert set (+74 / −42 of 445). | 2.4 confirmed, with exact `t`. The 1 ms tolerance is left open: it is not provable from the stream, and exact `t` matches spot and Binance. |
| 4 | Real spot taker flow is $1.4M/h. Largest order in 4 h: $96K. Futures `M=2`: $180M/h. | **Spot needs its own floor**: $30K gives 2.5 alerts/h on 4 pairs, $10K gives 8/h, $5K gives 24/h, with no overrides needed. Futures: $30K floor + 5 overrides → 111/h. `TradeAlertProperties` needs a per-venue default (today one global `default-min-notional`). |
| 5 | Close delay is long on MEXC. Futures alerts on contracts with < 0.1 real deals/s are 38% of all futures alerts: p90 76 s, max 12 min. Spot sweeps never span frames (0.04% of orders, no alert). | **Slice 4 of the detection plan comes with MEXC**: an idle timer (~1 s; a sweep's fills are ≤ 213 ms apart by `ts`). Spot can instead close a sweep at the end of its frame, which makes its delay zero. |
| 6 | Spot 25 frames/s, 41 deals/s (35 filtered). Futures 61 frames/s, 69 deals/s (50 filtered). | `filt` exceeds real deals on both venues. |
| 7 | Not built yet. It waits for the floor and the close mechanism above, because both change the expected alerts. | — |

Thresholds at the floors above (spot $10K floor, futures $30K), derived by `mexc_analyze.py --floor`:

```yaml
MEXC_FUTURES:
  BTC_USDT: 750000
  ETH_USDT: 500000
  SOL_USDT: 300000
  ZEC_USDT: 200000
  XRP_USDT: 150000
```

## 4. Slice 1 — transport

**Goal**: both MEXC `TRADE` streams connect, subscribe and route. With no decoder bound yet, the
handler counts the events as unhandled.

1. Parameterize both stream protocols (2.3). Depth behaviour must not change.
2. Add the YAML blocks (2.3, off by default) and the `MexcAdapterConfig` stream bindings.

**Tests**: protocol tests for both channel names (subscribe frame, routing of a captured deal
frame, ack and reject handling); `MexcAdapterConfigTest` with the trade bindings; existing depth
protocol tests unchanged.

**Verify (local, both TRADE streams on)**: about 17 spot + 2 futures trade connections; per-kind
frame rates near the capture's (~25 and ~60 frames/s); no rejected subscriptions; depth rates and
ring headroom unchanged.

## 5. Slice 2 — decode and detect

1. SPI changes (2.2) and the handler's overload choice. Binance decoder: `takerOrderId = -1`,
   `reportsMakerFills()` false.
2. `MexcProtobufWire` extraction; `MexcSpotFrameReader` rebased on it, depth tests unchanged.
3. `MexcSpotDealsDecoder`, `MexcFuturesDealDecoder`, filters as slice 0 decided (2.5, 2.6); decoder
   bindings for both venues.
4. `TradeDetector` continuation rule (2.4); `TradeState` fields, including the level count (2.7).
   `TradeAlert.levels` and `TradeChannel` sends it; on Binance it equals `fills`.
5. Metrics: filtered count per venue, `filt` column in the health log.

**Tests**:

| Test | Covers |
|---|---|
| `MexcSpotDealsDecoderTest` | A captured frame decodes to the right prices, quantities, sides, times, trade ids and taker-order ids. `X0_X0` deals go to `onFiltered`. A truncated or malformed item returns `false` with no sink call. The frame buffer's `position` and `limit` are unchanged. |
| `MexcFuturesDealDecoderTest` | A multi-deal frame is reported oldest first. `v` is multiplied by `contractSize`. The filter is applied. A bad item returns `false` with no sink call. |
| `TradeDetectorTest` | Equal taker ids continue across an equal price. Different taker ids close the sweep even with the same `t`. With `makerFills`, an equal price continues and a reversal still closes. Without it, the Binance behaviour is unchanged. `levels` counts distinct prices: 5 fills at one price give `levels = 1`, `fills = 5`. |
| `TradeChannelTest` | `levels` comes from `alert.levels()`, not `fills()`. |
| `TradeReplayTest` | Binance fixture unchanged. Both MEXC fixtures find exactly their expected alerts. |
| `MarketEventHandlerTest` | A binary `TRADE` event reaches the `ByteBuffer` overload, a text one the `String` overload. |

## 6. Slice 3 — thresholds and live check

1. The MEXC threshold blocks from slice 0 in `application.yml`; the YAML binding test.
2. Check the frontend contract for MEXC trade alerts (2.7).
3. Turn both `TRADE` streams on by default once the check below passes.

**Verify**: after an hour, compare alerts/hour per venue and the top symbols in
`/api/monitoring/trade-alerts/summary` with slice 0's simulation for a similar time of day. Spot
check a few multi-fill alerts against the MEXC trade list, and confirm that no alert is an
`X0_X0` deal.

## 7. Docs (as the code lands)

- `CLAUDE.md`:
  - Module Map: MEXC trade decoders.
  - The Streams bullet: `TRADE` bound for Binance and MEXC, plus the MEXC deal channels.
  - The `StreamKind` note "bound for Binance only".
- `.claude/docs/market-data-pipeline-architecture.md` §12 and §13: binary trade decoders,
  maker-fill venues.
- `.claude/plans/trade-alerts-plan.md`: Phase 5 row.
- `.claude/docs/for-frontend/websocket-feed-api.md` §3.8, if venues are listed there.

## 8. Out of scope

- Bybit (`publicTrade`), the next venue in Phase 5. The 2.2 SPI changes are shaped for it: its
  trades are also maker fills with string ids.
- Slice 4 of the detection plan, unless slice 0's Q5 says MEXC needs it.
- Showing filtered deals to users. They are counted and never judged.
- The `10ms` spot variant. 100 ms adds at most ~100 ms of latency against futures' ~0, which is
  fine for a screener. Revisit if users notice.
