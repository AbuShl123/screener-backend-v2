package dev.abu.screener_backend.marketdata.core.recovery;

import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEventPublisher;
import dev.abu.screener_backend.marketdata.spi.SnapshotOutcome;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Core's side of one batch: turns each slot's first report into exactly one ring event, whatever
 * the fetcher does.
 *
 * <p>The fetcher may report from several HTTP threads at once, report a slot twice, report a slot
 * that was never in the batch, or never report at all. The first report for a member slot wins;
 * {@link #seal()} fails every slot still unreported; anything after that is dropped. That is what
 * makes "every accepted request gets exactly one outcome" a core guarantee rather than a fetcher
 * obligation.
 *
 * <p>A late success is ignored on purpose: once its slot has been failed, the book may already be
 * back in {@code PENDING} or re-queued, and a body arriving that late helps no one.
 */
@Slf4j
final class BatchOutcome implements SnapshotOutcome {

    private final List<BookSlot> batch;
    private final DepthEventPublisher publisher;
    private final PipelineMetrics metrics;

    // Guarded by this. Batches are small (max-batch-size), so plain boxed sets are fine.
    private final Set<Integer> members = new HashSet<>();
    private final Set<Integer> reported = new HashSet<>();
    private boolean sealed;

    BatchOutcome(List<BookSlot> batch, DepthEventPublisher publisher, PipelineMetrics metrics) {
        this.batch = batch;
        this.publisher = publisher;
        this.metrics = metrics;
        for (BookSlot slot : batch) {
            members.add(slot.instrument().id());
        }
    }

    @Override
    public void delivered(BookSlot slot, String body) {
        if (claim(slot, "delivered")) {
            publisher.publishSnapshot(slot.instrument().id(), body);
        }
    }

    @Override
    public void failed(BookSlot slot) {
        if (claim(slot, "failed")) {
            publishFailure(slot);
        }
    }

    /**
     * Fails every slot that has not reported and stops accepting reports.
     *
     * @return how many slots core had to fail on the fetcher's behalf
     */
    int seal() {
        List<BookSlot> unreported = new ArrayList<>();
        synchronized (this) {
            if (sealed) return 0;
            sealed = true;
            for (BookSlot slot : batch) {
                if (!reported.contains(slot.instrument().id())) {
                    unreported.add(slot);
                }
            }
        }
        // Published outside the lock: a full ring blocks the publisher, and a reporter waiting on
        // this monitor meanwhile would only be dropped anyway.
        for (BookSlot slot : unreported) {
            publishFailure(slot);
        }
        return unreported.size();
    }

    private void publishFailure(BookSlot slot) {
        metrics.recordSnapshotFailure(slot.instrument().venue());
        publisher.publishSnapshotFailure(slot.instrument().id());
    }

    /**
     * Decides under the lock, publishes outside it. Safe because a claimed slot's book stays
     * {@code RECOVERING} — and so cannot be re-queued — until the event it is about to receive.
     */
    private boolean claim(BookSlot slot, String kind) {
        int id = slot.instrument().id();
        String reason;
        synchronized (this) {
            if (sealed) {
                reason = "batch already sealed";
            } else if (!members.contains(id)) {
                reason = "not in this batch";
            } else if (!reported.add(id)) {
                reason = "already reported";
            } else {
                return true;
            }
        }
        log.debug("[{}] {} snapshot report dropped - {}", slot.instrument().logName(), kind, reason);
        return false;
    }
}
