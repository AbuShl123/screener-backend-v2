package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;

/**
 * Resolves a venue to its {@link StreamProtocol}, once per connection pool.
 *
 * <p>The map is assembled from every {@link VenueStreamBinding} bean on the classpath, so core
 * holds no reference to any adapter package.
 *
 * <p>Unlike {@link SyncStrategyRegistry}, this also checks completeness at construction: every
 * enabled venue must have a binding. Without that check an enabled venue with no protocol would
 * only surface as an error log on the discovery thread, and the venue would never stream.
 */
@Component
public class StreamProtocolRegistry {

    private final EnumMap<Venue, StreamProtocol> byVenue = new EnumMap<>(Venue.class);

    public StreamProtocolRegistry(List<VenueStreamBinding> bindings, ExchangesProperties exchanges) {
        for (VenueStreamBinding binding : bindings) {
            StreamProtocol previous = byVenue.put(binding.venue(), binding.protocol());
            if (previous != null) {
                throw new IllegalStateException(
                        "Two StreamProtocol beans bound to venue " + binding.venue()
                                + " — check for a stray @Component alongside the adapter's @Bean");
            }
        }
        for (Venue venue : Venue.values()) {
            if (exchanges.isEnabled(venue) && !byVenue.containsKey(venue)) {
                throw new IllegalStateException(
                        "Venue " + venue + " is enabled but has no StreamProtocol bound — its adapter config is missing");
            }
        }
    }

    /**
     * @throws IllegalStateException if the venue has no adapter — a startup bug, not a runtime
     *         condition
     */
    public StreamProtocol forVenue(Venue venue) {
        StreamProtocol protocol = byVenue.get(venue);
        if (protocol == null) {
            throw new IllegalStateException(
                    "No StreamProtocol bound for venue " + venue + " — its adapter config is missing");
        }
        return protocol;
    }
}
