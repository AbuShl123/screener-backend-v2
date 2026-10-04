# Ticker List API — `GET /api/tickers`

> **Audience**: frontend engineers/agents who need the list of instruments the screener tracks, for
> example the ticker picker in Settings (custom rules, muted tickers). This document is the contract
> for `GET /api/tickers`.
>
> For the live order-book stream see [`websocket-feed-api.md`](./websocket-feed-api.md). For the
> rules endpoints that validate against this list see
> [`classification-rule-api.md`](./classification-rule-api.md).

---

## 0. What changed (breaking)

The response was redesigned for multi-exchange support. **The old shape is gone, and no
compatibility fields remain.** A client still validating the old schema will fail to parse the new
response.

| Before | Now |
|---|---|
| One row per **symbol**, with `hasFutures` / `hasSpot` flags | One row per **instrument**, meaning one `(venue, symbol)` pair. A symbol traded on both spot and futures appears **twice**. |
| `tickers: [{ symbol, hasFutures, hasSpot }]` | `instruments: [{ id, venue, symbol }]` |
| `spotCount`, `futuresCount` | `byVenue: { "BINANCE_SPOT": n, "BINANCE_FUTURES": m }` |
| Sorted alphabetically by `symbol` | Sorted by venue, then by `symbol` |
| `total` = number of symbols | `total` = number of instruments (spot + futures rows) |

Migration in one line: the old code built "a FUTURES row for every ticker plus a SPOT row if
`hasSpot`". The server now does that expansion for you. Map each `instruments[]` row directly to one
book, as described in §4.

---

## 1. Request

```
GET /api/tickers
Authorization: Bearer <accessToken>
```

- No body, no query parameters.
- Requires a **valid JWT** (any logged-in user). An **active subscription is not required**, so a
  user on the paywall can still load the picker.
- Missing or invalid token → `401` with the standard `ApiError` shape.

---

## 2. Response `200 OK`

```json
{
  "total": 5,
  "byVenue": {
    "BINANCE_SPOT": 2,
    "BINANCE_FUTURES": 3
  },
  "instruments": [
    { "id": 0, "venue": "BINANCE_SPOT",    "symbol": "BTCUSDT" },
    { "id": 2, "venue": "BINANCE_SPOT",    "symbol": "ETHUSDT" },
    { "id": 1, "venue": "BINANCE_FUTURES", "symbol": "BTCUSDT" },
    { "id": 4, "venue": "BINANCE_FUTURES", "symbol": "DOGEUSDT" },
    { "id": 3, "venue": "BINANCE_FUTURES", "symbol": "ETHUSDT" }
  ]
}
```

### 2.1 Top-level fields

| Field | Type | Meaning |
|---|---|---|
| `total` | integer | Number of rows in `instruments` (all venues combined). |
| `byVenue` | object `{ [venue]: integer }` | Instrument count per venue. Only venues with at least one instrument appear as keys. Don't assume a fixed key set. |
| `instruments` | array | Every tracked instrument. Sorted by venue (`BINANCE_SPOT` first, then `BINANCE_FUTURES`), then alphabetically by `symbol` within each venue. |

### 2.2 `instruments[]` fields

| Field | Type | Meaning |
|---|---|---|
| `id` | integer | **Debugging only. Do not use it.** It is a server-process-local index that is reassigned on every backend restart. Never store it, cache it, key state on it, or send it back to the server. |
| `venue` | string | The exchange and market the instrument trades on, combined into one value. See §3. |
| `symbol` | string | The trading pair, e.g. `"BTCUSDT"`. See §3.2. |

The identity of an instrument is **`(venue, symbol)`**. `BTCUSDT` on `BINANCE_SPOT` and `BTCUSDT`
on `BINANCE_FUTURES` are two separate instruments, each with its own order book.

---

## 3. `venue` and `symbol`

### 3.1 `venue` values

`venue` is an `EXCHANGE_MARKET` value. Today there are two:

| `venue` | `exchange` | `market` |
|---|---|---|
| `BINANCE_SPOT` | `BINANCE` | `SPOT` |
| `BINANCE_FUTURES` | `BINANCE` | `FUTURES` |

The WebSocket feed and the rules API **do not use `venue`**. They carry `exchange` and `market` as
two separate fields. Use a lookup table like the one above to convert. More venues will be added
when more exchanges are supported, so treat `venue` as an open string set. If you meet an unknown
value, either skip the row or split it on the **last** underscore (`EXCHANGE` / `MARKET`), since
`market` is always `SPOT` or `FUTURES`.

```ts
const VENUES: Record<string, { exchange: string; market: 'SPOT' | 'FUTURES' }> = {
  BINANCE_SPOT:    { exchange: 'BINANCE', market: 'SPOT' },
  BINANCE_FUTURES: { exchange: 'BINANCE', market: 'FUTURES' },
};

function parseVenue(venue: string) {
  const known = VENUES[venue];
  if (known) return known;
  const i = venue.lastIndexOf('_');
  return { exchange: venue.slice(0, i), market: venue.slice(i + 1) as 'SPOT' | 'FUTURES' };
}
```

### 3.2 `symbol` spelling

`symbol` is the **exchange's native spelling** of the pair. For every Binance instrument this is the
plain `BASEQUOTE` form (`"BTCUSDT"`), which is the same spelling the WebSocket feed and the rules
API use. You can match on it directly today.

> **Heads-up for future exchanges**: some exchanges use different native spellings (for example
> `BTC_USDT`). When such an exchange is added, this field may stop matching the normalized
> `BASEQUOTE` symbol in the feed and the rules API. This document will be updated when that
> happens.

---

## 4. Using the list in the UI

**Building the picker / book universe.** Map each row to one book:

```ts
const pool = res.instruments.map(({ venue, symbol }) => {
  const { exchange, market } = parseVenue(venue);
  return { exchange, market, symbol, key: `${exchange}:${market}:${symbol}` };
});
```

`key` matches the `(exchange, market, symbol)` key the WebSocket feed recommends
(see [`websocket-feed-api.md`](./websocket-feed-api.md) §3.7). Don't add a SPOT or FUTURES row
yourself: if a row is not in `instruments`, that market isn't tracked for the symbol.

**Driving the custom-rule form.** Rules are **exchange-independent**. A rule targets
`(symbol, market)` and applies on every exchange. For the rule picker:

- Offer `market: "SPOT"` for a symbol only if a `*_SPOT` row exists for it, and `FUTURES` only if a
  `*_FUTURES` row exists. `PUT /api/rules` and `DELETE /api/rules` reject a `(symbol, market)` that
  isn't tracked with `400` (`"<SYMBOL> is not tracked on market <MARKET>"`).
- Once there are several exchanges, de-duplicate by `(symbol, market)` so the same pair isn't
  offered once per exchange.

**Today's universe.** Every tracked Binance instrument is USDT-quoted. Every tracked symbol has a
`BINANCE_FUTURES` row (an active USDT perpetual contract is required for inclusion). Some also have
a `BINANCE_SPOT` row. Spot-only symbols are not tracked. Treat this as a description of the current
data, not a guarantee: write the UI against the rows, not against these rules.

**Freshness.** The backend refreshes the instrument universe every 4 hours. Re-fetch on page load,
or when the Settings modal opens, rather than caching the result indefinitely.

> **Known limitation**: an instrument delisted since the last backend restart can still appear in
> this list, and the rules API will still accept it as a target, even though it no longer streams
> and won't show up in the feed. It disappears from the list after the next backend restart.

---

## 5. Quick reference

```
GET /api/tickers            (JWT required, subscription not required)

200 → {
  total:       number,
  byVenue:     { [venue: string]: number },
  instruments: { id: number /* ignore */, venue: string, symbol: string }[]
}
```

- One row per `(venue, symbol)`. A symbol appears once per venue it trades on.
- `venue` → `exchange` + `market` via a lookup table (`BINANCE_SPOT` → `BINANCE` / `SPOT`).
- `id` is debugging-only. Never key on it.
- Key books as `${exchange}:${market}:${symbol}`, the same as the WebSocket feed.

Zod schema for the new shape:

```ts
export const instrumentSchema = z.object({
  id: z.number(),        // debug only — do not use
  venue: z.string(),     // e.g. "BINANCE_SPOT"; open set
  symbol: z.string(),
});

export const tickersResponseSchema = z.object({
  total: z.number(),
  byVenue: z.record(z.string(), z.number()),
  instruments: z.array(instrumentSchema),
});
```
