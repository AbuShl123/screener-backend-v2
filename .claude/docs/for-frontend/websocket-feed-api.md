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
  full current state and the most recent trade alerts. Live messages after it are applied on top.
- Two data types:
  - **`DEPTH`**: classified order-book levels (state: a book is upserted and removed).
  - **`TRADE`**: a large executed trade (an event: it happened once and never changes) (§3.8).
- Bursts (many same-side trades over seconds), spike alerts and order clusters will arrive later as
  new `type`s on the same socket. **Clients must ignore types they don't know** (§4.2).

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
      "data": { "bids": [ /* levels */ ], "asks": [ /* levels */ ] } },
    { "type": "TRADE", "exchange": "BINANCE", "market": "FUTURES", "symbol": "SOLUSDT",
      "data": { "id": "BINANCE_FUTURES:SOLUSDT:3653778376", "side": "SELL", /* … §3.8 */ } }
  ]
}
```

The only message without instrument fields, since it covers many instruments. Sent:

- once, automatically, right after connecting;
- after the client sends `SNAPSHOT_REQUEST` (§5);
- after the user's classification rules change.

Each entry of `data` is **a complete envelope, byte-for-byte what you would receive live** for
that instrument. Run each one through the same per-type handler as live messages (§4).

**How to treat it** depends on the type:

- **Depth**: replace your depth state. Clear all books, then handle each `DEPTH` entry. Any book
  you had that isn't in the snapshot is gone.
- **Trades**: **don't clear**. Trade alerts are history, not state: merge the snapshot's `TRADE`
  entries into your list by `id` and don't notify the user about them (§3.8.5).

Notes:

- `data` may be an empty array (nothing notable anywhere right now, no recent trade alerts).
- Entries come in no particular order and mix types. Sort trade alerts by `time` yourself.
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
- **Trade alerts are never merged.** Each alert is sent live exactly once, in the tick after it is
  detected; several may arrive in one tick, including several for one symbol. A reconnect replays
  the most recent ones in the `SNAPSHOT` (§3.8.5). Merge by `id` anyway: it makes any repeat harmless.

### 3.8 `TRADE` — a large trade was executed

```json
{
  "type": "TRADE",
  "exchange": "BINANCE",
  "market": "FUTURES",
  "symbol": "SOLUSDT",
  "data": {
    "id": "BINANCE_FUTURES:SOLUSDT:3653778376",
    "side": "SELL",
    "time": 1794931200123,
    "notional": 612455.4,
    "qty": 4380.0,
    "avgPrice": 139.83,
    "firstPrice": 139.91,
    "lastPrice": 139.74,
    "levels": 7,
    "threshold": 500000
  }
}
```

One **taker order** (a market order, or a limit order that crossed the spread) executed for at
least the symbol's threshold. Someone actually paid this much; unlike a depth level, it cannot be
cancelled. A trade alert is an **event, not state**: it is never updated or removed later.

Trade alerts exist today for **Binance spot and futures**. Other exchanges will follow with the
same shape. Every session receives every alert: there is no per-user filtering on the server yet,
so filters (exchange, market, symbol, size) are applied in the UI. Custom classification rules do
not affect trades.

`data` is an object with named fields (alerts are rare, so unlike depth levels there is no tuple):

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Stable identity of the alert, the same across reconnects and server restarts. Key and deduplicate by it. **Opaque**: don't parse it. |
| `side` | `"BUY"` \| `"SELL"` | The **aggressor's** side (§3.8.1). |
| `time` | integer | When the trade executed on the exchange, Unix epoch **ms**. Not when it was detected or sent (§3.8.4). |
| `notional` | number | Executed value in the **quote** asset (USDT for every instrument with trade alerts today), 2 decimals. The main size figure. |
| `qty` | number | Executed quantity in the **base** asset (`SOL` above). |
| `avgPrice` | number | Volume-weighted average execution price, `notional / qty`. Computed, so it can carry long decimals: format it (§3.8.6). |
| `firstPrice` | number | Price of the first level the order took. |
| `lastPrice` | number | Price of the last level the order took. Equal to `firstPrice` when `levels` is 1. |
| `levels` | integer ≥ 1 | How many price levels the order took (§3.8.2). |
| `threshold` | number | The minimum notional this symbol needed to alert, in the same quote asset. `notional ≥ threshold` always (§3.8.3). |

New fields may be added to `data` later; ignore fields you don't know.

#### 3.8.1 Side

Every trade has a buyer and a seller. `side` is the side of the one who **crossed the spread** (the
taker):

- `BUY`: the taker bought, consuming asks (offers). Price is pushed up.
- `SELL`: the taker sold, consuming bids. Price is pushed down.

Label it as "bought" / "sold" (or "taker buy" / "taker sell"), not as bullish/bearish.

#### 3.8.2 Sweeps: `levels`, `firstPrice`, `lastPrice`

An order bigger than the best level eats it and continues to the next price, then the next: a
**sweep**. The exchange reports each price level as a separate trade; the server joins them back
into one alert.

- `levels` is the number of price levels taken. Each is a different price, moving in the taker's
  direction: for `BUY` `lastPrice > firstPrice`, for `SELL` `lastPrice < firstPrice`.
- `levels: 1` is one large fill at one price: the order hit one level big enough to absorb it (a
  wall, or hidden size). `firstPrice == lastPrice == avgPrice`.
- `levels > 1` means the order swept the book. Show it (e.g. "swept 7 levels").
- **Price impact** of the sweep, as a fraction (`0.0012` = 0.12%):

  ```js
  const impact = Math.abs(data.lastPrice / data.firstPrice - 1);
  const pct = (impact * 100).toFixed(2);   // "0.12" → "0.12%"
  ```

  It is how far the one order moved the price. A large `levels` with a small impact means a dense
  book; few levels with a large impact means a thin one.

Limits of what the exchange reports: on futures, Binance merges same-price fills over about 100ms,
so rarely an alert is two or more orders the exchange itself merged. On spot, two orders at the same
price in the same millisecond (one algorithm's child orders) arrive as two alerts. Neither can be
told apart from the data.

#### 3.8.3 Size: `notional`, `threshold`

Thresholds differ per symbol, from $30K on most coins to $5M on BTC futures, so that each symbol
alerts at a readable rate. Raw `notional` therefore doesn't compare across symbols: $600K is routine
on BTC and huge on a small coin. Use the **relative size** for emphasis, the way `tier` drives
emphasis for depth:

```js
const relative = data.notional / data.threshold;   // always ≥ 1
```

Suggested buckets (tune freely): `1–2×`, `2–5×`, `5–10×`, `≥ 10×`. Show `notional` itself as the
headline number.

`threshold` is the value the alert was judged against when it fired. Thresholds are server
configuration and may change between releases; with per-user thresholds (planned) it will become the
user's own value. Always read it from the alert, never hardcode it.

#### 3.8.4 Time, delay and order

- `time` is the exchange's execution time. Show age from it (`Date.now() - time`).
- **Delay**: an alert usually arrives a few hundred ms after `time`. A sweep is only known to be
  over when that symbol trades again, and Binance futures sends the last part of a sweep up to
  ~150ms late. On a rarely traded symbol the delay can be several seconds.
- **Arrival order is detection order, not execution order.** A futures alert can arrive after a spot
  alert that executed later. Keep your list sorted by `time` (then `id` to break ties), don't just
  prepend.

#### 3.8.5 Snapshot replay and deduplication

Every `SNAPSHOT` carries the **most recent 100 trade alerts** across all exchanges and symbols (fewer
right after a server restart: alerts are kept in memory only).

- **Merge, don't clear.** Add each `TRADE` entry whose `id` you don't have; skip the ones you have.
  Keep alerts you already had that aren't in the snapshot. The ids are stable, so a snapshot after a
  reconnect or a server restart never duplicates anything.
- **Don't notify for snapshot entries.** They are history: put them in the list silently. Only live
  `TRADE` messages may trigger a toast or sound. Otherwise every reconnect, rule edit or
  `SNAPSHOT_REQUEST` would replay up to 100 notifications.
- Alerts that fired while you were disconnected are recovered only if they are still among the
  latest 100. In a quiet market that covers roughly the last 20–40 minutes, in a volatile one a few
  minutes.
- Cap your own list (e.g. a few hundred alerts, or by age). The server never tells you to remove an
  alert.

#### 3.8.6 Presentation notes (suggestions, not contract)

- **Rate**: measured over one afternoon, roughly 150–250 alerts/hour in total in quiet hours and
  ~1,000/hour in a sell-off, arriving in bursts during sharp moves. About 80% are futures.
  A list or tape absorbs that; toasts need a user-set minimum (relative size or notional), a mute
  switch and client-side rate limiting.
- **Prices**: `firstPrice` and `lastPrice` are exchange prices and print cleanly. Format `avgPrice`
  with the number of decimals of `firstPrice` / `lastPrice` (or `toPrecision`), never raw.
- **Numbers** may use exponent notation (`2.5E7` for $25M); `JSON.parse` handles it. Format
  `notional` compactly at render time ($612.5K, $25M).
- A useful row: side colour · `exchange` `market` `symbol` · notional · relative size · age, with
  "swept N levels, firstPrice → lastPrice (impact %)" when `levels > 1`.

---

## 4. Handling messages

### 4.1 Reference handler

Write one handler per `type` and use it for live messages and snapshot entries alike. The handler
is told whether the message came from a snapshot, so trade alerts can skip notifications:

```js
const key = (m) => `${m.exchange}:${m.market}:${m.symbol}`;

function onEnvelope(msg, fromSnapshot) {
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
    case "TRADE":
      if (trades.has(msg.data.id)) break;       // already have it (snapshot replay)
      trades.set(msg.data.id, {
        exchange: msg.exchange, market: msg.market, symbol: msg.symbol, ...msg.data,
      });                                       // keep sorted by time; cap the size (§3.8.5)
      if (!fromSnapshot) notifyTrade(msg);      // toasts/sounds for live alerts only
      break;
    default:
      break;                                    // unknown type: ignore (§4.2)
  }
}

function onMessage(msg) {
  if (msg.type === "SNAPSHOT") {
    books.clear();                              // depth is state: replace it
                                                // trades are history: NOT cleared, merged by id
    for (const entry of msg.data) onEnvelope(entry, true);
  } else {
    onEnvelope(msg, false);
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
| `SNAPSHOT` | Array of envelopes, exactly as sent live | Clear depth state, then handle each entry. Trade alerts are merged by `id`, not cleared, and not notified. |
| `DEPTH` | `{ bids, asks }` | Upsert the `(exchange, market, symbol)` book. |
| `DEPTH` | `null` | Remove that book (no-op if absent). |
| `TRADE` | `{ id, side, time, notional, qty, avgPrice, firstPrice, lastPrice, levels, threshold }` | Add to the alert list if the `id` is new; notify only when live. |
| anything else | — | Ignore. |

**Identity**: `exchange` (open set), `market` (`SPOT` / `FUTURES`), `symbol` (normalized
`BASEQUOTE`). Key state on all three.

**Levels**: positional arrays `[price, quantity, tier, firstSeenMillis, distance]`: `tier` 1–4,
`firstSeenMillis` epoch ms, `distance` a fraction rounded to 4 decimals (×100 then `.toFixed(2)`
for `%`). 0–5 per side, ordered by importance.

**Trades**: one large taker order. `side` is the aggressor's; `levels > 1` is a sweep from
`firstPrice` to `lastPrice`; relative size is `notional / threshold`; sort by `time`, dedupe by `id`.

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

interface TradeData {
  id: string;                // opaque, stable; dedupe key
  side: 'BUY' | 'SELL';      // aggressor's side
  time: number;              // exchange execution time, epoch ms
  notional: number;          // quote asset (USDT), 2 dp
  qty: number;               // base asset
  avgPrice: number;          // notional / qty; format before display
  firstPrice: number;
  lastPrice: number;         // === firstPrice when levels === 1
  levels: number;            // ≥ 1; > 1 = sweep
  threshold: number;         // notional >= threshold
}

interface TradeMessage extends InstrumentIdentity {
  type: 'TRADE';
  data: TradeData;           // never null
}

/** Any message except SNAPSHOT. Types not handled here must be ignored. */
interface UnknownEnvelope extends InstrumentIdentity {
  type: string;
  data: unknown;
}

type Envelope = DepthMessage | TradeMessage | UnknownEnvelope;

interface SnapshotMessage {
  type: 'SNAPSHOT';
  data: Envelope[];          // same shapes as live messages; never DEPTH with data: null;
                             // up to 100 TRADE entries (history: merge, don't notify)
}

type FeedMessage = SnapshotMessage | Envelope;
```
