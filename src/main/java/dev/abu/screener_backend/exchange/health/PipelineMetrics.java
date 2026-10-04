package dev.abu.screener_backend.exchange.health;

import dev.abu.screener_backend.exchange.Venue;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * Counters the market-data pipeline reports into, owned by core so adapters never depend on a
 * monitoring or logging class.
 *
 * <p>Deliberately small. This is the embryo of the venue-dimensioned health surface the
 * architecture vision calls for (§8.4) — books by state, connections, queue depth, dropped events —
 * but it holds only what is measured today. Grow it when a number is actually needed, not before.
 *
 * <h3>Why resyncs need counting at all</h3>
 * A synced-book count is blind to churn: a book that de-syncs and recovers every two seconds still
 * reports {@code SYNCED} for most of every sampling window. Without this counter, the only symptom
 * of a systematically broken sequence rule is a synced count that fails to climb — and once it has
 * climbed, nothing at all.
 *
 * <p>Totals are monotonic for the life of the process. Rates are the reader's job: sample, keep the
 * previous value, and subtract. That keeps this class free of any notion of an interval and means
 * two readers cannot steal each other's counts.
 */
@Component
public class PipelineMetrics {

    private final LongAdder[] frames = perVenue();
    private final LongAdder[] resyncs = perVenue();
    private final LongAdder[] snapshotFailures = perVenue();

    private static LongAdder[] perVenue() {
        LongAdder[] adders = new LongAdder[Venue.values().length];
        for (int i = 0; i < adders.length; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }

    /**
     * One data frame arrived on a venue's WebSocket and was routed to an instrument. The only
     * per-venue throughput number: the Disruptor counts per shard, and every shard mixes venues.
     *
     * <p><b>Hot path</b> — called on the WebSocket reader thread for every routed frame. A
     * {@link LongAdder} increment is allocation-free once its cells exist, and striping keeps a
     * venue's several reader threads from contending on one cache line.
     */
    public void recordFrame(Venue venue) {
        frames[venue.ordinal()].increment();
    }

    /** Monotonic total since startup. Subtract two samples for a rate. */
    public long frames(Venue venue) {
        return frames[venue.ordinal()].sum();
    }

    /**
     * One book left {@code SYNCED} (or failed to reach it) and was driven back to {@code PENDING}.
     *
     * <p>Called from a Disruptor consumer thread, but only on the recovery path — never per
     * message — so a striped {@link LongAdder} is far cheaper than the event it accompanies.
     */
    public void recordResync(Venue venue) {
        resyncs[venue.ordinal()].increment();
    }

    /** Monotonic total since startup. Subtract two samples for a rate. */
    public long resyncs(Venue venue) {
        return resyncs[venue.ordinal()].sum();
    }

    /**
     * One snapshot request ended without a body — an HTTP error, a timeout, or a request the venue
     * skipped for budget. The only signal that a venue's REST side is unhealthy: near zero in
     * steady state, non-zero during the startup ramp when the weight budget runs short.
     */
    public void recordSnapshotFailure(Venue venue) {
        snapshotFailures[venue.ordinal()].increment();
    }

    /** Monotonic total since startup. Subtract two samples for a rate. */
    public long snapshotFailures(Venue venue) {
        return snapshotFailures[venue.ordinal()].sum();
    }
}
