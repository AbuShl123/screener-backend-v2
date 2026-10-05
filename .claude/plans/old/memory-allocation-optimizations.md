# Memory Allocation Optimizations

## Context

After profiling 30 seconds of steady-state execution (all orderbooks synced, no startup activity),
IntelliJ's profiler revealed two dominant allocation hotspots:

| Location | Allocated (30 s) | Share |
|---|---|---|
| `OrderBook.applyLiveDiff()` via `DepthEventHandler.onEvent()` | 73.06 GB | 67.4% |
| `OrderBookBroadcaster.drain()` | ~27 GB | 25.4% |
| java-websocket internals | — | 7.2% |

These are **allocation throughput** numbers, not live heap. The JVM allocates into TLABs and
GC recycles them continuously — actual live heap at any moment is a fraction of this. The concern
is GC pressure (pause frequency/duration), not RAM exhaustion.

---

## Optimization 1 — Eliminate Intermediate Collections in `applyLiveDiff()`

**File**: `OrderBook.java`

### What is happening now

`applyLiveDiff()` has a two-phase design:

1. **Parse phase**: `parseLevelsInto(p, parsedBids)` and `parseLevelsInto(p, parsedAsks)` stream
   through the JSON and for each price level call `new double[]{price, qty}`, adding it to an
   `ArrayDeque<double[]>`.
2. **Apply phase**: `applyLevelUpdates(bids, parsedBids)` iterates the deque and mutates the TreeMap.

Both `ArrayDeque` objects and every `double[]` inside them exist only to bridge these two phases.
They are allocated on every call and discarded immediately after `applyLevelUpdates()` returns.

**Allocation cost per `applyLiveDiff()` call**:
- 2 `ArrayDeque` objects
- N `double[]` arrays, where N = number of changed price levels in this diff

At ~1500 diffs/second with an average of 10–30 levels each:
- 3 000 `ArrayDeque` objects/second
- 15 000–45 000 `double[]` objects/second

This is what drives the 39.55 GB figure attributed to line 165.

### Why the fix is safe

Binance's diff JSON format is deterministic: `U`, `u`, and `pu` always appear **before** `b` and `a`
in the object. This means sequence validation can be performed inline the first time a levels array
(`b` or `a`) is encountered — the sequence fields are already parsed by that point.

### What to do

Replace `parseLevelsInto` + `applyLevelUpdates` with a single `applyLevelsDirectly` method that
applies TreeMap mutations while streaming, with no intermediate collection:

```java
private OrderBookResult applyLiveDiff(String rawJson) {
    long U = 0, u = 0, pu = 0;
    boolean sequenceValidated = false;
    boolean sequenceOk = true;

    try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
        p.nextToken();
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "U"  -> U  = p.getLongValue();
                case "u"  -> u  = p.getLongValue();
                case "pu" -> pu = p.getLongValue();
                case "b", "a" -> {
                    // U/u/pu always precede b/a in Binance's format — validate once on first array.
                    if (!sequenceValidated) {
                        sequenceValidated = true;
                        if (market == Market.SPOT && U != lastUpdateId + 1) {
                            log.warn("[{}/{}] Sequence gap: expected U={}, got U={}", symbol, market, lastUpdateId + 1, U);
                            sequenceOk = false;
                        } else if (market == Market.FUTURES && pu != lastUpdateId) {
                            log.warn("[{}/{}] pu gap: expected pu={}, got pu={}", symbol, market, lastUpdateId, pu);
                            sequenceOk = false;
                        }
                    }
                    if (sequenceOk) {
                        applyLevelsDirectly(p, "b".equals(field) ? bids : asks);
                    } else {
                        p.skipChildren();
                    }
                }
                default -> p.skipChildren();
            }
        }
    } catch (IOException e) {
        log.warn("[{}/{}] Failed to parse diff: {}", symbol, market, e.getMessage());
        return resync();
    }

    if (!sequenceOk) return resync();

    apply30PercentFilter();
    lastUpdateId = u;
    return OrderBookResult.OK;
}

private void applyLevelsDirectly(JsonParser p, TreeMap<Double, PriceLevelEntry> map) throws IOException {
    while (p.nextToken() != JsonToken.END_ARRAY) {
        p.nextToken();
        double price = Double.parseDouble(p.getString());
        p.nextToken();
        double qty   = Double.parseDouble(p.getString());
        p.nextToken(); // END_ARRAY of [price, qty]

        if (qty == 0.0) {
            map.remove(price);
        } else {
            PriceLevelEntry entry = map.get(price);
            if (entry == null) {
                map.put(price, new PriceLevelEntry(qty, System.currentTimeMillis()));
            } else {
                entry.quantity = qty;
            }
        }
    }
}
```

The old `parseLevelsInto` and `applyLevelUpdates` methods can be removed entirely once this is in
place. `applyLevelUpdatesFirstEvent` (used during snapshot drain) has the same two-phase pattern
and should receive the same treatment.

**Allocation eliminated per call**: 2 `ArrayDeque` + N `double[]`. Expected impact: the 39.55 GB
figure drops to near zero.

---

## Optimization 2 — `p.getString()` String Allocation per Price/Qty

**File**: `OrderBook.java` — `applyLevelsDirectly` (after Opt 1) / `parseLevelsInto` (before Opt 1)

### What is happening now

```java
double price = Double.parseDouble(p.getString());
```

`p.getString()` (equivalently `p.getText()`) materializes a `String` from Jackson's internal char
buffer. This happens **twice per price level** (price + quantity). At 15 000–45 000 levels/second,
this generates tens of thousands of short-lived String objects per second — the 33.36 GB figure.

### Why the obvious fix doesn't work

Replacing with `p.getValueAsDouble()` looks promising but doesn't eliminate the allocation.
Internally, `getValueAsDouble()` on a `VALUE_STRING` token still calls `getText()`, which still
creates the String, then parses it. No improvement.

### What to do (two options, pick one)

**Option A — Use Jackson's raw text buffer (zero String allocation, low-level)**

Jackson's `JsonParser` exposes the underlying `char[]` buffer without creating a String:

```java
// Instead of: double price = Double.parseDouble(p.getString());
char[] chars  = p.getTextCharacters();
int    offset = p.getTextOffset();
int    len    = p.getTextLength();
double price  = NumberInput.parseDouble(new String(chars, offset, len));
// ^^^ still allocates — need a char-array-aware double parser
```

The problem: Java's standard library has no `Double.parseDouble(char[], offset, length)`. Jackson's
`NumberInput` class only accepts `String`. To fully eliminate the String, you'd need to write a
custom ASCII-float parser for the small subset of formats Binance uses (fixed decimal, no
scientific notation). This is feasible but adds non-trivial complexity and maintenance burden.

**Option B — Accept the String allocations, rely on young-gen GC (recommended for now)**

These Strings are very small (~8–12 chars, Latin-1) and extremely short-lived. Modern G1/ZGC
young-gen collection reclaims them efficiently with negligible pause impact. The `double[]` arrays
from Optimization 1 are far more damaging because each one is 24 bytes of object overhead plus
the backing array, and there are many more of them relative to levels.

Defer Option A until a profiler run after Opt 1 is implemented shows `p.getString()` has become
the dominant remaining allocator.

---

## Optimization 3 — Broadcaster String Allocations in `drain()`

**File**: `OrderBookBroadcaster.java`

### What is happening now

For each pending update, `drain()` calls:
1. `buildUpdateBody(update)` → `sb.toString()` → String A (the JSON body without seq)
2. `injectSeq(String A, seq)` → `sb.toString()` → String B (the final message)

With M sessions, step 2 runs M times per update. With N pending updates, that's N + N×M String
allocations per drain cycle.

The `StringBuilder sb` is correctly reused across calls (single-threaded drain), so the only
unavoidable allocations are the `toString()` calls themselves. That design is already correct.

### Root cause amplifier: the stub classifier

The 27 GB figure is greatly amplified by the stub `classify()` which assigns tier-4 to all levels
unconditionally. Because quantities change on almost every diff, `Arrays.equals()` is almost always
`false`, meaning `submit()` fires for nearly every diff on every synced book. The broadcaster
processes ~1000 pending entries per 100ms drain, building JSON for all of them, and then
`sendData()` discards everything (it's a no-op stub). This is expected behavior during development
but creates artificially maximum broadcaster load.

### What to do

**Short term**: No code change needed. The 27 GB figure will drop significantly once real
classification thresholds are implemented (most books will be LOW activity and emit nothing).

**When Phase 5 WebSocket sessions are wired up**: Eliminate the `String` intermediary entirely.
Instead of `session.sendData(String json)`, define `session.sendData(StringBuilder sb)` or
`session.sendData(byte[] bytes)` and write directly to the WebSocket frame output stream. This
removes the `sb.toString()` copy that currently creates a `String` just to hand it back to a
WebSocket library that will re-encode it to bytes anyway.

**Seq injection design note**: The current two-pass approach (build body, then inject seq) exists
to allow one body to be shared across multiple sessions with different seq numbers. This is the
correct design for multi-session fan-out and should not be changed. The per-session overhead is one
`String` per update per session, which is unavoidable unless moving to a binary protocol or
zero-copy frame construction.

---

## Optimization 4 — `applyLevelUpdatesFirstEvent` (same pattern as Opt 1)

**File**: `OrderBook.java`

`applyLevelUpdatesFirstEvent` (called during snapshot application for the first buffered diff) uses
the same `parseLevelsInto` → `ArrayDeque<double[]>` → `applyLevelUpdates` pattern. It is called
rarely (only during snapshot sync, not on the hot diff path), so the allocation impact is negligible
compared to Opt 1. However, for consistency and code reduction, it should be refactored to use
`applyLevelsDirectly` (introduced in Opt 1) when Opt 1 is implemented.

---

## What the Time Profile Tells Us (No Action Needed)

**`sun.nio.ch.WEPoll.wait` (27%)** — Windows NIO selector idle-waiting for new WebSocket data.
High utilization here is good; the selector is responsive.

**`jdk.internal.misc.Unsafe.park` (11–36%)** — Threads sleeping: Disruptor consumers on
`BlockingWaitStrategy`, `@Scheduled` threads between firings. Normal idle behavior.

**`sun.nio.ch.SocketDispatcher.read0` (6.66%)** — Actual socket read cost: kernel → JVM heap
byte copy. Expected at this stream volume.

The fact that `onEvent()` + `drain()` together consume only **0.15% of wall-clock time** confirms
the system is **I/O bound, not CPU bound**. Consumer threads spend the vast majority of their time
waiting for new ring buffer events, not processing them. This is the correct profile for this
architecture — no action needed here.

---

## Implementation Priority

| # | Optimization | Impact | Effort | When |
|---|---|---|---|---|
| 1 | Eliminate `ArrayDeque<double[]>` in `applyLiveDiff` | High (39.55 GB eliminated) | Low | Now |
| 2 | Same for `applyLevelUpdatesFirstEvent` | Negligible but consistent | Low | With Opt 1 |
| 3 | `p.getString()` String allocation | Medium (33 GB, but GC-friendly) | High (custom parser) | After Opt 1 profiled |
| 4 | Broadcaster String allocation | Self-resolving with real thresholds | None now | Phase 5 |
