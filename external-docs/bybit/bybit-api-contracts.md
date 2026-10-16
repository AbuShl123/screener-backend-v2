# Bybit API & WebSocket Contracts — Instruments + Local Orderbook (Spot & Linear)

Reference for Bybit's **v5** public REST and WebSocket surface: how to enumerate tradable
instruments, and how to build and maintain a synchronised local order book.

**Verified on 2026-08-02** against production (`api.bybit.com` / `stream.bybit.com`) plus the
official docs:

- Intro / base URLs — <https://bybit-exchange.github.io/docs/v5/intro>, <https://bybit-exchange.github.io/docs/v5/guide>
- WS connect — <https://bybit-exchange.github.io/docs/v5/ws/connect>
- WS public orderbook — <https://bybit-exchange.github.io/docs/v5/websocket/public/orderbook>
- Instruments info — <https://bybit-exchange.github.io/docs/v5/market/instrument>
- REST orderbook — <https://bybit-exchange.github.io/docs/v5/market/orderbook>
- Rate limits — <https://bybit-exchange.github.io/docs/v5/rate-limit>

Everything below is **public** — no API key, no signing, no timestamp header. Sections marked
**[measured]** were confirmed empirically; where measurement contradicts or refines the docs it is
called out explicitly.

---

## 1. Product taxonomy

Bybit v5 unifies all products behind a single `category` discriminator. It appears both as a REST
query parameter and, implicitly, as the WebSocket path segment.

| `category` | Contents | WS path | Notes |
|---|---|---|---|
| `spot` | Spot pairs | `/v5/public/spot` | 566 pairs live, 419 USDT-quoted **[measured]** |
| `linear` | USDT/USDC perpetuals **and** dated futures | `/v5/public/linear` | 780 instruments: 744 `LinearPerpetual`, 36 `LinearFutures` **[measured]** |
| `inverse` | Coin-margined perps/futures (`BTCUSD`) | `/v5/public/inverse` | Quantity denominated in the base asset |
| `option` | Options | `/v5/public/option` | Different depth levels (25/100 only) |
| `spread` | Spread trading | `/v5/public/spread` | — |

`linear` is a single stream endpoint carrying both perpetuals and dated futures — the split is
visible only via `contractType` in `instruments-info`, never in the stream itself.

---

## 2. Endpoints and domains

### 2.1 Global (recommended)

| | URL |
|---|---|
| REST mainnet | `https://api.bybit.com` |
| REST mainnet (backup) | `https://api.bytick.com` |
| REST testnet | `https://api-testnet.bybit.com` |
| WS spot | `wss://stream.bybit.com/v5/public/spot` |
| WS linear | `wss://stream.bybit.com/v5/public/linear` |
| WS inverse | `wss://stream.bybit.com/v5/public/inverse` |
| WS option | `wss://stream.bybit.com/v5/public/option` |
| WS testnet | `wss://stream-testnet.bybit.com/v5/public/{category}` |

### 2.2 Regional domains

Bybit operates region-specific hostnames. For **public market data** these behave as mirrors of the
global platform — same instrument universe, same message shapes.

| Region | REST | WS |
|---|---|---|
| Kazakhstan | `https://api.bybit.kz` | `wss://stream.bybit.kz` |
| Netherlands | `https://api.bybit.nl` | — |
| Turkey | `https://api.bybit.tr` | `wss://stream.bybit.tr` |
| Georgia | `https://api.bybitgeorgia.ge` | `wss://stream.bybitgeorgia.ge` |
| Indonesia | `https://api.bybit.id` | `wss://stream.bybit.id` |
| UAE | `https://api.bybit.ae` | — |
| EEA | `https://api.bybit.eu` | — |
| **Japan** | `https://api.manepa.jp` | `wss://stream.manepa.jp` |

**[measured]** `api.bybit.com`, `api.bytick.com`, `api.bybit.kz`, `api.bybit.nl` and `api.bybit.tr`
all return the identical 780-instrument `linear` universe and identical `spot` payloads.
`wss://stream.bybit.kz/v5/public/linear` subscribes and streams identically to
`wss://stream.bybit.com`.

### 2.3 The Japan domain is **not** a mirror — do not use it for market data

**[measured]** `manepa.jp` is Bybit Japan, a separate registered entity with its own book:

- `GET https://api.manepa.jp/v5/market/instruments-info?category=spot` → **5 instruments**
  (`ETHBTC`, `XRPBTC`, `XLMBTC`, `LTCBTC`, `XRPJPY`). `symbol=BTCUSDT` returns an **empty list**
  with `retCode: 0` — a silent empty result, not an error.
- `category=linear` returns the same 780 as global.
- `wss://stream.manepa.jp/v5/public/linear` **fails the WebSocket handshake** (non-101 response);
  no public v5 stream is served there.

Substituting the Japan domain therefore silently deletes ~99% of the spot universe and breaks the
stream entirely. The documented "Japan users use `manepa.jp`" guidance concerns *account holders* on
the Japanese platform, not public market-data consumers.

### 2.4 Geographic blocking

The docs state that IPs in the **United States** and **Mainland China** receive `403 Forbidden`.
No other region is documented as blocked for public endpoints, and Japan is not on that list.

---

## 3. REST API

### 3.1 Response envelope

Every v5 REST response — success or failure — uses the same wrapper:

```json
{
  "retCode": 0,
  "retMsg": "OK",
  "result": { },
  "retExtInfo": {},
  "time": 1785668326050
}
```

| Field | Meaning |
|---|---|
| `retCode` | `0` = success. Non-zero = business error |
| `retMsg` | `"OK"` or a human-readable error |
| `result` | Payload. **Present and shaped even on error** (empty `list`) |
| `retExtInfo` | Extended error detail; usually `{}` |
| `time` | Server timestamp, ms |

Error responses still return **HTTP 200** — the `retCode` is the authority, not the status code:

```json
{"retCode": 10001, "retMsg": "params error: symbol invalid",
 "result": {"category": "", "list": [], "nextPageCursor": ""},
 "retExtInfo": {}, "time": 1785668378332}
```

Selected codes: `10001` bad params · `10006` rate limit exceeded ("Too many visits!") · `10018`
IP rate limit · `403` (HTTP) access too frequent or geo-blocked.

**[measured]** Response headers on public market endpoints:
`Ret_code`, `Traceid`, `Timenow` (ms), plus CloudFront headers. The `X-Bapi-Limit*` headers
documented for private endpoints are **not present** on public market endpoints — there is no
server-side budget feedback to read; any pacing must be tracked locally.

### 3.2 Instrument discovery — `GET /v5/market/instruments-info`

This is the answer to "how do I enumerate active spot and futures tickers". Bybit has no
Binance-style `exchangeInfo`; this endpoint plays that role.

```
GET https://api.bybit.com/v5/market/instruments-info?category=spot
GET https://api.bybit.com/v5/market/instruments-info?category=linear&limit=1000
```

| Param | Req | Values |
|---|---|---|
| `category` | **yes** | `spot` \| `linear` \| `inverse` \| `option` |
| `symbol` | no | Uppercase, e.g. `BTCUSDT`. Invalid symbol ⇒ `retCode 10001` |
| `status` | no | `Trading`, `PreLaunch`, `Delivering`, `Closed` |
| `baseCoin` | no | `linear`/`inverse`/`option` only |
| `limit` | no | 1–1000, default 500 |
| `cursor` | no | Value of `nextPageCursor` from the previous page |

#### Pagination behaviour differs by category **[measured]**

| | `spot` | `linear` |
|---|---|---|
| `limit` honoured | **No** — ignored | Yes |
| `nextPageCursor` in response | **Field absent entirely** | Present (`""` when exhausted) |
| Full universe in one call | Always (566 rows) | Only with `limit=1000` (780 rows) |

For `spot`, `limit=100` still returns all 566 rows and the response object has no
`nextPageCursor` key at all — a client that unconditionally reads that field must tolerate its
absence. For `linear`, `limit=100` returns exactly 100 rows plus
`nextPageCursor: "first%3D0GUSDT%26last%3DAXSUSDT"` (URL-encoded `first=…&last=…`), which is passed
back verbatim as `cursor`. `limit=1000` returns the whole 780 in one page with `nextPageCursor: ""`.

#### Default status filter **[measured]**

Omitting `status` returns **only `Trading`** instruments — 566 spot / 780 linear, 100% `Trading`.
Pre-listing instruments must be requested explicitly:
`?category=linear&status=PreLaunch` → 3 rows. So the plain call is already the "active instruments"
query; no client-side status filter is required, though asserting `status == "Trading"` is cheap
insurance.

#### `linear` response row

```json
{
  "symbol": "0GUSDT",
  "contractType": "LinearPerpetual",
  "status": "Trading",
  "baseCoin": "0G",
  "quoteCoin": "USDT",
  "launchTime": "1758175736000",
  "deliveryTime": "0",
  "priceScale": "4",
  "leverageFilter": { "minLeverage": "1", "maxLeverage": "50.00", "leverageStep": "0.01" },
  "priceFilter": { "minPrice": "0.0001", "maxPrice": "1999.9998", "tickSize": "0.0001" },
  "lotSizeFilter": {
    "maxOrderQty": "350000.0", "minOrderQty": "0.1", "qtyStep": "0.1",
    "postOnlyMaxOrderQty": "350000.0", "maxMktOrderQty": "70000.0", "minNotionalValue": "5"
  },
  "unifiedMarginTrade": true,
  "fundingInterval": 240,
  "settleCoin": "USDT",
  "isPreListing": false,
  "riskParameters": { "priceLimitRatioX": "0.15", "priceLimitRatioY": "0.3" },
  "symbolId": 2449
}
```

Key fields: `symbol` (native, no separator), `contractType` (`LinearPerpetual` |
`LinearFutures`), `status`, `baseCoin`, `quoteCoin`, `settleCoin`, `priceFilter.tickSize`,
`lotSizeFilter.qtyStep` / `minOrderQty`, `launchTime` (ms string), `symbolId` (integer, stable per
symbol but exchange-assigned and **not dense**).

#### `spot` response row

```json
{
  "symbolId": 9,
  "symbol": "BTCUSDT",
  "baseCoin": "BTC",
  "quoteCoin": "USDT",
  "innovation": "0",
  "status": "Trading",
  "marginTrading": "utaOnly",
  "stTag": "0",
  "lotSizeFilter": {
    "basePrecision": "0.000001", "quotePrecision": "0.0000001",
    "minOrderQty": "0.000001", "maxOrderQty": "230",
    "minOrderAmt": "5", "maxOrderAmt": "8000000",
    "maxLimitOrderQty": "230", "maxMarketOrderQty": "120",
    "postOnlyMaxLimitOrderSize": "1150"
  },
  "priceFilter": { "tickSize": "0.1" },
  "riskParameters": { "priceLimitRatioX": "0.005", "priceLimitRatioY": "0.01" },
  "symbolType": "",
  "xstockMultiplier": "1"
}
```

Note the asymmetry: spot has **no** `contractType`, `launchTime` or `settleCoin`, and uses
`basePrecision`/`quotePrecision` where linear uses `qtyStep`. `stTag: "1"` marks a Special Treatment
(risk-flagged) pair; `innovation: "1"` marks the Innovation Zone.

#### Symbol format

Native symbols are **concatenated, uppercase, no separator**, identical across spot and linear —
`BTCUSDT` is `BTCUSDT` in both. This differs from venues that use `BTC_USDT` or product suffixes,
and means the same string is a valid topic component on either endpoint. Multiplier-prefixed
symbols are common on linear (`1000BONKUSDT`, `1000000BABYDOGEUSDT`, `10000SATSUSDT`) — the prefix
is part of the symbol, and the corresponding spot pair (`BONKUSDT`) is a *different* string.

`quoteCoin` distribution **[measured]**: spot — USDT 419, USDC 86, EUR 18, BTC 7, BRL 5, GBP 5;
linear — USDT 712, USDC 68.

### 3.3 Ticker statistics — `GET /v5/market/tickers`

One call returns every instrument in the category. Useful for volume/liquidity filtering.

```
GET https://api.bybit.com/v5/market/tickers?category=linear
GET https://api.bybit.com/v5/market/tickers?category=spot
```

Spot row:
```json
{"symbol":"NIGHTUSDT","bid1Price":"0.01914","bid1Size":"3193.6","ask1Price":"0.01915",
 "ask1Size":"2671.2","lastPrice":"0.01915","prevPrice24h":"0.01803","price24hPcnt":"0.0621",
 "highPrice24h":"0.01989","lowPrice24h":"0.01792","turnover24h":"822979.360199",
 "volume24h":"43504868.2","usdIndexPrice":"0.0191341"}
```

Linear rows add `indexPrice`, `markPrice`, `openInterest`, `openInterestValue`, `fundingRate`,
`nextFundingTime`, `fundingIntervalHour`, `deliveryTime`, `basis`, `curPreListingPhase`.

`turnover24h` is quote-denominated notional; `volume24h` is base-denominated.

**[measured]** `tickers?category=linear` returns **783** rows against `instruments-info`'s 780 —
tickers includes pre-listing symbols. Treat `instruments-info` as the authoritative universe and
`tickers` as a statistics join, not a second source of truth.

### 3.4 REST order book snapshot — `GET /v5/market/orderbook`

Not required for the WebSocket sync flow (§5), but available as an independent snapshot source or
cross-check.

```
GET https://api.bybit.com/v5/market/orderbook?category=linear&symbol=BTCUSDT&limit=1000
```

| Param | Req | Notes |
|---|---|---|
| `category` | yes | — |
| `symbol` | **yes** | Single symbol only; no bulk form |
| `limit` | no | spot 1–1000 (default 1); linear/inverse 1–1000 (default 25); option 1–25 |

```json
{"retCode":0,"retMsg":"OK",
 "result":{"s":"BTCUSDT","a":[["65557.7","16.606555"]],"b":[["65485.47","47.081829"]],
           "ts":1716863719031,"u":230704,"seq":1432604333,"cts":1716863718905},
 "retExtInfo":{},"time":1716863719382}
```

`b` descending by price, `a` ascending, both `[price, size]` string pairs. `u` and `seq` carry the
same meaning as in the stream (§5.4).

**[measured]** `limit=500` and `limit=1000` both return the requested depth on linear. There is no
weight cost header and no bulk endpoint, so a full-universe REST snapshot sweep costs one request
per instrument.

### 3.5 Server time — `GET /v5/market/time`

```json
{"retCode":0,"retMsg":"OK",
 "result":{"timeSecond":"1785669240","timeNano":"1785669240990066869"},
 "retExtInfo":{},"time":1785669240990}
```

### 3.6 Rate limits

**Public / IP-scoped:**

- **600 requests per 5-second sliding window per IP**, shared across `api.bybit.com`,
  `api.bytick.com` and the regional hostnames. Exceeding it returns HTTP `403 access too frequent`
  and triggers a **10-minute ban**.
- WebSocket: **max 500 new connections per 5 minutes per domain**; up to **1000 concurrent
  connections per IP**, counted **separately per market type** (spot / linear / inverse / option).
- WebSocket *messages* are not counted against REST rate limits.

**Per-UID limits** apply only to authenticated endpoints and are irrelevant to public market data.

There is no weight system: every request costs 1. Because public responses carry no
`X-Bapi-Limit*` headers (§3.1), consumption must be tracked client-side.

---

## 4. WebSocket protocol

### 4.1 Connection

Plain `wss://`, **text frames, uncompressed JSON** — no gzip/deflate, no protobuf, no binary path.
No authentication for `/v5/public/*`.

### 4.2 Subscribe / unsubscribe

```json
{
  "req_id": "test",
  "op": "subscribe",
  "args": ["orderbook.1000.BTCUSDT", "orderbook.1000.ETHUSDT"]
}
```

```json
{ "req_id": "test", "op": "unsubscribe", "args": ["orderbook.1000.BTCUSDT"] }
```

`req_id` is optional and echoed back verbatim — the only way to correlate an ack with the frame
that caused it.

**Acknowledgement:**

```json
{"success":true,"ret_msg":"subscribe","conn_id":"d9avrremonceh80v85sg-3jz15","req_id":"test","op":"subscribe"}
```

Control frames are distinguishable from data frames by the presence of `op` / `success`; data
frames have `topic` and never carry `op`.

### 4.3 Ack quirks that matter **[measured]**

| Behaviour | `spot` | `linear` |
|---|---|---|
| `ret_msg` on success | `"subscribe"` / `"unsubscribe"` | `""` (empty) |
| Ack ordering vs first data frame | Ack **before** snapshot | Ack sometimes **after** several data frames |
| Duplicate subscribe to an already-subscribed topic | **Silently ignored — no response at all** | `{"success":false,"ret_msg":"error:already subscribed,topic:orderbook.200.BTCUSDT"}` |
| One invalid topic in `args` | Whole frame rejected | Whole frame rejected |

Two consequences:

1. **Never treat the ack as a barrier.** On linear the snapshot can arrive before the ack, so a
   client that ignores data until acked will drop the snapshot.
2. **A subscribe frame is all-or-nothing.** `args` containing one bad topic rejects the entire
   batch — no partial subscription:
   ```json
   {"success":false,"ret_msg":"error:handler not found,topic:orderbook.500.ETHUSDT",
    "conn_id":"…","req_id":"probe-1","op":"subscribe"}
   ```
   In that run, the two *valid* topics in the same frame produced **zero** messages. Batches must be
   built from validated depth levels (§5.1) and known symbols.
3. **Duplicate subscribe never re-delivers a snapshot** and, on spot, never answers at all — so it
   is useless both as a recovery mechanism and as a liveness probe.

### 4.4 Frame size limit **[measured]**

Documented as a 21 000-character limit on the `args` array for public channels. Observed
enforcement:

| Frame | Result |
|---|---|
| 700 topics, 18 464 chars of args (~18.5 KB frame) | Accepted; all 700 snapshots delivered |
| 1 424 topics, 38 254 chars of args (36 872-char frame) | **Connection closed, code `1009`**, empty reason, no error frame |

Exceeding the limit **kills the connection** rather than returning `success:false`. Subscribe
batches must be chunked by serialised length, not just by arg count.

### 4.5 Per-request and per-connection limits

| Limit | Value |
|---|---|
| `args` per subscribe frame — **spot** | **10** (hard). 11 args ⇒ `{"success":false,"ret_msg":"args size >10"}` **[measured]** |
| `args` per subscribe frame — linear/inverse | No count cap observed; 700 accepted in one frame **[measured]** |
| `args` per subscribe frame — option | 2 000 per connection (documented) |
| `args` array total length | 21 000 chars (§4.4) |
| Topics per connection — spot | No cap hit: **566 topics** subscribed over 57 frames of 10, paced 100 ms, all acked **[measured]** |
| Topics per connection — linear | No cap hit: **1 560 topics** over 6 frames of 300 **[measured]** |
| New connections | 500 per 5 min per domain |
| Concurrent connections | 1 000 per IP per market type |

So the binding constraint on spot is the **10-args-per-frame** rule, not a connection capacity
limit: 500 spot instruments needs 50 paced subscribe frames on a single connection.

### 4.6 Heartbeat

Bybit uses an **application-level** ping, not a protocol-level WebSocket ping frame.

```json
{ "req_id": "ping-1", "op": "ping" }
```
```json
{"success":true,"ret_msg":"pong","conn_id":"d9avrremonceh80v85sg-3jz15","req_id":"ping-1","op":"ping"}
```

**[measured]** The pong echoes `op: "ping"` (not `"pong"`) and carries `ret_msg: "pong"` — on both
spot and linear. Matching on `op == "pong"` will never fire.

Docs recommend pinging every **20 seconds**; an idle connection is dropped after **10 minutes**.
The `max_active_time` query parameter (30 s – 600 s) applies to private streams.

**[measured]** A public `linear` connection subscribed to `orderbook.50.BTCUSDT` survived **780 s
(13 min) with no application ping sent at all**, delivering 23 744 messages without interruption —
comfortably past the documented 600 s idle limit. Inbound traffic evidently satisfies the liveness
check. This is an implementation detail, not a contract: a
subscription that stops producing (thin book, delisted symbol) would leave the connection with no
traffic in either direction. Send the 20-second ping regardless; it also doubles as the only
available round-trip liveness probe (§4.3).

### 4.7 Close codes

| Code | Cause |
|---|---|
| `1009` | Frame too large — subscribe args over the size limit **[measured]** |
| `1000`/`1006` | Normal close / abnormal termination; reconnect and resubscribe |

---

## 5. Order book channel

### 5.1 Topic format

```
orderbook.{depth}.{symbol}
```

Depth is **not free-form** — only the exact published values are valid handlers, and an invalid one
rejects the entire subscribe frame (§4.3).

| Category | Valid depths | Push interval |
|---|---|---|
| `spot` | 1, 50, 200, 1000 | 10 ms / 20 ms / 100 ms / 200 ms |
| `linear`, `inverse` | 1, 50, 200, 1000 | 10 ms / 20 ms / 100 ms / 200 ms |
| `option` | 25, 100 | 20 ms / 100 ms |

`orderbook.500.…` is invalid and returns `error:handler not found` **[measured]**.

**Depth is a cap on the maintained book, not a window.** `orderbook.200` means the stream conveys
only the top 200 levels per side; the local book can never be deeper than that. Depth 1000 is the
maximum available on both spot and linear **[measured]**.

The push interval is a *coalescing* interval, not a fixed cadence — the server emits at most one
message per interval and skips intervals with no change. Observed rates are well under nominal
(§7).

### 5.2 Message shape

**Snapshot** (spot, `orderbook.200.BTCUSDT`):

```json
{
  "topic": "orderbook.200.BTCUSDT",
  "ts": 1785668426416,
  "type": "snapshot",
  "data": {
    "s": "BTCUSDT",
    "b": [["63193.5","0.507648"], ["63193.4","0.039571"], "… 200 entries"],
    "a": [["63203.2","0.266763"], "… 200 entries"],
    "u": 57099029,
    "seq": 112250766534
  },
  "cts": 1785668426407
}
```

**Delta** (linear, `orderbook.200.ETHUSDT`):

```json
{
  "topic": "orderbook.200.ETHUSDT",
  "type": "delta",
  "ts": 1785668487831,
  "data": {
    "s": "ETHUSDT",
    "b": [["1867.53","29.07"], ["1867.05","14.99"], ["1865.43","0"]],
    "a": [["1868.01","11.91"], ["1868.30","181.46"]],
    "u": 8593329,
    "seq": 428230705038
  },
  "cts": 1785668487824
}
```

| Field | Type | Meaning |
|---|---|---|
| `topic` | string | Echo of the subscribed topic — the routing key |
| `type` | string | `snapshot` \| `delta` |
| `ts` | number | Server send timestamp, ms |
| `cts` | number | Matching-engine timestamp, ms; correlates with `T` on the public-trade channel |
| `data.s` | string | Symbol |
| `data.b` | array | Bids, `[price, size]` strings, **descending** |
| `data.a` | array | Asks, `[price, size]` strings, **ascending** |
| `data.u` | integer | Update id — the sequence cursor |
| `data.seq` | integer | Cross sequence — global generation order |

**Field order is not stable between categories** **[measured]**: spot emits
`topic, ts, type, data, cts`; linear emits `topic, type, ts, data, cts`. Any parser must be
order-independent.

**Number formatting differs** **[measured]**: linear pads to the instrument's `tickSize` scale
(`"63155.00"`), spot trims (`"63193.5"`). Both are JSON **strings** in both categories — never
numbers. Do not use the string form as a map key; parse to a numeric type.

Both `b` and `a` are always present, possibly as empty arrays — a delta touching only asks still
carries `"b":[]`.

### 5.3 Snapshot delivery — the defining property

**The snapshot arrives in-stream as the first message of a subscription.** There is no REST fetch
in the sync flow, no buffering window, and no "wait for the snapshot to catch up" race.

**[measured]**, on every subscription tested across both categories and depths 50/200/1000:

```
subscribe → type:"snapshot" (full depth) → type:"delta" → type:"delta" → …
```

Over a 70-second run on 6 topics (spot + linear, depths 50/200/1000): **exactly one snapshot per
topic**, zero unsolicited re-snapshots, zero sequence gaps.

### 5.4 `u` and `seq` semantics

**`u` — update id.** Per `(symbol, depth-topic)`, **strictly `+1` per message**.

**[measured]** Across 70 seconds and ~4 900 messages on 6 topics (spot and linear, depths 50/200/1000),
`u` incremented by exactly 1 on every single message — zero gaps. This makes `u` a clean desync
detector: `u != lastU + 1` on a delta ⇒ the book is stale.

Three documented exceptions:

1. **`u` keeps advancing while you are unsubscribed.** It is a server-side per-symbol counter, not
   per-connection. **[measured]** After unsubscribe at `u=8721861` and resubscribe 3 s later, the
   new snapshot carried `u=8721903`. A snapshot therefore **resets** the cursor — never gap-check a
   `snapshot` against the previous `u`.
2. **`u == 1` means the service restarted.** From the docs: *"Occasionally, you'll receive `"u"=1`,
   which is a snapshot data due to the restart of the service. So please overwrite your local
   orderbook."* Treat it as an unconditional full replacement regardless of `type`.
3. **Level-1 topics repeat `u`** (see §5.6).

**`seq` — cross sequence.** Global ordering stamp across depth levels of the same symbol: *"for the
smaller `seq`, then it means the data is generated earlier."* It is **not** contiguous — it jumps by
arbitrary amounts — so it cannot be used for gap detection. Its uses are (a) ordering messages for
the same symbol arriving on different depth topics, and (b) discarding an out-of-order frame.
**[measured]** `seq` was monotonically non-decreasing across every topic tested; zero decreases.

### 5.5 Applying a delta

Per the docs, for each `[price, size]` entry in `b` and `a`:

- *"If you receive an amount that is `0`, delete the entry"*
- *"If you receive an amount that does not exist, insert it"*
- *"If the entry exists, you simply update the value"*

The size is an **absolute replacement**, never an increment. Zero-size entries appear routinely in
production deltas — e.g. `["1865.43","0"]` and `["30248.70","0"]` — and dropping the deletion
silently corrupts the book with phantom liquidity.

`type: "snapshot"` is a **full replacement**: clear both sides and rebuild, never merge.

### 5.6 Level-1 topics are snapshot-only

`orderbook.1.{symbol}` never emits `delta`. Every message is `type: "snapshot"` carrying exactly one
bid and one ask.

```json
{"topic":"orderbook.1.ETHUSDT","ts":1785668428304,"type":"snapshot",
 "data":{"s":"ETHUSDT","b":[["1868.43","2.7605"]],"a":[["1868.44","4.89985"]],
         "u":27242390,"seq":178058643518},"cts":1785668428300}
```

Docs: *"Linear, inverse, spot level 1 data has `snapshot` message only"* and *"if 3 seconds have
elapsed without a change in the orderbook, a `snapshot` message will be pushed again, and the field
`u` will be the same as that in the previous message."*

**[measured]** Over 70 s, `orderbook.1.DOGEUSDT` produced 451 messages on linear and 125 on spot —
100% snapshots — with `u` repeating on the 3-second keep-alive resends (e.g. `10654280` observed
three consecutive times). Level 1 therefore has **no desync state**: every frame is authoritative,
and `u` is neither strictly increasing nor gap-checkable.

### 5.7 Excluded liquidity

*"Retail Price Improvement (RPI) orders will not be included in the messages."* RPI liquidity is
invisible in both the WebSocket channel and the REST snapshot, so the local book is by definition
a subset of the true book.

---

## 6. Local order book synchronisation algorithm

Bybit's model is **in-stream snapshot + sequenced deltas**, with **resubscription** as the recovery
action. No REST call participates.

### 6.1 States

```
   subscribe frame sent
            │
            ▼
     ┌──────────────┐   type:"snapshot"    ┌──────────┐
     │ AWAITING_SNAP├─────────────────────►│  SYNCED  │
     └──────┬───────┘   (build book)       └────┬─────┘
            │                                   │
            │ deltas received here              │ delta with u == lastU+1 → apply
            │ MUST be discarded                 │
            │ (nothing to apply them to)        │ delta with u != lastU+1 → DESYNCED
            ▲                                   │ type:"snapshot"        → rebuild, stay SYNCED
            │                                   │ u == 1                 → rebuild, stay SYNCED
            │                                   ▼
            │        unsubscribe + subscribe  ┌──────────┐
            └─────────────────────────────────┤ DESYNCED │
                                              └──────────┘
```

### 6.2 Steady-state loop

1. Connect to `wss://stream.bybit.com/v5/public/{spot|linear}`.
2. Send `{"op":"subscribe","args":[…]}` — ≤10 args on spot, chunked under 21 000 chars everywhere.
3. Discard depth frames until `type == "snapshot"` for that topic. (Do **not** gate on the
   subscribe ack — on linear it can arrive after the snapshot.)
4. On `snapshot`: clear both sides, insert every level, set `lastU = data.u`, `lastSeq = data.seq`,
   mark `SYNCED`.
5. On `delta` while `SYNCED`:
   - if `data.u == 1` → treat as a full snapshot (service restart), rebuild;
   - else if `data.u != lastU + 1` → **desync**, go to §6.3;
   - else apply every `[price, size]` in `b` and `a` (size `0` ⇒ delete), then
     `lastU = data.u`, `lastSeq = data.seq`.
6. On `snapshot` while `SYNCED` (server-initiated re-snapshot) → rebuild in place; do not gap-check.
7. Send `{"op":"ping"}` every 20 s per connection.

Because `u` is strictly `+1`, gap detection is a single integer comparison — there is no
`U`/`u`-range overlap logic and no buffering.

### 6.3 Recovery **[measured]**

The only mechanism that re-delivers a snapshot is **unsubscribe followed by subscribe** on the same
connection:

```
>> {"op":"unsubscribe","args":["orderbook.200.BTCUSDT"]}
   {"success":true,"ret_msg":"","op":"unsubscribe", …}
>> {"op":"subscribe","args":["orderbook.200.BTCUSDT"]}
   {"topic":"orderbook.200.BTCUSDT","type":"snapshot","data":{…,"u":8721903}, …}
```

Confirmed on both spot and linear: a fresh full-depth snapshot with a new `u` follows within ~250 ms.

**A bare re-subscribe does not work** — on linear it is rejected with
`error:already subscribed,topic:…`, and on spot it is silently ignored with no response and no
snapshot. The unsubscribe step is mandatory.

Recovery is therefore **per-topic and connection-local**: it costs two small control frames, does
not disturb the other topics on the connection, and needs no REST budget. A full reconnect is only
warranted when the transport itself fails.

Recovery triggers:

| Trigger | Action |
|---|---|
| `u` gap on a delta | unsubscribe + subscribe that topic |
| Parse failure | unsubscribe + subscribe that topic |
| No message for N × push-interval (staleness) | unsubscribe + subscribe that topic |
| `u == 1` | rebuild locally; no resubscribe needed |
| Socket close (`1006`, `1009`, …) | reconnect, resubscribe everything |

There is no server-side signal for a silently dead subscription. Since duplicate subscribe cannot
be used as a probe (§4.3), staleness must be detected by tracking `lastMessageAt` per topic against
the topic's push interval.

### 6.4 Ordering guarantee

All topics share one TCP connection, so per-topic ordering is guaranteed by the transport. Multiple
depth levels of the *same* symbol on *different* topics are independent books with independent `u`
counters; only `seq` relates them.

---

## 7. Measured characteristics

70-second continuous capture, `stream.bybit.com`, 2026-08-02.

| Topic | Category | Msgs | Rate | Nominal max | Snapshots | `u` gaps | `seq` decreases |
|---|---|---|---|---|---|---|---|
| `orderbook.1000.BTCUSDT` | linear | 350 | 5.0/s | 5/s | 1 | 0 | 0 |
| `orderbook.200.ETHUSDT` | linear | 439 | 6.3/s | 10/s | 1 | 0 | 0 |
| `orderbook.50.SOLUSDT` | linear | 1 582 | 22.6/s | 50/s | 1 | 0 | 0 |
| `orderbook.1.DOGEUSDT` | linear | 451 | 6.4/s | 100/s | 451 (all) | n/a | 0 |
| `orderbook.1000.BTCUSDT` | spot | 351 | 5.0/s | 5/s | 1 | 0 | 0 |
| `orderbook.200.ETHUSDT` | spot | 673 | 9.6/s | 10/s | 1 | 0 | 0 |
| `orderbook.50.SOLUSDT` | spot | 915 | 13.1/s | 50/s | 1 | 0 | 0 |
| `orderbook.1.DOGEUSDT` | spot | 125 | 1.8/s | 100/s | 125 (all) | n/a | 0 |

Frame sizes (JSON text, bytes):

| Frame | Size |
|---|---|
| linear `orderbook.1000` snapshot (BTCUSDT, 1000×2 levels) | ~42 200 |
| linear `orderbook.1000` delta (26 bids + 18 asks) | ~1 040 |
| linear `orderbook.200` snapshot (ETHUSDT) | ~7 900 |
| linear `orderbook.200` delta (8 + 5) | ~410 |
| spot `orderbook.200` snapshot (BTCUSDT) | ~9 100 |
| spot `orderbook.200` delta (0 + 1) | ~180 |
| spot `orderbook.1` snapshot | ~200 |

Subscribe throughput: 700 linear topics in one 18.5 KB frame produced 700 snapshots and 10 184
messages within 7 seconds. 566 spot topics via 57 paced 10-arg frames all acked without error.

---

## 8. Gotchas checklist

1. **`manepa.jp` (Japan) is a different exchange.** Empty spot universe for global pairs, and its
   public WS host does not serve v5. Use `stream.bybit.com` / `api.bybit.com`.
2. **HTTP 200 on errors.** Branch on `retCode`, never the status code.
3. **Spot `instruments-info` ignores `limit` and omits `nextPageCursor` entirely**; linear honours
   both. Don't write one pagination path for both.
4. **Default `status` filter is `Trading`** — pre-listing instruments are invisible unless requested.
5. **A subscribe frame is atomic.** One invalid topic ⇒ zero subscriptions from that frame.
6. **Spot caps `args` at 10 per frame**; linear does not.
7. **Oversize frames close the connection with `1009`**, no error message. Chunk by serialised
   byte length, not arg count.
8. **The ack is not a barrier** — linear can deliver the snapshot before the ack.
9. **Duplicate subscribe is not a recovery path** — rejected on linear, silently dropped on spot.
   Recovery is always unsubscribe → subscribe.
10. **Pong echoes `op:"ping"`**, with `ret_msg:"pong"`. Match on `ret_msg`, not `op`.
11. **Field order differs between spot and linear** (`ts`/`type` swapped). Parse order-independently.
12. **Prices and sizes are strings in every message**, with different padding per category.
13. **Size `0` means delete**, and it is common. Missing this corrupts the book silently.
14. **`u == 1` is a restart marker**, not a sequence value.
15. **`seq` is not contiguous** — never gap-check on it.
16. **Level-1 topics are snapshot-only** with repeating `u`; they have no sync state at all.
17. **Depth is a hard ceiling** — an `orderbook.200` book cannot see level 201, regardless of price
    distance.
18. **RPI liquidity is excluded** from both the stream and the REST snapshot.
19. **`1000BONKUSDT` ≠ `BONKUSDT`.** Multiplier-prefixed linear symbols do not match their spot
    counterparts by string equality.
20. **No rate-limit headers on public endpoints.** Budget must be tracked locally: 600 req / 5 s per
    IP, 10-minute ban on breach.

---

## 9. Unverified / open

- **Behaviour of `u == 1`** was never observed in production during testing; it is documented but
  rare. The handling in §6.2 follows the documentation.
- **Idle-connection drop without any inbound data** (a subscription that goes fully silent, or a
  connection with no subscriptions at all) was not reproduced — the 13-minute no-ping test kept
  receiving depth data throughout. The documented 10-minute window is assumed to apply to a
  genuinely traffic-free connection.
- **`inverse` and `option`** were not exercised. Inverse is expected to mirror linear
  (same depths, same sync model); option uses depths 25/100 only.
- **Reachability from a Japanese IP** was not tested — verification was performed from a European
  egress. Only US and Mainland China are documented as blocked, and the Japan-specific `manepa.jp`
  guidance applies to account holders rather than public market data, but this should be confirmed
  from the target host before relying on `api.bybit.com` / `stream.bybit.com` there.
