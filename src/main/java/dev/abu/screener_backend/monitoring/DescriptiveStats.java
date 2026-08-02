package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.monitoring.dto.Histogram;
import dev.abu.screener_backend.monitoring.dto.Histogram.BinMethod;
import dev.abu.screener_backend.monitoring.dto.Histogram.Bucket;
import dev.abu.screener_backend.monitoring.dto.HistogramDomain;
import dev.abu.screener_backend.monitoring.dto.StatBlock;
import dev.abu.screener_backend.monitoring.dto.StatBlock.Shape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure, dependency-free descriptive statistics. No Spring, no state, no I/O.
 *
 * <p>Every definition here is pinned to a named convention so the numbers can be reproduced
 * independently:
 * <ul>
 *   <li><b>percentiles</b> — R-7 / linear interpolation, the numpy {@code percentile} and Excel
 *       {@code PERCENTILE.INC} default: {@code h = (n-1)·p}, then
 *       {@code x[⌊h⌋] + (h-⌊h⌋)·(x[⌊h⌋+1] - x[⌊h⌋])}. {@code median} is exactly {@code p50}.</li>
 *   <li><b>variance / stdDev</b> — sample (Bessel, {@code n-1}); undefined for {@code n < 2}.</li>
 *   <li><b>mad</b> — {@code median(|xᵢ - median|)}. An outlier-proof spread: when
 *       {@code mad × 1.4826 ≪ stdDev}, outliers are inflating σ.</li>
 *   <li><b>skewness</b> — adjusted Fisher–Pearson G1,
 *       {@code n/((n-1)(n-2)) · Σ((xᵢ-x̄)/s)³} (equivalently scipy's {@code skew(bias=False)});
 *       undefined for {@code n < 3} or {@code s == 0}. Negative G1 = left skew = a long thin tail of
 *       <i>small</i> values.</li>
 *   <li><b>Tukey fences</b> — outlier outside {@code [p25 - 1.5·iqr, p75 + 1.5·iqr]}, extreme
 *       outside {@code [p25 - 3·iqr, p75 + 3·iqr]}.</li>
 *   <li><b>Freedman–Diaconis</b> — {@code width = 2·iqr·n^(-1/3)}, {@code bins = ceil(range/width)}
 *       clamped to {@code [5, 100]}; falls back to Sturges {@code ceil(log2(n)) + 1} when
 *       {@code iqr == 0}. {@code range} is measured over the histogram's <i>domain</i>, not the full
 *       sample — see {@link #histogram}.</li>
 * </ul>
 *
 * <p>Undefined statistics are returned as {@code null}, never as {@code 0} or {@code NaN} — an
 * undefined statistic must not be mistakable for a computed one.
 */
public final class DescriptiveStats {

    public static final int MIN_BINS = 5;
    public static final int MAX_BINS = 100;

    /** Tukey multiplier for a plain outlier. */
    public static final double OUTLIER_FACTOR = 1.5;
    /** Tukey multiplier for an extreme outlier. */
    public static final double EXTREME_FACTOR = 3.0;

    private DescriptiveStats() {}

    /**
     * Full stat block over a sample.
     *
     * @param values the sample; <b>sorted ascending in place</b> by this method
     */
    public static StatBlock of(double[] values) {
        Arrays.sort(values);
        return ofSorted(values);
    }

    /**
     * Full stat block over an already ascending-sorted sample.
     *
     * @param sorted ascending-sorted sample; not modified
     */
    public static StatBlock ofSorted(double[] sorted) {
        int n = sorted.length;
        if (n == 0) {
            return new StatBlock(0, 0.0,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null);
        }

        double sum = 0.0;
        for (double v : sorted) sum += v;
        double mean = sum / n;

        Double variance = null;
        Double stdDev = null;
        if (n >= 2) {
            double ss = 0.0;
            for (double v : sorted) {
                double d = v - mean;
                ss += d * d;
            }
            double var = ss / (n - 1);
            variance = round(var);
            stdDev = round(Math.sqrt(var));
        }

        double min = sorted[0];
        double max = sorted[n - 1];
        double p10 = percentileSorted(sorted, 0.10);
        double p25 = percentileSorted(sorted, 0.25);
        double p50 = percentileSorted(sorted, 0.50);
        double p75 = percentileSorted(sorted, 0.75);
        double p90 = percentileSorted(sorted, 0.90);
        double p95 = percentileSorted(sorted, 0.95);
        double iqr = p75 - p25;

        double mad = madSorted(sorted, p50);

        Double skewness = skewness(sorted, mean, n);
        Shape shape = Shape.of(skewness);

        return new StatBlock(
                n, round(sum),
                round(mean), round(p50),
                stdDev, variance,
                round(min), round(max), round(max - min),
                round(p10), round(p25), round(p50), round(p75), round(p90), round(p95),
                round(iqr), round(mad),
                skewness, shape);
    }

    /**
     * R-7 percentile of an ascending-sorted sample.
     *
     * @param sorted non-empty ascending-sorted sample
     * @param p      quantile in {@code [0, 1]}
     */
    public static double percentileSorted(double[] sorted, double p) {
        int n = sorted.length;
        if (n == 1) return sorted[0];
        double h = (n - 1) * p;
        int lo = (int) Math.floor(h);
        if (lo >= n - 1) return sorted[n - 1];
        double frac = h - lo;
        return sorted[lo] + frac * (sorted[lo + 1] - sorted[lo]);
    }

    /** {@code median(|xᵢ - median|)}. Allocates a second array — fine at monitoring cadence. */
    private static double madSorted(double[] sorted, double median) {
        double[] deviations = new double[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            deviations[i] = Math.abs(sorted[i] - median);
        }
        Arrays.sort(deviations);
        return percentileSorted(deviations, 0.50);
    }

    /** Adjusted Fisher–Pearson G1; {@code null} when undefined ({@code n < 3} or zero spread). */
    private static Double skewness(double[] values, double mean, int n) {
        if (n < 3) return null;

        double ss = 0.0;
        for (double v : values) {
            double d = v - mean;
            ss += d * d;
        }
        double s = Math.sqrt(ss / (n - 1));
        if (s == 0.0) return null;   // every value identical — skewness is undefined, not zero

        double m3 = 0.0;
        for (double v : values) {
            double z = (v - mean) / s;
            m3 += z * z * z;
        }
        double g1 = (double) n / ((n - 1L) * (n - 2L)) * m3;
        return round(g1);
    }

    /**
     * Bucket a sample into a histogram over a bounded domain.
     *
     * <p>Buckets are half-open {@code [from, to)} except the last, which is closed. Observations
     * above the domain's upper bound land in {@code overflowCount} rather than in a bucket, so
     * {@code Σ counts + overflowCount == n}.
     *
     * <p><b>Why the domain is bounded.</b> Freedman–Diaconis derives the bin <i>width</i> from the
     * IQR but the bin <i>count</i> from the range. On a heavily right-skewed sample those disagree
     * violently — at {@code range/iqr ≈ 49} it asks for ~234 bins — and clamping the count to
     * {@link #MAX_BINS} does not reduce the demand, it just inflates the width until the body of the
     * distribution collapses into two buckets. Bounding the domain instead lets FD's width stand and
     * reports the excluded tail explicitly. See {@link HistogramDomain}.
     *
     * @param sorted        ascending-sorted sample
     * @param requestedBins explicit bin count, or {@code 0} to choose by Freedman–Diaconis
     * @param iqr           the sample's IQR (already computed by the caller), or {@code null} if n == 0
     * @param domain        upper bound to bucket up to
     */
    public static Histogram histogram(double[] sorted, int requestedBins, Double iqr,
                                      HistogramDomain domain) {
        int n = sorted.length;
        if (n == 0) {
            return new Histogram(0, 0.0, null, null, null, domain, BinMethod.NONE, List.of(), 0L);
        }

        double lower = sorted[0];
        double sampleMax = sorted[n - 1];
        double upper = domainUpper(sorted, iqr, domain, lower, sampleMax);
        double range = upper - lower;

        if (range == 0.0) {
            // Degenerate: every observation identical, or the domain bound collapsed onto the
            // minimum. One bucket is the honest answer; anything above it is overflow.
            long inBucket = countAtMost(sorted, upper);
            return new Histogram(1, 0.0, round(lower), round(upper), round(sampleMax),
                    domain, BinMethod.SINGLE_VALUE,
                    List.of(new Bucket(round(lower), round(upper), inBucket)), n - inBucket);
        }

        int bins;
        BinMethod method;
        if (requestedBins > 0) {
            bins = clamp(requestedBins, MIN_BINS, MAX_BINS);
            method = BinMethod.EXPLICIT;
        } else if (iqr != null && iqr > 0.0) {
            double width = 2.0 * iqr * Math.pow(n, -1.0 / 3.0);
            bins = clamp((int) Math.ceil(range / width), MIN_BINS, MAX_BINS);
            method = BinMethod.FREEDMAN_DIACONIS;
        } else {
            bins = clamp((int) Math.ceil(log2(n)) + 1, MIN_BINS, MAX_BINS);
            method = BinMethod.STURGES;
        }

        double binWidth = range / bins;
        long[] counts = new long[bins];
        long overflow = 0L;
        for (double v : sorted) {
            if (v > upper) {                      // sorted ascending — everything after this is too
                overflow++;
                continue;
            }
            int idx = (int) ((v - lower) / binWidth);
            if (idx >= bins) idx = bins - 1;      // v == upper belongs to the last bucket
            if (idx < 0) idx = 0;
            counts[idx]++;
        }

        List<Bucket> buckets = new ArrayList<>(bins);
        for (int i = 0; i < bins; i++) {
            double from = lower + i * binWidth;
            double to = (i == bins - 1) ? upper : lower + (i + 1) * binWidth;
            buckets.add(new Bucket(round(from), round(to), counts[i]));
        }

        return new Histogram(bins, round(binWidth), round(lower), round(upper), round(sampleMax),
                domain, method, buckets, overflow);
    }

    /**
     * The right edge of the bucketed range for a domain choice. Never returns less than the sample
     * minimum (a degenerate sample can push a percentile or a fence below it) and never more than the
     * sample maximum (bucketing empty space to the right is exactly the problem being solved).
     */
    private static double domainUpper(double[] sorted, Double iqr, HistogramDomain domain,
                                      double lower, double sampleMax) {
        double bound = switch (domain) {
            case FULL -> sampleMax;
            case P99 -> percentileSorted(sorted, 0.99);
            case FENCE -> iqr == null || iqr <= 0.0
                    ? sampleMax
                    : percentileSorted(sorted, 0.75) + OUTLIER_FACTOR * iqr;
        };
        return Math.min(sampleMax, Math.max(lower, bound));
    }

    /** Count of observations {@code <= bound} in an ascending-sorted sample. */
    private static long countAtMost(double[] sorted, double bound) {
        long count = 0;
        for (double v : sorted) {
            if (v > bound) break;
            count++;
        }
        return count;
    }

    /** Round to 4 decimals; keeps the JSON readable without pretending to precision we lack. */
    public static double round(double v) {
        if (!Double.isFinite(v)) return v;
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    /** Null-tolerant {@link #round(double)}. */
    public static Double round(Double v) {
        return v == null ? null : round((double) v);
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double log2(int n) {
        return Math.log(n) / Math.log(2);
    }
}
