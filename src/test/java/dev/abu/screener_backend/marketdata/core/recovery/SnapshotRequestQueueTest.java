package dev.abu.screener_backend.marketdata.core.recovery;

import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEventPublisher;
import dev.abu.screener_backend.marketdata.core.ingress.EventType;
import dev.abu.screener_backend.marketdata.core.recovery.SnapshotRequestQueue;
import dev.abu.screener_backend.marketdata.spi.SnapshotFetcher;
import dev.abu.screener_backend.marketdata.spi.SnapshotOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SnapshotRequestQueue} driven through {@code tick()} directly, with a scripted fetcher and
 * a publisher that records what would have entered the ring.
 */
class SnapshotRequestQueueTest {

    private static final Venue VENUE = Venue.BINANCE_SPOT;

    private final FakeFetcher fetcher = new FakeFetcher();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final PipelineMetrics metrics = new PipelineMetrics();

    private SnapshotRequestQueue queue(int maxBatchSize) {
        return queue(maxBatchSize, Duration.ofSeconds(30));
    }

    private SnapshotRequestQueue queue(int maxBatchSize, Duration batchTimeout) {
        return new SnapshotRequestQueue(VENUE, fetcher,
                new SnapshotQueueProperties(maxBatchSize, Duration.ofMillis(250), batchTimeout), publisher, metrics);
    }

    private static BookSlot slot(int id) {
        return new BookSlot(Instrument.of(id, VENUE, "S" + id + "USDT", "S" + id, "USDT"),
                new OrderBook(0.1), null, null);
    }

    private static List<Integer> ids(List<BookSlot> slots) {
        return slots.stream().map(s -> s.instrument().id()).toList();
    }

    @Test
    @DisplayName("accepts up to max-batch-size, then refuses")
    void acceptsUpToMaxBatchSize() {
        SnapshotRequestQueue q = queue(3);

        assertTrue(q.requestRecovery(slot(1)));
        assertTrue(q.requestRecovery(slot(2)));
        assertTrue(q.requestRecovery(slot(3)));
        assertFalse(q.requestRecovery(slot(4)), "the batch cap is also the cap on buffering books");
    }

    @Test
    @DisplayName("refuses while a batch is in flight, and reopens once it completes")
    void refusesWhileInFlight() {
        SnapshotRequestQueue q = queue(10);
        CompletableFuture<Void> stage = new CompletableFuture<>();
        fetcher.behaviour = (batch, outcome) -> stage;

        q.requestRecovery(slot(1));
        CompletableFuture<Void> done = q.tick();

        assertFalse(q.requestRecovery(slot(2)), "closed until every request has an outcome");

        stage.complete(null);
        done.join();

        assertTrue(q.requestRecovery(slot(2)));
    }

    @Test
    @DisplayName("refuses while the fetcher is not accepting, so books stay PENDING without buffering")
    void refusesWhileFetcherNotAccepting() {
        SnapshotRequestQueue q = queue(10);
        fetcher.accepting = false;

        assertFalse(q.requestRecovery(slot(1)));
        q.tick().join();

        assertEquals(0, fetcher.batches.size(), "nothing was queued, so nothing is fetched");
    }

    @Test
    @DisplayName("a book queued twice is fetched once, with one outcome")
    void dedupesById() {
        SnapshotRequestQueue q = queue(2);

        assertTrue(q.requestRecovery(slot(7)));
        assertTrue(q.requestRecovery(slot(7)), "the hot path does not dedupe; tick does");
        q.tick().join();

        assertEquals(List.of(7), ids(fetcher.batches.getFirst()));
        assertEquals(List.of(new Published(EventType.REST_FAILED, 7, null)), publisher.events);
    }

    @Test
    @Timeout(30)
    @DisplayName("under concurrent producers every accepted request gets exactly one outcome")
    void concurrentProducersGetExactlyOneOutcomeEach() throws Exception {
        SnapshotRequestQueue q = queue(10);
        int producers = 4;
        int idsPerProducer = 2_000;
        AtomicInteger finished = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            int base = p * idsPerProducer;
            // Each producer is a shard consumer: it re-asks for the same book until accepted.
            Thread t = Thread.ofPlatform().start(() -> {
                for (int k = 0; k < idsPerProducer; k++) {
                    BookSlot s = slot(base + k);
                    while (!q.requestRecovery(s)) Thread.onSpinWait();
                }
                finished.incrementAndGet();
            });
            threads.add(t);
        }

        // The scheduler: keep ticking until every producer is done, then drain what is left.
        while (finished.get() < producers) {
            q.tick().join();
        }
        q.tick().join();
        for (Thread t : threads) t.join();

        int total = producers * idsPerProducer;
        Set<Integer> seen = new HashSet<>();
        synchronized (publisher) {
            assertEquals(total, publisher.events.size(), "one outcome per accepted request, no more, no less");
            for (Published e : publisher.events) {
                assertTrue(seen.add(e.instrumentId()), "duplicate outcome for " + e.instrumentId());
            }
        }
        for (List<BookSlot> batch : fetcher.batches) {
            assertTrue(batch.size() <= 10, "a batch never exceeds max-batch-size");
        }
    }

    @Test
    @DisplayName("drains in full, in arrival order, and never dispatches a request twice")
    void drainsInFull() {
        SnapshotRequestQueue q = queue(10);
        q.requestRecovery(slot(3));
        q.requestRecovery(slot(1));
        q.requestRecovery(slot(2));

        q.tick().join();
        q.tick().join();

        assertEquals(1, fetcher.batches.size(), "the second tick finds nothing pending");
        assertEquals(List.of(3, 1, 2), ids(fetcher.batches.getFirst()));
    }

    @Test
    @DisplayName("publishes a delivered slot as REST_MSG and a failed one as REST_FAILED")
    void publishesOutcomes() {
        SnapshotRequestQueue q = queue(10);
        fetcher.behaviour = (batch, outcome) -> {
            outcome.delivered(batch.get(0), "{\"lastUpdateId\":1}");
            outcome.failed(batch.get(1));
            return CompletableFuture.completedFuture(null);
        };
        q.requestRecovery(slot(1));
        q.requestRecovery(slot(2));

        q.tick().join();

        assertEquals(2, publisher.events.size());
        assertEquals(new Published(EventType.REST_MSG, 1, "{\"lastUpdateId\":1}"), publisher.events.get(0));
        assertEquals(new Published(EventType.REST_FAILED, 2, null), publisher.events.get(1));
        assertEquals(1, metrics.snapshotFailures(VENUE));
    }

    @Test
    @DisplayName("fails every slot the fetcher never reports, once its stage completes")
    void unreportedSlotsFailOnCompletion() {
        SnapshotRequestQueue q = queue(10);
        fetcher.behaviour = (batch, outcome) -> {
            outcome.delivered(batch.get(0), "{}");
            return CompletableFuture.completedFuture(null);
        };
        q.requestRecovery(slot(1));
        q.requestRecovery(slot(2));
        q.requestRecovery(slot(3));

        q.tick().join();

        assertEquals(List.of(
                new Published(EventType.REST_MSG, 1, "{}"),
                new Published(EventType.REST_FAILED, 2, null),
                new Published(EventType.REST_FAILED, 3, null)), publisher.events);
        assertEquals(2, metrics.snapshotFailures(VENUE));
    }

    @Test
    @DisplayName("a fetcher that never completes is failed by batch-timeout, and the queue reopens")
    void batchTimeoutFailsAndReopens() throws Exception {
        SnapshotRequestQueue q = queue(10, Duration.ofMillis(50));
        fetcher.behaviour = (batch, outcome) -> new CompletableFuture<>();   // never completes
        q.requestRecovery(slot(1));
        q.requestRecovery(slot(2));

        q.tick().get(5, TimeUnit.SECONDS);

        assertEquals(List.of(
                new Published(EventType.REST_FAILED, 1, null),
                new Published(EventType.REST_FAILED, 2, null)), publisher.events);
        assertTrue(q.requestRecovery(slot(3)), "reopened after the timeout");
    }

    @Test
    @DisplayName("late and duplicate reports are dropped — exactly one outcome per request")
    void lateAndDuplicateReportsDropped() {
        SnapshotRequestQueue q = queue(10);
        SnapshotOutcome[] captured = new SnapshotOutcome[1];
        fetcher.behaviour = (batch, outcome) -> {
            captured[0] = outcome;
            outcome.delivered(batch.get(0), "{\"first\":true}");
            outcome.delivered(batch.get(0), "{\"second\":true}");   // duplicate
            outcome.failed(batch.get(0));                           // contradicting duplicate
            outcome.delivered(slot(99), "{}");                      // never in the batch
            return CompletableFuture.completedFuture(null);
        };
        q.requestRecovery(slot(1));
        q.requestRecovery(slot(2));

        q.tick().join();
        captured[0].delivered(slot(2), "{\"late\":true}");          // after sealing: slot 2 already failed

        assertEquals(List.of(
                new Published(EventType.REST_MSG, 1, "{\"first\":true}"),
                new Published(EventType.REST_FAILED, 2, null)), publisher.events);
    }

    @Test
    @DisplayName("fetchAll throwing synchronously fails the whole batch and reopens the queue")
    void synchronousThrowFailsAndReopens() {
        SnapshotRequestQueue q = queue(10);
        fetcher.behaviour = (batch, outcome) -> {
            throw new IllegalStateException("boom");
        };
        q.requestRecovery(slot(1));

        q.tick().join();

        assertEquals(List.of(new Published(EventType.REST_FAILED, 1, null)), publisher.events);
        assertTrue(q.requestRecovery(slot(2)));
    }

    @Test
    @DisplayName("a stage that completes exceptionally still fails the unreported slots and reopens")
    void exceptionalStageFailsAndReopens() {
        SnapshotRequestQueue q = queue(10);
        fetcher.behaviour = (batch, outcome) -> CompletableFuture.failedFuture(new RuntimeException("io"));
        q.requestRecovery(slot(1));

        q.tick().join();

        assertEquals(List.of(new Published(EventType.REST_FAILED, 1, null)), publisher.events);
        assertTrue(q.requestRecovery(slot(2)));
    }

    @Test
    @DisplayName("tick with nothing pending never calls the fetcher")
    void emptyTickIsNoOp() {
        queue(10).tick().join();

        assertEquals(0, fetcher.batches.size());
        assertNull(fetcher.lastOutcome);
    }

    // --- Fakes ---------------------------------------------------------------------------------

    private static final class FakeFetcher implements SnapshotFetcher {
        volatile boolean accepting = true;
        final List<List<BookSlot>> batches = new ArrayList<>();
        SnapshotOutcome lastOutcome;
        /** Default: report nothing and complete at once — core fails every slot. */
        BiFunction<List<BookSlot>, SnapshotOutcome, CompletionStage<Void>> behaviour =
                (batch, outcome) -> CompletableFuture.completedFuture(null);

        @Override
        public boolean isAcceptingRequests() {
            return accepting;
        }

        @Override
        public CompletionStage<Void> fetchAll(List<BookSlot> batch, SnapshotOutcome outcome) {
            batches.add(batch);
            lastOutcome = outcome;
            return behaviour.apply(batch, outcome);
        }
    }

    record Published(EventType type, int instrumentId, String payload) { }

    private static final class RecordingPublisher implements DepthEventPublisher {
        final List<Published> events = new ArrayList<>();

        @Override
        public synchronized void publishFrame(int instrumentId, String payload) {
            throw new AssertionError("the snapshot queue never publishes frames");
        }

        @Override
        public synchronized void publishSnapshot(int instrumentId, String payload) {
            events.add(new Published(EventType.REST_MSG, instrumentId, payload));
        }

        @Override
        public synchronized void publishSnapshotFailure(int instrumentId) {
            events.add(new Published(EventType.REST_FAILED, instrumentId, null));
        }
    }
}
