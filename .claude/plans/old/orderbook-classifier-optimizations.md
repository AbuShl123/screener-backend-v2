# OrderBookClassifier — `classify()` Optimization & Cleanup

## Purpose

This document is the implementation guide for refactoring the top-K selection / apply
logic inside `OrderBookClassifier`. It captures three changes agreed on during design,
the reasoning behind each, and the exact target shape of the affected methods and
`SymbolState`.

The refactor is **behavior-preserving for the feed** (same ADD / UPDATE / DROP decisions,
same level contents, same ordering) except where explicitly noted. Its goals are: fewer
scratch arrays, simpler code, and — most importantly — **no `ClassifiedLevel` allocation
for the LOW books that make up the overwhelming majority of the universe**.

This work belongs in **Phase B** of the per-user classification plan
(`.claude/plans/per-user-classification-vision.md`). The file already takes a
`ClassificationRule` and drives its early-break off `rule.maxDistance(highLiquidity)`,
so this refactor is squarely in scope and does not conflict with later phases.

---

## Background — how `classify()` works today

`classify(levels, working, state, rule, highLiquidity)` runs two loops:

1. **Selection loop** — iterates the `TreeMap` from best (closest to spread) outward,
   computes each level's `tier` via `rule.computeTier(...)`, and inserts it into a
   pre-sorted top-K scratch buffer via `tryInsert`. Because both TreeMaps iterate in
   monotonically increasing distance order, it early-breaks once the buffer is full and
   `distance > rule.maxDistance(highLiquidity)`. The scratch buffer is kept sorted
   best→worst by `(tier DESC, notional DESC, distance ASC)`.

2. **Apply loop** — walks the top-K scratch and writes each entry into `working[i]`
   (`workBids` / `workAsks`), allocating a new `ClassifiedLevel` **only when the slot's
   value actually changed**. Returns `true` if any slot changed.

`process()` calls `classify()` once for bids and once for asks, then derives
`hasVisible` from the work arrays and decides ADD / UPDATE / DROP.

### Problems with the current shape

- **Seven parallel scratch arrays** in `SymbolState`: `topEntries` (declared but unused),
  `topPrices`, `topQuantities`, `topFirstSeen`, `topTiers`, `topNotionals`, `topDistances`.
  `topEntries` is dead code — `tryInsert` never touches it.
- **Allocation waste on LOW books.** Practice shows ~880 of ~890 books are LOW (never or
  rarely show a tier≥1 level). For every such book, every diff still runs the apply loop
  and churns up to 10 `ClassifiedLevel` objects that are immediately discarded — the
  near-spread tier-0 quantities shift constantly, so change-detection re-allocates almost
  every tick. This is exactly the GC pressure the project lists as a non-negotiable.

---

## Decision 1 — Store `Map.Entry` references, drop the parallel primitive arrays

Replace the five primitive scratch arrays with the already-declared `topEntries` array.
A `Map.Entry<Double, PriceLevelEntry>` already carries everything the apply loop and the
comparator need:

| Needed value | Source |
|---|---|
| `price`           | `e.getKey()` |
| `quantity`        | `e.getValue().quantity` |
| `firstSeenMillis` | `e.getValue().firstSeenMillis` |
| `distance`        | `e.getValue().distance` |
| `notional`        | `e.getKey() * e.getValue().quantity` (derived) |

`tier` is **not** on the entry — it is computed by the rule — so `topTiers` stays.

**Final scratch = `topEntries` + `topTiers` only** (per buffer; see Decision 3 for the
two-buffer split).

### Safety

Holding `TreeMap.Entry` references is safe here:
- Everything runs synchronously on the single shard consumer thread within one
  `process()` call. No diff is applied between selection and apply, so `quantity` /
  `distance` cannot mutate underneath the references.
- TreeMap entry nodes stay valid while their key is in the map; selection and apply
  happen before any structural change.
- `topEntries` is overwritten every call — no lifetime extension of map nodes, **no
  allocation** (the array is pre-allocated).

### Note

`new Map.Entry[TOP_LEVELS]` produces an unchecked-generic-array warning — already present
on the existing field. Keep `@SuppressWarnings("unchecked")` (or leave as-is, matching
current style).

---

## Decision 2 — Keep `isBetter()` on **notional**, but recompute it (don't store it)

We considered ranking on `quantity` instead of `notional` to avoid the `topNotionals`
array. **Rejected** — it changes ranking semantics, it is not a free optimization:

```
Level A: price 100, qty 10  → notional 1000
Level B: price  50, qty 15  → notional  750
```

By quantity B outranks A; by notional A outranks B. The screener ranks by **USD notional**
(e.g. "$1M+ orders"), and `tier` itself is derived from notional, so swapping to quantity
would re-rank tier-equal levels and produce visible inconsistencies.

**Resolution:** keep the comparator on notional, but **drop the `topNotionals` array** and
recompute notional from the entry when comparing:

- Candidate notional: computed once in `tryInsert` (`price * qty`), passed in.
- Incumbent notional: `topEntries[pos].getKey() * topEntries[pos].getValue().quantity`.

That's one multiply per comparison in an insertion sort over ≤5 elements — negligible.
Comparator order is unchanged: **`tier DESC, notional DESC, distance ASC`**.

---

## Decision 3 — Skip the apply loop entirely for LOW books (the main win)

For a book whose selected top-K are all tier-0 **and** which is currently LOW, there is no
feed output and no reason to populate the work arrays. Skip the apply loop → **zero
`ClassifiedLevel` allocation** for the LOW majority.

### Visibility without the apply loop

After selection the scratch is sorted tier-DESC first, so a side is visible iff:

```
topCount > 0 && topTiers[0] >= 1
```

This is provably equivalent to today's `hasVisibleLevel(workArray)`: every tier≥1
candidate is seen before the early-break (the break only fires once the buffer is full
**and** `distance > maxDist`, beyond which everything is tier-0), and tier≥1 always
outranks tier-0 in the buffer, so the best slot reflects the max tier present.

### The two-buffer requirement

The scratch buffer is currently **shared** between bids and asks — `classify()` consumes
it per side immediately. Splitting into "select, then maybe apply" breaks that: selecting
asks would overwrite the bid scratch before we apply it. And we cannot decide per side,
because if **asks** are visible the book is HIGH and we must still emit the top-5
**tier-0 bids** (the client shows both sides).

**Resolution: two scratch buffers per symbol** — one for bids, one for asks. Select both,
decide global visibility, then apply both from their own buffers. No re-iteration.

Even with two buffers we hold **4 small arrays** (`bidScratch` + `askScratch`, each
`topEntries` + `topTiers`) vs. the current 7, and the control flow is simpler.

### Behavior preserved

- Leaving `workBids` / `workAsks` untouched on LOW cycles is fine. On a later LOW→HIGH
  transition, `applyNewOrders` change-detects against the stale slots and re-allocates
  correct content; the ADD path ignores the `changed` flag anyway.
- The HIGH→LOW DROP path emits null/empty arrays (`submitDropUpdate`), so stale work-array
  contents are never read.

---

## Target `SymbolState`

```java
private static class SymbolState {
    ActivityLevel level = ActivityLevel.LOW;
    ClassifiedLevel[] workBids = new ClassifiedLevel[TOP_LEVELS];
    ClassifiedLevel[] workAsks = new ClassifiedLevel[TOP_LEVELS];

    // Two independent top-K scratch buffers so both sides can be selected
    // before either is applied (needed for the LOW-skip in process()).
    final Scratch bidScratch = new Scratch();
    final Scratch askScratch = new Scratch();
}

/**
 * Reusable top-K selection scratch. Maintained sorted best→worst by
 * (tier DESC, notional DESC, distance ASC); reset (topCount = 0) at the start
 * of each selectTopK() call. No heap allocation in steady state.
 */
private static class Scratch {
    int topCount = 0;
    @SuppressWarnings("unchecked")
    Map.Entry<Double, PriceLevelEntry>[] topEntries = new Map.Entry[TOP_LEVELS];
    int[] topTiers = new int[TOP_LEVELS];
}
```

Net change vs. today: `topEntries` becomes used; `topPrices`, `topQuantities`,
`topFirstSeen`, `topNotionals`, `topDistances` are deleted; scratch is duplicated into two
buffers wrapped in a small `Scratch` holder.

---

## Target methods

### `process(OrderBook ob)` — the LOW-skip restructure

Unchanged up to the point both sides are ready to be classified. The single `classify()`
call per side is replaced by `selectTopK` (always) + `applyNewOrders` (only when visible):

```java
boolean highLiquidity = defaultRule.isHighLiquidity(ob.getSymbol());

boolean bidVisible = selectTopK(bids, state.bidScratch, defaultRule, highLiquidity);
boolean askVisible = selectTopK(asks, state.askScratch, defaultRule, highLiquidity);

if (!bidVisible && !askVisible) {
    if (state.level == ActivityLevel.HIGH) {
        submitDropUpdate(key, ob);
        state.level = ActivityLevel.LOW;
    }
    return; // no apply loop, no ClassifiedLevel allocation
}

boolean bidsChanged = applyNewOrders(state.bidScratch, state.workBids);
boolean asksChanged = applyNewOrders(state.askScratch, state.workAsks);

if (state.level == ActivityLevel.LOW) {
    submitAddUpdate(key, ob, state);
    state.level = ActivityLevel.HIGH;
} else if (bidsChanged || asksChanged) {
    submitModifyUpdate(key, ob, state);
}
```

The `not-SYNCED` and `empty book` guards at the top of `process()` are unchanged.
`hasVisibleLevel(...)` is **removed** — visibility now comes from `selectTopK`'s return
value.

### `selectTopK(levels, scratch, rule, highLiquidity) → boolean` (was the first loop)

```java
private boolean selectTopK(TreeMap<Double, PriceLevelEntry> levels, Scratch s,
                           ClassificationRule rule, boolean highLiquidity) {
    s.topCount = 0;
    double maxDist = rule.maxDistance(highLiquidity);

    for (Map.Entry<Double, PriceLevelEntry> e : levels.entrySet()) {
        double distance = e.getValue().distance;
        // Distance is monotonically increasing in both TreeMaps; once the buffer is
        // full, nothing further can place. Everything beyond maxDist is tier-0.
        if (s.topCount == TOP_LEVELS && distance > maxDist) break;

        double notional = e.getKey() * e.getValue().quantity;
        int tier = rule.computeTier(notional, distance, highLiquidity);
        tryInsert(s, e, tier, notional, distance);
    }

    return s.topCount > 0 && s.topTiers[0] >= 1; // visible iff best slot is tier≥1
}
```

### `tryInsert(scratch, entry, tier, notional, dist)` — stores entry + tier only

Same insertion-sort-into-presorted-buffer logic as today, but writes `topEntries[pos]` and
`topTiers[pos]` instead of six primitive arrays. The incumbent's notional/distance for the
comparison are read from the entry already in the slot:

```java
private void tryInsert(Scratch s, Map.Entry<Double, PriceLevelEntry> entry,
                       int tier, double notional, double dist) {
    int pos = s.topCount < TOP_LEVELS ? s.topCount : TOP_LEVELS - 1;

    if (s.topCount == TOP_LEVELS
            && !isBetter(tier, notional, dist,
                         s.topTiers[pos], notionalOf(s.topEntries[pos]), distOf(s.topEntries[pos]))) {
        return;
    }

    while (pos > 0
            && isBetter(tier, notional, dist,
                        s.topTiers[pos - 1], notionalOf(s.topEntries[pos - 1]), distOf(s.topEntries[pos - 1]))) {
        s.topEntries[pos] = s.topEntries[pos - 1];
        s.topTiers[pos]   = s.topTiers[pos - 1];
        pos--;
    }

    s.topEntries[pos] = entry;
    s.topTiers[pos]   = tier;

    if (s.topCount < TOP_LEVELS) s.topCount++;
}
```

Tiny private helpers keep `tryInsert` readable:

```java
private static double notionalOf(Map.Entry<Double, PriceLevelEntry> e) {
    return e.getKey() * e.getValue().quantity;
}
private static double distOf(Map.Entry<Double, PriceLevelEntry> e) {
    return e.getValue().distance;
}
```

`isBetter(int tierA, double notionalA, double distA, int tierB, double notionalB, double distB)`
is **unchanged** (`tier DESC, notional DESC, distance ASC`).

### `applyNewOrders(scratch, working) → boolean` (was the second loop)

Identical logic to today's apply loop, reading from the entry instead of the primitive
arrays. Returns whether any slot changed.

```java
private boolean applyNewOrders(Scratch s, ClassifiedLevel[] working) {
    boolean changed = false;
    for (int i = 0; i < TOP_LEVELS; i++) {
        if (i < s.topCount) {
            Map.Entry<Double, PriceLevelEntry> e = s.topEntries[i];
            double price     = e.getKey();
            double quantity  = e.getValue().quantity;
            long   firstSeen = e.getValue().firstSeenMillis;
            double distance  = e.getValue().distance;
            int    tier      = s.topTiers[i];

            ClassifiedLevel existing = working[i];
            if (existing == null
                    || existing.price()           != price
                    || existing.quantity()        != quantity
                    || existing.tier()            != tier
                    || existing.firstSeenMillis() != firstSeen
                    || existing.distance()        != distance) {
                working[i] = new ClassifiedLevel(price, quantity, tier, firstSeen, distance);
                changed = true;
            }
        } else if (working[i] != null) {
            working[i] = null;
            changed = true;
        }
    }
    return changed;
}
```

---

## Equivalence argument (why the feed is unchanged)

| Aspect | Old | New | Same? |
|---|---|---|---|
| Selection set | all entries until buffer full & `distance > maxDist` | identical | ✅ |
| Comparator | `tier DESC, notional DESC, distance ASC` | identical (notional recomputed) | ✅ |
| Visibility | `hasVisibleLevel(work)` = any work slot tier>0 | `topTiers[0] >= 1` = best selected tier≥1 | ✅ equivalent |
| Work-array contents when HIGH | apply loop output | identical apply loop output | ✅ |
| ADD/UPDATE/DROP decisions | from work arrays | from `bidVisible/askVisible` + `changed` flags | ✅ equivalent |
| LOW→LOW cycle | apply loop runs, allocates, output discarded | apply loop skipped | ✅ no feed effect, allocation removed |

The only observable difference is the **absence of throwaway allocation** on LOW cycles —
no change to any client-visible output.

---

## Impact

- **Code:** smaller and clearer — 5 fewer arrays, `classify()` split into the
  self-describing `selectTopK` / `applyNewOrders`, dead `topEntries` field now used.
- **Speed / GC:** Decisions 1–2 are marginal (fewer memory writes; one cheap multiply per
  comparison). **Decision 3 is the meaningful one** — at hundreds of thousands of diffs/sec
  with the vast majority of books LOW, removing ~10 allocations *and* an apply loop per diff
  per LOW book is sustained GC-pressure relief, directly serving the project's
  GC-efficiency non-negotiable. This is a throughput/GC-stability improvement, not a raw
  latency cliff.
- **Per-user (Phases C–D) compounding:** when a symbol is classified against N user rules,
  most `(user, symbol)` views are also LOW and skip their apply loop and allocations. Each
  `UserClassificationContext` carries its own per-symbol scratch, so the two-buffer design
  scales the same way per context.

---

## Implementation checklist

1. Add the `Scratch` inner class; replace the seven scratch fields on `SymbolState` with
   `bidScratch` + `askScratch`; keep `workBids` / `workAsks`.
2. Rewrite the first loop as `selectTopK(levels, scratch, rule, highLiquidity) → boolean`.
3. Update `tryInsert` to store `(entry, tier)` and read incumbents via `notionalOf` /
   `distOf`; add those two helpers. Leave `isBetter` untouched.
4. Rewrite the second loop as `applyNewOrders(scratch, working) → boolean`.
5. Restructure `process()` per the snippet above; delete `hasVisibleLevel`.
6. Confirm no other caller references the removed `classify()` signature or
   `hasVisibleLevel`.
7. Update the class Javadoc (the "GC-efficient working buffers" section) to describe the
   two-buffer scratch and the LOW-skip.
8. Update `CURRENT_STATE.md` `OrderBookClassifier` entry to note the select/apply split and
   LOW-skip optimization.

### Suggested verification

- Unit test `selectTopK` ordering and visibility against a hand-built `TreeMap` (highest
  notional / closest distance wins; tier≥1 ⇒ visible; all tier-0 ⇒ not visible).
- Unit test the LOW→HIGH→LOW transitions emit ADD / UPDATE / DROP exactly once each and
  that a LOW→LOW cycle allocates no `ClassifiedLevel` (can assert via reference identity of
  work-array slots remaining unchanged across two LOW ticks).
- The `test/java/.../analysis/` directory already exists (untracked) — add the tests there.
```