# API Documentation: Orderbook Classification Rules

## Background: What Is Orderbook Classification?

A cryptocurrency **order book** is a live list of all open buy (bid) and sell (ask) orders for a
trading pair. Each order has a **price** and a **quantity** (how many coins are being offered at
that price). The backend tracks these order books in real time for all active Binance tickers.

**Classification** is the process of assigning a **tier** (a visual importance label) to each
price level in the order book. Not every order is interesting — a classification tier helps the
user focus on the most significant price levels.

Each price level is evaluated on two dimensions:

1. **Notional value** (`price × quantity`) — the USD value of the order. Bigger orders are more
   significant.
2. **Distance from mid-price** — how far the order is from the current market price
   (`|levelPrice − midPrice| / midPrice`). Orders closer to the current price are more
   significant. A distance of `0.01` means 1% away from mid-price.

### The Four Tiers

A tier is defined by **two thresholds a level must clear simultaneously**: a **minimum
notional** (size) and a **maximum distance** (proximity to mid-price). The key — and
counter-intuitive — property is the *direction* in which those thresholds move as the tier
number rises: **both** grow together.

| Tier | Min notional (size) | Max distance (proximity) | What it captures |
|------|---------------------|--------------------------|------------------|
| **Tier 1** | lowest | tightest — closest to the spread | Near-spread pressure: a level hugging the current price qualifies even at modest size |
| **Tier 2** | higher | wider | Sizable orders a bit further out |
| **Tier 3** | higher still | wider still | Large orders / walls further from price |
| **Tier 4** | highest | widest | Only astronomically large walls, allowed farthest from price |
| *(Tier 0)* | — | — | Invisible — clears no tier; not sent to the client |

**Do not read this as "Tier 1 = most important, Tier 4 = least important."** The notional
requirement **and** the allowed distance window both *increase* with the tier number. The
intuition: the **closer** an order sits to the current price, the **less size** it needs to
matter (Tier 1); the **farther** away it sits, the **more enormous** it must be to still be
worth showing (Tier 4). Distance erodes relevance unless sheer size overrules it.

A level is assigned a tier only when its notional **and** distance both satisfy that tier's
thresholds. If it qualifies for several tiers, it takes the **highest-numbered** match — so a
$100M order sitting right at the spread resolves to **Tier 4** (the "big wall"
classification), not Tier 1.

---

## The Default Classification Rule

The backend ships with a global default rule used for all tickers that the user has **not**
customised. It has two sub-tables. The live values can always be fetched from
`GET /api/rules/default` (see below).

**Standard tickers** (default thresholds):

| Tier | Min Notional (USD) | Max Distance from Mid |
|------|-------------------|-----------------------|
| 4    | $10,000,000       | 5.0% (`0.05`)         |
| 3    | $1,000,000        | 2.0% (`0.02`)         |
| 2    | $500,000          | 1.0% (`0.01`)         |
| 1    | $200,000          | 0.5% (`0.005`)        |

**High-liquidity tickers** (BTCUSDT, ETHUSDT, SOLUSDT — deeper books, so tighter thresholds):

| Tier | Min Notional (USD) | Max Distance from Mid  |
|------|-------------------|------------------------|
| 4    | $100,000,000      | 2.5% (`0.025`)         |
| 3    | $30,000,000       | 1.0% (`0.01`)          |
| 2    | $10,000,000       | 0.5% (`0.005`)         |
| 1    | $3,000,000        | 0.25% (`0.0025`)       |

A user's custom rule **overrides** the default entirely for a specific `(symbol, market)` pair.
Tickers without a custom rule always use the default.

---

## Per-User Custom Rules: How They Work

A user can define their own thresholds for any `(symbol, market)` combination. Key points:

- The custom rule **replaces** the default for that ticker — it is not merged with it.
- Each custom rule must define **all four tiers, 1 through 4** — partial tier sets (e.g. only
  tiers 1+2, or tiers 1+2+3) are rejected. There is no way to override just some tiers and fall
  back to the default for the rest.
- `(symbol, market)` is the unit of granularity: a user can have different thresholds for
  `BTCUSDT SPOT` vs `BTCUSDT FUTURES`.
- The same rule body can be applied to multiple tickers in one request.
- **Live effect**: rule edits apply immediately to any already-connected WebSocket session — no
  reconnect needed. Writing a rule (`PUT`/`DELETE`) rebuilds the affected user's classification
  context server-side, retargets every open session for that user, and pushes a fresh snapshot on
  the next broadcaster tick (≤100ms later).

---

## Authentication

All `/api/rules/**` endpoints require a valid **Bearer JWT** in the `Authorization` header —
including `GET /api/rules/default`, which is not in the security allowlist despite returning
non-personalized data:

```
Authorization: Bearer <access_token>
```

The user's identity is always derived from the JWT — the request body never contains a user ID.

**Missing or invalid token**: Spring Security rejects the request before it reaches the
controller, returning an **empty-body `403 Forbidden`** — not `401`, and not the JSON error shape
described below. Treat any `403` with no body as "not authenticated" and prompt a re-login /
token refresh.

---

## Access Requirement (Active Subscription)

`GET /api/rules`, `GET /api/rules/{symbol}/{market}`, `PUT /api/rules`, and `DELETE /api/rules`
additionally require the caller to currently have access (an unexpired free trial **or** a paid
subscription — see the monetization API docs for `AccessState`). This is checked *after*
authentication succeeds, so it's a distinct failure mode from the empty `403` above:

```json
{
  "message": "Active subscription required",
  "status": 403,
  "path": "/api/rules"
}
```

This is a normal, JSON-bodied `403` (unlike the empty-body auth failure) — distinguish the two by
checking for a response body. `ADMIN`-role users always bypass this check.

`GET /api/rules/default` is **not** gated by this check — any authenticated user can view the
default thresholds regardless of subscription state, so the UI can render the "what you'd be
overriding" table even for a lapsed user browsing the paywall.

---

## Base Paths

| Purpose | Base path |
|---------|-----------|
| Default rule info | `/api/rules` |
| User's custom rules | `/api/rules` |
| Active ticker list | `/api/tickers` |

---

## Fetching the Active Ticker List

Before a user can set a rule for a `(symbol, market)` pair, the frontend needs to show them which
tickers actually exist. `GET /api/tickers` returns the full set of tickers the backend is
currently tracking — this is also the source of truth used to validate `PUT`/`DELETE` targets
server-side (see [Per-Target Checks](#per-target-checks)), so anything not in this list will be
rejected by the rules endpoints.

### `GET /api/tickers`

Requires a valid JWT (see [Authentication](#authentication)) but **not** an active subscription —
any logged-in user can list tickers, same treatment as `GET /api/rules/default`.

**Request**: No body, no query parameters.

**Response `200 OK`**:

```json
{
  "total": 3,
  "spotCount": 2,
  "futuresCount": 3,
  "tickers": [
    { "symbol": "BTCUSDT", "hasFutures": true, "hasSpot": true },
    { "symbol": "DOGEUSDT", "hasFutures": true, "hasSpot": false },
    { "symbol": "ETHUSDT", "hasFutures": true, "hasSpot": true }
  ]
}
```

**Field meanings**:
- `total` — total number of tracked tickers
- `spotCount` — number of tickers that also have an active spot market (`hasSpot: true`)
- `futuresCount` — number of tickers with an active futures contract (currently equals `total`,
  since every tracked ticker requires an active futures contract to be included at all)
- `tickers` — alphabetically sorted by `symbol`
- `tickers[].symbol` — uppercase trading pair, e.g. `"BTCUSDT"`
- `tickers[].hasFutures` — `true` if a `FUTURES` market exists for this symbol. In practice this is
  always `true` — the backend only tracks tickers that have an active USDT-quoted, PERPETUAL,
  `TRADING`-status futures contract
- `tickers[].hasSpot` — `true` if a `SPOT` market **also** exists for this symbol. Spot-only
  tickers (no futures) are never tracked, so this is the flag that actually varies

**Using this to drive the rule form**:
- Only offer `market: "FUTURES"` for a ticker if `hasFutures` is `true` (always, in practice).
- Only offer `market: "SPOT"` for a ticker if `hasSpot` is `true` — submitting `SPOT` for a
  futures-only ticker fails the "market matches the symbol" check on `PUT /api/rules` (see
  [Per-Target Checks](#per-target-checks)) with a `400`.
- This list changes over time as tickers are listed/delisted (the backend refreshes it every
  3–4 hours). Re-fetch periodically, or at minimum on every fresh page load, rather than caching
  it indefinitely.

---

## Endpoints

---

### `GET /api/rules/default`

Returns the **server's default classification rule** — the two threshold tables and the list of
symbols that use the high-liquidity table. Requires a valid JWT (see [Authentication](#authentication))
but **not** an active subscription (see [Access Requirement](#access-requirement-active-subscription)) —
any logged-in user can view this, including a lapsed one. Use this to display the default
thresholds in the UI so the user understands what they are overriding.

**Request**: No body, no query parameters.

**Response `200 OK`**:

```json
{
  "normalTiers": [
    { "tier": 4, "minNotional": 10000000, "maxDistance": 0.05   },
    { "tier": 3, "minNotional": 1000000,  "maxDistance": 0.02   },
    { "tier": 2, "minNotional": 500000,   "maxDistance": 0.01   },
    { "tier": 1, "minNotional": 200000,   "maxDistance": 0.005  }
  ],
  "highLiquiditySymbols": ["BTCUSDT", "ETHUSDT", "SOLUSDT"],
  "highLiquidityTiers": [
    { "tier": 4, "minNotional": 100000000, "maxDistance": 0.025  },
    { "tier": 3, "minNotional": 30000000,  "maxDistance": 0.01   },
    { "tier": 2, "minNotional": 10000000,  "maxDistance": 0.005  },
    { "tier": 1, "minNotional": 3000000,   "maxDistance": 0.0025 }
  ]
}
```

**Field meanings**:
- `normalTiers` — tier thresholds that apply to all tickers **not** in `highLiquiditySymbols`
- `highLiquiditySymbols` — symbols that use the tighter `highLiquidityTiers` table
- `highLiquidityTiers` — tier thresholds for the high-liquidity symbols (tighter notional and
  distance requirements because those books are deeper and spreads are tighter)
- `tier` — integer 1–4
- `minNotional` — minimum USD notional (`price × quantity`) a level must have to match this tier
- `maxDistance` — maximum fractional distance from mid-price a level must be within to match this
  tier (`0.05` = 5%)

---

### `GET /api/rules`

Returns **all custom rules** the authenticated user has configured, grouped by `(symbol, market)`.

**Request**: No body, no query parameters.

**Response `200 OK`**:

```json
[
  {
    "symbol": "BTCUSDT",
    "market": "FUTURES",
    "tiers": [
      { "tier": 4, "minNotional": 5000000, "maxDistance": 0.04  },
      { "tier": 3, "minNotional": 1000000, "maxDistance": 0.02  },
      { "tier": 2, "minNotional": 500000,  "maxDistance": 0.01  },
      { "tier": 1, "minNotional": 200000,  "maxDistance": 0.005 }
    ]
  },
  {
    "symbol": "SOLUSDT",
    "market": "SPOT",
    "tiers": [
      { "tier": 4, "minNotional": 4000000, "maxDistance": 0.03 },
      { "tier": 3, "minNotional": 800000,  "maxDistance": 0.02 },
      { "tier": 2, "minNotional": 300000,  "maxDistance": 0.015 },
      { "tier": 1, "minNotional": 100000,  "maxDistance": 0.01 }
    ]
  }
]
```

Returns an **empty array** `[]` if the user has no custom rules configured.

**Field meanings**:
- `symbol` — trading pair, always uppercase (e.g. `"BTCUSDT"`)
- `market` — `"SPOT"` or `"FUTURES"`
- `tiers` — list of tier definitions (may be returned in any order; sort by `tier` descending for
  display)
- `tier` — integer 1–4
- `minNotional` — minimum USD notional for a level to match this tier
- `maxDistance` — maximum fractional distance from mid-price for a level to match this tier

---

### `GET /api/rules/{symbol}/{market}`

Returns the custom rule for **one specific ticker**, or `404` if none is configured.

**Path parameters**:
- `symbol` — e.g. `BTCUSDT` (case-insensitive; normalised to uppercase internally)
- `market` — `SPOT` or `FUTURES` (case-insensitive)

**Response `200 OK`**:

```json
{
  "symbol": "BTCUSDT",
  "market": "FUTURES",
  "tiers": [
    { "tier": 4, "minNotional": 5000000, "maxDistance": 0.04  },
    { "tier": 3, "minNotional": 1000000, "maxDistance": 0.02  },
    { "tier": 2, "minNotional": 500000,  "maxDistance": 0.01  },
    { "tier": 1, "minNotional": 200000,  "maxDistance": 0.005 }
  ]
}
```

**Response `404 Not Found`**: the user has not configured a rule for this pair. The default rule
applies — this is not an error state, just an absence of an override.

---

### `PUT /api/rules`

**Creates or replaces** custom rules for one or more tickers. This is a **bulk upsert**: one call
can apply the same rule to many tickers, or apply different rules to different tickers.

**Semantics**: for each `(symbol, market)` target, the existing rule (if any) is **completely
replaced** by the new tier set. This is not a patch — the entire tier set is overwritten
atomically. Every request must supply all four tiers (1 through 4); there is no way to update a
subset of tiers and leave the rest untouched.

**Request `Content-Type: application/json`**:

```json
{
  "assignments": [
    {
      "rule": {
        "tiers": [
          { "tier": 4, "minNotional": 5000000, "maxDistance": 0.04  },
          { "tier": 3, "minNotional": 1000000, "maxDistance": 0.02  },
          { "tier": 2, "minNotional": 500000,  "maxDistance": 0.01  },
          { "tier": 1, "minNotional": 200000,  "maxDistance": 0.005 }
        ]
      },
      "targets": [
        { "symbol": "BTCUSDT", "market": "FUTURES" },
        { "symbol": "ETHUSDT", "market": "FUTURES" },
        { "symbol": "SOLUSDT", "market": "SPOT" }
      ]
    }
  ]
}
```

The `assignments` array allows **multiple rule+targets pairs in one call**. Each entry applies one
rule body to all of its targets. To set different rules for different tickers, send multiple
assignments:

```json
{
  "assignments": [
    {
      "rule": {
        "tiers": [
          { "tier": 4, "minNotional": 5000000, "maxDistance": 0.04 },
          { "tier": 3, "minNotional": 1000000, "maxDistance": 0.03 },
          { "tier": 2, "minNotional": 500000,  "maxDistance": 0.02 },
          { "tier": 1, "minNotional": 100000,  "maxDistance": 0.01 }
        ]
      },
      "targets": [
        { "symbol": "BTCUSDT", "market": "FUTURES" }
      ]
    },
    {
      "rule": {
        "tiers": [
          { "tier": 4, "minNotional": 500000, "maxDistance": 0.04 },
          { "tier": 3, "minNotional": 200000, "maxDistance": 0.03 },
          { "tier": 2, "minNotional": 80000,  "maxDistance": 0.02 },
          { "tier": 1, "minNotional": 30000,  "maxDistance": 0.01 }
        ]
      },
      "targets": [
        { "symbol": "DOGEUSDT", "market": "SPOT"    },
        { "symbol": "SHIBUSDT", "market": "FUTURES" }
      ]
    }
  ]
}
```

**Response `200 OK`**: empty body — the upsert succeeded.

---

### `DELETE /api/rules`

**Removes** the user's custom rule for one or more tickers, resetting them to the default rule.
Deleting a rule for a ticker that has no custom rule is a **no-op** (returns `200`, not `404`).
The operation is idempotent.

**Request `Content-Type: application/json`**:

```json
{
  "targets": [
    { "symbol": "BTCUSDT", "market": "FUTURES" },
    { "symbol": "SOLUSDT", "market": "SPOT"    }
  ]
}
```

**Response `200 OK`**: empty body — the delete succeeded (or there was nothing to delete).

---

## Validation Rules & Error Responses

All validation is applied **before** any database write. If any check fails, the **entire request**
is rejected with `400 Bad Request` and a human-readable message. No partial application occurs.

### Per-Tier Checks

| Check | Rejection condition |
|-------|---------------------|
| `tier` in range | `tier` not in `[1, 4]` |
| No duplicate tiers within a rule | Two entries with the same `tier` number in one assignment |
| All four tiers present | Any of tiers `1, 2, 3, 4` is missing — e.g. `{1, 2, 3}` or `{1, 2}` are both rejected. Rule: exactly 4 distinct tiers, 1 through 4 |
| `minNotional` non-negative | `minNotional < 0` |
| `maxDistance` positive and within price filter | `maxDistance ≤ 0` or `maxDistance > 0.1` |
| Rule has at least one tier | `tiers` is empty or missing |

> **Why is `maxDistance` capped at `0.1`?** The backend only retains price levels within 10% of
> the mid-price. Any threshold above `0.1` could never match any level, so the backend rejects
> it upfront instead of storing a rule that silently does nothing.

> **Why must every rule define all four tiers?** A partial tier set (e.g. only tiers 1 and 2)
> would leave tiers 3 and 4 unresolvable for that ticker — orders that should be classified at
> those tiers would silently fall through. Requiring full coverage means a custom rule always
> produces an unambiguous classification, the same guarantee the default rule provides.

### Per-Target Checks

| Check | Rejection condition |
|-------|---------------------|
| Symbol is currently tracked | `symbol` is not in the backend's active ticker list (delisted, typo, etc.) |
| Market matches the symbol | e.g. requesting `BTCUSDT SPOT` for a ticker that only exists on futures |

Symbols are normalised to uppercase, so `"btcusdt"` and `"BTCUSDT"` are treated identically.

### Request-Level Checks

| Check | Rejection condition |
|-------|---------------------|
| Total targets per request | More than 200 `(assignment × target)` pairs in one PUT request |

### HTTP Error Shapes

All `4xx` errors return a JSON body:

```json
{
  "message": "maxDistance must be in (0, 0.1]",
  "status": 400,
  "path": "/api/rules"
}
```

| Status | Meaning |
|--------|---------|
| `400 Bad Request` | Validation failure — `message` contains a human-readable description |
| `403 Forbidden` (empty body) | Missing or invalid JWT — rejected by Spring Security before reaching the controller, so there is no JSON body |
| `403 Forbidden` (JSON body, `"Active subscription required"`) | Valid JWT, but no active trial/paid access — all endpoints except `GET /api/rules/default` |
| `404 Not Found` | Only from `GET /api/rules/{symbol}/{market}` when no rule exists |

---

## Practical Notes for the Frontend

- **Live effect**: rule edits apply to already-connected sessions immediately — no page reload or
  reconnect needed. The user should see their feed retarget within ~100ms of a successful
  `PUT`/`DELETE`.
- **Every `/api/rules/**` call needs a JWT**, including `GET /api/rules/default`. A `403` with no
  body means "not authenticated"; a `403` with a JSON body means "authenticated but access has
  lapsed" — handle these two differently (re-login vs. show paywall).
- **Symbols are always uppercase**: normalise user input before sending.
- **Markets**: only `"SPOT"` and `"FUTURES"` are valid — no other values accepted.
- **`minNotional` is USD**: collect and send as a plain number (`5000000` for $5M). No formatting
  in the JSON body.
- **`maxDistance` is a fraction**: `0.05` = 5%. Display as a percentage in the UI and divide by
  100 on submit.
- **Tier array order in the request does not matter**: the backend sorts internally.
- **Active ticker list**: `GET /api/tickers` returns currently active tickers, each flagged with
  `hasSpot`/`hasFutures` — use it to populate the ticker picker and validate `(symbol, market)`
  combinations before submission (see [Fetching the Active Ticker List](#fetching-the-active-ticker-list)).
- **Showing default thresholds**: call `GET /api/rules/default` once on load and display the
  relevant table (normal or high-liquidity) alongside the custom-rule form so the user can see
  what they are overriding.
