package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;

/**
 * Resolves a venue to its {@link KlineDecoder}, once per instrument at slot allocation. Assembled
 * from every {@link VenueKlineDecoderBinding} bean, like {@link TradeDecoderRegistry}: a venue
 * without one is not an error, {@link #forVenue} just returns {@code null} and its slots carry no
 * candle state.
 */
@Component
public class KlineDecoderRegistry {

    private final EnumMap<Venue, KlineDecoder> byVenue = new EnumMap<>(Venue.class);

    public KlineDecoderRegistry(List<VenueKlineDecoderBinding> bindings) {
        for (VenueKlineDecoderBinding binding : bindings) {
            KlineDecoder previous = byVenue.put(binding.venue(), binding.decoder());
            if (previous != null) {
                throw new IllegalStateException(
                        "Two KlineDecoder bindings for venue " + binding.venue()
                                + " — check for a stray @Component alongside the adapter's @Bean");
            }
        }
    }

    /** No venue streams klines. For tests. */
    public static KlineDecoderRegistry empty() {
        return new KlineDecoderRegistry(List.of());
    }

    /** The venue's decoder, or {@code null} if the venue does not stream klines. */
    public KlineDecoder forVenue(Venue venue) {
        return byVenue.get(venue);
    }
}
