# P2 step 5 — Venue-agnostic stream transport

**Goal**: `exchange/stream/` contains no Binance knowledge and no venue constants. The transport
starts one connection pool per enabled venue, the first time that venue shows up in a universe
event. Everything Binance-specific about the wire (subscribe frames, ack/error detection, routing,
heartbeat) lives in `exchange/binance/BinanceStreamProtocol`, bound through the SPI in the same way
as `DepthSyncStrategy`.

**Also in scope**: `ingress/` gets a single entry point, `DepthEventPublisher`, used by both
producers (WebSocket frames and REST snapshots).

**Non-goals** (decided; do not drift into them):

- **No binary frames.** Payloads stay `String`, and `DepthEvent.rawJson` keeps its name.
- **No startup retry** (review §1 #4). It is the next task after this one.
- **No dynamic subscribe/unsubscribe**, and no `instrumentId → connection` reverse map. Bybit's
  resubscribe-to-recover belongs to a later phase.
- **No book reset on reconnect** (the P3 reset lane).
- **No `tryNext()` backpressure change.** This refactor only puts the blocking `rb.next()` in one
  place.
- **No gating of `BinanceAdapterConfig` on `enabled`** (review §1 #5, adapter half). See §3.4.
- **No subscribe-frame pacing.** Binance sends 1–3 frames per connection, so it is not needed yet.

**Assessed at**: commit `28d0d84`, branch `feature/multi-exchange`.

---

## 1. Target layout

| Package | File | Status |
|---|---|---|
| `ingress/` | `DepthEventPublisher` | **new** interface; replaces `stream/RawDepthMessageHandler` |
| `ingress/` | `DisruptorDepthEventPublisher` | renamed from `DisruptorDepthMessageHandler`; implements both publish methods |
| `spi/` | `StreamProtocol` | **new** |
| `spi/` | `Heartbeat` | **new** sealed interface |
| `spi/` | `VenueStreamBinding` | **new** record `(Venue, StreamProtocol)` |
| `spi/` | `StreamProtocolRegistry` | **new**, mirrors `SyncStrategyRegistry` |
| `stream/` | `StreamManager` | replaces `BinanceWebSocketManager` |
| `stream/` | `ConnectionPool` | replaces `BinanceConnectionPool` |
| `stream/` | `StreamConnection` | replaces `BinanceStreamClient` |
| `stream/` | `SubscriptionIndex` | kept; built from `protocol.routingKey(...)` |
| `binance/` | `BinanceStreamProtocol` | **new**; takes all Binance wire logic |
| `binance/` | `BinanceAdapterConfig` | adds two `VenueStreamBinding` beans |
| `recovery/` | `SnapshotFetchQueue` | publishes through `DepthEventPublisher` |
| `config/` | `ExchangesProperties.VenueProperties` | `depthStream` → `streamTopic` template; gains `heartbeatIntervalSeconds` |
| `config/` | `WebSocketProperties` | loses `heartbeatIntervalSeconds` (reconnect timing only) |

Resulting dependency directions: `stream → ingress`, `recovery → ingress`, `spi → stream`
(for `SubscriptionIndex`), `binance → spi`. Nothing in `ingress/` imports `stream/`, and no core
package imports `binance/` (except `SnapshotFetchQueue → BinanceRestClient`, which is P2 step 3).

---

## 2. Step 1 — `ingress/` entry point

### 2.1 `DepthEventPublisher`

```java
package dev.abu.screener_backend.marketdata.ingress;

/**
 * The only way events enter the sharded pipeline. Implementations pick the shard from the id
 * ({@code id & (shardCount - 1)}), so both producers route an instrument to the same shard.
 */
public interface DepthEventPublisher {

  /**
   * Hot path, WebSocket reader threads. Must not parse.
   *
   * @param instrumentId already resolved by the transport; always a valid, published id
   * @param payload      the frame exactly as received
   */
  void publishFrame(int instrumentId, String payload);        // EventType.WS_MSG

  /** Snapshot-fetch completion threads (Reactor). */
  void publishSnapshot(int instrumentId, String payload);     // EventType.REST_MSG
}
```

The Javadoc must not `{@link}` `SubscriptionIndex`, because `ingress/` should not point back up to
`stream/`.

### 2.2 `DisruptorDepthEventPublisher`

Rename `DisruptorDepthMessageHandler`. It holds the one copy of the fill-and-publish block, in
`private void publish(EventType type, int id, String payload)`. Both public methods delegate to it.
It keeps the blocking `rb.next()`. Its Javadoc names it as the single place where the future
`tryNext()` change goes.

Update `DisruptorShardManager.ringFreePerShard()` Javadoc: `{@link DisruptorDepthMessageHandler}`
→ `{@link DisruptorDepthEventPublisher}`.

### 2.3 `SnapshotFetchQueue`

- Constructor: replace `@Lazy DisruptorShardManager shardManager` with
  `@Lazy DepthEventPublisher publisher`. The cycle is unchanged. It still runs
  `… → SnapshotFetchQueue → publisher → DisruptorShardManager → BookSlotTable → …`, so the `@Lazy`
  stays. Update the class Javadoc's cycle description.
- Delete `publishSnapshotEvent`. The success lambda calls
  `publisher.publishSnapshot(slot.instrument().id(), rawJson)`.
- Remove the imports of `RingBuffer`, `DepthEvent`, `DisruptorShardManager` and `EventType`.

### 2.4 Temporary bridge

Until step 5 lands, `BinanceStreamClient` / `BinanceConnectionPool` / `BinanceWebSocketManager`
switch from `RawDepthMessageHandler` to `DepthEventPublisher` (`handler.handle(id, msg)` →
`publisher.publishFrame(id, msg)`). Delete `RawDepthMessageHandler`. The tree compiles at the end
of this step.

---

## 3. Step 2 — config

### 3.1 `VenueProperties`

```java
public record VenueProperties(
        String streamUrl,
        String restUrl,
        String streamTopic,              // was depthStream
        int maxStreamsPerConnection,
        int codecBufferSizeMb,
        long weightThreshold,
        int minConnections,
        int maxConnections,
        int subscribeChunkSize,
        int heartbeatIntervalSeconds     // moved from WebSocketProperties
) {
    public static final String SYMBOL_PLACEHOLDER = "{symbol}";

    public VenueProperties {
        // Fail at startup rather than subscribing every stream to a garbage topic.
        if (streamTopic == null || !streamTopic.contains(SYMBOL_PLACEHOLDER)) {
            throw new IllegalArgumentException("stream-topic must contain " + SYMBOL_PLACEHOLDER
                    + ", got: " + streamTopic);
        }
        if (subscribeChunkSize <= 0) throw new IllegalArgumentException("subscribe-chunk-size must be > 0");
        if (heartbeatIntervalSeconds <= 0) throw new IllegalArgumentException("heartbeat-interval-seconds must be > 0");
    }

    /**
     * Renders this venue's topic for one symbol. Casing is the adapter's call; this method
     * substitutes {@code symbol} exactly as given.
     */
    public String streamTopic(String symbol) {
        return streamTopic.replace(SYMBOL_PLACEHOLDER, symbol);
    }
}
```

Javadoc: `@param streamTopic` is the per-venue topic template, for example Binance
`{symbol}@depth` or Bybit `orderbook.50.{symbol}`. Also drop the `BinanceConnectionPool` reference
from `@param minConnections` (it becomes `ConnectionPool`).

### 3.2 `WebSocketProperties`

Remove `heartbeatIntervalSeconds`. The Javadoc's "Transitional" paragraph is now resolved. Rewrite
it to say that heartbeat moved to the venue block and only reconnect backoff stays uniform.

### 3.3 YAML (`application.yml` **and** `application-local.yml`)

```yaml
SPOT:
  stream-topic: "{symbol}@depth"          # was depth-stream: "@depth"
  heartbeat-interval-seconds: 120
FUTURES:
  stream-topic: "{symbol}@depth@500ms"    # was depth-stream: "@depth@500ms"
  heartbeat-interval-seconds: 120
```

Remove `screener.websocket.heartbeat-interval-seconds` from both files. The quotes are required,
because an unquoted `{…}` is a YAML flow map.

### 3.4 Missing venue block

Resolving `exchanges.venue(...)` inside the adapter's binding beans (§5) makes a missing Binance
block a startup failure. This is **not a regression**: `WebClientConfig` already calls
`exchanges.venue(...)` unconditionally. The real fix is gating the whole adapter config (review §1
#5), and that is out of scope here.

---

## 4. Step 3 — SPI

### 4.1 `Heartbeat`

```java
package dev.abu.screener_backend.marketdata.spi;

/** How a connection keeps itself alive. The interval comes from the venue's config. */
public sealed interface Heartbeat {

  Duration interval();

  /** A WebSocket control-frame PING (Binance). */
  record ProtocolPing(Duration interval) implements Heartbeat {
  }

  /** An application-level text frame (Bybit {@code {"op":"ping"}}, MEXC {@code {"method":"PING"}}). */
  record TextPing(Duration interval, String payload) implements Heartbeat {
  }
}
```

`TextPing` has no user yet. It is kept because it is exactly the case the review names for
Bybit/MEXC, and it costs a single `switch` arm. Skip it if you want a strict YAGNI line; the switch
then gains the arm later.

### 4.2 `StreamProtocol`

```java
package dev.abu.screener_backend.marketdata.spi;

/**
 * One venue's wire protocol: what to send, how to read what comes back, and how to stay alive.
 *
 * <p>Core ({@code stream/}) owns connection lifecycle, reconnect, connection fan-out, subscribe
 * chunking and heartbeat scheduling. The protocol owns only byte-level knowledge of the venue.
 * One instance per venue, contributed through a {@link VenueStreamBinding}.
 */
public interface StreamProtocol {

  /** The frame was not a data frame for any instrument: ack, pong, error, or unrecognised. */
  int IGNORED = -2;
  /** A data frame whose routing key this connection never subscribed. Same value as {@link SubscriptionIndex#resolve} misses. */
  int UNKNOWN = -1;

  /**
   * Cold. One subscribe frame for a chunk of instruments. Core chunks by the venue's
   * {@code subscribe-chunk-size}; {@code requestId} is the chunk's index on this connection.
   */
  String subscribeFrame(List<Instrument> chunk, int requestId);

  /** Cold. The key the connection's {@link SubscriptionIndex} is built on. */
  String routingKey(Instrument instrument);

  /**
   * <b>Hot path</b>, called on the WebSocket reader thread for every frame. No allocation beyond
   * what {@link SubscriptionIndex#resolve} does and no logging above DEBUG, except for rare
   * error frames.
   *
   * @return the instrument id ({@code >= 0}), {@link #IGNORED}, or {@link #UNKNOWN}
   */
  int route(String frame, SubscriptionIndex index);

  /** Cold; read once per connection open. */
  Heartbeat heartbeat();
}
```

`spi → stream` for `SubscriptionIndex` is acceptable, since `spi/` already imports `ingress/` and
`book/`.

**Why `route` takes the index**: returning the resolved id keeps the call allocation-free without
packing a `(start, end)` pair into a `long`. It also lets a protocol find the key however its
frames need, whether that is a JSON field, a topic suffix, or (later) a protobuf field. Index
semantics stay core-owned. `SubscriptionIndex.resolve(msg, start, end)` keeps its signature.

### 4.3 `VenueStreamBinding` and `StreamProtocolRegistry`

`record VenueStreamBinding(Venue venue, StreamProtocol protocol) {}`, with Javadoc mirroring
`VenueStrategyBinding`.

`StreamProtocolRegistry` is an `@Component` taking `(List<VenueStreamBinding>, ExchangesProperties)`:

- Duplicate venue → `IllegalStateException`, with the same "stray `@Component`" message as
  `SyncStrategyRegistry`.
- **Startup completeness check**: for every `Venue` where `exchanges.isEnabled(venue)` and no
  binding exists → `IllegalStateException`. `SyncStrategyRegistry` has no such check. Here it moves
  "an enabled venue can never stream" from a log line on the discovery thread to a failed boot.
- `forVenue(Venue)` → the protocol, or `IllegalStateException` (same contract as
  `SyncStrategyRegistry.forVenue`).

---

## 5. Step 4 — `BinanceStreamProtocol`

```java
package dev.abu.screener_backend.marketdata.binance;

@Slf4j
public final class BinanceStreamProtocol implements StreamProtocol {

  private final Venue venue;
  private final VenueProperties props;

  public BinanceStreamProtocol(Venue venue, VenueProperties props) { ...}
```

| Method | Behaviour (moved from `BinanceStreamClient` unless noted) |
|---|---|
| `subscribeFrame` | `{"method":"SUBSCRIBE","params":["<topic>",…],"id":<requestId>}`, where each topic is `props.streamTopic(nativeSymbol.toLowerCase(Locale.ROOT))`. Uses a `StringBuilder` instead of `String.join` over a list (cold path, either works). `Locale.ROOT` is new, which fixes a latent Turkish-locale bug. |
| `routingKey` | `instrument.nativeSymbol()`. Binance's `"s"` field is the upper-case native symbol. |
| `route` | See below |
| `heartbeat` | `new ProtocolPing(Duration.ofSeconds(props.heartbeatIntervalSeconds()))` |

`route(frame, index)`:

```java
if (frame.length() <= 4) return IGNORED;
char c2 = frame.charAt(2);
if (c2 == 'r') {                                   // {"result":null,"id":N} — SUBSCRIBE ack
    log.debug("[{}] SUBSCRIBE ack received", venue);
    return IGNORED;
}
if (c2 == 'e' && frame.charAt(3) == 'r') {         // {"error":{...},"id":N} — NEW: previously dropped silently
    log.warn("[{}] Stream error frame: {}", venue, frame);
    return IGNORED;
}
int sPos = frame.indexOf("\"s\":\"");
if (sPos == -1) return IGNORED;
int start = sPos + 5;
int end = frame.indexOf('"', start);
if (end == -1) return IGNORED;
return index.resolve(frame, start, end);           // id, or -1 == UNKNOWN
```

The error-frame branch is the one behaviour change in this step. Binance answers a bad SUBSCRIBE
(an invalid stream name, too many streams) with `{"error":…}`. Today that is swallowed by the
`"s"` lookup, and the only symptom is books that never sync. The check is two `charAt`s on a
branch that only fires when `charAt(2) == 'e'`. That is true for every depth frame
(`{"e":"depthUpdate"`), so `charAt(3)` (`'"'` vs `'r'`) is what separates them.

### 5.1 Bindings in `BinanceAdapterConfig`

```java
@Bean
VenueStreamBinding binanceSpotStreamBinding(ExchangesProperties exchanges) {
    return new VenueStreamBinding(Venue.BINANCE_SPOT,
            new BinanceStreamProtocol(Venue.BINANCE_SPOT, exchanges.venue(Venue.BINANCE_SPOT)));
}
// + binanceFuturesStreamBinding, same shape
```

Update the class Javadoc: the config now also owns "how Binance venues are streamed".

---

## 6. Step 5 — core transport

### 6.1 `StreamConnection` (replaces `BinanceStreamClient`)

Constructor:
`(URI, Venue, List<Instrument>, StreamProtocol, DepthEventPublisher, ScheduledExecutorService,
VenueProperties, WebSocketProperties)`. The index becomes
`new SubscriptionIndex(instruments, protocol::routingKey)` (see §6.4).

| Concern | Change |
|---|---|
| `onOpen` | `for (String frame : subscribeFrames(protocol, instruments, venueProps.subscribeChunkSize())) send(frame);`. The chunking loop moves into a **package-private static** `subscribeFrames(...)` so it can be tested without a socket. |
| `onMessage` | `int id = protocol.route(message, index); if (id >= 0) publisher.publishFrame(id, message); else if (id == StreamProtocol.UNKNOWN) noteUnknownFrame(message);`. `IGNORED` falls through. |
| `noteUnknownFrame` | Same rate-limited counter. It logs `message.substring(0, min(len, 120))` instead of the extracted symbol, which core no longer knows. Rare path, so the allocation is fine. The comment about why this should be permanently zero stays. |
| Heartbeat | `Heartbeat hb = protocol.heartbeat()` read in `startHeartbeat()`, scheduled at `hb.interval()`. `sendHeartbeat` runs `switch (hb) { case ProtocolPing p -> sendPing(); case TextPing t -> send(t.payload()); }` inside the existing `isOpen()` + try/catch. |
| Reconnect, `onClose`, `onError`, `shutdown` | Unchanged. |
| Logging | Same messages. `[{}]` stays `venue`. |

### 6.2 `ConnectionPool` (replaces `BinanceConnectionPool`)

Constructor: `(Venue, VenueProperties, WebSocketProperties, StreamProtocol, DepthEventPublisher)`.
The body is unchanged apart from building `StreamConnection`s. `start()` is not idempotent, and
`StreamManager` guarantees a single call. Add an `IllegalStateException` guard if it is called
twice. Keep the `connectionCount` Javadoc and change "Binance's 1024-stream ceiling" to "a venue
with a large per-connection cap (Binance: 1024)". Make the class non-`final` so `StreamManagerTest`
can record calls (§8.1).

### 6.3 `StreamManager` (replaces `BinanceWebSocketManager`)

```java
@Slf4j
@Component
public class StreamManager {

    /** Test seam: builds a pool for a venue. Production wires the real constructor. */
    @FunctionalInterface
    interface PoolFactory { ConnectionPool create(Venue venue, StreamProtocol protocol); }

    private final ExchangesProperties exchanges;
    private final StreamProtocolRegistry protocols;
    private final PoolFactory poolFactory;

    private final Map<Venue, ConnectionPool> pools = new EnumMap<>(Venue.class); // guarded by this
    private boolean shutDown;                                                     // guarded by this

    @Autowired
    public StreamManager(ExchangesProperties exchanges, WebSocketProperties wsProps,
                         StreamProtocolRegistry protocols, DepthEventPublisher publisher) {
        this(exchanges, protocols, (venue, protocol) ->
                new ConnectionPool(venue, exchanges.venue(venue), wsProps, protocol, publisher));
    }

    StreamManager(ExchangesProperties exchanges, StreamProtocolRegistry protocols, PoolFactory poolFactory) { ... }
```

`onUniverseChanged(event)` is `@EventListener` and `synchronized`. It runs on the discovery thread;
`shutdown()` runs on the context-close thread.

1. If `shutDown`, return.
2. Group `event.getAdded()` by venue into an `EnumMap<Venue, List<Instrument>>`. Order within a
   venue is preserved; `added` is already in id order.
3. For each `(venue, instruments)`:
   - **Pool exists** → count it as "unhandled".
   - **`!exchanges.isEnabled(venue)`** → `log.warn` and skip. This is defensive only; discovery
     never emits disabled venues.
   - **Otherwise** → `try { pool = poolFactory.create(venue, protocols.forVenue(venue)); pool.start(instruments); pools.put(venue, pool); } catch (RuntimeException e) { log.error(...) }`.
     Failure is isolated per venue: one venue failing must not stop the others, and must not throw
     back into `InstrumentUniverseService.apply()`. On failure, `pools` does **not** get the venue,
     so a later event can try again. That will be with that event's `added` only, which is logged
     as a known limitation (see §6.3.1).
4. For every venue with a pool, if the event carries added or removed instruments for it:
   `log.info("[{}] Universe changed (+{} / -{}) — dynamic re-subscription not yet implemented", …)`.
   There is one line per venue, replacing the old single global line.
5. After starting any pool: `log.info("WebSocket pool started for {} — {} instruments", venue, n)`.

`@PreDestroy shutdown()` is `synchronized`: it sets `shutDown = true`, shuts down every pool, and
clears the map.

#### 6.3.1 The invariant this relies on — document it on the class and the event

> The first time a venue appears in `added`, that list is the venue's **entire** current universe.
> `InstrumentUniverseService.apply()` only adds instruments that `registry.find` has never seen,
> and a venue's first successful fetch has no earlier registrations.

This is what makes per-venue start correct without dynamic subscribe. It holds for every
first-appearance case. A venue whose first fetch returns an empty list gets no pool, and its first
non-empty fetch is again all-new.

**Known limitation (step 3 failure path)**: if `pool.start` throws for a venue, a later event
carries only that event's *new* instruments for it, so a retried pool would be partial. `start()`
cannot realistically throw today: `connect()` is async, and a bad URL is caught inside. So this
plan only logs the failure and does not retry. Note it in the class Javadoc; dynamic subscribe
removes it.

### 6.4 `SubscriptionIndex`

Constructor becomes `SubscriptionIndex(List<Instrument> instruments, Function<Instrument, String> routingKey)`.
Duplicate keys → `IllegalArgumentException`. Two instruments on one connection sharing a key would
silently route one's frames to the other, which is the exact misidentification this class exists
to prevent. Update the class Javadoc: "native symbol" → "routing key (for Binance, the native
symbol)".

### 6.5 Deletions

`stream/BinanceWebSocketManager.java`, `stream/BinanceConnectionPool.java`,
`stream/BinanceStreamClient.java`. `RawDepthMessageHandler` and `DisruptorDepthMessageHandler` are
already gone after step 1.

---

## 7. Step 6 — Javadoc/comment references to fix

| Where | What |
|---|---|
| `InstrumentUniverseChangedEvent` class Javadoc | Replace "on the first refresh it is the entire universe, which is what the transport uses to build its connection pools" with the per-venue invariant from §6.3.1 |
| `InstrumentUniverseService.apply()`, the `succeeded == 0` comment | "the transport, which starts its pools from the first event it receives" → "the transport, which starts a venue's pool from the first event that adds to it" |
| `Market.java` Javadoc | `…venues.*.depth-stream` → `…venues.*.stream-topic` |
| `ExchangesProperties` | the `@param` changes in §3.1 |
| `WebSocketProperties` | §3.2 |
| `DisruptorShardManager.ringFreePerShard` | §2.2 |
| `SnapshotFetchQueue` class Javadoc | §2.3 |

---

## 8. Step 7 — tests

There are no `stream/` tests today. New test classes:

### 8.1 `exchange/stream/StreamManagerTest`

Uses the package-private constructor. The `PoolFactory` returns a `RecordingPool extends ConnectionPool`
that overrides `start`/`shutdown` to record calls without connecting. The `ConnectionPool`
constructor only creates a scheduler, which `shutdown()` must release; the fake's `shutdown()` calls
`super.shutdown()`. `ExchangesProperties` and `StreamProtocolRegistry` are built directly with Binance
venue blocks and stub protocols.

| Test | Asserts |
|---|---|
| first event with both venues | two pools; each `start` receives only its venue's instruments, in id order |
| **first event carries only futures** (the review §1 #3 bug) | only the futures pool exists; a second event adding spot instruments starts the spot pool with exactly those |
| event for an already-started venue | no second pool; `start` not called again |
| event with only `removed` | no pool created |
| venue disabled in config | skipped, no pool |
| pool factory throws for one venue | the other venue's pool still starts; no exception escapes `onUniverseChanged` |
| `shutdown` then event | all pools shut down; the later event starts nothing |

Only Binance venues exist, so "another exchange succeeds while Binance fails" is modelled as
"futures appears before spot". The code path is the same one (a venue first seen in a later event).

### 8.2 `exchange/binance/BinanceStreamProtocolTest`

- `subscribeFrame` is exact for both venues: lower-cased symbols, template applied, `id` passed through.
- `route` with a real spot frame (`{"e":"depthUpdate","E":…,"s":"BTCUSDT","U":…,"u":…,"b":[…],"a":[…]}`) → id.
- `route` with a real futures frame (with `T` and `pu` before/after `s`) → id.
- ack `{"result":null,"id":0}` → `IGNORED`.
- error `{"error":{"code":2,"msg":"Invalid request"},"id":0}` → `IGNORED`.
- unsubscribed symbol → `UNKNOWN`.
- `""`, `"{}"`, a frame with no `"s"`, and an unterminated `"s":"BTC` → `IGNORED`, with no exception.
- `heartbeat()` is a `ProtocolPing` with the configured interval.

### 8.3 `exchange/stream/SubscriptionIndexTest`

- Resolving a `[start, end)` range inside a larger string returns the id.
- A miss returns `-1`.
- Duplicate routing keys → `IllegalArgumentException`.

### 8.4 `exchange/stream/StreamConnectionTest`

- `subscribeFrames(...)` static: 0 instruments → no frames; 5 instruments at chunk size 2 → 3
  frames with request ids 0, 1, 2 and chunks [2, 2, 1].
- `onMessage` with a stub protocol and a recording `DepthEventPublisher`, called directly on a
  never-connected `StreamConnection` (the `WebSocketClient` constructor does not connect):
  - id ≥ 0 → published once, with the same `String` instance.
  - `IGNORED` / `UNKNOWN` → nothing published.

### 8.5 Existing tests

- `BinanceAdapterConfigTest`: add "every Binance venue has a stream binding with a
  `BinanceStreamProtocol`, one instance per venue", following the existing strategy-binding tests.
- `StreamProtocolRegistryTest` (in `spi/`, or inside the adapter test if shorter): duplicate binding
  throws; an enabled venue without a binding throws; a disabled venue without a binding is fine.
- `config/VenuePropertiesTest`: a `stream-topic` without `{symbol}` throws, a non-positive chunk
  size or heartbeat interval throws, and `streamTopic("btcusdt")` renders `btcusdt@depth`.
- Any existing test that builds `VenueProperties` positionally will need the new argument order.
  Grep for `new VenueProperties(` / `new ExchangesProperties.VenueProperties(`.

---

## 9. Step 8 — docs

- `CLAUDE.md` Conventions: "depth-stream suffix" → "stream-topic template, heartbeat interval".
  The Module Map `exchange/` row: the `stream/` description stays accurate, but add that the wire
  protocol is adapter-supplied via `StreamProtocol`.
- `.claude/docs/multi-exchange-progress.md`: mark P2 step 5 **Done**. In the §5 "Not yet additive"
  table, move Transport to "Already additive" (the mechanism is `StreamProtocol` +
  `VenueStreamBinding`). In §6.1, change "No dynamic subscribe" to reference `StreamManager`, and
  update the "Ring-buffer backpressure" bullet to name `DisruptorDepthEventPublisher` as the single
  site.
- `.claude/plans/multi-exchange-generalization-review.md`: mark §1 #3 done, and note that §1 #5's
  transport half is done (the transport only touches venues that discovery emits).
- `.claude/docs/orderbook-sync-algorithm.md` is already flagged stale (progress doc §6.3). Leave it.

---

## 10. Order of work and verification

Each step must leave `./mvnw test` green:

1. `ingress/` entry point + `SnapshotFetchQueue` + bridge (§2)
2. Config + YAML (§3)
3. SPI types + registry (§4)
4. `BinanceStreamProtocol` + bindings (§5)
5. Core transport rewrite + deletions (§6)
6. Javadoc sweep (§7)
7. Tests (§8). The protocol and index tests can land with steps 4–5.
8. Docs (§9)

**Final checks**

- `./mvnw clean package` is green.
- `grep -rn "Binance\|BINANCE_" src/main/java/dev/abu/screener_backend/exchange/stream` → no hits.
- `grep -rn "exchange.stream" src/main/java/dev/abu/screener_backend/exchange/ingress` → no hits.
- Live run (`-Dspring.profiles.active=local`), compared against a run on `28d0d84`:
  - Startup log shows one "pool started" line per Binance venue, with the same instrument counts
    and the same connection counts (spot 2, futures 3).
  - SUBSCRIBE acks appear at DEBUG, and no stream error frames are logged.
  - The pipeline health line shows synced/tracked per venue converging to the same level as before
    within a minute or two, with msgs/s per shard in the same range.
  - After ~5 minutes: no "unsubscribed symbol" warnings, and reconnects (if any) resubscribe
    cleanly.
