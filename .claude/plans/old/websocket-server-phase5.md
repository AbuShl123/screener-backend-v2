# Phase 5 — Outbound WebSocket Server: Architecture Design

## What This Document Covers

Phase 5 is the component that pushes classified order book events to connected browser or API clients. Everything upstream (Disruptor pipeline, order book sync, classifier, feed store, broadcaster drain loop) is already built. Phase 5 adds the server-side WebSocket endpoint and the per-session delivery machinery.

This document records the architectural decisions and their reasoning. The companion implementation guide is `.claude/plans/websocket-server-phase5-impl.md`.

---

## Goals and Constraints

The four non-negotiables for this component, in priority order:

1. **Sending must never delay or block the screener pipeline.** The Disruptor consumer threads and the `@Scheduled` broadcaster drain loop must be completely insulated from client I/O.
2. **Sessions must be independent.** A slow or stalled client must not delay message delivery to any other client.
3. **Slow clients must be evicted promptly.** Unbounded memory accumulation for a lagging client is not acceptable.
4. **Sends must be fast.** Clients with good connectivity should receive events with minimal additional latency beyond the 100ms drain interval.

---

## Library Choice: Tomcat Jakarta WebSocket

### What was considered

**java-websocket (server mode)** — already a dependency, but built as a client library. Its server mode works but is not its design target: it has no connection management features suited for a production server, it runs its own embedded server on a separate port (meaning two servers inside one JVM), and it lacks integration with Spring Security and the existing session infrastructure. Rejected.

**Spring WebFlux WebSocket** — would require switching the HTTP server from Tomcat to Netty, which contradicts the deliberate MVC-over-Tomcat choice described in CLAUDE.md. WebFlux was added to the classpath solely as a client library (for `WebClient`). Promoting it to the server would couple the entire application to the reactive programming model. Rejected.

**Netty standalone** — maximum raw throughput but requires managing the full Netty pipeline manually: channel handlers, codec pipeline, frame parsing, connection lifecycle. This is significant complexity for a component that simply needs to push pre-serialized strings. Rejected.

**Tomcat Jakarta WebSocket** (`spring-boot-starter-websocket`) — the right choice. Tomcat is already the embedded server. Adding `spring-boot-starter-websocket` enables the Jakarta WebSocket (JSR 356) API that Tomcat already ships; it is not a new server, it is activating a feature of the existing one. `@ServerEndpoint` gives direct access to the Tomcat `Session` object. Integration with Spring Security (already on the classpath) is first-class. No second server, no second port, no new runtime.

### What to ignore in the dependency

`spring-boot-starter-websocket` transitively pulls in `spring-messaging` (the STOMP support layer). STOMP, SockJS, and the `@MessageMapping` machinery are not used here and can be ignored entirely. The screener uses the lower-level `@ServerEndpoint` / `Session` API directly, which sits below the STOMP layer.

---

## Concurrency Model: Virtual Thread Per Session

### The async callback alternative and why it loses

The conventional non-Java-21 approach to non-blocking WebSocket sends is `RemoteEndpoint.Async.sendText(text, SendHandler)`. This returns immediately and invokes a completion handler when the write finishes. The problem is a hard constraint in the JSR 356 specification: **only one async send may be in flight at a time per session**. If a second `sendText` is called before the first completes, the implementation throws `IllegalStateException`.

This means you must serialize sends through a per-session queue regardless — the async API buys you non-blocking initiation but requires you to build a completion-handler chain that re-checks the queue on each callback. The result is a state machine spread across callbacks, which is harder to reason about than a simple loop, and the non-blocking benefit of the async API is largely negated by the queueing requirement.

### Virtual threads on Java 21

Java 21 virtual threads change the calculus. A virtual thread performs blocking I/O by parking (suspending at the JVM level) without holding an OS thread. When `getBasicRemote().sendText(json)` blocks because the client's TCP receive buffer is full, the virtual thread parks. The OS thread underneath is released to run other virtual threads. When the TCP buffer drains, the virtual thread is rescheduled.

This means a simple blocking loop per session:

```
while running:
    batch = sendQueue.take()         // parks (not blocks) if queue is empty
    for each msg in batch:
        session.getBasicRemote()
               .sendText(msg)        // parks (not blocks) if TCP buffer is full
```

...has the same OS-thread cost as the async callback approach, but is dramatically simpler code. No state machine, no callback coordination, no risk of violating the JSR 356 one-send-at-a-time constraint.

Virtual threads are cheap to create — the JVM manages them, not the OS. Having hundreds of virtual threads parked on `queue.take()` is entirely normal and expected.

### Thread creation

Each virtual thread is started in `@OnOpen` via `Thread.ofVirtual().name(...).start(sendLoop)`. It exits when the session closes (the blocking calls throw, or `running` is set to false and the thread is interrupted). No thread pool or executor is needed — virtual threads are not pooled; they are created on demand and garbage-collected when done.

---

## Per-Session Bounded Queue

### Why a queue at all

The `@Scheduled` broadcaster drain loop runs every 100ms and must return quickly to keep the drain interval accurate. If the broadcaster called `sendText` directly, a stalled TCP connection would block the broadcaster thread for however long the OS takes to time out — blocking delivery to every other session until the stall resolves. The queue decouples the broadcaster from client I/O entirely.

The broadcaster's only interaction with a session is `sendQueue.offer(batch)`, which is a non-blocking in-memory operation. The virtual thread handles all I/O asynchronously relative to the broadcaster.

### Queue type and capacity

`ArrayBlockingQueue<List<String>>` — each slot holds one drain cycle's worth of pre-serialized, seq-injected messages. The unit is **drain cycles, not individual messages**.

This distinction matters: in a volatile market a single drain cycle may produce 100+ ticker updates, all coalesced into one `List<String>`. An `ArrayBlockingQueue<String>` with capacity 32 would overflow immediately in that case. An `ArrayBlockingQueue<List<String>>` with capacity 32 gives 32 drain cycles — approximately 3.2 seconds of genuine backlog — regardless of how many tickers fired in any individual cycle.

Capacity 32 is a predictable, market-activity-independent eviction threshold. A client with a good connection will never accumulate more than 1–2 queued batches. A client that falls 32 cycles behind is not keeping up.

### Slow client eviction

When `sendQueue.offer(batch)` returns `false` (queue full), the broadcaster calls `session.disconnect()`. The broadcaster does **not** call `broadcaster.removeSession()` directly — that is the virtual thread's responsibility (see Session Lifecycle below).

Dropping individual messages would leave the client with a partially inconsistent view of the order book that it has no way to recover from (it cannot know a message was dropped). Disconnection forces the client to reconnect and receive a full SNAPSHOT on reconnect, which restores a consistent state. Disconnection is strict but fair: a client with a good connection will never hit the limit.

---

## Pipeline Isolation

The critical property is that the Disruptor consumer threads and the broadcaster's `@Scheduled` thread are completely insulated from client I/O at all times. The table below shows what each thread does and what it does not touch:

| Thread | Work | Never touches |
|--------|------|---------------|
| Disruptor consumer | Parse diff, update TreeMap, run classifier, call `feedStore.submit()` | Queue, socket, broadcaster |
| `@Scheduled` broadcaster | Drain pending map, build JSON bodies, call `session.enqueueBatch()` | TCP socket, blocking I/O |
| Session virtual thread | `queue.take()`, `session.sendText()`, session close on exit | OrderBook, classifier, feed store |

`session.enqueueBatch(batch)` is implemented as `sendQueue.offer(batch)` — a non-blocking CAS on the queue's internal array. It will never block the broadcaster thread regardless of how many sessions exist or how slow any of them are.

---

## Session Lifecycle

### @OnOpen
1. Create a `UserWebSocketSession` wrapping the raw `jakarta.websocket.Session`.
2. Store it in `session.getUserProperties()` for retrieval in subsequent callbacks.
3. Call `userSession.startSendLoop()` — starts the virtual thread, which immediately parks on `queue.take()`.
4. Call `broadcaster.addSession(userSession)`.
5. On the next broadcaster drain cycle (~100ms), the session's `NEED_SNAPSHOT` status triggers a full snapshot delivery.

### @OnClose / @OnError
1. Retrieve the `UserWebSocketSession` from `session.getUserProperties()`.
2. Call `userSession.disconnect()` — sets `running = false` and interrupts the virtual thread (idempotent).
3. Call `broadcaster.removeSession(userSession)` — removes from the `CopyOnWriteArrayList` (idempotent).

Tomcat calls `@OnError` followed by `@OnClose` on errors, so both must be idempotent. All operations (`disconnect()`, `removeSession()`) satisfy this.

### Eviction via broadcaster

When `enqueueBatch()` returns false, the broadcaster calls `session.disconnect()`. The virtual thread, interrupted or finding `running = false`, exits its send loop and calls `cleanup()`, which closes the Jakarta session. Tomcat then fires `@OnClose`, which calls `broadcaster.removeSession()`. The broadcaster never calls `removeSession()` directly on the eviction path — that responsibility belongs entirely to `@OnClose`.

### SNAPSHOT_REQUEST from client

When a connected client sends a `SNAPSHOT_REQUEST` message, the `@OnMessage` handler sets `session.setStatus(NEED_SNAPSHOT)`. This write is safe because `status` is `volatile` and the broadcaster reads it once per drain cycle. On the next cycle the client receives a fresh SNAPSHOT and is reset to READY. This is the only client-to-server message currently handled; future message types will be dispatched via a JSON `{"type":"..."}` switch.

---

## Integration with Existing Components

### OrderBookBroadcaster
The broadcaster already has `addSession()` and `removeSession()` hooks and the `CopyOnWriteArrayList` that makes concurrent add/remove safe. The `session.sendData(json)` call site is replaced by `session.enqueueBatch(List<String>)`. The broadcaster builds update bodies once (shared across sessions), then per-session injects seq numbers and offers the resulting `List<String>` as a single batch. If `enqueueBatch` returns false, the broadcaster calls `session.disconnect()`.

Dead sessions (`!session.isRunning()`) are skipped in the drain loop without calling `disconnect()` again — `@OnClose` is already in flight to remove them.

### UserWebSocketSession
The stub in `feed/` is deleted and replaced by a real implementation in `ws/`:
- Holds a `jakarta.websocket.Session` reference
- Holds an `ArrayBlockingQueue<List<String>>` with capacity 32
- `enqueueBatch(List<String>)`: non-blocking `offer()`; broadcaster calls `disconnect()` on false
- `startSendLoop()`: starts the virtual thread
- `disconnect()`: sets `running = false` and interrupts the virtual thread (idempotent)
- `cleanup()` (called from virtual thread's finally block): closes the Jakarta session, triggering `@OnClose`

### Spring Security
`spring-boot-starter-websocket` integrates with Spring Security's filter chain. The `@ServerEndpoint` handshake goes through the same security context as HTTP requests. JWT authentication (planned for a later phase) will be applied at handshake time via a `HandshakeInterceptor` — the WebSocket session is authenticated once at connection, not per-message.

### ServerEndpointExporter
Spring requires a `ServerEndpointExporter` bean to register `@ServerEndpoint` classes with the Tomcat container when running as an embedded server. This is a one-line `@Bean` in a `@Configuration` class.

### SpringConfigurator
`@ServerEndpoint` classes are instantiated by the WebSocket container (Tomcat), not by Spring. `SpringConfigurator` overrides `getEndpointInstance()` to delegate to the Spring application context, enabling `@Autowired` on the endpoint class. The endpoint is a `@Component` singleton — all connections share the one instance, which is safe because all per-session state lives in `UserWebSocketSession` (stored in `session.getUserProperties()`).

---

## What Phase 5 Does Not Cover

- **Authentication and authorization.** JWT verification at handshake time is designed but not part of Phase 5. The `SecurityConfig` placeholder already permits all requests; that remains true through Phase 5.
- **Per-user classification rules.** The classifier currently applies identical rules to all tickers for all users. Per-user thresholds are a future design problem.
- **Horizontal scaling.** A single JVM pushes to all connected clients. Partitioned deployment (multiple JVMs, shared message bus) is future work.
- **STOMP or SockJS.** These abstractions are not used. The protocol is a raw WebSocket with the JSON message shapes defined in `classification-and-feed-impl.md`.
