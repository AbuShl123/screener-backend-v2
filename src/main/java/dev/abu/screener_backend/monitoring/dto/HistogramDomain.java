package dev.abu.screener_backend.monitoring.dto;

/**
 * How far to the right the histogram's bucketed range extends.
 *
 * <p>Book size is heavily right-skewed: in a representative sample the body spanned an IQR of ~286
 * levels while the range reached ~13 900, a ratio of ~49. Freedman–Diaconis sizes bins from the IQR
 * (the body) but the bin <i>count</i> from the range (the tail), so covering the full range at body
 * resolution demanded ~234 bins. Clamping that to {@code [5, 100]} silently widened the bins instead
 * — the result was 100 buckets of which 80 were empty and the first two held 57 % of the fleet, a
 * chart with no usable information in it.
 *
 * <p>The fix is to bound the <b>domain</b> rather than inflate the bin width. Observations beyond the
 * chosen upper bound are not dropped and not crammed into the last bucket — they are reported
 * separately as {@link Histogram#overflowCount}, with the true maximum in
 * {@link Histogram#sampleMax}, so nothing is hidden and the buckets stay honest.
 */
public enum HistogramDomain {

    /**
     * Bucket up to the 99th percentile. <b>The default.</b> Retains virtually the whole distribution
     * while cutting the extreme tail that destroys the resolution, and lets Freedman–Diaconis pick a
     * bin count that fits under the cap on its own.
     */
    P99,

    /**
     * Bucket up to the upper Tukey fence ({@code p75 + 1.5·iqr}). A tighter view of the body; sends
     * every statistical outlier to the overflow, which can be a substantial fraction of the sample
     * (~8 % in the reference run). Use when the body's shape is the whole question.
     */
    FENCE,

    /**
     * Bucket the entire range, up to the sample maximum. Never overflows. This is the historical
     * behaviour and it is the right choice only for a distribution that is not heavy-tailed.
     */
    FULL
}
