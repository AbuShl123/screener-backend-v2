package dev.abu.screener_backend.config;

import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

/**
 * Large-trade detection tunables ({@code .claude/plans/trade-detection-plan.md} §2.5).
 *
 * @param defaultMinNotional a trade (slice 3: a sweep) alerts when its notional, in quote currency
 *                           (USDT on Binance), is at least this. Applies to every instrument without
 *                           an override
 * @param minNotional        per-venue, per-native-symbol overrides of the default ({@code BTCUSDT} on
 *                           {@code BINANCE_FUTURES}); optional. An override for one venue does not
 *                           apply to the same symbol on another
 * @param recentCapacity     how many of the most recent alerts {@code TradeAlertStore} keeps
 * @param replaySize         how many of the most recent alerts every {@code /ws} {@code SNAPSHOT}
 *                           replays ({@code TradeChannel}); independent of {@code recentCapacity}
 */
@ConfigurationProperties(prefix = "screener.trade-alerts")
public record TradeAlertProperties(
        double defaultMinNotional,
        Map<Venue, Map<String, Double>> minNotional,
        int recentCapacity,
        int replaySize
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
    }
}
