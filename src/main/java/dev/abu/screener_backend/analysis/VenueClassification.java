package dev.abu.screener_backend.analysis;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;

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
        return cap == null ? NONE : capped(cap);
    }
}
