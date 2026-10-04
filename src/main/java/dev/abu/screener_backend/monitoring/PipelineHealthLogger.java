package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.book.BookSlot;
import dev.abu.screener_backend.exchange.book.BookSlotTable;
import dev.abu.screener_backend.exchange.book.OrderBookState;
import dev.abu.screener_backend.exchange.health.PipelineMetrics;
import dev.abu.screener_backend.exchange.ingress.DisruptorShardManager;
import dev.abu.screener_backend.feed.OrderBookBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One periodic line covering the whole market-data pipeline: how many books are synced, how hard
 * they are churning, how fast events are being consumed, how much ring headroom is left, and
 * whether the feed's drain loop is keeping inside its budget.
 *
 * <p>Replaces the {@code sync count: spot=… fut=…} line that {@code BookSlotTable} used to emit.
 * A synced count alone has a blind spot big enough to hide a broken sequence rule: a book that
 * de-syncs and recovers continuously still reports {@code SYNCED} most of the time, so the count
 * sits flat and looks healthy. Each of the other four numbers exists to make one specific failure
 * distinguishable from the others:
 *
 * <ul>
 *   <li><b>resyncs</b> — churn. Near zero at steady state; a steady trickle against a flat synced
 *       count means books are cycling, not settled.</li>
 *   <li><b>snapshot failures</b> — the venue's REST side. Non-zero during the startup ramp, when
 *       requests the weight budget cannot afford are failed; near zero after it.</li>
 *   <li><b>msgs/s per shard</b> — throughput, and shard balance. The two shards should track each
 *       other closely; a skew would undermine the {@code id & mask} routing assumption.</li>
 *   <li><b>ring free</b> — whether consumers keep up with producers. A persistent dip is the signal
 *       that the blocking {@code next()} on the producer side is a real risk.</li>
 *   <li><b>drain</b> — whether delivery, rather than ingest, is the bottleneck.</li>
 * </ul>
 *
 * <p>Lives in {@code monitoring/} because it reads across {@code exchange/} and {@code feed/};
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

    private final BookSlotTable slots;
    private final DisruptorShardManager shardManager;
    private final PipelineMetrics metrics;
    private final OrderBookBroadcaster broadcaster;

    private final long[] lastResyncs = new long[Venue.values().length];
    private final long[] lastSnapshotFailures = new long[Venue.values().length];
    private long[] lastProcessed;
    private long lastSampleNanos;

    @Scheduled(fixedDelayString = "30000")
    public void logHealth() {
        long nowNanos = System.nanoTime();
        double elapsedSec = lastSampleNanos == 0 ? 0 : (nowNanos - lastSampleNanos) / 1e9;
        lastSampleNanos = nowNanos;

        StringBuilder line = new StringBuilder(220);
        line.append("pipeline:");
        int[] tracked = appendBookCounts(line);
        appendResyncs(line, tracked);
        appendSnapshotFailures(line, tracked);
        appendThroughput(line, elapsedSec);
        appendRingFree(line);
        appendDrain(line);

        log.info("{}", line);
    }

    /**
     * Synced books per venue against the number tracked, so a shortfall is visible without arithmetic.
     *
     * @return books tracked per venue, by ordinal. The per-venue sections all skip a venue with
     *         none, so a disabled venue never appears in the line.
     */
    private int[] appendBookCounts(StringBuilder line) {
        int[] synced = new int[Venue.values().length];
        int[] total = new int[Venue.values().length];
        for (BookSlot slot : slots.snapshot()) {
            if (slot == null) continue;
            int v = slot.instrument().venue().ordinal();
            total[v]++;
            if (slot.book().getState() == OrderBookState.SYNCED) synced[v]++;
        }
        line.append(" synced");
        for (Venue venue : Venue.values()) {
            if (total[venue.ordinal()] == 0) continue;
            line.append(' ').append(shortName(venue)).append('=')
                    .append(synced[venue.ordinal()]).append('/').append(total[venue.ordinal()]);
        }
        return total;
    }

    private void appendResyncs(StringBuilder line, int[] tracked) {
        line.append(" | resyncs");
        for (Venue venue : Venue.values()) {
            long total = metrics.resyncs(venue);
            long delta = total - lastResyncs[venue.ordinal()];
            lastResyncs[venue.ordinal()] = total;
            if (tracked[venue.ordinal()] == 0) continue;
            line.append(' ').append(shortName(venue)).append('=').append(delta);
        }
    }

    private void appendSnapshotFailures(StringBuilder line, int[] tracked) {
        line.append(" | snapshot failures");
        for (Venue venue : Venue.values()) {
            long total = metrics.snapshotFailures(venue);
            long delta = total - lastSnapshotFailures[venue.ordinal()];
            lastSnapshotFailures[venue.ordinal()] = total;
            if (tracked[venue.ordinal()] == 0) continue;
            line.append(' ').append(shortName(venue)).append('=').append(delta);
        }
    }

    private void appendThroughput(StringBuilder line, double elapsedSec) {
        long[] processed = shardManager.processedPerShard();
        line.append(" | msgs/s [");
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
