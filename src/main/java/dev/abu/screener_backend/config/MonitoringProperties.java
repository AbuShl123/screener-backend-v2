package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the admin monitoring endpoints under {@code /api/monitoring}.
 *
 * @param orderbook orderbook-fleet statistics settings
 */
@ConfigurationProperties(prefix = "screener.monitoring")
public record MonitoringProperties(Orderbook orderbook) {

    /**
     * @param publishIntervalMs     how often each shard's consumer republishes its books'
     *                              {@code BookStats} (hot-path cadence)
     * @param sampleIntervalMs      how often the collector samples published stats to derive rates;
     *                              also the resolution of {@code idleMs}. Keep it well below
     *                              {@code staleThresholdMs}
     * @param historyIntervalMs     how often a fleet point is appended to the trend ring.
     *                              <b>Deliberately decoupled from {@code sampleIntervalMs}</b>: rates
     *                              and staleness need a fast sample, a multi-hour trend does not, and
     *                              at overnight retention the ring's JSON payload — not its heap
     *                              footprint — is what gets expensive. Rounded up to a whole number
     *                              of sample intervals
     * @param rateHalfLifeMs        EWMA half-life for the smoothed per-book update rate
     * @param historySize           fleet history ring length in points; retention is
     *                              {@code historySize × historyIntervalMs} (2 880 × 30 s = 24 h)
     * @param staleThresholdMs      a SYNCED book with no diff for this long counts as stale
     * @param defaultHistogramBins  {@code 0} = choose bin count by Freedman–Diaconis
     * @param maxOutliersListed     hard cap on named outliers per tail
     * @param maxBooksReturned      hard cap on rows from {@code /orderbook/books}
     * @param recorder              on-disk CSV capture settings
     */
    public record Orderbook(
            long publishIntervalMs,
            long sampleIntervalMs,
            long historyIntervalMs,
            long rateHalfLifeMs,
            int historySize,
            long staleThresholdMs,
            int defaultHistogramBins,
            int maxOutliersListed,
            int maxBooksReturned,
            Recorder recorder
    ) {

        /** Retention of the trend ring in wall-clock milliseconds. */
        public long historyWindowMs() {
            return historyIntervalMs * historySize;
        }
    }

    /**
     * Periodic CSV capture of the orderbook fleet to local disk.
     *
     * <p>The in-memory trend ring is bounded and dies with the JVM, and it is fleet-aggregate only —
     * it holds nothing per-book. Long-horizon capacity work (how does level count grow over days,
     * what does heap do as it grows, is shard load balanced) needs a persisted per-book series, and
     * disk is the cheap resource: a day of capture is tens of megabytes.
     *
     * <p>Off by default. Turning it on costs one scheduled disk write per interval on the shared
     * scheduler pool — never the hot path.
     *
     * @param enabled          master switch; when false the recorder bean is not created at all
     * @param booksIntervalMs  how often to append every book's row. This is the bulky file (~65 KB
     *                         per write at ~900 books), so it runs on a slower cadence than the
     *                         fleet row
     * @param fleetIntervalMs  how often to append the one-line fleet row and the per-shard rows.
     *                         Cheap enough (~150 bytes) to run frequently, which is what gives the
     *                         heap-vs-levels regression its resolution
     * @param directory        output directory; created if absent. Files rotate daily
     * @param retentionDays    delete captured files older than this many days; {@code 0} disables
     *                         the sweep, which on a small disk is how you eventually fill it
     */
    public record Recorder(
            boolean enabled,
            long booksIntervalMs,
            long fleetIntervalMs,
            String directory,
            int retentionDays
    ) {}
}
