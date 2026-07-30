# Plan: Binance API Weight Limit Guard

## Status: Implemented

---

## Problem

`BinanceRestClient` issues requests to Binance REST APIs without any awareness of
the per-minute weight budget. If the snapshot fetch queue (or any other caller)
submits too many requests within a minute, Binance will respond with HTTP 429 and
eventually ban the IP.

Binance enforces **separate** weight budgets per API family:

| Market  | Header                   | Hard limit | Guard threshold |
|---------|--------------------------|------------|-----------------|
| Spot    | `x-mbx-used-weight-1m`   | 6 000      | 5 800           |
| Futures | `x-mbx-used-weight-1m`   | 2 400      | 2 200           |

The header value in a spot response is completely blind to futures traffic and
vice versa. Two independent guards are required.

Weight resets at the **wall-clock minute boundary** (e.g. 10:05:00.000), not 60 s
after the first request in the window.

---

## Algorithm — `WeightGuard`

Two `volatile long` fields per guard instance:

```
lastObservedWeight   — value parsed from x-mbx-used-weight-1m in the last accepted response
lastObservedAtMs     — Binance server send time (from HTTP Date header) of that response
```

### Why server send time, not local receive time

Using `System.currentTimeMillis()` as the observation timestamp produces incorrect
delays under network latency. Example: Binance sends at `10:04:59` with weight `5 800`,
we receive at `10:05:01`. Local time gives `nextMinuteBoundary = 10:06:00` → 59 s
unnecessary delay. Server send time gives `nextMinuteBoundary = 10:05:00` → `now ≥
boundary` → no delay. The `Date` response header carries the server send time; local
clock is used only as a fallback when that header is absent.

### `delayMillisRequired()` — called before every outbound request

```
nextMinuteBoundaryMs = floor(lastObservedAtMs / 60_000) * 60_000 + 60_000

1. if now >= nextMinuteBoundaryMs  → return 0   // minute has already flipped
2. if lastObservedWeight < threshold → return 0  // still within budget
3. else → return (nextMinuteBoundaryMs - now) + 1_000  // wait to boundary + 1 s safety
```

### `observe(sentTimeMs, weight)` — called after every response that carries the header

```
if sentTimeMs < lastObservedAtMs AND weight < lastObservedWeight:
    return   // out-of-order response with stale data — discard
lastObservedAtMs   = sentTimeMs
lastObservedWeight = weight
```

#### Why this out-of-order rule is safe

Within a single Binance minute window the weight counter is strictly non-decreasing —
every processed request adds to it, nothing subtracts. Therefore a response that is
**both** older (earlier server send time) **and** lighter (lower weight) is genuinely
stale: a more informative reading is already recorded. Discarding it cannot cause us
to miss a throttle signal.

The case `sentTime older AND weight higher` cannot occur within a single window (it
would imply the counter went backwards). Across a minute boundary it is also harmless:
if `lastObservedAtMs` already points into the new minute, `delayMillisRequired()` would
compute `now >= nextMinuteBoundaryMs` → return 0 regardless of the stale weight value.

### Worked examples

| Server send time | Current time | Weight  | Threshold | Delay  |
|-----------------|--------------|---------|-----------|--------|
| 10:04:50        | 10:04:55     | 5 800   | 5 800     | 6 s    |
| 10:04:59        | 10:05:02     | 5 800   | 5 800     | 0 s (minute flipped) |
| 10:04:50        | 10:04:55     | 3 000   | 5 800     | 0 s (under budget)   |

### Initial state (no response received yet)

`lastObservedAtMs = 0` → `nextMinuteBoundaryMs = 60_000` (Jan 1, 1970 00:01:00).  
`now >= nextMinuteBoundaryMs` is always true → `delayMillisRequired()` returns 0.  
No spurious delays on startup.

---

## Caller Behaviour

### Blocking callers (`.block()`)

The calling thread is parked for the full delay duration before the HTTP call begins.
Acceptable for low-frequency scheduled tasks such as `TickerService.refreshTickers()`.
Not suitable for latency-sensitive code paths.

### Non-blocking callers (`.subscribe()`)

The calling thread returns immediately. The delay and HTTP call execute on Reactor's
timer/IO schedulers. No thread is held.

---

## Components

### 1. `WeightGuard`
`src/main/java/dev/abu/screener_backend/binance/api/WeightGuard.java`

- Two `volatile long` fields: `lastObservedWeight`, `lastObservedAtMs`
- `long delayMillisRequired()` — implements the algorithm above
- `void observe(long sentTimeMs, long weight)` — discards out-of-order stale responses; otherwise updates both fields
- Constructed with `long threshold`
- No Spring annotations — a plain object; constructed and owned by `WebClientConfig`

### 2. `WeightLimitFilter`
`src/main/java/dev/abu/screener_backend/binance/api/WeightLimitFilter.java`

Implements `ExchangeFilterFunction` (WebClient middleware).

**Intercept flow:**
```
filter(request, next):
  delay = guard.delayMillisRequired()
  call  = next.exchange(request)
             .doOnNext(response -> observeWeight(response))
  return delay > 0
      ? Mono.delay(Duration.ofMillis(delay)).then(call)
      : call
```

**Header extraction:**
```
observeWeight(response):
  rawWeight = headers.getFirst("x-mbx-used-weight-1m")
  if rawWeight == null: return
  weight    = Long.parseLong(rawWeight)
  sentTime  = headers.getFirstDate("Date")   // Binance server send time
  if sentTime == -1: sentTime = System.currentTimeMillis()  // fallback
  guard.observe(sentTime, weight)
```

- Constructed with a `WeightGuard` instance and a `market` label (`"SPOT"` / `"FUTURES"`)
- No Spring annotations — instantiated inside `WebClientConfig`

### 3. `WebClientConfig` (modified)
`src/main/java/dev/abu/screener_backend/config/WebClientConfig.java`

- Builds one `WeightGuard` + `WeightLimitFilter` per market
- Registers each filter on its `WebClient` via `.filter(weightLimitFilter)`

### 4. `BinanceApiProperties` (modified)
`src/main/java/dev/abu/screener_backend/config/BinanceApiProperties.java`

| Field                    | Default | Meaning                         |
|--------------------------|---------|---------------------------------|
| `spotWeightThreshold`    | 5 800   | Guard threshold for spot API    |
| `futuresWeightThreshold` | 2 200   | Guard threshold for futures API |

### 5. `application.yml` (modified)

```yaml
screener:
  binance:
    spot-weight-threshold: 5800
    futures-weight-threshold: 2200
```

---

## Bugs Found and Fixed During Implementation

### Out-of-order parallel response overwrite

**Problem**: The original `observe()` was a plain last-write-wins assignment. With
parallel requests, responses arrive out of order. A late-arriving response with a low
weight value (e.g. `50`) could silently overwrite an earlier response with a high
value (e.g. `5 800`), leaving the guard believing the budget is safe when it is not.

**Fix**: `observe()` now discards a response when both `sentTimeMs < lastObservedAtMs`
AND `weight < lastObservedWeight`. See the algorithm section for the full reasoning.

### Local clock used for minute boundary detection

**Problem**: The first implementation passed `System.currentTimeMillis()` (local receive
time) as the observation timestamp. Under network latency this can place the observation
in the wrong minute window, causing the guard to delay requests for up to a full extra
minute unnecessarily.

**Fix**: `WeightLimitFilter` now reads the HTTP `Date` header (Binance server send time)
and passes it to `guard.observe()`. Local clock is used only when the header is absent.

---

## What Does NOT Change

- `BinanceRestClient` public API — callers are unaffected; filtering is transparent
- Snapshot fetch queue design — weight limiting here is a safety net, not the primary throttle
- Any other existing component

---

## Out of Scope

- Handling HTTP 429 / 418 responses from Binance (separate concern; belongs in `BinanceRestClient` error handling)
- Per-request weight cost tracking (we rely on the header Binance sends back, not on request-type tables)
- Snapshot fetch queue rate limiting (this guard is a safety net only)
