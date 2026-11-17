package dev.abu.screener_backend.marketdata;

/**
 * Read-only discovery metadata for one {@link Venue}, as of the last completed refresh.
 *
 * @param status          outcome of the last discovery attempt
 * @param instrumentCount instruments in the venue's active universe (not every id ever registered)
 */
public record VenueDiscovery(DiscoveryStatus status, int instrumentCount) {

    /** A venue no discovery attempt has completed for. */
    public static final VenueDiscovery PENDING = new VenueDiscovery(DiscoveryStatus.PENDING, 0);
}
