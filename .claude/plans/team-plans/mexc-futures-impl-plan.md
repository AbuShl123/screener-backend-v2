# MEXC Futures — Implementation Plan — DONE

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
| Snapshot rate | 10 req / 2s documented; **4 req/s** clean, 5 req/s occasionally trips. One window per IP: shared by `api.` and `contract.mexc.com` and across symbols; `limit` 100–1500 all cost the same; `/contract/detail` not counted (V2) | empirical |
| Throttle penalty | none — next window succeeds | empirical |
| Universe | ~1106 USDT contracts (incl. tokenized stocks, dead pairs) | empirical |

---

## 2. Target shape

New package `exchange/mexc/`, registered via `MexcAdapterConfig`, mirroring `binance/`:

| Class | Role |
|---|---|
| `MexcAdapterConfig` | `@Configuration`; contributes the strategy binding, stream binding, instrument source, REST client, snapshot queue. Strategies/protocols are **not** `@Component`s. |
| `MexcFuturesRestClient` | `WebClient` from `ExchangeWebClientFactory`; unwraps the `{success, code, data}` envelope. Futures-only: MEXC spot is a different REST API (no envelope, other DTOs, query-param depth), so it gets its own client rather than a `BinancePaths`-style parameter. |
| `MexcInstrumentSource` | `InstrumentSource` for `MEXC_FUTURES`. |
| `MexcFuturesStreamProtocol` | `StreamProtocol` implementation. |
| `MexcFuturesSyncStrategy` + `MexcSyncContext` | `DepthSyncStrategy` + per-book cursor/buffer. |
| `MexcSnapshotFetcher` | `SnapshotFetcher` for `MEXC_FUTURES`: fetch, classify, pace (Phase 4b). |

---

## 3. Phases

### Phase 0 — Core prep and verification — DONE

Mostly the non-additive edits, done once.

- [x] `Exchange.MEXC`; `Venue.MEXC_FUTURES`. Do **not** add `MEXC_SPOT` until it is implemented, so no
      registry ever sees an unbound venue.
- [x] Quantity multiplier on `InstrumentCandidate` and `Instrument` (default 1.0; Binance unchanged).
      Validated positive and finite at the candidate; `InstrumentRegistry.register` warns if a
      refresh reports a different value for an existing instrument (kept until restart, §5).
- [x] Fix `ClassificationRuleService.validateTrackedTicker`: it hardcodes `Venue.of(Exchange.BINANCE, …)`
      and would reject rules on MEXC-only symbols. Validate "tracked on any exchange for this market"
      by `symbol` / `ruleKey`. → `InstrumentRegistry.isTracked(symbol, market)`, backed by a set
      of every registered `ruleKey`.
- [x] `screener.exchanges.mexc` YAML block, `enabled: ${MEXC_ENABLED:false}` until Phase 5. Carries
      the `FUTURES` transport block (REST, stream URL, `stream-topic: "{symbol}"`, chunk size 1,
      300 streams/connection, 15s heartbeat, 8 MB codec buffer for the ~2.3 MB `/contract/detail`);
      `discovery` and `snapshot-queue` arrive with Phases 1 and 4b.
- [x] **V1 — empirical version capture.** Record ~1 minute of `push.depth` for a few liquid and a few
      illiquid contracts, plus a couple of REST snapshots taken mid-stream. Confirm:
      (a) push `version` increments by exactly 1, (b) snapshot `version` lines up with the push
      counter, (c) `vol` is in contracts (BTC_USDT top-of-book quantities should look like contract
      counts, not BTC). Save findings under `external-docs/mexc/`.
      → `external-docs/mexc/mexc-depth-versioning-empirical.md`. **(a) failed**: pushes are
      aggregated, but carry undocumented `begin`/`end` fields that are contiguous. (b) and (c) hold.
      Phase 3's `check()` changes accordingly — see the note there.

### Phase 1 — Universe discovery — DONE

- [x] `MexcFuturesRestClient` with the envelope unwrap; codec buffer sized for `/contract/detail` (large
      response for ~1100 contracts). `success:false` (incl. a 200/510) → `MexcApiException`; non-2xx
      (incl. Akamai HTML 403) → `ExchangeApiException`, whose `from(venue, response)` now holds the
      empty-body-safe mapping both clients share.
- [x] `MexcInstrumentSource` for `MEXC_FUTURES` only. Filter: `quoteCoin == USDT`,
      `futureType == 1` (perpetual), `state == 0`, `apiAllowed == true`. **Hardcoded**, not YAML —
      see decision 1 (§6). Exclusions are applied by core afterwards — decision 2 (§6).
      Tokenized stocks (`*STOCK_USDT`) excluded since — decision 4 (§6); the turnover part of D1
      (§4) is still open.
- [x] Mapping: `BTC_USDT` → `nativeSymbol=BTC_USDT`, `base=BTC`, `quote=USDT`, so
      `symbol=BTCUSDT` and `ruleKey=BTCUSDT:FUTURES` — user rules apply on MEXC with no change.
      `contractSize` → quantity multiplier. A row with a missing / non-positive `contractSize` is
      skipped with a warn rather than failing the whole refresh.
- [x] Placeholder no-op `DepthSyncStrategy` binding (`MexcPlaceholderSyncStrategy`; drops events,
      books stay `PENDING`) — see §1.5. Phase 3 replaces it.

**Verify**: `/api/tickers` lists the MEXC universe; Binance instrument ids are unchanged; a failing
MEXC source does not affect Binance's universe (failure isolation is per source).
→ Unit-tested (`MexcInstrumentSourceTest`, `MexcFuturesRestClientTest`, `MexcAdapterConfigTest`), plus a
one-off live fetch through the production WebClient: **1052** contracts selected (1055 eligible − 3
excluded), contract sizes from `1e-5` to `1e7`. The `/api/tickers` check needs MEXC enabled, which
`StreamProtocolRegistry` refused until Phase 2 bound the stream protocol — moved to Phase 2's verify.

### Phase 2 — WebSocket transport — DONE

- [x] `MexcFuturesStreamProtocol` — the SPI fit without changes:
  - `subscribeFrame`: `{"method":"sub.depth","param":{"symbol":"<nativeSymbol>"}}`, with
    `subscribe-chunk-size: 1` so core emits one frame per instrument. The constructor rejects any
    other chunk size, and any `stream-topic` but the bare `{symbol}` (pushes are routed by the
    echoed native symbol).
  - `routingKey`: `nativeSymbol`.
  - `route`: **not** on a leading `"channel"` — V1 showed depth pushes arrive as
    `{"symbol":…,"data":…,"channel":"push.depth",…}`, channel *last*. A frame starting
    `{"symbol":"` is a depth push with its symbol at a fixed offset; a frame starting
    `{"channel":"` is a control frame (`rs.sub.depth`, `pong`, `rs.error`) or a depth push in the
    documented order, which is still routed. A non-`success` ack or `rs.error` → `warn`.
  - `heartbeat`: `Heartbeat.TextPing(15s, {"method":"ping"})`.
- [x] Sub acks don't echo the symbol: logged at debug. Ack counting belongs to the P3 health surface.
- [x] `max-streams-per-connection: 300` (set in Phase 0) → 3 connections for ~680 contracts.
- [x] `StreamConnection.onMessage(ByteBuffer)` → rate-limited `warn`, so a gzip/binary frame is never
      silently dropped.
- [x] Per-venue frame counter, `PipelineMetrics.recordFrame(venue)`, incremented in
      `StreamConnection` for each routed frame and logged by `PipelineHealthLogger` as
      `frames/s <venue>=…`. Needed because the existing `msgs/s` is per shard and mixes venues; the
      placeholder strategy stays a pure no-op.

**Verify** (needs `MEXC_ENABLED=true`): `frames/s mexc/futures` ≈ 1k (V1 measured ~1.1k msg/s for the
full 1106; the stock exclusion leaves ~680); no 60s idle disconnects over a long run; no
`Binary frame` / `sub.depth rejected` / `Stream error frame` warnings; Binance throughput unaffected;
`/api/tickers` lists the MEXC universe (the Phase 1 check deferred to here).
→ Live local run, 2026-10-04, ~10 min: 681 selected, 678 streamed after core exclusions, on 3
connections; `frames/s mexc/futures` steady at 640–740; zero MEXC disconnects (so the 15s ping
holds the socket well past the 60s idle cutoff); none of the three warnings. Binance reached
354/354 spot + 525/525 futures with the ring untouched (`drain max=0ms`). The only disconnects
were Binance 1006s, which happen on local runs only. `/api/tickers` lists the MEXC universe.

### Phase 3 — Sync strategy (unit-tested in isolation) — DONE

Same shape as Binance (progress doc §4): one flat dispatch table in `onEvent`, `recover()` called only
from there, buffering only while `RECOVERING`, `PENDING` retrying on every diff, and the §4.10
invariants.

- [x] `MexcSyncContext`: `ArrayDeque<String>` buffer (bounded at 500), `long lastVersion` (-1 = no sync
      point), single `reset()`.
- [x] `check()` — the V1 predicate over the undocumented `begin`/`end`, not the documented `+1` on
      `version` (which would desync on most pushes):
      - `end < lastVersion` → `IGNORE` (strict `<`, as Binance spot, decision log #10)
      - `begin <= lastVersion + 1` → `OK`, `lastVersion = end`
      - otherwise → `DE_SYNCED`

      One predicate covers the post-snapshot sync point (including the ~1/3 of snapshots that land
      inside a push's range) and steady state. A push without `begin`/`end` throws → resync, so their
      disappearance is loud.
- [x] §1.2 pre-scan: `begin`/`end` are found with `lastIndexOf` on the raw frame (they sit near its
      end) and parsed by hand; only an `OK` push is then streamed through `JsonParser` into the book.
- [x] Snapshot handling: one walk shared with pushes — find the top-level `data` object, stream
      `asks`/`bids` into the (empty) book, read `version`. No `data.version` (incl. a 510 body) →
      resync. Then `computeDistance()`, `lastVersion = version`, drain the buffer through the full
      `handleDiff` path. Empty / all-stale buffer = success.
- [x] Level application: JSON number tokens are read from the parser's char buffer and parsed with
      `JavaDoubleParser`, as on Binance — so an integer `99` and a fractional `99.0` share one key.
      `vol × quantityMultiplier` before `applyLevel`; `orderCount` and anything after it is skipped.

**Verify**: `MexcFuturesSyncStrategyTest` (35 cases) drives MEXC-shaped JSON in the delivered field
order (and once in the documented order) through the real `JsonParser`. Pins: sequence fields after
the levels, aggregated pushes, straddling and boundary snapshots, contract-size scaling, gap /
stale-snapshot / malformed / 510-body → one resync, and the shared dispatch contract (refusal
retries, overflow, `REST_FAILED`, resync counter). `FakeRecoverySink` moved to the `spi` test package
so both adapters' suites use it.

**Wired in 4b**: `MexcAdapterConfig` binds the real strategy to the MEXC snapshot queue;
`MexcPlaceholderSyncStrategy` is deleted.

### Phase 4 — Recovery — DONE

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

**4b — MEXC recovery — DONE** (verified live 2026-10-04). Adapter-only: a `MexcSnapshotFetcher`
handed to `SnapshotQueueFactory.create(venue, fetcher)` from `MexcAdapterConfig`, plus a
`screener.exchanges.mexc.snapshot-queue` block. `MexcPlaceholderSyncStrategy` was swapped for
`MexcFuturesSyncStrategy` in the same change. **No core change**: decision 6 (§6) explains why the
existing queue fits.

*Design (decided 2026-10-04 — rationale in §6, decisions 5–8)*

- [x] **Verification first — V2**, below (done 2026-10-04). Outcome: `rest.base-url` and
      `request-interval` unchanged; `depth-limit` raised 1000 → 1500 (decision 9).
- [x] `MexcFuturesRestClient.depth(symbol, limit)`: `GET /api/v1/contract/depth/{symbol}?limit=…`,
      returning the **raw body** (the strategy stream-parses it) — not unwrapped through
      `MexcResponse`.
- [x] `MexcSnapshotFetcher` **classifies before reporting**. Read only the envelope's `success` /
      `code` with a `JsonParser` and stop; never full-deserialize the ~1500-level (~43 KB) body:

      | Response | Report | Cooldown |
      |---|---|---|
      | 200, `success:true` | `delivered` | — |
      | 200, `success:false` (510 throttle) | `failed` | `throttle-cooldown` (3s) |
      | 403 HTML (Akamai WAF) or 429 | `failed` | `waf-cooldown` (90s) |
      | timeout / connection error / other status | `failed` | — |

      A 510 must never reach the strategy as a snapshot. (The strategy also resyncs on a body
      without `data.version` — defense in depth, not the primary guard.)
- [x] **Pacing: one send clock per fetcher**, not per batch. A `nextSendAt` field; each slot reserves
      `delay = max(0, nextSendAt − now)` and advances `nextSendAt` by `request-interval` (250ms).
      The send waits on `Mono.delay(delay)` (Reactor's parallel scheduler — never sleep, `fetchAll`
      runs on the shared `snapshot-queue` thread), then requests are `flatMap`ped so sends stay
      evenly spaced regardless of response latency (`concatMap` would serialize on round-trip time).
- [x] **Cooldown checked at send time, not at batch start.** When a slot's delay elapses inside a
      cooldown, it is reported `failed` without sending. So a 510 or 403 mid-batch fails the rest of
      that batch promptly, the queue reopens, and those books re-ask once the cooldown ends. No
      retries anywhere, as on Binance.
- [x] `isAcceptingRequests()` = `now >= cooldownUntil` — one volatile read (like
      `WeightGuard.isBanned`). Cooldowns only extend, never shorten (a 3s throttle must not cut a
      90s WAF pause short).
- [x] Logging: the first 510 / 403 of a cooldown at `warn` with its duration, repeats at debug;
      count failures through the existing `PipelineMetrics.recordSnapshotFailure` (`BatchOutcome`
      already does).

*Configuration*

- [x] `screener.exchanges.mexc.snapshot-queue`: `max-batch-size: 12`, `flush-interval: PT0.25S`,
      `batch-timeout: PT20S`.
- [x] Venue-level snapshot block (shape mirroring Binance's per-market snapshot properties):
      `depth-limit: 1500` (the server cap — same cost as 1000, decision 9),
      `request-interval: PT0.25S`, `throttle-cooldown: PT3S`, `waf-cooldown: PT90S`.
- [x] **Fail fast in `MexcAdapterConfig`**: `batch-timeout` must exceed
      `(max-batch-size − 1) × request-interval + rest.response-timeout` (12 → 2.75s + 10s = 12.75s;
      PT20S leaves margin). `SnapshotQueueFactory` only checks against `response-timeout` — it cannot
      know the fetcher's pacing — but the adapter config holds both property sets. A too-short
      timeout would seal batches whose tail requests had not even been sent.

*As built (deviations and details)*

- Venue snapshot block bound by `MexcSnapshotProperties` (`screener.exchanges.mexc.venues.<market>.snapshot`,
  the `BinanceSnapshotProperties` pattern); `depth-limit` validated to 1–1500.
- Fail-fast formula is **`max-batch-size × request-interval + response-timeout`** (13s), not
  `(max-batch-size − 1) × …` (12.75s): with the persistent send clock, a batch's first send can wait
  up to one interval for the previous batch's last. PT20S still leaves margin.
- Escalation logging: WARN when a cooldown starts, or when a 403/429 escalates a 510 pause; repeats
  inside the pause at debug. A `success:false` with a code other than 510, or an unreadable / empty
  200 body, is reported failed at WARN with no cooldown.
- Tests: `MexcSnapshotFetcherTest` (16) drives a real `MexcFuturesRestClient` under a hand-rolled
  virtual-time `ManualScheduler` (no `reactor-test` dependency): spacing within and across batches,
  510 / 403 / 429 mid-batch, cooldown never shortening, no-cooldown failures, and envelope
  classification (incl. stopping at `success:true` before `data`). Plus `MexcSnapshotPropertiesTest`
  (binds both shipped YAMLs, which pass the fail-fast check) and the queue wiring in `MexcAdapterConfigTest`.

**Verify** (live, `MEXC_ENABLED=true`): MEXC books ramp to `SYNCED` in ~3 min; no 510 / 403 WARNs
from `MexcSnapshotFetcher` in steady state; batch debug lines show sends 250ms apart; Binance's
ramp unaffected.
→ Live local run, 2026-10-04, 25 min, `-Xmx512m`, all three venues enabled:
- **Ramp**: 678/678 MEXC books `SYNCED` ~3m25s after the sockets opened, in 74 batches / 681
  sends: 678 plus 3 resyncs, all during the ramp. Effective 3.5 req/s. A full batch of 12 takes
  3.1–3.4s against an ideal 3.0s, so the inter-batch gap costs ~10%, the top of decision 6's
  estimate. Every batch logged `sends from +0 ms`: the gap always exceeded one interval.
- **Steady state** (~22 min): 678/678 held with **zero** resyncs, zero snapshot failures, no
  510 / 403 / 429, no MEXC disconnects. `frames/s mexc/futures` was 660–820.
- **Binance unaffected**: 354/354 spot by ~2 min and 525/525 futures by ~2.5 min, through two
  local-only 1006 reconnects on futures. Three more 1006s at ~10 min resynced up to 177
  futures books and were back to 525/525 ~80s after the last one. MEXC was untouched. Ring free
  stayed at ~65.5k/65.5k, `drain max` ≤ 9ms, and the heap stayed flat through the run.

*Expectations*

- Cold start: ~678 contracts (after the stock exclusion and core exclusions) at 4 req/s ≈ **170s
  minimum**, ~3 minutes with inter-batch gaps — comparable to Binance's ramp.
- A dropped socket (300 books) recovers in ~75s. Steady-state resync demand is far below 4 req/s,
  so the rate only matters for cold start and mass reconnects.
- The last book of a 12-batch waits ~3s for its snapshot and buffers a handful of pushes —
  nowhere near the 500 cap.

*V2 — empirical checks before / while building* (Phase 0's V1 style; save findings to
`external-docs/mexc/mexc-api-rate-limits-empirical.md`, gentle runs that stop at the first
rejection) — **all done 2026-10-04**, details in the empirical doc's "Futures — V2 follow-up".

- [x] **V2a — host mismatch.** The futures measurements hit `contract.mexc.com`; the configured
      `rest.base-url` is `api.mexc.com`. They may sit behind different Akamai configs or limiters.
      Re-run the 4 req/s × 20s and 5 req/s probes on `api.mexc.com/api/v1/contract/depth/BTC_USDT`.
      If it differs (or is worse), switch `base-url` to `contract.mexc.com` and confirm
      `/api/v1/contract/detail` still works there for discovery.
      **Result: same limiter.** `api.mexc.com` matched `contract.mexc.com` (4 req/s clean, 5 req/s
      tripped once in two runs), and 4 + 4 req/s split across both hosts tripped both within ~2s.
      `base-url` stays `api.mexc.com`.
- [x] **V2b — window scope: per IP or per symbol?** Every probe so far used `BTC_USDT` only. Run
      ~8 req/s rotating across distinct symbols (stop at the first 510). If per-symbol, 4 req/s is
      very conservative and `request-interval` can drop; if per-IP (expected), it stays.
      **Result: per IP.** 8 req/s over 24–40 symbols tripped after 7–20 OKs, the same band as one
      symbol. `request-interval` stays `PT0.25S`.
- [x] **V2c — does `limit` change the cost?** Repeat the 5 req/s probe with `limit=1000` vs no
      `limit` / a small one. If `limit=1000` trips earlier, either lower `depth-limit` (the price
      filter drops far levels anyway — check how deep 10% from mid reaches on liquid contracts) or
      lengthen `request-interval`.
      **Result: no, for any useful depth.** `limit` 100 / 500 / 1000 / 1500 all trip at ~10 per 2s;
      only `limit=20` escaped the limiter. The server caps at 1500 levels per side (no `limit` and
      `limit=2000` both return 1500), and 1000 levels reach only ~0.5–0.7% from mid on BTC_USDT
      (1500: ~0.9%) — far inside the 10% filter. So `depth-limit` goes **up** to 1500 at no cost.
- [x] **V2d — does discovery share the budget?** Does `/api/v1/contract/detail` count against the
      depth window (e.g. a detail call followed immediately by 9–10 depth calls within 2s)? 4 req/s
      leaves ~1 req/s of headroom under the ~5 req/s limit, and discovery is rare, so this is
      expected to be a non-issue — confirm rather than design for it.
      **Result: not shared.** Detail at 2 req/s alongside depth at 4 req/s (6 req/s combined): no
      rejection on either.

*Deferred*

- [ ] The `depth_commits/{symbol}/1000` gap backfill: still a REST call, likely against the same
      limit, so it buys little at first. Measured in 4b's live run: 3 resyncs in 25 min, all
      during the ramp, so it is not worth building unless longer runs show otherwise.

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
| D1 | **Universe size** — all ~1106 contracts, or a filtered set? | *Partly decided: tokenized stocks excluded (§6, decision 4); turnover filter still open.* Full set includes tokenized stocks (`*STOCK_USDT`; ~370 of the 1052 selected on 2026-10-04 — no clean field marks them, only the name suffix and `conceptPlate` `mc-trade-zone-Stock`) and pairs with no activity. Options: exclude list; minimum 24h turnover via `/api/v1/contract/ticker` (`amount24`); intersection with Binance futures. Affects cold-start time and connection count. |
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
  below where spot tripped. No 403 seen on either futures host up to 8 req/s (V2); sustained
  violation is still untested. A block pauses snapshots for `waf-cooldown`.

---

## 6. Decisions made during implementation

1. **Universe eligibility filters are hardcoded in every adapter**, not YAML. MEXC:
   `quoteCoin == USDT ∧ futureType == 1 ∧ state == 0 ∧ apiAllowed`. Binance, aligned for consistency:
   `status TRADING ∧ quote USDT`, plus `contractType PERPETUAL` on futures and spot ⊆ futures on
   spot. They define what the pipeline can handle (a non-USDT quote needs different notional math),
   so changing one is a code change anyway. Spot ⊆ futures is a load decision rather than a
   capability one, but dropping it multiplies Binance spot and belongs in its own change.
   `BinanceDiscoveryProperties` and `MexcDiscoveryProperties` are gone, and so is every
   `screener.exchanges.<exchange>.discovery` block.
2. **One exchange-agnostic exclusion list**: `screener.discovery.excluded-symbols`
   (`DiscoveryProperties`), written as `base + quote` (`USDCUSDT`), never in native form. Applied once
   by `InstrumentUniverseService` to every source's validated result, matched on
   `Instrument.symbol(base, quote)`. It is the union of the two former lists, so MEXC now also drops
   `FDUSD`, `DAI`, `PYUSD` and `USD1`. Trade-off accepted: no per-exchange exclusion (nothing needs
   one yet). The "universe selected" INFO line is therefore logged by core, after exclusion; sources
   log their pre-exclusion count at debug.
3. **REST clients are per API, not per exchange.** `MexcRestClient` → `MexcFuturesRestClient`.
   Binance's spot and futures are one API under two prefixes, hence `BinancePaths`; MEXC's are not
   (envelope, DTOs, symbol format and depth-path shape all differ, on the same host), so MEXC spot
   will get its own `MexcSpotRestClient`.
4. **Tokenized-stock perpetuals are excluded** (2026-10-04): `MexcInstrumentSource` drops any symbol
   ending in `STOCK_USDT` (~370 of 1052). They track US equities, not crypto: thin, market-maker-quoted
   books that go quiet when the stock market closes, no Binance counterpart for user rules, and ~1.5 min
   of extra cold-start snapshots. No field marks them; the name suffix is the filter
   (`conceptPlate` `mc-trade-zone-Stock` is the fallback signal if it ever stops matching). A
   non-crypto contract without the suffix (e.g. `XLE`, sampled in V1, which looks like an ETF ticker)
   is not caught by it.
5. **Futures snapshots are paced at a fixed 4 req/s, not adaptively** (2026-10-04). Measured: 4 req/s
   clean for 20s; 5 req/s (the documented 10 / 2s) occasionally trips, because jitter bunches
   arrivals into one window. Evenly spaced at 250ms, any 2s window — fixed or sliding — holds at
   most 8. Probing upward (AIMD) would buy ~10% of cold-start time, but a trip costs a failed slot
   plus a 3s cooldown (12 lost sends), so it loses more than it gains; and outside cold start and
   mass reconnects the demand is far below 4 req/s anyway. The rate is config
   (`request-interval`); V2b confirmed the window is per IP, so it stays at 250ms. The spot numbers (12 req/s clean,
   Akamai WAF at 20–30 req/s) do **not** apply: different host path, different limiter — futures is
   bound by MEXC's own 10-per-2s window, which fires long before the WAF.
6. **Queue capacity ≠ request rate; the core queue is unchanged** (2026-10-04). On Binance the two
   look coupled because `BinanceSnapshotFetcher` fires a whole batch in parallel. On MEXC,
   `max-batch-size` only bounds how many books are `RECOVERING` (buffering) at once; the fetcher
   paces inside `fetchAll`, which the `SnapshotFetcher` contract already permits ("may spread the
   batch over time"). `BatchOutcome`'s one-outcome-per-slot guarantee covers mid-batch failures
   unchanged. The one cost is that batches don't overlap: between batches there is a dead gap of
   the last response's round-trip plus ≤ one `flush-interval`. Because the send clock persists
   across batches, the gap only costs throughput beyond the 250ms spacing — ~5–10%. Removing it
   means letting the queue reopen while a batch is still pacing (pipelining) — a core change not
   worth making for that.
   `max-batch-size: 12` ≈ 2.75s of sends per batch: amortizes the inter-batch gap over more sends
   than a smaller batch, while tail books still buffer only a few pushes. 8 (one 2s window) would
   work equally well; nothing in the design depends on the exact value.
7. **One persistent send clock; cooldown checked at send time** (2026-10-04). A per-batch
   `i × interval` schedule would restart spacing at every batch and could put two sends closer
   than 250ms across a boundary (e.g. after a batch whose last request failed fast). A
   fetcher-wide `nextSendAt` keeps spacing global — across batches and cooldowns. Checking the
   cooldown when each slot's delay elapses (rather than once in `fetchAll`) lets a 510 or 403 that
   arrives mid-batch stop the rest of the batch from being sent.
8. **Cooldowns: 3s after a 510, 90s after a 403 / 429** (2026-10-04). MEXC sends no `Retry-After`
   and no rate-limit headers, so durations are fixed config. 510: the window is ~2s and empirically
   carries no penalty (a request 0.5s after a rejection succeeded); 3s covers a full window plus
   slack, and at 4 req/s a 510 means our model of the limit is wrong (another client on the IP,
   shared budget), so pausing is the right reaction to a rare event. 403/429: the Akamai block is
   host-wide and "flappy" for ~20–100s on spot, and quick retries hit edges still blocking, so
   ≥60–90s. Cooldowns only extend, so a 510 never shortens a WAF pause.
9. **Snapshots request the full 1500 levels** (2026-10-04, V2c). `limit` 100–1500 costs the same
   against the 10-per-2s window, and 1500 is the server's cap. 1000 levels reach only ~0.5–0.7% from
   mid on BTC_USDT, so the extra 500 widen the seeded book (~0.9%) for ~14 KB more per response.
   Even 1500 stays far inside the 10% price filter on liquid contracts; levels beyond it enter the
   book only through pushes.
