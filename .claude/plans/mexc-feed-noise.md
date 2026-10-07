# MEXC Feed Noise: Per-Venue Visibility Cap and Tier Hysteresis

**Written**: 2026-10-07.
**Goal**: bring the MEXC futures depth feed down to Binance-like volume and stability without
hiding real Binance signals. Two independent changes:

1. **Per-venue visibility cap** (`max-visible-distance`): levels further than a venue-set fraction
   from mid are never classified on that venue. MEXC is capped at 1%. This removes the
   market-maker ladders from the feed. **Done** (§2).
2. **Tier hysteresis** in `OrderBookClassifier` (all venues, default and user rules): a level that
   is already shown keeps its tier until it leaves the tier by a margin. This stops boundary
   flicker. **Not started** (§3).

**Read first**: `analysis/OrderBookClassifier`, `analysis/DefaultClassificationRule`,
`analysis/ThresholdClassificationRule`, `analysis/SymbolState`, `config/ExchangesProperties`,
`marketdata/core/book/OrderBook`, `marketdata/core/ingress/DisruptorShardManager`,
`.claude/docs/orderbook-classification.md`.

**Non-goals**
- No change to the depth wire format or to `/api/rules`.
- No change to `screener.orderbook.price-filter-threshold` (§2.1 explains why).
- Not fixing distance-only re-emits (a level's distance changes with every mid tick, so an
  otherwise-unchanged book is re-sent). Separate, smaller follow-up.

---

## 1. Findings this plan rests on

Measured 2026-10-07 from a 120s local `/ws` capture (admin user, default rules), plus MEXC and
Binance REST. Tools: `.claude/tools/feed-capture/`.

| 120s capture | MEXC futures | Binance futures | Binance spot |
|---|---|---|---|
| Books in SNAPSHOT | 71 | 16 | 2 |
| Live messages | 3515 (29/s) | 900 (7.5/s) | 10 |
| Drops | 188 | 2 | 0 |
| Distinct books | 105 | 17 | 2 |
| Appearances lasting ≤0.5s | 61 | 0 | 0 |
| Tier-4 levels (live) | 2339 | 0 | 0 |

- **The quantity multiplier is correct.** MEXC `amount24 / (volume24 × lastPrice)` reproduces
  `contractSize` for every symbol checked (BTC, ETH, XRP, PEPE, DOGE, APT, LTC, XAU…), and MEXC's
  raw REST depth shows the same walls the pipeline does.
- **MEXC books carry market-maker ladders.** The walls mirror each other on bid and ask at fixed
  distances: XRP ~$40M × 3 at ±3.5/3.6/3.7%, APT ~$16M × 3 at ±3.0–3.2% (APT's whole 24h MEXC
  turnover is $13M), with a second band of ~$2–3M at ~0.8–1%. The median visible MEXC level is
  26% of its symbol's 24h MEXC turnover; on Binance it is 0.16%. These clear the
  Binance-tuned `NORMAL_TIERS` easily.
- **Client input**: MEXC-type exchanges are thin next to Binance, and MEXC orders further than 1%
  from the spread are of no interest to traders. The big ladders start at ~3%.
- **Flicker is boundary-driven.** Of 4788 MEXC level exits (a shown level dropping out or changing
  tier), 33% happen with distance ≥80% of the tier's limit and 28% with notional ≤125% of the
  tier's floor. Binance shows the same pattern at a much smaller scale: 146 exits, 57% near a
  boundary. Example: MINA's single $263k ask at ~0.45% crossed tier 1's 0.5% limit as mid
  jittered, and the book was dropped and re-added about every second.

---

## 2. Step 1: Per-venue visibility cap — done

### 2.1 Why a classifier cap and not a per-venue `price-filter-threshold`

`OrderBook.computeDistance()` *deletes* every level outside ±`price-filter-threshold` from the
`TreeMap`. A deleted level only returns when the venue sends a diff for that exact price, and MEXC
only sends one when the level changes. With a 1% filter, a routine 1.5% swing deletes the bids
that were 0.5–1% below the old mid; when price comes back they are missing from the local book,
and sequence validation cannot notice. So the book keeps its wide storage bound (0.1, global), and
the 1% limit is applied where levels are classified.

### 2.2 Config

`ExchangesProperties.VenueProperties` gains `Double maxVisibleDistance` (last component). `null`
means no cap. When set it must be positive and finite (checked in the compact constructor).
`ExchangesProperties.maxVisibleDistances()` returns the cap of every enabled venue that sets one.

```yaml
screener.exchanges.mexc.venues.FUTURES:
  max-visible-distance: 0.01
```

Binance YAML is untouched (no cap). A cap above `price-filter-threshold` is harmless: the book
holds nothing beyond the filter anyway.

### 2.3 Classifier

- `DisruptorShardManager` reads `exchangesProps.maxVisibleDistances()` once and passes it to every
  shard's `OrderBookClassifier`; the startup log line prints it.
- `OrderBookClassifier` copies it into an `EnumMap<Venue, Double>` with an entry for every venue
  (`+∞` when uncapped), so the hot-path `get` never misses or boxes.
- `process()` reads `visibleCap` once per book and passes it to `classifyOne` → `selectTopK`, where
  `maxDist = Math.min(rule.maxDistance(highLiquidity), visibleCap)`. The existing early `break`
  does the rest. No allocation; the scan stops earlier on MEXC than before.
- The cap applies to the **default pass and every user pass**. A user rule stays
  exchange-independent: a 5% rule on `XYZUSDT:FUTURES` sees 5% on Binance and 1% on MEXC.
- `ClassificationRuleService` still bounds a user's `maxDistance` by the global
  `price-filter-threshold`; rules know nothing about venues.

### 2.4 Tests

- `VenuePropertiesTest`: `null` and `0.01` accepted; zero, negative, NaN and infinity rejected.
- `ExchangesPropertiesTest.shippedYamlBinds`: MEXC futures binds `0.01`, Binance futures `null`.
- `OrderBookClassifierTest`: a ~$31M ask at 3% is tier 4 on Binance futures and absent on MEXC
  futures, for both the default pass and a user rule with `maxDistance` 0.05; a ~$2M ask at 0.5% on
  MEXC is still tier 3 while the 3% wall stays hidden.
- Existing classifier tests construct with `Map.of()` (no caps) and pass unchanged.

---

## 3. Step 2: Tier hysteresis

### 3.1 Rule

For a book that is currently shown (`state.level == HIGH`), a level that was in the last emitted
top-5 at tier `p` gets:

```
strict  = rule.computeTier(notional, distance)
held    = rule.computeTier(notional / (1 - notionalMargin), distance / (1 + distanceMargin))
tier    = max(strict, min(p, held))
```

So a shown level keeps its tier (never above `p`) while its notional stays ≥ `floor × (1 − nm)`
and its distance ≤ `limit × (1 + dm)`. Promotion is never delayed; only demotion and exit are.
Levels that weren't shown, and every level of a LOW book, use `strict` alone. A book therefore
enters exactly as it does today, and leaves only once no level survives the relaxed check.

The venue's visibility cap (§2) is one more distance limit and is relaxed the same way: a shown
MEXC level stays until ~1.2%, a new one must be within 1%. Without this, the 1% edge becomes a new
flicker boundary.

With margins 0.2 / 0.2, tier 1 (normal) enters at $200k within 0.5% and exits below $160k or beyond
0.6%. MINA's ask, which oscillated between 0.29% and 0.50%, stays shown.

### 3.2 Config

Bind a new `ClassificationProperties` record to `screener.classification` (the existing
`max-targets-per-request` key may be folded into it or left on its `@Value`):

```yaml
screener.classification:
  hysteresis:
    # A shown level keeps its tier until notional < floor × (1 − notional-margin) or
    # distance > limit × (1 + distance-margin). 0 / 0 = no hysteresis (pre-2026-10 behaviour).
    notional-margin: 0.2
    distance-margin: 0.2
```

Validate `0 ≤ notional-margin < 1` and `distance-margin ≥ 0`. The margins are global, not
per-venue: boundary flicker is a classifier property, not a venue one.

### 3.3 Classifier changes

`DisruptorShardManager` passes `ClassificationProperties` to each `OrderBookClassifier`, which
precomputes `notionalRelax = 1 / (1 − nm)` and `distanceRelax = 1 / (1 + dm)` into final fields.

In `classifyOne`, the memory is the last emitted top-5, `state.workBids` / `state.workAsks`. They
are written only by `applyNewOrders`, after both sides are selected, so during selection they
still hold the previous emission. Pass them only when the book is HIGH:

```java
ClassifiedLevel[] shownBids = state.level == HIGH ? state.workBids : null;
boolean bidVisible = selectTopK(bids, state.bidScratch, rule, highLiquidity, visibleCap, shownBids);
```

`selectTopK`:

```java
double maxDist  = Math.min(rule.maxDistance(highLiquidity), visibleCap);
double scanDist = shown == null ? maxDist : maxDist * (1 + distanceMargin);
for (...) {
    double distance = e.getValue().distance;
    if (distance > scanDist) break;
    double notional = e.getKey() * e.getValue().quantity;
    int tier = distance > visibleCap ? 0 : rule.computeTier(notional, distance, highLiquidity);
    if (shown != null) {
        int prev = shownTier(shown, e.getKey());   // linear scan of ≤5 slots, 0 if absent
        if (prev > tier) {
            double relaxedDist = distance * distanceRelax;
            int held = relaxedDist > visibleCap ? 0
                    : rule.computeTier(notional * notionalRelax, relaxedDist, highLiquidity);
            tier = Math.max(tier, Math.min(prev, held));
        }
    }
    if (tier == 0) continue;
    tryInsert(s, e, tier, notional, distance);
}
```

- Identity is the price (`double ==` against `ClassifiedLevel.price()`), the same key the
  book's `TreeMap` uses. A re-quote to a new price is a new level, which is correct.
- Hot-path rules hold: no allocation; `shownTier` is ≤5 primitive comparisons, run only for
  levels inside `scanDist`.
- The memory is per `SymbolState`, so the default pass and each user pass hold their own levels.
  A rule edit builds a fresh context, which clears the memory.
- Leaving `workBids`/`workAsks` stale while LOW is harmless: memory is only read when HIGH, and
  the LOW→HIGH transition rewrites them.

### 3.4 Tests (`OrderBookClassifierTest`)

All existing tests construct with margins 0 / 0 and must pass unchanged. That shows
zero margins reproduce today's behaviour. New cases use 0.2 / 0.2:

- A shown tier-1 level drifting from 0.45% to 0.55%: no DROP, still tier 1. Past 0.6%: DROP.
- A shown tier-1 level shrinking from $210k to $170k: kept. Below $160k: dropped.
- A tier-2 level that falls to tier-1 strict values but stays within tier-2's relaxed band: still
  tier 2 (hold is capped at `p`).
- Promotion is not delayed: a shown tier-1 level that grows past the tier-2 floor becomes tier 2
  in the same update.
- A LOW book does not use relaxed thresholds: a level at 0.55% never brings a LOW book in.
- A level that was not shown gets no hold: at 0.55% it is not selected even while the book is HIGH.
- A user pass and the default pass on one book keep separate memory.
- Visibility cap: a shown MEXC level drifting from 0.95% to 1.1% stays; past 1.2% it goes. A
  level first seen at 1.1% is never selected.

## 4. Considered and rejected

Each was checked against the same capture unless noted.

- **Per-venue `price-filter-threshold`** (MEXC 0.01): the filter deletes levels, which then go
  missing from the book after any swing larger than the filter (§2.1).
- **Per-venue notional scale for the default rule** (MEXC tiers need 3× the notional): a replay
  at scale 3 brought MEXC to Binance's volume (2808 → 884 msgs / 120s, 69 → 18 books). Superseded
  by the cap: the client has no interest in MEXC levels beyond 1% at any size, the cap removes the
  ladders outright, and it leaves MEXC tiers comparable to Binance's. It stays the fallback if the
  ~0.8–1% band turns out to be noise too (§5).
- **Turnover-relative floors** (`minNotional = max(abs, r × turnover24h)`): this moves floors in the
  wrong direction for MEXC. Thin coins get lower floors, but the noise *is* on thin MEXC coins.
- **Turnover ceiling** (ignore levels above `c × turnover24h`): weak. At c = 0.05, MEXC still has
  1575 msgs and 38 books. It also hits Binance (max Binance level/turnover is 2.2×). And it
  suppresses the "wall big relative to volume" case, which is the signal traders want.
- **Minimum level age** (count a level only after it rests N seconds, via `firstSeenMillis`): no
  effect. MEXC messages go 2808 → 2781 at 3s. The flickering levels are old; they cross
  boundaries rather than appear and vanish.
- **Delayed DROP** (grace period before removing a book): only 11 of 152 drop→re-add gaps are
  under 2s (most are 5–10s), so a useful grace would show stale levels for many seconds.
  Hysteresis removes the drops at the source instead.

## 5. Known gaps, deliberately left

- **The ~$2–3M band at ~0.8–1% is inside the cap.** It clears tier 3 ($1M within 2%), so some MEXC
  market-maker noise remains. Re-measure (§6); if it is still noisy, revisit the notional scale.
- **User rules are capped on MEXC too, silently.** `/api/rules` still accepts `maxDistance` up to
  0.1, and nothing tells the client that MEXC shows at most 1%. If the frontend needs it, expose
  the per-venue cap additively (e.g. on `/api/rules/default`).
- **A held level is shown with a tier it no longer strictly meets** (e.g. tier 1 at 0.55%, or a
  MEXC level at 1.1%). This is the point of hysteresis. Document it in the client-facing docs so
  nobody files it as a bug.
- **The cap is static.** If the client's view of MEXC changes, retune the YAML value. No code
  change is needed.

## 6. Verification

1. **Cap only** (now): run with `max-visible-distance: 0.01` on MEXC and with the line removed,
   minutes apart on the same build.
2. **After hysteresis**: Run A with margins `0 / 0`, Run B with `0.2 / 0.2`, both with the cap.

For each run, log in, then:

```bash
node .claude/tools/feed-capture/capture.mjs token.txt run.jsonl 120
python .claude/tools/feed-capture/feed_stats.py run.jsonl
```

**Accept the final state when**:
- MEXC messages per 120s ≤ 1.5× Binance futures messages.
- MEXC drops ≤ 25% of the uncapped run's, and MEXC appearances ≤0.5s ≤ 25% of the uncapped run's.
- MEXC tier-4 is rare: ≤ ~5% of MEXC's live levels (today 10%).
- No MEXC level is shown beyond 1% (beyond ~1.2% once hysteresis lands).
- Binance futures books visible within ±20% of the uncapped run. Hysteresis may *raise* this
  slightly; it should not fall.

Then `./mvnw test`, and a look at the frontend: MEXC rows should sit still.

## 7. Docs

- `.claude/docs/orderbook-classification.md`: the per-venue visibility cap (§2) and why it is not
  the price filter; the hysteresis rule (§3.1).
- `.claude/docs/for-frontend/websocket-feed-api.md`: MEXC levels appear only within 1% of mid;
  §3.5 (`tier`): a level may keep its tier while slightly outside the tier's limits (hysteresis).
- `.claude/docs/for-frontend/classification-rule-api.md`: a rule's `maxDistance` is additionally
  capped per venue (1% on MEXC).
- `.claude/docs/multi-exchange-progress.md`: add `max-visible-distance` to the config table; record
  the MEXC ladder finding. `price-filter-threshold` stays global by design.

## 8. Commits

One commit for the visibility cap (step 1), one for hysteresis (step 2), then docs.
