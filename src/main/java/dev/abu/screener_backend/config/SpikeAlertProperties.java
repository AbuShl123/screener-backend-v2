package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Price and volume spike detection tunables ({@code screener.spike-alerts.*},
 * {@code .claude/plans/team-plans/spike-alerts-plan.md} D11–D15). The thresholds are first-cut
 * values, to be recalibrated against live data through {@code /api/monitoring/spike-alerts}.
 *
 * @param enabled         run the detectors at all. Candles are kept either way, so turning it on
 *                        needs no warm-up beyond a restart's.
 * @param deliveryEnabled push spikes to {@code /ws} clients; off = shadow mode, alerts are only
 *                        visible on the monitoring endpoint
 * @param recentCapacity  how many of the most recent alerts {@code SpikeAlertStore} keeps
 * @param replaySize      how many of the most recent alerts every {@code /ws} {@code SNAPSHOT}
 *                        replays ({@code SpikeChannel})
 * @param price           the price spike rule
 * @param volume          the volume spike rule
 */
@ConfigurationProperties(prefix = "screener.spike-alerts")
public record SpikeAlertProperties(
        boolean enabled,
        boolean deliveryEnabled,
        int recentCapacity,
        int replaySize,
        Price price,
        Volume volume
) {

    /** 1m candles: the only interval the detectors use. */
    public static final long CANDLE_INTERVAL_MS = 60_000;

    /**
     * A pump (or dump) is the current price against the lowest low (highest high) over the forming
     * candle and the {@code windowMinutes - 1} closed candles before it.
     *
     * @param windowMinutes  candles in the window, including the forming one
     * @param thresholdPct   minimum move in percent; also the escalation step (5 → alerts at 5%, 10%, …)
     * @param rearmMarginPct the direction re-arms once the move falls below
     *                       {@code thresholdPct - rearmMarginPct}
     */
    public record Price(int windowMinutes, double thresholdPct, double rearmMarginPct) {
        public Price {
            if (windowMinutes < 1) {
                throw new IllegalArgumentException(
                        "screener.spike-alerts.price.window-minutes must be >= 1, got " + windowMinutes);
            }
            if (!(thresholdPct > 0)) {
                throw new IllegalArgumentException(
                        "screener.spike-alerts.price.threshold-pct must be > 0, got " + thresholdPct);
            }
            if (!(rearmMarginPct >= 0) || rearmMarginPct >= thresholdPct) {
                throw new IllegalArgumentException("screener.spike-alerts.price.rearm-margin-pct must be in "
                        + "[0, threshold-pct), got " + rearmMarginPct);
            }
        }
    }

    /**
     * The quote volume of the last {@code windowMinutes} closed candles against the
     * {@code windowMinutes} before them. Both conditions must hold.
     *
     * @param windowMinutes  closed candles in each of the two windows
     * @param increasePct    minimum increase in percent (200 = 3×)
     * @param minIncreaseUsd minimum absolute increase in quote currency (USDT)
     */
    public record Volume(int windowMinutes, double increasePct, double minIncreaseUsd) {
        public Volume {
            if (windowMinutes < 1) {
                throw new IllegalArgumentException(
                        "screener.spike-alerts.volume.window-minutes must be >= 1, got " + windowMinutes);
            }
            if (!(increasePct > 0)) {
                throw new IllegalArgumentException(
                        "screener.spike-alerts.volume.increase-pct must be > 0, got " + increasePct);
            }
            if (!(minIncreaseUsd >= 0)) {
                throw new IllegalArgumentException(
                        "screener.spike-alerts.volume.min-increase-usd must be >= 0, got " + minIncreaseUsd);
            }
        }
    }

    public SpikeAlertProperties {
        if (recentCapacity <= 0) {
            throw new IllegalArgumentException(
                    "screener.spike-alerts.recent-capacity must be > 0, got " + recentCapacity);
        }
        if (replaySize <= 0) {
            throw new IllegalArgumentException(
                    "screener.spike-alerts.replay-size must be > 0, got " + replaySize);
        }
        if (price == null || volume == null) {
            throw new IllegalArgumentException("screener.spike-alerts.price and .volume must both be set");
        }
    }

    /** Closed candles each instrument keeps: enough for both windows. */
    public int candleRingSize() {
        return Math.max(2 * volume.windowMinutes(), Math.max(1, price.windowMinutes() - 1));
    }
}
