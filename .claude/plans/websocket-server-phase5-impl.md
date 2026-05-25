# Phase 5 Implementation Guide — Outbound WebSocket Server

This document is a step-by-step coding guide. Read `.claude/plans/websocket-server-phase5.md` first for architectural rationale. This document contains only what to build, where to put it, and what caveats to watch for.

---

## Decisions Locked In

| Decision | Choice |
|---|---|
| Endpoint URL | `/ws` → `ws://localhost:8080/ws` |
| Client-to-server messages | `SNAPSHOT_REQUEST` (raw string) only; future messages parsed via a JSON dispatch switch |
| Queue unit | Per-drain-cycle batch (`List<String>`) — one queue slot per 100ms cycle, not per message |
| Queue capacity | 32 drain cycles (~3.2 s of backlog before eviction) |
| Eviction owner | Virtual thread closes the Jakarta session; broadcaster only signals |
| Session removal owner | `@OnClose` callback (Tomcat triggers it whether close is initiated by client or server) |
| Spring DI in endpoint | `SpringConfigurator` — endpoint is a singleton `@Component`, all per-session state lives in `Session.getUserProperties()` |

---

## New Package: `ws/`

```
src/main/java/dev/abu/screener_backend/
├── ws/
│   ├── WebSocketConfig.java             ← registers @ServerEndpoint classes with Tomcat
│   ├── ScreenerWebSocketEndpoint.java   ← @ServerEndpoint("/ws"), thin lifecycle handler
│   └── UserWebSocketSession.java        ← replaces the stub in feed/
```

The existing stub `feed/UserWebSocketSession.java` is **deleted**. All references to it (`OrderBookBroadcaster`) update their import to `ws.UserWebSocketSession`.

---

## Step 1 — Add `spring-boot-starter-websocket` to `pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

This activates JSR 356 on the already-running Tomcat and pulls in `spring-websocket` (which contains `SpringConfigurator`). It also transitively pulls in `spring-messaging` (the STOMP layer) — ignore it entirely; we do not use STOMP.

---

## Step 2 — `WebSocketConfig.java`

```java
package dev.abu.screener_backend.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

@Configuration
public class WebSocketConfig {

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}
```

`ServerEndpointExporter` scans the Spring context for `@ServerEndpoint`-annotated classes and registers them with the embedded Tomcat WebSocket container. Without this bean, the endpoint class is compiled and discovered but Tomcat never activates it.

---

## Step 3 — `UserWebSocketSession.java`

Replaces the stub. This is the core session object: it holds the send queue, the virtual thread, and the per-session state the broadcaster needs.

### Key design notes before the code

**Queue type**: `ArrayBlockingQueue<List<String>>` — each slot is one drain cycle's worth of pre-serialized, seq-injected messages. Capacity 32 means 32 drain cycles (~3.2 s) of headroom. A client that is genuinely online will never accumulate more than 1–2 queued batches.

**Seq**: `seqNumber` is accessed only by the broadcaster's `@Scheduled` thread. No need for `volatile` or `AtomicInteger`. Both `resetSeq()` and `getAndIncrementSeq()` are called exclusively from the broadcaster thread — `resetSeq()` is called right before snapshot delivery in `drain()`. Do NOT reset `seqNumber` inside `setStatus()`: that method is called from the `@OnMessage` Tomcat thread, which is a different thread from the broadcaster.

**`running` flag**: `volatile` because the broadcaster thread writes it (via `disconnect()`) and the virtual thread reads it in the send loop.

**Send loop exit contract**: The loop exits on `InterruptedException` (interrupted by `disconnect()`) or `IOException` (Jakarta session closed mid-send). Either way it falls through to `cleanup()`. `cleanup()` is idempotent — safe to run after a double-interrupt.

**`cleanup()` responsibility**: Set `running = false`, then close the Jakarta session if still open. Tomcat will then invoke `@OnClose`, which calls `broadcaster.removeSession(this)`. `cleanup()` itself never touches the broadcaster — that responsibility belongs to the endpoint's `@OnClose` handler.

```java
package dev.abu.screener_backend.ws;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

@Slf4j
public class UserWebSocketSession {

    public enum Status { NEED_SNAPSHOT, READY }

    private static final int QUEUE_CAPACITY = 32;

    private final Session jakartaSession;
    private final ArrayBlockingQueue<List<String>> sendQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private volatile Status status = Status.NEED_SNAPSHOT;
    private volatile boolean running = true;
    private volatile Thread virtualThread;

    // Accessed only by the broadcaster's @Scheduled thread — no atomics needed.
    private int seqNumber = 0;

    public UserWebSocketSession(Session jakartaSession) {
        this.jakartaSession = jakartaSession;
    }

    // ---- Called by broadcaster (@Scheduled thread) ----

    public Status getStatus() { return status; }

    public void setStatus(Status status) {
        this.status = status;
    }

    /** Called only by the broadcaster's @Scheduled thread, right before snapshot delivery. */
    public void resetSeq() { seqNumber = 0; }

    /** Increments before returning, matching existing broadcaster convention. */
    public int getAndIncrementSeq() { return ++seqNumber; }

    public boolean isRunning() { return running; }

    /**
     * Offers a pre-serialized, seq-injected batch to the send queue.
     * Non-blocking — returns false if the queue is full or the session is shutting down.
     * The broadcaster must call disconnect() when this returns false (queue-full eviction).
     */
    public boolean enqueueBatch(List<String> batch) {
        if (!running) return false;
        return sendQueue.offer(batch);
    }

    // ---- Lifecycle ----

    /** Called once from @OnOpen. Starts the virtual thread send loop. */
    public void startSendLoop() {
        virtualThread = Thread.ofVirtual()
                .name("ws-send-" + jakartaSession.getId())
                .start(this::sendLoop);
    }

    /**
     * Signals the send loop to stop. Idempotent — safe to call multiple times.
     * The virtual thread will call cleanup() which closes the Jakarta session,
     * causing Tomcat to invoke @OnClose, which calls broadcaster.removeSession().
     */
    public void disconnect() {
        running = false;
        Thread vt = virtualThread;
        if (vt != null) vt.interrupt();
    }

    // ---- Send loop (virtual thread) ----

    private void sendLoop() {
        try {
            while (running) {
                List<String> batch = sendQueue.take(); // parks (virtual) until a batch arrives
                for (String msg : batch) {
                    jakartaSession.getBasicRemote().sendText(msg);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.debug("Send error on session {}: {}", jakartaSession.getId(), e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        running = false;
        try {
            if (jakartaSession.isOpen()) {
                jakartaSession.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, ""));
                // Tomcat will call @OnClose → broadcaster.removeSession(this)
            }
        } catch (IOException ignored) {}
    }
}
```

---

## Step 4 — `ScreenerWebSocketEndpoint.java`

This class is a **singleton Spring bean** (via `@Component`). All connections share the one instance. Per-session state is stored in `session.getUserProperties()` — the standard JSR 356 mechanism for shared-instance endpoints.

Tomcat calls all handler methods (`@OnOpen`, `@OnClose`, etc.) with the specific `Session` as a parameter, so the shared instance correctly routes each event to the right session object.

`SpringConfigurator` is the bridge: when Tomcat asks for an endpoint instance, `SpringConfigurator.getEndpointInstance()` calls `applicationContext.getBean(ScreenerWebSocketEndpoint.class)`, returning the singleton. Without it, `@Autowired OrderBookBroadcaster` would be null.

### Lifecycle responsibilities

| Callback | Responsibility |
|---|---|
| `@OnOpen` | Create `UserWebSocketSession`, store in `getUserProperties`, start send loop, register with broadcaster |
| `@OnClose` | Retrieve session, call `disconnect()` (idempotent), call `broadcaster.removeSession()` |
| `@OnError` | Same as `@OnClose` — Tomcat calls both on error, so both must be idempotent |
| `@OnMessage` | Retrieve session, dispatch message type; currently only handles `SNAPSHOT_REQUEST` |

### `@OnClose` / `@OnError` idempotency chain

Two paths lead to `@OnClose`: (a) the client disconnects normally, (b) the virtual thread calls `cleanup()` → `jakartaSession.close()`. In both cases Tomcat fires `@OnClose`. The operations in `@OnClose` are:
- `userSession.disconnect()` — idempotent (`running = false` + interrupt, safe on already-interrupted thread)
- `broadcaster.removeSession(userSession)` — `CopyOnWriteArrayList.remove()` on a non-present element returns `false` and is safe

```java
package dev.abu.screener_backend.ws;

import dev.abu.screener_backend.feed.OrderBookBroadcaster;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.server.standard.SpringConfigurator;

@Slf4j
@Component
@ServerEndpoint(value = "/ws", configurator = SpringConfigurator.class)
public class ScreenerWebSocketEndpoint {

    // Injected once (singleton). All @OnXxx handlers share this reference safely.
    @Autowired
    private OrderBookBroadcaster broadcaster;

    @OnOpen
    public void onOpen(Session session) {
        UserWebSocketSession userSession = new UserWebSocketSession(session);
        session.getUserProperties().put("session", userSession);
        userSession.startSendLoop();
        broadcaster.addSession(userSession);
        log.debug("WebSocket opened: {}", session.getId());
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        UserWebSocketSession userSession = getSession(session);
        if (userSession == null) return;
        userSession.disconnect();
        broadcaster.removeSession(userSession);
        log.debug("WebSocket closed: {} reason={}", session.getId(), reason.getCloseCode());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.warn("WebSocket error on session {}: {}", session.getId(), error.getMessage());
        UserWebSocketSession userSession = getSession(session);
        if (userSession == null) return;
        userSession.disconnect();
        broadcaster.removeSession(userSession);
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        UserWebSocketSession userSession = getSession(session);
        if (userSession == null) return;

        String trimmed = message.trim();
        if ("SNAPSHOT_REQUEST".equals(trimmed)) {
            userSession.setStatus(UserWebSocketSession.Status.NEED_SNAPSHOT);
            log.debug("SNAPSHOT_REQUEST from session {}", session.getId());
            return;
        }
        // Future: parse JSON {"type":"..."} and dispatch
        log.debug("Unknown message from session {}: {}", session.getId(), trimmed);
    }

    private UserWebSocketSession getSession(Session session) {
        return (UserWebSocketSession) session.getUserProperties().get("session");
    }
}
```

---

## Step 5 — Update `OrderBookBroadcaster`

Three changes:

1. Import updated to `ws.UserWebSocketSession` (feed/ stub is deleted).
2. `drain()` builds a per-session `List<String>` batch and calls `enqueueBatch()` instead of `sendData()`. Eviction (false return) calls `session.disconnect()` — the virtual thread handles the rest.
3. Dead sessions (`!session.isRunning()`) are skipped silently — Tomcat's `@OnClose` is already in flight or has already fired.

The shared `StringBuilder sb` is still used for JSON building; `injectSeq` captures the result as a `String` before the next sb operation, so reuse is safe on the single `@Scheduled` thread.

### Updated `drain()` method

```java
@Scheduled(fixedDelay = 100)
public void drain() {
    if (sessions.isEmpty()) return;

    Map<String, OrderBookUpdate> pending = feedStore.drainPending();

    String snapshotBody = null;
    List<String> updateBodies = null;

    for (UserWebSocketSession session : sessions) {
        if (!session.isRunning()) continue; // shutting down — @OnClose will remove it

        if (session.getStatus() == UserWebSocketSession.Status.NEED_SNAPSHOT) {
            if (snapshotBody == null) {
                snapshotBody = buildSnapshotBody(feedStore.getSnapshot());
            }
            session.resetSeq(); // broadcaster thread resets seq — keeps seqNumber single-threaded
            String seqMsg = injectSeq(snapshotBody, session.getAndIncrementSeq());
            List<String> batch = List.of(seqMsg);
            if (!session.enqueueBatch(batch)) {
                session.disconnect(); // queue full — virtual thread will close and @OnClose will remove
            } else {
                session.setStatus(UserWebSocketSession.Status.READY);
            }

        } else if (!pending.isEmpty()) {
            if (updateBodies == null) {
                updateBodies = buildAllUpdateBodies(pending);
            }
            // Build per-session batch: bodies are shared, seq is per-session
            List<String> batch = new ArrayList<>(updateBodies.size());
            for (String body : updateBodies) {
                batch.add(injectSeq(body, session.getAndIncrementSeq()));
            }
            if (!session.enqueueBatch(batch)) {
                session.disconnect();
            }
        }
    }
}
```

Also add a `@PreDestroy` to the broadcaster so Spring shutdown cleanly disconnects all sessions:

```java
@PreDestroy
public void shutdown() {
    for (UserWebSocketSession session : sessions) {
        session.disconnect();
    }
}
```

---

## Step 6 — Delete `feed/UserWebSocketSession.java`

This stub is replaced entirely by `ws/UserWebSocketSession.java`. After the new file is created, delete the stub. The only file that imported it was `OrderBookBroadcaster` — update that import.

`OrderBookClassifier` and `DepthEventHandler` do not reference `UserWebSocketSession`, so no other changes are needed outside the broadcaster.

---

## Step 7 — Update `CURRENT_STATE.md`

After implementation:
- Add the `ws/` package and its three files to the project layout
- Update the `UserWebSocketSession` entry: move description from `feed/` to `ws/`, replace stub description with real description
- Add `WebSocketConfig` and `ScreenerWebSocketEndpoint` entries
- Mark "User WebSocket server" as complete in the status table

---

## Implementation Order

Steps are sequential — each depends on the previous compiling cleanly.

| Step | Action |
|---|---|
| 1 | Add `spring-boot-starter-websocket` to `pom.xml` and run `mvn dependency:resolve` to verify |
| 2 | Create `ws/WebSocketConfig.java` |
| 3 | Create `ws/UserWebSocketSession.java` |
| 4 | Create `ws/ScreenerWebSocketEndpoint.java` |
| 5 | Update `feed/OrderBookBroadcaster.java`: change import, update `drain()`, add `@PreDestroy shutdown()` |
| 6 | Delete `feed/UserWebSocketSession.java` — verify no remaining references (`mvn compile`) |
| 7 | Update `CURRENT_STATE.md` |

---

## Caveats and Non-Obvious Details

**`@ServerEndpoint` + `@Component` = singleton endpoint**: All connections share one instance. This works because the instance has no mutable per-connection fields. If any per-connection field is ever added to `ScreenerWebSocketEndpoint`, it would be a bug — always push per-session state into `UserWebSocketSession` or `session.getUserProperties()`.

**`SpringConfigurator` source**: It lives in `org.springframework.web.socket.server.standard.SpringConfigurator`, part of `spring-websocket`, which is brought in transitively by `spring-boot-starter-websocket`.

**`@OnError` + `@OnClose` both fire on error**: Tomcat calls `@OnError` first, then `@OnClose`. Both handlers call `disconnect()` and `removeSession()` — both operations are idempotent so the double-call is harmless.

**`Session.close()` from the virtual thread is safe**: Calling `jakartaSession.close()` from the virtual thread sends the WebSocket Close frame, which causes Tomcat to fire `@OnClose` on the endpoint. This is expected and intentional — it's the mechanism by which eviction triggers cleanup.

**`enqueueBatch` returns false for two distinct reasons**: queue full (eviction case) and `!running` (already shutting down). The broadcaster handles both with `session.disconnect()`, which is idempotent. There is no need to distinguish these cases.

**Allocation per drain cycle**: With N READY sessions each receiving M updates per cycle, the broadcaster allocates N `ArrayList<String>` instances per drain. This is on the `@Scheduled` thread, not the Disruptor consumer thread, so GC pressure here is acceptable. Leave a TODO comment for Phase 6 optimization (pre-serialize bodies once, inject seq during send).

**Graceful shutdown ordering**: Spring will call `@PreDestroy` before stopping the `@Scheduled` executor. All virtual threads are signalled in `shutdown()`, but they may not finish before Spring fully exits. This is acceptable — the JVM shutdown will terminate them. If strict drain-before-shutdown is needed, it is a future requirement.

**`SNAPSHOT_REQUEST` as raw string**: The current `@OnMessage` does `"SNAPSHOT_REQUEST".equals(message.trim())`. If future client messages are JSON, replace this with a JSON dispatch: parse `{"type": "..."}` and switch on the type field. Leave a comment noting this.

---

## What This Does NOT Implement

- Authentication/authorization (JWT at handshake time — future Phase)
- Per-user classification rules
- Horizontal scaling / message bus
- STOMP or SockJS
- Client-side message types beyond `SNAPSHOT_REQUEST`
