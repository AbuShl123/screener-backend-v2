# Price & Volume Spike Alerts — Design Notes and Plan

**Created**: 2026-10-05, branch `feature/multi-exchange`. Status: **design / pre-implementation**.
Captures the discussion so far: what the feature is, where the data comes from, the architectural
decisions, open questions, and a proposed phasing and team split. Nothing here is built yet.

**Read first**
- `.claude/docs/epics.md` — why this feature spans Epic 1 (kline ingestion) and Epic 2 (signal and
  delivery); see §9.
- `.claude/docs/multi-exchange-progress.md` — SPI, core pipeline, sync contract.
- `marketdata/core/ingress/` (`DepthEvent`, `EventType`, `DepthEventHandler`,
  `DisruptorDepthEventPublisher`) and `marketdata/core/stream/` (`StreamManager`, `ConnectionPool`,
  `StreamConnection`) — the code this plan generalizes.
- `feed/OrderBookBroadcaster`, `ws/UserWebSocketSession` and
  `.claude/docs/for-frontend/websocket-feed-api.md` — the delivery path and the WS contract that
  D16/D17 restructure.
- `.claude/plans/mexc-spot-impl-plan.md` — also changes the ring event (binary payload); see §7.
- `.claude/plans/team-plans/order-cluster-plan.md` — also changes the event handler and builds its
  delivery on D16/D17; see §7.

---

## 1. The feature

Two alert types, pushed to the user while connected:

- **Price spike** — a ticker's price moved **up or down** by at least a threshold within a window
  (e.g. ≥ 5% in 5 min). UI shows: symbol, exchange, % change (e.g. +6.34%), old price X, new price Y.
- **Volume spike** — quote volume over the last N minutes **increased** against the N minutes
  before it, by both a percentage and an absolute amount (e.g. ≥ +200% and ≥ $70K). Increases only.
  UI shows e.g. `PEPE_USDT  $5m: $40K → $126K (+215%), time = 12:40:23`.

Exact rules: D12 (price), D13 (volume).

Both are backend-detected and delivered over the existing `/ws` connection.

---

## 2. Facts

### 2.1 Data source: Binance kline WebSocket streams

`<symbol>@kline_1m` exists on spot and futures. Each push carries the full state of the current
candle: open, high, low, close, base volume `v`, **quote volume `q` (USDT)**, and `x` (candle
closed).

- **Volume in dollars is native.** `q` is the candle's quote volume; a window's volume is the sum
  of `q` over its candles. No conversion.
- **Candle fields used**: `t` (candle open time — identifies the minute), `h`, `l`, `c` (last trade
  price = current price while `x` is false), `q`. Everything else is ignored.
- **No sequencing.** Each message is a self-contained candle state — no gaps to detect, no
  snapshots, no PENDING/RECOVERING/SYNCED machine. Far simpler than depth.
- **Warm-up.** After restart/reconnect the window is empty. REST `/fapi/v1/klines` and
  `/api/v3/klines` can backfill the last N candles; without it there are no alerts for ~5 min
  after a deploy.
- **Price source.** Order-book mid-price was considered and rejected: volume needs klines anyway,
  books are blind while recovering, and users compare against last-trade price (what charts show).

### 2.2 Endpoints (confirmed empirically)

| Market | Kline endpoint | Notes |
|--------|----------------|-------|
| Futures | `wss://fstream.binance.com/market/stream` | **Different from the depth endpoint.** The `/ws` variant does not work. Combined-stream envelope. |
| Spot | `wss://stream.binance.com/stream` | `/ws` also works. Same host as depth. |

The `/stream` (combined) endpoints wrap every payload as `{"stream":"...","data":{...}}`, unlike
the raw `/ws` frames the depth `BinanceStreamProtocol` parses. The kline protocol needs its own
`route()`.

### 2.3 Rates and limits

- Futures kline: 250ms update speed per symbol (docs). Spot kline_1m: ~2s (docs).
- **Unverified:** whether Binance pushes only when the candle changed (likely; illiquid symbols would
  then push less than 4/s). Measure before sizing.
- Universe: **525 futures tickers** at the last discovery → **≤ 2,100 msg/s** futures klines.
  For comparison, futures depth is `@depth@500ms` → ≤ ~1,050 msg/s. Klines are *more* messages than
  futures depth, but each is a few hundred bytes with O(1) processing.
- 1024 streams per connection → one kline connection per venue covers the whole universe.

---

## 3. Analysis

### 3.1 It is hot-path-adjacent — apply the rules, don't over-engineer

The rate is real, the per-message cost is tiny: stream-parse 4–5 fields, write into a primitive
ring. The risk is GC noise, not throughput. Apply the hot-path rules (Jackson streaming, no
per-message allocation, primitive candle rings, no per-message logging) — they're cheap here.
Even at three exchanges this stays a small share of what depth already processes.

Outbound is **not** proportional to kline rate: only **alerts** go to users, and those are sparse
(a handful per minute across the universe after cooldown).

### 3.2 Spot vs. futures — not interchangeable

Price moves are mostly the same for listed majors (arbitrage + funding keep perp and spot within
fractions of a percent). Everything else diverges:

- **Coverage.** Many of the 525 futures have no Binance spot market (perp-only listings, new
  tokens, memecoins) — exactly the volatile small caps where alerts matter most.
- **Symbol mismatch.** `1000PEPEUSDT` (futures) vs `PEPEUSDT` (spot), also `1000SHIB`, `1000BONK`…
  % change is equal; displayed prices differ by 1000×.
- **Volume is a different market.** Perp volume on alts is often several times spot. "$70K/min"
  means different things on each.
- **Liquidation cascades are a futures event** — futures wicks further than spot during forced
  liquidations, and that wick is the alert a futures trader wants.
- The product is futures-first (all 525 futures vs. a spot subset).

**Conclusion: if only one market is subscribed, it must be futures, not spot.** Spot's lower rate
(1 msg / 2s) is a real saving but irrelevant given §3.1, and it costs coverage and correctness.

### 3.3 Cross-exchange — price is roughly uniform, volume is not

- **Price**: arbitrage keeps liquid assets aligned across venues; one venue is a good proxy.
  Exception: coins listed on MEXC (or Bybit) but not Binance — MEXC lists many small caps.
  Binance-only alerts can never cover them.
- **Volume**: venue-specific by definition. A MEXC volume spike is MEXC's volume.

This is a business question (§6, Q-B1).

---

## 4. Architectural decisions (proposed)

### D1. Klines run on separate WebSocket connections from depth

Forced on Binance futures anyway (different endpoint, §2.2). Keeps the kline load from competing
with the depth firehose on the same socket. One connection per (venue, stream kind).

### D2. Klines go through the existing Disruptor shards (same rings, same consumer threads)

**Main reason — co-location.** CLAUDE.md lists "klines/candlestick streams for extra signals"
under Future Work. If candle state lives in the instrument's `BookSlot` on the same shard thread,
the classifier can later read 1m/5m volume next to the book with **zero synchronization**. A
separate pipeline would need a thread-safe bridge later.

**Isolation concern is weak.** Kline events are O(1). A depth stall (e.g. a large snapshot apply)
delays klines by milliseconds — irrelevant for 5-minute windows.

**Shard routing is unchanged.** A kline is for the same `Instrument` (same dense id) → `id &
(shardCount - 1)` lands it on the shard owning that book.

### D3. A generic ring event; stream kind is a new axis on it

The ring slot no longer carries depth only, so the ingress types lose the `Depth` prefix:

| Today | After |
|---|---|
| `DepthEvent` | `MarketEvent` |
| `DepthEventHandler` | `MarketEventHandler` |
| `DepthEventPublisher` / `DisruptorDepthEventPublisher` | `MarketEventPublisher` / `DisruptorMarketEventPublisher` |
| `DepthEventFactory` | `MarketEventFactory` |
| `EventType` (field `type`) | `EventSource` (field `source`) |

`EventSource` (`WS_MSG` / `REST_MSG` / `REST_FAILED`) stays **provenance**, and the backpressure
policy hangs off it. Stream kind is orthogonal: a new field `stream` of type
`StreamKind { DEPTH, KLINE }`. A REST kline backfill is `REST_MSG + KLINE`, which composes
naturally. With both fields on the event, `source` reads unambiguously where `type` would not.

`DepthSyncStrategy` keeps its name — it is depth-only — and takes a `MarketEvent`.

### D4. The shard consumer becomes a dispatcher

Today `DepthEventHandler.onEvent` runs `slot.strategy().onEvent(...)` then
`classificationModule.process(...)` for **every** event. `MarketEventHandler.onEvent` switches on
`event.stream` **before** both: `KLINE` → candle state update → detectors; `DEPTH` → the current
path, unchanged.

### D5. `StreamManager` / `ConnectionPool` keyed by (venue, stream kind)

Today: one pool per `Venue` (`EnumMap<Venue, ConnectionPool>`), and `StreamConnection` /
`ConnectionPool` are bound to `DepthEventPublisher`. Generalize: pools keyed by (venue, kind);
each connection publishes to `MarketEventPublisher` with its kind. Kline subscriptions follow the
same universe events as depth. Kline URLs/topics go in YAML under
`screener.exchanges.<exchange>.venues.<market>.*` (per CLAUDE.md conventions), e.g.
`kline-stream-url`, `kline-stream-topic`, `klines-enabled`.

`StreamProtocolRegistry` must follow: protocols keyed by (venue, kind), and its startup
completeness check ("every enabled venue has a protocol") applies to `DEPTH` only. A venue with no
kline protocol (MEXC futures today) simply gets no kline pool — it must not fail startup.

### D6. Kline protocol is its own `StreamProtocol` per venue

Parses the combined-stream envelope (§2.2); `routingKey` per instrument on the kline connection's
own `SubscriptionIndex`. Venue-agnostic SPI so MEXC (`sub.kline`) and Bybit are adapter-only
additions later.

### D7. Backpressure

Publisher claims with blocking `rb.next()`; a full ring stalls the publishing thread. Since klines
arrive on their own connections, a stall only blocks the kline reader thread — acceptable.
Re-check ring sizing with the added event rate.

### D8. Alert delivery: same `/ws`, same `seq`, recent alerts in the snapshot

The current protocol is a **state stream**: snapshot, then `seq`-numbered deltas; a client-detected
gap triggers `SNAPSHOT_REQUEST`. Alerts are **events**, but they join the same stream:

- Spikes are `PRICE_SPIKE` / `VOLUME_SPIKE` messages in the common envelope (D16) and carry the
  session's single `seq` counter like every other message.
- The snapshot carries the **last N alerts** as entries. After a gap the client re-snapshots and
  replaces its recent-alerts list with the snapshot's, so a lost alert is recovered in the list.
- Toasts fire only for live alert messages, never for snapshot entries.
- Shard threads produce alerts (multi-producer: N shards); `SpikeChannel` (D17) drains them on the
  broadcaster's 100ms tick. The alert store is bounded (pending queue + recent-N ring).
- Entitlement is already enforced at `@OnOpen` — nothing new.

### D9. Hot-path refactor lands alone first

The D3 rename + stream kind, the handler dispatch (D4) and pools keyed by kind (D5): mechanical,
but in the most sensitive code. Ship as a **no-behavior-change** change verified by existing
tests + `PipelineMetrics` before any kline code lands.

### D10. Two internal seams, agreed before the work splits

So detection, ingestion and delivery can be built in parallel by different people:

- **`CandleWindow`** — the read-only view detectors get of an instrument's candles: the forming
  candle (current close, high, low) and the ring of closed candles (high, low, quote volume each),
  plus how many consecutive closed candles are filled. `CandleStore` implements it (D14); detector
  tests use a fake. This is also the boundary between Epic 1 (candles) and Epic 2 (detectors).
- **`AlertSink`** — what detectors call to emit an alert (`emit(instrument, PriceAlert | VolumeAlert)`).
  The alert store behind `SpikeChannel` implements it; delivery tests use a stub producer.

### D11. Feature flags and shadow mode

`screener.alerts.enabled` (run detectors at all) and `screener.alerts.delivery-enabled` (push to
clients — `SpikeChannel` emits nothing while it is off). Detection with delivery off is the
shadow-tuning mode (Phase 6), with recent alerts visible on an admin monitoring endpoint.
Per-venue `klines-enabled` (D5) gates ingestion.

### D12. Price spike rule

- Evaluated on **every kline push** of the instrument (futures: up to every 250ms).
- Window = the forming candle + the last `price.window-minutes − 1` closed candles.
- **Pump**: `low = min(l)` over the window, `pct = (c − low) / low × 100`.
  **Dump**: `high = max(h)` over the window, `pct = (high − c) / high × 100`.
  Comparing against the window extreme, not the price exactly N minutes ago, catches a
  dip-then-rip inside the window (103 → 100 → 106 is a 6% pump, not 2.9%).
- Fires when `pct ≥ price.threshold-pct`. Alert shows old price = `low` (pump) / `high` (dump),
  new price = `c`, pct signed (+ pump, − dump).
- **Escalation and re-arm** — state per (instrument, direction): `lastAlertedLevel`, initially 0.
  - `level = floor(pct / threshold)`, so with 5%: 5.4% → 1, 10.2% → 2.
  - Fire only when `level > lastAlertedLevel`, then store it. 5.4% fires; 5.5–9.9% stays quiet;
    10.1% fires again.
  - Reset to 0 once `pct < threshold − price.rearm-margin-pct` (the margin stops a price hovering at
    the threshold from flapping).
  - Pump and dump state are independent.
  - Re-arm keys off the percentage, not off the identity of `low`/`high`: as the window slides the
    extreme changes, and that must not re-send the same move.
- No alert until the window is warm (all window candles present).

### D13. Volume spike rule

- **Increases only.** Falling volume is not alerted.
- Evaluated **once per minute, on candle rollover**, over closed candles only (v1). Reporting
  latency of up to ~60s is accepted for simplicity.
- `now` = Σ`q` of the last `volume.window-minutes` closed candles; `prev` = Σ`q` of the
  `volume.window-minutes` closed candles before those.
- Fires when **both** hold:
  - `(now − prev) / prev × 100 ≥ volume.increase-pct` (e.g. 200 = 3×). `prev = 0` passes this check;
    the floor then decides.
  - `now − prev ≥ volume.min-increase-usd` (e.g. $70K).

  The percentage alone fires on illiquid tickers ($200 → $1,200 is +500%); the absolute floor alone
  fires on majors ($5M → $5.1M is +$100K but only +2%). Requiring both filters out both cases.
- **Re-arm**: fire once, then stay quiet until the increase percentage falls back below
  `volume.increase-pct`. Without it, one spike re-fires every minute for N minutes as the window
  slides.
- No alert until `2 × volume.window-minutes` consecutive closed candles are filled.

### D14. `CandleStore` owns candles; detectors only read

- One `CandleStore` per instrument, in its `BookSlot` (D2), mutated only on the shard thread. It
  holds the forming candle and a primitive ring of `candle-ring-size` closed candles (`h`, `l`, `q`
  per candle). Both detectors read the same store through `CandleWindow` (D10) — neither keeps its
  own candle state.
- `CandleStore` is market data (Epic 1) and lives under `marketdata/`; the detectors and alert
  store are a signal (Epic 2) and live in `alert/`.
- The pipeline only routes a kline event to its shard and to the kline branch of the dispatcher
  (D4); it knows nothing about candles.
- **Rollover on a change of `t`, not on `x: true`.** `x: true` is lost if the connection drops at
  the wrong moment. A new `t` means the previous forming candle is finished: push it into the ring
  with its last known values, then run the volume detector (D13).
- If `t` jumps by more than one minute, the ring has a hole (reconnect gap): reset the filled count
  so detectors wait for the window to refill. Caveat: if Binance sends no candle for zero-trade
  minutes on illiquid symbols, a jump there is normal (Q-T1).

### D15. Alert configuration

All tunables in `application.yml`, bound to an `AlertsProperties` record:

```yaml
screener:
  alerts:
    enabled: true              # run detectors (D11)
    delivery-enabled: false    # push to clients (D11)
    candle-ring-size: 10       # closed candles kept per instrument
    price:
      window-minutes: 5        # N for price
      threshold-pct: 5         # spike threshold; also the escalation step
      rearm-margin-pct: 1      # re-arm once pct < threshold − margin
    volume:
      window-minutes: 5        # N for volume
      increase-pct: 200        # minimum increase vs. previous window (200 = 3×)
      min-increase-usd: 70000  # minimum absolute increase (quote currency)
```

Keeping these consistent is the configurer's responsibility and is not validated at startup:
`candle-ring-size ≥ 2 × volume.window-minutes` and `≥ price.window-minutes − 1`. Values are
calibrated during the shadow run (Phase 6).

### D16. Common WebSocket message envelope

The `/ws` feed is about to carry depth, spikes and order clusters. Every server → client message
except `SNAPSHOT` has one shape:

```json
{ "seq": 12, "type": "DEPTH", "exchange": "BINANCE", "market": "FUTURES", "symbol": "BTCUSDT",
  "data": { "bids": [ ... ], "asks": [ ... ] } }
```

- `type` says how to parse `data`: `DEPTH` (bids/asks), `PRICE_SPIKE`, `VOLUME_SPIKE`, and `CLUSTER`
  (order-cluster plan). Only `data` differs between types.
- **State types** (`DEPTH`, `CLUSTER`) are upserts; `"data": null` removes the instrument's entry.
  ADD / UPDATE / DROP leave the wire — the client already treats UPDATE as an upsert.
- **`SNAPSHOT`** is the one message without identity, because it covers many instruments. Its
  `data` is a list of the same envelopes the client receives live (without `seq`), so the client
  handles a snapshot entry and a live message with the same code:
  `{"seq":1,"type":"SNAPSHOT","data":[ {"type":"DEPTH",...}, {"type":"PRICE_SPIKE",...} ]}`.
- Clients ignore unknown `type`s, so new signals are additive.
- Type names are uppercase, matching the existing enum-name convention.
- Client → server messages (`SNAPSHOT_REQUEST`) are unchanged.
- This is a **breaking change** for depth messages: the frontend parser switch ships in the same
  release as the backend, and `.claude/docs/for-frontend/websocket-feed-api.md` is rewritten for it.

### D17. The broadcaster is split into feed channels

`OrderBookBroadcaster` today mixes three jobs: the per-session loop (seq, snapshot status,
enqueue/evict), the depth-specific global + per-user merge with its `ruleKey` filter, and JSON
building. Everything but the loop moves behind one interface:

```java
interface FeedChannel {
    void drain();                                                       // once per tick: drain stores, build shared bodies
    void collectUpdates(UserWebSocketSession session, List<String> out);  // this session's envelopes this tick
    void collectSnapshot(UserWebSocketSession session, List<String> out); // this channel's SNAPSHOT entries
}
```

- **`OrderBookBroadcaster`** keeps the loop: drain every channel once per tick, then per session
  either collect snapshot entries from all channels into one `SNAPSHOT`, or collect updates,
  inject `seq` and enqueue (evicting on a full queue). Drain timing metrics stay here.
- **`DepthChannel`** — today's global + per-user merge and `ruleKey` filter, moved unchanged.
- **`SpikeChannel`** — drains the alert store (D8); every session gets the same bodies; snapshot
  entries are the last N alerts.
- **`ClusterChannel`** (order-cluster plan) — one global store, every session, no filter.
- A shared helper writes the envelope head; each channel writes its own `data`. JSON stays
  `StringBuilder`-built, as today (`feed/` is hot path).
- Channels are beans collected into a list; the broadcaster does not know which exist.
- **Out of scope**: client-side channel subscriptions, per-channel `seq` counters, a generic event
  bus, Jackson serialization of depth bodies.

### Draft alert contract (to agree before FE/BE split)

```json
{ "seq": 14, "type": "PRICE_SPIKE", "exchange": "BINANCE", "market": "FUTURES", "symbol": "PEPEUSDT",
  "data": { "ts": 1759667423000, "oldPrice": 0.00001012, "newPrice": 0.00001076,
            "pct": 6.34, "windowSec": 300 } }

{ "seq": 15, "type": "VOLUME_SPIKE", "exchange": "BINANCE", "market": "FUTURES", "symbol": "PEPEUSDT",
  "data": { "ts": 1759667423000, "volPrev": 40000, "volNow": 126000,
            "pct": 215, "windowSec": 300 } }
```

`pct` is signed for `PRICE_SPIKE` (negative = dump) and always positive for `VOLUME_SPIKE`.

Field names inside `data` and the symbol display convention (`PEPE_USDT`? base/quote split? the
`1000` prefix?) are open (Q-T5).

---

## 5. Blast radius

| Kind | What |
|------|------|
| **New** | `alert/` package (detectors, re-arm state, alert store, `SpikeChannel`); `CandleStore` + `CandleWindow` under `marketdata/`; Binance kline `StreamProtocol` + parser; REST backfill; `FeedChannel`, `DepthChannel` and the envelope helper in `feed/`; config (`screener.alerts.*`, kline URL/topic per venue); metrics; admin endpoints (candles, recent alerts) |
| **Modified — moderate** | `core/ingress/` (D3 rename, `stream` field, handler dispatch, publisher signature); `core/stream/` (pools keyed by venue + kind, publisher decoupling); `spi/StreamProtocolRegistry` + `VenueStreamBinding` (keyed by kind, completeness check for `DEPTH` only); `feed/OrderBookBroadcaster` (reduced to the session loop, D17); the WS contract (D16, breaking for depth) |
| **Modified — small** | `BookSlot` (candle state); `DepthSyncStrategy` and both adapters' sync strategies (event type in the signature only); `ExchangesProperties`; `BinanceAdapterConfig` (kline bindings); `monitoring/`; `websocket-feed-api.md`; frontend WS parser |
| **Untouched** | Book logic, sync logic, recovery, classifier logic, auth, billing, entitlement, payments |

---

## 6. Open questions

### Business / product

- **Q-B1. Venue scope.** Is the alert about "this asset is moving" (one source enough, plus
  coverage for coins listed elsewhere) or "the market on the exchange I trade is active" (per
  venue)? Possibly: price → asset-level, volume → venue-level.
- **Q-B2. Market scope.** Futures only, or futures + spot? (Recommendation: futures first; §3.2.)
- **Q-B3. Global vs. per-user thresholds.** Per-user adds a rules CRUD, a Flyway migration, and
  per-user evaluation + fan-out (like `analysis/rule/`) — roughly +1 week.
- **Q-B4. Alert history.** In-memory recent-N only (alerts exist while connected), or persisted with
  a history page / REST endpoint (+2–3 days)?
- **Q-B5. Plan gating.** Are alerts part of the existing entitlement, or a separate plan tier?
- **Q-B6. Spot + futures duplicates.** If both markets are on, one move produces two alerts — show
  both, or merge?

### Detection semantics

Settled — see D12 (price), D13 (volume), D14 (candle store), D15 (config). Threshold values are
calibrated in the shadow run (Phase 6).

### Technical

- **Q-T1.** Confirm Binance kline push behaviour: on change only, or fixed cadence? Measure actual
  msg/s across the 525 futures before sizing rings. Also check whether a candle is pushed for a
  zero-trade minute on illiquid symbols — this decides whether a `t` jump is a gap (D14).
- **Q-T2.** Ring buffer sizing with the extra kline load; is the current size enough?
- **Q-T3.** Backfill on startup/reconnect: REST weight budget for 525 kline requests. Kline requests
  are cheap (low single-digit weight each for small `limit` — verify against current docs), but
  startup is exactly when every depth book is also fetching snapshots. Proposal: go through the
  Binance adapter's existing `WeightGuard` so the two cannot jointly exceed the limit.
- **Q-T4.** Kline connection lifecycle on universe change (new listing / delisting). *Partly
  answered by the code:* `StreamManager` has no dynamic re-subscription for depth either — a venue's
  pool is built once from its first universe event and later changes are only logged. Klines
  inherit that: new listings start streaming after a restart. Dynamic subscribe is separate work
  that would serve both kinds.
- **Q-T5.** Alert `data` field names and symbol display (`1000`-prefix handling). The volume alert
  shows previous vs. current window volume and the increase (D13), not `$1m` / `$5m`.

---

## 7. Coordination with MEXC spot and order clusters

`mexc-spot-impl-plan.md` Phase 1 adds `ByteBuffer rawBytes` beside `rawJson` on the ring event and
`publishFrame(int, ByteBuffer)` on the publisher — the same classes ticket 1 renames (D3). Sequence
them — whichever lands second rebases onto the first — and do not run them in parallel on
separate branches.

`order-cluster-plan.md` adds a call in the event handler — it belongs in the **depth** branch of the
D4 dispatcher — and delivers clusters as a `ClusterChannel` (D17) with the `CLUSTER` envelope type
(D16). Ticket 4 is therefore a prerequisite of the cluster delivery ticket. Clusters and spikes share
the session's `seq` but are separate channels and types.

---

## 8. Phasing

### Phase 0 — Product decisions and seams
Preconditions, not tickets:
- Answer Q-B1..B6 with product. Detection rules are settled (D12–D15).
- BE-1 commits the `CandleWindow` interface (D10) up front; detectors build against it.
- The detector owner defines `AlertSink` (D10) at the start of ticket 5; delivery builds against it.
- The envelope (D16) is fixed in ticket 4; it unblocks the frontend and spike delivery.

### Phase 1 — Core refactor, no behaviour change (ticket 1, Epic 1)
- D3 rename and `stream` field; handler dispatches by kind (D4); publisher carries kind.
- `StreamManager` / `ConnectionPool` keyed by (venue, kind); `StreamProtocolRegistry` keyed by
  (venue, kind) with completeness checked for `DEPTH` only (D5).
- Only `DEPTH` exists after this phase. Existing tests + `PipelineMetrics` must be unchanged.
- Coordinate with MEXC spot and order clusters (§7).

### Phase 2 — WS envelope and feed channels (ticket 4, Epic 2)
- All messages move to the envelope (D16); the broadcaster splits into channels with
  `DepthChannel` as the only one (D17). No new data.
- Rewrite `websocket-feed-api.md`; the frontend parser switch ships in the same release.

### Phase 3 — Kline ingestion and candle state (tickets 2 and 3, Epic 1, after Phase 1)
- **Ticket 2**: measure Binance futures kline msg/s across the universe first (Q-T1) with a
  throwaway client. Then: streaming kline parser (Jackson `JsonParser`, combined-stream envelope,
  no allocation; extracts `t`, `h`, `l`, `c`, `q`); `CandleStore` implementing `CandleWindow` —
  forming candle, primitive ring of `candle-ring-size` closed candles, rollover on `t`, gap
  handling (D14); Binance kline `StreamProtocol` for futures (`/market/stream`) and spot,
  `VenueStreamBinding`s, YAML (`kline-stream-url`, `kline-stream-topic`, `klines-enabled`);
  candle store in `BookSlot`, kline branch of the dispatcher writing into it. Metrics (kline msg/s
  per venue) and an admin debug endpoint showing an instrument's candles. Re-check ring sizing
  (Q-T2).
- **Ticket 3**: REST backfill on startup through `WeightGuard` (Q-T3). Deferrable for v1: without
  it, alerts start ~5 min after a deploy. Detectors handle a partially filled window without
  firing either way.
- **No alerts yet.**

### Phase 4 — Detectors (ticket 5, Epic 2, parallel with Phases 1–3 against a fake `CandleWindow`)
- `screener.alerts.*` → `AlertsProperties` record (D15); feature flags (D11).
- Price detector: pumps and dumps against window low/high, level escalation, re-arm margin (D12).
  Runs on every kline push.
- Volume detector: current vs. previous N-minute window, percentage AND absolute floor, increases
  only, re-arm below threshold (D13). Runs on rollover.
- Runs on the shard thread from the kline branch of the dispatcher; hot-path rules apply.

### Phase 5 — Spike delivery (ticket 6, Epic 2, after Phase 2, against a stub `AlertSink` producer)
- Alert store implementing `AlertSink` (multi-producer from shards, bounded).
- `SpikeChannel` (D17): `PRICE_SPIKE` / `VOLUME_SPIKE` envelopes in the session `seq`; last N alerts
  as snapshot entries (D8).
- `delivery-enabled` flag; admin endpoint listing recent alerts (used by Phase 6).
- Spike types added to `websocket-feed-api.md`.

### Phase 6 — Shadow tuning (release step)
- After tickets 2, 5 and 6 are merged: run in prod with `delivery-enabled=false` for about a week;
  review via the admin endpoint.
- Recalibrate thresholds; confirm alert volume is "a handful per minute".
- Enable delivery only after the frontend (ticket 7) is released.

### Phase 7 — Frontend (ticket 7, Epic 2; starts once ticket 4 fixes the envelope, against mocks)
- `PRICE_SPIKE` / `VOLUME_SPIKE` handling; recent-alerts list replaced from each snapshot; toasts
  for live alerts only.
- Price and volume alert presentation (symbol display, % change, old → new price, window volume).

### Later
- MEXC / Bybit kline adapters (adapter-only if D6 holds).
- Per-user thresholds (Q-B3), alert persistence (Q-B4), dynamic re-subscription (Q-T4).
- Classifier using kline-derived signals (the reason for D2).

---

## 9. Team split & estimate

Per `epics.md`, ingesting a new stream is Epic 1 and the signal built on it is Epic 2, so the work
is split into two stories, one per epic. The WS envelope is its own Epic 2 story because order
clusters build on it too. Rough estimates, one developer per ticket. BE-1 is the pipeline owner.

| # | Epic | Story | Jira ticket | Phase | Owner | Estimate (days) |
|---|---|---|---|---|---|---|
| 1 | 1 — Market Data Engine | Kline stream ingestion | **[BE] Generalize the ingress pipeline for multiple stream kinds (no behaviour change)** | 1 | BE-1 | 2–3 |
| 2 | 1 — Market Data Engine | Kline stream ingestion | **[BE] Ingest Binance futures and spot 1m klines into a per-instrument candle store** | 3 | BE-1 | 3–4 |
| 3 | 1 — Market Data Engine | Kline stream ingestion | **[BE] Backfill recent candles over REST on startup within the Binance weight budget** | 3 | BE-1 | 1 |
| 4 | 2 — Signals & Visualization | Common WebSocket envelope | **[BE] Move all WebSocket messages to a common envelope and split the broadcaster into feed channels** | 2 | BE-1 | 2–3 |
| 5 | 2 — Signals & Visualization | Price & volume spike alerts | **[BE] Implement price and volume spike detectors with alert config and feature flags** | 4 | BE-2 | 3–4 |
| 6 | 2 — Signals & Visualization | Price & volume spike alerts | **[BE] Deliver spike alerts over WebSocket and include recent alerts in snapshots** | 5 | BE-2 / BE-3 | 1–2 |
| 7 | 2 — Signals & Visualization | Price & volume spike alerts | **[FE] Handle spike alert messages and present price and volume alerts** | 7 | FE-1 | FE's own breakdown |

**Dependencies**
- Ticket 2 needs ticket 1. Ticket 3 needs ticket 2.
- Ticket 5 needs only the `CandleWindow` interface (Phase 0) and runs in parallel with tickets 1–3
  against a fake.
- Ticket 6 needs ticket 4 and the `AlertSink` from ticket 5.
- Ticket 7 needs ticket 4's envelope and runs against mocks.
- The shadow run (Phase 6) needs tickets 2, 5 and 6 merged. Delivery is switched on only after
  ticket 7 is released.
- The order-cluster delivery ticket needs ticket 4.

**Owner notes**
- Tickets 1–3 (all of Epic 1) go to the pipeline owner: they touch streams, sharding and REST
  pacing. Critical path: 1 → 2 → 3.
- Ticket 4 also goes to BE-1, done **before** ticket 1: its envelope unblocks the frontend, spike
  delivery and cluster delivery, and nothing in it waits on the engine. Its frontend parser switch
  is done by FE-1 within the same story and release.
- Ticket 5 and the shadow calibration go to the same person: whoever defines the detection rules
  should also tune them. With two backend developers, ticket 6 follows ticket 5 for BE-2.
- With a third backend developer, tickets 4 and 6 (and the cluster delivery ticket) form a delivery
  role for BE-3, which takes ticket 4 off BE-1's critical path.
- Ticket 7 is one frontend ticket; the frontend breaks it down on its own.

**Total**: ~12–17 developer-days of backend effort, 8–11 of them on BE-1. That is ~2 weeks elapsed,
set by BE-1's chain, plus the shadow-tuning week before delivery is switched on. Add ~1 week for
per-user thresholds and ~2–3 days for persisted history. The riskiest ticket is #1, because it sits
next to the code that keeps books alive.
