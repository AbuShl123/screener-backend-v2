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

The feed now supports multiple exchanges.

1. **Every message that names an order book now carries an `exchange` field.** This covers `ADD`,
   `UPDATE`, `DROP` and every entry in `SNAPSHOT.data`. The identity fields always come in the
   order `exchange`, `symbol`, `market`:

   ```diff
   - {"seq":2,"type":"UPDATE","symbol":"BTCUSDT","market":"FUTURES","bids":[…],"asks":[…]}
   + {"seq":2,"type":"UPDATE","exchange":"BINANCE","symbol":"BTCUSDT","market":"FUTURES","bids":[…],"asks":[…]}
   ```

2. **Key local state on `(exchange, market, symbol)`, not `(market, symbol)`.** The same symbol and
   market can now exist on several exchanges as independent books. A two-part key would merge them
   into one row that flickers between exchanges. See §3.7.

3. `exchange` is `"BINANCE"` for every message today. Treat it as an open set of strings, not a
   closed enum, so a new exchange doesn't break parsing.

Nothing else changed: message types, level fields, `seq`, the client → server protocol and the
connection lifecycle are the same as before.

---

## 1. What this socket is

A single WebSocket connection that streams **classified order-book levels** for many instruments at
once. The server pushes and the client almost only listens. About every 100ms the server sends the
order books that changed in that window. The client keeps a local map of
`(exchange, market, symbol) → order book` and re-renders it as messages arrive.

The feed contains only order books that **currently have at least one notable level** (tier 1–4).
A tracked instrument with nothing notable near its mid-price isn't in the feed at all. It enters
with `ADD`/`UPDATE` when a notable level appears and leaves with `DROP` when the last one goes away.
So the feed is a *subset* of the universe returned by `GET /api/tickers`.

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
> blip, eviction, see §7) goes through both checks again. Keep the access token fresh (see
> `auth-api.md` §4.4) so a reconnect always has a valid token.

### 2.3 What happens right after a successful connection

Nothing is required from the client. On the next broadcaster tick (~100ms after connecting) the
server sends a full **`SNAPSHOT`** of the current active state. No request is needed. After that,
incremental messages (`ADD` / `UPDATE` / `DROP`) arrive as things change.

---

## 3. Message format — server → client

Every message is a **JSON string** (use `JSON.parse` on `event.data`). Every message has a `type`
field. There are four types: **`SNAPSHOT`**, **`ADD`**, **`UPDATE`**, **`DROP`**.

Every message also starts with a `seq` field. **You can ignore it** (see §5).

### 3.1 `SNAPSHOT` — the full current state

Sent once automatically on connect. It is sent again when you send a `SNAPSHOT_REQUEST` (§6) and
when the user's classification rules change. It contains every currently active order book in one
message.

```json
{
  "seq": 1,
  "type": "SNAPSHOT",
  "data": [
    {
      "exchange": "BINANCE",
      "symbol": "BTCUSDT",
      "market": "FUTURES",
      "bids": [ /* level objects */ ],
      "asks": [ /* level objects */ ]
    },
    {
      "exchange": "BINANCE",
      "symbol": "ETHUSDT",
      "market": "SPOT",
      "bids": [ ... ],
      "asks": [ ... ]
    }
  ]
}
```

**How to treat it**: replace your entire local state. Clear the map and rebuild it from `data`. Each
entry in `data` is one order book, identified by its `(exchange, market, symbol)` triple (§3.7).
Remove any book you were tracking that is **not** in the new `data`. A snapshot is complete and
authoritative. `data` can be an empty array.

### 3.2 `ADD` and `UPDATE` — an order book changed

`ADD` and `UPDATE` have the **same payload shape**, and **the frontend should handle them the same
way**. See §4 for the full rule.

```json
{
  "seq": 2,
  "type": "UPDATE",
  "exchange": "BINANCE",
  "symbol": "BTCUSDT",
  "market": "FUTURES",
  "bids": [ /* level objects */ ],
  "asks": [ /* level objects */ ]
}
```

`ADD` is identical except for `"type": "ADD"`. Each of these messages carries the **current top
levels for that one book** (up to 5 per side, see §3.5). It **replaces** that book's levels. It is
not a delta to merge: overwrite the stored `bids`/`asks` for the book with the arrays in the message.

**How to treat it**: upsert. Look up `(exchange, market, symbol)` in your local map. If the book
exists, replace its `bids`/`asks`. If it doesn't, create it and render a new order book. Don't
discard an `UPDATE` because you never saw an `ADD` for it (see §4).

### 3.3 `DROP` — an order book left the feed

```json
{
  "seq": 3,
  "type": "DROP",
  "exchange": "BINANCE",
  "symbol": "BTCUSDT",
  "market": "FUTURES"
}
```

A `DROP` has **no `bids`/`asks`**, only the identifying `exchange`, `symbol` and `market`. It means
the book no longer has any notable level to show. The usual reason is that its last tier-1+ level
was filled, cancelled or drifted out of range. The book can also drop because it lost sync with the
exchange or the instrument was delisted.

**How to treat it**: remove that `(exchange, market, symbol)` book from your local map **right
away** and stop rendering it. A `DROP` is often temporary. If the book gets a notable level again,
you'll receive a new `ADD`/`UPDATE` for it.

### 3.4 Level object shape (entries in `bids` / `asks`)

Each element of a `bids` or `asks` array:

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
| `distance` | number | **Fractional** distance from mid-price. `0.0123` means **1.23%**. See §3.6: you must format it yourself. |

`bids` are the buy side and `asks` the sell side. The server orders each array by importance:
highest tier first, then larger notional, then closer to mid-price.

### 3.5 Array sizes

Each side (`bids`, `asks`) has **0 to 5** levels. Either side can be empty, but in an `ADD`,
`UPDATE` or `SNAPSHOT` entry at least one side has a level (a book with nothing notable is
`DROP`ped instead). Iterate whatever is in the array and don't assume exactly 5.

### 3.6 `distance` — round it yourself

`distance` is a **raw fraction with full floating-point precision**. You will receive values like
`0.012338271604938272`, not a clean `0.0123`. The backend leaves formatting to the client.

To display it as a percentage:

```js
const pct = (level.distance * 100).toFixed(2); // "1.23"  →  render as "1.23%"
```

So: **multiply by 100, then round to 2 decimals** for a percent string. Do this at render time, and
keep the raw value if you need it for anything numeric.

### 3.7 Book identity — `exchange`, `market`, `symbol`

Every message that names a book (`ADD`, `UPDATE`, `DROP`, and each `SNAPSHOT` entry) carries all
three fields, in the order `exchange`, `symbol`, `market`:

| Field | Values | Notes |
|---|---|---|
| `exchange` | `"BINANCE"` today | More exchanges will be added. Treat it as an open set of strings, not a fixed enum. |
| `symbol` | e.g. `"BTCUSDT"` | Normalized `BASEQUOTE` form. **It matches the spelling the rules API uses** and is never an exchange-native spelling such as `BTC_USDT`. |
| `market` | `"SPOT"` or `"FUTURES"` | |

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

## 4. The core rendering rule: ADD ≡ UPDATE, and UPDATE-without-ADD is normal

This is the most important behavior rule for the feed. Internally the backend separates `ADD` (a
book enters the feed) from `UPDATE` (later changes), but **for the frontend the two are the same**.
Don't assume an `ADD` always comes before an `UPDATE` for a given book.

**Because of feed coalescing and per-user timing, you will sometimes receive an `UPDATE` for a book
you never saw an `ADD` for.** This is expected. It is not an error or a dropped message, and you
shouldn't guard against it.

**The rule: handle `ADD` and `UPDATE` with the same upsert:**

```js
const key = (m) => `${m.exchange}:${m.market}:${m.symbol}`;

function onOrderBookMessage(msg) {
  switch (msg.type) {
    case "SNAPSHOT":
      state.clear();
      for (const book of msg.data) {
        state.set(key(book), book);
      }
      break;

    case "ADD":
    case "UPDATE": {                       // ← identical handling, intentionally
      // If it's missing, create it; if present, replace its levels.
      state.set(key(msg), {
        exchange: msg.exchange, symbol: msg.symbol, market: msg.market,
        bids: msg.bids, asks: msg.asks,
      });
      break;
    }

    case "DROP":
      state.delete(key(msg));              // remove immediately
      break;
  }
}
```

In short:
- **`ADD` / `UPDATE`** → if the `(exchange, market, symbol)` book is missing, render it. If it
  exists, replace its levels. Never drop an `UPDATE` because no `ADD` came first.
- **`DROP`** → remove the book immediately.
- **`SNAPSHOT`** → clear everything and rebuild.

---

## 5. About the `seq` field — ignore it

Every message starts with an integer `seq` (e.g. `{"seq": 42, "type": "UPDATE", ...}`). It restarts
at `1` with every `SNAPSHOT`. **Ignore it completely.** Don't track it and don't branch on it. It is
documented here only so it doesn't cause confusion in the payload.

---

## 6. Client → server messages

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

## 7. Connection lifecycle & reconnection

### 7.1 Slow-client eviction

The server protects itself from clients that can't keep up. Each session has a bounded send queue
(32 batches, about 3.2s of backlog). If your connection stalls and the queue fills, the server
**disconnects you** instead of buffering without limit. You'll see the socket close, typically with
close code **1001 / GOING_AWAY**.

This isn't an error you can fix per message. It means the client couldn't read fast enough (stalled
tab, dead network). Respond the same way as to any disconnect: **reconnect**, and you'll get a fresh
snapshot.

### 7.2 Reconnection strategy (recommended)

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

  ws.onmessage = (e) => onOrderBookMessage(JSON.parse(e.data));

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

### 7.3 Close code cheat sheet

| Close code | Reason | Meaning | Client action |
|---|---|---|---|
| 1008 (VIOLATED_POLICY) | `Missing token` / `Invalid or expired token` | Auth failed at handshake | Refresh token, then reconnect. If refresh fails → login. |
| 1008 (VIOLATED_POLICY) | `Subscription required` | No active trial/subscription | Show paywall. Don't reconnect until the user pays. |
| 1001 (GOING_AWAY) | — | Slow-client eviction, or server shutdown | Reconnect with backoff. |
| 1006 / other abnormal | — | Network drop | Reconnect with backoff. |

---

## 8. Quick reference

**Connect**: `wss://<host>/ws?token=<accessToken>`. The token is the **access JWT**, passed as a
query param. The user also needs an active trial/subscription (admins always pass).

**Server → client message types**:

| Type | Has `bids`/`asks`? | Client action |
|---|---|---|
| `SNAPSHOT` | Yes (array under `data[]`) | Clear all local state, rebuild from `data`. |
| `ADD` | Yes | Upsert `(exchange, market, symbol)`: create if missing, replace levels if present. |
| `UPDATE` | Yes | **Same as `ADD`.** Upsert. Can arrive without a prior `ADD`; that's normal. |
| `DROP` | No | Remove `(exchange, market, symbol)` immediately. |

**Identity**: `exchange` (`"BINANCE"` today, open set), `symbol` (normalized `BASEQUOTE`), `market`
(`"SPOT"` / `"FUTURES"`). **Key local state on all three.**

**Level fields**: `price`, `quantity`, `tier` (whole number 1–4), `firstSeenMillis` (epoch ms),
`distance` (**fraction**: `×100`, then `.toFixed(2)` for a `%`). 0–5 levels per side.

**`seq`**: ignore it.

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

interface BookIdentity {
  exchange: string;          // "BINANCE" today; open set
  symbol: string;            // normalized BASEQUOTE
  market: Market;
}

interface BookData extends BookIdentity {
  bids: Level[];
  asks: Level[];
}

type FeedMessage =
  | { seq: number; type: 'SNAPSHOT'; data: BookData[] }
  | ({ seq: number; type: 'ADD' | 'UPDATE' } & BookData)
  | ({ seq: number; type: 'DROP' } & BookIdentity);
```
