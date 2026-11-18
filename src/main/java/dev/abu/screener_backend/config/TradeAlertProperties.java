package dev.abu.screener_backend.config;

import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

/**
 * Large-trade detection tunables ({@code .claude/plans/trade-detection-plan.md} §2.5).
 *
 * <p>The thresholds ({@code default-min-notional}, {@code venue-default-min-notional},
 * {@code min-notional}) live in {@code trade-thresholds.yml}, which {@code application.yml} imports
 * into this same prefix; a {@code trade-thresholds.yml} in the working directory overrides the
 * bundled one per property. The behaviour settings stay in {@code application.yml}.
 *
 * @param defaultMinNotional      a sweep alerts when its notional, in quote currency (USDT on every
 *                                venue with trade alerts), is at least this. Applies to every
 *                                instrument without a venue default or a symbol override
 * @param venueDefaultMinNotional per-venue defaults ({@code MEXC_SPOT: 10000}); optional. Between the
 *                                symbol overrides and the global default ({@code trade-mexc-plan.md}
 *                                §2.8)
 * @param minNotional             per-venue, per-native-symbol overrides ({@code BTCUSDT} on
 *                                {@code BINANCE_FUTURES}); optional. An override for one venue does
 *                                not apply to the same symbol on another
 * @param recentCapacity     how many of the most recent alerts {@code TradeAlertStore} keeps
 * @param replaySize         how many of the most recent alerts every {@code /ws} {@code SNAPSHOT}
 *                           replays ({@code TradeChannel}); independent of {@code recentCapacity}
 * @param sweepIdleCloseMs   per venue, how long a sweep may wait for its next fill before it is
 *                           closed and judged, in ms of local processing time; optional. A venue
 *                           left out closes sweeps only on its next trade (or at the end of the frame,
 *                           where its decoder says so). Plan: {@code trade-mexc-plan.md} §2.6
 * @param tickIntervalMs     how often the shard tick checks every sweep for its idle close, in ms.
 *                           The tick runs only when {@code sweepIdleCloseMs} names a venue; the worst
 *                           close delay is about {@code sweepIdleCloseMs + tickIntervalMs}
 */
@ConfigurationProperties(prefix = "screener.trade-alerts")
public record TradeAlertProperties(
        double defaultMinNotional,
        Map<Venue, Double> venueDefaultMinNotional,
        Map<Venue, Map<String, Double>> minNotional,
        int recentCapacity,
        int replaySize,
        Map<Venue, Long> sweepIdleCloseMs,
        long tickIntervalMs
) {

    public TradeAlertProperties {
        if (!(defaultMinNotional > 0)) {
            throw new IllegalArgumentException(
                    "screener.trade-alerts.default-min-notional must be > 0, got " + defaultMinNotional);
        }
        if (recentCapacity <= 0) {
            throw new IllegalArgumentException(
                    "screener.trade-alerts.recent-capacity must be > 0, got " + recentCapacity);
        }
        if (replaySize <= 0) {
            throw new IllegalArgumentException(
                    "screener.trade-alerts.replay-size must be > 0, got " + replaySize);
        }
        Map<Venue, Double> venueDefaults = new EnumMap<>(Venue.class);
        if (venueDefaultMinNotional != null) {
            venueDefaultMinNotional.forEach((venue, value) -> {
                if (value == null || !(value > 0)) {
                    throw new IllegalArgumentException("screener.trade-alerts.venue-default-min-notional."
                            + venue + " must be > 0, got " + value);
                }
                venueDefaults.put(venue, value);
            });
        }
        venueDefaultMinNotional = Map.copyOf(venueDefaults);

        Map<Venue, Map<String, Double>> copy = new EnumMap<>(Venue.class);
        if (minNotional != null) {
            minNotional.forEach((venue, symbols) -> {
                if (symbols == null) return;
                symbols.forEach((symbol, value) -> {
                    if (symbol == null || symbol.isBlank() || value == null || !(value > 0)) {
                        throw new IllegalArgumentException("screener.trade-alerts.min-notional." + venue
                                + "." + symbol + " must be > 0, got " + value);
                    }
                });
                copy.put(venue, Map.copyOf(symbols));
            });
        }
        minNotional = Map.copyOf(copy);

        if (tickIntervalMs <= 0) {
            throw new IllegalArgumentException(
                    "screener.trade-alerts.tick-interval-ms must be > 0, got " + tickIntervalMs);
        }
        Map<Venue, Long> idle = new EnumMap<>(Venue.class);
        if (sweepIdleCloseMs != null) {
            sweepIdleCloseMs.forEach((venue, value) -> {
                if (value == null || value <= 0) {
                    throw new IllegalArgumentException("screener.trade-alerts.sweep-idle-close-ms." + venue
                            + " must be > 0, got " + value);
                }
                idle.put(venue, value);
            });
        }
        sweepIdleCloseMs = Map.copyOf(idle);
    }

    /** Whether any venue closes idle sweeps, and so whether the shard tick runs at all. */
    public boolean idleCloseEnabled() {
        return !sweepIdleCloseMs.isEmpty();
    }
}
