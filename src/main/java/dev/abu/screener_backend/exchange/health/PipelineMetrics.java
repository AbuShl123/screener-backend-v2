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

    private final LongAdder[] resyncs;

    public PipelineMetrics() {
        this.resyncs = new LongAdder[Venue.values().length];
        for (int i = 0; i < resyncs.length; i++) {
            resyncs[i] = new LongAdder();
        }
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
}
