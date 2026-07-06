# Design Brief — Real-Time Orderbook Screener (Bid/Ask Classification View)

> **For:** an AI design agent (UI/UX). You have full latitude to propose your own
> visual language, layout, color system, motion, and component hierarchy. This
> document describes **what the feature does, what data exists, and what it means** —
> not how it must look. The one hard requirement is that the design honor the
> *semantics* of the data (especially the tier model, described carefully below,
> because it is easy to get wrong).

---

## 1. Product in one paragraph

This is a **cryptocurrency market screener**. For a set of trading pairs (e.g.
`XRP/USDT`, `BTC/USDT`) on Binance, the backend maintains a live order book — the
full list of open buy orders (**bids**) and sell orders (**asks**) — and continuously
analyzes it to surface only the **most significant price levels**. The user does not
want to see the raw thousands of orders; they want the screener to answer *"where is
the meaningful money sitting right now?"* The frontend renders these classified levels
as a live, always-updating dashboard. Think of it as a "heatmap of important orders"
rather than a traditional depth-of-market ladder.

The audience is **crypto traders**. They read this dashboard to spot support/resistance
walls, absorption near the current price, and large resting orders — signals that inform
entries, exits, and expectations of where price may stall or bounce.

---

## 2. The core visual unit: one orderbook "card"

The screen is a **grid of cards**, one card per trading pair. The user watches many
pairs at once (the prototype shows a 2×2 grid; the real product should scale to more,
responsively). Each card is self-contained and updates independently in real time.

A single card contains:

- **A header** — the ticker symbol (e.g. `XRP/USDT`) and a **market badge** indicating
  whether this is the **SPOT** or **FUTURES** market for that pair. The same pair can
  appear as two separate cards (one SPOT, one FUTURES); they are distinct order books
  with distinct data. In the prototype the market is shown as a tiny green badge with an
  "f" (futures). This needs to be legible and unambiguous — SPOT vs FUTURES is a
  meaningful distinction to a trader, not decoration.

- **An ASKS section** — the top half. Up to **5 ask levels** (sell orders). Conventionally
  colored in a "sell" hue (red in the prototype). Asks are ordered so that the level
  **closest to the current market price sits at the bottom of the asks block**, i.e.
  descending toward the spread. The prices in asks are all **above** the current price.

- **A BIDS section** — the bottom half. Up to **5 bid levels** (buy orders). Colored in a
  "buy" hue (green in the prototype). Bids are ordered so the level **closest to the
  current price sits at the top of the bids block**. The prices in bids are all **below**
  the current price.

- **The "spread" is the gap between the asks block and the bids block.** The visual center
  of the card — where asks meet bids — represents the current market price. Levels radiate
  away from that center: the further from center (top of card for asks, bottom for bids),
  the further from the current price. This center-out reading is the heart of the layout
  and should be reinforced by whatever design you choose.

There is **no explicit numeric mid-price shown** in the prototype, but the spread/center
is conceptually where "now" is. You may choose to surface the mid-price or last price if
it helps orient the user — that's a design decision.

---

## 3. What each row (price level) contains

Every row is one price level and carries these data fields (the backend sends them per
level over a WebSocket; see §6):

| Field | Meaning | Example | Prototype rendering |
|---|---|---|---|
| **price** | The price of this order level, in quote currency (USDT). Decimal precision varies wildly by asset — `1.1345` for XRP, `0.075050` for DOGE, `65432.1` for BTC. | `1.1166` | Left column, colored by side (red ask / green bid) |
| **notional** | USD value of the order = price × quantity. This is "how big is the wall." | `$298.2K`, `$1.06M` | Middle column (compact currency, K / M) **and** the **length** of the highlight bar behind the row (see §4 / §7) |
| **distance** | How far this level is from the current price, as a **percentage**. `0.50%` means half a percent away from mid. Small = near the current price. | `2.11%` | Right column, percentage |
| **tier** | An importance classification, integer **1–4** (see §4 — the critical section). Drives visual emphasis. | `2` | Encoded as the **color** of the highlight bar behind the row (see §4 / §7) |
| **lifetime** | How long this exact order level has existed in the book, derived from a `firstSeenMillis` timestamp. "This wall has been sitting here for 3m 12s." A fresh order vs. a long-resting one means very different things to a trader. | 3m 12s | **Hover tooltip** — hidden until the user hovers the row |
| **quantity** | Raw coin amount (price × quantity = notional). Rarely shown directly; notional is the trader-facing number. | `0.85` | Not shown in prototype |

**Formatting notes for the designer:**
- **Notional** is always compacted: `$298.2K`, `$561.6K`, `$1.06M`, `$1.15M`. This is the
  number the eye scans most — it deserves strong typographic treatment. Alignment matters
  (traders compare magnitudes down a column).
- **Distance** is a percentage with 2 decimals, e.g. `0.03%`, `2.79%`.
- **Price** decimal count is asset-dependent and should be shown as-is from the backend;
  don't force a fixed decimal count. Monospaced or tabular figures help columns line up.
- Only **5 levels per side** ever show. The screener has already done the filtering — the
  frontend never renders a deep ladder. This is a *curated* view, not a raw order book.

---

## 4. THE TIER MODEL — read carefully, this is the crux and it is counter-intuitive

Each shown level has a **tier from 1 to 4**. Tiers drive how much visual weight a row gets.
It is tempting to read "tier 1 = most important, tier 4 = least important" (like a ranking).
**That mental model is wrong and will produce a misleading design.** Here is the real model.

### How a tier is assigned

A level qualifies for a tier only if it clears **both** a **minimum notional** (size) **and**
a **maximum distance** (proximity to price) threshold. The backend's default thresholds:

| Tier | Min notional (size) | Max distance (proximity) |
|---|---|---|
| **1** | ≥ $300K | within 0.5% of price |
| **2** | ≥ $500K | within 1% of price |
| **3** | ≥ $1M | within 2% of price |
| **4** | ≥ $10M | within 5% of price |

Read the table again and notice the **direction** of both columns:

- As tier number **goes up**, the **required size goes up** (a tier-4 order must be *huge*,
  $10M+), **and** the **allowed distance also goes up** (a tier-4 order may sit far, up to 5%
  away).
- As tier number **goes down**, the size bar **drops** and the proximity requirement **tightens**.
  A tier-1 order can be relatively modest ($300K) *because it is right up against the current
  price*, where even a moderate order exerts real pressure.

The intuition the design must express:

> **The closer an order is to the current price, the less size it needs to matter. The
> farther away it is, the more enormous it must be to still matter.** An order sitting
> right at the spread is significant even if it's "only" a few hundred K. An order sitting
> 4% away is only worth showing if it's a multi-million-dollar wall. Distance kills
> relevance unless sheer size overrules it.

### A level takes the HIGHEST tier it qualifies for

The backend checks tier 4 first, then 3, 2, 1, and assigns the first (highest) match. So a
$100M order sitting 0.1% from price resolves to **tier 4**, not tier 1 — because a massive
order earns the "big wall" classification. Concretely: **tier 4 = the giant distant (or
giant nearby) walls; tier 1 = the tight near-the-spread pressure that qualifies on
proximity, not size.**

### Design implication (important)

Do **not** design tiers as a simple "hot → cold" or "1st place → 4th place" ranking where
tier 1 is loudest and tier 4 is faintest. Both ends of the scale are *interesting for
different reasons*:

- **Low tiers (1–2)** = *near-spread action*. Orders hugging the current price. Signals
  immediate support/resistance, absorption, imminent pressure.
- **High tiers (3–4)** = *big walls / whales*. Large notional, possibly farther out. Signals
  major support/resistance zones and large resting liquidity.

You are free to choose whether tier maps to **intensity**, **hue**, **bar length**, **weight**,
an **icon**, or a combination — but the encoding should let a trader distinguish "a wall"
from "near-spread pressure" at a glance, and should not imply a false 1-is-best hierarchy.
If you use a single-axis intensity ramp, make a deliberate choice about what "louder" means
and be consistent.

### The prototype's two-channel encoding (tier = color, notional = bar length)

The prototype already separates "what kind of level" from "how big," and this dual encoding
is worth understanding (keep it, refine it, or replace it — your call):

- Behind each row sits a **horizontal highlight bar**. Its **length encodes notional**: the
  bigger the order, the longer the bar stretches. The length is **capped** — a bar never
  extends past where the price column ends, so the longest possible bar spans roughly the
  row width and everything else is proportional to that. This lets a trader compare wall
  sizes down and across the card at a glance.
- The bar's **color encodes tier**, on a fixed mapping:

  | Tier | Prototype bar color |
  |---|---|
  | 1 | green |
  | 2 | yellow |
  | 3 | red |
  | 4 | purple |
  | 0 | *no bar at all (transparent)* — but tier 0 is never sent anyway (see §4 end) |

  So **color answers "what type of significance"** (near-spread vs. big wall) and **length
  answers "how big."** A short green bar = modest order hugging the spread (tier 1); a long
  purple bar = a giant distant wall (tier 4).

You do **not** have to keep these specific colors — green/yellow/red/purple is the
prototype's arbitrary choice and is not a semantic requirement (in particular it is
*unrelated* to the red-ask / green-bid side coloring, which can be visually confusing).
What matters is preserving the **two independent channels**: one for tier (categorical,
4 values) and one for notional magnitude (continuous, length-like). If you collapse them
or recolor them, do it deliberately and keep both readable.

Note also: these thresholds are **user-configurable** and there's a tighter variant for
high-liquidity symbols (BTC/ETH/SOL). So the exact numbers are not fixed constants to bake
into the design — the design should be robust to any tier being present or absent. A given
card might show only tier-1 and tier-2 levels, or a mix up to tier-4.

### Tier 0 never appears

Levels that clear no tier are "tier 0 — invisible" and are simply not sent. The frontend
only ever receives levels worth showing. Empty slots (fewer than 5 on a side) are normal
and just mean fewer qualifying levels exist right now.

---

## 5. Real-time behavior (this is a live, moving surface — not a static table)

This is the single most important thing to design *for*, beyond static layout:

- **Everything updates continuously.** The backend pushes a new state roughly every
  **100 ms**. Prices, notionals, distances, tiers, and which levels are even present all
  change constantly as the market moves.
- **Levels appear, change, and disappear.** A row can pop into existence (a new wall
  formed), update in place (size grew/shrank, moved closer/farther), or vanish (the wall
  was pulled or eaten). Rows also **reorder** as prices move relative to the spread.
- **This churn needs to feel legible, not chaotic.** Design should help the eye track
  change without inducing flicker fatigue: consider subtle transitions on value changes,
  gentle enter/exit for appearing/disappearing rows, and stability of layout so the whole
  card doesn't jump on every tick. Traders stare at this for long sessions; motion must
  inform, not annoy.
- **Lifetime is a first-class signal here.** Because levels come and go, "how long has this
  one been here" is meaningful. The prototype hides it in a hover tooltip; you may choose to
  elevate it (e.g. an age indicator) if it earns the space — a long-lived wall vs. a
  flickering one is a real distinction to a trader.
- **Many cards update at once.** In a full grid of dozens of cards all ticking, the
  aggregate must remain calm and scannable.

---

## 6. Delivery mechanics (context, not a design constraint)

Data arrives over a **WebSocket**. On connect (or refresh) the client gets a **SNAPSHOT**
(full current state of all cards). Thereafter it receives **UPDATE** messages per card
every ~100 ms with the new bid/ask levels, and **DROP** messages when a pair is removed
from the screener entirely (the card should disappear). This is purely informational for
you — it just confirms the surface is push-driven and live, and that whole cards can enter
and leave, not only rows.

A per-level payload looks like:
`{"price": 65432.1, "quantity": 0.85, "tier": 2, "firstSeenMillis": 1716680000000, "distance": 1.23}`
(`distance` is already a percentage; `firstSeenMillis` is a UNIX-ms timestamp you subtract
from "now" to get lifetime.)

---

## 7. The prototype (what exists today, for reference only)

The attached screenshot is a **throwaway single-file HTML prototype** built for a quick
visual sanity check. **It is not a design to preserve** — it's the current state you're
replacing. What it establishes, and what you should feel free to keep, reinterpret, or
discard:

- Dark theme, tabular rows, card grid.
- Side coloring: asks red, bids green (a near-universal trading convention — worth keeping
  the semantic even if you change the exact palette).
- Three columns per row: price | notional | distance.
- Center-out layout: asks descending to the spread, bids descending from it.
- A **horizontal highlight bar behind each row**, carrying two channels at once (detailed
  in §4): its **length encodes notional** (bigger order → longer bar, capped at the price
  column edge), and its **color encodes tier** (green=1, yellow=2, red=3, purple=4; tier 0
  gets no bar). E.g. the big `$1.06M` / `$1.15M` walls show long bars. This is the
  prototype's honest-but-crude attempt at the tier semantics from §4 — the two-channel idea
  is sound; the specific colors are arbitrary and can be improved.
- Hover tooltip revealing the order's lifetime.

Weaknesses to improve on: the tier bar colors (green/yellow/red/purple) are arbitrary and
**clash with the red-ask / green-bid side coloring** — a green tier-1 bar behind a red ask
row is genuinely confusing; the market badge is cryptic; there's no motion design for a
surface that is fundamentally live; density and hierarchy are flat. The two-channel bar
idea (color=tier, length=notional) is the strongest part and worth carrying forward.

---

## 8. What we want from you

Propose a UI/UX design for this screener that:

1. Makes the **grid of live cards** scannable across many pairs at once.
2. Renders each card's **asks-over-bids, center-out** structure clearly, with an obvious
   sense of "the current price is here."
3. Encodes the **tier model honestly** (§4) — distinguishing near-spread pressure from big
   distant walls, without a misleading 1-to-4 "importance ranking."
4. Gives **notional** the typographic/visual prominence it deserves as the primary
   magnitude the eye compares.
5. Handles **continuous real-time change** gracefully — enter/update/exit of rows and whole
   cards — with motion that informs rather than distracts.
6. Surfaces **distance**, **market (SPOT/FUTURES)**, and **lifetime** legibly.
7. Works as a **web app**, responsive, comfortable for long viewing sessions (dark theme is
   expected but propose what you think is best).

Deliver your own color system, typography, spacing, component anatomy for a card and a row,
tier encoding, market badge treatment, empty/loading/dropped states, and any motion
guidance. Treat the numbers and thresholds as illustrative — the design must adapt to any
mix of tiers and any asset's price scale.
