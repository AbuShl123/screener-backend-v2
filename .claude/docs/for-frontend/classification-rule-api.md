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
| 1    | $300,000          | 0.5% (`0.005`)        |

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
- Each custom rule defines between **1 and 4 tiers**, and they must be **contiguous starting at
  tier 1** (e.g. tiers 1+2+3 are valid; tiers 1+3 are not valid — tier 2 cannot be skipped).
- `(symbol, market)` is the unit of granularity: a user can have different thresholds for
  `BTCUSDT SPOT` vs `BTCUSDT FUTURES`.
- The same rule body can be applied to multiple tickers in one request.
- Live effect: custom rules take effect the next time the user connects via WebSocket. Editing a
  rule while connected requires a reconnect to take effect.

---

## Authentication

All endpoints require a valid **Bearer JWT** in the `Authorization` header:

```
Authorization: Bearer <access_token>
```

The user's identity is always derived from the JWT — the request body never contains a user ID.
Requests without a valid token receive `401 Unauthorized`.

---

## Base Paths

| Purpose | Base path |
|---------|-----------|
| Default rule info | `/api/rules` |
| User's custom rules | `/api/rules` |

---

## Endpoints

---

### `GET /api/rules/default`

Returns the **server's default classification rule** — the two threshold tables and the list of
symbols that use the high-liquidity table. No authentication required. Use this to display the
default thresholds in the UI so the user understands what they are overriding.

**Request**: No body, no query parameters.

**Response `200 OK`**:

```json
{
  "normalTiers": [
    { "tier": 4, "minNotional": 10000000, "maxDistance": 0.05   },
    { "tier": 3, "minNotional": 1000000,  "maxDistance": 0.02   },
    { "tier": 2, "minNotional": 500000,   "maxDistance": 0.01   },
    { "tier": 1, "minNotional": 300000,   "maxDistance": 0.005  }
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
      { "tier": 2, "minNotional": 300000, "maxDistance": 0.02 },
      { "tier": 1, "minNotional": 100000, "maxDistance": 0.01 }
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
atomically. Sending two tiers for a target that previously had four tiers results in exactly two
tiers afterwards.

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
          { "tier": 2, "minNotional": 500000, "maxDistance": 0.02 },
          { "tier": 1, "minNotional": 100000, "maxDistance": 0.01 }
        ]
      },
      "targets": [
        { "symbol": "BTCUSDT", "market": "FUTURES" }
      ]
    },
    {
      "rule": {
        "tiers": [
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
| Tiers contiguous starting at 1 | Missing a tier — e.g. tiers `{1, 3}` (tier 2 skipped). Rule: `maxTier == numberOfTiers` |
| `minNotional` non-negative | `minNotional < 0` |
| `maxDistance` positive and within price filter | `maxDistance ≤ 0` or `maxDistance > 0.1` |
| Rule has at least one tier | `tiers` is empty or missing |

> **Why is `maxDistance` capped at `0.1`?** The backend only retains price levels within 10% of
> the mid-price. Any threshold above `0.1` could never match any level, so the backend rejects
> it upfront instead of storing a rule that silently does nothing.

> **Why must tiers be contiguous starting at 1?** Gaps (e.g. having tier 1 and tier 3 but not
> tier 2) would leave tier 2 unresolvable. The user must define every tier from 1 up to their
> desired maximum.

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
| `401 Unauthorized` | Missing or invalid JWT |
| `404 Not Found` | Only from `GET /api/rules/{symbol}/{market}` when no rule exists |

---

## Practical Notes for the Frontend

- **Live effect**: custom rules take effect the next time the user connects via WebSocket. Editing
  a rule while connected requires a page reload / reconnect.
- **Symbols are always uppercase**: normalise user input before sending.
- **Markets**: only `"SPOT"` and `"FUTURES"` are valid — no other values accepted.
- **`minNotional` is USD**: collect and send as a plain number (`5000000` for $5M). No formatting
  in the JSON body.
- **`maxDistance` is a fraction**: `0.05` = 5%. Display as a percentage in the UI and divide by
  100 on submit.
- **Tier array order in the request does not matter**: the backend sorts internally.
- **Active ticker list**: `GET /api/tickers` returns currently active tickers — use it
  to validate `(symbol, market)` combinations before submission.
- **Showing default thresholds**: call `GET /api/rules/default` once on load and display the
  relevant table (normal or high-liquidity) alongside the custom-rule form so the user can see
  what they are overriding.
