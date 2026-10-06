package dev.abu.screener_backend.feed;

import dev.abu.screener_backend.ws.UserWebSocketSession;
import dev.abu.screener_backend.ws.UserWebSocketSession.Status;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs the 100ms drain loop and handles new-client snapshot delivery.
 * All broadcaster logic runs on a single @Scheduled thread — no concurrency inside this class.
 *
 * <p>What is sent comes from the {@link FeedChannel} beans, in {@code @Order}; this class only
 * owns the per-session loop. Each tick:
 * <ul>
 *   <li>every channel drains once, before any session is served — even with no sessions;</li>
 *   <li>a {@code NEED_SNAPSHOT} session gets one {@code SNAPSHOT} holding every channel's entries,
 *       and nothing else that tick (the snapshot covers the tick's updates);</li>
 *   <li>a {@code READY} session gets one batch holding every channel's updates, in channel order.
 *       One batch per tick, not one per channel: each batch takes one of the session's 32 queue
 *       slots, and the ~3.2s eviction budget is counted in ticks.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedBroadcaster {

    private final List<FeedChannel> channels;

    // CopyOnWriteArrayList so connect/disconnect (from another thread) needs no extra locking
    private final List<UserWebSocketSession> sessions = new CopyOnWriteArrayList<>();

    /** A drain slower than this has eaten most of its 100ms budget and is worth a line of its own. */
    private static final long SLOW_DRAIN_NANOS = 50_000_000L;

    // Written only by the @Scheduled drain thread, read by the health logger's thread. Volatile
    // rather than plain because these are sampled ~10x/second, not per depth message, so the fence
    // costs nothing worth counting.
    private volatile long maxDrainNanos;
    private volatile long slowDrains;

    /**
     * Times one drain and reports the outliers.
     *
     * <p>The loop has a 100ms budget and does per-session JSON building inside it. If it overruns,
     * delivery — not ingest — is the bottleneck, and nothing else in the pipeline would distinguish
     * those two: both look like a client seeing stale levels.
     */
    @Scheduled(fixedDelay = 100)
    public void drain() {
        long startNanos = System.nanoTime();
        try {
            drainOnce();
        } finally {
            long elapsed = System.nanoTime() - startNanos;
            if (elapsed > maxDrainNanos) maxDrainNanos = elapsed;
            if (elapsed > SLOW_DRAIN_NANOS) {
                slowDrains++;
                log.warn("drain took {}ms for {} session(s) — over half the 100ms budget",
                        elapsed / 1_000_000, sessions.size());
            }
        }
    }

    /**
     * Longest drain since the last call, in milliseconds, and the number of drains that blew the
     * threshold. Reading resets both, so exactly one reader (the health logger) may call it.
     */
    public long[] takeDrainStats() {
        long[] out = { maxDrainNanos / 1_000_000, slowDrains };
        maxDrainNanos = 0;
        slowDrains = 0;
        return out;
    }

    private void drainOnce() {
        // Every channel drains before any session is served, and drains with no sessions too:
        // skipping would let pending state pile up (DROPs reaching sessions that already hold a
        // current snapshot, a bounded event queue overflowing).
        for (FeedChannel ch : channels) ch.drain();
        if (sessions.isEmpty()) return;

        for (UserWebSocketSession session : sessions) {
            if (!session.isRunning()) continue; // shutting down — @OnClose will remove it

            // A batch that cannot be enqueued evicts the session — it is never skipped.
            // Gap-free delivery depends on that.
            List<String> batch = new ArrayList<>();
            if (session.getStatus() == Status.NEED_SNAPSHOT) {
                List<String> entries = new ArrayList<>();
                for (FeedChannel ch : channels) ch.collectSnapshot(session, entries);
                batch.add(Envelope.snapshot(entries));
                if (!session.enqueueBatch(batch)) {
                    session.disconnect();
                    continue;
                }
                session.setStatus(Status.READY); // the snapshot covers this tick's updates
            } else {
                for (FeedChannel ch : channels) ch.collectUpdates(session, batch);
                if (!batch.isEmpty() && !session.enqueueBatch(batch)) session.disconnect();
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        for (UserWebSocketSession session : sessions) {
            session.disconnect();
        }
    }

    /** Called from the WebSocket server when a client connects. */
    public void addSession(UserWebSocketSession session) {
        sessions.add(session);
    }

    /** Called from the WebSocket server when a client disconnects. */
    public void removeSession(UserWebSocketSession session) {
        sessions.remove(session);
    }
}
