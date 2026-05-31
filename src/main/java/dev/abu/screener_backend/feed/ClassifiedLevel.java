package dev.abu.screener_backend.feed;

/**
 * One classified price level delivered to clients.
 *
 * @param distance fractional distance from mid-price ({@code 0.05} = 5%), carried verbatim from
 *                 {@code PriceLevelEntry.distance}. The backend keeps this as a fraction
 *                 end-to-end; converting to a percent for display is the client's responsibility.
 */
public record ClassifiedLevel(
        double price,
        double quantity,
        int tier,
        long firstSeenMillis,
        double distance
) {}
