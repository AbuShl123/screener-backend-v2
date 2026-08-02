package dev.abu.screener_backend.monitoring.dto;

import java.util.List;

/**
 * Server-computed histogram of a sample. Buckets are half-open {@code [from, to)} except the last,
 * which is closed so an observation exactly equal to {@code upper} lands inside it.
 *
 * <p><b>Counts sum to {@code n} only together with {@code overflowCount}</b>:
 * {@code Σ buckets[i].count + overflowCount == n}. Unless {@code domain} is
 * {@link HistogramDomain#FULL}, {@code upper} is a chosen bound rather than the sample maximum, and
 * every observation above it is counted in {@code overflowCount} instead of being crammed into the
 * last bucket. See {@link HistogramDomain} for why the domain is bounded at all.
 *
 * <p>To plot: draw the buckets, then draw {@code overflowCount} as a single annotated off-scale bar
 * beyond {@code upper} — the reference Python does exactly this. Do not silently discard it;
 * {@code sampleMax} tells you how far the tail really goes.
 *
 * @param bins          number of buckets, clamped to {@code [5, 100]} (except the degenerate cases)
 * @param binWidth      {@code (upper - lower) / bins}
 * @param lower         sample minimum — the left edge of the first bucket; {@code null} for an empty sample
 * @param upper         right edge of the last bucket; equals {@code sampleMax} only when
 *                      {@code domain == FULL}. {@code null} for an empty sample
 * @param sampleMax     the true sample maximum, whether or not it was bucketed; {@code null} for an
 *                      empty sample
 * @param domain        which upper bound was applied
 * @param method        how {@code bins} was chosen
 * @param buckets       the buckets, ordered left to right
 * @param overflowCount observations strictly greater than {@code upper}, in no bucket
 */
public record Histogram(
        int bins,
        double binWidth,
        Double lower,
        Double upper,
        Double sampleMax,
        HistogramDomain domain,
        BinMethod method,
        List<Bucket> buckets,
        long overflowCount
) {

    /** How the bin count was arrived at. */
    public enum BinMethod {
        /** Caller passed an explicit {@code bins}. */
        EXPLICIT,
        /** {@code width = 2·iqr·n^(-1/3)} — the default. */
        FREEDMAN_DIACONIS,
        /** {@code ceil(log2(n)) + 1} — fallback when the IQR is zero. */
        STURGES,
        /** Every observation identical: one bucket. */
        SINGLE_VALUE,
        /** Empty sample: no buckets. */
        NONE
    }

    /**
     * @param from  left edge, inclusive
     * @param to    right edge, exclusive (inclusive in the last bucket)
     * @param count observations falling in this bucket
     */
    public record Bucket(double from, double to, long count) {}
}
