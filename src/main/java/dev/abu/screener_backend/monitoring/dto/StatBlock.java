package dev.abu.screener_backend.monitoring.dto;

/**
 * Descriptive statistics over one numeric sample. Computed identically wherever it appears —
 * see {@code DescriptiveStats} for the pinned definition of every field.
 *
 * <p>Fields that are undefined for the sample size are {@code null}, never {@code 0} or
 * {@code NaN}: {@code variance}/{@code stdDev} need {@code n ≥ 2}, {@code skewness} needs
 * {@code n ≥ 3} and a non-zero spread, and everything but {@code n}/{@code sum} needs {@code n ≥ 1}.
 *
 * @param n         sample size
 * @param sum       Σx
 * @param mean      Σx / n
 * @param median    identical to {@code p50}, repeated because it is what callers ask for by name
 * @param stdDev    sample standard deviation (Bessel, {@code n-1})
 * @param variance  sample variance (Bessel, {@code n-1})
 * @param min       smallest observation
 * @param max       largest observation
 * @param range     {@code max - min}
 * @param p10       10th percentile (R-7)
 * @param p25       25th percentile (R-7)
 * @param p50       50th percentile (R-7)
 * @param p75       75th percentile (R-7)
 * @param p90       90th percentile (R-7)
 * @param p95       95th percentile (R-7)
 * @param iqr       {@code p75 - p25}
 * @param mad       median absolute deviation — outlier-proof spread; if {@code mad × 1.4826} is far
 *                  below {@code stdDev}, outliers are inflating σ
 * @param skewness  adjusted Fisher–Pearson G1; negative = long thin tail of <i>small</i> values
 * @param shape     human-readable bucketing of {@code skewness}
 */
public record StatBlock(
        int n,
        double sum,
        Double mean,
        Double median,
        Double stdDev,
        Double variance,
        Double min,
        Double max,
        Double range,
        Double p10,
        Double p25,
        Double p50,
        Double p75,
        Double p90,
        Double p95,
        Double iqr,
        Double mad,
        Double skewness,
        Shape shape
) {

    /** Coarse reading of the G1 skewness coefficient. */
    public enum Shape {
        SYMMETRIC,
        MODERATE_LEFT_SKEW,
        MODERATE_RIGHT_SKEW,
        HIGH_LEFT_SKEW,
        HIGH_RIGHT_SKEW;

        /** {@code null} in, {@code null} out — an undefined skewness has no shape. */
        public static Shape of(Double g1) {
            if (g1 == null) return null;
            double abs = Math.abs(g1);
            if (abs < 0.5) return SYMMETRIC;
            if (abs < 1.0) return g1 < 0 ? MODERATE_LEFT_SKEW : MODERATE_RIGHT_SKEW;
            return g1 < 0 ? HIGH_LEFT_SKEW : HIGH_RIGHT_SKEW;
        }
    }
}
