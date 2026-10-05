package dev.abu.screener_backend.marketdata.core.recovery;

import dev.abu.screener_backend.config.ExchangesProperties.SnapshotQueueProperties;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEventPublisher;
import dev.abu.screener_backend.marketdata.spi.RecoverySink;
import dev.abu.screener_backend.marketdata.spi.SnapshotFetcher;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The snapshot recovery queue for one REST-recovering venue. Core queues, the venue paces: this
 * class behaves identically for every venue, and everything venue-shaped sits behind the
 * {@link SnapshotFetcher}.
 *
 * <ul>
 *   <li><b>Accept</b> while open and under {@code max-batch-size}, and while the fetcher is
 *       accepting. Every refusal is cheap and normal: the book stays {@code PENDING}, buffers
 *       nothing, and asks again on its next diff.</li>
 *   <li><b>Flush</b> on a fixed tick ({@link SnapshotQueueFactory}'s thread): every pending request
 *       goes out as one batch, and the queue stays closed until each has an outcome.</li>
 *   <li><b>Publish</b> exactly one ring event per request ({@code REST_MSG} or
 *       {@code REST_FAILED}) through {@link BatchOutcome}, which also fails whatever the fetcher
 *       leaves unreported. No request is ever retried or dispatched twice.</li>
 * </ul>
 *
 * <p><b>Lock-free on the consumer side.</b> {@link #requestRecovery} runs on shard consumer threads,
 * so it never locks and never allocates: one volatile read answers every refusal, and an accept is
 * one CAS that reserves an index in a preallocated array plus one volatile store into it. All the
 * waiting is pushed onto the queue's own thread — {@link #tick} closes the queue with a CAS, then
 * spins until every reserved index has been written.
 *
 * <p>Not a {@code @Component}: one instance per venue, created by the adapter through
 * {@link SnapshotQueueFactory}.
 */
@Slf4j
public class SnapshotRequestQueue implements RecoverySink {

    /** {@link #state} from the moment {@link #tick} takes a batch until that batch is sealed. */
    private static final int CLOSED = -1;

    private final Venue venue;
    private final SnapshotFetcher fetcher;
    private final DepthEventPublisher publisher;
    private final PipelineMetrics metrics;
    private final int maxBatchSize;
    private final long batchTimeoutNanos;

    /**
     * While open: how many indexes of {@link #slots} are reserved, {@code 0..maxBatchSize}.
     * {@link #CLOSED} while a batch is in flight. Only {@link #tick} closes it and only
     * {@link #finish} reopens it.
     */
    private final AtomicInteger state = new AtomicInteger();
    /** Index {@code i} is written once by the consumer that reserved it, and emptied by {@link #tick}. */
    private final AtomicReferenceArray<BookSlot> slots;

    SnapshotRequestQueue(Venue venue, SnapshotFetcher fetcher, SnapshotQueueProperties props,
                         DepthEventPublisher publisher, PipelineMetrics metrics) {
        this.venue = venue;
        this.fetcher = fetcher;
        this.publisher = publisher;
        this.metrics = metrics;
        this.maxBatchSize = props.maxBatchSize();
        this.batchTimeoutNanos = props.batchTimeout().toNanos();
        this.slots = new AtomicReferenceArray<>(maxBatchSize);
    }

    public Venue venue() {
        return venue;
    }

    /**
     * Shard consumer threads, possibly several at once. During the startup ramp this runs on every
     * diff of every {@code PENDING} book, and almost every call is a refusal — closed or full —
     * answered by the first volatile read.
     *
     * <p>No dedupe here: a book asks only while {@code PENDING} and turns {@code RECOVERING} as soon
     * as this returns {@code true}, so it cannot ask twice for one batch except after a diff-buffer
     * overflow. {@link #tick} drops such a duplicate.
     */
    @Override
    public boolean requestRecovery(BookSlot slot) {
        int n = state.get();
        if (n == CLOSED || n >= maxBatchSize) return false;
        if (!fetcher.isAcceptingRequests()) return false;
        while (!state.compareAndSet(n, n + 1)) {
            n = state.get();
            if (n == CLOSED || n >= maxBatchSize) return false;
        }
        slots.set(n, slot);
        return true;
    }

    /**
     * Drains the queue in full and hands the batch to the fetcher. A no-op while a batch is in
     * flight or nothing is pending. Single-threaded: only the factory's scheduler (or a test) calls it.
     *
     * @return completes once the batch is sealed and the queue has reopened — tests join it; the
     *         scheduler ignores it
     */
    CompletableFuture<Void> tick() {
        int reserved = state.get();
        while (reserved > 0 && !state.compareAndSet(reserved, CLOSED)) {
            reserved = state.get();   // a consumer reserved meanwhile
        }
        if (reserved <= 0) return CompletableFuture.completedFuture(null);   // empty, or in flight

        List<BookSlot> batch = drain(reserved);
        BatchOutcome outcome = new BatchOutcome(batch, publisher, metrics);
        CompletableFuture<Void> stage;
        try {
            // copy(): the timeout must not complete a future the fetcher still owns
            stage = fetcher.fetchAll(batch, outcome).toCompletableFuture().copy();
        } catch (Throwable t) {
            finish(outcome, batch.size(), t);
            return CompletableFuture.completedFuture(null);
        }
        return stage.orTimeout(batchTimeoutNanos, TimeUnit.NANOSECONDS)
                .handle((ignored, err) -> {
                    finish(outcome, batch.size(), err);
                    return null;
                });
    }

    /**
     * Takes the first {@code reserved} slots, in arrival order, without duplicates. A consumer that
     * won its CAS may not have stored its slot yet — it is one store away — so this waits for it,
     * here on the queue's thread rather than on the consumer's. Nothing between that CAS and that
     * store can throw, so the wait always ends.
     */
    private List<BookSlot> drain(int reserved) {
        Map<Integer, BookSlot> unique = new LinkedHashMap<>();
        for (int i = 0; i < reserved; i++) {
            BookSlot slot;
            while ((slot = slots.getAndSet(i, null)) == null) {
                Thread.onSpinWait();
            }
            unique.putIfAbsent(slot.instrument().id(), slot);
        }
        return List.copyOf(unique.values());
    }

    private void finish(BatchOutcome outcome, int batchSize, Throwable err) {
        try {
            Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
            if (cause instanceof TimeoutException) {
                log.warn("[{}] snapshot batch of {} timed out after {} ms", venue, batchSize,
                        TimeUnit.NANOSECONDS.toMillis(batchTimeoutNanos));
            } else if (cause != null) {
                log.warn("[{}] snapshot batch of {} failed: {}", venue, batchSize, cause.toString());
            }
            int failedByCore = outcome.seal();
            if (failedByCore > 0) {
                log.debug("[{}] {} of {} snapshot requests left unreported - failed by the queue",
                        venue, failedByCore, batchSize);
            }
        } finally {
            state.set(0);   // reopen: every index was emptied by drain()
        }
    }
}
