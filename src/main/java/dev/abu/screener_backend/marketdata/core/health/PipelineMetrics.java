package dev.abu.screener_backend.marketdata.core.health;

import dev.abu.screener_backend.marketdata.StreamKey;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.spike.SpikeKind;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
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

    /** Per stream, indexed by {@link StreamKey#index()}. */
    private final LongAdder[] frames = adders(StreamKey.COUNT);
    /** Per venue: resyncs and snapshot failures are depth-sync concepts, not per stream. */
    private final LongAdder[] resyncs = adders(Venue.values().length);
    private final LongAdder[] snapshotFailures = adders(Venue.values().length);
    /** Per venue: a venue has at most one trade stream, so per venue is per trade stream. */
    private final LongAdder[] tradesDecoded = adders(Venue.values().length);
    private final LongAdder[] tradesFiltered = adders(Venue.values().length);
    private final LongAdder[] tradeParseFailures = adders(Venue.values().length);
    private final LongAdder[] tradeAlerts = adders(Venue.values().length);
    private final LongAdder[] tradeAlertsCoalescedOnly = adders(Venue.values().length);
    private final LongAdder[] tradeIdleCloses = adders(Venue.values().length);
    private final LongAdder[] tradeIdleSplits = adders(Venue.values().length);
    /** Per venue: a venue has at most one kline stream. */
    private final LongAdder[] klines = adders(Venue.values().length);
    private final LongAdder[] klineParseFailures = adders(Venue.values().length);
    private final LongAdder[] candleGaps = adders(Venue.values().length);
    private final LongAdder[] priceSpikes = adders(Venue.values().length);
    private final LongAdder[] volumeSpikes = adders(Venue.values().length);
    private final LongAdder ticksSkipped = new LongAdder();
    private final LongAdder clusterRuns = new LongAdder();
    private final LongAdder clusterRunNanos = new LongAdder();
    private final AtomicLong clusterRunMaxNanos = new AtomicLong();
    private final LongAdder activeClusters = new LongAdder();
    private final LongAdder clusterEmits = new LongAdder();
    private final LongAdder clusterFailures = new LongAdder();

    /** Labels of the fills-per-alerted-sweep buckets: 1, 2–3, 4–10, more than 10. */
    public static final String[] FILL_BUCKETS = {"1", "2-3", "4-10", ">10"};
    /** Per venue and bucket, at {@code venue.ordinal() * FILL_BUCKETS.length + bucket}. */
    private final LongAdder[] tradeAlertFills = adders(Venue.values().length * FILL_BUCKETS.length);

    private static LongAdder[] adders(int count) {
        LongAdder[] adders = new LongAdder[count];
        for (int i = 0; i < adders.length; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }

    /**
     * One data frame arrived on a stream's WebSocket and was routed to an instrument. The only
     * per-stream throughput number: the Disruptor counts per shard, and every shard mixes venues
     * and kinds.
     *
     * <p><b>Hot path</b> — called on the WebSocket reader thread for every routed frame. A
     * {@link LongAdder} increment is allocation-free once its cells exist, and striping keeps a
     * stream's several reader threads from contending on one cache line.
     */
    public void recordFrame(StreamKey stream) {
        frames[stream.index()].increment();
    }

    /** Monotonic total since startup. Subtract two samples for a rate. */
    public long frames(StreamKey stream) {
        return frames[stream.index()].sum();
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

    /**
     * One trade decoded from a {@code TRADE} frame and handed to detection. On Binance this tracks
     * the stream's frame count one for one (one trade per frame); a gap between the two means frames
     * are failing to decode. Venues that batch trades decode more trades than frames, and trades the
     * decoder drops are counted by {@link #recordTradeFiltered} instead.
     *
     * <p><b>Hot path</b> — called on a Disruptor consumer thread for every trade. Striped, because
     * every shard decodes trades of every venue.
     */
    public void recordTradeDecoded(Venue venue) {
        tradesDecoded[venue.ordinal()].increment();
    }

    /** Monotonic total since startup. Subtract two samples for a rate. */
    public long tradesDecoded(Venue venue) {
        return tradesDecoded[venue.ordinal()].sum();
    }

    /**
     * One trade the venue's decoder dropped by policy (MEXC spot {@code X0_X0}, MEXC futures
     * self-trades). <b>Hot path</b>, like {@link #recordTradeDecoded}.
     */
    public void recordTradeFiltered(Venue venue) {
        tradesFiltered[venue.ordinal()].increment();
    }

    /** Monotonic total since startup. Subtract two samples for a rate. */
    public long tradesFiltered(Venue venue) {
        return tradesFiltered[venue.ordinal()].sum();
    }

    /** One {@code TRADE} frame the venue's decoder could not parse. Zero in steady state. */
    public void recordTradeParseFailure(Venue venue) {
        tradeParseFailures[venue.ordinal()].increment();
    }

    /** Monotonic total since startup. Subtract two samples for a rate. */
    public long tradeParseFailures(Venue venue) {
        return tradeParseFailures[venue.ordinal()].sum();
    }

    /**
     * One large-trade alert raised for a sweep of {@code fills} fills; {@code coalescedOnly} when no
     * single fill reached the threshold on its own. Rare path: far fewer than decoded trades.
     */
    public void recordTradeAlert(Venue venue, int fills, boolean coalescedOnly) {
        int v = venue.ordinal();
        tradeAlerts[v].increment();
        tradeAlertFills[v * FILL_BUCKETS.length + fillBucket(fills)].increment();
        if (coalescedOnly) tradeAlertsCoalescedOnly[v].increment();
    }

    /** Monotonic total since startup. Subtract two samples for a count over an interval. */
    public long tradeAlerts(Venue venue) {
        return tradeAlerts[venue.ordinal()].sum();
    }

    /** Alerts that exist only because their fills were summed. Monotonic total since startup. */
    public long tradeAlertsCoalescedOnly(Venue venue) {
        return tradeAlertsCoalescedOnly[venue.ordinal()].sum();
    }

    /**
     * One sweep closed by the idle close (judged, whether or not it alerted). At most once per sweep,
     * on the shard thread.
     */
    public void recordTradeIdleClose(Venue venue) {
        tradeIdleCloses[venue.ordinal()].increment();
    }

    /** Monotonic total for {@code venue}. */
    public long tradeIdleCloses(Venue venue) {
        return tradeIdleCloses[venue.ordinal()].sum();
    }

    /**
     * One fill that would have continued a sweep the idle close had already closed: the sweep was
     * split in two. The live measure of whether the venue's {@code sweep-idle-close-ms} is too short;
     * it should stay near zero.
     */
    public void recordTradeIdleSplit(Venue venue) {
        tradeIdleSplits[venue.ordinal()].increment();
    }

    /** Monotonic total for {@code venue}. */
    public long tradeIdleSplits(Venue venue) {
        return tradeIdleSplits[venue.ordinal()].sum();
    }

    /**
     * One candle push applied to an instrument's candle store (stale pushes are not counted).
     * <b>Hot path</b>: called on a Disruptor consumer thread for every kline frame.
     */
    public void recordKline(Venue venue) {
        klines[venue.ordinal()].increment();
    }

    /** Monotonic total for {@code venue}. */
    public long klines(Venue venue) {
        return klines[venue.ordinal()].sum();
    }

    /** One {@code KLINE} frame the venue's decoder could not parse. */
    public void recordKlineParseFailure(Venue venue) {
        klineParseFailures[venue.ordinal()].increment();
    }

    /** Monotonic total for {@code venue}. */
    public long klineParseFailures(Venue venue) {
        return klineParseFailures[venue.ordinal()].sum();
    }

    /**
     * One instrument whose candles skipped at least one interval (a reconnect, or a minute the venue
     * sent nothing for): its spike windows restart empty.
     */
    public void recordCandleGap(Venue venue) {
        candleGaps[venue.ordinal()].increment();
    }

    /** Monotonic total for {@code venue}. */
    public long candleGaps(Venue venue) {
        return candleGaps[venue.ordinal()].sum();
    }

    /** One price or volume spike alert. Rare path. */
    public void recordSpikeAlert(Venue venue, SpikeKind kind) {
        (kind == SpikeKind.PRICE ? priceSpikes : volumeSpikes)
                [venue.ordinal()].increment();
    }

    /** Monotonic total for {@code venue}. */
    public long priceSpikes(Venue venue) {
        return priceSpikes[venue.ordinal()].sum();
    }

    /** Monotonic total for {@code venue}. */
    public long volumeSpikes(Venue venue) {
        return volumeSpikes[venue.ordinal()].sum();
    }

    /**
     * One shard tick not published because the shard's ring was full. The next tick follows, so a
     * skip only delays idle closes; a steady count means the shard is not keeping up.
     */
    public void recordTickSkipped() {
        ticksSkipped.increment();
    }

    /** Monotonic total over all shards. */
    public long ticksSkipped() {
        return ticksSkipped.sum();
    }

    /**
     * Alerts whose sweep had a fill count in {@link #FILL_BUCKETS}{@code [bucket]}. Monotonic total
     * since startup.
     */
    public long tradeAlertFills(Venue venue, int bucket) {
        return tradeAlertFills[venue.ordinal() * FILL_BUCKETS.length + bucket].sum();
    }

    /**
     * One order-cluster detection run over one book took {@code nanos}. Throttled per instrument
     * ({@code screener.clusters.throttle-millis}), so far rarer than depth events; allocation-free.
     */
    public void recordClusterRun(long nanos) {
        clusterRuns.increment();
        clusterRunNanos.add(nanos);
        long max = clusterRunMaxNanos.get();
        while (nanos > max && !clusterRunMaxNanos.compareAndSet(max, nanos)) {
            max = clusterRunMaxNanos.get();
        }
    }

    /** Monotonic total of detection runs since startup. */
    public long clusterRuns() {
        return clusterRuns.sum();
    }

    /** Monotonic total time spent in detection runs since startup. Divide deltas for a mean. */
    public long clusterRunNanos() {
        return clusterRunNanos.sum();
    }

    /** The slowest detection run since the previous call, then resets it. One reader: the health log. */
    public long takeClusterRunMaxNanos() {
        return clusterRunMaxNanos.getAndSet(0);
    }

    /** An announced cluster appeared ({@code +1}) or went away ({@code -1}). */
    public void adjustActiveClusters(int delta) {
        activeClusters.add(delta);
    }

    /** Announced clusters right now, over every shard. A gauge, not a total. */
    public long activeClusters() {
        return activeClusters.sum();
    }

    /** One cluster announced or re-announced (an upsert built). Monotonic total since startup. */
    public void recordClusterEmit() {
        clusterEmits.increment();
    }

    /** Monotonic total of cluster (re-)announcements since startup. */
    public long clusterEmits() {
        return clusterEmits.sum();
    }

    /** One cluster detection run threw; the shard carried on. Zero in steady state. */
    public void recordClusterFailure() {
        clusterFailures.increment();
    }

    /** Monotonic total of failed detection runs since startup. */
    public long clusterFailures() {
        return clusterFailures.sum();
    }

    private static int fillBucket(int fills) {
        if (fills <= 1) return 0;
        if (fills <= 3) return 1;
        if (fills <= 10) return 2;
        return 3;
    }
}
