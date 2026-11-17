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
  checks `nq ≤ q`.
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

- Bound to a `TradeAlertProperties` record in `config/`. Values above are placeholders; the shipped
  default and per-symbol entries come from the slice 0 analysis (Q1).
- `TradeThresholds.minNotional(Instrument)` does two map lookups (`EnumMap<Venue, Map<String,
  Double>>`, then the default). At under 1K trades/s that costs nothing, and Phase 6's per-user
  overrides layer onto one central lookup rather than a value copied into every slot.

### 2.6 Alerts and the store (slice 2)

- `TradeAlert` record: `Instrument instrument`, `boolean aggressorBuy`, `double vwap`, `double qty`,
  `double notional`, `double firstPrice`, `double lastPrice`, `int fills`, `long tradeTimeMs`,
  `long detectedAtMs`. In slice 2 `fills` is 1 and all three prices are equal.
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
  describes the line format, the side files and the caveats.
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

1. `TradeState` in the slot, allocated in `InstrumentSlotTable.allocate`. Fields: `open`,
   `aggressorBuy`, `tradeTimeMs`, `lastTradeId`, `sumQty`, `sumNotional`, `firstPrice`,
   `lastPrice`, `fills`, plus `lastFillAtMs` (local clock) if slice 4 is a go.
2. `TradeDetector.onTrade` replaces the slice 2 per-trade check:
   - If a sweep is open and the trade **continues** it, accumulate.
   - Otherwise **close** the open sweep (judge `sumNotional` against the threshold, alert with
     `fills`, `vwap = sumNotional / sumQty`, first and last price), then open a new sweep with
     this trade.
3. **Continuation rule**: as chosen in the slice 0 findings. The working assumption is same side,
   same `tradeTimeMs`, and, when both ids are known, `firstTradeId == lastTradeId + 1`. Without ids
   (future venues), side and time alone.
4. Metric, for alerted sweeps only: fills per sweep (`1, 2–3, 4–10, >10`).

**Known gap**: a sweep closes only when the instrument's next trade arrives. On an illiquid symbol
that can be seconds or minutes. Slice 0 measures this delay (question 7) and decides whether
slice 4 is needed.

**Tests** (`TradeDetectorTest`, synthetic sequences): one large trade alerts when the next trade
closes it; ten contiguous fills, each below the threshold, summing above it → one alert, `fills = 10`,
correct VWAP and first/last price; the same fills summing below → no alert; a side change, a time
change and an id gap each close the sweep; ids `-1` fall back to side + time. `TradeReplayTest`:
the fixture finds exactly the coalesced alerts the analysis found.

**Verify**: compare alerts/hour with the slice 2 live baseline and with the slice 0 coalesced
simulation; inspect a few multi-fill alerts against the Binance trade list.

---

## 7. Slice 4 — shard tick and stale-sweep flush (only if slice 0 says go)

- A scheduler publishes one `TICK` event per shard every `tick-interval` (starting idea 250 ms).
  `MarketEventHandler` handles it before the slot lookup (no instrument).
- On a tick, the handler scans its shard's slots (`id & mask == shard`, about a thousand checks)
  and closes sweeps whose `lastFillAtMs` is older than `sweep-idle-close-ms`.
- Both values are tunables; the slice 0 close-delay distribution gives their starting values. This
  is a core edit to the publisher and handler; Phase 4's burst windows reuse the same tick for
  expiry and cooldown.
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
