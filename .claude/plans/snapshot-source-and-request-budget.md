# Snapshot fetching, REST clients and request budgets

*Written 2026-09-27. Covers steps 4–5 of the "Suggested order" in
`.claude/plans/multi-exchange-generalization-review.md` (P2 steps 3–4 in
`.claude/docs/multi-exchange-progress.md`). Informed by `external-docs/mexc/mexc-api-contracts.md`.*

Goal: nothing outside `exchange/binance/` depends on `BinanceRestClient`, `WeightGuard` or a
Binance endpoint path. A new exchange that fetches REST snapshots needs only a new
`exchange/<name>/` package and a YAML block.

---

## 1. Problems in the current `SnapshotFetchQueue`

1. **In-flight requests are fired again.** Each 6s tick dispatches every entry in the map, and an
   entry is removed only when its response arrives. The queue has no separate "in flight" state,
   so a response slower than one tick triggers a duplicate request that pays the weight twice.
   (`delayElement(5s)` made this near-certain. It is being removed: see decision D1.)
2. **The 10-entry cap and the 6s tick are an unwritten Binance weight budget.**
   10 × 50 weight per 6s ≈ 5000/min against spot's 6000, and 10 × 20 per 6s ≈ 2000/min against
   futures' 2400. These constants only hold for Binance's weights and `limit=1000`. Any other
   venue would either underuse its budget or overrun it.
3. **A refused retry can leave a book stuck.** On error the entry is removed and `requestRecovery`
   is called again. If the queue filled up in between, the call is refused. The book stays
   `RECOVERING` until its 500-diff buffer overflows, about 8 min on spot at 1 diff/s. A request the
   queue has already accepted must be retried inside the queue, never handed back.
4. **No request timeout.** A hung request holds one of the 10 slots indefinitely.
5. **Core depends on the adapter.** `SnapshotFetchQueue` imports `BinanceRestClient` and hardcodes
   `/api/v3/depth` and `/fapi/v1/depth`. `config/WebClientConfig` imports `WeightGuard` /
   `WeightLimitFilter` and names `Venue.BINANCE_*`.

---

## 2. Design

### 2.1 Core (exchange-agnostic)

**`ExchangeWebClientFactory`** — builds a `WebClient` from a venue's `rest` config: base URL, codec
buffer, connect timeout, and a **network** response timeout (reactor-netty
`HttpClient.responseTimeout`). The timeout must not use `Mono.timeout`, which would also count time
spent waiting on the budget. The adapter passes its extra filters in. `WebClientConfig` keeps only
the Multicard client.

**`ExchangeApiException`** — a single error type that core can act on:

```java
public class ExchangeApiException extends RuntimeException {
    public enum Kind { RATE_LIMITED, BANNED, BAD_REQUEST, SERVER_ERROR, INVALID_RESPONSE }
    Venue venue; Kind kind; int httpStatus; @Nullable Duration retryAfter; String body;
}
```

The adapter decides the `Kind`, because the HTTP status alone is not enough:

| Exchange | Errors and their `Kind` |
|---|---|
| Binance | 429 → `RATE_LIMITED`; 418 → `BANNED`; `-1121` (invalid symbol) → `BAD_REQUEST` |
| MEXC futures | HTTP 200 with `{"success":false,"code":510}` → `RATE_LIMITED` (the adapter must look inside the envelope) |

**`SnapshotSource`** — per-venue SPI, supplied by the adapter:

```java
public interface SnapshotSource {
    /** Cold Mono of the raw body. Classifiable failures surface as ExchangeApiException. */
    Mono<String> fetch(Instrument instrument);
    /** Budget cost of one fetch, from the adapter's cost table and the configured depth limit. */
    int cost();
}
```

The body stays an opaque `String`, and the sync strategy parses it, as today (progress-doc
decision #4: no normalised DTO). For MEXC futures the strategy streams through the
`{success, code, data}` envelope. The source only needs to recognise a failed envelope.

**`SnapshotRequestQueue`** — one instance per venue that fetches snapshots over REST. It
implements `RecoverySink` and replaces `SnapshotFetchQueue`. It is **not** a singleton
`@Component`. A core `SnapshotQueueFactory` component creates instances, keeps them in a list,
holds the `@Lazy DepthEventPublisher`, and drives every queue from one scheduler.

Each instance owns:
- a **waiting** set, keyed by instrument id and capped at `max-pending`. Refusal when it is full
  still limits how many books hold a 500-entry buffer during the startup ramp (progress-doc
  decision #6);
- an **in-flight** set, capped at `max-in-flight`. An entry in flight is never dispatched again;
- **pacing through the budget**: an entry is dispatched only when the queue can take the cost
  from the budget in the `BULK` lane (§2.1, `RequestBudget`). Otherwise it stays waiting until the
  next tick;
- **retry inside the queue**: an accepted request is never handed back to the sink. It returns to
  waiting with backoff;
- **error reaction**:

  | Error | What the queue does |
  |---|---|
  | `RATE_LIMITED` | `budget.pause(retryAfter)`, then retry |
  | `BANNED` | Long pause and an ERROR log, then retry |
  | `SERVER_ERROR` / timeout | Backoff, then retry |
  | `BAD_REQUEST` / `INVALID_RESPONSE` | Drop the request and log a WARN. The book leaves `RECOVERING` through buffer overflow (D5) |

No settle delay: the response is published as soon as it arrives (D1).

**`RequestBudget`** — one rate-limit pool:

```java
public interface RequestBudget {
    enum Lane { FOREGROUND, BULK }
    /** Non-blocking. 0 = `cost` reserved now; >0 = ms until it could be. BULK may not dip into the reserve. */
    long tryAcquire(int cost, Lane lane);
    /** Header feedback; a no-op for purely local budgets. */
    void onResponse(HttpHeaders headers, long serverTimeMs);
    /** Called on RATE_LIMITED / BANNED. */
    void pause(Duration duration);
}
```

- **The reserve (D6).** `BULK` (snapshot traffic) may use only `capacity − reserve`. `FOREGROUND`
  (discovery and other rare calls) may use all of it. A snapshot burst therefore cannot starve
  `exchangeInfo`, and ordinary traffic stays below the real limit, which lowers the risk of
  hitting it by accident.
- **`BudgetFilter`** (core `ExchangeFilterFunction`) is the single point where requests are
  counted. It takes a budget and an adapter-supplied `RequestCost` (`ClientRequest → int`).
  Requests the queue has already paid for carry a Reactor-context marker, and the filter skips
  them. It charges every other request as `FOREGROUND`, delaying it if needed, and passes every
  response to `onResponse`.
  Discovery goes through the filter as well: `exchangeInfo` costs 20 / 1 on Binance and 25 on
  MEXC spot, so it must be counted.
- Core provides `TokenBucketBudget(capacity, window, reserve)` for exchanges that document fixed
  request counts (MEXC).

### 2.2 Adapter (exchange-owned)

- **Typed clients, one per venue.**
  - `BinanceRestClient(WebClient, BinancePaths)` exposes `exchangeInfo()` and
    `depth(symbol, limit)`. It is built twice: prefix `/api/v3` for spot, `/fapi/v1` for futures.
    Query strings use `uriBuilder`.
  - MEXC gets its own `MexcSpotClient` and `MexcContractClient`.
  - No shared `ExchangeRestClient` interface (review §2, "What not to do").
- **`BinanceWeightBudget implements RequestBudget`** (in `binance/`) takes over from `WeightGuard`
  and the header parsing in `WeightLimitFilter`. It counts weight locally, corrects the count from
  `x-mbx-used-weight-1m`, and resets at the wall-clock minute boundary. Those rules are
  Binance-specific and stay in the adapter.
- **Cost tables.**
  - Binance: `depth` by `limit` (spot 1000 → 50, futures 1000 → 20, *verify*); `exchangeInfo`
    spot 20 / futures 1.
  - MEXC: per endpoint (§3).
- **Budget config** is exchange-shaped, so it goes in an adapter-owned record, the same way
  `BinanceDiscoveryProperties` does.
  - Binance: weight limit per minute, reserve.
  - MEXC: requests per window per endpoint, reserve.
- **Wiring in `BinanceAdapterConfig`.** For each venue:
  1. build the budget and the `WebClient` (factory + `BudgetFilter`), then the typed client;
  2. create `SnapshotSource source = instrument -> client.depth(instrument.nativeSymbol(), limit)`;
  3. call `RecoverySink sink = snapshotQueueFactory.create(venue, source, budget, snapshotProps)`;
  4. pass `sink` into that venue's sync strategy.

  The global `RecoverySink` bean goes away. Every venue gets its own sink.
- **Result:**
  - `BinanceAdapterConfig` can be gated with `@ConditionalOnProperty`, which closes the adapter
    half of review §1 #5.
  - The success test holds: nothing outside `binance/` names `BinanceRestClient`.

### 2.3 Core config (`VenueProperties`)

```yaml
venues:
  SPOT:
    rest:
      base-url: https://api.binance.com
      codec-buffer-size-mb: 18
      response-timeout: PT10S
    snapshot:              # absent ⇒ venue does not fetch snapshots over REST (model B)
      depth-limit: 1000
      max-pending: 10
      max-in-flight: 10
```

- `rest-url`, `codec-buffer-size-mb` and `weight-threshold` move out of the flat `VenueProperties`.
  `weight-threshold` goes to the Binance budget record.
- When the `snapshot` block is absent, the venue has no queue. Model-B venues (Bybit, OKX) recover
  by resubscribing, and core must not assume every venue has a `SnapshotSource`.

---

## 3. How MEXC fits

- **Spot.** The same model as Binance spot: buffer diffs, fetch `/api/v3/depth?limit=5000`, then
  validate `fromVersion`/`toVersion`. It needs a plain `SnapshotSource` plus its own cost and depth
  limit.
- **Futures.** A full snapshot from `/api/v1/contract/depth/{symbol}?limit=1000`, then continuity
  on `version`. **No `depth_commits` gap patching (D3)**: every gap triggers a full re-snapshot,
  as on Binance.
  - If `depth_commits` is added later, the adapter creates a second queue for the venue that shares
    the budget. The strategy records in its `BookSyncContext` which request is outstanding, and no
    interface changes.
- **Budget scope — needs research (D2).** MEXC limits appear to be per endpoint, e.g. futures
  depth at 10 req / 2s according to docs found online. Still unknown:
  - whether spot and futures on `api.mexc.com` share any per-IP ceiling;
  - whether futures signals throttling only through the `510` envelope code or also through
    HTTP 429.

  This must be measured empirically before MEXC's pool layout is fixed. The design already allows
  it either way: a budget is a plain object that the adapter hands to whatever uses that pool, so a
  budget is not always one per venue.

---

## 4. Implementation order

**4a — REST plumbing (mechanical, no behaviour change)**
1. Add `ExchangeWebClientFactory` with timeouts, and the `rest` config sub-block.
2. Split `BinanceRestClient` into one instance per venue with `BinancePaths`, and give it typed
   `exchangeInfo()` / `depth()`. Update `BinanceInstrumentSource` to take the two clients.
3. Add `ExchangeApiException`, and replace `BinanceApiException` with Binance's mapping onto it.
4. Move the Binance `WebClient` beans out of `WebClientConfig` into `BinanceAdapterConfig`.
   `WeightLimitFilter` stays attached as-is for now.

**4b — `SnapshotSource` and per-venue queue**
1. Add `SnapshotSource`, `SnapshotRequestQueue` and `SnapshotQueueFactory`. The queue has separate
   waiting and in-flight sets, retries inside the queue, and applies the error reaction table.
2. Remove `delayElement(5s)` (D1).
3. For now, pacing keeps today's behaviour (`max-in-flight` = 10, a 6s tick), now read from
   config, so the change can be checked against current resync counts in the health line.
4. Give each Binance venue its own `RecoverySink` from the adapter config. Delete
   `SnapshotFetchQueue`.

**5 — `RequestBudget`**
1. Add `RequestBudget`, `TokenBucketBudget` and `BudgetFilter`, including the `BULK` /
   `FOREGROUND` lanes and the reserve.
2. Replace `WeightGuard` / `WeightLimitFilter` with `BinanceWeightBudget` plus the Binance cost
   table.
3. The queue changes from firing on a fixed tick to dispatching whenever the budget allows, and
   the 6s constant goes away. `max-in-flight` remains as a concurrency cap.
4. Optionally gate `BinanceAdapterConfig` on `enabled`.

**Check after each step:** resyncs per interval and synced/tracked per venue in the pipeline
health line should hold steady or improve, with no 429s from Binance.

---

## 5. Decisions

| # | Decision |
|---|---|
| D1 | **Drop `delayElement(5s)`.** It existed so the diff buffer could fill before the snapshot arrived. The sync strategy no longer treats an empty buffer as an error, and a run without the delay behaved the same. |
| D2 | **MEXC rate-limit scope is an open research item.** Limits appear per endpoint (futures depth ≈ 10 req / 2s). Shared per-IP ceilings and the throttle signal must be verified empirically before MEXC's budgets are laid out. |
| D3 | **No `depth_commits`.** MEXC futures recovers by full snapshot only. |
| D4 | **Snapshot request generations — deferred.** See §6. |
| D5 | **A `BAD_REQUEST` snapshot relies on buffer overflow** to return the book to `PENDING` until the P3 reset lane exists. |
| D6 | **Budgets keep a reserve.** Bulk snapshot traffic cannot use the last `reserve` units. This protects discovery and lowers the risk of hitting a limit by accident. |
| D7 | **The exchange dimension in the public API / feed is a separate, later phase**, independent of this work. |

---

## 6. Deferred: snapshot request generations

**The hazard.** A book requests a snapshot (request A), and A goes in flight. Before A returns,
the book re-syncs again, for example because its buffer overflows. The book resets and requests
snapshot B. When A's response lands, the book is `RECOVERING`, so it applies A as though it were
B. A was fetched before the reset, so it predates the diffs now buffered.

**Today's outcome.**
- Buffer replay finds the sequence gap and triggers another recovery. So the harm is wasted
  weight and a slower sync, not a corrupt book.
- When B arrives after the book has synced, it is ignored (`state != RECOVERING`).

**The fix, when taken up.**
1. A per-book counter, incremented on every recovery.
2. Each queued request carries the counter's value when enqueued.
3. The published REST event carries it (a new `DepthEvent` field).
4. The strategy drops any response whose generation is not the current one.

This belongs with the P3 reset lane (progress doc §6.1).
