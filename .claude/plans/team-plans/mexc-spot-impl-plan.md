# MEXC Spot — Implementation Plan

**Created**: 2026-10-04, branch `feature/multi-exchange`.
**Scope**: add MEXC spot as the fourth venue (`MEXC_SPOT`). MEXC futures is done and live
(`.claude/plans/mexc-impl-plan.md`, `mexc-impl-phase-5.md`).

**Read first**
- `.claude/docs/multi-exchange-progress.md`: the SPI, the core, and the MEXC futures sync and
  recovery, which spot mirrors.
- `external-docs/mexc/mexc-api-contracts.md` §2.1, §3.1: spot REST and WS contracts (docs-derived).
- `external-docs/mexc/mexc-ws-limits-empirical.md` and `mexc-api-rate-limits-empirical.md`: what has
  been measured for spot so far.
- `MexcFuturesSyncStrategy`, `MexcSnapshotFetcher`, `MexcInstrumentSource`: the templates.

---

## 1. Facts that shape the design

### 1.1 The stream is Protobuf, so the pipeline's first binary venue

Spot market-data pushes are Protobuf (`PushDataV3ApiWrapper`, from
`github.com/mexcdevelop/websocket-proto`) in **binary** WS frames. Control frames (subscribe ack,
`PONG`) are still text JSON. The pipeline is text end to end:

- `StreamProtocol.route(String, SubscriptionIndex)`
- `DepthEvent.rawJson` is a `String`
- `SubscriptionIndex.resolve(String, int, int)`
- `StreamConnection.onMessage(ByteBuffer)` only warns and drops

REST snapshots (`/api/v3/depth`) are JSON. Only `WS_MSG` events carry binary.

### 1.2 Which Protobuf body, and what it holds, is unverified

The contracts doc names `publicincreasedepths`. That is the body of the older
`spot@public.increase.depth` channel. The `spot@public.aggre.depth.v3.api.pb@100ms@<SYMBOL>`
channel most likely uses `publicAggreDepths` (wrapper field 313, from memory, **unverified**), with
`asks` / `bids` items of `{price, quantity}` and `fromVersion` / `toVersion`, all as **strings**.
Field numbers must be confirmed against the `.proto` files and a live capture before the decoder is
written (Phase 0, V1).

### 1.3 Sequence semantics are documented, not measured

Docs: each push carries `fromVersion` / `toVersion`, and the snapshot's `lastUpdateId` is on the same
counter. That is Binance-spot-shaped, the same as the futures `begin` / `end` rule. The futures docs
were wrong about their sequence rule (`mexc-depth-versioning-empirical.md`), so the spot docs are not
trusted until V1 confirms contiguity and snapshot alignment.

### 1.4 30 subscriptions per connection, and failure arrives as success

Hard-enforced and cumulative per connection (empirical). A rejected subscribe still replies
`"code":0`; only `msg` reveals it: `Not Subscribed successfully! [<ch>,…]`. The protocol must parse
that out, or books silently never receive frames.

### 1.5 REST limits: spot measured alone, never next to futures

Spot `/api/v3/depth` alone is clean at ~10 req/s. The binding limit is Akamai's WAF (HTML 403, no
`Retry-After`, per IP and host-wide, ~20–30 req/s sustained or a ~120-request burst). Futures
already uses `rest.base-url: https://api.mexc.com`, **the same host**, so the two venues' traffic
probably adds up at the WAF (4 + ~10 req/s). Untested. If it adds up, two independent fetchers are
not safe (progress doc §11, "a rate limit shared by two venues of one exchange").

### 1.6 Keepalive and lifetime

- Docs: `{"method":"PING"}` → `{"msg":"PONG"}`.
- The server drops a connection with no subscription after 30s. It also drops one whose
  subscriptions have had **no data flow for 60s**. Whether PING prevents the second case is untested.
  The rule below (spot requires futures) keeps dead symbols mostly out.
- Connections have a 24h lifetime. Core reconnects. Books stay `SYNCED` across the reconnect, and the
  first push afterwards fails the sequence check and resyncs (progress doc §12, "no reset lane").

### 1.7 A venue is dark until its YAML block exists

`ExchangesProperties.isEnabled(venue)` requires a `venues.<MARKET>` block. `Venue.MEXC_SPOT` can land
early. The `SPOT` block goes into `application-local.yml` during development and into
`application.yml` in the last phase. No new flag is needed.

### 1.8 Limits at a glance

| | Value | Source |
|---|---|---|
| Subscriptions per WS connection | 30, hard | empirical |
| WS connections per IP | no cap observed up to 300 | empirical |
| Keepalive | text `{"method":"PING"}`; idle drop after 30s / 60s | docs |
| Sub ack | `{"id":…,"code":0,"msg":…}`; rejection only visible in `msg` | empirical |
| Snapshot rate (alone) | ~10 req/s budget; WAF trips at 20–30 req/s or bursts | empirical |
| Snapshot rate (with futures) | **unknown** | Phase 0, V2 |
| Snapshot depth | `limit` up to 5000 (~104 KB); cost vs smaller limits **unknown** | Phase 0, V2 |
| Universe | 1497 USDT pairs, before the futures filter | empirical |

---

## 2. Decisions

1. **Universe: spot requires futures**, the same rule as Binance. A MEXC spot pair is tracked only if
   `base + quote` is in the MEXC futures universe after its own filters (perpetual, enabled, API
   allowed, `*STOCK_USDT` excluded). The native forms differ (`ETHUSDT` vs `ETH_USDT`), so the match
   is on `base + quote`, never on the native symbol. `MexcInstrumentSource` therefore spans both
   venues, like `BinanceInstrumentSource`, and fetches `/api/v3/exchangeInfo` and
   `/api/v1/contract/detail` concurrently. A failure in either one freezes both venues. That is the
   same trade-off Binance accepts.
   Spot filter: `quoteAsset == USDT`, `status == "1"`, `isSpotTradingAllowed == true`, and in the
   futures set. Status encoding to be confirmed in V4.
2. **Binary transport is an additive SPI change.** Text venues are untouched:
   - `StreamProtocol` gains `default int route(ByteBuffer frame, SubscriptionIndex index)`
     returning `IGNORED`.
   - `DepthEvent` gains a `ByteBuffer rawBytes` beside `rawJson`, cleared in `clear()`.
   - `DepthEventPublisher` gains `publishFrame(int, ByteBuffer)`.
   - `SubscriptionIndex` gains a `resolve` over a byte range. Symbols are ASCII, so it compares
     bytes to chars without allocating.
   - `StreamConnection.onMessage(ByteBuffer)` routes and publishes, like the text path.
   Two typed fields, not an `Object payload`.
3. **Pass the frame's `ByteBuffer` by reference**, the way the text path passes a `String`, as long
   as java-websocket allocates a fresh buffer per frame (to verify in Phase 1). Buffering while
   `RECOVERING` then just stores the reference. If the buffer turns out to be reused, copy into a
   `byte[]` at the publisher instead.
4. **A hand-rolled Protobuf wire reader, not `protobuf-java` generated classes.** Three messages
   matter: the wrapper, the depth body and the level item. A reader that walks tags and varints,
   skips unknown fields, and parses ASCII decimal bytes with `JavaDoubleParser.parseDouble(byte[],
   off, len)` is ~100 lines and allocation-free. Generated classes allocate several objects per level
   per frame and add a `protoc` build step. Tests use the real binary frames captured in Phase 0.
   `protobuf-java` may be added in **test scope** to build edge-case frames.
5. **Validate, then apply.** The decoder locates `fromVersion` / `toVersion` first, runs `check()`,
   then streams the levels into the book. Same flow as futures, and indifferent to field order.
6. **Standalone `MexcSpotSyncStrategy`** mirroring `MexcFuturesSyncStrategy`. That makes three
   copies of the `onEvent` dispatch skeleton. Extracting a shared skeleton is a separate, optional
   cleanup after spot is green (§5), once three real copies show what is common.
7. **Own REST client.** `MexcSpotRestClient`: there is no `{success, code, data}` envelope, the DTOs
   differ, and depth is a query parameter (futures plan, decision 3).
8. **Tier calibration (D2) stays the very last step**, after every venue is wired. It is a product
   decision for the business client and applies to spot as well as futures. Not in this plan's
   phases.

---

## 3. Target shape

New classes in `marketdata/adapter/mexc/`, registered in `MexcAdapterConfig`:

| Class | Role |
|---|---|
| `MexcSpotRestClient` | `WebClient` from `ExchangeWebClientFactory`; `exchangeInfo()`, `depth(symbol, limit)`. Non-2xx (incl. Akamai HTML 403) → `ExchangeApiException`. |
| `dto/MexcSpotSymbolDto` (+ `exchangeInfo` wrapper) | Spot discovery DTOs. |
| `MexcInstrumentSource` (extended) | Both venues; decision 1. |
| `MexcProtoReader` (name TBD) | Allocation-free Protobuf wire reader over a `ByteBuffer`: tags, varints, length-delimited ranges. |
| `MexcSpotStreamProtocol` | Subscribe frames, text-route (ack / pong / rejection), binary-route (symbol from the wrapper), `TextPing`. |
| `MexcSpotSyncStrategy` + `MexcSpotSyncContext` | `DepthSyncStrategy`; buffers `ByteBuffer`s while recovering. |
| `MexcSpotSnapshotFetcher` (or a parameterized `MexcSnapshotFetcher`) | Classify and pace spot snapshots; shape depends on V2. |

---

## 4. Phases

Phase 0 and Phase 1 are independent and can run in parallel. Each phase is its own commit (or a few).

### Phase 0 — Empirical verification

A Python probe (`websockets` + `protobuf`, stubs compiled from `mexcdevelop/websocket-proto`), as for
the futures V-tasks. Every run is gentle and stops at the first rejection. Findings go in
`external-docs/mexc/`, and they **override** `mexc-api-contracts.md` where they disagree.

- [ ] **V1 — versioning and wire format.** Record ~2 min of `aggre.depth@100ms` for a few liquid and
      a few illiquid pairs, with REST snapshots taken mid-stream. Confirm:
      (a) which wrapper body field carries the depth, and every field number and type actually used
          (`price` / `quantity` / versions as strings?);
      (b) `fromVersion == previous toVersion + 1` on every push, or the real rule;
      (c) the snapshot `lastUpdateId` is on the push counter, and how often it straddles a push;
      (d) snapshot + pushes reproduces a later snapshot (top 50 levels);
      (e) whether the symbol is in wrapper field `symbol`, only in `channel`, or both.
      **Save 20–50 raw binary frames per symbol as test fixtures**
      (`src/test/resources/mexc/spot/`), plus one snapshot body.
      → `mexc-spot-depth-versioning-empirical.md`
- [ ] **V2 — REST budget alongside futures.**
      (a) spot `/api/v3/depth` at ~8 req/s **concurrently** with futures depth at 4 req/s, both on
          `api.mexc.com`;
      (b) the same, with futures on `contract.mexc.com`. Does a separate host give a separate WAF
          budget?
      (c) spot `limit=5000` vs `1000` vs `100`: is the rate the same? body size; reach from mid on
          BTC / ETH / a mid-cap vs the 10% price filter;
      (d) 429 vs 403 body shape on spot, if one shows up.
      → append to `mexc-api-rate-limits-empirical.md`
- [ ] **V3 — idle drop.** Subscribe to a few illiquid pairs that are also in the futures set, send
      `PING` every 15–20s, hold for 3+ min. Is the connection dropped for having no data flow?
      Record the `PONG` frame exactly.
      → append to `mexc-ws-limits-empirical.md`
- [ ] **V4 — discovery.** `/api/v3/exchangeInfo`: body size (codec buffer), the `status` encoding
      (`"1"` vs `"ENABLED"`), and how many USDT pairs survive "spot requires futures".
      → `mexc-api-contracts.md` §2.1 note, or the V1 doc

**Pass**: every "unknown" row in §1.8 and §1.2 / §1.3 has a measured answer, and fixtures are
committed.

### Phase 1 — Core binary-frame support

No venue uses it yet. Decision 2.

- [ ] Confirm java-websocket (1.5.7) hands `onMessage(ByteBuffer)` a fresh buffer per frame,
      including reassembled fragmented frames. Decide reference vs copy (decision 3) and record it
      in `DepthEvent`'s javadoc.
- [ ] `StreamProtocol.route(ByteBuffer, SubscriptionIndex)` default method; javadoc carries the same
      hot-path rules as the text `route`.
- [ ] `DepthEvent.rawBytes`; `DepthEventPublisher.publishFrame(int, ByteBuffer)`;
      `DisruptorDepthEventPublisher` sets one payload field and nulls the other.
- [ ] `SubscriptionIndex.resolve(ByteBuffer, int start, int end)`, allocation-free.
- [ ] `StreamConnection.onMessage(ByteBuffer)`: route → publish / unknown-frame note. Keep the WARN
      for a venue whose protocol returns `IGNORED` for every binary frame (the old safety net), e.g.
      count binary frames per connection that routed nowhere.
- [ ] Tests: publisher round trip, byte-range resolve (hit, miss, same-length near-miss), the
      default `route` returning `IGNORED`.

**Pass**: full suite green; Binance and MEXC futures behave the same, `SYNCED` locally.

### Phase 2 — Discovery and stream

Spot frames reach the ring; books stay `PENDING`. Needs V1 and V4.

- [ ] `Venue.MEXC_SPOT`; remove the "deliberately absent" comments in `Venue` and
      `MexcAdapterConfig`.
- [ ] `MexcSpotRestClient` + spot DTOs; `exchangeInfo()` only for now.
- [ ] `MexcInstrumentSource` spans both venues (decision 1): concurrent fetch, spot filtered by
      the futures set on `base + quote`, quantity multiplier 1.0. Tests: native-form mismatch,
      a spot pair with no future excluded, a futures-only contract not producing a spot row,
      all-or-nothing on either call failing.
- [ ] `MexcProtoReader` + tests on the V1 fixtures.
- [ ] `MexcSpotStreamProtocol`:
  - `subscribeFrame`: `{"method":"SUBSCRIPTION","params":[…],"id":<requestId>}`.
  - `routingKey`: the native symbol, or the full channel, depending on V1(e).
  - text `route`: ack → `IGNORED`. An ack whose `msg` contains `Not Subscribed successfully! [`
    with a non-empty list → WARN naming the channels. `PONG` → `IGNORED`. Anything else →
    rate-limited WARN.
  - binary `route`: read the symbol (or channel) range from the wrapper, then `index.resolve`.
  - `heartbeat`: `TextPing(interval, "{\"method\":\"PING\"}")`.
- [ ] No-op `VenueStrategyBinding` for `MEXC_SPOT`, replaced in Phase 3.
- [ ] YAML: a `venues.SPOT` block in **`application-local.yml` only**: `stream-url:
      wss://wbs-api.mexc.com/ws`, `stream-topic: "spot@public.aggre.depth.v3.api.pb@100ms@{symbol}"`,
      `max-streams-per-connection: 30`, `subscribe-chunk-size: 30`, `max-connections` sized from V4
      plus headroom, `heartbeat-interval-seconds: 15` (or per V3), `rest` block with
      `codec-buffer-size-mb` per V4.

**Pass**: locally, every spot instrument receives frames (frame counter per venue in the health
log), zero `Not Subscribed` WARNs, zero unknown frames, connections stay up for 10+ min.

### Phase 3 — Sync and recovery

Books reach `SYNCED`. Needs V1 and V2.

- [ ] `MexcSpotSyncStrategy` + `MexcSpotSyncContext`, following progress doc §7 and the
      `DepthSyncStrategy` contract (`REST_FAILED` on `RECOVERING` → reset + `PENDING`).
      `check()` per V1. Expected shape, as on futures:
      `to < lastVersion` → `IGNORE`; `from <= lastVersion + 1` → `OK`, `lastVersion = to`;
      otherwise `DE_SYNCED`.
      Snapshot apply: JSON `lastUpdateId` + `bids` / `asks` (string pairs), parsed in one
      streaming pass.
- [ ] Spot snapshot fetcher. Classify: 200 → delivered. Akamai HTML 403 or 429 → failed +
      `waf-cooldown`. Anything else → failed. Pace with one send clock, like `MexcSnapshotFetcher`.
      Then, by the V2 result:
  - **independent budgets** → its own fetcher and queue, its own `request-interval`;
  - **shared WAF budget, but a separate host fixes it** → move futures to `contract.mexc.com`
    (one YAML line; V2a showed the futures 510 window is the same on both hosts);
  - **shared, no way around it** → one send clock per exchange, shared by both MEXC fetchers. This
    is the progress doc §11 gap; design it as a small core addition, not a MEXC special case.
- [ ] `snapshot` block for `SPOT` in `MexcSnapshotProperties` (depth limit per V2c), and
      `requireBatchTimeoutCoversPacing` applied to spot too. `mexc.snapshot-queue` is per exchange
      today. If spot needs a different shape, that is a config change in `ExchangesProperties`.
- [ ] Tests on fixtures: straddling snapshot, gap → `DE_SYNCED`, stale push → `IGNORE`, buffer
      replay, `REST_FAILED`, zero quantity removes the level, price-filter drop, the 403-HTML and 429
      classification.

**Pass**: locally, all spot books `SYNCED` within a few minutes of start and held for 20+ min with
futures also running; no WAF 403 in the log; one book's top 50 levels matched against REST.

### Phase 4 — Enable and wrap up

- [ ] Live correctness: MEXC BTCUSDT spot top walls vs Binance spot (same order of magnitude), one
      level checked against MEXC's website. The global feed carries `MEXC:SPOT:…` entries.
- [ ] Move the `SPOT` block into `application.yml`. All four venues `SYNCED`.
- [ ] Progress doc: phase table, a MEXC spot section (binary transport, decoder, sync, budget), the
      SPI change in §5 / §11, decisions from §2 added to the log. Update `CLAUDE.md`'s module map and
      the "MEXC spot next" line in Future Work.
- [ ] Frontend note in `.claude/docs/for-frontend/` if `exchange: "MEXC"` + `market: "SPOT"` needs
      anything new.
- [ ] Long run (recommended, not blocking): several hours. Covers the 24h lifetime drop, idle drops,
      and WAF behaviour from the server's IP.

---

## 5. After this plan

- **Tier calibration (D2)** for MEXC spot and futures. Product decision with the business client;
  the last step once everything is wired.
- **Optional cleanup**: extract the `onEvent` dispatch skeleton shared by the Binance base,
  `MexcFuturesSyncStrategy` and `MexcSpotSyncStrategy`, if the three copies show a clean seam.
- **D1, the turnover filter**, remains open for both MEXC venues.
