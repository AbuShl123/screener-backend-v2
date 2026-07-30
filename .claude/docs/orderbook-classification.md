# Order Book Classification — Backend Implementation

## Overview

Classification is the stage that turns a fully synchronised local order book into the small,
opinionated payload a user actually sees. A synced book can hold thousands of price levels; a client
receives **at most 5 levels per side, per ticker**, and only for tickers that currently contain
something worth showing.

The module answers three questions on every single order book update:

1. **Which tier does each price level fall into?** — a `(notional, distance)` band lookup.
2. **Which 5 levels per side survive?** — a top-K selection over the whole book.
3. **Is this order book worth sending at all?** — the visibility gate and the per-symbol
   `LOW`/`HIGH` activity state machine.

It runs in two passes: a **default pass** against the global, system-wide rule, and a **per-user
pass** for every connected user who has custom rules for that `(symbol, market)`. The two passes
write into two different feed stores, and the broadcaster merges them per session so that every
client receives exactly one authoritative message per ticker per tick.

Packages involved:

| Package | Role in classification |
|---|---|
| `binance/orderbook/` | Produces the input: a `SYNCED` `OrderBook` with `distance` already computed per level |
| `binance/disruptor/` | Owns the consumer threads; calls the classifier once per depth event |
| `analysis/` | The classifier itself, the rule model, per-user contexts, the registry |
| `analysis/rule/` | `/api/rules` CRUD, JPA persistence, live rule-update event |
| `feed/` | Feed stores (coalescing + snapshot), the 100ms broadcaster, the wire model |
| `ws/` | Jakarta WebSocket endpoint and per-session send machinery |

---

## 1. The input: what the classifier receives

`DepthEventHandler.onEvent` (`binance/disruptor/DepthEventHandler.java:20`) is the single entry
point. For every ring buffer event it first runs the order-book sync machine, then — if a book was
returned — hands that book to the classifier:

```java
OrderBook ob = obSyncMachine.process(event);
if (ob != null) classificationModule.process(ob);
```

The classifier therefore runs **once per depth diff**, on the Disruptor consumer thread that owns
that shard. It never allocates per-message garbage in the common case (see §4.5).

Each level in the book is a `PriceLevelEntry` (`binance/orderbook/PriceLevelEntry.java`):

```java
public double quantity;
public final long firstSeenMillis;   // level lifetime — when this price first appeared
public double distance;              // fraction of mid-price: 0.05 == 5%
```

`distance` is recomputed for every surviving level after each applied diff, inside
`OrderBook.computeDistance()` (`binance/orderbook/OrderBook.java:332`), which also sweeps away
everything outside `±screener.orderbook.price-filter-threshold` (default `0.1` = ±10%):

```java
double midPrice = (bids.firstKey() + asks.firstKey()) / 2.0;
e.getValue().distance = Math.abs(key - midPrice) / midPrice;
```

**Unit convention:** `distance` is a *fraction*, never a percentage, everywhere in the backend —
`PriceLevelEntry.distance`, every rule threshold, the persisted `max_distance` column, and the
`distance` field on the wire. Converting to `%` is the client's job.

Two consequences matter for classification:

- The price filter is the hard ceiling on what any rule can ever match. A rule with
  `maxDistance = 0.2` is meaningless because levels that far out were already swept from the
  `TreeMap` — which is why rule validation clamps `maxDistance` to the live
  `priceFilterThreshold` (`analysis/rule/ClassificationRuleService.java:59`).
- Notional is computed by the classifier, not stored: `notional = price × quantity`, in quote
  currency (USD for the `*USDT` universe).

---

## 2. The tier model

### 2.1 What a tier actually is

A tier is **a pair of threshold boundaries**, not a ranking. Each tier `1..4` declares:

- `minNotional` — the level's `price × quantity` must be **at least** this;
- `maxDistance` — the level's distance from mid-price must be **at most** this.

A level is assigned a tier only when it satisfies **both** conditions simultaneously. If it
satisfies none, it is **tier 0** — the fall-through "not interesting" bucket.

> **Tier number is not a level of importance.** Both thresholds grow together as the tier number
> rises. Tier 1 has the *smallest* notional requirement and the *tightest* distance window; tier 4
> has the *largest* notional requirement and the *widest* distance window. So in practice tier-1
> levels sit closest to the spread and tier-4 levels sit farthest from it. The underlying idea:
> **the farther an order is from the current price, the bigger it has to be before it is worth a
> user's attention.** Proximity buys relevance cheaply; distance has to be paid for with size.

Because the bands are nested (`tier4.maxDistance > tier3.maxDistance > …`), a level can qualify for
several tiers at once. `computeTier` walks the bands **highest-tier-first** and returns the first
match, so a level that qualifies for both tier 1 and tier 4 resolves to **tier 4** — i.e. a huge
wall sitting right at the spread is reported as a tier-4 wall, not a tier-1 near-spread level.

### 2.2 The default rule

`DefaultClassificationRule` (`analysis/DefaultClassificationRule.java`) is a single stateless
`@Component` shared by every shard. It holds two threshold tables.

**Standard tickers:**

| Tier | Min notional (USD) | Max distance from mid |
|---|---|---|
| 4 | 10,000,000 | 0.05 (5%) |
| 3 | 1,000,000 | 0.02 (2%) |
| 2 | 500,000 | 0.01 (1%) |
| 1 | 200,000 | 0.005 (0.5%) |

**High-liquidity tickers** — `BTCUSDT`, `ETHUSDT`, `SOLUSDT` — use tighter thresholds, because their
books are so deep that the standard table would classify nearly everything as tier 4:

| Tier | Min notional (USD) | Max distance from mid |
|---|---|---|
| 4 | 100,000,000 | 0.025 (2.5%) |
| 3 | 30,000,000 | 0.01 (1%) |
| 2 | 10,000,000 | 0.005 (0.5%) |
| 1 | 3,000,000 | 0.0025 (0.25%) |

Reading the standard table as boundaries: a $250k order within 0.5% of mid is tier 1; the same
$250k order at 1.5% is tier 0 (invisible) because nothing but tier 3/4 reaches that far and it is
far too small for either; to be visible at 1.5% out it would need ≥ $1M (tier 3), and at 4% out it
would need ≥ $10M (tier 4).

The `TierDto` lists are the **single source of truth** — both the hot-path evaluator and the
client-facing `GET /api/rules/default` payload derive from them, so the advertised numbers can never
drift from the ones actually applied. The runtime evaluators (`NORMAL_RULE`, `HIGH_LIQUIDITY_RULE`)
are pre-built `ThresholdClassificationRule` instances created once at class-init.

`isHighLiquidity(symbol)` is evaluated **once per book** in `OrderBookClassifier.process` and
threaded down into the rule calls, so the set lookup happens once per depth event rather than once
per price level.

### 2.3 The rule abstraction

`ClassificationRule` (`analysis/ClassificationRule.java`) is a pure, stateless, two-method
interface:

```java
int    computeTier(double notional, double distance, boolean highLiquidity);
double maxDistance(boolean highLiquidity);   // widest distance any tier can match
```

`maxDistance` exists purely as a hot-path optimisation — see the early break in §4.2.

Two implementations:

- **`DefaultClassificationRule`** — picks one of two tables based on `highLiquidity`, then
  delegates.
- **`ThresholdClassificationRule`** (`analysis/ThresholdClassificationRule.java`) — the general
  evaluator. Immutable; built from a list of `TierThreshold(tier, minNotional, maxDistance)` records
  sorted tier-descending into three parallel primitive arrays (`int[] tiers`, `double[] minNotionals`,
  `double[] maxDistances`) plus a precomputed `widestDistance`. Evaluation is an allocation-free
  linear scan of at most 4 entries:

  ```java
  for (int i = 0; i < tiers.length; i++) {
      if (notional >= minNotionals[i] && distance <= maxDistances[i]) return tiers[i];
  }
  return 0;
  ```

  It ignores `highLiquidity` entirely — **user thresholds are absolute**, with no per-symbol
  special-casing. The user asked for exactly these numbers, so exactly these numbers are applied.

---

## 3. Default vs. per-user rules

| | Default rule | Per-user rule |
|---|---|---|
| Scope | Every `(symbol, market)` | Only the `(symbol, market)` keys the user configured |
| Source | Hard-coded tables in `DefaultClassificationRule` | `classification_rules` table, per user |
| High-liquidity special-casing | Yes (3 symbols) | No — absolute thresholds |
| Feed store | One global `OrderBookFeedStore` bean | One `OrderBookFeedStore` per connected custom user |
| Lifecycle | Application lifetime | Built at WebSocket connect, rebuilt on rule change, discarded on last disconnect |
| Cost when unused | — | Zero: no context, no extra hot-path work |

A per-user rule **replaces** the default for that key — it does not layer on top of it. For every
key a user has *not* configured, they receive the default classification, merged in by the
broadcaster (§6.2).

### 3.1 `UserClassificationRules` — the per-user lookup table

`analysis/UserClassificationRules.java` is a `Map<String, ThresholdClassificationRule>` keyed by
`"SYMBOL:MARKET"` plus a cached `keySet()`. It deliberately does **not** implement
`ClassificationRule`: the classifier does an O(1) `configuredKeys().contains(key)` membership test
first, and only then fetches the leaf rule for that key. Keys absent from the map are never touched
by the user pass.

### 3.2 `UserClassificationContext` — a user's classification session

`analysis/UserClassificationContext.java` bundles everything one connected custom-rules user needs:

```java
UUID userId;
UserClassificationRules rule;                       // immutable, built at connect time
OrderBookFeedStore feedStore;                       // this user's private feed
ConcurrentHashMap<String, SymbolState> states;      // per-symbol activity state
```

One context is shared by all of that user's concurrent sessions (browser tabs). It is shared across
**all shards** — a user's configured symbols hash to different shards — hence the
`ConcurrentHashMap` for `states`. Each individual `SymbolState` *value* is still single-threaded,
because its key is pinned to one shard.

---

## 4. The classifier

`analysis/OrderBookClassifier.java`. One instance **per Disruptor shard**, created by
`DisruptorShardManager.start()` and never shared between shards.

### 4.1 Two-pass `process(OrderBook)`

```java
public void process(OrderBook ob) {
    String key = ob.getSymbol() + ":" + ob.getMarket();
    boolean highLiquidity = defaultRule.isHighLiquidity(ob.getSymbol());   // once per book

    // Pass 1 — default, always
    SymbolState defaultState = defaultStates.computeIfAbsent(key, k -> new SymbolState());
    classifyOne(ob, key, defaultState, defaultRule, feedStore, highLiquidity);

    // Pass 2 — per user, only for contexts that configured this key
    for (UserClassificationContext ctx : activeUserContexts) {
        if (ctx.rule().configuredKeys().contains(key)) {
            classifyOne(ob, key,
                        ctx.states().computeIfAbsent(key, k -> new SymbolState()),
                        ctx.rule().ruleFor(key),
                        ctx.feedStore(), highLiquidity);
        }
    }
}
```

`activeUserContexts` is a `volatile` array, **swapped atomically, never mutated in place**, by
`UserFeedRegistry` via `DisruptorShardManager.setActiveUserContexts(...)`. Every shard classifier
receives the *same* array reference, because any shard may hold any user's configured symbol. When
no custom-rule user is connected, the loop body never executes and the entire per-user cost is one
volatile read.

`classifyOne` is the whole state machine, parameterised by the `(state, rule, feedStore)` triple —
the default and user passes are literally the same code on different data.

### 4.2 Top-K selection

`selectTopK(levels, scratch, rule, highLiquidity)` walks the side's `TreeMap` from best (closest to
the spread) outward — bids are reverse-ordered, asks natural-ordered, so **distance increases
monotonically** as the iteration proceeds. For each level:

```java
double notional = price * quantity;
int tier = rule.computeTier(notional, distance, highLiquidity);
tryInsert(scratch, entry, tier, notional, distance);
```

Levels are ranked by:

```
tier DESC, notional DESC, distance ASC
```

`tryInsert` maintains a pre-allocated, pre-sorted 5-slot buffer (`SymbolState.Scratch`): if the
buffer is full and the candidate does not beat the current worst slot, it is discarded; otherwise
elements shift right in place until the sorted position is found. **No heap allocation.**

The monotonic distance ordering enables an early break:

```java
if (s.topCount == TOP_LEVELS && distance > maxDist) break;
```

Once the buffer holds 5 entries and the iteration has passed the rule's widest `maxDistance`,
everything remaining is necessarily tier 0 and already out-ranked — so most books are only partially
scanned rather than fully walked.

### 4.3 The visibility gate — which books reach the user

`selectTopK` returns:

```java
return s.topCount > 0 && s.topTiers[0] >= 1;   // visible iff the best slot is tier ≥ 1
```

Because the scratch buffer is sorted tier-descending, slot 0 holds the highest tier present anywhere
in that side of the book. So a side is **visible** iff the book contains at least one level of
tier ≥ 1.

This is the rule that decides which order books are transmitted at all:

- **A book whose levels are *all* tier 0 is not sent.** No `ClassifiedLevel` is even allocated for
  it. If every resting order is too small for its distance, the ticker simply does not appear in the
  user's feed. Across ~1000 tracked streams, this is the overwhelming majority of books at any given
  moment.
- **Visibility is evaluated per side, but emission is per book.** Both sides are *selected* before
  either is *applied*, because a visible ask side forces the (possibly tier-0) bid side to be sent
  too — the client needs both sides of the book to render it:

  ```java
  boolean bidVisible = selectTopK(bids, state.bidScratch, rule, highLiquidity);
  boolean askVisible = selectTopK(asks, state.askScratch, rule, highLiquidity);
  if (!bidVisible && !askVisible) { /* LOW: skip entirely */ }
  ```
- **Tier-0 levels are used as filler.** Once a book is visible, the top-5 buffer is filled
  regardless of tier, so clients do receive `"tier": 0` entries — they pad out the 5 slots when
  fewer than 5 qualifying levels exist. Tier 0 on the wire means "shown for context, cleared no
  threshold".
- **`tier` on the wire is bounded to `0..4`.**

### 4.4 The per-symbol activity state machine

Each `(symbol, market)` key, **per classification context**, carries a `SymbolState`
(`analysis/SymbolState.java`) that is either `LOW` or `HIGH`:

| Current | Condition | Emitted |
|---|---|---|
| `LOW` | a side became visible | **`ADD`** |
| `HIGH` | still visible, top-5 changed | **`UPDATE`** |
| `HIGH` | still visible, top-5 unchanged | *nothing* |
| `HIGH` | no side visible any more | **`DROP`** |
| `HIGH` | book left `SYNCED`, or a side went empty | **`DROP`** |
| `LOW` | still not visible | *nothing* |

The non-`SYNCED` check comes first: only `SYNCED` books ever produce classification output, so a
book that loses sequence continuity and falls back to `PENDING`/`SNAPSHOT_REQUESTED` emits a single
`DROP` and goes quiet until it re-syncs. `DROP` is also how a ticker leaves the screener when it is
delisted or stops trading.

Because the state lives on the `SymbolState` of a *context*, the same ticker can be `HIGH` globally
and `LOW` for a user whose custom thresholds are stricter — that user gets a `DROP` for it while
everyone else keeps receiving `UPDATE`s.

### 4.5 Change detection and allocation discipline

`applyNewOrders(scratch, working)` copies the selected entries into the state's persistent
`workBids`/`workAsks` arrays and allocates a new `ClassifiedLevel` **only when a slot's value
actually changed** (price, quantity, tier, `firstSeenMillis` or distance):

```java
if (existing == null || existing.price() != price || existing.quantity() != quantity
        || existing.tier() != tier || existing.firstSeenMillis() != firstSeen
        || existing.distance() != distance) {
    working[i] = new ClassifiedLevel(price, quantity, tier, firstSeen, distance);
    changed = true;
}
```

It returns whether anything changed, which is what distinguishes a silent `HIGH → HIGH` from an
emitted `UPDATE`. Slots beyond `topCount` are nulled — the arrays use a **null sentinel**, so
consumers iterate until the first `null`.

The apply stage is skipped entirely for `LOW` books, so the dominant case allocates nothing at all.
When an event *is* emitted, `workBids.clone()` / `workAsks.clone()` are handed to the feed store so
the classifier can keep mutating its own arrays without racing the broadcaster.

---

## 5. The feed store

`feed/OrderBookFeedStore.java`. Two instances exist per user in the worst case: the global
`@Component` (default classification) and one plain `new` instance per active
`UserClassificationContext`.

It holds two maps of `"SYMBOL:MARKET" → OrderBookUpdate`:

- **`snapshotMap`** — the current live state of every visible ticker. Used to build a `SNAPSHOT`
  for a newly connected client. `DROP` removes the key; `ADD`/`UPDATE` overwrite it.
- **`pendingRef`** — the changes accumulated in the current 100ms window. `drainPending()` atomically
  swaps it for a fresh empty map and returns the old one.

`submit(key, update)` is called by the classifier on a consumer thread; `drainPending()` and
`getSnapshot()` are called by the broadcaster thread. Multiple shard threads write, one thread
drains.

**Coalescing.** Because a fast ticker can fire many times inside one 100ms window, `submit` merges
into `pendingRef` rather than appending:

| Existing | Incoming | Result |
|---|---|---|
| `ADD` | `ADD` / `UPDATE` | `UPDATE` (client treats them identically) |
| `ADD` | `DROP` | key removed — "never happened" |
| `UPDATE` | `ADD` / `UPDATE` | latest wins |
| `UPDATE` | `DROP` | `DROP` |
| `DROP` | anything | incoming |

The net effect is **at most one event per ticker per feed per 100ms window**, always carrying the
newest level arrays.

`OrderBookUpdate` (`feed/OrderBookUpdate.java`) is the internal wire model:

```java
record OrderBookUpdate(String symbol, Market market, FeedEventType type,
                       ClassifiedLevel[] bids, ClassifiedLevel[] asks) {}
record ClassifiedLevel(double price, double quantity, int tier,
                       long firstSeenMillis, double distance) {}
```

`DROP` carries `null` arrays. Never compare two `OrderBookUpdate`s with `equals()` — record equality
on array fields is reference equality.

---

## 6. The broadcaster

`feed/OrderBookBroadcaster.java`. A single `@Scheduled(fixedDelay = 100)` method — all broadcaster
logic runs on one thread, so there is no concurrency inside the class. It is the **only** component
that touches sessions, and it never touches a TCP socket.

### 6.1 One tick

```
if no sessions → return
globalPending  = globalFeed.drainPending()
for each active context: ctxPending[ctx] = ctx.feedStore().drainPending()   // once per context
for each session:
    NEED_SNAPSHOT → build snapshot body, resetSeq, enqueue, mark READY
    READY         → build/reuse update bodies, inject per-session seq, enqueue batch
    enqueueBatch() == false → session.disconnect()   (slow-client eviction)
```

The global feed is drained **even if every session is `NEED_SNAPSHOT`** — skipping would let `DROP`
events pile up across cycles and later reach a `READY` session that already holds a current
snapshot. Each context's personal feed is drained exactly **once per tick**, not once per session,
because one context can back several sessions.

JSON is hand-built into a single reused `StringBuilder` (no Jackson on this path). Update bodies are
built **once per tick and shared across all sessions**; only the `seq` prefix is per-session, which
`injectSeq` splices on:

```java
sb.append("{\"seq\":").append(seq).append(',').append(body, 1, body.length());
```

### 6.2 The per-user merge

This is where default and custom classification come back together. Bodies are built **keyed** by
`"SYMBOL:MARKET"` precisely so they can be filtered per session:

- A **default session** (`context == null`) receives the global bodies verbatim.
- A **custom session** receives:
  1. every body from its **personal** feed (its configured keys, with its custom tiers), plus
  2. every body from the **global** feed whose key it has **not** configured.

```java
Set<String> configured = ctx.rule().configuredKeys();
for (String body : personalBodies.values()) batch.add(injectSeq(body, seq++));
for (var e : globalBodies.entrySet())
    if (!configured.contains(e.getKey())) batch.add(injectSeq(e.getValue(), seq++));
```

This guarantees **exactly one authoritative update per `(symbol, market)` per session per tick** —
custom tiers where the user configured them, default tiers everywhere else, and never both.

Snapshots use the same rule via `mergedSnapshot(ctx)`: the global snapshot minus the user's
configured keys, unioned with the user's personal snapshot. Snapshots are rare (connect / explicit
`SNAPSHOT_REQUEST`), so building a small per-session `LinkedHashMap` there is acceptable.

**Known cold-start gap:** a user connecting mid-drain may appear in the session list for a context
that was not yet in `activeContexts()` when `ctxPending` was built. Its personal feed has not been
drained that tick; the broadcaster treats it as empty and it drains on the next tick.

---

## 7. Delivery to the client

See `.claude/docs/websocket-server.md` for the full server design and
`.claude/docs/for-frontend/websocket-feed-api.md` for the client-facing contract. The
classification-relevant parts:

### 7.1 Connect

`ws(s)://<host>/ws?token=<accessToken>` — the JWT is a query parameter because browsers cannot set
headers on a WebSocket handshake. `ScreenerWebSocketEndpoint.onOpen` (`ws/ScreenerWebSocketEndpoint.java:43`):

1. Validate the JWT → close `1008 VIOLATED_POLICY` if missing/invalid.
2. `entitlementService.hasAccess(user)` → close `1008` ("Subscription required") if not entitled.
3. Create `UserWebSocketSession`.
4. **`userFeedRegistry.onUserConnect(userId, session)`** — loads the user's rules and attaches the
   context, *before* the broadcaster ever sees the session, so a custom-rules user can never be
   served a plain global snapshot.
5. Start the per-session virtual-thread send loop; register with the broadcaster.
6. Record connection analytics (failures here never tear down the session).

The session's initial status is `NEED_SNAPSHOT`, so the next drain tick (~100ms) pushes a full
snapshot automatically — the client sends nothing.

### 7.2 Wire format

```json
{"seq": 1, "type": "SNAPSHOT", "data": [
  {"symbol":"BTCUSDT","market":"FUTURES","bids":[…],"asks":[…]}, …]}

{"seq": 2, "type": "UPDATE", "symbol":"BTCUSDT","market":"FUTURES","bids":[…],"asks":[…]}
{"seq": 3, "type": "ADD",    "symbol":"BTCUSDT","market":"FUTURES","bids":[…],"asks":[…]}
{"seq": 4, "type": "DROP",   "symbol":"BTCUSDT","market":"FUTURES"}
```

Each level:

```json
{"price": 65432.1, "quantity": 0.85, "tier": 2, "firstSeenMillis": 1716680000000, "distance": 0.0123}
```

- `tier` — `0..4`, as classified for **this** user (custom if configured, default otherwise).
- `distance` — raw fraction at full float precision; clients do `×100` and round for display.
- `bids`/`asks` — best-first, **at most 5**, possibly fewer.
- `ADD` and `UPDATE` are structurally identical and clients should treat both as an upsert; because
  of coalescing and per-user timing, an `UPDATE` with no preceding `ADD` is normal.
- `DROP` carries no levels — remove the ticker from local state.

Client → server: only the raw string `SNAPSHOT_REQUEST`, which sets `NEED_SNAPSHOT` and gets a fresh
merged snapshot on the next tick.

### 7.3 Backpressure

Each session owns an `ArrayBlockingQueue<List<String>>` of capacity 32 (≈3.2s of drain cycles) and
one virtual thread doing `queue.take()` → `sendText(...)`. A stalled client parks only its own
virtual thread. If its queue fills, the broadcaster evicts it (`disconnect()` → `GOING_AWAY`) rather
than dropping individual messages — a dropped message would leave the client with an unrecoverable
partial book. On reconnect it receives a fresh snapshot.

---

## 8. Per-user rule APIs

`analysis/rule/ClassificationRuleController.java`, mounted at **`/api/rules`**. Every endpoint
requires a valid JWT (`SecurityConfig`'s `anyRequest().authenticated()` catch-all), and every
endpoint except `/default` additionally requires **active entitlement** — `authorizedUserId()`
throws `403 "Active subscription required"` otherwise. The `userId` is always taken from the JWT
principal, never from the request body, so a user can only ever read or modify their own rules.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/rules/default` | The two default threshold tables + the high-liquidity symbol list |
| `GET` | `/api/rules` | All of the caller's rules, grouped per `(symbol, market)` |
| `GET` | `/api/rules/{symbol}/{market}` | One rule; `404` if not configured; `400` on an unknown market |
| `PUT` | `/api/rules` | Bulk upsert — replaces the tier set for each target |
| `DELETE` | `/api/rules` | Bulk delete by target list; deleting a non-existent rule is a no-op |

**Upsert body** — one shape covers both "same rule for many tickers" and "different rules in one
call":

```json
{
  "assignments": [
    {
      "rule": { "tiers": [
        { "tier": 1, "minNotional": 150000,  "maxDistance": 0.004 },
        { "tier": 2, "minNotional": 400000,  "maxDistance": 0.01  },
        { "tier": 3, "minNotional": 900000,  "maxDistance": 0.03  },
        { "tier": 4, "minNotional": 5000000, "maxDistance": 0.08  }
      ]},
      "targets": [ { "symbol": "BTCUSDT", "market": "FUTURES" },
                   { "symbol": "ETHUSDT", "market": "SPOT" } ]
    }
  ]
}
```

**Delete body:** `{ "targets": [ { "symbol": "BTCUSDT", "market": "FUTURES" } ] }`.

### 8.1 Validation

All validation runs **before any DB write**; the first failure rejects the whole request with `400`
and no partial application (`ClassificationRuleService.validate`):

- `assignments` non-empty; each assignment has ≥1 target; each target has a symbol and a market.
- Symbols are normalised (`trim().toUpperCase()`) and must exist in `TickerRegistry` **and** be
  tracked on the requested market (`ticker.hasSpot()` / `hasFutures()`), else
  `400 "unknown symbol"` / `"… is not tracked on market …"`.
- `tier ∈ [1,4]`, no duplicates, and the tier list must cover **all four tiers** — partial sets like
  `{1,2}` are rejected.
- `minNotional >= 0`.
- `maxDistance ∈ (0, screener.orderbook.price-filter-threshold]` — bound to the live config, because
  levels beyond the price filter have already been swept and could never match.
- Total targets per request ≤ `screener.classification.max-targets-per-request` (default 200),
  bounding one transaction's size.

An upsert **replaces** rather than merges: the existing tier rows for each `(user, symbol, market)`
are deleted, then the new set is inserted.

### 8.2 Persistence

`classification_rules` (`V3__create_classification_rules.sql`, hardened in `V7`) — **one row per
tier**, so a logical rule spans up to 4 rows:

```sql
id UUID PK, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
symbol VARCHAR(255), market VARCHAR(255),          -- 'SPOT' | 'FUTURES'
tier_no INTEGER, min_notional DOUBLE PRECISION, max_distance DOUBLE PRECISION,
created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ
```

`V7` adds `idx_classification_rules_user_id` (rules are always loaded by user at connect time) and
the `uq_rule_tier UNIQUE (user_id, symbol, market, tier_no)` backstop. The row-per-tier shape keeps
the tier range extensible without a schema change.

### 8.3 Rows → runtime rule

`buildRuntimeRule(userId)` (`ClassificationRuleService.java:168`) is the seam between the CRUD world
and the hot path. It runs **on a Tomcat thread, off the hot path** (connect time or rule update),
groups rows by `"SYMBOL:MARKET"`, builds one `ThresholdClassificationRule` per key, and wraps them
in a `UserClassificationRules`. It returns `Optional.empty()` for a user with no rules — those users
get no context at all and consume the global feed directly. Stored values are already validated at
write time and are not re-validated here.

---

## 9. Context lifecycle — `UserFeedRegistry`

`analysis/UserFeedRegistry.java` is the source of truth for *who is connected* and *which contexts
are active*. All mutation happens inside a single `synchronized` block (connect / disconnect / rule
update are rare and run on Tomcat threads, so lock cost is irrelevant). The published `active` array
is `volatile` and rebuilt-and-swapped, never mutated in place, so shard classifiers and the
broadcaster read it lock-free.

**Connect** (`onUserConnect`): register the session; if the user already has a context (another
tab), reuse it — no DB reload, no re-push. Otherwise `buildRuntimeRule`; if empty, leave
`context == null` (default-only session); else build a fresh context, rebuild the `active` array,
and fan it out to every shard.

**Disconnect** (`onUserDisconnect`): remove the session. The context survives while any of the
user's other sessions remain; it is dropped (and GC'd) when the last one closes. Idempotent — Tomcat
may fire both `@OnClose` and `@OnError` for one connection.

**Live rule update** (`onRuleUpdated`): `ClassificationRuleService` publishes a `RuleUpdatedEvent`
after a write, consumed by `@TransactionalEventListener(AFTER_COMMIT)` so the listener's re-read
sees committed rows. The DB read happens **outside** the lock (never hold a lock across I/O); state
is re-read inside it. Three cases:

| Case | Situation | Effect |
|---|---|---|
| A | Rules changed, context exists | Build a fresh context (new feed store, empty state map), retarget all of the user's sessions, queue a fresh snapshot for each |
| B | Rules created while connected without a context | Same — a context is created and attached |
| C | All rules deleted while connected | Context removed; sessions revert to the global default feed and get a fresh snapshot |

**Write-ordering invariant:** whenever a session is retargeted, `setContext(...)` (write A) must
precede `setStatus(NEED_SNAPSHOT)` (write B). The broadcaster's volatile read of the status
establishes a happens-before edge back to write A, so a consumed `NEED_SNAPSHOT` is always served
from the new context. **The user sees new tiers within ~100ms, with no reconnect.**

`presenceSnapshot()` — a consistent point-in-time view of connected users (`userId`, open session
count, whether they have a custom context) — backs the admin-only
`GET /api/monitoring/presence`.

---

## 10. End-to-end data flow

```
Binance depth diff (spot 1/s, futures 2/s)
  → BinanceConnectionPool (java-websocket)
  → Disruptor ring buffer, shard = |symbol.hashCode()| % shardCount
  → DepthEventHandler.onEvent  [consumer thread, one per shard]
      → OrderBookProcessor.process → OrderBook (TreeMap update, distance recompute, price filter)
      → OrderBookClassifier.process(ob)
          ├─ Pass 1  default rule  → selectTopK ×2 → visibility gate → LOW/HIGH machine
          │                        → globalFeed.submit(key, ADD|UPDATE|DROP)
          └─ Pass 2  for each active user context that configured this key
                                   → same machine with the user's ThresholdClassificationRule
                                   → ctx.feedStore().submit(key, …)
  → OrderBookFeedStore: coalesce into pendingRef, mirror into snapshotMap
  → OrderBookBroadcaster.drain()  [@Scheduled, every 100ms, single thread]
      → drain global + each context's pending
      → build JSON bodies once, keyed by SYMBOL:MARKET
      → per session: personal bodies + global bodies for unconfigured keys, seq injected
      → session.enqueueBatch(...)   (non-blocking; false ⇒ evict)
  → UserWebSocketSession virtual thread: queue.take() → sendText(...)
  → Client
```

### Threading summary

| Thread | Does | Never touches |
|---|---|---|
| Disruptor consumer (1 per shard) | Parse diff, update `TreeMap`, classify, `feedStore.submit()` | Sessions, queues, sockets |
| `@Scheduled` broadcaster (1) | Drain feeds, build JSON, `enqueueBatch()` | Order books, classifier, TCP socket |
| Session virtual thread (1 per session) | `queue.take()`, `sendText()`, cleanup | Order books, classifier, feed store |
| Tomcat request/`@OnOpen`/`@OnMessage` | Rule CRUD, `buildRuntimeRule`, registry mutation, `setStatus` | Order books, `seqNumber` |

Cross-thread handoffs are exactly three: the `volatile UserClassificationContext[]` array
(registry → classifiers), the feed store's `AtomicReference` pending swap (classifier →
broadcaster), and the session's `ArrayBlockingQueue` (broadcaster → send loop).

---

## 11. Configuration

| Key | Default | Effect on classification |
|---|---|---|
| `screener.orderbook.price-filter-threshold` | `0.1` | Hard ceiling on level distance; also the upper bound for any user's `maxDistance` |
| `screener.classification.max-targets-per-request` | `200` | Max `(symbol, market)` targets per rule upsert/delete |
| `screener.disruptor.shard-count` | `2` | Number of classifier instances (one per shard) |
| broadcaster interval | `100ms` (`@Scheduled` literal) | Feed coalescing window / max delivery latency |
| `OrderBookClassifier.TOP_LEVELS` | `5` | Levels per side per ticker |
| Default tier tables & high-liquidity set | in `DefaultClassificationRule` | The default thresholds themselves |

The tier tables, `TOP_LEVELS`, and the broadcaster interval are **not** externalised to
`application.yml` today — changing them is a code change.

---

## 12. Edge cases worth knowing

- **A book with only tier-0 levels never reaches any client**, and never allocates a
  `ClassifiedLevel`. This is the normal state for most of the ~1000 tracked streams.
- **A visible book always ships both sides**, even when one side is entirely tier 0.
- **Tier 0 appears on the wire** as filler in the top-5 slots.
- **The same ticker can be `HIGH` for one user and `LOW` for another** — activity state is per
  context, not per book.
- **Stricter user rules can hide a ticker the default feed shows.** Since the broadcaster excludes
  global bodies for configured keys, that user correctly sees the personal `DROP` and no default
  update.
- **Losing sync emits exactly one `DROP`**, not a stream of them — the `HIGH → LOW` transition is
  edge-triggered.
- **`ADD` can be swallowed by coalescing** (`ADD` + `DROP` inside one window = nothing), and an
  `UPDATE` can arrive with no prior `ADD`. Clients must treat `ADD` and `UPDATE` identically.
- **`firstSeenMillis` survives quantity changes** — a level keeps its original timestamp while the
  price stays in the book, so clients can render order age. It resets when the level is removed
  (`quantity == 0`) and later reappears, and on a full snapshot reload.
- **Rule changes propagate in ~100ms without a reconnect**, via a fresh context plus a forced
  snapshot.
- **No entitlement, no feed:** the WebSocket handshake and every `/api/rules` endpoint both go
  through `EntitlementService.hasAccess`; admins bypass entitlement entirely.

---

## 13. Tests

- `analysis/DefaultClassificationRuleTest` — default table semantics, highest-tier-first resolution,
  high-liquidity switching.
- `analysis/ThresholdClassificationRuleTest` — band evaluation, sorting, `widestDistance`.
- `analysis/UserClassificationRulesTest` — key membership and leaf lookup.
- `analysis/UserFeedRegistryTest` — context lifecycle, multi-session reuse, live rule-update cases
  A/B/C, write-ordering.
- `analysis/rule/ClassificationRuleServiceTest` — CRUD, validation rejections, `buildRuntimeRule`
  translation.
