package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.config.TradeAlertProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * The minimum notional at which a sweep alerts, per instrument: the per-venue, per-native-symbol
 * override if one is configured, else the venue's own default, else the global default.
 *
 * <p>Looked up per sweep rather than copied into every slot: three map lookups at under a thousand
 * sweeps a second cost nothing, and per-user thresholds (Phase 6) can later layer onto this one
 * lookup. Immutable after construction, so the shard threads read it without locks.
 */
@Component
public class TradeThresholds {

    private final double defaultMinNotional;
    private final Map<Venue, Double> venueDefaults;
    private final Map<Venue, Map<String, Double>> overrides;

    public TradeThresholds(TradeAlertProperties props) {
        this.defaultMinNotional = props.defaultMinNotional();
        this.venueDefaults = props.venueDefaultMinNotional().isEmpty()
                ? new EnumMap<>(Venue.class)
                : new EnumMap<>(props.venueDefaultMinNotional());
        this.overrides = props.minNotional().isEmpty()
                ? new EnumMap<>(Venue.class)
                : new EnumMap<>(props.minNotional());
    }

    /**
     * <b>Hot path</b>: called for every judged sweep. Allocation-free: the maps hold their values
     * already boxed, and the symbol's hash is cached in the {@code String}.
     */
    public double minNotional(Instrument instrument) {
        Venue venue = instrument.venue();
        Map<String, Double> symbols = overrides.get(venue);
        if (symbols != null) {
            Double override = symbols.get(instrument.nativeSymbol());
            if (override != null) return override;
        }
        Double venueDefault = venueDefaults.get(venue);
        return venueDefault != null ? venueDefault : defaultMinNotional;
    }
}
