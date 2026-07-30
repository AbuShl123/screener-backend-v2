# Plan: LMAX Disruptor Pipeline (Phase 2)

## Context

WebSocket connections (Phase 1) are complete. `LoggingDepthMessageHandler` receives every depth
message directly on the WebSocket thread and counts them. This phase replaces that stub with a
real `DisruptorDepthMessageHandler` that publishes events into sharded LMAX Disruptor ring buffers.
A consumer `EventHandler` on each shard counts events and logs per-shard stats every 10 seconds —
proving the full pipeline end-to-end without implementing orderbook logic yet.

The `RawDepthMessageHandler` interface from Phase 1 is the seam: the WebSocket layer calls
`handler.handle(symbol, market, rawJson)` and has no knowledge of what lies behind it.

---

## Design Decisions (Confirmed)

| Decision | Detail |
|---|---|
| Shard count | 4 (configurable, already in `application.yml`) |
| Ring buffer size | 65536 per shard, power of 2 (already in `application.yml`) |
| Producer type | `ProducerType.MULTI` — spot and futures WebSocket threads publish concurrently |
| Wait strategy | `BlockingWaitStrategy` for now (no CPU burn during development); switch to `YieldingWaitStrategy` before load testing |
| Shard routing | `Math.abs(symbol.hashCode()) % shardCount` — stable, deterministic. Symbol is already interned so `hashCode()` is computed once |
| Event object | Pre-allocated `DepthEvent` with public fields — mutated on publish, nulled on consume to release GC roots |
| Event payload | Store `String rawJson` reference directly (already allocated by java-websocket; no copy) |
| `LoggingDepthMessageHandler` | Remove `@Component` — deactivated but kept in source for reference |
| Consumer thread naming | `"disruptor-shard-" + shardIndex` for easy identification in thread dumps |
| Stats logging | Centralized in `DisruptorShardManager` via `@Scheduled(fixedDelay=10_000)` — reads count from each `DepthEventHandler`, logs per-shard breakdown + aggregate total |

---

## Lifecycle / Startup Sequencing

```
Spring context initializes
  └─ @PostConstruct DisruptorShardManager.start()
       └─ 4 Disruptors created and started (consumer threads alive and waiting)

Context fully ready → scheduler fires
  └─ TickerRefreshScheduler → TickerService.refreshTickers()
       └─ TickersRefreshedEvent published
            └─ BinanceWebSocketManager.startPools()
                 └─ WebSocket connections open, messages flow in
                      └─ DisruptorDepthMessageHandler.handle()
                           └─ rb.next() → fill DepthEvent → rb.publish()
                                └─ DepthEventHandler.onEvent() → count++
```

Disruptors are always started before any WebSocket events arrive — no race condition.

---

## New Files

### Package layout

```
binance/
└── disruptor/
    ├── DepthEvent.java
    ├── DepthEventFactory.java
    ├── DepthEventHandler.java
    ├── DisruptorShardManager.java
    └── DisruptorDepthMessageHandler.java
config/
└── DisruptorProperties.java
```

---

### `config/DisruptorProperties.java`

```java
@ConfigurationProperties(prefix = "screener.disruptor")
public record DisruptorProperties(int shardCount, int ringBufferSize) {}
```

`application.yml` already has `screener.disruptor.shard-count` and
`screener.disruptor.ring-buffer-size`. Register in `WebClientConfig`'s
`@EnableConfigurationProperties` alongside `BinanceApiProperties` and `WebSocketProperties`.

---

### `binance/disruptor/DepthEvent.java`

Pre-allocated ring buffer entry. Disruptor creates `ringBufferSize` instances at startup via the
factory — never again after that. Fields are set on publish and nulled on consume to release the
`String` references back to GC.

```java
public class DepthEvent {
    public String symbol;
    public Market market;
    public String rawJson;
}
```

Public fields — not getters — to avoid method dispatch overhead inside the tight consumer loop.

---

### `binance/disruptor/DepthEventFactory.java`

```java
public class DepthEventFactory implements EventFactory<DepthEvent> {
    @Override
    public DepthEvent newInstance() { return new DepthEvent(); }
}
```

---

### `binance/disruptor/DepthEventHandler.java`

Phase 2 consumer. Single-threaded per shard so `count` needs no synchronization.

```java
public class DepthEventHandler implements EventHandler<DepthEvent> {

    private final int shardIndex;
    private long eventCount = 0;

    public DepthEventHandler(int shardIndex) {
        this.shardIndex = shardIndex;
    }

    @Override
    public void onEvent(DepthEvent event, long sequence, boolean endOfBatch) {
        eventCount++;
        // Phase 3: extract symbol/market, route to orderbook processor
        event.symbol  = null;   // release String reference
        event.rawJson = null;
        event.market  = null;
    }

    public long getShardIndex() { return shardIndex; }

    /** Called from the stats logger thread — safe because it's a single long read. */
    public long getAndResetCount() {
        long c = eventCount;
        eventCount = 0;
        return c;
    }
}
```

**Note on `getAndResetCount()` thread safety**: `eventCount` is a plain `long` written only by the
consumer thread and read/reset by the stats logger. On x86-64 a 64-bit read is atomic in practice,
but it is not guaranteed by the JMM. Two safe options:
- Declare `eventCount` as `volatile long` (cheap — stats logging is infrequent)
- Use `LongAdder` (overkill here)

Recommend `volatile long` for correctness without any meaningful cost.

---

### `binance/disruptor/DisruptorShardManager.java`

`@Component`. Creates and manages N `Disruptor<DepthEvent>` instances. Exposes shard routing for
the publisher.

**Constructor / fields:**
- `DisruptorProperties props`
- `RingBuffer<DepthEvent>[] ringBuffers` — length = shardCount
- `DepthEventHandler[] handlers` — one per shard, held for stats logging
- `Disruptor<DepthEvent>[] disruptors` — held for clean shutdown

**`@PostConstruct start()`:**
```
for each shard i:
  threadFactory = r -> new Thread(r, "disruptor-shard-" + i)
  disruptor = new Disruptor<>(new DepthEventFactory(), props.ringBufferSize(),
                               threadFactory, ProducerType.MULTI,
                               new BlockingWaitStrategy())
  handler = new DepthEventHandler(i)
  disruptor.handleEventsWith(handler)
  ringBuffers[i] = disruptor.start()
  handlers[i] = handler
log: "Disruptor pipeline started — {} shards, {} slots each"
```

**`getRingBuffer(String symbol)`:**
```java
return ringBuffers[Math.abs(symbol.hashCode()) % props.shardCount()];
```

**`@Scheduled(fixedDelay = 10_000) logStats()`:**

Two-tier logging:
- Every 10s: per-shard breakdown + aggregate total at `DEBUG`
- Every 5 minutes (30 × 10s): average events/second at `INFO`

```
long total = 0
for each handler:
  long c = handler.getAndResetCount()
  total += c
  log.debug("  shard {}: {} events", handler.getShardIndex(), c)
log.debug("Disruptor — {} total events in last 10s", total)

windowTotal += total
if (++windowIntervals >= 30):
  log.info("Disruptor avg throughput — {} events/sec", windowTotal / 300)
  windowTotal = 0
  windowIntervals = 0
```

**`@PreDestroy shutdown()`:**
```
for each disruptor:
  disruptor.shutdown()   // drains in-flight events before returning
log: "Disruptor pipeline shut down"
```

---

### `binance/disruptor/DisruptorDepthMessageHandler.java`

`@Component`. Replaces `LoggingDepthMessageHandler` as the active `RawDepthMessageHandler` bean.
Publishes each incoming depth event to the correct shard's ring buffer.

```java
@Component
@RequiredArgsConstructor
public class DisruptorDepthMessageHandler implements RawDepthMessageHandler {

    private final DisruptorShardManager shardManager;

    @Override
    public void handle(String symbol, Market market, String rawJson) {
        RingBuffer<DepthEvent> rb = shardManager.getRingBuffer(symbol);
        long seq = rb.next();
        try {
            DepthEvent event = rb.get(seq);
            event.symbol  = symbol;
            event.market  = market;
            event.rawJson = rawJson;
        } finally {
            rb.publish(seq);
        }
    }
}
```

The `try/finally` around `publish` is mandatory — failure to publish leaves the sequence slot
permanently claimed, stalling all producers on that shard when the ring wraps around.

---

## Changes to Existing Files

### `config/WebClientConfig.java`

Add `DisruptorProperties.class` to `@EnableConfigurationProperties`.

### `binance/websocket/LoggingDepthMessageHandler.java`

Remove `@Component` — deactivated. The class stays in source; it may be useful for isolated
testing without starting the full Disruptor. Since there will now be exactly one
`RawDepthMessageHandler` bean (`DisruptorDepthMessageHandler`), no `@Primary` annotation needed.

---

## Implementation Order

1. `DisruptorProperties` record + register in `WebClientConfig`
2. `DepthEvent`
3. `DepthEventFactory`
4. `DepthEventHandler`
5. `DisruptorShardManager`
6. `DisruptorDepthMessageHandler`
7. Remove `@Component` from `LoggingDepthMessageHandler`
8. `CURRENT_STATE.md` update

---

## What Is Explicitly Deferred

| Item | Phase |
|---|---|
| Jackson streaming parser for diff updates | Phase 3 |
| Orderbook store (`TreeMap<Double, PriceLevelEntry>`) | Phase 3 |
| Snapshot fetch queue + sync state machine | Phase 3 |
| 30% price filter | Phase 3 |
| `pu` continuity validation (futures) | Phase 3 |
| Order classification (5 tiers) | Phase 4 |
| Switching to `YieldingWaitStrategy` | Pre-load-test |

---

## Verification

1. App starts — logs: `"Disruptor pipeline started — 4 shards, 65536 slots each"`
2. WebSocket pools start (existing Phase 1 logs unchanged)
3. Every 10 seconds — per-shard counts appear; aggregate total ≈ Phase 1's spot + futures counts
4. Shard distribution is roughly uniform (symbol hashing is well-distributed over USDT perpetuals)
5. No `InsufficientCapacityException` or `AlertException` — ring buffer is not overflowing
6. On shutdown — `"Disruptor pipeline shut down"` appears after pool shutdown logs
