# Venue-Aware Classification and Feed

**Status**: implemented (steps 1–5, docs, tests). `canonical` was removed from `Instrument` along
the way (§3).
**Branch**: `feature/multi-exchange`, on top of `73fccaf`.
**Written**: 2026-10-01.

**Goal**: two instruments on different exchanges with the same symbol and market (Binance
`BTCUSDT` spot, MEXC `BTCUSDT` spot) get **separate** classification state, separate feed entries and
distinguishable WebSocket messages, while a user's custom rule for `BTCUSDT:SPOT` still applies to
**both**.

**Non-goals**:
- No per-venue user rules. No Flyway migration. `ClassificationRuleEntity` is untouched.
- No per-venue *default* tiers (see §7).
- No dense id-indexed arrays in the classifier (see §2, decision 4).
- No MEXC/Bybit code. With Binance as the only exchange, the only observable change is the new
  `exchange` field in WebSocket payloads.

**Related reading**

| File | Relationship |
|---|---|
| `.claude/docs/multi-exchange-progress.md` | Current state of the tree. Explicitly scoped classification/feed *out*; this plan brings them in. |
| `.claude/docs/orderbook-classification.md` | Classifier design. Update its keying description when this lands. |
| `.claude/docs/for-frontend/websocket-feed-api.md` | Client contract. Gains the `exchange` field (§5). |
| `.claude/docs/for-frontend/classification-rule-api.md` | Rule API. Clarify that symbols are exchange-independent (§5). |

---

## 1. The problem

`Instrument.feedKey` (`"BTCUSDT:SPOT"`, built from `nativeSymbol`) currently does two jobs:

| Job | Needs | Used by today |
|---|---|---|
| **Which rule applies?** | venue-agnostic key | `UserClassificationRules.configuredKeys()` / `ruleFor()`, broadcaster "configured" filter |
| **Whose state / feed entry is this?** | venue-specific key | `OrderBookClassifier.defaultStates`, `UserClassificationContext.states`, `OrderBookFeedStore` |

With one exchange the two coincide. With two, Binance and MEXC `BTCUSDT:SPOT` would share one
`SymbolState` and overwrite each other's feed entry.

There is a second, quieter coupling: the key is built from `nativeSymbol`. Native symbols differ
across exchanges (MEXC futures `BTC_USDT`, OKX `BTC-USDT`), so a rule stored as `BTCUSDT:SPOT` would
silently never match those instruments. The same applies to `DefaultClassificationRule.isHighLiquidity`,
which is called with `nativeSymbol`.

---

## 2. Decisions

1. **Rules stay universal.** A user's rule is keyed `(symbol, market)` and applies on every exchange.
   The DB shape and the `/api/rules` contract do not change. If per-venue rules are wanted later, the
   additive path is a nullable `exchange` column (null = all exchanges) with most-specific-first lookup.
2. **The rule key is built from `base + quote`, not `nativeSymbol`.** This makes it mean the same
   thing on every exchange. For Binance, `base + quote == nativeSymbol`, so existing DB rows keep
   matching byte-for-byte and no migration is needed.
3. **The state/feed key is venue-qualified**: `EXCHANGE:MARKET:SYMBOL`.
4. **String keys, not arrays.** State maps stay `HashMap` / `ConcurrentHashMap` keyed by a
   precomputed `String` with `computeIfAbsent`, exactly as today. `String` caches its hash, the key is
   allocated once at discovery, and the lookup costs far less than the `TreeMap` walk in `selectTopK`.
   Dense arrays were considered and rejected: their growth semantics (especially for the
   cross-shard user contexts) add complexity for a gain the pipeline doesn't need.
5. **The WebSocket payload gains an `exchange` field.** Its `symbol` field carries the normalized
   `BASEQUOTE` form (same as the rule API), not the exchange-native symbol, so the client can relate
   a feed row to a user rule.

---

## 3. New `Instrument` shape

Three precomputed strings, all built once in `Instrument.of`:

| Field | Value (Binance BTC spot) | Built from | Used by |
|---|---|---|---|
| `symbol` *(new)* | `BTCUSDT` | `base + quote` | `isHighLiquidity`, the payload's `symbol` field |
| `ruleKey` *(new)* | `BTCUSDT:SPOT` | `symbol + ":" + market` | user rule lookup, broadcaster "configured" filter |
| `feedKey` *(value changes)* | `BINANCE:SPOT:BTCUSDT` | `exchange + ":" + market + ":" + symbol` | default + user `SymbolState` maps, both `OrderBookFeedStore`s |

`symbol` is a third field beyond the two originally discussed. It's needed because `isHighLiquidity`
and the payload both want bare `BASEQUOTE`; deriving it from `ruleKey` or concatenating per message
would allocate on the hot path.

The `feedKey` name stays (its role — keying state and feed — doesn't change); only its value does.
The "must stay byte-identical to the DB format" contract in the `Instrument.of` javadoc **moves to
`ruleKey`**. Rewrite that javadoc accordingly; leaving it on `feedKey` would invite someone to
"fix" the wrong field.

`canonical` (`BTC/USDT`) is **removed**: nothing in the pipeline read it, and its only reader was
the debug `/api/tickers` view, which drops the field.

---

## 4. Steps

Each step compiles and passes tests on its own. Steps 1–3 change no observable behaviour on Binance.

### Step 1 — `Instrument` fields

- `exchange/Instrument.java`: add `symbol` and `ruleKey` components, change `feedKey`'s
  construction, rewrite the `@param` docs and the `of(...)` javadoc per §3.
- No caller changes: every construction goes through `Instrument.of` (registry + tests).
- New `InstrumentTest`:
  - `ruleKey` for Binance spot/futures is byte-identical to `nativeSymbol + ":" + market` (pins
    decision 2 — the guard that existing DB rows keep matching).
  - `ruleKey` and `symbol` are independent of `nativeSymbol` (e.g. native `BTC_USDT`, base `BTC`,
    quote `USDT` → `symbol` `BTCUSDT`, `ruleKey` `BTCUSDT:FUTURES`).
  - two instruments differing only in exchange get different `feedKey`s, the same `ruleKey`.
    (Only one `Exchange` constant exists today; if a test-only second exchange isn't practical,
    assert the `feedKey` format instead and note it.)

### Step 2 — Classifier: split rule lookup from state lookup

`analysis/OrderBookClassifier.process`:

```java
String stateKey = inst.feedKey();                                  // venue-specific
String ruleKey  = inst.ruleKey();                                  // venue-agnostic
boolean highLiquidity = defaultRule.isHighLiquidity(inst.symbol());

SymbolState defaultState = defaultStates.computeIfAbsent(stateKey, k -> new SymbolState());
...
for (UserClassificationContext ctx : ctxs) {
    ThresholdClassificationRule rule = ctx.rule().ruleFor(ruleKey);
    if (rule != null) {
        SymbolState state = ctx.states().computeIfAbsent(stateKey, k -> new SymbolState());
        classifyOne(inst, ob, state, rule, ctx.feedStore(), highLiquidity);
    }
}
```

Folding `configuredKeys().contains` + `ruleFor` into one `ruleFor` null check saves a lookup; keep
`configuredKeys()` for the broadcaster.

- `submit*Update`: pass `inst.feedKey()` as the store key (unchanged call, new value).
- `UserClassificationRules`: no code change; fix the javadoc — keys are `ruleKey`s (`BASEQUOTE:MARKET`).
- `UserClassificationContext` / `SymbolState`: javadoc only — state keys are `feedKey`s
  (`EXCHANGE:MARKET:SYMBOL`); "a given key is pinned to one shard" still holds because a `feedKey`
  identifies exactly one instrument.
- `ClassificationRuleService.buildRuntimeRule`: no change — it already builds
  `row.getSymbol() + ":" + row.getMarket()`, which is the `ruleKey` format. Add a comment pointing at
  `Instrument.ruleKey` so the two formats are visibly tied.

### Step 3 — `OrderBookUpdate` carries the `Instrument`

- `feed/OrderBookUpdate.java`: replace `String symbol, Market market` with `Instrument instrument`.
- `OrderBookClassifier.submit*Update`: construct with `inst`.
- `OrderBookFeedStore`: no code change (still `String`-keyed, now by `feedKey`); update the doc
  comments that say "per ticker".

### Step 4 — Broadcaster: filter by `ruleKey`, emit `exchange`

`feed/OrderBookBroadcaster.java`. The pending/snapshot maps are now keyed by `feedKey`, so the
"has this user configured it?" filter can no longer test the map key — it must test the update's
`ruleKey`.

- `buildKeyedBodies`: the result is only ever iterated, never looked up. Change it to return a
  `List<KeyedBody>` where `record KeyedBody(String ruleKey, String body)`, taking `ruleKey` from
  `update.instrument().ruleKey()`.
- Custom-session global filter: `if (!configured.contains(kb.ruleKey()))`.
- `mergedSnapshot`: filter the global snapshot by `e.getValue().instrument().ruleKey()`; the merged
  map stays keyed by `feedKey`, so Binance and MEXC entries for one symbol don't collide.
- JSON (`buildUpdateBody`, `appendTickerData`): emit
  `"exchange": inst.venue().exchange().name()`, `"symbol": inst.symbol()`, `"market": inst.market().name()`.
  Add `exchange` to **DROP** messages and to every **SNAPSHOT** entry, not only ADD/UPDATE.
- Field order: `seq`, `type`, `exchange`, `symbol`, `market`, … (`injectSeq` still relies on the body
  starting with `{`).

Semantics check to keep in mind while editing: a user with a `BTCUSDT:SPOT` rule receives their
**personal** feed for both Binance and MEXC BTCUSDT spot, and the **global** feed for neither.

### Step 5 — `isHighLiquidity`

- `OrderBookClassifier`: call with `inst.symbol()` (done in step 2).
- `DefaultClassificationRule`: no logic change; rename the parameter's doc to "normalized
  `BASEQUOTE` symbol". `HIGH_LIQUIDITY_TICKERS` and the `/api/rules/default` list stay `BASEQUOTE`.
  Optionally make `getDefaultRule()` reuse the set instead of repeating the literal list.

---

## 5. Docs and client contract

- `.claude/docs/for-frontend/websocket-feed-api.md`:
  - add `exchange` (currently always `"BINANCE"`) to every message example and to SNAPSHOT entries;
  - change the client keying guidance from `${symbol}:${market}` to `${exchange}:${market}:${symbol}`
    (§4 of that doc, including the `key` helper);
  - state that `symbol` is the normalized `BASEQUOTE` form and matches the rule API.
- `.claude/docs/for-frontend/classification-rule-api.md`: rules are exchange-independent; `symbol`
  is `BASEQUOTE` (e.g. `BTCUSDT`), never an exchange-native spelling; one rule applies to that
  symbol+market on every exchange.
- `.claude/docs/orderbook-classification.md`: update the keying description (state by `feedKey`,
  rule by `ruleKey`).
- `.claude/docs/multi-exchange-progress.md`: record this as done and add decisions 1–5 to the
  decisions log.

**Rollout**: the new field is additive and safe to ship immediately. The frontend currently keys its
state on `${symbol}:${market}` (per the feed API doc), so it **must** re-key on `exchange` before a
second exchange is enabled — otherwise two exchanges' rows merge into one and flicker.

---

## 6. Tests

| Test | Change |
|---|---|
| `InstrumentTest` *(new)* | §4 step 1 |
| `UserClassificationRulesTest` | No change — already uses `BASEQUOTE:MARKET` keys, which is the `ruleKey` format |
| `UserFeedRegistryTest` | No change expected (rule keys only); run to confirm |
| `DefaultClassificationRuleTest` | No change |
| `OrderBookClassifierTest` *(new, if feasible)* | Two instruments, same `ruleKey`, different `feedKey`: separate default `SymbolState`s and separate feed-store entries; one user rule produces personal entries for both |
| Broadcaster test *(new, if feasible)* | Custom session: global entries whose `ruleKey` is configured are filtered for **every** exchange; payload contains `exchange`, including DROP and SNAPSHOT |

The two "if feasible" tests need a second `Exchange` value to be meaningful. If adding a test-only
exchange isn't practical, write them against two instruments built with a hand-rolled `Instrument`
(the record constructor is public) whose `feedKey`s differ.

Verify end-to-end with `./mvnw test`, then a local run: the feed should be unchanged except for the
`exchange` field.

---

## 7. Known gaps, deliberately left

- **Default tiers are global.** A $1M wall is noise on Binance BTC and significant on a thinner
  exchange. Per-exchange defaults will likely be wanted before per-exchange user rules. Out of scope.
- **Cross-venue symbol aliases.** `1000PEPEUSDT` (Binance futures) vs `PEPEUSDT` elsewhere produce
  different `symbol`s, so a rule for one won't apply to the other. Acceptable — the rule simply
  doesn't match; nothing breaks.
- **Delisted `HIGH` books never get a `DROP`.** Pre-existing; belongs with P3 dynamic unsubscribe.
- **Rule validation is still Binance-only.** `ClassificationRuleService.validateTrackedTicker` looks
  up `(BINANCE venue, nativeSymbol)`. Equivalent today; with a second exchange it should accept any
  exchange that tracks the `ruleKey`. Recorded in `multi-exchange-progress.md` §5.
- **`feedKey` uniqueness is assumed, not enforced.** It is built from `base + quote`, so two
  instruments on one venue with the same base/quote would collide. Binance can't produce that today
  (spot symbols are `base + quote`; futures discovery is perpetual-only). An adapter that admits,
  e.g., dated futures must disambiguate before registering.
