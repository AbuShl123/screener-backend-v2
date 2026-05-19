# Plan: WebSocket Connection Layer (Phase 1)

## Context

Ticker management is complete. This phase establishes WebSocket connections to Binance (spot and
futures), subscribes to `@depth` streams for all registered tickers, and handles reconnection with
exponential backoff. No message processing occurs in this phase — messages are received and passed
to a stub handler that logs counts. The handler interface is deliberately designed so the Disruptor
publisher (Phase 2) simply replaces the stub behind the same interface.

---

## Design Decisions (Confirmed)

| Decision | Detail |
|---|---|
| Stream type | Non-combined. Connect to `wss://stream.binance.com/ws` (spot) or `wss://fstream.binance.com/ws` (futures). No stream names in the URL. |
| Subscription | SUBSCRIBE frames sent after connection opens. One frame per ≤300 streams (`subscribe-chunk-size`). |
| Spot stream name | `{symbol}@depth@1000ms` |
| Futures stream name | `{symbol}@depth@500ms` |
| Message discrimination | `message.charAt(2) == 'r'` → SUBSCRIBE response (`{"result":...}`), skip. Otherwise → depth event, publish to handler. |
| Symbol extraction | Quick `indexOf("\"s\":\"")` scan in `onMessage` to extract the symbol for shard routing. Symbol string is interned (`String.intern()`) so each unique symbol lives once in the JVM string pool. |
| Disruptor event payload | Store the `String` reference from java-websocket directly — no `getBytes()`. The String is already allocated by the library; a second allocation would be counterproductive. |
| Ring buffer size | 65536 per shard, 4 shards (unchanged from `application.yml`). |
| Reconnect ownership | Each `BinanceStreamClient` owns its own reconnect: schedules backoff on `onClose`, resets counter on `onOpen`. |
| Startup sequencing | `TickerService` publishes `TickersRefreshedEvent` after every successful registry update. `BinanceWebSocketManager` listens for this event to start connections — eliminates the race between `TickerRefreshScheduler`'s immediate first run and `ApplicationReadyEvent`. Also sets up future dynamic re-subscription (ticker refresh → event → update pools). |

---

## Startup / Initialization Flow

```
App starts
  └─ TickerRefreshScheduler (fixedDelay, runs immediately)
       └─ TickerService.refreshTickers()
            └─ fetches spot+futures exchangeInfo, builds registry
            └─ publishes TickersRefreshedEvent

  └─ BinanceWebSocketManager (listens for TickersRefreshedEvent)
       └─ first event received:
            └─ spot pool: start( tickers where hasSpot=true )
            └─ futures pool: start( all tickers )
       └─ subsequent events (periodic refresh):
            └─ log "ticker refresh received — dynamic re-subscription not yet implemented"
```

---

## Changes to Existing Files

### `application.yml`

```yaml
# Under screener.websocket — replace the existing block:
screener:
  websocket:
    spot-stream-url: wss://stream.binance.com/ws        # was /stream, now /ws
    futures-stream-url: wss://fstream.binance.com/ws    # was /stream, now /ws
    max-streams-per-connection: 1024
    subscribe-chunk-size: 300                           # new
    reconnect-initial-delay-ms: 100                     # new
    reconnect-max-delay-ms: 30000                       # new
```

### `config/WebClientConfig.java`

Add `@EnableConfigurationProperties(WebSocketProperties.class)` to the existing
`@EnableConfigurationProperties` list.

### `ticker/TickerService.java`

Inject `ApplicationEventPublisher eventPublisher`. At the end of a successful `refreshTickers()`
call (after `registry.replace()`), publish:
```java
eventPublisher.publishEvent(new TickersRefreshedEvent(this, registry.getAll()));
```
Do not publish on failure (registry was not updated).

---

## New Files

### Package layout

```
binance/
└── websocket/
    ├── TickersRefreshedEvent.java
    ├── Market.java
    ├── RawDepthMessageHandler.java
    ├── LoggingDepthMessageHandler.java    ← Phase 1 stub; replaced in Phase 2
    ├── BinanceStreamClient.java
    ├── BinanceConnectionPool.java
    └── BinanceWebSocketManager.java
config/
└── WebSocketProperties.java
```

---

### `config/WebSocketProperties.java`

```java
@ConfigurationProperties(prefix = "screener.websocket")
public record WebSocketProperties(
    String spotStreamUrl,
    String futuresStreamUrl,
    int maxStreamsPerConnection,
    int subscribeChunkSize,
    long reconnectInitialDelayMs,
    long reconnectMaxDelayMs
) {}
```

---

### `ticker/TickersRefreshedEvent.java`

```java
public class TickersRefreshedEvent extends ApplicationEvent {
    private final Map<String, Ticker> tickers;

    public TickersRefreshedEvent(Object source, Map<String, Ticker> tickers) {
        super(source);
        this.tickers = Collections.unmodifiableMap(tickers);
    }

    public Map<String, Ticker> getTickers() { return tickers; }
}
```

Note: lives in the `ticker` package, not `binance/websocket`, because `TickerService` publishes it
and it should not create a dependency from `ticker` → `binance`.

---

### `binance/websocket/Market.java`

```java
public enum Market {
    SPOT {
        @Override public String streamSuffix() { return "@depth@1000ms"; }
    },
    FUTURES {
        @Override public String streamSuffix() { return "@depth@500ms"; }
    };

    public abstract String streamSuffix();
}
```

---

### `binance/websocket/RawDepthMessageHandler.java`

```java
@FunctionalInterface
public interface RawDepthMessageHandler {
    /**
     * Called from the WebSocket onMessage callback. Must be fast — no blocking, no heavy parsing.
     * symbol is already interned. rawJson is the full depth update payload as received.
     */
    void handle(String symbol, Market market, String rawJson);
}
```

This is the seam between the WebSocket layer and the processing layer. Phase 2 replaces the stub
implementation with a Disruptor ring buffer publisher.

---

### `binance/websocket/LoggingDepthMessageHandler.java`

Phase 1 stub. Counts messages per market, logs a summary every 10 seconds.

```java
@Component
public class LoggingDepthMessageHandler implements RawDepthMessageHandler {

    private final AtomicLong spotCount = new AtomicLong();
    private final AtomicLong futuresCount = new AtomicLong();

    @Override
    public void handle(String symbol, Market market, String rawJson) {
        if (market == Market.SPOT) spotCount.incrementAndGet();
        else futuresCount.incrementAndGet();
    }

    @Scheduled(fixedDelay = 10_000)
    public void logStats() {
        log.info("Depth messages received — spot: {}, futures: {}",
            spotCount.getAndSet(0), futuresCount.getAndSet(0));
    }
}
```

---

### `binance/websocket/BinanceStreamClient.java`

Extends `org.java_websocket.client.WebSocketClient`. One connection, up to
`maxStreamsPerConnection` streams, for one `Market`.

**Constructor parameters:**
- `URI serverUri`
- `Market market`
- `List<String> symbols` — the symbols this connection is responsible for (immutable copy stored)
- `RawDepthMessageHandler handler`
- `ScheduledExecutorService reconnectScheduler` — shared across all clients in the pool
- `WebSocketProperties props`

**Fields:**
- `volatile boolean shuttingDown = false`
- `AtomicInteger reconnectAttempt = new AtomicInteger(0)`
- `final List<String> symbols` — stored for re-subscription on reconnect

**`onOpen`:**
1. `reconnectAttempt.set(0)` — reset backoff counter
2. Partition `symbols` into chunks of `props.subscribeChunkSize()`
3. For each chunk, build and send one SUBSCRIBE frame:
   ```json
   {"method":"SUBSCRIBE","params":["btcusdt@depth@1000ms",...],"id":"<chunkIndex>"}
   ```
4. Log: `"[SPOT|FUTURES] WebSocket opened — subscribing {} streams across {} frames"`

**`onMessage`:**
```java
// O(1) discrimination: subscription responses are {"result":...}, depth events are {"e":...}
if (message.charAt(2) == 'r') {
    log.debug("SUBSCRIBE ack received");
    return;
}
// Extract symbol — quick scan, terminates within first ~60 chars
int start = message.indexOf("\"s\":\"") + 5;
int end = message.indexOf('"', start);
String symbol = message.substring(start, end).intern();
handler.handle(symbol, market, message);
```

**`onClose`:**
```java
if (shuttingDown) return;
long delay = Math.min(
    props.reconnectInitialDelayMs() * (1L << Math.min(reconnectAttempt.getAndIncrement(), 8)),
    props.reconnectMaxDelayMs()
);
log.warn("[{}] Connection closed (code={}, reason={}). Reconnecting in {}ms", market, code, reason, delay);
reconnectScheduler.schedule(this::reconnect, delay, TimeUnit.MILLISECONDS);
```

The `reconnect()` call re-enters the java-websocket connect flow; `onOpen` fires on success and
re-subscribes all symbols.

**`onError`:** `log.warn("[{}] WebSocket error: {}", market, ex.getMessage())`

**`shutdown()`:**
```java
shuttingDown = true;
close();
```

---

### `binance/websocket/BinanceConnectionPool.java`

Manages N `BinanceStreamClient` instances for one `Market`. One pool per market.

**Constructor parameters:**
- `Market market`
- `WebSocketProperties props`
- `RawDepthMessageHandler handler`

**Fields:**
- `List<BinanceStreamClient> clients = new ArrayList<>()`
- `ScheduledExecutorService reconnectScheduler` — single shared executor for all clients in this pool,
  created in constructor: `Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "reconnect-" + market.name().toLowerCase()))`

**`start(Collection<Ticker> tickers)`:**
1. Extract symbol list from tickers
2. Partition into batches of `props.maxStreamsPerConnection()`
3. For each batch, create a `BinanceStreamClient`, call `client.connect()`, add to `clients`
4. Log: `"[{}] Starting {} connection(s) for {} tickers"`

**`shutdown()`:**
1. `clients.forEach(BinanceStreamClient::shutdown)`
2. `reconnectScheduler.shutdownNow()`
3. Log: `"[{}] Connection pool shut down"`

---

### `binance/websocket/BinanceWebSocketManager.java`

Spring `@Component`. Wires everything together. Listens for `TickersRefreshedEvent`.

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class BinanceWebSocketManager {

    private final WebSocketProperties props;
    private final RawDepthMessageHandler handler;

    private BinanceConnectionPool spotPool;
    private BinanceConnectionPool futuresPool;
    private volatile boolean initialized = false;

    @EventListener
    public void onTickersRefreshed(TickersRefreshedEvent event) {
        if (!initialized) {
            initialized = true;
            startPools(event.getTickers().values());
        } else {
            log.info("Ticker refresh received — dynamic re-subscription not yet implemented");
        }
    }

    private void startPools(Collection<Ticker> tickers) {
        List<Ticker> spotTickers = tickers.stream().filter(Ticker::hasSpot).toList();
        List<Ticker> futuresTickers = new ArrayList<>(tickers);

        spotPool = new BinanceConnectionPool(Market.SPOT, props, handler);
        futuresPool = new BinanceConnectionPool(Market.FUTURES, props, handler);

        spotPool.start(spotTickers);
        futuresPool.start(futuresTickers);

        log.info("WebSocket pools started — spot: {} tickers, futures: {} tickers",
            spotTickers.size(), futuresTickers.size());
    }

    @PreDestroy
    public void shutdown() {
        if (spotPool != null) spotPool.shutdown();
        if (futuresPool != null) futuresPool.shutdown();
    }
}
```

---

## Implementation Order

1. `application.yml` — update WebSocket URLs, add `subscribe-chunk-size` and reconnect delays
2. `WebSocketProperties` record + register in `WebClientConfig` `@EnableConfigurationProperties`
3. `TickersRefreshedEvent` (in `ticker` package)
4. `TickerService` — inject `ApplicationEventPublisher`, publish event after successful refresh
5. `Market` enum
6. `RawDepthMessageHandler` interface
7. `LoggingDepthMessageHandler` stub
8. `BinanceStreamClient`
9. `BinanceConnectionPool`
10. `BinanceWebSocketManager`
11. `CURRENT_STATE.md` — update with new classes

---

## What Is Explicitly Deferred

| Item | Phase |
|---|---|
| LMAX Disruptor ring buffers + `DisruptorDepthMessageHandler` | Phase 2 |
| Jackson streaming parser for depth events | Phase 2 |
| `OrderBook` / `PriceLevelEntry` | Phase 3 |
| Snapshot fetch queue + sync state machine | Phase 3 |
| 30% price level filter | Phase 3 |
| Order classification (5 tiers) | Phase 4 |
| Dynamic re-subscription on ticker refresh | Future (hook is in place via event listener) |

---

## Verification

1. Run app — confirm logs show `"WebSocket pools started — spot: N tickers, futures: M tickers"`
2. Wait ~10 seconds — `LoggingDepthMessageHandler` logs should show non-zero counts for both markets
3. Confirm futures count is significantly higher than spot count (more tickers on futures)
4. Kill network briefly (disable/re-enable adapter or use a proxy) — confirm reconnect log with
   increasing delay appears, then subscription ack after reconnect
5. Confirm no INFO-level logs firing per-message inside any WebSocket callback
6. Confirm no `ClassCastException` / `IndexOutOfBoundsException` from the `charAt(2)` check on
   malformed messages (Binance occasionally sends ping frames — add a length guard: `message.length() > 4`)
