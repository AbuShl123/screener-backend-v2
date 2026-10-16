# MEXC API & WebSocket Contracts — Tickers + Local Orderbook (Spot & Futures)

Scope: USDT-quoted pairs only (e.g. `ETHUSDT` spot, `ETH_USDT` futures). Verified against the official docs on 2026-07-29:

- Spot: https://www.mexc.com/api-docs/spot-v3/
- Futures: https://www.mexc.com/api-docs/futures/

---

## 1. Base endpoints

| | Spot | Futures |
|---|---|---|
| REST base | `https://api.mexc.com` | `https://api.mexc.com` (legacy `https://contract.mexc.com` still works but docs now point to `api.mexc.com`) |
| WebSocket base | `wss://wbs-api.mexc.com/ws` | `wss://contract.mexc.com/edge` |
| WS payload format | **Protobuf** (market streams migrated off plain JSON) | Plain JSON |
| Max WS connection lifetime | 24h (must reconnect) | not explicitly time-boxed, but disconnects if idle (see keepalive below) |
| Keepalive | send `{"method":"PING"}`, expect `{"msg":"PONG"}`; server drops connections with **no subscription** after 30s, or a subscription with **no data flow** after 60s | send `{"method":"ping"}` every 10–20s, expect `{"channel":"pong","data":...}`; server disconnects if no ping within 60s |
| Max subscriptions per connection | 30 (empirically confirmed, hard-enforced — see `mexc-ws-limits-empirical.md`) | not documented; empirically **no limit** — all 1106 USDT contracts fit on one connection (see `mexc-ws-limits-empirical.md`) |

---

## 2. Fetching the list of USDT tickers

### 2.1 Spot

**Step A — get the symbol universe** (so you can filter to `quoteAsset == "USDT"` and confirm the pair is actually tradable):

```
GET https://api.mexc.com/api/v3/exchangeInfo
```
- Weight: 25
- Optional filters: `?symbol=ETHUSDT` or `?symbols=ETHUSDT,BTCUSDT`
- Relevant response fields per symbol: `symbol`, `baseAsset`, `quoteAsset`, `status` (`"1"`=online, `"2"`=paused, `"3"`=offline), `isSpotTradingAllowed`.
- Filter logic: `quoteAsset === "USDT" && status === "1" && isSpotTradingAllowed === true`.

**Step B — get live 24h ticker stats** (price, volume, bid/ask, etc.) for all symbols in one call:

```
GET https://api.mexc.com/api/v3/ticker/24hr
```
- Weight: 25
- No `symbol` param ⇒ returns a JSON **array** of ticker objects for every symbol.
- Key fields: `symbol`, `lastPrice`, `bidPrice`, `askPrice`, `volume`, `quoteVolume`, `priceChangePercent`, `openTime`, `closeTime`.
- Filter the array by `symbol.endsWith("USDT")` (or better, cross-check against the USDT set from `exchangeInfo`, since `endsWith` alone would also match e.g. base assets literally named `...USDT`).

There's also a lighter `GET /api/v3/defaultSymbols` (no auth) that MEXC's own FAQ points to for "which pairs does the API support" — useful as a cheap sanity check, but `exchangeInfo` is the authoritative source for per-symbol trading status/precision.

### 2.2 Futures

**Step A — get the contract universe:**

```
GET https://api.mexc.com/api/v1/contract/detail
```
(MEXC's official FAQ and multiple SDKs use `/api/v1/contract/detail`; the current docs page for "Get Contract Info" renders the path as `/api/v1/contract/detail/country`, which looks like a docs artifact — `/api/v1/contract/detail` is the one actually used in production integrations. Verify by calling it before hard-coding into the screener.)
- Optional: `?symbol=BTC_USDT` for a single contract.
- Relevant fields: `symbol` (format `BASE_QUOTE`, e.g. `ETH_USDT`), `baseCoin`, `quoteCoin`, `futureType` (1=perpetual, 2=delivery), `state` (0=enabled, 1=delivery, 2=delivered, 3=offline, 4=paused), `apiAllowed`.
- Filter logic: `quoteCoin === "USDT" && futureType === 1 && state === 0 && apiAllowed === true` (add the perpetual filter since MEXC also lists delivery/quarterly contracts).

**Step B — get live ticker stats:**

```
GET https://api.mexc.com/api/v1/contract/ticker
```
- Rate limit: 10 req / 2s
- No `symbol` param ⇒ returns `data` as a JSON **array** of ticker objects for every contract (confirmed against production responses; the docs example only shows a single-object sample because it doesn't display the "no symbol" case clearly).
- Key fields: `symbol`, `lastPrice`, `bid1`, `ask1`, `volume24`, `amount24`, `holdVol` (open interest), `riseFallRate`, `fundingRate`, `indexPrice`, `fairPrice`.
- Filter by `symbol.endsWith("_USDT")`, cross-checked against the perpetual/enabled set from Step A.

**Symbol format gotcha:** spot uses concatenated symbols (`ETHUSDT`), futures uses underscore-separated (`ETH_USDT`). Don't reuse one symbol map for both.

---

## 3. Maintaining a local live orderbook

Both markets use the same general pattern — REST snapshot + WS incremental diffs, reconciled by a monotonically increasing version number — but the concrete mechanics differ.

### 3.1 Spot — Diff.Depth Stream

**WebSocket channel:**
```
spot@public.aggre.depth.v3.api.pb@(100ms|10ms)@<symbol>
```
Subscribe message:
```json
{
  "method": "SUBSCRIPTION",
  "params": ["spot@public.aggre.depth.v3.api.pb@100ms@ETHUSDT"]
}
```
Unsubscribe: same shape with `"method": "UNSUBSCRIPTION"`.

**⚠️ Protobuf, not JSON.** Since a recent docs revision, spot market-data pushes (deals, depth, k-line, book ticker, mini-ticker, etc.) are all encoded with Protocol Buffers, not plain JSON. You need to:
1. Pull the `.proto` definitions from https://github.com/mexcdevelop/websocket-proto
2. Compile them with `protoc` for your target language (Java, Python, Go, JS, etc. all supported)
3. Deserialize each push against `PushDataV3ApiWrapper`, then read the `publicincreasedepths` sub-message for depth updates.

Decoded payload shape (shown here as JSON for readability — this is what the protobuf message maps to):
```json
{
  "channel": "spot@public.aggre.depth.v3.api.pb@100ms@ETHUSDT",
  "publicincreasedepths": {
    "asksList": [{ "price": "3450.12", "quantity": "0.0000" }],
    "bidsList": [{ "price": "3449.98", "quantity": "1.2500" }],
    "eventtype": "spot@public.aggre.depth.v3.api.pb@100ms",
    "fromVersion": "10589632359",
    "toVersion": "10589632359"
  },
  "symbol": "ETHUSDT",
  "sendtime": 1736411507002
}
```
- `quantity == "0"` at a price level ⇒ remove that level (order fully canceled/filled).
- Otherwise, upsert `price -> quantity`.

**REST snapshot for initialization:**
```
GET https://api.mexc.com/api/v3/depth?symbol=ETHUSDT&limit=5000
```
Response includes `lastUpdateId`, plus `bids`/`asks` arrays of `[price, quantity]`.

**Official reconciliation algorithm** ("How to Properly Maintain a Local Copy of the Order Book" — this is MEXC's own recommended procedure, reproduced faithfully below):

1. Open the WS connection and subscribe to `spot@public.aggre.depth.v3.api.pb@(100ms|10ms)@<symbol>`.
2. Start buffering incoming incremental updates; note the `fromVersion` of the very first buffered update.
3. Call the REST snapshot (`/api/v3/depth?...&limit=5000`) and record `lastUpdateId`.
4. If `lastUpdateId < fromVersion` of the first buffered update, the snapshot is stale relative to what you've already buffered — re-fetch the snapshot (go back to step 3).
5. Discard every buffered update whose `toVersion <= lastUpdateId` (already represented in the snapshot).
6. Take the first remaining buffered update. If its `fromVersion > lastUpdateId + 1`, there's a gap — reinitialize from step 3.
7. Otherwise: seed the local book from the snapshot, set local version = `lastUpdateId`, then apply the remaining buffered updates (and all subsequent live updates) in order. After each applied update, set local version = that update's `toVersion`. If at any point an incoming update doesn't satisfy `fromVersion == previous toVersion + 1`, the book is out of sync — reinitialize from step 3.

**Caveat MEXC calls out explicitly:** the snapshot is capped at 5000 levels, so a price level that sits outside the snapshot and never changes afterward will never be pushed via the incremental stream, meaning your local book can drift slightly from the true book at the tail end. For most trading/screener purposes this is negligible.

If you don't want to deal with reconciling diffs yourself, MEXC also offers `spot@public.limit.depth.v3.api.pb@<symbol>@<levels>` ("Partial Book Depth Streams") which pushes a ready-made top-N snapshot on every update instead of incremental deltas — simpler, but less precise/lower levels than a fully reconciled book.

### 3.2 Futures — push.depth incremental stream

**WebSocket subscribe (plain JSON, unlike spot):**
```json
{
  "method": "sub.depth",
  "param": { "symbol": "ETH_USDT" }
}
```
Unsubscribe:
```json
{
  "method": "unsub.depth",
  "param": { "symbol": "ETH_USDT" }
}
```
Push shape (delivered ~every 200ms after subscribing):
```json
{
  "channel": "push.depth",
  "data": {
    "asks": [[3450.1, 3251, 1]],
    "bids": [],
    "version": 96801927
  },
  "symbol": "ETH_USDT",
  "ts": 1587442022003
}
```
Each depth row is `[price, quantity, orderCount]`. `quantity` is an **absolute** value for that price level (not a delta) — `quantity == 0` means the level should be removed.

**REST snapshot for initialization:**
```
GET https://api.mexc.com/api/v1/contract/depth/{symbol}?limit=1000
```
Response includes `version`, `timestamp`, and `asks`/`bids` as `[price, quantity, orderCount]` arrays (same triplet shape as the WS push).

**Gap-recovery endpoint** (unique to futures — spot has no equivalent, it just re-snapshots):
```
GET https://api.mexc.com/api/v1/contract/depth_commits/{symbol}/1000
```
Returns the latest 1000 incremental depth commits, ascending by version — lets you patch a gap without a full snapshot re-fetch.

**Official reconciliation algorithm** ("Incremental Order Book Maintenance Mechanism"):

1. **Initialize**: call `GET /api/v1/contract/depth/{symbol}?limit=1000`, store its `version` as `localLastVersion`, seed the local book from `asks`/`bids`.
2. **Subscribe** to `push.depth` over the WS connection.
3. **Apply live updates**: for each incoming `push.depth` message —
   - if `version == localLastVersion + 1`, apply directly (upsert or delete by `quantity`) and set `localLastVersion = version`.
   - if `version > localLastVersion + 1` (later than expected), you can cache it temporarily since a higher version always wins for a given price level, but this signals a possible gap.
4. **On detected gap** (version sequence discontinuous): call `GET /api/v1/contract/depth_commits/{symbol}/1000` to fetch the last 1000 commits (ascending version).
5. **Replay commits**: skip any commit with `version <= localLastVersion`; starting from the first commit where `version == localLastVersion + 1`, apply sequentially (upsert if `quantity > 0`, delete if `quantity == 0`), updating `localLastVersion` after each. Then re-apply any cached WS updates whose version is still higher.
6. **Resume real-time**: keep consuming live `push.depth` events, each expected to have `version == previous version + 1`. Any further discontinuity → repeat from step 4, or if repeated gaps keep occurring, fall back to a full reinit at step 1.

---

## 4. Quick comparison table

| | Spot | Futures |
|---|---|---|
| Symbol format | `ETHUSDT` | `ETH_USDT` |
| Symbol/contract list endpoint | `GET /api/v3/exchangeInfo` | `GET /api/v1/contract/detail` |
| Ticker list endpoint | `GET /api/v3/ticker/24hr` (no `symbol` → array, all pairs) | `GET /api/v1/contract/ticker` (no `symbol` → array, all contracts) |
| Orderbook snapshot (REST) | `GET /api/v3/depth?symbol=...&limit=5000` → `lastUpdateId` | `GET /api/v1/contract/depth/{symbol}?limit=1000` → `version` |
| Depth WS channel | `spot@public.aggre.depth.v3.api.pb@(100ms\|10ms)@<symbol>` | `sub.depth` / `push.depth` |
| WS message encoding | **Protobuf** | Plain JSON |
| Version fields | `fromVersion` / `toVersion` per update | single `version`, expected to increment by exactly 1 |
| Gap recovery | re-fetch REST snapshot, replay buffered diffs (steps 3–7 above) | `GET /api/v1/contract/depth_commits/{symbol}/1000` to patch without a full re-snapshot |
| Zero-quantity semantics | remove that price level | remove that price level |
| Keepalive | `{"method":"PING"}` → `PONG` in payload | `{"method":"ping"}` every 10–20s → `push.pong`-style `{"channel":"pong",...}` |

---

## 5. Sources

- Spot introduction / rate limits: https://www.mexc.com/api-docs/spot-v3/introduction
- Spot Exchange Information: https://www.mexc.com/api-docs/spot-v3/market-data-endpoints/exchange-information
- Spot 24hr Ticker: https://www.mexc.com/api-docs/spot-v3/market-data-endpoints/api-24hr-ticker-price-change-statistics
- Spot Order Book (REST): https://www.mexc.com/api-docs/spot-v3/market-data-endpoints/order-book
- Spot Websocket Market Streams overview: https://www.mexc.com/api-docs/spot-v3/websocket-market-streams/
- Spot Diff.Depth Stream: https://www.mexc.com/api-docs/spot-v3/websocket-market-streams/diffdepth-stream
- Spot Partial Book Depth Streams: https://www.mexc.com/api-docs/spot-v3/websocket-market-streams/partial-book-depth-streams
- Spot Protobuf integration guide: https://www.mexc.com/api-docs/spot-v3/websocket-market-streams/protocol-buffers-integration
- Spot "How to Properly Maintain a Local Copy of the Order Book": https://www.mexc.com/api-docs/spot-v3/websocket-market-streams/how-to-properly-maintain-a-local-copy-of-the-order-book
- Websocket proto definitions repo: https://github.com/mexcdevelop/websocket-proto
- Futures Get Contract Info: https://www.mexc.com/api-docs/futures/market-endpoints/get-contract-info
- Futures Get Ticker: https://www.mexc.com/api-docs/futures/market-endpoints/get-ticker-contract-market-data
- Futures Get Contract Order Book Depth: https://www.mexc.com/api-docs/futures/market-endpoints/get-contract-order-book-depth
- Futures WebSocket API overview / native endpoint: https://www.mexc.com/api-docs/futures/websocket-api/
- Futures Incremental Order Book Maintenance Mechanism: https://www.mexc.com/api-docs/futures/websocket-api/incremental-order-book-maintenance-mechanism
- MEXC API FAQ (default symbol endpoints): https://www.mexc.com/mexc-api

---

## 6. Notes for the Screener integration

- Your existing Binance pipeline seeds books from a REST snapshot + `@depth@1000ms` diff stream with a `TreeMap`/version-gated apply loop — the spot MEXC flow above is structurally the same pattern (snapshot `lastUpdateId` + `fromVersion`/`toVersion` gating), so the existing `SyncState` buffering approach should port over with minimal changes. The futures MEXC flow is *closer to Binance futures* than to MEXC spot (single incrementing `version` counter, delta reconciliation via a dedicated backfill endpoint instead of a full re-snapshot).
- Biggest net-new integration cost is the **protobuf decoding** on the spot side — nothing in your current Binance/Java stack needs that today, so budget for adding `protobuf-java` and generating stubs from `mexcdevelop/websocket-proto`.
- Remember the symbol-format split (`ETHUSDT` vs `ETH_USDT`) when building your internal symbol-routing/sharding keys — don't assume one canonical string works for both markets.
