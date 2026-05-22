# Phase 5 — Outbound WebSocket Server: Architecture Design

## What This Document Covers

Phase 5 is the component that pushes classified order book events to connected browser or API clients. Everything upstream (Disruptor pipeline, order book sync, classifier, feed store, broadcaster drain loop) is already built. Phase 5 adds the server-side WebSocket endpoint and the per-session delivery machinery.

This document records the architectural decisions and their reasoning. The companion implementation guide should be written before coding begins.

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
while session is open:
    json = sendQueue.take()         // parks (not blocks) if queue is empty
    session.getBasicRemote()
           .sendText(json)          // parks (not blocks) if TCP buffer is full
```

...has the same OS-thread cost as the async callback approach, but is dramatically simpler code. No state machine, no callback coordination, no risk of violating the JSR 356 one-send-at-a-time constraint.

Virtual threads are cheap to create — the JVM manages them, not the OS. Having hundreds of virtual threads parked on `queue.take()` is entirely normal and expected.

### Thread creation

Each virtual thread is started in `@OnOpen` via `Thread.ofVirtual().start(sendLoop)`. It exits when the session closes (the blocking calls throw, or `running` is set to false and the thread is interrupted). No thread pool or executor is needed — virtual threads are not pooled; they are created on demand and garbage-collected when done.

---

## Per-Session Bounded Queue

### Why a queue at all

The `@Scheduled` broadcaster drain loop runs every 100ms and must return quickly to keep the drain interval accurate. If the broadcaster called `sendText` directly, a stalled TCP connection would block the broadcaster thread for however long the OS takes to time out — blocking delivery to every other session until the stall resolves. The queue decouples the broadcaster from client I/O entirely.

The broadcaster's only interaction with a session is `sendQueue.offer(json)`, which is a non-blocking in-memory operation. The virtual thread handles all I/O asynchronously relative to the broadcaster.

### Queue type and capacity

`ArrayBlockingQueue<String>` — bounded, array-backed (no node allocation per element), thread-safe, supports blocking `take()` on the consumer side and non-blocking `offer()` on the producer side. Exactly the right fit.

Capacity: 32–64 messages. Reasoning: the drain interval is 100ms. A typical burst might produce 20 updates per cycle. A capacity of 32 gives a minimum of ~1.5 seconds of headroom before a client is considered unresponsive; 64 gives ~3 seconds. A client with genuinely good connectivity will never accumulate more than a few entries. A client that fills the queue in under 1.5 seconds is not keeping up and should be disconnected.

### Slow client eviction

When `sendQueue.offer(json)` returns `false` (queue full), the broadcaster calls `session.disconnect()` and `broadcaster.removeSession(session)`. This is the correct response — not dropping individual messages.

Dropping individual messages would leave the client with a partially inconsistent view of the order book that it has no way to recover from (it cannot know a message was dropped). Disconnection forces the client to reconnect and receive a full SNAPSHOT on reconnect, which restores a consistent state. Disconnection is strict but fair: a client with a good connection will never hit the limit.

The alternative — dropping messages silently — would produce incorrect screener data on the client side with no error signal. This is worse than a disconnect.

---

## Pipeline Isolation

The critical property is that the Disruptor consumer threads and the broadcaster's `@Scheduled` thread are completely insulated from client I/O at all times. The table below shows what each thread does and what it does not touch:

| Thread | Work | Never touches |
|--------|------|---------------|
| Disruptor consumer | Parse diff, update TreeMap, run classifier, call `feedStore.submit()` | Queue, socket, broadcaster |
| `@Scheduled` broadcaster | Drain pending map, build JSON bodies, call `session.enqueue()` | TCP socket, blocking I/O |
| Session virtual thread | `queue.take()`, `session.sendText()` | OrderBook, classifier, feed store |

`session.enqueue()` (the broadcaster's side) is implemented as `sendQueue.offer(json)` — a non-blocking CAS on the queue's internal array. It will never block the broadcaster thread regardless of how many sessions exist or how slow any of them are.

---

## Session Lifecycle

### @OnOpen
1. Wrap the raw `jakarta.websocket.Session` in a new `UserWebSocketSession`.
2. Start the virtual thread send loop for this session.
3. Call `broadcaster.addSession(session)`.
4. On the next broadcaster drain cycle (~100ms), the session's `NEED_SNAPSHOT` status will trigger a full snapshot delivery.

### @OnClose / @OnError
1. Signal the send loop to exit (set `running = false`, interrupt the virtual thread).
2. Call `broadcaster.removeSession(session)`.
3. The `CopyOnWriteArrayList` in the broadcaster ensures this is safe from the WebSocket callback thread.

### SNAPSHOT_REQUEST from client (future)
When a connected client sends a `SNAPSHOT_REQUEST` message (e.g., after a processing gap), the `@OnMessage` handler sets `session.setStatus(NEED_SNAPSHOT)`. This write is safe because `status` is `volatile` (established in the `UserWebSocketSession` stub) and the broadcaster reads it once per drain cycle. On the next cycle the client receives a fresh SNAPSHOT and is reset to READY.

---

## Integration with Existing Components

### OrderBookBroadcaster
The broadcaster already has `addSession()` and `removeSession()` hooks and the `CopyOnWriteArrayList` that makes concurrent add/remove safe. The existing `session.sendData(json)` call site becomes `session.enqueue(json)` (or the same method renamed — the broadcaster does not care whether the send is synchronous or queued). No other change to the broadcaster is needed.

### UserWebSocketSession
The stub needs to be replaced with a real implementation:
- Holds a `jakarta.websocket.Session` reference
- Holds an `ArrayBlockingQueue<String>`
- `sendData(json)` becomes `enqueue(json)`: calls `offer()`, disconnects on false
- `startSendLoop()`: starts the virtual thread
- `disconnect()`: closes the Jakarta session and interrupts the virtual thread

### Spring Security
`spring-boot-starter-websocket` integrates with Spring Security's filter chain. The `@ServerEndpoint` handshake goes through the same security context as HTTP requests. JWT authentication (planned for a later phase) will be applied at handshake time via a `HandshakeInterceptor` — the WebSocket session is authenticated once at connection, not per-message.

### ServerEndpointExporter
Spring requires a `ServerEndpointExporter` bean to register `@ServerEndpoint` classes with the Tomcat container when running as an embedded server. This is a one-line `@Bean` in a `@Configuration` class.

---

## What Phase 5 Does Not Cover

- **Authentication and authorization.** JWT verification at handshake time is designed but not part of Phase 5. The `SecurityConfig` placeholder already permits all requests; that remains true through Phase 5.
- **Per-user classification rules.** The classifier currently applies identical rules to all tickers for all users. Per-user thresholds are a future design problem.
- **Horizontal scaling.** A single JVM pushes to all connected clients. Partitioned deployment (multiple JVMs, shared message bus) is future work.
- **STOMP or SockJS.** These abstractions are not used. The protocol is a raw WebSocket with the JSON message shapes defined in `classification-and-feed-impl.md`.
