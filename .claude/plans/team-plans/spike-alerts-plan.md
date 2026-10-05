# Price & Volume Spike Alerts — Design Notes and Plan

**Created**: 2026-10-05, branch `feature/multi-exchange`. Status: **design / pre-implementation**.
Captures the discussion so far: what the feature is, where the data comes from, the architectural
decisions, open questions, and a proposed phasing and team split. Nothing here is built yet.

**Read first**
- `.claude/docs/multi-exchange-progress.md` — SPI, core pipeline, sync contract.
- `marketdata/core/ingress/` (`DepthEvent`, `EventType`, `DepthEventHandler`,
  `DisruptorDepthEventPublisher`) and `marketdata/core/stream/` (`StreamManager`, `ConnectionPool`,
  `StreamConnection`) — the code this plan generalizes.
- `feed/OrderBookBroadcaster` and `ws/UserWebSocketSession` — the delivery path alerts join.
- `.claude/plans/mexc-spot-impl-plan.md` — also changes `DepthEvent` (binary payload); see §7.
- `.claude/plans/team-plans/order-cluster-plan.md` — also changes `DepthEventHandler` and the
  broadcaster; see §7.

---

## 1. The feature

Two alert types, pushed to the user while connected:

- **Price spike** — a ticker's price moved by at least a threshold within a window
  (e.g. ≥ 5% in 5 min). UI shows: symbol, exchange, % change (e.g. 6.34%), old price X, new price Y.
- **Volume spike** — traded volume over the last minute is at least a threshold (e.g. ≥ $70K).
  UI shows e.g. `PEPE_USDT  $1m = $87K; $5m = $126K, time = 12:40:23`.

Both are backend-detected and delivered over the existing `/ws` connection.

---

## 2. Facts

### 2.1 Data source: Binance kline WebSocket streams

`<symbol>@kline_1m` exists on spot and futures. Each push carries the full state of the current
candle: open, high, low, close, base volume `v`, **quote volume `q` (USDT)**, and `x` (candle
closed).

- **Volume in dollars is native.** `$1m` = `q` of the current 1m candle; `$5m` = sum of the last
  five 1m candles. No conversion.
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

### D3. Stream kind is a new axis on the event, not a new `EventType`

`EventType` (`WS_MSG` / `REST_MSG` / `REST_FAILED`) is deliberately **provenance**, and the
backpressure policy hangs off it. Stream kind (`DEPTH` / `KLINE`) is orthogonal — add it as a
separate field (e.g. `stream`) on the event. A REST kline backfill would then be
`REST_MSG + KLINE`, which composes naturally.

### D4. The shard consumer becomes a dispatcher

Today `DepthEventHandler.onEvent` runs `slot.strategy().onEvent(...)` then
`classificationModule.process(...)` for **every** event. A kline event must branch **before** both:
→ candle state update → detectors. Depth events keep their current path unchanged.

### D5. `StreamManager` / `ConnectionPool` keyed by (venue, stream kind)

Today: one pool per `Venue` (`EnumMap<Venue, ConnectionPool>`), and `StreamConnection` /
`ConnectionPool` are bound to `DepthEventPublisher`. Generalize: pools keyed by (venue, kind);
each connection publishes with its kind. Kline subscriptions follow the same universe events as
depth. Kline URLs/topics go in YAML under `screener.exchanges.<exchange>.venues.<market>.*`
(per CLAUDE.md conventions), e.g. `kline-stream-url`, `kline-stream-topic`, `klines-enabled`.

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

### D8. Alert delivery: same `/ws`, outside the book `seq`

The current protocol is a **state stream**: snapshot, then `seq`-numbered deltas; a client-detected
gap triggers `SNAPSHOT_REQUEST`. Alerts are **events**, and don't fit:

- If alerts share the book `seq`, a lost alert triggers a snapshot — which doesn't contain alerts.
- Proposal: alerts are `type: "alert"`, **outside the book seq** (or with their own counter).
- The snapshot carries the **last N alerts**, so a fresh/reconnected client sees recent history.
- Shard threads produce alerts (multi-producer: N shards); the broadcaster drains them on its 100ms
  tick into each session's batch. Follow the existing feed-store pattern for the store.
- Entitlement is already enforced at `@OnOpen` — nothing new.

### D9. Hot-path refactor lands alone first

`DepthEvent` → generic market event + stream kind, handler → dispatcher, pools keyed by kind:
mechanical, but in the most sensitive code. Ship as a **no-behavior-change** commit verified by
existing tests + `PipelineMetrics` before any kline code lands.

### D10. Two internal seams, agreed before the work splits

So detection, ingestion and delivery can be built in parallel by different people:

- **`CandleWindow`** — the read-only view detectors get of an instrument's candle ring (last close,
  high/low and summed quote volume over the last N 1m candles, how many candles are filled). The
  candle store implements it; detector tests use a fake.
- **`AlertSink`** — what detectors call to emit an alert (`emit(instrument, PriceAlert | VolumeAlert)`).
  The delivery store implements it; delivery tests use a stub producer.

### D11. Feature flags and shadow mode

`screener.alerts.enabled` (run detectors at all) and `screener.alerts.delivery-enabled` (push to
clients). Detection with delivery off is the shadow-tuning mode (Phase 5), with recent alerts
visible on an admin monitoring endpoint. Per-venue `klines-enabled` (D5) gates ingestion.

### Draft alert contract (to agree before FE/BE split)

```json
{ "type": "alert", "kind": "PRICE", "exchange": "BINANCE", "market": "FUTURES",
  "symbol": "PEPEUSDT", "ts": 1759667423000,
  "oldPrice": 0.00001012, "newPrice": 0.00001076, "pct": 6.34, "windowSec": 300 }

{ "type": "alert", "kind": "VOLUME", "exchange": "BINANCE", "market": "FUTURES",
  "symbol": "PEPEUSDT", "ts": 1759667423000,
  "vol1m": 87000, "vol5m": 126000 }
```

Field names and the symbol display convention (`PEPE_USDT`? base/quote split? the `1000` prefix?)
are open (Q-T5).

---

## 5. Blast radius

| Kind | What |
|------|------|
| **New** | `alert/` package (candle rings, detectors, cooldown, alert store); Binance kline `StreamProtocol` + parser; REST backfill; config (`screener.alerts.*`, kline URL/topic per venue); metrics; admin endpoints (candles, recent alerts) |
| **Modified — moderate** | `core/ingress/` (`DepthEvent` generalization, handler dispatch, publisher signature); `core/stream/` (pools keyed by venue + kind, publisher decoupling); `spi/StreamProtocolRegistry` + `VenueStreamBinding` (keyed by kind, completeness check for `DEPTH` only). The only changes adjacent to the hot path. |
| **Modified — small** | `BookSlot` (candle state); `OrderBookBroadcaster` (alerts into batches, recent alerts in snapshot); `ExchangesProperties`; `BinanceAdapterConfig` (kline bindings); `monitoring/`; frontend WS message dispatch |
| **Untouched** | Books, sync strategies, recovery, classifier logic, auth, billing, entitlement, payments |

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

- **Q-D1. Price window.** Now vs. close 5 min ago (point-to-point — misses a pump that reverses
  inside the window), or window low→now / high→now (catches it)?
- **Q-D2. Direction.** Pumps only, or dumps too?
- **Q-D3. Cooldown / re-arm.** Without it, a +5% ticker fires every 250ms. Per-(instrument, kind)
  cooldown? Escalating levels (5% → 10% → 15%)? Re-arm only after falling back below threshold?
- **Q-D4. Volume threshold shape.** A flat $70K/min is meaningless for majors (BTC/ETH always
  qualify). Relative ("1m vol ≥ k × trailing average") with $70K as a floor? Per-symbol tiers?
- **Q-D5. Volume window.** Current forming 1m candle (fire at most once per candle) or a true
  rolling 60s? Candle-based is much simpler.

### Technical

- **Q-T1.** Confirm Binance kline push behaviour: on change only, or fixed cadence? Measure actual
  msg/s across the 525 futures before sizing rings.
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
- **Q-T5.** Alert JSON contract (field names, symbol display, `1000`-prefix handling).
- **Q-T6.** Seq handling: alerts with no seq, or their own counter? What does the client do on a
  dropped alert (nothing, presumably)?
- **Q-T7.** How does the frontend currently treat an unknown `type`? If it assumes every message is a
  book update, the FE change must ship before or together with BE delivery.

---

## 7. Coordination with MEXC spot and order clusters

`mexc-spot-impl-plan.md` Phase 1 also changes `DepthEvent` (`ByteBuffer rawBytes` beside
`rawJson`) and `DepthEventPublisher` (`publishFrame(int, ByteBuffer)`). Both plans touch the same
classes on the same axis-adding pattern. Sequence them — whichever lands second rebases onto the
first — and do not run them in parallel on separate branches.

`order-cluster-plan.md` adds a call in `DepthEventHandler.onEvent` (it belongs in the **depth**
branch of the D4 dispatcher) and a new body type, a snapshot field and a custom-session filter
bypass in `OrderBookBroadcaster` — the same places D8 changes. Clusters are state inside `seq`;
alerts are events outside it, so the two must not share a channel. Whichever lands second rebases.

---

## 8. Phasing

### Phase 0 — Decisions, measurement, contracts
- Answer Q-B1..B6 and Q-D1..D5 with product. These decide whether the feature is useful or noisy.
- Measure Binance futures kline msg/s across the universe (Q-T1) with a throwaway client.
- Agree the alert JSON contract (Q-T5, Q-T6) → unblocks frontend and delivery.
- Agree the internal seams `CandleWindow` and `AlertSink` (D10) → unblocks parallel BE work.
- FE confirms how the client treats an unknown `type` (Q-T7).

### Phase 1 — Core refactor (no behaviour change)
- Event gains a stream-kind field (D3); handler dispatches by kind (D4); publisher carries kind.
- `StreamManager` / `ConnectionPool` keyed by (venue, kind); `StreamProtocolRegistry` keyed by
  (venue, kind) with completeness checked for `DEPTH` only (D5).
- Only `DEPTH` exists after this phase. Existing tests + `PipelineMetrics` must be unchanged.
- Coordinate with MEXC spot and order clusters (§7).
- **Owner: whoever owns the pipeline** — touches the sync/sharding invariants.

### Phase 2 — Kline ingestion + candle state
- **2a (parallel with Phase 1)**: streaming kline parser (Jackson `JsonParser`, combined-stream
  envelope, no allocation) and a primitive candle ring (~60 × 1m) implementing `CandleWindow`.
  Pure code, unit-tested on recorded frames.
- **2b (after Phase 1 + 2a)**: Binance kline `StreamProtocol` for futures (`/market/stream`) and
  spot, `VenueStreamBinding`s, YAML (`kline-stream-url`, `kline-stream-topic`, `klines-enabled`),
  candle ring in `BookSlot`, kline branch of the dispatcher writing into it. Metrics (kline msg/s
  per venue) and an admin debug endpoint showing an instrument's candles. Re-check ring sizing (Q-T2).
- **2c (after 2b)**: REST backfill on startup through `WeightGuard` (Q-T3). Optional for v1:
  without it, alerts start ~5 min after a deploy. Detectors must handle a partially filled window
  without firing.
- **No alerts yet.**

### Phase 3 — Detectors (parallel with Phase 2, against a fake `CandleWindow`)
- `screener.alerts.*` → `@ConfigurationProperties` record; feature flags (D11).
- Shared cooldown/re-arm component per (instrument, kind) (Q-D3).
- Price detector (window semantics per Q-D1, direction per Q-D2).
- Volume detector (1m / 5m quote volume, threshold shape per Q-D4/Q-D5).
- Runs on the shard thread from the kline branch of the dispatcher; hot-path rules apply.

### Phase 4 — Alert delivery (parallel with Phases 2–3, against a stub `AlertSink` producer)
- Alert store implementing `AlertSink` (multi-producer from shards → broadcaster drain).
- `type: "alert"` messages outside the book `seq` (D8); last N alerts in the snapshot.
- `delivery-enabled` flag; admin endpoint listing recent alerts (used by Phase 5).
- Contract doc under `.claude/docs/for-frontend/`.

### Phase 5 — Shadow tuning
- Run in prod with `delivery-enabled=false` for about a week; review via the admin endpoint.
- Recalibrate thresholds and cooldowns; confirm alert volume is "a handful per minute".
- Enable delivery only after the FE alert channel (Phase 6) is released.

### Phase 6 — Frontend (can start once the Phase 0 contract is fixed, against mocks)
- WS dispatch for `type: "alert"`, tolerant of unknown types; alert store; list + toast.
- Price and volume alert presentation (symbol display, % change, old → new price, `$1m` / `$5m`).

### Later
- MEXC / Bybit kline adapters (adapter-only if D6 holds).
- Per-user thresholds (Q-B3), alert persistence (Q-B4), dynamic re-subscription (Q-T4).
- Classifier using kline-derived signals (the reason for D2).

---

## 9. Team split & estimate

Rough estimates, one developer per ticket. BE-1 is the pipeline owner.

| # | Jira ticket | Phase | Owner | Estimate (days) |
|---|---|---|---|---|
| 1 | **[BE] Spike alerts: settle detection rules with product, measure kline message rates, and agree the alert WebSocket contract and internal interfaces** | 0 | BE-2 + product | 2–3 |
| 2 | **[BE] Spike alerts: generalize the ingress pipeline and stream pools by stream kind (no behaviour change)** | 1 | BE-1 | 2–3 |
| 3 | **[BE] Spike alerts: implement streaming kline parser and per-instrument 1m candle ring** | 2a | BE-2 | 1–2 |
| 4 | **[BE] Spike alerts: subscribe to Binance futures and spot kline streams and feed candles into the shard pipeline** | 2b | BE-1 | 2–3 |
| 5 | **[BE] Spike alerts: backfill recent candles over REST on startup within the Binance weight budget** | 2c | BE-1 | 1 |
| 6 | **[BE] Spike alerts: implement price spike detector with per-instrument cooldown and alert config** | 3 | BE-2 | 2 |
| 7 | **[BE] Spike alerts: implement volume spike detector on 1m/5m quote volume** | 3 | BE-2 / BE-3 | 1–2 |
| 8 | **[BE] Spike alerts: deliver alerts over WebSocket outside the book sequence, include recent alerts in snapshots, add admin alert endpoint** | 4 | BE-3 | 2–3 |
| 9 | **[BE] Spike alerts: shadow-run detectors in production and calibrate thresholds before enabling delivery** | 5 | BE-2 | ~1 week elapsed, low effort |
| 10 | **[FE] Spike alerts: handle alert WebSocket messages, show alert toast and recent-alerts list** | 6 | FE-1 | 3–4 |
| 11 | **[FE] Spike alerts: present price and volume alerts (change %, old/new price, 1m/5m volume)** | 6 | FE-1 / FE-2 | 2–3 |

**Dependencies**
- Ticket 1 blocks every ticket except two:
  - ticket 2, because the refactor needs no product answers;
  - ticket 3, because the candle ring needs only the `CandleWindow` shape, and ticket 1 settles that
    first.
- Ticket 4 needs tickets 2 and 3. Ticket 5 needs ticket 4.
- Tickets 6, 7 and 8 start as soon as ticket 1 has agreed the interfaces and the contract. They run
  against a fake `CandleWindow` and a stub producer, and do not wait for tickets 2–5.
- Ticket 7 reuses the cooldown component from ticket 6. One developer runs them in sequence; two
  developers agree the cooldown interface up front.
- Ticket 9 needs tickets 4–8 merged. Delivery is switched on only after ticket 10 is released.
- Tickets 10 and 11 start once ticket 1's contract is fixed.

**Owner notes**
- Tickets 2, 4 and 5 go to the pipeline owner: they touch streams, sharding and REST pacing.
- Tickets 1, 6 and 9 go to the same person: whoever defines the detection rules should also tune
  them. Ticket 3 also fits there, since that person owns `CandleWindow`.
- Ticket 8 is ordinary broadcaster/WS work and suits a third backend developer. With only two
  backend developers it goes to BE-2 after ticket 6, because BE-1's chain (2 → 4 → 5) is already
  the critical path.
- Tickets 10 and 11 can split across two FE developers, or run sequentially for one.

**Total**: ~15 developer-days of backend effort. With 2–3 backend developers in parallel that is
~1.5–2 weeks elapsed, plus the shadow-tuning week before delivery is switched on. Frontend is
~1–1.5 weeks. Add ~1 week for per-user thresholds and ~2–3 days for persisted history. The riskiest
ticket is #2, because it sits next to the code that keeps books alive.
