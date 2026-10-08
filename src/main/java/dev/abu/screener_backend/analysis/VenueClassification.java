package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.analysis.filter.CompositeLevelFilter;
import dev.abu.screener_backend.analysis.filter.LevelFilter;
import dev.abu.screener_backend.analysis.filter.MinAgeFilter;
import dev.abu.screener_backend.analysis.filter.MmFingerprintFilter;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.FingerprintProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.LevelFilterProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * How {@link OrderBookClassifier} treats one venue's books beyond the tier rules, which are the same
 * on every venue. Applies to the default pass and every user pass alike.
 *
 * @param maxVisibleDistance fraction of mid beyond which levels are never classified; +∞ for no cap
 * @param filter             per-level condition checked before a level takes a top-K slot
 */
public record VenueClassification(double maxVisibleDistance, LevelFilter filter) {

    /** No cap, no filtering. */
    public static final VenueClassification NONE =
            new VenueClassification(Double.POSITIVE_INFINITY, LevelFilter.ACCEPT_ALL);

    public VenueClassification {
        if (!(maxVisibleDistance > 0)) {
            throw new IllegalArgumentException("maxVisibleDistance must be positive, got: " + maxVisibleDistance);
        }
        if (filter == null) throw new IllegalArgumentException("filter must not be null");
    }

    /** A visibility cap and no filtering. */
    public static VenueClassification capped(double maxVisibleDistance) {
        return new VenueClassification(maxVisibleDistance, LevelFilter.ACCEPT_ALL);
    }

    /** Builds a venue's classification from its config block. */
    public static VenueClassification of(VenueProperties venue) {
        Double cap = venue.maxVisibleDistance();
        return new VenueClassification(cap == null ? Double.POSITIVE_INFINITY : cap, filterOf(venue.levelFilter()));
    }

    /** The age check first: it is a single comparison, the fingerprint scans nearby levels. */
    private static LevelFilter filterOf(LevelFilterProperties props) {
        if (props == null) return LevelFilter.ACCEPT_ALL;
        List<LevelFilter> filters = new ArrayList<>(2);
        if (props.minAge() != null) filters.add(new MinAgeFilter(props.minAge()));
        FingerprintProperties fp = props.fingerprint();
        if (fp != null) {
            filters.add(new MmFingerprintFilter(fp.twinMaxDistance(), fp.twinQuantityTolerance(),
                    fp.mirrorNotionalTolerance(), fp.mirrorDistanceTolerance()));
        }
        return CompositeLevelFilter.allOf(filters);
    }
}
