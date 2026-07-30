# Per-User Classification — Phase B: The Rule / State-Machine / Feed Split

## Scope

**Building now**: extract the classification *rule* out of `OrderBookClassifier` into a small,
pluggable abstraction so that the per-shard classifier can run its existing state machine
against *any* rule, not just the hardcoded default.

Concretely:
- A `ClassificationRule` interface — `computeTier(...)` + `maxDistance(...)`.
- `DefaultClassificationRule` (`@Component`) — the current thresholds, the high-liquidity
  table, and `isHighLiquidity(symbol)`, moved verbatim out of `OrderBookClassifier`.
- `ThresholdClassificationRule` — an immutable, primitive-backed rule built from a user's tier
  set. Built and **unit-tested standalone** in Phase B; it is not consumed by the hot path
  until Phase C.
- Refactor `OrderBookClassifier` so `classify()` takes a `ClassificationRule` and its
  early-break is driven by `rule.maxDistance(highLiquidity)` instead of a constant.
- Wire the `DefaultClassificationRule` singleton into the per-shard classifiers via
  `DisruptorShardManager`.

**Not building now** (Phase C/D): `UserClassificationRule`, `UserClassificationContext`,
`UserOrderBookFeedStore`, `UserFeedRegistry`, the `volatile activeUserContexts` array, the
two-pass hot path, the broadcaster merge, and connect-time rule loading. No DB rows are read
by any Disruptor-thread code. The WebSocket server, feed store, and broadcaster are untouched.

**The defining constraint**: Phase B is a **pure refactor**. The global default feed must be
byte-for-byte identical before and after. The only logic that physically moves is the tier
computation; the activity state machine, top-K selection, change detection, and feed
submission stay exactly as they are.

See [per-user-classification-vision.md](per-user-classification-vision.md) for the full runtime
design and [per-user-classification-phase-a.md](per-user-classification-phase-a.md) for the
persistence/REST layer this eventually consumes.

---

## Design Decisions (settled)

| Decision | Choice | Rationale |
|---|---|---|
| Interface shape | `int computeTier(notional, distance, highLiquidity)` + `double maxDistance(highLiquidity)` | Two pure methods, no state. The early-break needs the rule's widest distance, so it must be part of the contract — not a constant in the classifier. |
| `highLiquidity` as a parameter | Kept on **both** interface methods | High-liquidity is a *default-rule* concept, but the classifier is rule-agnostic. It computes the boolean once per `process()` and threads it in without branching on rule type. Cheaper than passing the symbol + re-checking a `Set` per level. The user rule simply ignores it. |
| High-liquidity logic location | Stays in `DefaultClassificationRule` (hardcoded `Set` + tighter table + `isHighLiquidity`) | It is default-rule behavior, not a generic technique. Moving it intact keeps the default feed unchanged. |
| `computeTier` "no match" result | Always returns `0` (invisible) when no tier's thresholds are satisfied | `OrderBookClassifier.hasVisibleLevel()` treats `tier > 0` as visible — this invariant must hold for every rule, default and user. |
| `ThresholdClassificationRule` build input | A list of plain `TierThreshold(tier, minNotional, maxDistance)` records, sorted highest-tier-first internally | Decouples the rule from JPA. Phase C maps persisted rows → `List<TierThreshold>`; Phase B tests build the list directly. |
| Package | `dev.abu.screener_backend.analysis` (next to `OrderBookClassifier`) | These are hot-path classification classes. Kept separate from `analysis/rule/`, which holds the Phase-A persistence + REST layer. |
| Where the rule classes are *consumed* in B | Only the default pass; `rule == defaultRule` always | No user contexts exist yet. The seam (a `ClassificationRule` parameter on `classify()`) is introduced now so Phase C only has to *call* it with a different rule. |

---

## New Classes

All three live in `src/main/java/dev/abu/screener_backend/analysis/`.

### 1. `ClassificationRule` — interface

```java
package dev.abu.screener_backend.analysis;

/**
 * A pure, stateless classification rule. Implementations decide which tier (0–4) a single
 * price level falls into, given its notional value and distance from mid-price.
 *
 * <p>The {@code highLiquidity} flag is a default-rule concern (some tickers use tighter
 * thresholds). The classifier computes it once per book and threads it into whichever rule is
 * active; rules that don't care about it (user rules) simply ignore the parameter.
 */
public interface ClassificationRule {

    /**
     * @return the tier 1–4 if the level matches a tier's notional AND distance thresholds,
     *         or 0 (invisible) if it matches none. Higher-numbered tiers are checked first,
     *         so a level that qualifies for several tiers resolves to the highest.
     */
    int computeTier(double notional, double distance, boolean highLiquidity);

    /**
     * @return the widest distance any tier in this rule can match. The classifier uses this to
     *         break out of its distance-ordered top-K loop early: once the top-K buffer is full,
     *         any level beyond this distance can only be tier-0 and is already out-ranked.
     */
    double maxDistance(boolean highLiquidity);
}
```

### 2. `DefaultClassificationRule` — `@Component` singleton

The current `OrderBookClassifier.computeTier()` body (lines 242–256), the `HIGH_LIQUIDITY_TICKERS`
set (line 65), and the `isHighLiquidity` check (line 114) move here **unchanged**.

```java
package dev.abu.screener_backend.analysis;

import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class DefaultClassificationRule implements ClassificationRule {

    /**
     * High-liquidity tickers use tighter notional/distance thresholds due to deeper books
     * and tighter spreads — standard thresholds would classify nearly everything as tier-4.
     */
    private static final Set<String> HIGH_LIQUIDITY_TICKERS = Set.of("BTCUSDT", "ETHUSDT", "SOLUSDT");

    /** True if {@code symbol} uses the tighter high-liquidity threshold table. */
    public boolean isHighLiquidity(String symbol) {
        return HIGH_LIQUIDITY_TICKERS.contains(symbol);
    }

    @Override
    public int computeTier(double notional, double distance, boolean highLiquidity) {
        if (highLiquidity) {
            if (notional >= 100_000_000 && distance <= 0.025)  return 4;
            if (notional >= 30_000_000  && distance <= 0.01)   return 3;
            if (notional >= 10_000_000  && distance <= 0.005)  return 2;
            if (notional >= 3_000_000   && distance <= 0.0025) return 1;
        } else {
            if (notional >= 10_000_000  && distance <= 0.05)   return 4;
            if (notional >= 1_000_000   && distance <= 0.02)   return 3;
            if (notional >= 500_000     && distance <= 0.01)   return 2;
            if (notional >= 300_000     && distance <= 0.005)  return 1;
        }
        return 0;
    }

    @Override
    public double maxDistance(boolean highLiquidity) {
        // Matches the widest (tier-4) max_distance of each table above, preserving the exact
        // early-break behavior the classifier has today (was: highLiquidity ? 0.025 : 0.05).
        return highLiquidity ? 0.025 : 0.05;
    }
}
```

> **Parity note**: `maxDistance()` must equal the tier-4 `max_distance` of the matching table
> (`0.025` HL / `0.05` normal). If you ever widen a tier-4 distance in `computeTier`, widen
> `maxDistance` to match, or the early-break will silently truncate qualifying levels.

### 3. `ThresholdClassificationRule` — immutable user-override leaf

Built off the hot path from a user's tier set; **not consumed by the classifier in Phase B**
(no context creates one yet). Included now so it can be unit-tested in isolation before Phase C
wires it in. Mirrors the default rule's highest-first evaluation but uses absolute thresholds
and ignores `highLiquidity`.

```java
package dev.abu.screener_backend.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A user-supplied classification rule for one (symbol, market). Absolute thresholds, no
 * high-liquidity special-casing. Backed by parallel primitive arrays sorted highest-tier-first
 * for allocation-free hot-path evaluation. Immutable — built once at user connect/update time.
 */
public final class ThresholdClassificationRule implements ClassificationRule {

    /** Construction input — one tier band. Decoupled from JPA; Phase C maps DB rows to these. */
    public record TierThreshold(int tier, double minNotional, double maxDistance) {}

    private final int[]    tiers;          // sorted by tier DESC (highest-first)
    private final double[] minNotionals;   // parallel to tiers
    private final double[] maxDistances;   // parallel to tiers
    private final double   widestDistance; // precomputed max of maxDistances

    private ThresholdClassificationRule(int[] tiers, double[] minNotionals,
                                        double[] maxDistances, double widestDistance) {
        this.tiers          = tiers;
        this.minNotionals   = minNotionals;
        this.maxDistances   = maxDistances;
        this.widestDistance = widestDistance;
    }

    /**
     * Builds an immutable rule from an unordered list of tier bands. Sorts highest-tier-first
     * and precomputes the widest distance. Callers (Phase C) are expected to pass validated
     * input — tiers in [1,4], no duplicates — but this method does not re-validate.
     */
    public static ThresholdClassificationRule of(List<TierThreshold> bands) {
        List<TierThreshold> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparingInt(TierThreshold::tier).reversed());

        int n = sorted.size();
        int[]    t  = new int[n];
        double[] mn = new double[n];
        double[] md = new double[n];
        double widest = 0.0;
        for (int i = 0; i < n; i++) {
            TierThreshold b = sorted.get(i);
            t[i]  = b.tier();
            mn[i] = b.minNotional();
            md[i] = b.maxDistance();
            if (b.maxDistance() > widest) widest = b.maxDistance();
        }
        return new ThresholdClassificationRule(t, mn, md, widest);
    }

    @Override
    public int computeTier(double notional, double distance, boolean highLiquidity) {
        // highLiquidity intentionally ignored — user thresholds are absolute.
        for (int i = 0; i < tiers.length; i++) {
            if (notional >= minNotionals[i] && distance <= maxDistances[i]) {
                return tiers[i]; // highest-first order → first match is the highest qualifying tier
            }
        }
        return 0;
    }

    @Override
    public double maxDistance(boolean highLiquidity) {
        return widestDistance;
    }
}
```

---

## Changes to Existing Classes

### `OrderBookClassifier` (`analysis/OrderBookClassifier.java`)

The state machine, top-K scratch buffers, change detection, and feed submission are **untouched**.
Only the rule plumbing changes.

**Remove:**
- `HIGH_LIQUIDITY_TICKERS` (line 65) — moved to `DefaultClassificationRule`.
- The private `computeTier(...)` method (lines 242–256) — moved to `DefaultClassificationRule`.
- The `import java.util.Set;` if it becomes unused.

**Add a field + constructor param:**

```java
private final OrderBookFeedStore feedStore;
private final DefaultClassificationRule defaultRule;   // NEW — default pass rule + isHighLiquidity source
private final Map<String, SymbolState> states = new HashMap<>();

public OrderBookClassifier(OrderBookFeedStore feedStore, DefaultClassificationRule defaultRule) {
    this.feedStore   = feedStore;
    this.defaultRule = defaultRule;
}
```

**`process(OrderBook ob)`** — change the `highLiquidity` source and pass the rule into `classify()`:

```java
// BEFORE (line 114):
boolean highLiquidity = HIGH_LIQUIDITY_TICKERS.contains(ob.getSymbol());
boolean bidsChanged = classify(bids, state.workBids, state, highLiquidity);
boolean asksChanged = classify(asks, state.workAsks, state, highLiquidity);

// AFTER:
boolean highLiquidity = defaultRule.isHighLiquidity(ob.getSymbol());
boolean bidsChanged = classify(bids, state.workBids, state, defaultRule, highLiquidity);
boolean asksChanged = classify(asks, state.workAsks, state, defaultRule, highLiquidity);
```

Everything else in `process()` (the SYNCED check, empty-book check, ADD/UPDATE/DROP transitions)
stays identical.

**`classify(...)`** — add a `ClassificationRule rule` parameter; replace the constant and the
private call with rule delegation:

```java
private boolean classify(TreeMap<Double, PriceLevelEntry> levels, ClassifiedLevel[] working,
                         SymbolState state, ClassificationRule rule, boolean highLiquidity) {
    state.topCount = 0;
    double maxDist = rule.maxDistance(highLiquidity);          // was: highLiquidity ? 0.025 : 0.05

    for (Map.Entry<Double, PriceLevelEntry> e : levels.entrySet()) {
        double price     = e.getKey();
        double quantity  = e.getValue().quantity;
        long   firstSeen = e.getValue().firstSeenMillis;
        double notional  = price * quantity;
        double distance  = e.getValue().distance;

        if (state.topCount == TOP_LEVELS && distance > maxDist) break;

        int tier = rule.computeTier(notional, distance, highLiquidity);   // was: computeTier(...)
        tryInsert(state, price, quantity, firstSeen, tier, notional, distance);
    }
    // ... the rest (top-K write-back + change detection) is unchanged ...
}
```

`tryInsert`, `isBetter`, `hasVisibleLevel`, and the three `submit*Update` helpers are unchanged.

> Note: in Phase B the classifier holds and uses only `defaultRule`. The `ClassificationRule`
> parameter on `classify()` is the seam Phase C needs — Phase C will call `classify(..., ctx.rule, ...)`
> for each active user context. Do not add the context loop in Phase B.

### `DisruptorShardManager` (`binance/disruptor/DisruptorShardManager.java`)

Inject the `DefaultClassificationRule` singleton and pass it into each per-shard classifier.
It is a stateless `@Component`, so sharing the one instance across all shards is safe.

```java
private final DisruptorProperties      props;
private final OrderBookProcessor       orderBookProcessor;
private final OrderBookFeedStore       feedStore;
private final DefaultClassificationRule defaultRule;   // NEW — @RequiredArgsConstructor injects it

// inside start(), per shard:
OrderBookClassifier classifier = new OrderBookClassifier(feedStore, defaultRule);   // was: new OrderBookClassifier(feedStore)
```

Add the import: `import dev.abu.screener_backend.analysis.DefaultClassificationRule;`.

### `DepthEventHandler`

**No change.** It already holds an `OrderBookClassifier` and calls `classifier.process(ob)`.

---

## Tests

Two focused unit tests under `src/test/java/dev/abu/screener_backend/analysis/`. The only logic
that moved is tier computation, so that is what we characterize.

### `DefaultClassificationRuleTest`

A characterization test pinning the moved thresholds so the refactor can't silently alter them:

- `isHighLiquidity("BTCUSDT")` true; `isHighLiquidity("DOGEUSDT")` false.
- Normal table: e.g. `(notional=10_000_000, distance=0.05, hl=false) → 4`; just-below-notional and
  just-beyond-distance edges → lower tier or 0. Cover one boundary per tier (1–4) plus a clear
  tier-0 case.
- High-liquidity table: e.g. `(100_000_000, 0.025, hl=true) → 4`; one boundary per tier; a tier-0 case.
- `maxDistance(true) == 0.025`, `maxDistance(false) == 0.05`.

### `ThresholdClassificationRuleTest`

- Build `of([ {4, 5_000_000, 0.04}, {1, 200_000, 0.01} ])` (deliberately unsorted input).
- Highest-first: a level matching both bands resolves to tier 4, not 1.
- A level matching only the tier-1 band → 1.
- A level matching no band (notional too low, or distance too far) → 0.
- `maxDistance(anything) == 0.04` (the widest), regardless of the boolean.
- Single-tier rule and a 4-tier rule both evaluate correctly.

### Smoke

`ScreenerBackendApplicationTests.contextLoads()` must still pass — confirms the new `@Component`
and the `DisruptorShardManager` constructor change wire up without context errors.

---

## Behavior-Identical Verification

Because Phase B must not change the default feed:

1. The default thresholds are moved **verbatim** — same constants, same order, same `>=`/`<=`
   operators. `DefaultClassificationRuleTest` pins them.
2. `maxDistance(highLiquidity)` returns the same `0.025`/`0.05` the old constant produced, so the
   early-break fires at the identical point.
3. `classify()`'s structure is unchanged apart from delegating two calls — the top-K selection,
   change detection, and submission paths are byte-identical.

Optional manual confirmation: run the app against live Binance with one WebSocket client and
confirm the SNAPSHOT/UPDATE stream looks the same as before the refactor (the `/run` or `/verify`
skill can drive this).

---

## Package Layout (Phase B additions)

```
src/main/java/dev/abu/screener_backend/analysis/
├── ClassificationRule.java             ← new (interface)
├── DefaultClassificationRule.java      ← new (@Component)
├── ThresholdClassificationRule.java    ← new (POJO, tested but not yet consumed)
└── OrderBookClassifier.java            ← refactored (rule param; HIGH_LIQUIDITY_TICKERS + computeTier removed)

src/main/java/dev/abu/screener_backend/binance/disruptor/
└── DisruptorShardManager.java          ← inject DefaultClassificationRule into each classifier

src/test/java/dev/abu/screener_backend/analysis/
├── DefaultClassificationRuleTest.java   ← new
└── ThresholdClassificationRuleTest.java ← new
```

---

## Implementation Steps

1. Create the `ClassificationRule` interface.
2. Create `DefaultClassificationRule`; move `computeTier`, `HIGH_LIQUIDITY_TICKERS`, and
   `isHighLiquidity` out of `OrderBookClassifier` into it; add `maxDistance`.
3. Create `ThresholdClassificationRule` (+ nested `TierThreshold` record).
4. Refactor `OrderBookClassifier`: add the `defaultRule` field/constructor param, update
   `process()` to source `highLiquidity` from `defaultRule` and pass the rule into `classify()`,
   add the `ClassificationRule rule` param to `classify()`, and replace the `maxDist` constant +
   `computeTier` call with `rule.maxDistance(...)` / `rule.computeTier(...)`. Delete the now-dead
   members.
5. Update `DisruptorShardManager` to inject and pass `DefaultClassificationRule`.
6. Add the two unit tests; run the full test suite (including `contextLoads`).
7. Update `CURRENT_STATE.md` (see below).

Steps 1–3 are independent. Step 4 depends on 1–2. Step 5 depends on 4. Tests depend on 1–3.

---

## CURRENT_STATE.md Update

- Add `analysis/ClassificationRule.java`, `analysis/DefaultClassificationRule.java`, and
  `analysis/ThresholdClassificationRule.java` to the project layout and a short description of each.
- Update the `OrderBookClassifier` entry: it no longer owns the thresholds or
  `HIGH_LIQUIDITY_TICKERS`; it now delegates tier computation to an injected `ClassificationRule`
  and the early-break to `rule.maxDistance()`.
- Update `DisruptorShardManager`: now injects `DefaultClassificationRule` into each shard classifier.
- Add the two test classes under Tests.
- In the status table, mark "Per-user classification — rule abstraction (Phase B)" complete and
  note that the runtime context wiring (Phase C) is still pending.

---

## What Phase B Does NOT Do

- No `UserClassificationRule`, `UserClassificationContext`, `UserOrderBookFeedStore`,
  `UserFeedRegistry`, or `activeUserContexts` array — that is **Phase C**.
- No two-pass hot path; `OrderBookClassifier.process()` still runs exactly one (default) pass.
- No broadcaster or feed-store changes; the global feed is the only feed.
- No reading of `classification_rules` rows by any runtime code; `ThresholdClassificationRule` is
  built only in tests for now.
- No live propagation of rule edits — that is **Phase D**.
