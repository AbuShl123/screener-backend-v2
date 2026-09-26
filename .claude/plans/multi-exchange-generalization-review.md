# Multi-exchange generalization — review and remaining work

*Written 2026-09-26, reviewing the uncommitted universe-discovery generalization on
`feature/multi-exchange` (`InstrumentSource` SPI, `BinanceInstrumentSource`,
`DiscoveryProperties`, per-source failure isolation in `InstrumentUniverseService`).*

Verdict: the discovery design is good, and discovery itself is generalized enough to build on.
The REST plumbing and several things around discovery are not. The biggest gap before a second
exchange is the missing exchange dimension in the public API / feed payload, not discovery.

See also: `.claude/plans/universe-discovery-generalization.md`,
`.claude/docs/multi-exchange-progress.md` §5–6.

---

## 1. Universe discovery

### What is right

- **Split of responsibilities.** The adapter decides only what exists and what to track. Core owns
  what must behave the same for every exchange: id order, the added/removed diff, publication
  order, and failure handling.
- **One source may span several venues.** Binance needs it (spot inclusion depends on the futures
  list); the interface still pushes independent venues toward independent sources.
- **Failure isolation per source.** A failed, timed-out, mis-keyed or newly-empty result keeps the
  previous universe and contributes no removals. That is the right defence against a network blip
  reading as a mass delisting, and it matters more with more exchanges.
- **Startup validation of venue claims** (one source per venue, one exchange per source) catches a
  stray `@Component` next to the adapter's `@Bean`.
- **Policy ownership.** Inclusion policy lives in adapter-owned records
  (`BinanceDiscoveryProperties`); `DiscoveryProperties` stays exchange-agnostic.

### Fix before committing

1. **`application.yml` — `enabled: ${BINANCE_ENABLED}` has no default.** An unset env var fails
   startup on an unresolved placeholder. Use `${BINANCE_ENABLED:true}`, or document the variable in
   `deployment-guide.md`.
2. **`BinanceDiscoveryProperties` is unvalidated.** A null `quoteAsset` or `futuresContractType`
   makes every refresh throw an NPE inside the filter, which is logged as a warning and silently
   keeps the old universe forever. Null-check in the record's compact constructor, as
   `DiscoveryProperties` does.

### Problems that become real with a second source

3. **The transport assumes the first event is the whole universe.** `BinanceWebSocketManager`
   starts its pools from the first `InstrumentUniverseChangedEvent` and ignores later ones
   ("dynamic re-subscription not yet implemented"). If Binance fails on the first refresh while
   another exchange succeeds, the event carries only the other exchange's instruments, the manager
   marks itself initialized, and Binance is never streamed until restart. Per-source isolation makes
   this more likely. Until dynamic subscribe exists: **start pools per venue**, the first time a
   venue appears in `added`.
4. **No startup retry.** If the first refresh fails, nothing runs for `refresh-interval` (4h). This
   predates the change, but more exchanges mean a higher chance one is down at boot. Retry on a
   short interval while any enabled venue has never had a successful fetch.
5. **`enabled` only disables discovery.** `ExchangesProperties.isEnabled` treats a missing block as
   disabled, but `WebClientConfig`, `BinanceWebSocketManager`, `SnapshotFetchQueue` and the strategy
   bindings are unconditional, and some call `exchanges.venue(...)`, which throws on a missing
   block. For an adapter to genuinely "ship dark", gate its whole `@Configuration` (e.g.
   `@ConditionalOnProperty`), not just its source.
6. **`InstrumentCandidate` has no quantity multiplier.** Deferring `tickSize`/`stepSize` is fine.
   The one that will bite is contract size: MEXC futures depth quantities are in contracts, not base
   coin (OKX swaps likewise). Without it, notional is wrong and every tier is misclassified. It must
   arrive with MEXC futures and reach `Instrument`, not only the candidate.

---

## 2. `BinanceRestClient` and the REST layer

A reasonable thin wrapper, but not the shape to copy for new exchanges:

- **Market is baked into the API** — `getSpot`/`getFutures` plus two qualified `WebClient` beans.
  The natural unit is one client per venue, matching the rest of the pipeline. MEXC also has two
  hosts (`api.mexc.com` spot, `contract.mexc.com` futures), so per-venue fits it too.
- **Core depends on the adapter.** `config/WebClientConfig` names `Venue.BINANCE_*` and imports
  `binance.WeightGuard`; `recovery/SnapshotFetchQueue` imports `BinanceRestClient` and hardcodes
  `/api/v3/depth` and `/fapi/v1/depth`.
- **No response timeout.** Discovery is now bounded by `source-timeout`; snapshot fetches are not.
  A hung request never leaves the queue, and each queue holds only 10 entries — ten hung requests
  silently stop recovery for that venue.
- **Error handling is Binance-specific.** 429/418 become `BinanceApiException`, are logged, and the
  book is re-queued for the next 6s tick. Core cannot tell "rate-limited, back off" from "bad
  request" — a ban risk that grows with exchanges.
- **Query strings are concatenated** (`"...depth?symbol=" + symbol`). Use `uriBuilder`.

### What not to do

Don't generalize into an `ExchangeRestClient` interface. MEXC spot v3 resembles Binance, but the MEXC
contract API has different paths (`/api/v1/contract/detail`, `/api/v1/contract/depth/{symbol}`) and
a `{success, code, data}` envelope. A shared interface would only fit Binance.

### Proposed split

| Where | What |
|---|---|
| **Core — shared plumbing** | A factory that builds a `WebClient` from `VenueProperties` (base URL, codec buffer, **response timeout**) plus adapter-supplied filters. A generic `ExchangeApiException(venue, status, body, retryAfter)` so core reacts to rate limiting uniformly. |
| **Core SPI** | `SnapshotSource { Venue venue(); Mono<String> fetchSnapshot(Instrument) }`. The shared queue owns pacing, concurrency, capacity and backoff; the adapter only knows how to fetch. (Progress doc: P2 step 3.) |
| **Core SPI** | `RequestBudget`, with a header-feedback implementation (Binance `x-mbx-used-weight-1m`) and a local token bucket (MEXC, limited per IP/endpoint). `WeightGuard` becomes the Binance implementation. (P2 step 4.) |
| **Adapter** | Typed clients owning endpoints, DTOs and envelopes: `BinanceRestClient` built once per venue with its paths (`exchangeInfo()`, `depth(symbol, limit)`); separate `MexcSpotClient` and `MexcContractClient`. The adapter's config builds its own `WebClient`s; `WebClientConfig` keeps only Multicard. |

Success test: nothing outside `exchange/binance/` depends on `BinanceRestClient`.

---

## 3. Other generalization work before adding an exchange

The progress doc already lists snapshot fetching, request budget, transport and config. Two items
are missing from it and rank highest:

1. **No exchange dimension in the public API or the feed.** `OrderBookUpdate`, `ClassifiedLevel`
   and the broadcaster's JSON are keyed by `(symbol, market)`, and
   `ClassificationRuleService.validateTrackedTicker` hardcodes `Venue.of(Exchange.BINANCE, ...)`.
   Bybit's `BTCUSDT` would collide with Binance's in the feed store and on the client; MEXC's
   `BTC_USDT` avoids the collision only by accident, and the frontend then sees two spellings of one
   pair. Needs:
   - an `exchange` field in the feed payload,
   - a canonical display symbol from `base`/`quote` (already on `Instrument`),
   - a Flyway migration adding exchange to rule targets,
   - a frontend contract change (`.claude/docs/for-frontend/`).

   It crosses the API boundary, so do it before a second exchange exists.
2. **The transport SPI (P2 step 5) is broader than the doc states.** MEXC spot's v3 WebSocket
   appears to have moved to protobuf (verify against MEXC's current docs). If so, frame *parsing*
   must be adapter-owned too, not just subscribe-frame building — the pipeline currently assumes
   Jackson streaming JSON everywhere.

### MEXC vs Bybit as venue #2

The progress doc argues for Bybit because MEXC spot "would pass a Binance-shaped abstraction by
luck". Refinement: MEXC *futures* is not Binance-shaped at all (contract units, envelope responses,
a different depth/sync model). MEXC including futures is a real test of the SPI; MEXC spot alone is
not.

---

## 4. Suggested order

1. Commit the discovery change with fixes 1–2.
2. Per-venue pool start (3), adapter config gated on `enabled` (5), startup retry (4).
3. Exchange dimension in the API, feed and rules.
4. `SnapshotSource` + WebClient factory with timeouts; per-venue `BinanceRestClient`.
5. `RequestBudget`.
6. Transport SPI (including adapter-owned frame parsing).
7. First new exchange.
