package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/**
 * Layered order-cluster detection tunables ({@code screener.clusters.*},
 * {@code .claude/plans/team-plans/US3-implementation-brief.md} §5 and §8). Every default in
 * {@code application.yml} is a first-cut proposal, to be recalibrated in the shadow-tuning week
 * ({@code deliveryEnabled = false}).
 *
 * @param enabled             run the detector at all; when off, no detector is built
 * @param deliveryEnabled     push clusters to {@code /ws} clients; off = shadow mode (BE-2)
 * @param throttleMillis      per instrument, at most one detection run per this many ms
 * @param maxDistance         a cluster must start within this fraction of mid (0.05 = 5%); also the
 *                            window the side's median level notional is measured in
 * @param minLevels           a cluster has at least this many levels
 * @param similarityTolerance a level joins a run while its notional is within this fraction of the
 *                            run's median notional (0.4 = ±40%)
 * @param gapFactor           a run breaks when the relative price step to the next level exceeds this
 *                            many times the run's median relative step
 * @param standoutRatio       a run's mean level notional must be at least this many times the side's
 *                            median level notional inside the window: keeps thick books quiet
 * @param exitFactor          hysteresis: a tracked cluster survives down to this fraction of the
 *                            enter threshold
 * @param minLifetimeMillis   a cluster is announced only after being present this long
 * @param updateThreshold     an announced cluster is re-emitted when its total moved by more than this
 *                            fraction (or its price bounds changed)
 * @param maxClustersPerSide  at most this many clusters per side per instrument, the largest by total
 * @param sameClusterOverlap  two runs are the same cluster, updated, when their price ranges overlap by
 *                            at least this fraction of the shorter range
 * @param maxClusterLevels    a run closes at this many levels: bounds the ladder a cluster carries on
 *                            the wire and the walk past {@code maxDistance}
 * @param minTotalNotional    the enter threshold per liquidity class
 */
@ConfigurationProperties(prefix = "screener.clusters")
public record ClusterDetectionProperties(
        boolean enabled,
        boolean deliveryEnabled,
        long throttleMillis,
        double maxDistance,
        int minLevels,
        double similarityTolerance,
        double gapFactor,
        double standoutRatio,
        double exitFactor,
        long minLifetimeMillis,
        double updateThreshold,
        int maxClustersPerSide,
        double sameClusterOverlap,
        int maxClusterLevels,
        MinTotalNotional minTotalNotional
) {

    /**
     * @param highLiquidity enter threshold for the majors ({@code DefaultClassificationRule.isHighLiquidity})
     * @param normal        enter threshold for everything else; bound from {@code default}
     */
    public record MinTotalNotional(double highLiquidity, @Name("default") double normal) {

        public MinTotalNotional {
            requirePositive("min-total-notional.high-liquidity", highLiquidity);
            requirePositive("min-total-notional.default", normal);
        }

        public double forClass(boolean highLiquidity) {
            return highLiquidity ? this.highLiquidity : normal;
        }
    }

    /**
     * Validated only when {@code enabled}: with detection off nothing reads the thresholds, and a
     * missing {@code screener.clusters} block binds to {@code enabled = false} with every other
     * value zero, which must not stop the application from starting.
     */
    public ClusterDetectionProperties {
        if (enabled) validate(throttleMillis, maxDistance, minLevels, similarityTolerance, gapFactor, standoutRatio,
                exitFactor, minLifetimeMillis, updateThreshold, maxClustersPerSide, sameClusterOverlap,
                maxClusterLevels, minTotalNotional);
    }

    @SuppressWarnings("java:S107")
    private static void validate(long throttleMillis, double maxDistance, int minLevels, double similarityTolerance,
                                 double gapFactor, double standoutRatio, double exitFactor, long minLifetimeMillis,
                                 double updateThreshold, int maxClustersPerSide, double sameClusterOverlap,
                                 int maxClusterLevels, MinTotalNotional minTotalNotional) {
        if (throttleMillis < 0) fail("throttle-millis", throttleMillis, ">= 0");
        if (!(maxDistance > 0)) fail("max-distance", maxDistance, "> 0");
        if (minLevels < 2) fail("min-levels", minLevels, ">= 2");
        if (!(similarityTolerance > 0)) fail("similarity-tolerance", similarityTolerance, "> 0");
        if (!(gapFactor >= 1)) fail("gap-factor", gapFactor, ">= 1");
        if (!(standoutRatio > 0)) fail("standout-ratio", standoutRatio, "> 0");
        if (!(exitFactor > 0 && exitFactor <= 1)) fail("exit-factor", exitFactor, "in (0, 1]");
        if (minLifetimeMillis < 0) fail("min-lifetime-millis", minLifetimeMillis, ">= 0");
        if (!(updateThreshold >= 0)) fail("update-threshold", updateThreshold, ">= 0");
        if (maxClustersPerSide < 1) fail("max-clusters-per-side", maxClustersPerSide, ">= 1");
        if (!(sameClusterOverlap > 0 && sameClusterOverlap <= 1)) {
            fail("same-cluster-overlap", sameClusterOverlap, "in (0, 1]");
        }
        if (maxClusterLevels < minLevels) fail("max-cluster-levels", maxClusterLevels, ">= min-levels");
        if (minTotalNotional == null) {
            throw new IllegalArgumentException("screener.clusters.min-total-notional is required");
        }
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0)) fail(name, value, "> 0");
    }

    private static void fail(String name, Object value, String rule) {
        throw new IllegalArgumentException("screener.clusters." + name + " must be " + rule + ", got " + value);
    }
}
