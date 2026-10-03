# MEXC Futures — Implementation Plan

**Created**: 2026-10-02, branch `feature/multi-exchange`.
**Scope**: add MEXC as the second exchange, **futures venue only** (`MEXC_FUTURES`). MEXC spot is
deferred because its market-data WebSocket is Protobuf-encoded.

**Read first**
- `.claude/docs/multi-exchange-progress.md` — current state of the pipeline, the SPI, and the Binance
  sync strategy (§4), whose shape the MEXC strategy follows.
- `external-docs/mexc/mexc-api-contracts.md` — REST/WS contracts and MEXC's official book-maintenance
  algorithm.
- `external-docs/mexc/mexc-api-rate-limits-empirical.md` and `mexc-ws-limits-empirical.md` —
  measured limits; these override the documentation-derived `mexc-api-rate-limits.md` where they
  disagree.

---

## 1. Facts that shape the design

### 1.1 Quantities are in contracts, not base asset

MEXC futures depth rows are `[price, vol, orderCount]`, where `vol` is a **number of contracts**.
Each contract has its own `contractSize` (e.g. BTC_USDT = 0.0001 BTC). Binance futures quantities
are in base asset. Feeding `vol` into the book unscaled makes notional wrong by orders of magnitude,
and every tier comparison in the classifier becomes meaningless.

**Decision**: discovery reads `contractSize` from `/api/v1/contract/detail`; it travels on
`InstrumentCandidate` → `Instrument` as a quantity multiplier (1.0 for Binance). The strategy
multiplies **at parse time**, so `OrderBook` always holds base-asset quantity and the classifier stays
venue-agnostic. (`InstrumentCandidate`'s javadoc already anticipates per-venue fields "arriving with
the first venue that needs them".)

### 1.2 `version` comes after the levels in the push frame

Binance's single-pass design relies on `U`/`u`/`pu` preceding `b`/`a` (progress doc §4.4). MEXC's
frame order is:

```json
{"channel":"push.depth","data":{"asks":[...],"bids":[...],"version":96801927},"symbol":"ETH_USDT","ts":...}
```

**Decision**: locate `"version":` by string scan on the raw frame, parse the long by hand, run
`check()`, and only then stream-apply the levels in one pass. This is cheap, allocation-free, and
robust to field reordering (which MEXC does not guarantee either way).

### 1.3 Sequence semantics are documented but not yet verified

Docs: every push has `version == previous + 1`, and the REST snapshot's `version` is on the same
counter. Unverified empirically. If pushes are aggregated (version jumps), the `check()` predicate
changes. **Verify before Phase 3** (see §3, Phase 0 task V1).

### 1.4 Throttling arrives as HTTP 200

A throttled `GET /api/v1/contract/depth/{symbol}` returns HTTP 200 with
`{"success":false,"code":510,"message":"Requests are too frequent..."}`. No `Retry-After`, no
rate-limit headers. The snapshot source must classify the body (ok / throttled / error) **before**
anything is published into the ring. A 510 body must never reach the strategy as a "snapshot".

Separately, the host sits behind Akamai: a WAF block is an **HTML 403** (spot-observed; may apply to
futures at higher rates). Treat it as a rate-limit signal, not an auth error.

### 1.5 Phases are not independently shippable without stubs

With `MEXC_FUTURES` enabled:
- `BookSlotTable.allocate` calls `SyncStrategyRegistry.forVenue`, which throws for an unbound venue;
- `StreamProtocolRegistry` fails startup for an enabled venue with no `VenueStreamBinding`.

So Phase 1 needs at least a no-op strategy binding, and Phase 2 needs the real stream protocol.

### 1.6 Limits at a glance

| | Value | Source |
|---|---|---|
| Subscriptions per WS connection | no limit observed (all 1106 on one) | empirical |
| WS connections per IP | no cap observed up to 150 | empirical |
| WS keepalive | text `{"method":"ping"}` every 10–20s; disconnect after 60s without | docs |
| Sub ack | `{"channel":"rs.sub.depth","data":"success"}` — does **not** echo the symbol | empirical |
| Snapshot rate | 10 req / 2s documented; **4 req/s** clean, 5 req/s occasionally trips | empirical |
| Throttle penalty | none — next window succeeds | empirical |
| Universe | ~1106 USDT contracts (incl. tokenized stocks, dead pairs) | empirical |

---

## 2. Target shape

New package `exchange/mexc/`, registered via `MexcAdapterConfig`, mirroring `binance/`:

| Class | Role |
|---|---|
| `MexcAdapterConfig` | `@Configuration`; contributes the strategy binding, stream binding, instrument source, REST client, snapshot queue. Strategies/protocols are **not** `@Component`s. |
| `MexcDiscoveryProperties` | `screener.exchanges.mexc.discovery.*` inclusion policy. |
| `MexcRestClient` | `WebClient` from `ExchangeWebClientFactory`; unwraps the `{success, code, data}` envelope. |
| `MexcInstrumentSource` | `InstrumentSource` for `MEXC_FUTURES`. |
| `MexcFuturesStreamProtocol` | `StreamProtocol` implementation. |
| `MexcFuturesSyncStrategy` + `MexcSyncContext` | `DepthSyncStrategy` + per-book cursor/buffer. |
| `MexcSnapshotFetcher` | `SnapshotFetcher` for `MEXC_FUTURES`: fetch, classify, pace (Phase 4b). |

---

## 3. Phases

### Phase 0 — Core prep and verification

Mostly the non-additive edits, done once.

- [ ] `Exchange.MEXC`; `Venue.MEXC_FUTURES`. Do **not** add `MEXC_SPOT` until it is implemented, so no
      registry ever sees an unbound venue.
- [ ] Quantity multiplier on `InstrumentCandidate` and `Instrument` (default 1.0; Binance unchanged).
- [ ] Fix `ClassificationRuleService.validateTrackedTicker`: it hardcodes `Venue.of(Exchange.BINANCE, …)`
      and would reject rules on MEXC-only symbols. Validate "tracked on any exchange for this market"
      by `symbol` / `ruleKey`.
- [ ] `screener.exchanges.mexc` YAML block, `enabled: ${MEXC_ENABLED:false}` until Phase 5.
- [ ] **V1 — empirical version capture.** Record ~1 minute of `push.depth` for a few liquid and a few
      illiquid contracts, plus a couple of REST snapshots taken mid-stream. Confirm:
      (a) push `version` increments by exactly 1, (b) snapshot `version` lines up with the push
      counter, (c) `vol` is in contracts (BTC_USDT top-of-book quantities should look like contract
      counts, not BTC). Save findings under `external-docs/mexc/`.

### Phase 1 — Universe discovery

- [ ] `MexcRestClient` with the envelope unwrap; codec buffer sized for `/contract/detail` (large
      response for ~1100 contracts).
- [ ] `MexcInstrumentSource` for `MEXC_FUTURES` only. Filter: `quoteCoin == USDT`,
      `futureType == 1` (perpetual), `state == 0`, `apiAllowed == true`, minus `excluded-symbols`.
      Additional policy per decision D1 (§4).
- [ ] Mapping: `BTC_USDT` → `nativeSymbol=BTC_USDT`, `base=BTC`, `quote=USDT`, so
      `symbol=BTCUSDT` and `ruleKey=BTCUSDT:FUTURES` — user rules apply on MEXC with no change.
      `contractSize` → quantity multiplier.
- [ ] Placeholder no-op `DepthSyncStrategy` binding (drops events, books stay `PENDING`) — see §1.5.

**Verify**: `/api/tickers` lists the MEXC universe; Binance instrument ids are unchanged; a failing
MEXC source does not affect Binance's universe (failure isolation is per source).

### Phase 2 — WebSocket transport

- [ ] `MexcFuturesStreamProtocol` — the current SPI should fit without changes:
  - `subscribeFrame`: `{"method":"sub.depth","param":{"symbol":"<nativeSymbol>"}}`, with
    `subscribe-chunk-size: 1` so core emits one frame per instrument.
  - `routingKey`: `nativeSymbol`.
  - `route`: discriminate on the leading `"channel"` value (`push.depth` vs `rs.sub.depth`, `pong`,
    `rs.error`), then resolve `"symbol":"…"` through `SubscriptionIndex`. Error frames → `warn`.
  - `heartbeat`: `Heartbeat.TextPing({"method":"ping"}, ~15s)` — already supported by
    `StreamConnection`.
- [ ] Sub acks don't echo the symbol: log at debug for now. Ack counting belongs to the P3 health
      surface.
- [ ] Moderate `max-streams-per-connection` (~300 → ~4 connections) so one dropped socket doesn't
      resync every MEXC book at once.
- [ ] `StreamConnection` has no binary `onMessage` override — add one that logs at warn, so a
      gzip/binary frame cannot be silently dropped.

**Verify**: the no-op strategy counts frames; `PipelineHealthLogger` shows ~1.1k msg/s for MEXC
(full universe); no 60s idle disconnects over a long run; Binance throughput unaffected.

### Phase 3 — Sync strategy (unit-tested in isolation)

Same shape as Binance (progress doc §4): one flat dispatch table in `onEvent`, `recover()` called only
from there, buffering only while `RECOVERING`, `PENDING` retrying on every diff, and the §4.10
invariants.

- [ ] `MexcSyncContext`: `ArrayDeque<String>` buffer (bounded), `long lastVersion` (-1 = no sync point),
      single `reset()`.
- [ ] `check()` (assuming V1 confirms +1 semantics):
      - `version <= lastVersion` → `IGNORE`
      - `version == lastVersion + 1` → `OK`, advance cursor
      - otherwise → `DE_SYNCED`
- [ ] Snapshot handling: unwrap `data`, stream `asks`/`bids` into the (empty) book, `computeDistance()`,
      set `lastVersion = snapshot.version`, drain the buffer through the full `handleDiff` path.
      Stale entries `IGNORE`; the first remaining one must be `version + 1` or it is a gap → recover.
      Empty / all-stale buffer = success.
- [ ] Level application: levels are JSON **numbers**, not strings — add a numeric-token variant of the
      level parse; multiply quantity by the instrument's multiplier before `applyLevel`.

**Verify**: a test suite modelled on `BinanceDepthSyncStrategyTest`, driving real MEXC-shaped JSON
(including its field order) through the real `JsonParser`, with `FakeRecoverySink`. Pin: version
located after levels, contract-size scaling, gap → recover, stale snapshot → recover, 510 body never
reaching the strategy (that last one is a Phase 4 test).

### Phase 4 — Recovery

**4a — Generalize the snapshot queue — DONE** (P2 steps 3 + 4, plan
`.claude/plans/snapshot-source-and-request-budget.md`; current shape in progress doc §3.1). It
landed differently from this plan's original sketch: there is no `SnapshotSource` / `RequestBudget` pair.
Core owns the queue; the adapter supplies one `SnapshotFetcher` per venue that both fetches and
paces.

- [x] One generic `SnapshotRequestQueue` (core, not a `@Component`) implementing `RecoverySink`,
      created **per venue** through `SnapshotQueueFactory` by the adapter config and passed to that
      venue's strategy.
- [x] Fetch + classify + budget → one adapter `SnapshotFetcher` (`isAcceptingRequests()` +
      `fetchAll(batch, outcome)`). It reports each slot as `delivered` or `failed`; core turns that
      into exactly one `REST_MSG` / `REST_FAILED` ring event. Binance: `BinanceSnapshotFetcher` +
      `WeightGuard`, fed by `x-mbx-used-weight-1m`.
- [x] Capacity and pacing separated: **capacity** is the queue's `max-batch-size` (the refusal
      that bounds startup memory); **pacing** is the fetcher's — it may spread a batch over time
      inside `fetchAll`, since the queue stays closed until the batch completes.
- [x] `SnapshotFetchQueue` and `WeightLimitFilter` deleted; queue shape is the exchange-level
      `screener.exchanges.<exchange>.snapshot-queue` block.

**Pass condition for 4a**: Binance still reaches ~353 spot + ~525 futures `SYNCED` in ~6 minutes and holds.

**4b — MEXC recovery.** Adapter-only: a `MexcSnapshotFetcher` handed to
`SnapshotQueueFactory.create(venue, fetcher)` from `MexcAdapterConfig`, plus a
`screener.exchanges.mexc.snapshot-queue` block. No core change expected.

- [ ] `MexcSnapshotFetcher`: `GET /api/v1/contract/depth/{symbol}?limit=1000`. Classify the body
      **before** reporting: `success:false` / `code:510` (HTTP 200) → `failed`, never `delivered`;
      HTML 403 → WAF block → `failed`.
- [ ] Pacing inside `fetchAll`: ~4 req/s, evenly spaced with a non-blocking Reactor delay (never
      sleep — `fetchAll` runs on the queue's scheduler thread). `isAcceptingRequests()` returns
      false during a cooldown: one window (~2s) after a 510, ≥60s after a 403 (pauses **all** MEXC
      REST traffic).
- [ ] `snapshot-queue.batch-timeout` must exceed `max-batch-size / rate + rest.response-timeout`
      (e.g. 10 at 4 req/s + 10s ≈ 12.5s). `SnapshotQueueFactory` only checks it against
      `response-timeout`, so state this in the YAML comment.
- [ ] Cold-start expectation: ~1100 contracts at 4 req/s ≈ 4.6 minutes minimum — comparable to
      Binance's ramp.
- [ ] Defer the `depth_commits/{symbol}/1000` gap backfill: it is still a REST call, likely against the
      same limit, so it buys little at first. Revisit once resync rates are measured.

### Phase 5 — End-to-end

The Disruptor, consumer and classifier are already venue-agnostic; nothing new should be needed there.

- [ ] Enable MEXC by default.
- [ ] **Live verification**: MEXC books reach `SYNCED` and hold; resync rate per venue is sane;
      BTC wall notional on MEXC is in the same order of magnitude as Binance (confirms §1.1);
      feed entries keyed `MEXC:FUTURES:…` coexist with `BINANCE:FUTURES:…`.
- [ ] Frontend (`screener-frontend-new`): handle `exchange: "MEXC"` in the WebSocket payload.
- [ ] Update `.claude/docs/multi-exchange-progress.md` (phase table, §5 "what adding an exchange
      requires", decisions log) and add a MEXC sync section alongside the Binance one.

---

## 4. Open decisions

| # | Question | Notes |
|---|---|---|
| D1 | **Universe size** — all ~1106 contracts, or a filtered set? | Full set includes tokenized stocks (`*STOCK_USDT`) and pairs with no activity. Options: exclude list; minimum 24h turnover via `/api/v1/contract/ticker` (`amount24`); intersection with Binance futures. Affects cold-start time and connection count. |
| D2 | **Global tier calibration** | Default tiers were tuned on Binance liquidity; the same dollar wall means more on MEXC. Acceptable for now, but a product question. |
| D3 | **Ordering of 4a vs 3** | The strategy can be built against `FakeRecoverySink` before the queue exists, so 4a (a Binance-only refactor) can run in parallel with Phases 1–3 rather than after. |

---

## 5. Risks

- **Sequence semantics differ from docs** (V1 fails) — `check()` and the snapshot-alignment rule change;
  everything else in the plan holds.
- **Contract size changes across a refresh** — rare; `Instrument` identity is stable across refreshes, so
  a changed multiplier would not be picked up until restart. Acceptable for now; note it.
- **Symbol spelling differs across exchanges** (e.g. a `1000`-prefixed base on one venue only) — the
  rule key won't match, so a user rule set for Binance's spelling won't apply on MEXC. Data-driven,
  not a correctness bug.
- **Akamai WAF on the futures host** — untested at higher rates; the 4 req/s pacing keeps us well
  below where spot tripped.
