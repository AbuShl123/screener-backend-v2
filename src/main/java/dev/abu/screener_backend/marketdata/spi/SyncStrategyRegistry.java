package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;

/**
 * Resolves a venue to its {@link DepthSyncStrategy}, once per book at slot allocation.
 *
 * <p>The map is assembled from every {@link VenueStrategyBinding} bean on the classpath, so core
 * holds no reference to any adapter package. Adding an exchange contributes new bindings from its
 * own {@code adapter/<name>/} config and touches nothing here.
 *
 * <p>Cold path only — {@code BookSlotTable.allocate} calls this at discovery time and pins the
 * result into the {@code BookSlot}. The consumer thread reads {@code slot.strategy()} and never
 * walks {@code id → Instrument → Venue → strategy}.
 */
@Component
public class SyncStrategyRegistry {

    private final EnumMap<Venue, DepthSyncStrategy> byVenue = new EnumMap<>(Venue.class);

    public SyncStrategyRegistry(List<VenueStrategyBinding> bindings) {
        for (VenueStrategyBinding binding : bindings) {
            DepthSyncStrategy previous = byVenue.put(binding.venue(), binding.strategy());
            if (previous != null) {
                throw new IllegalStateException(
                        "Two DepthSyncStrategy beans bound to venue " + binding.venue()
                                + " — check for a stray @Component alongside the adapter's @Bean");
            }
        }
    }

    /**
     * @throws IllegalStateException if the venue has no adapter. An unmapped venue is a startup
     *         bug, not a runtime condition — a {@code null} here would surface much later as an NPE
     *         on the consumer thread.
     */
    public DepthSyncStrategy forVenue(Venue venue) {
        DepthSyncStrategy strategy = byVenue.get(venue);
        if (strategy == null) {
            throw new IllegalStateException(
                    "No DepthSyncStrategy bound for venue " + venue + " — its adapter config is missing");
        }
        return strategy;
    }
}
