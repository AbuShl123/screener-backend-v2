package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;

/**
 * Resolves a venue to its {@link TradeDecoder}, once per instrument at slot allocation.
 *
 * <p>Assembled from every {@link VenueTradeDecoderBinding} bean on the classpath, so core holds no
 * reference to any adapter package.
 *
 * <p>Unlike {@link SyncStrategyRegistry}, a missing venue is not an error: only some venues stream
 * trades. {@link #forVenue} returns {@code null} for them, and the slot carries no decoder. If a
 * {@code TRADE} stream is enabled for such a venue anyway, the consumer counts its events as
 * unhandled and logs a WARN once a minute.
 *
 * <p>Cold path only. The consumer thread reads {@code slot.tradeDecoder()}.
 */
@Component
public class TradeDecoderRegistry {

    private final EnumMap<Venue, TradeDecoder> byVenue = new EnumMap<>(Venue.class);

    public TradeDecoderRegistry(List<VenueTradeDecoderBinding> bindings) {
        for (VenueTradeDecoderBinding binding : bindings) {
            TradeDecoder previous = byVenue.put(binding.venue(), binding.decoder());
            if (previous != null) {
                throw new IllegalStateException(
                        "Two TradeDecoder bindings for venue " + binding.venue()
                                + " — check for a stray @Component alongside the adapter's @Bean");
            }
        }
    }

    /** The venue's decoder, or {@code null} if the venue does not stream trades. */
    public TradeDecoder forVenue(Venue venue) {
        return byVenue.get(venue);
    }
}
