# Snapshot recovery: generic request queue + per-venue fetcher

*Written 2026-10-02. Implements P2 steps 3–4 in `.claude/docs/multi-exchange-progress.md`.*

> **Revised after implementation (2026-10-02).** Two parts of this plan were superseded; the
> progress doc §3.1 is current. (1) §3.4: `SnapshotRequestQueue` is lock-free, not a monitor —
> `requestRecovery` runs on shard consumer threads, where locks are forbidden. Dedupe moved to
> `tick()`. (2) §3.6 / §5.5: config is inverted. The queue shape is one exchange-level
> `screener.exchanges.<exchange>.snapshot-queue` block, and Binance pricing sits in each venue at
> `venues.<market>.snapshot`. The batch-timeout check moved into `SnapshotQueueFactory`.

**Goal**: nothing outside `exchange/binance/` names a Binance endpoint, weight rule or REST client.
A new exchange that recovers books from REST snapshots plugs in by supplying one
`SnapshotFetcher` per venue from its own adapter config, plus a YAML block.

Venues that recover in-stream (resubscribe, e.g. Bybit) do not use any of this. Their
`RecoverySink` is their own business; this plan only covers venues that fetch REST snapshots.

---

## 1. Principles

1. **No retries.** A failed snapshot request is not retried by anyone. The book goes back to
   `PENDING`, and it asks again on its next diff through the normal `PENDING` path. A failure can
   have many causes, and there is no guarantee a retry would succeed. If the instrument was
   delisted in the meantime, its diffs stop, so it never asks again. This also removes the need
   for any retry state, backoff or error-reaction table in core.
2. **The queue is drained in full on every flush.** Each flush takes every pending request as one
   batch, and the queue accepts nothing new until every request in that batch has an outcome.
   There is only one queue per venue, with no separate waiting or in-flight sets. A request is
   never dispatched twice.
3. **Core queues, the venue paces.** The queue's behaviour is the same for every venue: accept
   while open and under the batch cap, flush on a tick, publish outcomes. *How* a batch is sent
   (endpoint, cost, rate-limit rules, ban handling) is entirely the venue's job, behind the
   `SnapshotFetcher` SPI.
4. **Every book state change happens on the book's shard thread.** A snapshot *failure* is
   published into the ring buffer as its own event type, exactly like a successful snapshot. The
   HTTP thread never touches `OrderBook` or `BookSyncContext`.
5. **Every request gets exactly one outcome, and core enforces it.** Success → `REST_MSG`.
   Failure, timeout, or "skipped for budget" → `REST_FAILED`. A slot the fetcher never reports is
   failed by core when the batch completes or times out. This is what makes "a book is never
   stranded in `RECOVERING`" hold without relying on diff-buffer overflow.

### Why the failure must go through the ring buffer

Writing `markPending()` from the HTTP thread has two problems.

**Lost update.** The consumer calls `requestRecovery()` and *then* `markRecovering()`, and that
order is required. Consider this sequence:

1. Shard 0 enqueues book X. `requestRecovery` returns `true`.
2. The queue flushes.
3. X's request fails fast, and the HTTP thread writes `PENDING`.
4. Shard 0, still finishing step 1, writes `RECOVERING`.

X is now `RECOVERING` with no request in flight, and it stays stuck there.

**Unreachable context.** The diff buffer lives in `BookSyncContext`, which belongs to the
consumer. The HTTP thread cannot reset it, so the book would sit in `PENDING` holding stale
buffered diffs.

As a ring event, the failure is processed after `markRecovering()` (it is published later, and
the shard is FIFO). The strategy can then reset the context on the thread that owns it.

---

## 2. Recovery lifecycle

```
            PENDING ──diff──► requestRecovery()
               ▲                 │ refused (queue closed / full / venue not accepting)
               │                 │     → diff dropped, stays PENDING, asks again next diff
               │                 │ accepted
               │                 ▼
               │            RECOVERING  (diffs buffered)
               │                 │
               │     queue tick: batch drained → fetcher.fetchAll(batch)
               │                 │
               │     ┌───────────┴────────────┐
               │  REST_FAILED              REST_MSG
               │  (ctx.reset)          handleSnapshot()
               └──────┘                 ok │      │ fail → recover() (resync)
                                           ▼
                                         SYNCED
```

A refusal is normal and cheap. It keeps the book in `PENDING` with no buffer, so a venue that
is out of budget or banned holds its books there instead of making them buffer for minutes.

---

## 3. Core changes

### 3.1 `EventType.REST_FAILED`

```java
public enum EventType {
    WS_MSG,
    REST_MSG,
    /** A REST request for this instrument completed without a usable body. rawJson is null. */
    REST_FAILED
}
```

This is still provenance, not semantics: it says a REST call for this instrument ended with no
body. It does not say why. Update the class javadoc. The backpressure note must now cover both
REST types: neither may ever be dropped. Losing a `REST_FAILED` strands a book in
`RECOVERING`, which is exactly what this plan exists to prevent. When P3 moves `WS_MSG` to
`tryNext()`, both REST types keep the blocking claim.

### 3.2 `DepthEventPublisher.publishSnapshotFailure(int instrumentId)`

Add a third method. `DisruptorDepthEventPublisher` routes it through the same private
`publish(type, id, payload)` with `REST_FAILED` and a `null` payload. There is no other change
to the publisher.

### 3.3 SPI: `SnapshotFetcher` (in `exchange/spi/`)

```java
public interface SnapshotFetcher {

    /**
     * Cheap, called from shard consumer threads on every diff of a PENDING book (volatile reads
     * only, no locks, no allocation). false while the venue is banned or out of budget: the queue
     * then refuses, and books stay PENDING without buffering.
     */
    boolean isAcceptingRequests();

    /**
     * Sends snapshot requests for this batch however the venue's rate limits allow. Must report
     * exactly one outcome per slot. A slot it decides not to send (budget) is reported as failed.
     * The returned stage completes once every slot has an outcome. Called from the queue's
     * scheduler thread: it must start the requests and return, never block.
     */
    CompletionStage<Void> fetchAll(List<BookSlot> batch, SnapshotOutcome outcome);
}

public interface SnapshotOutcome {
    void delivered(BookSlot slot, String body);
    void failed(BookSlot slot);
}
```

`SnapshotOutcome` is implemented by core, so the fetcher never sees the publisher, the book or
the sync state. The fetcher handles only HTTP and pacing. It may spread a batch over time, for
example on a venue limited to N requests per 2s. That is safe, because the queue stays closed
until the stage completes.

### 3.4 `SnapshotRequestQueue implements RecoverySink` (in `exchange/recovery/`)

There is one instance per REST-recovering venue. It is a plain class, not a `@Component`, and
the adapter creates it through `SnapshotQueueFactory`.

**State**

```java
private final Object lock = new Object();
private final LinkedHashMap<Integer, BookSlot> pending;   // keyed by instrument id: dedupes
private volatile boolean inFlight;
```

**`requestRecovery(slot)`**: called from consumer threads, possibly several shards at once.

```
if (inFlight) return false;                       // volatile fast path, the common refusal
if (!fetcher.isAcceptingRequests()) return false;
synchronized (lock) {
    if (inFlight || pending.size() >= maxBatchSize) return false;
    pending.put(slot.instrument().id(), slot);
    return true;
}
```

During the startup ramp this runs on every diff of every `PENDING` book, roughly 1–2k calls/s
in total. The volatile fast path handles almost all of them. The lock is taken only while the
queue is open, it is uncontended in practice, and the critical section is a single map put.
A plain monitor is enough.

**`tick()`**: runs every `flush-interval` on the factory's scheduler thread.

```
List<BookSlot> batch;
synchronized (lock) {
    if (inFlight || pending.isEmpty()) return;
    batch = List.copyOf(pending.values());
    pending.clear();                              // atomic with the copy: no slot can slip between
    inFlight = true;
}
BatchOutcome outcome = new BatchOutcome(batch);   // tracks which slots have reported
try {
    fetcher.fetchAll(batch, outcome)
           .toCompletableFuture()
           .orTimeout(batchTimeout)
           .whenComplete((v, err) -> finish(outcome, err));
} catch (Throwable t) {
    finish(outcome, t);
}
```

**`finish(outcome, err)`** logs `err` at WARN if it is present. It then seals the outcome: every
slot that has not reported gets `failed`, and any report that arrives afterwards is ignored.
Finally it sets `inFlight = false`.

**`BatchOutcome`** (core, package-private) implements `SnapshotOutcome`:

- **`delivered`**: if the slot has not reported yet and the batch is not sealed, call
  `publisher.publishSnapshot(id, body)`.
- **`failed`**: under the same check, call `publisher.publishSnapshotFailure(id)`.
- **Tracking**: reported ids are kept in a set. A duplicate or late report is dropped and
  logged at debug.

Ignoring a late success is deliberate. Once its slot has been failed, the book may already be
back in `PENDING` or re-queued, and a body arriving that late helps no one.

**Why a fixed tick, and no flush when the queue fills.** The tick gives batching for free and
needs no timer bookkeeping:

- At startup the queue fills at once and flushes within one interval.
- In steady state a lone desync waits at most one interval.
- The tick never dispatches from a consumer thread: building and sending HTTP requests stays off
  the hot path.

### 3.5 `SnapshotQueueFactory` (core `@Component`, in `exchange/recovery/`)

```java
public SnapshotRequestQueue create(Venue venue, SnapshotFetcher fetcher, SnapshotProperties props)
```

The factory:

- **Holds `@Lazy DepthEventPublisher`.** The bean cycle (`BookSlotTable → SyncStrategyRegistry →
  VenueStrategyBinding → … → DepthEventPublisher → DisruptorShardManager → BookSlotTable`) is
  broken in exactly one place in core, and adapters never see the publisher.
- **Owns one single-thread `ScheduledExecutorService`** (a daemon thread named `snapshot-queue`)
  that ticks every queue. Do **not** use Boot's shared `@Scheduled` scheduler. It is a single
  thread shared with the 100ms feed drain and the ticker refresh, which can block for up to
  `source-timeout`, and that would stall recovery for every venue.
- **Schedules each created queue's `tick()`** at its `flush-interval`, and shuts the executor
  down in `@PreDestroy`.

### 3.6 Config: an optional `snapshot` block in `VenueProperties`

```yaml
venues:
  SPOT:
    snapshot:              # absent ⇒ this venue does not recover over REST
      max-batch-size: 10   # also the cap on books buffering diffs at once
      flush-interval: PT0.25S
      batch-timeout: PT30S # safety net; must exceed rest.response-timeout
```

```java
public record SnapshotProperties(int maxBatchSize, Duration flushInterval, Duration batchTimeout) { }
```

Validate in the compact constructor: all values positive. In the adapter, `batchTimeout` must be
greater than `rest.responseTimeout`. Update the `ExchangesProperties` javadoc that currently
says a `snapshot` block is "deliberately not present".

`rest.response-timeout` (reactor-netty `HttpClient.responseTimeout`, already wired by
`ExchangeWebClientFactory`) is what bounds a single request. `batch-timeout` only guards
against a fetcher that never completes its stage.

---

## 4. Sync strategy change

In `BinanceDepthSyncStrategy.onEvent`, add a branch at the top, next to the `REST_MSG` one:

```java
if (event.type == EventType.REST_FAILED) {
    if (state == OrderBookState.RECOVERING) {
        ctx.reset();
        slot.book().markPending();
        log.debug("[{}] snapshot request failed - back to PENDING", slot.instrument().logName());
    }
    return;
}
```

- **No `recover()` call.** It would re-request immediately and count as a resync. The book
  re-asks on its next diff through the `PENDING` path, the same as any refused request.
- **No `clearLevels()`.** A `RECOVERING` book is already empty (invariant 3).
- **A `REST_FAILED` on a book that is not `RECOVERING` is dropped**, by the same rule as a late
  `REST_MSG`.
- **New invariant: a `PENDING` book has an empty context.** That means an empty buffer,
  `lastUpdateId == -1` and `syncPointFound == false`. `recover()` and the `REST_FAILED` branch
  both reset the context before writing `PENDING`.

Add `PipelineMetrics.recordSnapshotFailure(venue)` (a `LongAdder` per venue, like resyncs) and
show it in the health line as "snapshot failures per interval". It is the only signal that a
venue's REST side is unhealthy.

Every future venue strategy that uses the queue must handle `REST_FAILED` the same way. State
this in the `DepthSyncStrategy` javadoc.

---

## 5. Binance adapter

There is no `WebClient` filter on the Binance clients. `WeightLimitFilter` is **deleted**. The
snapshot fetcher is the only component that tracks and enforces Binance's weight limits, and it
reads the rate-limit headers from its own depth responses.

### 5.1 Rate-limit state: `WeightGuard` (one per venue, owned by the fetcher)

`WeightGuard` stays as a small, separately testable state holder. Each `BinanceSnapshotFetcher`
creates its own, and no other component shares it.

| Method | Meaning |
|---|---|
| `observe(HttpHeaders)` | Parses `x-mbx-used-weight-1m` and the `Date` header (server send time). A response that is both older and lighter than the last observation is discarded, as today |
| `remaining(limit, nowMs)` | `limit - lastObservedWeight` if still in the observed server minute, else `limit` (window rolled) |
| `ban(untilMs)` / `isBanned(nowMs)` | Set on 429 / 418, read by `isAcceptingRequests()` |

The minute-window rule is unchanged. It is anchored to the server's `Date` header, and the
window counts as rolled once local `now` passes the next server-minute boundary. The reserve
absorbs clock skew. `delayMillisRequired()` is deleted, because nothing delays requests any more.

**Discovery traffic is not observed.** `exchangeInfo` runs once per refresh (every 4h) and costs
20 on spot and 1 on futures. The weight reserve covers it, and the next depth response's header
includes it anyway. A 429 on a discovery call is not turned into a ban. If it happens, the next
snapshot request also gets a 429 and sets the ban itself.

### 5.2 Headers on every outcome

The fetcher needs response headers on both success and failure:

- **`BinanceRestClient.depth(symbol, limit)`** returns `Mono<ResponseEntity<String>>`
  (`retrieve().toEntity(String.class)`), so the success path carries headers.
- **`ExchangeApiException`** gains the response `HttpHeaders`, so a 429 or 418 carries
  `Retry-After` and the weight header to the fetcher. `toApiException` fills them in from the
  `ClientResponse`.

### 5.3 `BinanceSnapshotFetcher implements SnapshotFetcher`

There is one class, built once per venue with that venue's `BinanceRestClient`, its own
`WeightGuard`, `depthLimit`, `weightPerRequest` (from the cost table, §5.4), `weightBudget`
(`weight-limit-per-minute − weight-reserve`) and `banFallback`.

```java
public boolean isAcceptingRequests() {
    long now = System.currentTimeMillis();
    return !guard.isBanned(now) && guard.remaining(weightBudget, now) >= weightPerRequest;
}

public CompletionStage<Void> fetchAll(List<BookSlot> batch, SnapshotOutcome outcome) {
    int affordable = (int) (guard.remaining(weightBudget, System.currentTimeMillis()) / weightPerRequest);
    List<BookSlot> send = batch.subList(0, Math.min(affordable, batch.size()));
    batch.subList(send.size(), batch.size()).forEach(outcome::failed);   // skipped for budget
    return Flux.fromIterable(send)
            .flatMap(slot -> client.depth(slot.instrument().nativeSymbol(), depthLimit)
                    .doOnNext(resp -> {
                        guard.observe(resp.getHeaders());
                        outcome.delivered(slot, resp.getBody());
                    })
                    .onErrorResume(e -> {
                        onError(slot, e);
                        outcome.failed(slot);
                        return Mono.empty();
                    }))
            .then()
            .toFuture();
}
```

**`onError`:**

- If `e` is an `ExchangeApiException`, it calls `guard.observe(e.getHeaders())`.
- On **429** or **418**, it reads `Retry-After` (seconds) and calls
  `guard.ban(serverNow + retryAfter)`. If the header is missing, the ban runs until the next
  minute boundary for a 429, and for `ban-fallback` for a 418.
- It logs at ERROR once per transition into the banned state. Every other failure (`-1121`
  invalid symbol, 5xx, timeouts) is already logged at WARN by `BinanceRestClient`.

**Accounting is exact.** The queue never overlaps batches, so when `fetchAll` starts nothing
from this fetcher is in flight. The last observed header is the true usage, apart from the rare
discovery call. The reserve therefore only needs to cover discovery traffic, not a batch.
Clamping to `affordable` is what keeps a batch inside the window.

**All requests in a batch go out in parallel.** That is fine for Binance's minute-window limit:
the budget fills quickly, `isAcceptingRequests()` turns false, and the books wait in `PENDING`
until the minute rolls.

### 5.4 Depth limit and cost table

**Only `limit ∈ {100, 500, 1000}` is valid on every Binance venue.** Binance futures rejects any
other value. Spot accepts anything up to 1000, but one rule for all Binance venues is simpler and
removes a class of misconfiguration. Any other configured value, such as `800`, **fails startup**
in the compact constructor of the adapter's properties record (§5.5).

Weight per depth request (both columns confirmed empirically):

| `limit` | Spot | Futures |
|---|---|---|
| 100 | 5 | 5 |
| 500 | 25 | 10 |
| 1000 | 50 | 20 |

The table lives in the adapter as an exhaustive lookup keyed by `(Market, limit)`. It is
resolved once, when the fetcher is built, and is never consulted on the hot path.

### 5.5 Binance config (adapter-owned)

```yaml
screener:
  exchanges:
    binance:
      snapshot:
        SPOT:    { depth-limit: 1000, weight-limit-per-minute: 6000, weight-reserve: 200, ban-fallback: PT2M }
        FUTURES: { depth-limit: 1000, weight-limit-per-minute: 2400, weight-reserve: 200, ban-fallback: PT2M }
      venues:
        SPOT:
          snapshot: { max-batch-size: 10, flush-interval: PT0.25S, batch-timeout: PT30S }
        FUTURES:
          snapshot: { max-batch-size: 10, flush-interval: PT0.25S, batch-timeout: PT30S }
```

- `venues.<market>.snapshot` is the core queue config (§3.6).
- `binance.snapshot.<market>` is Binance-shaped: depth limit, weight limit, reserve and ban
  fallback. It binds to an adapter record, `BinanceSnapshotProperties` (a `Map<Market, …>`), the
  same way `BinanceDiscoveryProperties` does.
- Its compact constructor validates:
  - `depth-limit ∈ {100, 500, 1000}`;
  - `0 ≤ weight-reserve < weight-limit-per-minute`;
  - `weight-limit-per-minute − weight-reserve ≥` one request's weight.
- The weight limits currently hard-coded in `BinanceAdapterConfig` (5800 / 2200) go away.

### 5.6 Wiring in `BinanceAdapterConfig`

For each Binance venue:

1. build a `WebClient` via `ExchangeWebClientFactory` **with no filters**, then a
   `BinanceRestClient`;
2. build a `BinanceSnapshotFetcher(client, binanceSnapshotProps.forMarket(market))`. It creates
   its own `WeightGuard` and resolves `weightPerRequest` from the cost table;
3. call `RecoverySink sink = snapshotQueueFactory.create(venue, fetcher, venueProps.snapshot())`;
4. call `new BinanceSpotSyncStrategy(sink, metrics)` (or the futures strategy) inside the venue's
   `VenueStrategyBinding`.

The global `RecoverySink` bean disappears, and `SnapshotFetchQueue` and `WeightLimitFilter` are
deleted. Each venue gets its own sink. The Binance strategies are unchanged apart from §4.

---

## 6. Invariants

1. A request accepted by the queue gets **exactly one** outcome event (`REST_MSG` or
   `REST_FAILED`) in the instrument's shard. Core guarantees this through `BatchOutcome` sealing,
   not the fetcher.
2. While a batch is in flight the queue accepts nothing. At most one request per book is
   outstanding at the HTTP layer.
3. Only the shard's consumer thread writes `OrderBook.state` and the `BookSyncContext`. The
   HTTP thread only publishes.
4. A `PENDING` book has empty levels **and** an empty context.
5. `isAcceptingRequests()` and the queue's fast path do no locking and no allocation, because
   they run on the consumer thread.
6. No component delays or retries a snapshot request. Back-off happens by refusing at
   `requestRecovery`.

---

## 7. Worked traces

**Cold start.** About 880 books are `PENDING`. The first 10 diffs on spot are accepted and the
rest are refused. The tick flushes, and 10 × 50 = 500 weight goes out in parallel. Responses
become `REST_MSG` events and the books sync. The queue reopens and the next 10 are accepted
within one diff interval. After about 11 batches the spot budget is spent:
`isAcceptingRequests()` turns false, books wait in `PENDING` with no buffers, and the minute
rolls. The ramp is limited by weight: about 3 min for 353 spot books, and about 4.4 min for 525
futures books at 20 weight each.

**Request fails (e.g. a 5xx).** The fetcher calls `failed`, which publishes `REST_FAILED`. The
consumer resets the context and sets the book to `PENDING`. Its next diff asks again.

**Rate limited.** One depth response is a 429 with `Retry-After: 8`. The fetcher bans its guard
for 8s, and that slot's `REST_FAILED` sets it back to `PENDING`. For 8s every `requestRecovery` is
refused: books stay `PENDING`, drop diffs and buffer nothing. After 8s the ramp continues.

**Budget runs short mid-batch.** Ten books are queued but only 4 are affordable. Four requests
go out, and 6 `REST_FAILED` events are published at once. Those 6 books re-ask on their next
diffs and are refused until the window rolls.

**Hung fetcher.** A request stalls past `response-timeout`, so it fails and gets `REST_FAILED`.
If a buggy fetcher never completes its stage, `batch-timeout` seals the batch: unreported slots
get `REST_FAILED` and the queue reopens.

**Delisted instrument.** Its request fails (`-1121`) and the book goes back to `PENDING`. Its
diffs have stopped, so it never asks again.

---

## 8. Tests

- **`SnapshotRequestQueueTest`** (core, with a fake `SnapshotFetcher` and a recording
  `DepthEventPublisher`), driving `tick()` directly:
  - accepts up to `max-batch-size`, then refuses;
  - refuses while in flight;
  - refuses while `isAcceptingRequests()` is false;
  - dedupes by id;
  - drains in full;
  - publishes a delivered slot as `REST_MSG` and a failed one as `REST_FAILED`;
  - reports the slots a fetcher never reports as failed on completion;
  - fails and reopens on `batch-timeout`;
  - drops late or duplicate reports;
  - reopens after `fetchAll` throws synchronously.
- **`BinanceDepthSyncStrategyTest`** — a new nested group for `REST_FAILED`:
  - `RECOVERING` goes to `PENDING` with an empty buffer and context;
  - ignored on `SYNCED` and `PENDING`;
  - the next diff re-requests;
  - no resync is counted.
- **`BinanceSnapshotFetcherTest`** (with a stub `BinanceRestClient` or MockWebServer):
  - the budget clamp, with the skipped slots failed;
  - error → `failed`;
  - stage completes after all outcomes;
  - weight observed from success and error headers;
  - 429 / 418 → ban from `Retry-After`, and the fallback when the header is missing;
  - `isAcceptingRequests()` while banned and while out of budget.
- **`WeightGuard` tests**: header parsing, stale observation discarded, window roll, `remaining`.
- **`BinanceSnapshotProperties` tests**: `depth-limit` 100 / 500 / 1000 bind; `800` (or any
  other value) fails; the cost table returns the §5.4 weights for both markets.
- **`BinanceAdapterConfigTest`**: each venue's strategy is wired to its own sink.

---

## 9. Implementation order

1. **Ring plumbing.** Add `EventType.REST_FAILED`, `publishSnapshotFailure`, the strategy branch
   (§4) and its tests. This is a no-op until something publishes a failure.
2. **Core queue.** Add the `SnapshotFetcher` / `SnapshotOutcome` SPI, `SnapshotRequestQueue`,
   `BatchOutcome`, `SnapshotQueueFactory`, `SnapshotProperties`, and the queue tests.
3. **Binance fetcher.** Add the headers to `ExchangeApiException`, make `depth()` return a
   `ResponseEntity`, extend `WeightGuard`, and add `BinanceSnapshotFetcher`, the cost table,
   `BinanceSnapshotProperties` (with the `{100, 500, 1000}` validation) and the YAML.
4. **Wire and delete.** Wire per-venue sinks in `BinanceAdapterConfig` with filter-less
   `WebClient`s. Delete `SnapshotFetchQueue`, `WeightLimitFilter` and the `Pseudo*` /
   `SnapshotFetchPacer` sketch files. Add the snapshot-failure counter to the health line.
5. **Live check.** Run against Binance:
   - synced/tracked per venue should reach full within the weight-limited ramp (§7);
   - no 429s;
   - snapshot failures per interval should be near zero in steady state;
   - no book should remain `RECOVERING` for longer than one batch.
6. **Docs.** Update `.claude/docs/multi-exchange-progress.md`: §3 (the queue), §4.3 (the dispatch
   table gains `REST_FAILED`), §4.10 (invariants), §5 (snapshot fetching and the request budget
   become additive).

---

## 10. How MEXC fits (sanity check, not in scope)

- **Spot.** A `MexcSnapshotFetcher` over `/api/v3/depth`, with its own cost and budget rules.
  The strategy validates `fromVersion` / `toVersion`. Everything else is reused unchanged.
- **Futures.** Depth is limited to about 10 req / 2s per endpoint, and throttling is signalled
  as HTTP 200 `{"success":false,"code":510}`. The fetcher dispatches at most 10, then waits out
  the 2s window *inside* `fetchAll` before sending the rest of the batch. This is allowed
  because the queue stays closed until the stage completes. The fetcher maps `510` to a ban and
  reports `failed`. Full re-snapshot on every gap; no `depth_commits`.
- **Shared limits.** Whether MEXC spot and futures share a per-IP ceiling must be measured. If
  they do, both fetchers share one guard object. That needs no core change.

---

## 11. Out of scope

- **Snapshot request generations.** A stale `REST_MSG` or `REST_FAILED` still in the ring can
  reach a book that has since re-queued. It self-corrects through the sequence check, and it is
  deliberately not addressed here.
- **Per-instrument cooldown** for a symbol whose REST snapshot fails persistently while its
  stream stays alive. Watch the snapshot-failure counter, and add a cooldown only if it shows up.
- **Futures depth limit.** At `limit=1000` futures is the slowest ramp. Lowering it to 500
  halves the weight per request (20 → 10), at the cost of book depth. This is a config decision
  for later.
