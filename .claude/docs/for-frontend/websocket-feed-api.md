# WebSocket Feed API — Realtime Order Book Delivery

> **Audience**: frontend engineers/agents building the live order-book UI against the screener
> backend. This document is a complete contract reference for the `/ws` endpoint — how to connect,
> how the token is passed, every message type the server sends, the exact shape of each payload, and
> how the client should react to each. It is the source of truth for the socket protocol.
>
> For obtaining the token in the first place (register/login/refresh), see
> [`auth-api.md`](./auth-api.md). This doc assumes you already have a valid **access token**.

---

## 1. What this socket is

A single WebSocket connection that streams **classified order-book levels** for many tickers at once.
The server pushes; the client (almost) only listens. Every ~100ms the server sends whatever changed
in that window, per ticker. The client's job is to maintain a local map of
`(symbol, market) → order book` and re-render it as messages arrive.

Each connected user gets the feed shaped by their own classification rules (if they've configured
any via the rules API); otherwise they get the global default feed. This is transparent to the
client — the message format is identical either way. You do not opt in or send rule config over the
socket; it's derived server-side from the authenticated user.

---

## 2. Connecting

### 2.1 Endpoint URL

```
ws(s)://<host>/ws?token=<accessToken>
```

- **Local dev**: `ws://localhost:8080/ws?token=<accessToken>`
- **Production**: `wss://tc-screener.com/ws?token=<accessToken>` (use `wss://` — TLS — in prod)

There is no `/api` prefix on the socket path. It is exactly `/ws`.

### 2.2 Authentication — token as a query parameter

The access token is passed **as the `token` query parameter on the connection URL**, not as a
header. This is deliberate: browsers' `WebSocket` constructor does not allow setting custom headers,
so the standard `Authorization: Bearer` mechanism used for REST is not available at handshake time.

- Use the **access token** (the JWT from `/api/auth/login` or `/api/auth/refresh`) — the **same**
  token you send as `Authorization: Bearer` on REST calls. **Not** the refresh token.
- The server validates the JWT signature and expiry at handshake (`@OnOpen`). On any problem it
  immediately closes the socket with close code **1008 (VIOLATED_POLICY)**:
  - Missing `token` param → closed, reason `"Missing token"`.
  - Invalid or expired token → closed, reason `"Invalid or expired token"`.
- So a connection that closes with code 1008 right after opening means **auth failed** — refresh the
  access token and reconnect. Do not retry with the same expired token in a tight loop.

```js
const token = getAccessToken(); // your stored JWT
const ws = new WebSocket(`wss://tc-screener.com/ws?token=${encodeURIComponent(token)}`);
```

> **Token expiry mid-session**: the token is only checked at connection time. An already-open socket
> is **not** force-closed the moment the JWT expires — it keeps streaming. But do not rely on that:
> if you ever need to reconnect (network blip, eviction — see §7), you must reconnect with a
> **currently valid** token. Best practice: keep the access token fresh (see `auth-api.md` §4.4) so a
> reconnect always has a good token on hand.

### 2.3 What happens right after a successful connection

You do **nothing**. On the next broadcaster tick (~100ms after connecting) the server automatically
sends you a full **`SNAPSHOT`** of the current active state. You do not need to send a request for
the initial snapshot — it is pushed for you. After that, incremental messages (`ADD` / `UPDATE` /
`DROP`) flow as things change.

---

## 3. Message format — server → client

Every message is a **JSON string** (use `JSON.parse` on `event.data`). Every message has a `type`
field. There are four types: **`SNAPSHOT`**, **`ADD`**, **`UPDATE`**, **`DROP`**.

Every message also carries a leading `seq` field — **you can ignore it entirely** (see §5).

### 3.1 `SNAPSHOT` — the full current state

Sent automatically once on connect, and again whenever you send a `SNAPSHOT_REQUEST` (§6). It is the
complete set of currently-active `(symbol, market)` order books in one message.

```json
{
  "seq": 1,
  "type": "SNAPSHOT",
  "data": [
    {
      "symbol": "BTCUSDT",
      "market": "FUTURES",
      "bids": [ /* level objects */ ],
      "asks": [ /* level objects */ ]
    },
    {
      "symbol": "ETHUSDT",
      "market": "SPOT",
      "bids": [ ... ],
      "asks": [ ... ]
    }
  ]
}
```

**How to treat it**: replace your entire local state. Clear whatever you had and rebuild the map from
`data`. Every entry in `data` is one order book keyed by the `(symbol, market)` pair. Anything you
were previously tracking that is **not** in the new `data` should be dropped — a fresh snapshot is
authoritative and complete.

### 3.2 `ADD` and `UPDATE` — an order book changed

Both `ADD` and `UPDATE` carry the **same payload shape** and — importantly — **the frontend should
treat them identically**. See §4 for the full rule.

```json
{
  "seq": 2,
  "type": "UPDATE",
  "symbol": "BTCUSDT",
  "market": "FUTURES",
  "bids": [ /* level objects */ ],
  "asks": [ /* level objects */ ]
}
```

`ADD` looks the same, just with `"type": "ADD"`. Each such message is the **current top levels for
that one ticker** (up to 5 per side — see §3.5). It is a *replacement* for that ticker's book, not a
delta to merge: overwrite your stored `bids`/`asks` for that `(symbol, market)` with the arrays in
the message.

**How to treat it**: upsert. Look up `(symbol, market)` in your local map. If present, replace its
`bids`/`asks`. If **not** present, create it — render a new order book. (Do not discard an `UPDATE`
just because you never saw an `ADD` for it — see §4.)

### 3.3 `DROP` — a ticker was removed

```json
{
  "seq": 3,
  "type": "DROP",
  "symbol": "BTCUSDT",
  "market": "FUTURES"
}
```

A `DROP` has **no `bids`/`asks`** — just the identifying `symbol` + `market`. It means this ticker is
no longer part of the screener (delisted, went non-`TRADING`, lost sync, etc.).

**How to treat it**: remove that `(symbol, market)` from your local map **immediately** and stop
rendering it. There is no "coming back soon" implied — if it returns later, you'll get a fresh `ADD`
/ `UPDATE` for it.

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
| `tier` | integer | A whole number bound to the range **0–4 inclusive**. Use it to drive visual emphasis (color/weight). |
| `firstSeenMillis` | integer | Unix epoch **milliseconds** — the time this order was first detected. Treat it as the order's age: `Date.now() - firstSeenMillis`. |
| `distance` | number | **Fractional** distance from mid-price. `0.0123` means **1.23%**. See §3.6 — you must format this yourself. |

`bids` are the buy side, `asks` the sell side. Arrays are already ordered best-first by the server
and contain **at most 5 levels** each.

### 3.5 Array sizes

Each side (`bids`, `asks`) contains **up to 5** levels. It can be fewer than 5 (or empty). Just
iterate whatever is there; don't assume exactly 5.

### 3.6 `distance` — round it yourself

The `distance` field is a **raw fraction with full floating-point precision** — e.g. you will
literally receive values like `0.012338271604938272`, not a clean `0.0123`. The backend sends it
verbatim as a fraction and leaves formatting to the client.

To display it as a percentage:

```js
const pct = (level.distance * 100).toFixed(2); // "1.23"  →  render as "1.23%"
```

So: **multiply by 100, then round to 2 decimals** for a percent string. Do this at render time; keep
the raw value if you need it for anything numeric.

---

## 4. The core rendering rule: ADD ≡ UPDATE, and UPDATE-without-ADD is normal

This is the single most important behavioral note for the feed. Internally the backend distinguishes
`ADD` (first time a ticker enters the feed) from `UPDATE` (subsequent changes), but **for the
frontend there is no meaningful difference** — and you must not assume `ADD` always precedes
`UPDATE` for a given ticker.

**Because of feed coalescing and per-user timing, it is completely normal and expected to receive an
`UPDATE` for a ticker you have never seen an `ADD` for.** This is not an error, not a dropped
message, and not something to guard against.

**The rule — treat both `ADD` and `UPDATE` as the same "upsert" operation:**

```js
function onOrderBookMessage(msg) {
  switch (msg.type) {
    case "SNAPSHOT":
      state.clear();
      for (const book of msg.data) {
        state.set(key(book.symbol, book.market), book);
      }
      break;

    case "ADD":
    case "UPDATE": {                       // ← identical handling, intentionally
      const k = key(msg.symbol, msg.market);
      // If it's missing, create it; if present, replace its levels.
      state.set(k, { symbol: msg.symbol, market: msg.market, bids: msg.bids, asks: msg.asks });
      break;
    }

    case "DROP":
      state.delete(key(msg.symbol, msg.market));   // remove immediately
      break;
  }
}

const key = (symbol, market) => `${symbol}:${market}`;
```

In short:
- **`ADD` / `UPDATE`** → if the `(symbol, market)` book is missing, render it; if it exists, replace
  its levels. Never drop an `UPDATE` for lack of a prior `ADD`.
- **`DROP`** → remove the book immediately.
- **`SNAPSHOT`** → wipe and rebuild everything.

`market` is always one of exactly two string values: **`"SPOT"`** or **`"FUTURES"`**. Always key your
local state on the `(symbol, market)` pair — the same symbol can exist in both markets simultaneously
as two independent books.

---

## 5. About the `seq` field — ignore it

Every message includes a leading integer `seq` (e.g. `{"seq": 42, "type": "UPDATE", ...}`).
**Completely ignore this parameter.** Do not track it or branch on it. It is documented here only so
you're not confused seeing it in the payload.

---

## 6. Client → server messages

The client rarely needs to send anything. There is exactly **one** supported message today:

| Send (raw text, not JSON) | Effect |
|---|---|
| `SNAPSHOT_REQUEST` | Server delivers a fresh full `SNAPSHOT` on its next drain tick (~100ms). |

```js
ws.send("SNAPSHOT_REQUEST"); // literally this string, no JSON envelope
```

Send this if you ever suspect your local state has drifted, or after a UI event where you want to
hard-resync (e.g. the user re-opens the order-book panel). Any unrecognized message is silently
ignored by the server. Note it is sent as a **plain string**, not JSON.

---

## 7. Connection lifecycle & reconnection

### 7.1 Slow-client eviction

The server protects itself from clients that can't keep up. Each session has a bounded send queue
(32 batches ≈ 3.2s of backlog). If your connection stalls and the queue fills, the server
**disconnects you** rather than buffering unbounded. You'll observe this as the socket closing
(typically close code **1001 / GOING_AWAY**).

This is not an error you can "fix" per message — it means the client couldn't drain fast enough
(stalled tab, dead network). The correct response is the same as any disconnect: **reconnect**, and
you'll get a fresh snapshot.

### 7.2 Reconnection strategy (recommended)

1. On `close` (any code) or `error`, attempt to reconnect with **exponential backoff** (e.g. start
   ~1s, cap ~30s) plus a little jitter. Don't hammer a tight retry loop.
2. **Before reconnecting, ensure the access token is still valid**; refresh it if needed (see
   `auth-api.md` §4.4). Reconnecting with an expired token gets you an immediate 1008 close.
3. On close code **1008**, treat it as an auth problem specifically: refresh the token first, then
   reconnect. If refresh itself fails, route the user to login — don't keep retrying the socket.
4. On reconnect you'll automatically receive a new `SNAPSHOT` — **clear local state and rebuild** from
   it. Do not try to resume from where you left off.

```js
let backoff = 1000;
function connect() {
  const ws = new WebSocket(`wss://tc-screener.com/ws?token=${encodeURIComponent(getAccessToken())}`);

  ws.onmessage = (e) => onOrderBookMessage(JSON.parse(e.data));

  ws.onopen = () => { backoff = 1000; };          // reset backoff on success

  ws.onclose = (e) => {
    if (e.code === 1008) {                         // auth failure
      refreshTokenThen(connect);                   // refresh, then reconnect
      return;
    }
    setTimeout(connect, backoff + Math.random() * 500);
    backoff = Math.min(backoff * 2, 30000);
  };
}
```

### 7.3 Close code cheat sheet

| Close code | Meaning | Client action |
|---|---|---|
| 1008 (VIOLATED_POLICY) | Missing / invalid / expired token at handshake | Refresh token, then reconnect. If refresh fails → login. |
| 1001 (GOING_AWAY) | Slow-client eviction, or server shutdown | Reconnect with backoff. |
| 1006 / other abnormal | Network drop | Reconnect with backoff. |

---

## 8. Quick reference

**Connect**: `wss://<host>/ws?token=<accessToken>` — token is the **access JWT**, as a query param.

**Server → client message types**:

| Type | Has `bids`/`asks`? | Client action |
|---|---|---|
| `SNAPSHOT` | Yes (array under `data[]`) | Clear all local state, rebuild from `data`. |
| `ADD` | Yes | Upsert `(symbol, market)` — create if missing, replace levels if present. |
| `UPDATE` | Yes | **Identical to `ADD`.** Upsert. Normal to arrive with no prior `ADD`. |
| `DROP` | No | Remove `(symbol, market)` immediately. |

**Level fields**: `price`, `quantity`, `tier` (whole number 0–4), `firstSeenMillis` (epoch ms),
`distance` (**fraction** — do `×100`, `.toFixed(2)` for a `%`).

**`market`**: always `"SPOT"` or `"FUTURES"`. Key local state on `(symbol, market)`.

**`seq`**: ignore it.

**Client → server**: only `SNAPSHOT_REQUEST` (raw string) to force a resync.

**On disconnect**: reconnect with backoff (fresh token if 1008) → rebuild from the pushed `SNAPSHOT`.
