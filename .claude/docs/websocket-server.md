# WebSocket Server — Outbound Delivery to Clients

## Overview

The WebSocket server is the outbound delivery layer: it accepts browser or API client connections and pushes classified order book events as JSON. The endpoint is `ws://localhost:8080/ws`.

Everything upstream (Disruptor pipeline, order book sync, classifier, feed store, broadcaster drain loop) runs independently of client I/O. The central design constraint is that **client I/O must never slow down or block the screener pipeline**. The implementation achieves this via a per-session send queue and a dedicated virtual thread per session.

---

## Package Layout

```
src/main/java/dev/abu/screener_backend/
├── ws/
│   ├── WebSocketConfig.java             — registers @ServerEndpoint classes with Tomcat
│   ├── CustomSpringConfigurator.java    — bridges Spring DI into Tomcat's WebSocket lifecycle
│   ├── ScreenerWebSocketEndpoint.java   — @ServerEndpoint("/ws"), thin lifecycle handler
│   └── UserWebSocketSession.java        — per-session state: queue, virtual thread, seq counter
└── feed/
    └── OrderBookBroadcaster.java        — 100ms drain loop; the only component that writes to sessions
```

---

## Library: Tomcat Jakarta WebSocket (JSR 356)

The embedded Tomcat server already ships JSR 356. Adding `spring-boot-starter-websocket` to `pom.xml` activates it — it is not a new server or port. The `@ServerEndpoint` / `Session` API gives direct access to Tomcat sessions with zero additional abstraction.

`spring-boot-starter-websocket` transitively pulls in `spring-messaging` (the STOMP layer). STOMP, SockJS, and `@MessageMapping` are **not used**. The screener uses the lower-level `@ServerEndpoint` / `Session` API directly.

---

## Class Responsibilities

### `WebSocketConfig`

A single `@Bean` that creates a `ServerEndpointExporter`. This bean tells Spring to scan for `@ServerEndpoint`-annotated classes and register them with the embedded Tomcat WebSocket container. Without it, the endpoint class is ignored by Tomcat at runtime.

### `CustomSpringConfigurator`

Bridges Spring dependency injection into Tomcat's WebSocket lifecycle. Tomcat instantiates `@ServerEndpoint` classes itself, bypassing Spring — which would leave `@Autowired` fields null. `CustomSpringConfigurator` fixes this with the static-field pattern:

1. Spring creates `CustomSpringConfigurator` as a bean and calls `setApplicationContext()`, storing the context in a `static volatile` field.
2. When a WebSocket connection arrives, Tomcat instantiates a fresh `CustomSpringConfigurator` and calls `getEndpointInstance(ScreenerWebSocketEndpoint.class)`.
3. `getEndpointInstance()` calls `context.getBean(ScreenerWebSocketEndpoint.class)`, returning the Spring-managed singleton with all dependencies already injected.

**Why not `SpringConfigurator` from `spring-websocket`?** It looks up the `ApplicationContext` via `ContextLoaderListener`, which does not exist in Spring Boot's embedded Tomcat setup. It fails at connection time with `IllegalStateException: Failed to find the root WebApplicationContext`.

### `ScreenerWebSocketEndpoint`

A singleton Spring `@Component` annotated with `@ServerEndpoint("/ws")`. All connections share one instance — this is safe because the class holds no per-connection mutable state. All per-session state lives in `UserWebSocketSession`, stored in `session.getUserProperties()`.

Tomcat supplies the relevant `Session` parameter on every callback, so the shared instance correctly routes each event to the right session object.

| Callback | Responsibility |
|---|---|
| `@OnOpen` | Create `UserWebSocketSession`, store in `getUserProperties`, start send loop, register with broadcaster |
| `@OnClose` | Retrieve session, call `disconnect()`, call `broadcaster.removeSession()` |
| `@OnError` | Same as `@OnClose` — Tomcat calls `@OnError` then `@OnClose` on errors; both are idempotent |
| `@OnMessage` | Dispatch client message; currently only `SNAPSHOT_REQUEST` (raw string) is handled |

`@OnClose` is the single owner of `broadcaster.removeSession()`. The eviction path (broadcaster → `session.disconnect()`) deliberately does not call `removeSession()` directly — it signals the virtual thread, which closes the Jakarta session, which triggers `@OnClose`, which calls `removeSession()`.

### `UserWebSocketSession`

The core per-session object. Created in `@OnOpen`, stored in `session.getUserProperties()`.

**Fields:**

| Field | Type | Owner thread | Purpose |
|---|---|---|---|
| `jakartaSession` | `Session` | virtual thread (sends) | Underlying Tomcat session |
| `sendQueue` | `ArrayBlockingQueue<List<String>>` | broadcaster writes, virtual thread reads | Decouples broadcaster from socket I/O |
| `status` | `volatile Status` | broadcaster reads/writes; `@OnMessage` writes | `NEED_SNAPSHOT` or `READY` |
| `running` | `volatile boolean` | broadcaster writes (via disconnect); virtual thread reads | Send loop exit signal |
| `virtualThread` | `volatile Thread` | set in `startSendLoop()` | Reference for interruption on disconnect |
| `seqNumber` | `int` | broadcaster only | Per-session message sequence number |

**Key methods:**

- `startSendLoop()` — starts the virtual thread send loop; called once from `@OnOpen`
- `enqueueBatch(List<String>)` — non-blocking `offer()` to the queue; returns `false` if full or shutting down; the broadcaster must call `disconnect()` on `false`
- `disconnect()` — sets `running = false` and interrupts the virtual thread; idempotent
- `resetSeq()` — resets `seqNumber` to 0; called only by the broadcaster right before snapshot delivery
- `getAndIncrementSeq()` — returns `++seqNumber`; called only by the broadcaster

### `OrderBookBroadcaster` (`feed/` package)

The only component that interacts with sessions. Runs a `@Scheduled(fixedDelay = 100)` drain loop that:

1. Drains `OrderBookFeedStore.drainPending()` to get the current 100ms window of classified updates.
2. For each running session:
   - **`NEED_SNAPSHOT`**: builds or reuses the snapshot body, resets the session's seq counter, wraps in a single-element batch, enqueues it, then sets status to `READY`.
   - **`READY` with non-empty pending**: builds or reuses the update bodies (shared across sessions), builds a per-session batch with injected seq numbers, enqueues it.
   - If `enqueueBatch()` returns `false`: calls `session.disconnect()` (slow client eviction).

`@PreDestroy shutdown()` signals all sessions to disconnect on Spring context shutdown.

The broadcaster **never touches the TCP socket**. Its only interaction with a session is `enqueueBatch()` — a non-blocking in-memory CAS on the queue's internal array.

---

## Concurrency Model

### Virtual Thread Per Session

Each session runs one virtual thread. The send loop:

```
while running:
    batch = sendQueue.take()         // parks (virtual) if queue is empty
    for each msg in batch:
        session.getBasicRemote().sendText(msg)  // parks (virtual) if TCP buffer is full
```

Virtual threads park on blocking I/O without holding an OS thread. A slow client's TCP stall parks only its own virtual thread. The broadcaster thread and all other session threads are unaffected.

The JSR 356 spec prohibits concurrent sends on the same session. The single-virtual-thread-per-session model satisfies this naturally — there is never more than one in-flight send per session.

### Thread Ownership

| Thread | What it does | What it never touches |
|---|---|---|
| Disruptor consumer | Parse diff, update TreeMap, run classifier, call `feedStore.submit()` | Queue, socket, broadcaster |
| `@Scheduled` broadcaster | Drain pending map, build JSON bodies, call `session.enqueueBatch()` | TCP socket, blocking I/O |
| Session virtual thread | `queue.take()`, `sendText()`, `cleanup()` on exit | OrderBook, classifier, feed store |
| Tomcat `@OnMessage` thread | Call `session.setStatus(NEED_SNAPSHOT)` | `seqNumber` (broadcaster-owned) |

### `seqNumber` Thread Safety

`seqNumber` is **not** `volatile` and intentionally so — it is read and written exclusively by the `@Scheduled` broadcaster thread. `setStatus()` (callable from the `@OnMessage` Tomcat thread) does not touch `seqNumber`. The seq reset is done by the broadcaster via `session.resetSeq()` immediately before building the snapshot message.

---

## Session Lifecycle

### Connect

1. Tomcat upgrades the HTTP request and fires `@OnOpen`.
2. A `UserWebSocketSession` is created and stored in `session.getUserProperties()`.
3. `startSendLoop()` starts the virtual thread, which immediately parks on `sendQueue.take()`.
4. `broadcaster.addSession()` registers the session.
5. On the next drain cycle (~100ms), the `NEED_SNAPSHOT` status delivers a full snapshot.

### Disconnect (client-initiated or normal)

1. Tomcat fires `@OnClose`.
2. `userSession.disconnect()` sets `running = false` and interrupts the virtual thread.
3. `broadcaster.removeSession()` removes from the `CopyOnWriteArrayList`.

### Error

Tomcat fires `@OnError` then `@OnClose`. Both call `disconnect()` and `removeSession()`, both idempotent.

### Eviction (slow client)

1. `sendQueue.offer()` returns `false` (queue at capacity = 32 drain cycles).
2. Broadcaster calls `session.disconnect()`.
3. Virtual thread unblocks (interrupted or `running == false`), falls through to `cleanup()`.
4. `cleanup()` closes the Jakarta session (`GOING_AWAY`).
5. Tomcat fires `@OnClose`, which calls `broadcaster.removeSession()`.

Eviction disconnects the client rather than dropping individual messages, because a dropped message leaves the client with an unrecoverable partial view of the order book. On reconnect the client receives a fresh `SNAPSHOT`.

---

## Message Protocol

All messages are JSON strings. Every message has a `seq` field injected as the first key — it is a per-session monotonically increasing integer starting at 1, reset to 0 before each snapshot.

### Server → Client

**Snapshot** (sent once per connection, and on `SNAPSHOT_REQUEST`):
```json
{"seq": 1, "type": "SNAPSHOT", "data": [
  {"symbol": "BTCUSDT", "market": "FUTURES", "bids": [...], "asks": [...]},
  ...
]}
```

**Update** (sent every 100ms cycle when there are changes):
```json
{"seq": 2, "type": "UPDATE", "symbol": "BTCUSDT", "market": "FUTURES", "bids": [...], "asks": [...]}
```

**Drop** (ticker removed from the screener):
```json
{"seq": 3, "type": "DROP", "symbol": "BTCUSDT", "market": "FUTURES"}
```

Each level object in `bids`/`asks`:
```json
{"price": 65432.1, "quantity": 0.85, "tier": 2, "firstSeenMillis": 1716680000000}
```

### Client → Server

| Message | Effect |
|---|---|
| `SNAPSHOT_REQUEST` (raw string) | Sets session status to `NEED_SNAPSHOT`; next drain cycle delivers a fresh snapshot |

Future client message types will use a JSON `{"type": "..."}` envelope dispatched in `@OnMessage`.

---

## Queue Sizing

The send queue holds `List<String>` batches, not individual messages. One slot = one 100ms drain cycle, regardless of how many tickers fired that cycle. Capacity 32 = ~3.2 seconds of genuine backlog. A client on a healthy connection will never hold more than 1–2 queued batches; the limit is only hit by a truly stalled TCP connection.

---

## What Is Not Implemented

- **Authentication**: JWT verification at handshake time is the intended future design, via a `HandshakeInterceptor`. `SecurityConfig` currently permits all requests.
- **Per-user classification rules**: All clients receive identical data. Per-user thresholds are a future design problem.
- **Horizontal scaling**: A single JVM pushes to all connected clients.
- **STOMP / SockJS**: Not used. Raw WebSocket only.
