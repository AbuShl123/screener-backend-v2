package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.marketdata.StreamKey;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a stream to its {@link StreamProtocol}, once per connection pool.
 *
 * <p>The map is assembled from every {@link VenueStreamBinding} bean on the classpath, so core
 * holds no reference to any adapter package.
 *
 * <p>Unlike {@link SyncStrategyRegistry}, this also checks completeness at construction: every
 * enabled stream must have a binding. Without that check an enabled stream with no protocol would
 * only surface as an error log on the discovery thread, and the stream would never run. A binding
 * for a stream that is disabled or not configured is fine, so an adapter can ship a kind dark.
 */
@Component
public class StreamProtocolRegistry {

    private final Map<StreamKey, StreamProtocol> byStream = new HashMap<>();

    public StreamProtocolRegistry(List<VenueStreamBinding> bindings, ExchangesProperties exchanges) {
        for (VenueStreamBinding binding : bindings) {
            StreamProtocol previous = byStream.put(binding.stream(), binding.protocol());
            if (previous != null) {
                throw new IllegalStateException(
                        "Two StreamProtocol beans bound to stream " + binding.stream()
                                + " — check for a stray @Component alongside the adapter's @Bean");
            }
        }
        for (Venue venue : Venue.values()) {
            for (StreamKind kind : StreamKind.values()) {
                StreamKey key = new StreamKey(venue, kind);
                if (exchanges.isEnabled(key) && !byStream.containsKey(key)) {
                    throw new IllegalStateException("Stream " + key
                            + " is enabled but has no StreamProtocol bound — its adapter config is missing");
                }
            }
        }
    }

    /**
     * @throws IllegalStateException if the stream has no adapter — a startup bug, not a runtime
     *         condition
     */
    public StreamProtocol forStream(StreamKey stream) {
        StreamProtocol protocol = byStream.get(stream);
        if (protocol == null) {
            throw new IllegalStateException(
                    "No StreamProtocol bound for stream " + stream + " — its adapter config is missing");
        }
        return protocol;
    }
}
