# P1 — Instrument Identity (narrowed scope)

**Status**: implementation plan, agreed. Supersedes the "P0 — Identity" row of
`.claude/plans/multi-exchange-architecture-vision.md` §12, which was deliberately narrowed.

**Goal**: replace `(String symbol, Market market)` as the pipeline's identity with a dense
`int` instrument id, introduce `Venue = (exchange, market)` as the adapter unit, and swap the
`ConcurrentHashMap`-keyed order-book store for an array indexed by that id — **with Binance as
the only exchange and observable behaviour unchanged.**

**Related reading**: `.claude/plans/multi-exchange-architecture-vision.md` (the long-range
design this is the first slice of), `.claude/docs/orderbook-sync-algorithm.md` (the sync
behaviour that must not change), `CLAUDE.md` (hot-path rules — unchanged and still binding).

---

## 0. Scope

### In scope

- `Venue` enum (`BINANCE_SPOT`, `BINANCE_FUTURES`) as the pipeline's adapter unit.
- `Instrument` + `InstrumentRegistry` with dense, stable `int` ids.
- `InstrumentUniverseService` replacing `TickerService`, retiring `Ticker` / `TickerRegistry`.
- `BookSlot` + `BookSlotTable` (array indexed by id) replacing `OrderBookStore`.
- `DepthEvent` reduced to `{ int instrumentId, EventType, String rawJson }`.
- Shard routing `id & (shardCount - 1)`, with power-of-two validation at startup.
- `SubscriptionIndex` per WebSocket connection; `.intern()` removed from the hot path.
- Derived connection count (configured value becomes a floor).
- `screener.exchanges.binance.venues.*` config restructure — venue dimension only.
- Minimal re-keying of `SnapshotFetchQueue`, `MonitoringController`, `OrderBookClassifier`.

### Explicitly out of scope (deferred to later phases)

| Deferred | Phase | Why not now |
|---|---|---|
| `DepthSyncStrategy` / `BookSyncContext` SPI | P2 | Moving `lastUpdateId` + `diffBuffer` off `OrderBook` is a rewrite of the sync core. `OrderBook` keeps its `Venue` field and its `SPOT`/`FUTURES` branch as marked transitional debt. |
| Generic `ConnectionPool` + `StreamProtocol` + `RequestBudget` | P2 | No second venue exists to validate the seam against. |
| Parameterised `SnapshotRequestQueue`, `SnapshotSource` | P2 | Needs the SPI. This phase only re-keys the existing queue. |
| Moving `binance/` under `exchange/binance/` | P2 | P2 *splits* `OrderBook`; those files move anyway. A rename storm now would bury the semantically dangerous diff (id assignment, slot routing) in noise. |
| Reset lane (`resetRequested`), `tryNext()` backpressure | P3 | |
| Dynamic subscribe/unsubscribe on universe change | P3 | Refresh keeps logging "not implemented", as today. |
| Staleness watchdog, venue-dimensioned health surface | P3 | |
| Tracking spot-only symbols (vision §9.1) | P3 | Would take spot from ~"futures ∩ spot" to every USDT spot pair — a large load change stacked on a correctness refactor. Policy is made *configurable* here and set to reproduce today exactly. |
| Storage accessor seam / primitive book | P4/P5 | |

### Hard constraints

1. **No Flyway migration.** `classification_rules` keeps its `(symbol, market)` columns.
2. **No REST or WebSocket payload shape change**, with one agreed exception: `/api/tickers`
   (an internal debug endpoint, so documented by its own javadoc).
3. **The feed keeps working end to end.** It is the primary regression signal for this phase —
   see §6.

---

## 1. Target model

### 1.1 `Market` survives, `Venue` is layered above it

`Market` is persisted (`classification_rules.market VARCHAR`, mapped `EnumType.STRING`) and
appears in API payloads (`TargetDto`, `RuleResponse`, `OrderBookUpdate`,
`MonitoringController.OrderBookResponse`). It therefore **stays, with its value names
unchanged**. Only its package moves — DB-safe, because `EnumType.STRING` persists `name()`.

```java
// exchange/Market.java  (moved from binance/websocket/Market.java)
public enum Market { SPOT, FUTURES }
```

`Market.streamSuffix()` is **deleted**. `"@depth"` / `"@depth@500ms"` are Binance transport
config, not a property of a persisted enum; they move to `screener.exchanges.binance.venues.*.depth-stream`.

```java
// exchange/Exchange.java
public enum Exchange { BINANCE }

// exchange/Venue.java
public enum Venue {
    BINANCE_SPOT   (Exchange.BINANCE, Market.SPOT),
    BINANCE_FUTURES(Exchange.BINANCE, Market.FUTURES);

    private final Exchange exchange;
    private final Market   market;
    // exchange(), market() accessors
}
```

This is the seam that makes the "no migration, no API change" constraint cheap: the **pipeline**
moves to `Venue`; **persistence and API** stay on `Market`.

### 1.2 `Instrument`

```java
// exchange/Instrument.java
public record Instrument(
        int    id,            // dense, runtime-only, assigned at registration
        Venue  venue,
        String nativeSymbol,  // "BTCUSDT" — exactly what Binance expects
        String base,          // "BTC"
        String quote,         // "USDT"
        String canonical,     // "BTC/USDT" — populated, unused this phase (vision §9.2)
        String feedKey,       // nativeSymbol + ":" + venue.market().name()
        String logName        // venue.name() + "/" + nativeSymbol
) {
    public Market market() { return venue.market(); }
}
```

`feedKey` is precomputed at registration and is **exactly** `nativeSymbol + ":" + market.name()`.

> **Why exactly that**: `OrderBookClassifier.process` builds `ob.getSymbol() + ":" + ob.getMarket()`
> today, and `ClassificationRuleService.buildRuntimeRule` builds `row.getSymbol() + ":" + row.getMarket()`
> for `UserClassificationRules.configuredKeys()`. Both resolve to `SYMBOL:MARKET`. Reproducing
> the string byte-for-byte is what lets `analysis/`, `feed/` and `ws/` stay untouched — and it
> **removes** the per-event concat that exists today.

`base` / `quote` / `canonical` are free to populate from the Binance `exchangeInfo` response
(`baseAsset`, `quoteAsset`) and expensive to backfill later. Nothing reads them yet.

### 1.3 `InstrumentRegistry`

```java
// exchange/InstrumentRegistry.java  @Component
public final class InstrumentRegistry {

    int  register(Venue venue, String nativeSymbol, String base, String quote); // cold; idempotent
    Optional<Instrument> find(Venue venue, String nativeSymbol);                // cold
    Instrument byId(int id);                                                    // cold
    String describe(int id);                                                    // cold — logging only
    int  everRegistered();                                                      // slot-table sizing
    Collection<Instrument> all();                                               // cold — /api/tickers
}
```

Backed by a `HashMap<String, Instrument>` keyed on `venue.name() + '|' + nativeSymbol`, plus a
copy-on-write `volatile Instrument[] byId`.

Id rules (from vision §2.5, all load-bearing):

- **Dense** — ids come from a counter, space is `[0, everRegistered)` for the process lifetime.
- **Stable** — re-registering the same `(venue, nativeSymbol)` returns the same id. The 4-hourly
  refresh must not reshuffle ids.
- **Never transferred** — a delisted instrument's id is retired, leaving a hole. Never reused.
- **Never persisted** — nothing writes an id to the database or an API payload.

Registration is single-threaded (the discovery thread). Reads from other threads go through the
`volatile byId` reference.

To make ids reproducible across restarts (helps when diffing parity runs), register in a
deterministic order: sort candidates by `(venue.ordinal(), nativeSymbol)`.

### 1.4 `BookSlot` / `BookSlotTable`

```java
// binance/orderbook/BookSlot.java
public final class BookSlot {
    public final Instrument instrument;
    public final OrderBook  book;
}

// binance/orderbook/BookSlotTable.java  @Component
public final class BookSlotTable {
    private volatile BookSlot[] slots;

    void       allocate(Instrument inst);   // discovery thread only
    void       publish();                   // volatile store of the grown array
    BookSlot   get(int id);                 // hot — bounds-checked read
    BookSlot[] snapshot();                  // cold — monitoring, sync-count logging
}
```

`BookSlot` deliberately holds only `instrument` + `book` this phase. `ctx`, `strategy` and
`resetRequested` join it in P2/P3.

> **Placement note**: `BookSlot`/`BookSlotTable` go in `binance/orderbook/` alongside `OrderBook`
> rather than a new `exchange/book/`, because P2 moves that whole package wholesale when the SPI
> splits `OrderBook`. Splitting them across packages now would create a seam we'd immediately undo.

`OrderBookStore` is **deleted**. Its `logSyncCount` scheduled method moves to `BookSlotTable`,
reading `slot.instrument.venue()` instead of `ob.getMarket()`.

---

## 2. Work order

Seven steps. Each is a commit; steps 1–2 and 5–7 compile and run independently, steps 3–4 land
together (see below).

### Step 1 — `Venue`, `Exchange`, relocate `Market`

**New**: `exchange/Exchange.java`, `exchange/Venue.java`.
**Moved**: `binance/websocket/Market.java` → `exchange/Market.java`, dropping `streamSuffix()`.

**Import-only edits** (8 files): `analysis/rule/ClassificationRuleEntity`,
`ClassificationRuleRepository`, `ClassificationRuleController`, `dto/TargetDto`,
`dto/RuleResponse`, `feed/OrderBookUpdate`, `monitoring/MonitoringController`,
`binance/orderbook/OrderBook`.

`BinanceStreamClient.buildSubscribeFrame` temporarily inlines the suffix; it moves to config in
step 5.

**Test edit**: `ClassificationRuleServiceTest` imports `Market` from the new package.

*No behaviour change. DB-safe: `EnumType.STRING` + unchanged value names.*

### Step 2 — `Instrument`, `InstrumentRegistry`, `InstrumentUniverseService`

**New**: `exchange/Instrument.java`, `exchange/InstrumentRegistry.java`,
`exchange/InstrumentUniverseService.java`, `exchange/InstrumentUniverseChangedEvent.java`.

**Deleted**: `ticker/Ticker.java`, `ticker/TickerRegistry.java`, `ticker/TickerService.java`,
`ticker/TickersRefreshedEvent.java`.
**Repointed**: `ticker/TickerRefreshScheduler` → calls `InstrumentUniverseService.refresh()`.
**Rewritten**: `ticker/TickerController` (see §3).

`InstrumentUniverseService.refresh()` keeps `TickerService`'s exact structure — `Mono.zip` of the
two `exchangeInfo` calls, `blockOptional(Duration.ofSeconds(30))`, catch-log-and-retain on any
failure — but the filter logic is restated as an explicit policy step:

```
futuresSet = futures.symbols
             | status == TRADING
             | contractType == PERPETUAL
             | quoteAsset == USDT
             | symbol not in excludedSymbols

spotSet    = spot.symbols
             | status == TRADING
             | quoteAsset == USDT
             | symbol in futuresSet          ← the deferred vision-§9.1 rule, now config-driven

register each futuresSet symbol as (BINANCE_FUTURES, symbol)
register each spotSet    symbol as (BINANCE_SPOT,    symbol)
```

The `spotRequiresFutures: true` flag and the `excluded-symbols` list move to config (§4). Set to
today's values, the resulting universe is **identical to `TickerService.buildTickerMap`'s**.

> **The key modelling change**: `BTCUSDT` is now **two instruments** — `(BINANCE_SPOT,"BTCUSDT")`
> and `(BINANCE_FUTURES,"BTCUSDT")` — with two ids, two slots, two books. Today's
> `Ticker(symbol, hasFutures, hasSpot)` bundled both markets into one object, and that bundling
> is precisely what forced `OrderBookStore`'s composite string key.

After registering, the service allocates slots (`BookSlotTable.allocate` per new instrument),
calls `publish()`, and *then* fires `InstrumentUniverseChangedEvent(added, removed)`. Ordering is
an invariant — see §5.1.

**`ClassificationRuleService` switches `TickerRegistry` → `InstrumentRegistry`.** Its
`validateTrackedTicker` collapses from a two-step lookup + `hasSpot()`/`hasFutures()` switch to:

```java
Venue venue = Venue.of(Exchange.BINANCE, target.market());
if (registry.find(venue, symbol).isEmpty()) throw badRequest("unknown symbol: " + symbol);
```

The error message for an untracked `(symbol, market)` pair changes wording slightly — accepted;
it is a 400 body string, not a contract field. **Update `ClassificationRuleServiceTest`**, which
constructs `new TickerRegistry()` directly.

### Steps 3 + 4 — slot table, id-keyed events, classifier adapter *(one commit)*

These land together: step 3 removes `symbol`/`market` from `OrderBook`, which breaks the
classifier's call site immediately. Nothing compiles in between.

**`OrderBook`** — field changes:

| Removed | Added |
|---|---|
| `final String symbol` | `final int instrumentId` |
| `final Market market` | `final Venue venue` *(transitional — carries the U/pu branch until P2)* |
| | `final String logName` — set once at construction from `instrument.logName()` |

All ~15 `log.debug/warn("[{}/{}]", symbol, market)` sites become `log.warn("[{}] …", logName, …)`.
`applyLiveDiff`'s sequence branch becomes `venue == Venue.BINANCE_SPOT` / `BINANCE_FUTURES`.

> **Transitional debt, marked as such in a class javadoc**: `venue` exists on `OrderBook` only to
> select the sequence rule. P2 replaces it with `DepthSyncStrategy` + `BookSyncContext`. `logName`
> is *not* debt — it is a log-only field with no hot-path cost, and identity remains the int.

**`BookSlotTable`** replaces `OrderBookStore` (deleted).

**`OrderBookProcessor.process`** loses its `SNAPSHOT`/`DIFF` branch, its `computeIfAbsent`, its
key concatenation and its null check. Slots are unconditionally present:

```java
BookSlot slot = slots.get(event.instrumentId);
OrderBookResult result = (event.type == EventType.SNAPSHOT)
        ? slot.book.applySnapshot(event.rawJson)
        : slot.book.onDiff(event.rawJson);
...
if (!snapshotFetchQueue.enqueue(slot)) return slot;
```

It returns `BookSlot` rather than `OrderBook`, so the handler has the instrument for the classifier.

**`DepthEvent`**:

```java
public class DepthEvent {
    public EventType type;
    public int       instrumentId;
    public String    rawJson;

    public void clear() { type = null; instrumentId = -1; rawJson = null; }
}
```

**`DisruptorShardManager`**:

```java
@PostConstruct validate: shardCount is a power of two and >= 1 — fail fast otherwise.
private int shardMask = shardCount - 1;
public RingBuffer<DepthEvent> getRingBuffer(int instrumentId) {
    return ringBuffers[instrumentId & shardMask];
}
```

This also removes a latent bug: `Math.abs(Integer.MIN_VALUE)` is negative, so today's
`Math.abs(symbol.hashCode()) % shardCount` can throw `ArrayIndexOutOfBoundsException` for an
unlucky symbol string.

**`DepthEventHandler`**:

```java
BookSlot slot = processor.process(event);
if (slot != null) classifier.process(slot.instrument, slot.book);
event.clear();
```

**`OrderBookClassifier`** — the whole adapter, six lines:

| Today | After |
|---|---|
| `process(OrderBook ob)` | `process(Instrument inst, OrderBook ob)` |
| `String key = ob.getSymbol() + ":" + ob.getMarket();` | `String key = inst.feedKey();` *(no allocation)* |
| `defaultRule.isHighLiquidity(ob.getSymbol())` | `defaultRule.isHighLiquidity(inst.nativeSymbol())` |
| `new OrderBookUpdate(ob.getSymbol(), ob.getMarket(), …)` ×3 | `new OrderBookUpdate(inst.nativeSymbol(), inst.market(), …)` ×3 |

`classifyOne`, `selectTopK`, `tryInsert`, `applyNewOrders`, `SymbolState`,
`UserClassificationContext`, `UserClassificationRules`, `OrderBookFeedStore`,
`OrderBookBroadcaster`, `ScreenerWebSocketEndpoint` and the WS payload are **untouched**.

**`SnapshotFetchQueue`** — minimal re-key only:

- `ConcurrentHashMap<String, OrderBook>` → `ConcurrentHashMap<Integer, BookSlot>` (both queues).
- `enqueue(OrderBook)` → `enqueue(BookSlot)`; market selection reads `slot.instrument.venue()`,
  the URL reads `slot.instrument.nativeSymbol()`.
- `publishSnapshotEvent` sets `event.instrumentId = slot.instrument.id()` and routes via
  `shardManager.getRingBuffer(id)`.

`dispatchSpot`/`dispatchFutures` stay as two methods. The `@Lazy DisruptorShardManager` cycle
break stays. Everything else — capacity, 5s settle delay, re-enqueue on error — is unchanged.

### Step 5 — transport: `SubscriptionIndex`, no `intern()`, derived connections

**New**: `binance/websocket/SubscriptionIndex.java`.

```java
public final class SubscriptionIndex {
    private final Map<String, Integer> byNativeSymbol;   // built once, in the client ctor

    public int resolve(String msg, int start, int end) {
        Integer id = byNativeSymbol.get(msg.substring(start, end));
        return id == null ? -1 : id;
    }
}
```

The class exists so the zero-allocation version (open-addressed `String[] keys` + `int[] ids`,
hash the char range in place, verify a probe hit with `regionMatches`) is a one-file drop-in
later. **It is not written now**: it is hand-rolled probing in the hottest method in the
application, its failure mode on a bad verify is an event applied to the *wrong instrument's
book*, and the frame `String` java-websocket already allocated (200–2000 B) dominates the ~40 B
substring anyway. Deferred pending a microbenchmark.

**`RawDepthMessageHandler`**: `handle(String symbol, Market market, String rawJson)` →
`handle(int instrumentId, String rawJson)`.

**`BinanceStreamClient`**:

- Constructor takes `List<Instrument>` (not `List<String>`) plus the venue's config record. It
  already needed the symbol list for `buildSubscribeFrame`, so the index costs no new plumbing —
  and because one client serves exactly one venue, `nativeSymbol → id` needs no disambiguation.
- `index` is `final` this phase. It becomes a `volatile` copy-on-write reference when P3 adds
  dynamic subscribe/unsubscribe — the same pattern `TickerRegistry` uses today.
- `onMessage` — `.intern()` gone, plus an unknown-symbol guard:

```java
int id = index.resolve(message, start, end);
if (id < 0) { unknownSymbols.increment(); return; }   // must never reach slots[-1]
handler.handle(id, message);
```

`unknownSymbols` is a rate-limited warn counter. It should be permanently zero today (Binance only
pushes what you subscribed), but a partial resubscribe after reconnect could produce one, and
silently routing it would be exactly the corruption class this phase exists to eliminate.

The `message.charAt(2) == 'r'` control-frame check stays verbatim; it becomes
`StreamProtocol.isControlFrame` in P2.

**`BinanceConnectionPool`** — derived connection count:

```
connections = clamp(ceil(streamCount / maxStreamsPerConnection), minConnections, maxConnections)
```

> **Parity warning.** With `max-streams-per-connection: 1024` and ~700 futures / ~400 spot
> streams, the `ceil` term is **1** for both — which would cut connections from today's 3 and 2
> down to 1 each. That is a real behaviour change. `min-connections` must therefore be set to
> today's values (spot **2**, futures **3**) so the floor reproduces current fan-out exactly. This
> is precisely the vision §5.1 framing: *the configured value becomes a floor, not the authority.*
> The `ceil` term only starts dominating at a venue with a small per-connection cap (MEXC ≈ 30).

Batch splitting keeps today's even `i*size/count … (i+1)*size/count` slicing.
`subscribe-chunk-size` (frames per connection) is unrelated and unchanged.

**`BinanceWebSocketManager`** listens for `InstrumentUniverseChangedEvent` instead of
`TickersRefreshedEvent`, splits by `instrument.venue()`, and keeps its existing
`initialized` guard + "dynamic re-subscription not yet implemented" log on subsequent refreshes.

### Step 6 — config restructure

See §4. Bind `screener.exchanges` as a nested `@ConfigurationProperties` record.
`WebSocketProperties` is deleted; `BinanceApiProperties` keeps only the weight thresholds and
codec buffer size (those move in P2 with `RequestBudget`).

### Step 7 — monitoring and `/api/tickers`

**`MonitoringController.getOrderBook`** — same request params (`symbol`, `market`), same response
record, new lookup:

```java
Venue venue = Venue.of(Exchange.BINANCE, market);
BookSlot slot = registry.find(venue, symbol.toUpperCase())
        .map(i -> slots.get(i.id())).orElse(null);
if (slot == null) return ResponseEntity.notFound().build();
```

*(Pre-existing, not introduced here: `snapshotBids()` does `new TreeMap<>(bids)` while a consumer
thread mutates the map, so this endpoint can throw `ConcurrentModificationException`. Noted, left
alone — fixing it belongs with the P4 storage accessor seam.)*

**`TickerController`** — reshaped, as agreed. `Ticker` no longer exists and one symbol is now two
instruments:

```json
{
  "total": 1100,
  "byVenue": { "BINANCE_SPOT": 400, "BINANCE_FUTURES": 700 },
  "instruments": [
    { "id": 0, "venue": "BINANCE_SPOT", "symbol": "AAVEUSDT", "canonical": "AAVE/USDT" }
  ]
}
```

Sorted by `(venue, symbol)`. `id` is exposed here **only** as a debugging aid — it is a
process-local index and no client may persist or rely on it. Say so in the javadoc.

---

## 3. What does *not* change

Verified by reading each file — no edit required beyond an import in the two marked cases:

| Area | Files |
|---|---|
| Feed & broadcast | `feed/OrderBookUpdate` *(import only)*, `OrderBookFeedStore`, `OrderBookBroadcaster`, `ClassifiedLevel`, `FeedEventType` |
| Classification internals | `SymbolState`, `UserClassificationContext`, `UserClassificationRules`, `UserFeedRegistry`, `ThresholdClassificationRule`, `DefaultClassificationRule`, `ClassificationRule` |
| Rule persistence & API | `ClassificationRuleEntity` *(import only)*, `ClassificationRuleRepository`, `ClassificationRuleController`, all `analysis/rule/dto/*` |
| Client WebSocket | `ws/*` — endpoint, session, config, configurator |
| Everything non-pipeline | `auth/`, `user/`, `billing/`, `entitlement/`, `payment/`, `email/`, `error/` |
| Schema | No new Flyway migration. `baseline-version` stays 4. |
| Frontend contract | `.claude/docs/for-frontend/websocket-feed-api.md`, `classification-rule-api.md` — unchanged |

The WS payload keeps emitting `"symbol"` and `"market"` (`OrderBookBroadcaster` lines 190–191,
216–217) because `OrderBookUpdate` keeps carrying them.

---

## 4. Config shape

Restructured to the venue dimension **only**. No `budget: {type: …}` or `snapshot: {mode: …}`
discriminators — those exist to select between implementations that will not exist until P2, and
binding config for absent abstractions guarantees a rewrite.

```yaml
screener:
  exchanges:
    binance:
      enabled: true
      rest:
        codec-buffer-size-mb: 18
      discovery:
        quote-asset: USDT
        futures-contract-type: PERPETUAL
        spot-requires-futures: true     # vision §9.1 — flip in P3, not now
        excluded-symbols:
          - USDCUSDT
          - FDUSDUSDT
          - DAIUSDT
          - PYUSDUSDT
          - USD1USDT
          - XAUTUSDT
          - PAXGUSDT
      venues:
        SPOT:
          stream-url: wss://stream.binance.com/ws
          rest-url:   https://api.binance.com
          depth-stream: "@depth"
          max-streams-per-connection: 1024
          min-connections: 2            # floor — reproduces today's fan-out exactly
          max-connections: 8
          subscribe-chunk-size: 400
        FUTURES:
          stream-url: wss://fstream.binance.com/ws
          rest-url:   https://fapi.binance.com
          depth-stream: "@depth@500ms"
          max-streams-per-connection: 1024
          min-connections: 3            # floor — see §2 step 5 parity warning
          max-connections: 8
          subscribe-chunk-size: 400
```

**Staying put this phase** (they move in P2 with `RequestBudget` / `SnapshotRequestQueue`):
`screener.binance.{spot,futures}-weight-threshold`, `screener.orderbook.*-snapshot-*`,
`screener.websocket.{reconnect-*,heartbeat-interval-seconds}`, `screener.disruptor.*`,
`screener.orderbook.price-filter-threshold`.

`config/WebSocketProperties.java` is deleted; a new `config/ExchangesProperties.java` binds
`screener.exchanges` as `Map<String, ExchangeProperties>` with a nested
`Map<Market, VenueProperties>`. Register it in `WebClientConfig`'s
`@EnableConfigurationProperties` list.

---

## 5. Invariants to defend

### 5.1 Slot publication happens-before subscription

New with this phase. Lazy `getOrCreate` made ordering irrelevant; pre-populated slots do not.

> **Discovery thread order: register all → `BookSlotTable.publish()` (volatile store) → fire
> `InstrumentUniverseChangedEvent` → transport subscribes.**

If a subscribe frame is sent before the array is published, a reader thread can resolve an id
whose index is past the array's length. The event listener runs synchronously on the discovery
thread, so the ordering holds naturally — but it must be asserted in the service, not assumed.
`BookSlotTable.get` bounds-checks and returns `null` rather than throwing; a `null` slot is
counted and dropped, never dereferenced.

### 5.2 A ticker's events never split across shards

Unchanged in substance, but the mechanism changes. Each instrument has one id and one slot; every
producer routes by `id & shardMask`. Both producers (`DisruptorDepthMessageHandler`,
`SnapshotFetchQueue.publishSnapshotEvent`) must use the same expression — no second copy of the
routing rule.

**Verified non-issue**: spot and futures `BTCUSDT` now have different ids and may land on
*different* shards, whereas today they hash the same string to the same shard. Harmless — they are
separate books with separate `feedKey`s and separate `SymbolState`s. Each shard's
`OrderBookClassifier.defaultStates` is a plain `HashMap` owned by that shard's thread and simply
holds a different subset of keys. `UserClassificationContext.states` is already a
`ConcurrentHashMap` for exactly this reason, and `OrderBookFeedStore` is already multi-writer.

### 5.3 Ids never leave the process

No id in a database column, a REST body (except `/api/tickers`, documented as debug-only), or a
WebSocket payload. `(venue, nativeSymbol)` remains the durable identity.

### 5.4 Hot-path austerity

Per §CLAUDE.md, and this phase should *improve* on it:

| Allocation | Today | After |
|---|---|---|
| `symbol.intern()` per message | locked JNI call into the JVM `StringTable` | **gone** |
| `symbol + ":" + market.name()` in `OrderBookStore.getOrCreate` | every message | **gone** (slot lookup is `slots[id]`) |
| `ob.getSymbol() + ":" + ob.getMarket()` in `classifier.process` | every message | **gone** (`inst.feedKey()` precomputed) |
| symbol substring in `onMessage` | 1 per message | 1 per message *(unchanged — deferred, see step 5)* |

---

## 6. Verification

There is no unit test for the pipeline today and this phase does not add one — the risk is
integration-shaped (misrouting), not logic-shaped. Verification is therefore behavioural, run
against the same ticker set before and after:

1. **Universe size.** `/api/tickers` after the refactor must report `BINANCE_FUTURES` count ==
   pre-refactor `total`, and `BINANCE_SPOT` count == pre-refactor `spotCount`. Any drift means
   the discovery policy was not faithfully restated.
2. **Sync counts.** The `sync count: spot=… fut=…` log line must settle at the same values as a
   pre-refactor run of comparable duration.
3. **Order-book contents.** `GET /api/monitoring/orderbook?symbol=BTCUSDT&market=FUTURES` (and a
   handful of mid-cap symbols, plus SPOT) — level counts and `state` in the same range.
4. **Feed end to end.** Connect the frontend and confirm symbols appear, update and drop.
   This is the check that would catch a misrouted event; the other three would not.
5. **Custom rules.** Create a per-user rule via `/api/rules`, confirm the live-update path still
   retargets sessions and that the user's feed shows custom tiers — this exercises `feedKey`
   matching against `UserClassificationRules.configuredKeys()`, the one place where a byte
   difference in the key string would silently degrade to "user sees default tiers".
6. `./mvnw test` green, with `ClassificationRuleServiceTest` updated for `InstrumentRegistry`.

**Failure signal to watch**: a non-zero `unknownSymbols` counter, or `BookSlotTable.get` returning
`null`. Both should be permanently zero; either indicates the identity mapping is wrong.

---

## 7. File-by-file summary

**New** — `exchange/Exchange`, `exchange/Market` *(moved)*, `exchange/Venue`,
`exchange/Instrument`, `exchange/InstrumentRegistry`, `exchange/InstrumentUniverseService`,
`exchange/InstrumentUniverseChangedEvent`, `binance/orderbook/BookSlot`,
`binance/orderbook/BookSlotTable`, `binance/websocket/SubscriptionIndex`,
`config/ExchangesProperties`.

**Deleted** — `ticker/Ticker`, `ticker/TickerRegistry`, `ticker/TickerService`,
`ticker/TickersRefreshedEvent`, `binance/orderbook/OrderBookStore`,
`binance/websocket/Market` *(moved)*, `config/WebSocketProperties`.

**Substantially edited** — `OrderBook`, `OrderBookProcessor`, `SnapshotFetchQueue`, `DepthEvent`,
`DisruptorShardManager`, `DisruptorDepthMessageHandler`, `DepthEventHandler`,
`BinanceStreamClient`, `BinanceConnectionPool`, `BinanceWebSocketManager`,
`RawDepthMessageHandler`, `TickerController`, `MonitoringController`,
`ClassificationRuleService`, `WebClientConfig`, `application.yml`, `application-local.yml`.

**Lightly edited** — `OrderBookClassifier` (6 lines), `LoggingDepthMessageHandler` (inactive bean,
signature only), and the 6 import-only files from step 1.

**Docs to refresh at the end** — `CLAUDE.md` (module map: `ticker/` gone, `exchange/` added;
config conventions), `.claude/docs/orderbook-sync-algorithm.md` (identity and store sections).
`.claude/docs/for-frontend/*` needs no change.
