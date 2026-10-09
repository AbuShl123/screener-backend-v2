# MEXC Spot: Implementation Plan

**Created**: 2026-10-09, branch `feature/mexc-spot`.

Adds `MEXC_SPOT` as the fourth venue. MEXC futures (`adapter/mexc/`) is the template. Everything
venue-agnostic already exists except one thing: the pipeline is text end to end, and MEXC spot
streams depth as **Protobuf binary frames**. Read `.claude/docs/multi-exchange-progress.md` (§11
"Not yet exercised", §12 gaps) and `external-docs/mexc/` first.

## Decisions

1. **Universe = Binance's rule.** A spot pair is eligible only if its base asset has an eligible
   MEXC futures contract. Since futures eligibility already excludes TradFi, spot inherits that
   filter. Spot-side eligibility: `quoteAsset = USDT ∧ status = "1" ∧ isSpotTradingAllowed`.
   The match is spot `baseAsset == ` futures **`baseCoinName`**, not `baseCoin`: on 17 contracts
   `baseCoin` is an internal id (`TRUMPOFFICIAL`, `FILECOIN`), and matching on it would lose 16 spot
   pairs (549 vs 533). The match only decides inclusion: futures instruments keep `base = baseCoin`
   as today (see `mexc-spot-depth-empirical.md` §6 for why).
2. **Hand-written Protobuf wire reader, no `protobuf-java` codegen.** Generated classes build a full
   object graph with a `String` per price and quantity on every frame. That is exactly the full-POJO
   decode the hot-path rules forbid, and it adds `protoc` + a Maven plugin to the build. The wire
   format is varint tags + length-delimited fields, and we need about 10 fields across 3 messages.
3. **`DepthEvent` gets a second typed payload field** (`ByteBuffer rawBytes`) rather than one
   `Object payload`, so the hot path never casts.
4. **`EventType` is unchanged.** It is provenance, not encoding. A MEXC spot `WS_MSG` is always
   binary, and the strategy knows that.
5. **No copy of the frame.** The `ByteBuffer` from java-websocket goes into the ring as-is, like the
   `String` does today. This relies on Java-WebSocket 1.5.7 allocating a fresh payload buffer per
   frame (to be confirmed and pinned by a test, Phase 1). Consequence: routing (reader thread) and
   parsing (shard thread) both read with **absolute indexes** and never move `position`.
6. **Snapshots stay text.** `GET /api/v3/depth` returns Binance-spot-shaped JSON
   (`lastUpdateId`, `bids`/`asks` as `[["price","qty"],…]`), so `REST_MSG` keeps using `rawJson`.

## What does not change

`OrderBookClassifier`, `feed/`, `ws/`, `DepthEventHandler`, `BookSlotTable`, `SnapshotRequestQueue`,
and the Binance and MEXC futures adapters. User rules are exchange-independent (`ruleKey` =
`base + quote : SPOT`), so an existing SPOT rule applies to MEXC spot books with no change.
`quantityMultiplier` is 1 (spot quantities are already base asset).

---

## Phase 0: Empirical capture

MEXC's documented version rule for futures turned out to be wrong, so nothing below Phase 1 is built
on the spot docs alone. Script under `.claude/tools/mexc-spot-capture/`. Record only MEXC spot.

Confirm:

1. **Field numbers and wire order.** Record raw binary frames for a handful of symbols (one liquid,
   one mid, one illiquid) and decode them with `protoc --decode_raw`. Compare against
   `mexcdevelop/websocket-proto`. Expected (from memory, unverified): the `aggre.depth` channel
   arrives in `PushDataV3ApiWrapper` field **313 `publicAggreDepths`**, not `publicIncreaseDepths`
   (302) as `mexc-api-contracts.md` says; inside it `asks = 1`, `bids = 2`, `eventType = 3`,
   `fromVersion = 4`, `toVersion = 5`; level `price = 1`, `quantity = 2`, all strings; wrapper
   `channel = 1`, `symbol = 3`, `sendTime = 6`. Also record whether `symbol` comes before or after
   the body on the wire.
2. **Version continuity.** Across consecutive pushes, is `fromVersion == prev toVersion + 1` exact,
   or do ranges overlap/skip the way futures `begin`/`end` did? How does a snapshot's
   `lastUpdateId` land relative to push ranges? Does a push with `toVersion == lastUpdateId` need
   keeping (the `<` vs `<=` question, progress doc decision 8)?
3. **Snapshot `limit`.** For the price filter (`price-filter-threshold`, 0.1), which `limit` covers
   ±10% of mid on liquid and illiquid pairs? Response size and latency for 1000 vs 5000.
4. **REST budget.** Does a 403 storm on `api.mexc.com` also block `contract.mexc.com`? If it does,
   the spot and futures fetchers need a shared per-exchange budget (progress doc §11). Be gentle:
   stop at the first rejection.
5. **Control frames.** Exact text of the subscribe ack (success and partial rejection), the PING
   reply, and what happens to an idle subscription (illiquid pair) with and without PING.
6. **Universe size.** How many spot pairs survive spot ∩ futures; any base-asset naming mismatches
   between the two APIs (e.g. a futures base that differs from the spot base for the same coin).

**Output**: `external-docs/mexc/mexc-spot-depth-empirical.md`, plus a few captured binary frames and
one snapshot body stored as test fixtures under `src/test/resources/mexc/spot/`.

## Phase 1: Binary seam in core

No venue uses it yet; mergeable on its own.

| File | Change |
|---|---|
| `core/ingress/DepthEvent` | add `public ByteBuffer rawBytes`; `clear()` nulls it |
| `core/ingress/DepthEventPublisher` | add `publishFrame(int instrumentId, ByteBuffer payload)` |
| `core/ingress/DisruptorDepthEventPublisher` | implement it (`WS_MSG`, sets `rawBytes`, `rawJson = null`) |
| `spi/StreamProtocol` | add `default int route(ByteBuffer frame, SubscriptionIndex index) { return IGNORED; }`. Documented as hot path, absolute reads only |
| `core/stream/StreamConnection` | `onMessage(ByteBuffer)` routes through the protocol and publishes; an `IGNORED` binary frame keeps the existing rate-limited warning, `UNKNOWN` goes through `noteUnknownFrame` |
| `core/stream/SubscriptionIndex` | add `resolve(ByteBuffer buf, int start, int end)`: decode the range as **UTF-8** to a `String` and look it up (same allocation as today's `substring`). Not ASCII: five MEXC spot symbols are CJK (`龙虾USDT`, `币安人生USDT`, …) |

Tests: binary routing and publishing in `StreamConnection` tests; `SubscriptionIndex` byte resolve
(including a multi-byte UTF-8 symbol);
a test that pins decision 5 (two consecutive binary frames delivered by java-websocket are distinct
buffers). Update the javadoc on `EventType.REST_FAILED` and `StreamConnection.onMessage(ByteBuffer)`.

## Phase 2: Wire reader and sync strategy

Unit-tested against the Phase 0 fixtures, not wired into Spring yet.

- **`MexcSpotFrameReader`** (`adapter/mexc/`), static and allocation-free:
  - varint and tag reading, skipping unknown fields by wire type;
  - `routingKey range`: walk the wrapper's top-level tags to `symbol` (or the suffix of `channel`
    after the last `@`), return its byte range for `SubscriptionIndex`;
  - `fromVersion` / `toVersion`: hand-parse the ASCII digits;
  - level walk: for each `asks` / `bids` item, `JavaDoubleParser.parseDouble(byte[], off, len)` on
    the price and quantity strings, then `book.applyLevel(...)`. Heap buffers only (`hasArray()`),
    with `arrayOffset()` respected.
  - A missing body field or a malformed varint throws, like `readVersionField` does: the format is
    external and undocumented in places, so drift must be loud.
- **`MexcSpotSyncStrategy`** + **`MexcSpotSyncContext`**: same shape as `MexcFuturesSyncStrategy`
  (flat dispatch, single `recover`, buffering only while `RECOVERING`, `REST_FAILED` handling per the
  `DepthSyncStrategy` contract). `fromVersion`/`toVersion` map to `begin`/`end`, `lastUpdateId` to
  `version`. The range check is the same predicate, so extract it as a shared static helper used by
  both MEXC strategies; adjust if Phase 0 finds a different rule. The context buffers `ByteBuffer`s.
- **Snapshot parse**: Jackson streaming over the JSON body, modelled on the Binance spot snapshot
  path; `lastUpdateId` is the sync point.

Tests: `MexcSpotFrameReaderTest` (fixtures + hand-built edge cases: unknown fields, empty side,
zero quantity), `MexcSpotSyncStrategyTest` mirroring `MexcFuturesSyncStrategyTest`.

## Phase 3: Wiring

Off by default, behind the existing `MEXC_ENABLED` gating.

- **`Venue.MEXC_SPOT`** (`Exchange.MEXC`, `Market.SPOT`). Remove the "deliberately absent" comments
  in `Venue` and `MexcAdapterConfig`.
- **`MexcSpotRestClient`**: `GET /api/v3/exchangeInfo` and `GET /api/v3/depth`. A separate client,
  since spot is a different API (no envelope, different host, `ETHUSDT` not `ETH_USDT`; decision 20
  in the progress doc). 403 (Akamai HTML) and 429 handling as found in
  `mexc-api-rate-limits-empirical.md`. Pass `symbol` as a URI template variable, as
  `BinanceRestClient.depth` does, so `WebClient` percent-encodes CJK symbols.
- **Discovery**: `MexcInstrumentSource` serves both venues, like `BinanceInstrumentSource`. Spot
  eligibility per decision 1: spot `baseAsset` matched against the `baseCoinName` set of eligible
  futures contracts (after TradFi filtering). Add `baseCoinName` to `MexcContractDto`; a contract
  with a null `baseCoinName` matches no spot pair. Futures candidates are unchanged (`base =
  baseCoin`). Spot instruments take `nativeSymbol`, `base` and `quote` from the spot row, so
  `TRUMPUSDT` on spot sits next to `TRUMPOFFICIALUSDT` on futures; that is accepted.
  `screener.discovery.excluded-symbols` applies as usual.
- **`MexcSpotStreamProtocol`** on `wss://wbs-api.mexc.com/ws`:
  - subscribe `{"method":"SUBSCRIPTION","params":["spot@public.aggre.depth.v3.api.pb@100ms@<SYMBOL>",…]}`;
  - `route(ByteBuffer)` via `MexcSpotFrameReader`; `route(String)` handles text control frames only
    and returns `IGNORED`;
  - an ack whose `msg` contains `Not Subscribed successfully! [` with a non-empty list is logged at
    WARN, since `code` is 0 even then;
  - heartbeat `TextPing` `{"method":"PING"}`.
  - Constructor validation as in the futures protocol: `subscribe-chunk-size ≤ 30`.
- **Recovery**: a `mexcSpotSnapshotQueue` built by `SnapshotQueueFactory` with a spot
  `MexcSnapshotFetcher` (fixed pacing, ~10 req/s or lower per Phase 0). If Phase 0 shows a shared
  block across hosts, pace both fetchers from one budget instead.
- **YAML**: `screener.exchanges.mexc.venues.spot.*`: stream URL, topic, `max-streams-per-connection:
  30`, `subscribe-chunk-size: 30`, heartbeat interval, REST block, snapshot `limit` and pacing,
  `max-visible-distance`.

Tests: `MexcSpotStreamProtocolTest`, `MexcInstrumentSourceTest` (spot ∩ futures on `baseCoinName`
with futures `base` still `baseCoin`, TradFi inheritance), `MexcSpotRestClientTest`, `MexcAdapterConfigTest` (both venues bound).

## Phase 4: Live verification and enablement

- Run locally with spot enabled: time to `SYNCED`, number of books held, steady-state resync rate,
  snapshot rejections. The bar is the same as futures: books hold `SYNCED` with near-zero resyncs
  over a 25–40 min run.
- Check that the five CJK pairs subscribe and sync. Phase 0 did not test a CJK channel; a rejection
  shows up as the ack WARN.
- Watch connection drops (the documented 24h spot connection lifetime) and how books behave across
  a reconnect, given the known "no reset lane" and "no staleness watchdog" gaps.
- Feed check: is the MEXC spot panel as noisy as futures was? Decide whether the MEXC noise filter
  (`.claude/plans/mexc-noise-reduction/`) applies per exchange or per venue.
- Update `multi-exchange-progress.md` (status table, §11 binary frames now exercised, the adapter
  section) and `CLAUDE.md`'s module map.

## Risks

- **Undocumented or misdocumented wire details** (field numbers, version rule). Mitigated by Phase 0
  and loud failures in the reader.
- **Buffer ownership** (decision 5) is a java-websocket implementation detail. A library upgrade
  that reuses buffers would corrupt books silently; the Phase 1 test guards it.
- **Silent subscription rejection** on spot (`code: 0`). The ack check logs it; counting acks
  against subscriptions belongs to the health surface (§12) and is out of scope here.
