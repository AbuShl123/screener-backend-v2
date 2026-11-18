package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * The minimum notional at which a trade alerts, per instrument: a per-venue, per-native-symbol
 * override if one is configured, the default otherwise.
 *
 * <p>Looked up per trade rather than copied into every slot: two map lookups at under a thousand
 * trades a second cost nothing, and per-user thresholds (Phase 6) can later layer onto this one
 * lookup. Immutable after construction, so the shard threads read it without locks.
 */
@Component
public class TradeThresholds {

    private final double defaultMinNotional;
    private final Map<Venue, Map<String, Double>> overrides;

    public TradeThresholds(TradeAlertProperties props) {
        this.defaultMinNotional = props.defaultMinNotional();
        this.overrides = props.minNotional().isEmpty()
                ? new EnumMap<>(Venue.class)
                : new EnumMap<>(props.minNotional());
    }

    /**
     * <b>Hot path</b>: called for every decoded trade. Allocation-free: the override map holds its
     * values already boxed, and the symbol's hash is cached in the {@code String}.
     */
    public double minNotional(Instrument instrument) {
        Map<String, Double> venue = overrides.get(instrument.venue());
        if (venue != null) {
            Double override = venue.get(instrument.nativeSymbol());
            if (override != null) return override;
        }
        return defaultMinNotional;
    }
}
