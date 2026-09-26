# Generalising Instrument Universe Discovery

**Status**: agreed approach, no code written yet.
**Branch**: `feature/multi-exchange`, on top of `c9de754`.
**Written**: 2026-09-25.

**Goal**: `InstrumentUniverseService` becomes an exchange-agnostic merger. Every Binance-specific
detail it holds today — REST client, endpoints, DTOs, status literals, inclusion policy, and the
policy's config record — moves into the Binance adapter behind a new `InstrumentSource` SPI. After
this, adding an exchange's discovery is a new adapter bean plus a YAML block, with **no edit to
core**.

**Non-goal**: no MEXC code. Binance stays the only source. This is a pure refactor with one
deliberate behaviour change (per-source failure isolation, §4.3), which cannot be observed with a
single source.

**Related reading**

| File | Relationship |
|---|---|
| `.claude/plans/mexc-first-venue.md` | This plan **replaces its step 1** and settles its open question 4. It also absorbs the `enabled` wiring from its step 0. Where the two disagree on discovery, this file wins. |
| `.claude/plans/multi-exchange-architecture-vision.md` | §9 (discovery) is the chapter this implements. §9.3 (failure isolation) is made concrete here. |
| `.claude/docs/multi-exchange-progress.md` | Current state of the tree. §2 describes today's `InstrumentUniverseService`. |

---

## 1. What is Binance-coupled today

`exchange/InstrumentUniverseService.java` has three layers of coupling. Only the first is obvious:

| Layer | Today | Why it blocks a second exchange |
|---|---|---|
| **Fetch** | Injects `BinanceRestClient`; hardcodes `/api/v3/exchangeInfo` and `/fapi/v1/exchangeInfo`; `Mono.zip` over exactly two calls | A new exchange would have to edit the constructor, `refresh()` and the zip arity |
| **Policy** | `TRADING` literal, `contractType`, quote filter, `spot-requires-futures` intersection, all over `BinanceSymbolDto` | MEXC's filter (`futureType == 1 && state == 0 && apiAllowed`) has nothing in common with it |
| **Policy config** | `ExchangesProperties.DiscoveryProperties` is a **core** record with Binance-only fields (`futuresContractType`, `spotRequiresFutures`) | No single core record can hold every exchange's inclusion policy. Moving the code alone would leave core naming Binance's policy shape |

Plus one behavioural problem: `Mono.zip` is **all-or-nothing**. With N exchanges, one exchange's
outage would stall discovery for all of them (vision §9.3).

**What is already generic and must be preserved verbatim** — `apply()`:

- register all → allocate slots → `slots.publish()` → fire `InstrumentUniverseChangedEvent`
  (the ordering invariant documented on `BookSlotTable`);
- the added/removed diff;
- the `(venue.ordinal(), nativeSymbol)` sort that makes ids reproducible across restarts;
- single-threaded id assignment on the calling thread.

---

## 2. The central decision: one source per *fetch unit*, not per venue

`mexc-first-venue.md` step 1 proposed one `InstrumentSource` **per venue**, and flagged that
`spot-requires-futures` "does not decompose cleanly" (its open question 4, option (a): a shared
collaborator behind two beans).

**Decision: an `InstrumentSource` declares the set of venues it is authoritative for, and returns
all of them from one `fetch()`.**

Reasoning:

1. **The coupling is real, so it should be visible.** Binance spot cannot be computed without the
   futures list. Two per-venue beans sharing a caching collaborator do not remove that dependency.
   They hide it behind an implicit contract about call order and cache lifetime. With a per-unit
   source, the dependency stays entirely inside the adapter, where it belongs.
2. **The adapter chooses its own granularity.** Binance ships one source covering
   `{BINANCE_SPOT, BINANCE_FUTURES}`. MEXC, whose markets are unrelated products, can ship two
   independent single-venue sources. If a MEXC spot policy later needs the futures list or joint
   volume data, it can merge them into one without touching core.
3. **The failure unit follows the dependency unit.** If Binance futures discovery fails, Binance
   spot cannot be trusted either, because the intersection needs both. Isolating failures per
   source is therefore exactly right, not a compromise.
4. **It does not contradict "one set of adapter beans per venue".** That decision (vision §1,
   progress-doc decision #1) is about hot-path beans: strategy, protocol and recovery sink, where a
   per-instrument lookup must be uniform. Discovery is cold, runs every four hours, and nothing
   indexes into it per instrument.

Core enforces the one invariant that matters: **at most one source may claim a given venue**,
checked at startup. This mirrors `SyncStrategyRegistry`'s duplicate-binding throw.

---

## 3. The SPI

New files in `exchange/spi/`:

```java
/**
 * Reports the tradable universe for one or more venues of a single exchange.
 *
 * <p>Contributed as a {@code @Bean} from an adapter's configuration, never component-scanned.
 * A source spans several venues when their inclusion policies depend on each other (Binance:
 * spot requires futures); independent venues should be independent sources, so a failure in one
 * does not freeze the other.
 */
public interface InstrumentSource {

    /** The venues this source is authoritative for. Non-empty, all of one exchange, stable. */
    Set<Venue> venues();

    /**
     * Blocking; called off the hot path on a discovery worker thread.
     *
     * <p>Returns the inclusion-filtered universe for <b>every</b> venue in {@link #venues()}.
     * All-or-nothing: on any failure, throw. Never return a partial map, and never return an
     * empty list to signal failure. An empty list means "this venue genuinely lists nothing", and
     * core will treat it as a mass delisting unless the empty-result guard (§4.4) catches it.
     */
    Map<Venue, List<InstrumentCandidate>> fetch();
}

/** One discovered instrument, venue-less; core attaches the venue from the map key. */
public record InstrumentCandidate(String nativeSymbol, String base, String quote) { }
```

Notes:

- `InstrumentCandidate` carries no `Venue`, so a source cannot put a candidate under the wrong
  key. The map key is the only place the venue is stated.
- The signature carries no `Mono`. Whether a source uses `WebClient` internally is its own
  business. Core deals in blocking calls on worker threads it owns.
- `InstrumentCandidate` is deliberately minimal. `tickSize` / `stepSize` (vision §2.2) are added
  when a checksum venue needs them. Adding them now would be binding data to an absent consumer.

---

## 4. Core: `InstrumentUniverseService` as a merger

### 4.1 Construction

```java
public InstrumentUniverseService(List<InstrumentSource> sources,
                                 InstrumentRegistry registry,
                                 BookSlotTable slots,
                                 ExchangesProperties exchanges,
                                 ApplicationEventPublisher eventPublisher)
```

At construction:

1. **Validate claims.** Throw `IllegalStateException` if two sources claim the same venue, if a
   source's `venues()` is empty, or if it spans two exchanges. Each of these is a startup bug.
2. **Filter to enabled.** Keep a source only if **all** of its venues are enabled. A source with
   some venues enabled and some not is a configuration error. Throw rather than guess, because a
   source cannot be asked to fetch a subset of its venues. Log one line per skipped source.
3. Hold the enabled sources in a final list; nothing about them changes at runtime.

### 4.2 Wiring `enabled` for the first time

`ExchangeProperties.enabled` is read nowhere today (`mexc-first-venue.md` §6.3). Give it exactly
one chokepoint on `ExchangesProperties`:

```java
/** False when the exchange is disabled or has no configuration block at all. */
public boolean isEnabled(Venue venue)
```

Missing block ⇒ disabled, rather than throwing like `exchange(...)` does. That lets MEXC's
`Venue` constants exist before MEXC has YAML, which removes the "needs a YAML block from step 0"
constraint noted in `mexc-first-venue.md` §4.4. Other consumers (the stream manager, the health
logger) adopt the same method when they need it; this plan only wires discovery.

### 4.3 `refresh()` — per-source failure isolation

State changes from one set to one set per venue:

```java
/** Ids per venue as of that venue's last successful fetch. Discovery thread only. */
private final Map<Venue, Set<Integer>> activeByVenue = new EnumMap<>(Venue.class);
```

Flow:

```
1. submit source.fetch() for every enabled source to a virtual-thread executor
2. for each source, await with a per-source timeout (config, default 30s)
     success → validate the returned keys == source.venues()      (else treat as failure)
             → apply the empty-result guard (§4.4)                  (else treat as failure)
             → collect (venue, candidate) pairs
     failure / timeout → log WARN naming the source's venues and the previous sizes;
                         mark those venues as "retained"
3. on the calling thread, exactly as today:
     sort fresh candidates by (venue.ordinal(), nativeSymbol)
     register each → allocate slot if new → build fresh per-venue id sets
     for every retained venue, copy its previous id set forward unchanged
     slots.publish()
     removed = (union of previous sets) − (union of current sets)
     activeByVenue = current
     log + fire InstrumentUniverseChangedEvent(added, removed)
```

Properties this gives:

- **A failed source produces zero removals.** Its venues' previous id sets are carried forward, so
  the diff sees no change for them. It can never be read as a mass delisting (vision §9.3).
- **A failed source does not block the others.** Their additions and removals apply normally.
- **All sources failing** still runs step 3 with nothing fresh. That publishes an empty diff, and
  the event fires with no added and no removed instruments. Skip the event entirely when both
  lists are empty and no source succeeded, to keep the log honest. (With one source this matches
  today's "log and retain" behaviour exactly.)
- **First refresh at startup with a failing source** leaves that source's venues empty until the
  next scheduled refresh (`screener.ticker.refresh-interval`, 4h). This is today's behaviour too.
  A faster startup retry is a separate, later concern; note it, don't build it.
- Id assignment stays single-threaded, and the sort key is unchanged. Binance ids therefore stay
  byte-identical to today.

The executor is an `Executors.newVirtualThreadPerTaskExecutor()` created per refresh inside a
try-with-resources. Refreshes are four hours apart, so a long-lived pool buys nothing. A timed-out
`fetch()` is cancelled via `Future.cancel(true)`. A source that ignores interruption leaks one
virtual thread until its own HTTP timeout fires, which is acceptable.

### 4.4 Empty-result guard

If a venue had instruments and its fresh result is empty, treat the whole source as failed and
retain. A changed response shape, a renamed status literal or a bad filter value would otherwise
silently delist every instrument on that venue. A venue that was already empty may return empty.

This is deliberately the only sanity check. A percentage-drop threshold was considered and
rejected, because real mass delistings do happen (a whole quote asset retired) and a threshold
would need tuning per exchange.

### 4.5 What leaves core

After this plan, `InstrumentUniverseService` imports nothing from `exchange/binance/`, and
`ExchangesProperties` has no discovery record. The class javadoc's "Inclusion policy" section
moves to the Binance source. Its "Ordering invariant" and "Failure behaviour" sections stay,
with failure behaviour updated to per-source isolation.

---

## 5. The Binance adapter

### 5.1 `exchange/binance/BinanceInstrumentSource`

- `venues()` → `EnumSet.of(BINANCE_SPOT, BINANCE_FUTURES)`.
- `fetch()` → issues both `exchangeInfo` calls concurrently (`Mono.zip`, as today; internal to the
  adapter now) and blocks with its own timeout. It applies the policy and returns a two-entry map.
- Carries, moved verbatim from `selectCandidates`: the `TRADING` literal, the contract-type filter,
  the quote-asset filter, the exclusion set, and the `spot-requires-futures` intersection.
- Endpoint paths `/api/v3/exchangeInfo` and `/fapi/v1/exchangeInfo` become constants in this
  class. They are part of Binance's API, not a deployment tunable. The REST base URLs are already
  per-venue config.
- No sorting. Ordering is core's job (§4.3), because id reproducibility is a core property.
- `BinanceRestClient` and the `dto/` classes are unchanged.

### 5.2 `exchange/binance/BinanceDiscoveryProperties`

Replaces core's `ExchangesProperties.DiscoveryProperties`, field for field:

```java
@ConfigurationProperties(prefix = "screener.exchanges.binance.discovery")
public record BinanceDiscoveryProperties(
        String quoteAsset,
        String futuresContractType,
        boolean spotRequiresFutures,
        Set<String> excludedSymbols
) { }
```

- **The YAML does not change.** The adapter record binds the same subtree the core record binds
  today.
- Remove the `discovery` component from `ExchangesProperties.ExchangeProperties`. Spring's binder
  ignores the now-unbound `discovery` key under the core record.
- Register it with `@EnableConfigurationProperties(BinanceDiscoveryProperties.class)` on
  `BinanceAdapterConfig`, **not** in `WebClientConfig`'s central list. Registering it centrally
  would make core config name an adapter class.
- Excluded symbols stay in **native** form and therefore per-exchange (`USDCUSDT` vs `USDC_USDT`).
  The vision's global-plus-override exclusion set only makes sense against `canonical`. If it is
  ever wanted, it is one core filter on a new `screener.discovery.excluded-canonicals` list,
  applied in the merger. It is not part of this plan.

### 5.3 Registration

Add to `BinanceAdapterConfig`, following the existing `VenueStrategyBinding` pattern:

```java
@Bean
InstrumentSource binanceInstrumentSource(BinanceRestClient restClient,
                                         BinanceDiscoveryProperties discovery) {
    return new BinanceInstrumentSource(restClient, discovery);
}
```

`BinanceInstrumentSource` is not a `@Component`, for the same reason the strategies are not.

---

## 6. Commits

Two commits, each verified against live Binance before the next.

### Commit A — `enabled` chokepoint

- `ExchangesProperties.isEnabled(Venue)` (§4.2) plus a unit test (enabled, disabled, missing
  block).
- No consumer yet. This is kept separate so commit B's diff is only the discovery move.

(`Exchange.MEXC` / `Venue.MEXC_*` constants belong to `mexc-first-venue.md` step 0, not here. This
plan does not need them, and its tests use fake sources over the Binance venues.)

### Commit B — `InstrumentSource` SPI and the merger

- `exchange/spi/InstrumentSource`, `exchange/spi/InstrumentCandidate`.
- `InstrumentUniverseService` rewritten as the merger (§4).
- `exchange/binance/BinanceInstrumentSource`, `BinanceDiscoveryProperties`, and the bean in
  `BinanceAdapterConfig` (§5).
- `ExchangesProperties.DiscoveryProperties` and the `discovery` component deleted.
- Add `screener.discovery.source-timeout` (default `PT30S`) to `application.yml`, bound to a small
  `DiscoveryProperties` record in `config/`. Only core, exchange-agnostic tunables go here. The
  name is free because the old record is deleted in this same commit.

---

## 7. Tests

**`InstrumentUniverseServiceTest`** (new, core, fake sources, no Spring context):

| Case | Asserts |
|---|---|
| Two sources claim one venue | Constructor throws |
| Source spans two exchanges / has empty `venues()` | Constructor throws |
| Source with a disabled venue | Skipped entirely; `fetch()` never called |
| Partially-enabled source | Constructor throws |
| Happy path | Ids assigned in `(venue.ordinal(), nativeSymbol)` order regardless of the order the source returns |
| Refresh twice, same data | Same ids; event carries no added/removed |
| Symbol disappears | Appears in `removed`; id not reused on relist |
| Source A throws, source B succeeds | B's changes apply; A's venues produce zero removals |
| Source times out | Same as throws |
| Source returns keys ≠ `venues()` | Treated as failure, retained |
| Venue goes from N to empty | Guard fires, retained, zero removals |
| Venue empty → empty | Accepted |
| Ordering invariant | `slots.publish()` is invoked before the event is published (inspect call order on mocks) |

**`BinanceInstrumentSourceTest`** (new, adapter): canned spot and futures `exchangeInfo` JSON
driven through a stubbed `BinanceRestClient`. Pins the `TRADING` filter, the contract-type filter,
the quote filter, exclusions, and the `spot-requires-futures` intersection with the flag both on
and off. Also checks that a failing REST call throws, not returns empty.

**Existing suites** must stay green: `BinanceDepthSyncStrategyTest`, `BinanceAdapterConfigTest`,
`OrderBookTest`.

---

## 8. Verification (live)

Record a baseline run **before** commit B: the `Instrument universe updated` line, and the
`Instrument universe selected` debug line with debug logging on for `InstrumentUniverseService`.

After commit B:

- [ ] `Instrument universe updated — 878 tracked (878 added, 0 removed)` on startup
      (353 spot + 525 futures, or whatever the baseline run showed that day).
- [ ] Binance instrument **ids** identical to the baseline (diff a debug dump of
      `id → venue/nativeSymbol`; add a one-off debug log if none exists).
- [ ] `synced binance/spot=…/… binance/futures=…/…` reached on the same ~6-minute ramp.
- [ ] Point the Binance futures REST URL at an unreachable host, then restart: startup logs the
      source failure, no crash, and zero instruments. Restore it and trigger a refresh (shorten
      `refresh-interval` temporarily): the universe arrives.
- [ ] With a working first refresh, break the URL and wait for the next refresh: the WARN is logged,
      `0 removed`, and books stay `SYNCED`.
- [ ] `./mvnw clean package` green.

---

## 9. What this unlocks, and what it deliberately leaves

**Unlocks**: `mexc-first-venue.md` step 3's discovery half becomes `MexcFuturesInstrumentSource`
plus `MexcDiscoveryProperties` plus one `@Bean` in `MexcAdapterConfig`, with zero core edits. MEXC
spot, when its inclusion policy is decided, can be a separate source or fold into a joint one,
the adapter's choice.

**Leaves alone**

- `InstrumentUniverseChangedEvent` shape and its consumers. Dynamic subscribe/unsubscribe is P3.
- `TickerRefreshScheduler` and the single global refresh interval. Per-venue cadence is vision
  §13 open question 6. The SPI does not prevent it later: core could schedule per source.
- `SnapshotFetchQueue`'s hardcoded depth paths. That is P2 step 3, a separate seam.
- Rate limiting for discovery calls. Binance's go through the existing weight filter on
  `BinanceRestClient`, unchanged. MEXC's are two calls per four hours (`mexc-first-venue.md` §2).
