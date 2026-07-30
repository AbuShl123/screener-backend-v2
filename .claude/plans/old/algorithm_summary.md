# Order Book Tier Classifier — Algorithm Summary

## Goal

Given a live BTC/USDT futures order book, classify every price level by
**importance** — a function of two signals:

- **Q** — dollar amount at that level (`qty × price`)
- **D** — distance from mid-price in % (how close to the spread)

An order is more important if it is large AND close to the spread. The output
is 5 tiers (T1–T5), each with a distinct color, so traders can scan the book
visually and immediately focus on what matters.


## Why not hard rules like "T1 = Q ≥ $300k AND D ≤ 0.5%"?

Hard boundaries create cliffs. A $9.9M order at 5.0% would fall into T5 while
a $10M order at 5.0% is T4 — a $100k difference causes a full tier jump, which
feels arbitrary to end-users. The business team originally proposed this model
but acknowledged the limitation.

The solution is to compute a **continuous importance score** first, then map
score ranges to tiers. Near-misses score nearly identically and land in the
same tier naturally.


## Core Formula

```
score(Q, D) = log10(Q) / (D + EPSILON)
```

**`Q`** — dollar value of the order. Uses `log10` because the difference
between a $1M and $2M order is more meaningful than between $100M and $101M.
Logarithmic compression prevents giant orders from making everything else
invisible.

**`D`** — distance from mid-price in percent. Acts as a penalty in the
denominator — the further from the spread, the lower the score for the same Q.

**`EPSILON`** (default `0.05`, in % units) — a soft-floor on the denominator.
Without it, an order at D=0.001% would score astronomically high regardless of
its size, because the denominator approaches zero. EPSILON ensures that being
extremely close to the spread stops providing extra benefit beyond a certain
point. This is NOT a tiny numerical stabilizer — it must be set to a
meaningful value (0.05–0.10). Raising EPSILON shifts the balance toward Q
mattering more; lowering it makes distance proximity more rewarded.


## Tier Threshold Derivation

The business team defines tiers in plain language as anchor points:
`(label, min_Q_dollars, max_D_percent)`. Each anchor describes the
"boundary case" of that tier — the smallest order at the furthest distance
that still qualifies.

The score of that boundary case becomes the tier's score threshold:

```
threshold = log10(min_Q) / (max_D + EPSILON)
```

Classification then becomes: compute the order's score, walk the thresholds
highest-first, assign the first tier whose threshold the score meets or exceeds.

**Example derivation with current config:**

| Tier | min_Q      | max_D  | threshold = log10(Q) / (D + 0.05) |
|------|------------|--------|-----------------------------------|
| T1   | $3,000,000 | 0.20%  | log10(3M) / 0.25 = **25.91**      |
| T2   | $10,000,000| 0.50%  | log10(10M) / 0.55 = **12.73**     |
| T3   | $30,000,000| 1.00%  | log10(30M) / 1.05 = **7.12**      |
| T4   | $100,000,000| 2.50% | log10(100M) / 2.55 = **3.14**     |
| T5   | anything that passed both gates but scored below T4 threshold |

A concrete order `{Q=$3.46M, D=0.03%}` scores:
`log10(3,460,000) / (0.03 + 0.05) = 6.539 / 0.08 = 81.7` → well above T1
threshold of 25.91 → **T1**.


## Two-Gate Pre-Filter (applied before scoring)

Orders that pass neither gate get dropped entirely — they are not shown at all,
not even as T5. This keeps the output clean and prevents noise from polluting
the ranked lists.

**Gate 1 — Q noise floor:**
```
Q < MIN_ORDER_DOLLARS → excluded
```
`MIN_ORDER_DOLLARS` is set manually. Suggested value: slightly below the
lowest tier's Q anchor so near-miss orders of genuine interest are still
visible as T5. Example: if T1 starts at $3M, set to $2M.

**Gate 2 — Distance ceiling:**
```
D > MAX_DISTANCE_PCT → excluded
MAX_DISTANCE_PCT = max(max_D in TIER_CONFIG) × DISTANCE_BUFFER_FACTOR
```
Auto-derived. An order further than the widest tier boundary (plus a buffer)
can never qualify for any tier, so it is pure noise. `DISTANCE_BUFFER_FACTOR`
defaults to 1.2 — keeps orders up to 20% beyond the widest tier for T5
context visibility.


## Configuration (business language only)

```python
MIN_ORDER_DOLLARS      = 2_000_000   # Gate 1 — manual noise floor
EPSILON                = 0.05        # denominator soft-floor (% units)
DISTANCE_BUFFER_FACTOR = 1.2         # Gate 2 buffer multiplier

TIER_CONFIG = [
    ("T1",   3_000_000,  0.2),
    ("T2",  10_000_000,  0.5),
    ("T3",  30_000_000,  1.0),
    ("T4", 100_000_000,  2.5),
]
```

Everything else — score thresholds, MAX_DISTANCE_PCT, gate logic — is derived
automatically. The business team only ever edits TIER_CONFIG.


## Tier Colors

| Tier | Color  | Hex       |
|------|--------|-----------|
| T1   | Purple | `#a855f7` |
| T2   | Red    | `#ef4444` |
| T3   | Yellow | `#eab308` |
| T4   | Green  | `#22c55e` |
| T5   | Grey   | `#6b7280` |


## Tuning Guide

| Symptom | Fix |
|---|---|
| Small orders near spread scoring too high | Raise EPSILON (e.g. 0.05 → 0.10) |
| Large distant orders not getting credit | Lower EPSILON |
| Too much noise in T5 | Raise MIN_ORDER_DOLLARS |
| Near-miss orders disappearing entirely | Lower MIN_ORDER_DOLLARS |
| Too many distant T5 orders cluttering output | Lower DISTANCE_BUFFER_FACTOR (e.g. 1.2 → 1.0) |
| Want more context beyond tier boundaries | Raise DISTANCE_BUFFER_FACTOR |


## Data Source

```
GET http://localhost:8080/api/v2/orderbook?symbol=BTCUSDT&market=FUTURES
```

Response fields used per level:
- `price` — price of the level
- `qty` — base asset quantity → multiply by price to get Q in dollars
- `distance` — fractional distance from mid (multiply by 100 to get %)
- `lifetimeMs` — age of the order in milliseconds

The script falls back to a realistic mock data generator if the API is
unavailable, with intentional edge-case orders injected for model testing.
