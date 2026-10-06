# WebSocket Feed API — `/ws`

> **Audience**: frontend engineers/agents building the live screener UI. This is the contract for
> the `/ws` endpoint: how to connect and authenticate, every message the server sends, the exact
> payload shapes, and how the client should react to each.
>
> For getting the token (register/login/refresh), see [`auth-api.md`](./auth-api.md). For the list
> of instruments the screener tracks, see [`ticker-list-api.md`](./ticker-list-api.md).

---

## 1. Overview

One WebSocket connection carries the whole live feed. The server pushes and the client almost
only listens.

- About every **100ms** the server sends what changed in that window.
- Every message is one JSON object in its own text frame, wrapped in a common **envelope**
  (`type`, instrument identity, `data`). `type` says how to read `data`.
- On connect, and whenever a resync is needed, the server sends one **`SNAPSHOT`** holding the
  full current state. Live messages after it are applied on top.
- Today the only data type is **`DEPTH`**: classified order-book levels. Spike alerts and order
  clusters will arrive later as new `type`s on the same socket. **Clients must ignore types they
  don't know** (§4.2).

The depth feed only contains books that **currently have at least one notable level** (tier 1–4).
A tracked instrument with nothing notable near its mid-price isn't in the feed at all, so the feed
is a *subset* of the universe returned by `GET /api/tickers`. A book enters with a `DEPTH` message
carrying levels and leaves with a `DEPTH` message whose `data` is `null`.

Each user's feed is shaped by their own classification rules (configured through the rules API),
or by the global defaults if they have none. The client doesn't see the difference: the message
format is identical. Nothing about rules is sent over the socket. When the user edits a rule, the
open socket receives a fresh `SNAPSHOT` with the new tiers automatically; no reconnect is needed.

---

## 2. Connecting

### 2.1 URL

```
ws(s)://<host>/ws?token=<accessToken>
```

- **Local dev**: `ws://localhost:8080/ws?token=<accessToken>`
- **Production**: `wss://tc-screener.com/ws?token=<accessToken>` (always `wss://` in prod)

There is no `/api` prefix. The path is exactly `/ws`.

### 2.2 Authentication and access

The token goes in the **`token` query parameter**, because the browser `WebSocket` constructor
can't set an `Authorization` header.

- Use the **access token** (the JWT from `/api/auth/login` or `/api/auth/refresh`, the one sent as
  `Authorization: Bearer` on REST calls). **Not** the refresh token.
- On connect the server validates the token, then checks that the user has **screener access**
  (an active trial or subscription; admins always pass). If either check fails, the socket is
  closed immediately with code **1008 (VIOLATED_POLICY)**. The close **reason** tells the cases
  apart:

| Close reason | Cause | Client action |
|---|---|---|
| `"Missing token"` | No `token` query param. | Client bug. Fix the URL. |
| `"Invalid or expired token"` | Bad signature or expired JWT. | Refresh the access token, then reconnect. If the refresh fails → login. |
| `"Subscription required"` | Valid token, but the trial/subscription has expired. | **Don't reconnect.** Show the paywall/plans page. Reconnect only after a successful purchase. |

```js
const ws = new WebSocket(`wss://tc-screener.com/ws?token=${encodeURIComponent(getAccessToken())}`);
```

> **Checks happen only at connect time.** An open socket is not closed when the JWT or the
> subscription expires mid-session. Don't rely on that: every reconnect (network blip, eviction,
> deploy) goes through both checks again. Keep the access token fresh (`auth-api.md` §4.4) so a
> reconnect always has a valid one.

### 2.3 After connecting

Nothing is required from the client. On the next server tick (~100ms) a full `SNAPSHOT` arrives
without being requested. Live messages follow.

---

## 3. Message format — server → client

Every frame is a JSON string; `JSON.parse(event.data)` gives one message object. Every message has
a `type`.

### 3.1 The envelope

Every message except `SNAPSHOT` has exactly these fields, in this order:

```json
{ "type": "DEPTH", "exchange": "BINANCE", "market": "FUTURES", "symbol": "BTCUSDT", "data": { } }
```

| Field | Meaning |
|---|---|
| `type` | What the message is, and how to read `data`. |
| `exchange`, `market`, `symbol` | The instrument the message is about (§3.6). |
| `data` | The payload. Its shape depends on `type`. |

### 3.2 `SNAPSHOT` — the full current state

```json
{
  "type": "SNAPSHOT",
  "data": [
    { "type": "DEPTH", "exchange": "BINANCE", "market": "FUTURES", "symbol": "BTCUSDT",
      "data": { "bids": [ /* levels */ ], "asks": [ /* levels */ ] } },
    { "type": "DEPTH", "exchange": "BINANCE", "market": "SPOT", "symbol": "ETHUSDT",
      "data": { "bids": [ /* levels */ ], "asks": [ /* levels */ ] } }
  ]
}
```

The only message without instrument fields, since it covers many instruments. Sent:

- once, automatically, right after connecting;
- after the client sends `SNAPSHOT_REQUEST` (§5);
- after the user's classification rules change.

Each entry of `data` is **a complete envelope, byte-for-byte what you would receive live** for
that instrument. Run each one through the same per-type handler as live messages (§4).

**How to treat it**: replace your entire local state. Clear everything, then handle each entry. Any
book you had that isn't in the snapshot is gone. Notes:

- `data` may be an empty array (nothing notable anywhere right now).
- Entries come in no particular order, and may mix types once more types exist.
- Snapshot entries never have `"data": null`.

### 3.3 `DEPTH` with `data` — an order book was added or changed

```json
{
  "type": "DEPTH",
  "exchange": "BINANCE",
  "market": "FUTURES",
  "symbol": "BTCUSDT",
  "data": {
    "bids": [ [65432.1, 0.85, 2, 1716680000000, 0.0123] ],
    "asks": [ ]
  }
}
```

`data` holds the **current top levels of that one book**, up to 5 per side, each a positional
array (§3.5). It is the full state
of the book, not a delta: overwrite the stored `bids`/`asks` with these arrays.

**How to treat it**: upsert by `(exchange, market, symbol)`. If the book exists, replace its levels.
If it doesn't, create it. There is no separate "add" message; the first `DEPTH` you receive for a
book is how it enters.

### 3.4 `DEPTH` with `"data": null` — an order book left the feed

```json
{ "type": "DEPTH", "exchange": "BINANCE", "market": "FUTURES", "symbol": "BTCUSDT", "data": null }
```

The book no longer has a notable level. Usually its last tier-1+ level was filled, cancelled, or
drifted out of range. It also happens when the book loses sync with the exchange or the instrument
is delisted.

**How to treat it**: remove that book from local state **immediately**. Removal is often temporary:
if a notable level reappears, a new `DEPTH` with `data` brings the book back. A removal for a book
you don't have is possible and is a no-op.

### 3.5 Level tuple (entries in `bids` / `asks`)

A level is an **array of 5 numbers in fixed positions**, not an object. Field names would repeat
in every level and roughly double the payload, so they are left out (the same style Binance uses
for its depth levels).

```json
[65432.1, 0.85, 2, 1716680000000, 0.0123]
```

| Index | Name | Type | Meaning |
|---|---|---|---|
| `0` | `price` | number | Price level. |
| `1` | `quantity` | number | Size resting at that price, in base-asset units. |
| `2` | `tier` | integer | Importance, **1–4 inclusive** (4 is the most important). Tier 0 is never sent. Drive visual emphasis from it. |
| `3` | `firstSeenMillis` | integer | Unix epoch **milliseconds** when the level was first seen. Age = `Date.now() - firstSeenMillis`. |
| `4` | `distance` | number | **Fractional** distance from mid-price: `0.0123` means **1.23%**. Rounded to 4 decimals (§3.5.1). |

Read the tuple by index. Decode it into an object at the edge if the rest of the UI prefers named
fields:

```js
const toLevel = ([price, quantity, tier, firstSeenMillis, distance]) =>
  ({ price, quantity, tier, firstSeenMillis, distance });
```

The order is part of the contract. A new field, if ever added, is **appended** at the end;
existing positions never move. Ignore any extra trailing elements.

`bids` is the buy side, `asks` the sell side. Each array is ordered by importance: highest tier
first, then larger notional (`price × quantity`), then closer to mid-price.

Each side has **0 to 5** levels. Either side can be empty, but a non-null `data` always has at
least one level on some side (a book with nothing notable is removed instead). Iterate whatever is
there; don't assume 5.

#### 3.5.1 Formatting `distance`

`distance` arrives as a fraction rounded to **4 decimals**, i.e. a resolution of 0.01%: `0.0123`
means 1.23%. A level very close to mid-price may arrive as `0` or `0.0`. Formatting is the
client's job. For a percent string, multiply by 100 and round at render time:

```js
const pct = (level[4] * 100).toFixed(2); // distance; "1.23" → render "1.23%"
```

Always format with `toFixed`: `0.0007 * 100` is `0.06999999999999999` in floating point. More
than 2 decimals of percent carries no extra information.

Numbers are plain JSON numbers and may use exponent notation for very small or large values
(`1.0E-4`). `JSON.parse` handles that.

### 3.6 Instrument identity — `exchange`, `market`, `symbol`

| Field | Values | Notes |
|---|---|---|
| `exchange` | `"BINANCE"`, `"MEXC"` | More will be added. Treat it as an open set of strings, not a fixed enum. |
| `market` | `"SPOT"`, `"FUTURES"` | |
| `symbol` | e.g. `"BTCUSDT"` | Normalized `BASEQUOTE`. Same spelling as the rules API; never an exchange-native form like `BTC_USDT`. |

The same `symbol` + `market` on two exchanges are **independent books**. Key local state on **all
three** fields; `symbol` + `market` alone merges two exchanges into one flickering row.

```js
const key = (m) => `${m.exchange}:${m.market}:${m.symbol}`;   // "BINANCE:FUTURES:BTCUSDT"
```

This matches the key recommended in [`ticker-list-api.md`](./ticker-list-api.md) §4, so feed rows
line up with picker entries.

Custom rules are **exchange-independent**: a rule for `BTCUSDT` / `SPOT` applies to `BTCUSDT` spot
on every exchange. To relate a feed row to a rule, use `symbol` + `market` without `exchange`.

### 3.7 Delivery guarantees

- **Gap-free while connected.** The server never skips messages for an open socket: it either
  delivers everything in order or disconnects (§6.1). Messages carry no sequence number because
  none is needed. After any reconnect the new `SNAPSHOT` resets state.
- **At most one `DEPTH` per book per tick.** Changes within a ~100ms window are merged; you get the
  net result.
- **Redundant messages are possible and harmless.** Right after a `SNAPSHOT` you may receive a
  `DEPTH` repeating what the snapshot already showed, or a `DEPTH` with `data: null` for a book the
  snapshot didn't contain. Upsert and remove-if-present handle both; don't treat them as errors.

---

## 4. Handling messages

### 4.1 Reference handler

Write one handler per `type` and use it for live messages and snapshot entries alike:

```js
const key = (m) => `${m.exchange}:${m.market}:${m.symbol}`;

function onEnvelope(msg) {
  switch (msg.type) {
    case "DEPTH":
      if (msg.data === null) {
        books.delete(key(msg));                 // left the feed (no-op if absent)
      } else {
        books.set(key(msg), {                   // upsert: create or replace levels
          exchange: msg.exchange, market: msg.market, symbol: msg.symbol,
          bids: msg.data.bids, asks: msg.data.asks,
        });
      }
      break;
    default:
      break;                                    // unknown type: ignore (§4.2)
  }
}

function onMessage(msg) {
  if (msg.type === "SNAPSHOT") {
    books.clear();                              // and any other per-type state
    for (const entry of msg.data) onEnvelope(entry);
  } else {
    onEnvelope(msg);
  }
}

ws.onmessage = (e) => onMessage(JSON.parse(e.data));
```

### 4.2 Unknown types

New `type`s will be added without a coordinated frontend release. **Ignore any `type` you don't
recognise**, live or inside `SNAPSHOT.data`. Don't throw, log noisily, or close the socket.

---

## 5. Client → server

There is exactly **one** supported message, sent as a raw string (not JSON):

| Send | Effect |
|---|---|
| `SNAPSHOT_REQUEST` | The server sends a fresh `SNAPSHOT` on its next tick (~100ms). |

```js
ws.send("SNAPSHOT_REQUEST");
```

Use it when local state may have drifted or the UI wants a hard resync (e.g. the order-book panel
is re-opened). Any other message is silently ignored. You don't need it after editing rules; that
snapshot is pushed automatically.

---

## 6. Connection lifecycle

### 6.1 Slow-client eviction

Each session has a bounded send queue: 32 ticks, about 3.2s of backlog. If the client stops reading
(stalled tab, dead network) and the queue fills, the server **disconnects** instead of buffering
without limit or dropping messages. The socket closes, typically with **1001 (GOING_AWAY)**.
Reconnect as for any disconnect; the new snapshot restores state.

### 6.2 Reconnection strategy

1. On `close` (any code) or `error`, reconnect with **exponential backoff** plus jitter (e.g. start
   ~1s, cap ~30s). Never retry in a tight loop.
2. Before reconnecting, make sure the access token is valid; refresh it if needed.
3. On **1008**, check `event.reason`:
   - `"Subscription required"` → **stop** and show the paywall.
   - otherwise → refresh the token, then reconnect. If the refresh fails, send the user to login.
4. After reconnecting, a `SNAPSHOT` arrives automatically. Rebuild from it; don't try to resume.

```js
let backoff = 1000;

function connect() {
  const ws = new WebSocket(`wss://tc-screener.com/ws?token=${encodeURIComponent(getAccessToken())}`);

  ws.onopen = () => { backoff = 1000; };
  ws.onmessage = (e) => onMessage(JSON.parse(e.data));
  ws.onclose = (e) => {
    if (e.code === 1008) {
      if (e.reason === "Subscription required") { showPaywall(); return; }
      refreshTokenThen(connect);
      return;
    }
    setTimeout(connect, backoff + Math.random() * 500);
    backoff = Math.min(backoff * 2, 30000);
  };
}
```

### 6.3 Close codes

| Code | Reason | Meaning | Client action |
|---|---|---|---|
| 1008 | `Missing token` / `Invalid or expired token` | Auth failed at connect | Refresh token, reconnect. Refresh fails → login. |
| 1008 | `Subscription required` | No active trial/subscription | Show paywall. Don't reconnect until the user pays. |
| 1001 | — | Slow-client eviction, or server shutdown/deploy | Reconnect with backoff. |
| 1006 / other | — | Network drop | Reconnect with backoff. |

---

## 7. Quick reference

**Connect**: `wss://<host>/ws?token=<accessJwt>`. Needs an active trial/subscription (admins pass).

**Envelope** (every message except `SNAPSHOT`): `{"type", "exchange", "market", "symbol", "data"}`.

| `type` | `data` | Client action |
|---|---|---|
| `SNAPSHOT` | Array of envelopes, exactly as sent live | Clear all state, then handle each entry. |
| `DEPTH` | `{ bids, asks }` | Upsert the `(exchange, market, symbol)` book. |
| `DEPTH` | `null` | Remove that book (no-op if absent). |
| anything else | — | Ignore. |

**Identity**: `exchange` (open set), `market` (`SPOT` / `FUTURES`), `symbol` (normalized
`BASEQUOTE`). Key state on all three.

**Levels**: positional arrays `[price, quantity, tier, firstSeenMillis, distance]`: `tier` 1–4,
`firstSeenMillis` epoch ms, `distance` a fraction rounded to 4 decimals (×100 then `.toFixed(2)`
for `%`). 0–5 per side, ordered by importance.

**Client → server**: `SNAPSHOT_REQUEST` (raw string) forces a resync.

**On disconnect**: reconnect with backoff and rebuild from the pushed `SNAPSHOT`. On 1008 refresh
the token, unless the reason is `Subscription required` (show the paywall).

### TypeScript shapes

```ts
type Market = 'SPOT' | 'FUTURES';

/** Wire tuple: [price, quantity, tier, firstSeenMillis (epoch ms), distance (fraction, 4 dp)] */
type LevelTuple = [
  price: number,
  quantity: number,
  tier: 1 | 2 | 3 | 4,
  firstSeenMillis: number,
  distance: number,
];

interface InstrumentIdentity {
  exchange: string;          // open set, e.g. "BINANCE", "MEXC"
  market: Market;
  symbol: string;            // normalized BASEQUOTE
}

interface DepthData {
  bids: LevelTuple[];        // 0–5, most important first
  asks: LevelTuple[];
}

interface DepthMessage extends InstrumentIdentity {
  type: 'DEPTH';
  data: DepthData | null;    // null = remove the book
}

/** Any message except SNAPSHOT. Types not handled here must be ignored. */
interface UnknownEnvelope extends InstrumentIdentity {
  type: string;
  data: unknown;
}

type Envelope = DepthMessage | UnknownEnvelope;

interface SnapshotMessage {
  type: 'SNAPSHOT';
  data: Envelope[];          // same shapes as live messages; never DEPTH with data: null
}

type FeedMessage = SnapshotMessage | Envelope;
```
