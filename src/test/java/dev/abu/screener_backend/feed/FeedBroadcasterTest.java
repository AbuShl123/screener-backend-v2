package dev.abu.screener_backend.feed;

import dev.abu.screener_backend.ws.UserWebSocketSession;
import dev.abu.screener_backend.ws.UserWebSocketSession.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-session loop of {@link FeedBroadcaster}, against two fake channels that record their
 * calls and return fixed strings.
 */
class FeedBroadcasterTest {

    /** Every channel call, in order, across both channels. */
    private final List<String> calls = new ArrayList<>();

    private class FakeChannel implements FeedChannel {
        private final String name;

        FakeChannel(String name) {
            this.name = name;
        }

        @Override
        public void drain() {
            calls.add(name + ".drain");
        }

        @Override
        public void collectUpdates(UserWebSocketSession session, List<String> out) {
            calls.add(name + ".updates");
            out.add(name + "-update");
        }

        @Override
        public void collectSnapshot(UserWebSocketSession session, List<String> out) {
            calls.add(name + ".snapshot");
            out.add("\"" + name + "-entry\"");
        }
    }

    /** Captures enqueued batches; refuses them all when {@code accept} is false. */
    static class CapturingSession extends UserWebSocketSession {
        final List<List<String>> batches = new ArrayList<>();
        final boolean accept;

        CapturingSession(boolean accept) {
            super(null, UUID.randomUUID());
            this.accept = accept;
        }

        @Override
        public boolean enqueueBatch(List<String> batch) {
            if (!accept) return false;
            batches.add(List.copyOf(batch));
            return true;
        }
    }

    private FeedBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new FeedBroadcaster(List.of(new FakeChannel("a"), new FakeChannel("b")));
    }

    @Test
    @DisplayName("every channel drains before any collect, and drains with no sessions")
    void drainsFirstAndWithoutSessions() {
        broadcaster.drain();
        assertEquals(List.of("a.drain", "b.drain"), calls);

        calls.clear();
        CapturingSession ready = new CapturingSession(true);
        ready.setStatus(Status.READY);
        broadcaster.addSession(ready);
        broadcaster.addSession(new CapturingSession(true)); // NEED_SNAPSHOT
        broadcaster.drain();

        assertEquals(List.of("a.drain", "b.drain"), calls.subList(0, 2));
        assertFalse(calls.subList(2, calls.size()).stream().anyMatch(c -> c.endsWith(".drain")));
    }

    @Test
    @DisplayName("a READY session gets one batch with every channel's updates, in channel order")
    void readySessionGetsOneBatch() {
        CapturingSession session = new CapturingSession(true);
        session.setStatus(Status.READY);
        broadcaster.addSession(session);

        broadcaster.drain();

        assertEquals(List.of(List.of("a-update", "b-update")), session.batches);
    }

    @Test
    @DisplayName("a NEED_SNAPSHOT session gets exactly one SNAPSHOT, no updates, then turns READY")
    void snapshotSessionGetsOnlyTheSnapshot() {
        CapturingSession session = new CapturingSession(true);
        broadcaster.addSession(session);

        broadcaster.drain();

        assertEquals(List.of(List.of("{\"type\":\"SNAPSHOT\",\"data\":[\"a-entry\",\"b-entry\"]}")),
                session.batches);
        assertFalse(calls.stream().anyMatch(c -> c.endsWith(".updates")));
        assertEquals(Status.READY, session.getStatus());
    }

    @Test
    @DisplayName("a batch that cannot be enqueued disconnects the session")
    void failedEnqueueDisconnects() {
        CapturingSession ready = new CapturingSession(false);
        ready.setStatus(Status.READY);
        CapturingSession snapshot = new CapturingSession(false);
        broadcaster.addSession(ready);
        broadcaster.addSession(snapshot);

        broadcaster.drain();

        assertFalse(ready.isRunning());
        assertFalse(snapshot.isRunning());
        assertEquals(Status.NEED_SNAPSHOT, snapshot.getStatus());
        assertTrue(ready.batches.isEmpty() && snapshot.batches.isEmpty());
    }
}
