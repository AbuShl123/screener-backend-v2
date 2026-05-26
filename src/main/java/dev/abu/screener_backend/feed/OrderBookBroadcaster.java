package dev.abu.screener_backend.feed;

import dev.abu.screener_backend.ws.UserWebSocketSession;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs the 100ms drain loop and handles new-client snapshot delivery.
 * All broadcaster logic runs on a single @Scheduled thread — no concurrency inside this class.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBookBroadcaster {

    private final OrderBookFeedStore feedStore;

    // CopyOnWriteArrayList so Phase-5 connect/disconnect (from another thread) needs no extra locking
    private final List<UserWebSocketSession> sessions = new CopyOnWriteArrayList<>();

    // Reused across every drain() call — safe because drain() runs on a single @Scheduled thread.
    // Each build method resets it at entry and captures the result via toString() before returning,
    // so injectSeq() can reuse the same buffer immediately after without risk of corruption.
    private final StringBuilder sb = new StringBuilder(4096);

    @Scheduled(fixedDelay = 100)
    public void drain() {
        if (sessions.isEmpty()) return;

        // Drain eagerly even if all sessions are NEED_SNAPSHOT: skipping would let DROP events
        // accumulate across cycles and reach READY sessions that already hold a current snapshot.
        Map<String, OrderBookUpdate> pending = feedStore.drainPending();

        String snapshotBody = null;    // built once, on the first NEED_SNAPSHOT session
        List<String> updateBodies = null; // built once, on the first READY session

        for (UserWebSocketSession session : sessions) {
            if (!session.isRunning()) continue; // shutting down — @OnClose will remove it

            if (session.getStatus() == UserWebSocketSession.Status.NEED_SNAPSHOT) {
                if (snapshotBody == null) {
                    snapshotBody = buildSnapshotBody(feedStore.getSnapshot());
                }
                session.resetSeq(); // broadcaster thread resets seq — keeps seqNumber single-threaded
                String seqMsg = injectSeq(snapshotBody, session.getAndIncrementSeq());
                if (!session.enqueueBatch(List.of(seqMsg))) {
                    session.disconnect();
                } else {
                    session.setStatus(UserWebSocketSession.Status.READY);
                    // Do NOT also send pending updates — they're already reflected in the snapshot
                }
            } else if (!pending.isEmpty()) {
                if (updateBodies == null) {
                    updateBodies = buildAllUpdateBodies(pending);
                }
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

    @PreDestroy
    public void shutdown() {
        for (UserWebSocketSession session : sessions) {
            session.disconnect();
        }
    }

    /** Called from the Phase-5 WebSocket server when a client connects. */
    public void addSession(UserWebSocketSession session) {
        sessions.add(session);
    }

    /** Called from the Phase-5 WebSocket server when a client disconnects. */
    public void removeSession(UserWebSocketSession session) {
        sessions.remove(session);
    }

    // ---- JSON building — all methods write into the shared sb field ----

    private List<String> buildAllUpdateBodies(Map<String, OrderBookUpdate> pending) {
        List<String> bodies = new ArrayList<>(pending.size());
        for (OrderBookUpdate update : pending.values()) {
            bodies.add(buildUpdateBody(update));
        }
        return bodies;
    }

    private String buildUpdateBody(OrderBookUpdate update) {
        sb.setLength(0);
        sb.append("{\"type\":\"").append(update.type().name()).append('"');
        sb.append(",\"symbol\":\"").append(update.symbol()).append('"');
        sb.append(",\"market\":\"").append(update.market().name()).append('"');
        if (update.type() != FeedEventType.DROP) {
            sb.append(",\"bids\":");
            appendLevels(update.bids());
            sb.append(",\"asks\":");
            appendLevels(update.asks());
        }
        sb.append('}');
        return sb.toString();
    }

    private String buildSnapshotBody(Map<String, OrderBookUpdate> snapshot) {
        sb.setLength(0);
        sb.append("{\"type\":\"SNAPSHOT\",\"data\":[");
        boolean first = true;
        for (OrderBookUpdate update : snapshot.values()) {
            if (!first) sb.append(',');
            appendTickerData(update);
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private void appendTickerData(OrderBookUpdate update) {
        sb.append("{\"symbol\":\"").append(update.symbol()).append('"');
        sb.append(",\"market\":\"").append(update.market().name()).append('"');
        sb.append(",\"bids\":");
        appendLevels(update.bids());
        sb.append(",\"asks\":");
        appendLevels(update.asks());
        sb.append('}');
    }

    private void appendLevels(ClassifiedLevel[] levels) {
        sb.append('[');
        boolean first = true;
        for (ClassifiedLevel level : levels) {
            if (level == null) break;
            if (!first) sb.append(',');
            sb.append("{\"price\":").append(level.price());
            sb.append(",\"quantity\":").append(level.quantity());
            sb.append(",\"tier\":").append(level.tier());
            sb.append(",\"firstSeenMillis\":").append(level.firstSeenMillis());
            sb.append(",\"distance\":").append(level.distance());
            sb.append('}');
            first = false;
        }
        sb.append(']');
    }

    // Injects seq as the first field: {"type":"...",...} → {"seq":N,"type":"...",...}
    // Uses the shared sb; safe because the body String was already captured before this call.
    private String injectSeq(String body, int seq) {
        sb.setLength(0);
        sb.append("{\"seq\":").append(seq).append(',').append(body, 1, body.length());
        return sb.toString();
    }
}
