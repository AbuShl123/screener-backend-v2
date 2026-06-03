package dev.abu.screener_backend.ws;

import dev.abu.screener_backend.analysis.UserClassificationContext;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class UserWebSocketSession {

    public enum Status { NEED_SNAPSHOT, READY }

    private static final int QUEUE_CAPACITY = 32;

    private final Session jakartaSession;
    @Getter
    private final UUID userId;

    /**
     * This session's per-user classification context, or {@code null} for a default-only user
     * (no custom rules). The broadcaster reads it to merge the personal feed with the filtered
     * global feed. Shared across all of this user's sessions (refcounted in {@code UserFeedRegistry}).
     */
    @Getter
    private final UserClassificationContext context;

    // One-shot guard so the registry refcount is decremented exactly once per session, even though
    // Tomcat may fire BOTH @OnClose and @OnError. (disconnect()/removeSession() are idempotent on
    // their own, but a refcount decrement is not.)
    private final AtomicBoolean released = new AtomicBoolean(false);

    private final ArrayBlockingQueue<List<String>> sendQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    @Setter @Getter
    private volatile Status status = Status.NEED_SNAPSHOT;

    @Getter
    private volatile boolean running = true;

    private volatile Thread virtualThread;

    // Accessed only by the broadcaster's @Scheduled thread — no atomics needed.
    // resetSeq() and getAndIncrementSeq() are both called exclusively from the broadcaster.
    // setStatus() does NOT touch this field — it can be called from the @OnMessage Tomcat thread.
    private int seqNumber = 0;

    public UserWebSocketSession(Session jakartaSession, UUID userId, UserClassificationContext context) {
        this.jakartaSession = jakartaSession;
        this.userId = userId;
        this.context = context;
    }

    /**
     * Atomically marks this session's registry refcount as released. Returns {@code true} the
     * first time only, so the caller decrements the user's refcount exactly once.
     */
    public boolean markReleased() {
        return released.compareAndSet(false, true);
    }

    // ---- Called by broadcaster (@Scheduled thread) ----

    /** Called only by the broadcaster's @Scheduled thread, right before snapshot delivery. */
    public void resetSeq() { seqNumber = 0; }

    /** Increments before returning, matching existing broadcaster convention. */
    public int getAndIncrementSeq() { return ++seqNumber; }

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
                List<String> batch = sendQueue.take();
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
            }
        } catch (IOException ignored) {}
    }
}
