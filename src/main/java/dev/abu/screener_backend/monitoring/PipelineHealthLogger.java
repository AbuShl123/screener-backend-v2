package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.book.BookSlotTable;
import dev.abu.screener_backend.marketdata.core.book.OrderBookState;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DisruptorShardManager;
import dev.abu.screener_backend.feed.FeedBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One periodic block covering the whole market-data pipeline — a table row per venue, then one line
 * for the shards and the feed: how many books are synced, how hard they are churning, how fast
 * events are being consumed, how much ring headroom is left, and whether the feed's drain loop is
 * keeping inside its budget.
 *
 * <p>Replaces the {@code sync count: spot=… fut=…} line that {@code BookSlotTable} used to emit.
 * A synced count alone has a blind spot big enough to hide a broken sequence rule: a book that
 * de-syncs and recovers continuously still reports {@code SYNCED} most of the time, so the count
 * sits flat and looks healthy. Each of the other numbers exists to make one specific failure
 * distinguishable from the others:
 *
 * <ul>
 *   <li><b>resyncs</b> — churn. Near zero at steady state; a steady trickle against a flat synced
 *       count means books are cycling, not settled.</li>
 *   <li><b>snap fails</b> (snapshot failures) — the venue's REST side. Non-zero during the startup ramp, when
 *       requests the weight budget cannot afford are failed; near zero after it.</li>
 *   <li><b>frames/s per venue</b> — what each venue's WebSocket delivers, before sync state is
 *       known. A venue at zero has a dead or never-subscribed stream; it is also the only rate that
 *       moves for a venue whose books cannot sync yet.</li>
 *   <li><b>msgs/s per shard</b> — throughput, and shard balance. The two shards should track each
 *       other closely; a skew would undermine the {@code id & mask} routing assumption.</li>
 *   <li><b>ring free</b> — whether consumers keep up with producers. A persistent dip is the signal
 *       that the blocking {@code next()} on the producer side is a real risk.</li>
 *   <li><b>drain</b> — whether delivery, rather than ingest, is the bottleneck.</li>
 * </ul>
 *
 * <p>Lives in {@code monitoring/} because it reads across {@code marketdata/} and {@code feed/};
 * putting it in either would have coupled them. Nothing depends on it, so it introduces no cycle.
 *
 * <p>Everything here runs on the scheduler thread and samples counters the pipeline maintains
 * anyway — no hot-path cost, and the rates are computed from deltas rather than by resetting
 * counters other readers might want.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineHealthLogger {

    /** venue | synced | resyncs | snap fails | frames/s — wide enough for {@code binance/futures}. */
    private static final String ROW = "  %-16s %9s %8s %11s %9s";

    private final BookSlotTable slots;
    private final DisruptorShardManager shardManager;
    private final PipelineMetrics metrics;
    private final FeedBroadcaster broadcaster;

    private final long[] lastResyncs = new long[Venue.values().length];
    private final long[] lastSnapshotFailures = new long[Venue.values().length];
    private final long[] lastFrames = new long[Venue.values().length];
    private long[] lastProcessed;
    private long lastSampleNanos;

    @Scheduled(fixedDelayString = "30000")
    public void logHealth() {
        long nowNanos = System.nanoTime();
        double elapsedSec = lastSampleNanos == 0 ? 0 : (nowNanos - lastSampleNanos) / 1e9;
        lastSampleNanos = nowNanos;

        StringBuilder out = new StringBuilder(512);
        out.append("pipeline health:");
        appendVenueTable(out, elapsedSec);
        out.append(System.lineSeparator()).append("  shards  ");
        appendThroughput(out, elapsedSec);
        appendRingFree(out);
        appendDrain(out);

        log.info("{}", out);
    }

    /**
     * One row per venue: synced books against the number tracked (so a shortfall is visible
     * without arithmetic), then resyncs, snapshot failures and frame rate over the interval. A
     * venue with no tracked books is skipped, so a disabled venue never appears — but its counters
     * are still sampled, so the first interval after it appears is not inflated.
     */
    private void appendVenueTable(StringBuilder out, double elapsedSec) {
        int[] synced = new int[Venue.values().length];
        int[] total = new int[Venue.values().length];
        for (BookSlot slot : slots.snapshot()) {
            if (slot == null) continue;
            int v = slot.instrument().venue().ordinal();
            total[v]++;
            if (slot.book().getState() == OrderBookState.SYNCED) synced[v]++;
        }

        out.append(System.lineSeparator())
                .append(String.format(ROW, "venue", "synced", "resyncs", "snap fails", "frames/s"));
        for (Venue venue : Venue.values()) {
            int v = venue.ordinal();
            long resyncs = delta(metrics.resyncs(venue), lastResyncs, v);
            long snapshotFailures = delta(metrics.snapshotFailures(venue), lastSnapshotFailures, v);
            long frames = delta(metrics.frames(venue), lastFrames, v);
            if (total[v] == 0) continue;
            out.append(System.lineSeparator()).append(String.format(ROW,
                    shortName(venue),
                    synced[v] + "/" + total[v],
                    resyncs,
                    snapshotFailures,
                    elapsedSec <= 0 ? "-" : Math.round(frames / elapsedSec)));
        }
    }

    /** Advances {@code last[i]} to {@code current} and returns the increase since the previous sample. */
    private static long delta(long current, long[] last, int i) {
        long delta = current - last[i];
        last[i] = current;
        return delta;
    }

    private void appendThroughput(StringBuilder line, double elapsedSec) {
        long[] processed = shardManager.processedPerShard();
        line.append("msgs/s [");
        for (int i = 0; i < processed.length; i++) {
            if (i > 0) line.append(", ");
            if (lastProcessed == null || lastProcessed.length != processed.length || elapsedSec <= 0) {
                line.append('-');
            } else {
                line.append(Math.round((processed[i] - lastProcessed[i]) / elapsedSec));
            }
        }
        line.append(']');
        lastProcessed = processed;
    }

    private void appendRingFree(StringBuilder line) {
        long[] free = shardManager.ringFreePerShard();
        line.append(" | ring free [");
        for (int i = 0; i < free.length; i++) {
            if (i > 0) line.append(", ");
            line.append(free[i]);
        }
        line.append(']');
    }

    private void appendDrain(StringBuilder line) {
        long[] drain = broadcaster.takeDrainStats();
        line.append(" | drain max=").append(drain[0]).append("ms");
        if (drain[1] > 0) line.append(" slow=").append(drain[1]);
    }

    /** {@code BINANCE_SPOT} → {@code binance/spot} — readable without being a paragraph. */
    private static String shortName(Venue venue) {
        return venue.exchange().name().toLowerCase() + "/" + venue.market().name().toLowerCase();
    }
}
