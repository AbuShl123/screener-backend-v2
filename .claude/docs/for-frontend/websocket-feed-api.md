# WebSocket Feed API — Realtime Order Book Delivery

> **Audience**: frontend engineers/agents building the live order-book UI against the screener
> backend. This document is the contract for the `/ws` endpoint: how to connect, how to pass the
> token, every message type the server sends, the exact shape of each payload, and how the client
> should react to each one.
>
> For getting the token (register/login/refresh), see [`auth-api.md`](./auth-api.md). For the list
> of instruments the screener tracks, see [`ticker-list-api.md`](./ticker-list-api.md). This doc
> assumes you already have a valid **access token**.

---

## 0. What changed (breaking)

The feed now wraps every message in one **common envelope**, so it can carry more than order books.
Spike alerts and order clusters will arrive later as new `type`s.

1. **Every message except `SNAPSHOT` has the same shape:**
   `{"type", "exchange", "market", "symbol", "data"}`. `type` says how to read `data`. The other
   three fields identify the instrument. Note the new field order: `market` now comes before
   `symbol`.

2. **Order books are `DEPTH`. `ADD`, `UPDATE` and `DROP` are gone.** `bids` / `asks` move under
   `data`. A `DEPTH` message that has `data` is an upsert, and `"data": null` removes the book:

   ```diff
   - {"seq":2,"type":"UPDATE","exchange":"BINANCE","symbol":"BTCUSDT","market":"FUTURES","bids":[…],"asks":[…]}
   + {"type":"DEPTH","exchange":"BINANCE","market":"FUTURES","symbol":"BTCUSDT","data":{"bids":[…],"asks":[…]}}

   - {"seq":3,"type":"DROP","exchange":"BINANCE","symbol":"BTCUSDT","market":"FUTURES"}
   + {"type":"DEPTH","exchange":"BINANCE","market":"FUTURES","symbol":"BTCUSDT","data":null}
   ```

3. **Each entry in `SNAPSHOT.data` is a message exactly as you'd receive it live.** It is a full
   envelope (`{"type":"DEPTH",…,"data":{…}}`), so one handler covers snapshot entries and live
   messages.

4. **`seq` is gone.** No message carries it.

5. **Ignore unknown `type`s.** New message types will be added without a coordinated release. A
   client that ignores types it doesn't recognise keeps working.

Unchanged: the level fields, the three identity fields, the client → server protocol and the
connection lifecycle.

---

## 1. What this socket is

A single WebSocket connection that streams **classified order-book levels** for many instruments at
once. The server pushes and the client almost only listens. About every 100ms the server sends the
order books that changed in that window. The client keeps a local map of
`(exchange, market, symbol) → order book` and re-renders it as messages arrive.

The feed contains only order books that **currently have at least one notable level** (tier 1–4).
A tracked instrument with nothing notable near its mid-price isn't in the feed at all. It enters
with a `DEPTH` message when a notable level appears. It leaves with a `DEPTH` message whose `data`
is `null` when the last one goes away. So the feed is a *subset* of the universe returned by
`GET /api/tickers`.

Each user's feed is shaped by their own classification rules (if they have configured any through
the rules API). Otherwise they get the global default feed. The client doesn't see the difference:
the message format is the same either way. You don't opt in or send rule config over the socket. The
server works it out from the authenticated user, and when the user edits a rule, the open socket
receives a fresh `SNAPSHOT` with the new tiers automatically. No reconnect is needed.

---

## 2. Connecting

### 2.1 Endpoint URL

```
ws(s)://<host>/ws?token=<accessToken>
```

- **Local dev**: `ws://localhost:8080/ws?token=<accessToken>`
- **Production**: `wss://tc-screener.com/ws?token=<accessToken>` (use `wss://`, i.e. TLS, in prod)

There is no `/api` prefix on the socket path. It is exactly `/ws`.

### 2.2 Authentication and access

The access token is passed **as the `token` query parameter** on the connection URL, not as a
header. The browser `WebSocket` constructor can't set custom headers, so the
`Authorization: Bearer` header used for REST isn't available at handshake time.

- Use the **access token**: the JWT from `/api/auth/login` or `/api/auth/refresh`, the same one you
  send as `Authorization: Bearer` on REST calls. **Not** the refresh token.
- On connect (`@OnOpen`) the server checks the token, then checks that the user has **screener
  access** (an active trial or subscription; admins always pass). If either check fails, it
  immediately closes the socket with close code **1008 (VIOLATED_POLICY)**. Use the close
  **reason** to tell the cases apart:

| Close reason | Cause | Client action |
|---|---|---|
| `"Missing token"` | No `token` query param. | Bug on the client side. Fix the URL. |
| `"Invalid or expired token"` | Bad signature or expired JWT. | Refresh the access token, then reconnect. If the refresh fails → login. |
| `"Subscription required"` | Token is valid, but the user's trial/subscription has expired. | **Don't reconnect.** Send the user to the paywall/plans page. Reconnect only after a successful purchase. |

```js
const token = getAccessToken(); // your stored JWT
const ws = new WebSocket(`wss://tc-screener.com/ws?token=${encodeURIComponent(token)}`);
```

> **Checks happen only at connection time.** An open socket is **not** closed when the JWT or the
> subscription expires mid-session. It keeps streaming. Don't rely on that: any reconnect (network
> blip, eviction, see §6) goes through both checks again. Keep the access token fresh (see
> `auth-api.md` §4.4) so a reconnect always has a valid token.

### 2.3 What happens right after a successful connection

Nothing is required from the client. On the next broadcaster tick (~100ms after connecting) the
server sends a full **`SNAPSHOT`** of the current active state. No request is needed. After that,
live `DEPTH` messages arrive as things change.

---

## 3. Message format — server → client

Every message is a **JSON string** (use `JSON.parse` on `event.data`) with a `type` field. Two
types exist today: **`SNAPSHOT`** and **`DEPTH`**. More will be added (§3.2).

### 3.1 The envelope

Every message except `SNAPSHOT` has this shape, with the fields in this order:

```json
{ "type": "DEPTH", "exchange": "BINANCE", "market": "FUTURES", "symbol": "BTCUSDT", "data": { } }
```

| Field | Meaning |
|---|---|
| `type` | What the message is, and how to read `data`. |
| `exchange`, `market`, `symbol` | The instrument the message is about (§3.8). |
| `data` | The payload. Its shape depends on `type`. For `DEPTH`, `null` means "remove this book". |

### 3.2 Unknown types: ignore them

The feed will carry new types (spike alerts, order clusters). **Ignore any `type` you don't
recognise**, whether it arrives live or inside `SNAPSHOT.data`. Don't throw, log noisily or close
the connection.

### 3.3 `SNAPSHOT` — the full current state

Sent once automatically on connect. It is sent again when you send a `SNAPSHOT_REQUEST` (§5) and
when the user's classification rules change. It is the one message without instrument fields,
because it covers many instruments.

```json
{
  "type": "SNAPSHOT",
  "data": [
    {
      "type": "DEPTH", "exchange": "BINANCE", "market": "FUTURES", "symbol": "BTCUSDT",
      "data": { "bids": [ /* level objects */ ], "asks": [ /* level objects */ ] }
    },
    {
      "type": "DEPTH", "exchange": "BINANCE", "market": "SPOT", "symbol": "ETHUSDT",
      "data": { "bids": [ ... ], "asks": [ ... ] }
    }
  ]
}
```

Each entry in `data` is **exactly the message you would receive live** for that instrument. Run it
through the same per-type handler (§4).

**How to treat it**: replace your entire local state. Clear the map, then handle every entry. Any
book you were tracking that is **not** in the new `data` is gone. A snapshot is complete and
authoritative. `data` can be an empty array. Snapshot entries never have `"data": null`.

### 3.4 `DEPTH` — an order book changed

```json
{
  "type": "DEPTH",
  "exchange": "BINANCE",
  "market": "FUTURES",
  "symbol": "BTCUSDT",
  "data": {
    "bids": [ /* level objects */ ],
    "asks": [ /* level objects */ ]
  }
}
```

`data` carries the **current top levels for that one book** (up to 5 per side, see §3.6). It
**replaces** that book's levels. It is not a delta to merge: overwrite the stored `bids`/`asks`
with the arrays in the message.

**How to treat it**: upsert. Look up `(exchange, market, symbol)` in your local map. If the book
exists, replace its `bids`/`asks`. If it doesn't, create it and render a new order book. There is
no separate "add" message: the first `DEPTH` you receive for a book is how it enters.

### 3.5 `DEPTH` with `"data": null` — an order book left the feed

```json
{ "type": "DEPTH", "exchange": "BINANCE", "market": "FUTURES", "symbol": "BTCUSDT", "data": null }
```

The book no longer has any notable level to show. The usual reason is that its last tier-1+ level
was filled, cancelled or drifted out of range. The book can also leave because it lost sync with
the exchange or the instrument was delisted.

**How to treat it**: remove that `(exchange, market, symbol)` book from your local map **right
away** and stop rendering it. A removal is often temporary. If the book gets a notable level again,
you'll receive a new `DEPTH` with `data` for it.

### 3.6 Level object shape (entries in `bids` / `asks`)

Each element of a `data.bids` or `data.asks` array:

```json
{
  "price": 65432.1,
  "quantity": 0.85,
  "tier": 2,
  "firstSeenMillis": 1716680000000,
  "distance": 0.0123
}
```

| Field | Type | Meaning |
|---|---|---|
| `price` | number | Price level. |
| `quantity` | number | Size resting at that price (base asset units). |
| `tier` | integer | Whole number in the range **1–4 inclusive**. Tier 0 is never sent. Use it to drive visual emphasis (color/weight). |
| `firstSeenMillis` | integer | Unix epoch **milliseconds** when this level was first detected. Use it as the order's age: `Date.now() - firstSeenMillis`. |
| `distance` | number | **Fractional** distance from mid-price. `0.0123` means **1.23%**. See §3.7: you must format it yourself. |

`bids` are the buy side and `asks` the sell side. The server orders each array by importance:
highest tier first, then larger notional, then closer to mid-price.

Each side has **0 to 5** levels. Either side can be empty, but when `data` is not `null` at least
one side has a level (a book with nothing notable is removed instead). Iterate whatever is in the
array and don't assume exactly 5.

### 3.7 `distance` — round it yourself

`distance` is a **raw fraction with full floating-point precision**. You will receive values like
`0.012338271604938272`, not a clean `0.0123`. The backend leaves formatting to the client.

To display it as a percentage:

```js
const pct = (level.distance * 100).toFixed(2); // "1.23"  →  render as "1.23%"
```

So: **multiply by 100, then round to 2 decimals** for a percent string. Do this at render time, and
keep the raw value if you need it for anything numeric.

### 3.8 Instrument identity — `exchange`, `market`, `symbol`

Every message except `SNAPSHOT` carries all three fields, in the order `exchange`, `market`,
`symbol`. So does every entry inside `SNAPSHOT.data`.

| Field | Values | Notes |
|---|---|---|
| `exchange` | `"BINANCE"`, `"MEXC"` | More exchanges will be added. Treat it as an open set of strings, not a fixed enum. |
| `market` | `"SPOT"` or `"FUTURES"` | |
| `symbol` | e.g. `"BTCUSDT"` | Normalized `BASEQUOTE` form. **It matches the spelling the rules API uses** and is never an exchange-native spelling such as `BTC_USDT`. |

The same `symbol` + `market` can appear on several exchanges as **independent books**. Key your
local state on all three fields. A key of `symbol` + `market` alone will merge two exchanges' books
into one row that flickers between them.

```js
const key = (m) => `${m.exchange}:${m.market}:${m.symbol}`;   // e.g. "BINANCE:FUTURES:BTCUSDT"
```

This is the same key the ticker list docs recommend, so feed rows and picker entries line up (see
[`ticker-list-api.md`](./ticker-list-api.md) §4).

Custom rules (rules API) are **exchange-independent**. A user's rule for `BTCUSDT` / `SPOT` applies
to `BTCUSDT` spot on **every** exchange, so use `symbol` + `market` (without `exchange`) to relate a
feed row to a rule.

---

## 4. The handler

Write one handler per `type` and use it for both live messages and snapshot entries:

```js
const key = (m) => `${m.exchange}:${m.market}:${m.symbol}`;

function onEnvelope(msg) {
  switch (msg.type) {
    case "DEPTH":
      if (msg.data === null) {
        state.delete(key(msg));                // book left the feed: remove immediately
      } else {
        state.set(key(msg), {                  // upsert: create if missing, replace levels if present
          exchange: msg.exchange, market: msg.market, symbol: msg.symbol,
          bids: msg.data.bids, asks: msg.data.asks,
        });
      }
      break;
    default:
      break;                                   // unknown type: ignore (§3.2)
  }
}

function onMessage(msg) {
  if (msg.type === "SNAPSHOT") {
    state.clear();
    for (const entry of msg.data) onEnvelope(entry);
  } else {
    onEnvelope(msg);
  }
}
```

In short:
- **`DEPTH` with `data`** → upsert the `(exchange, market, symbol)` book.
- **`DEPTH` with `data: null`** → remove the book immediately.
- **`SNAPSHOT`** → clear everything, then handle each entry like a live message.
- **Any other type** → ignore.

---

## 5. Client → server messages

The client rarely needs to send anything. There is exactly **one** supported message:

| Send (raw text, not JSON) | Effect |
|---|---|
| `SNAPSHOT_REQUEST` | The server sends a fresh full `SNAPSHOT` on its next drain tick (~100ms). |

```js
ws.send("SNAPSHOT_REQUEST"); // literally this string, no JSON envelope
```

Send it if you suspect local state has drifted, or after a UI event that calls for a hard resync
(for example, the user re-opens the order-book panel). The server silently ignores any other
message. Send it as a **plain string**, not JSON.

You do **not** need to send it after editing rules. The server pushes a fresh snapshot by itself
(§1).

---

## 6. Connection lifecycle & reconnection

### 6.1 Slow-client eviction

The server protects itself from clients that can't keep up. Each session has a bounded send queue
(32 batches, about 3.2s of backlog). If your connection stalls and the queue fills, the server
**disconnects you** instead of buffering without limit. You'll see the socket close, typically with
close code **1001 / GOING_AWAY**.

This isn't an error you can fix per message. It means the client couldn't read fast enough (stalled
tab, dead network). Respond the same way as to any disconnect: **reconnect**, and you'll get a fresh
snapshot.

The server never skips messages for a connected client: it either delivers everything or
disconnects. So while the socket is open your state is complete, and after any reconnect the
`SNAPSHOT` brings it back in line.

### 6.2 Reconnection strategy (recommended)

1. On `close` (any code) or `error`, reconnect with **exponential backoff** (e.g. start at ~1s, cap
   at ~30s) plus some jitter. Don't retry in a tight loop.
2. **Before reconnecting, make sure the access token is still valid**, and refresh it if needed
   (see `auth-api.md` §4.4).
3. On close code **1008**, check `event.reason`:
   - `"Subscription required"` → **stop reconnecting** and show the paywall.
   - anything else → refresh the token first, then reconnect. If the refresh fails, send the user to
     login. Don't keep retrying the socket.
4. On reconnect you'll automatically get a new `SNAPSHOT`. **Clear local state and rebuild** from
   it. Don't try to resume where you left off.

```js
let backoff = 1000;
function connect() {
  const ws = new WebSocket(`wss://tc-screener.com/ws?token=${encodeURIComponent(getAccessToken())}`);

  ws.onmessage = (e) => onMessage(JSON.parse(e.data));

  ws.onopen = () => { backoff = 1000; };          // reset backoff on success

  ws.onclose = (e) => {
    if (e.code === 1008) {
      if (e.reason === "Subscription required") {  // entitlement lapsed
        showPaywall();                             // do NOT reconnect
        return;
      }
      refreshTokenThen(connect);                   // auth failure: refresh, then reconnect
      return;
    }
    setTimeout(connect, backoff + Math.random() * 500);
    backoff = Math.min(backoff * 2, 30000);
  };
}
```

### 6.3 Close code cheat sheet

| Close code | Reason | Meaning | Client action |
|---|---|---|---|
| 1008 (VIOLATED_POLICY) | `Missing token` / `Invalid or expired token` | Auth failed at handshake | Refresh token, then reconnect. If refresh fails → login. |
| 1008 (VIOLATED_POLICY) | `Subscription required` | No active trial/subscription | Show paywall. Don't reconnect until the user pays. |
| 1001 (GOING_AWAY) | — | Slow-client eviction, or server shutdown | Reconnect with backoff. |
| 1006 / other abnormal | — | Network drop | Reconnect with backoff. |

---

## 7. Quick reference

**Connect**: `wss://<host>/ws?token=<accessToken>`. The token is the **access JWT**, passed as a
query param. The user also needs an active trial/subscription (admins always pass).

**Envelope**: `{"type", "exchange", "market", "symbol", "data"}` on every message except
`SNAPSHOT`.

**Server → client message types**:

| Type | `data` | Client action |
|---|---|---|
| `SNAPSHOT` | Array of envelopes, exactly as received live | Clear all local state, then handle each entry. |
| `DEPTH` | `{ bids, asks }` | Upsert `(exchange, market, symbol)`: create if missing, replace levels if present. |
| `DEPTH` | `null` | Remove `(exchange, market, symbol)` immediately. |
| anything else | — | Ignore. |

**Identity**: `exchange` (open set of strings), `market` (`"SPOT"` / `"FUTURES"`), `symbol`
(normalized `BASEQUOTE`). **Key local state on all three.**

**Level fields**: `price`, `quantity`, `tier` (whole number 1–4), `firstSeenMillis` (epoch ms),
`distance` (**fraction**: `×100`, then `.toFixed(2)` for a `%`). 0–5 levels per side.

**Client → server**: only `SNAPSHOT_REQUEST` (raw string) to force a resync.

**On disconnect**: reconnect with backoff → rebuild from the pushed `SNAPSHOT`. On 1008, refresh the
token, unless the reason is `Subscription required`, in which case show the paywall.

**TypeScript shapes**:

```ts
type Market = 'SPOT' | 'FUTURES';

interface Level {
  price: number;
  quantity: number;
  tier: 1 | 2 | 3 | 4;
  firstSeenMillis: number;
  distance: number;          // fraction
}

interface InstrumentIdentity {
  exchange: string;          // open set, e.g. "BINANCE", "MEXC"
  market: Market;
  symbol: string;            // normalized BASEQUOTE
}

interface DepthData {
  bids: Level[];
  asks: Level[];
}

interface DepthMessage extends InstrumentIdentity {
  type: 'DEPTH';
  data: DepthData | null;    // null = remove the book
}

/** Every message except SNAPSHOT. More types will be added; ignore the ones you don't handle. */
type Envelope = DepthMessage | (InstrumentIdentity & { type: string; data: unknown });

interface SnapshotMessage {
  type: 'SNAPSHOT';
  data: Envelope[];
}

type FeedMessage = SnapshotMessage | Envelope;
```
