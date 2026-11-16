package dev.abu.screener_backend.config;

import dev.abu.screener_backend.config.ExchangesProperties.StreamProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.marketdata.StreamKind;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Builders for {@link VenueProperties} and {@link StreamProperties} with working defaults, so a test
 * names only the fields it cares about and a record change touches this file alone.
 *
 * <pre>{@code
 * VenueProperties v = TestVenueProps.venue().rest(rest).depth(TestVenueProps.stream().chunk(30)).build();
 * VenueProperties t = TestVenueProps.venue().stream(StreamKind.TRADE, TestVenueProps.stream().enabled(false)).build();
 * }</pre>
 */
public final class TestVenueProps {

    public static final RestProperties REST =
            new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));

    private TestVenueProps() {}

    /** Defaults: enabled, {@code wss://x}, 1024 per connection, 1..1 connections, chunk 400, heartbeat 120 s. */
    public static StreamBuilder stream() {
        return new StreamBuilder();
    }

    /** Defaults: enabled, {@link #REST}, one default {@code DEPTH} stream, no visible-distance cap. */
    public static VenueBuilder venue() {
        return new VenueBuilder();
    }

    public static final class StreamBuilder {
        private boolean enabled = true;
        private String url = "wss://x";
        private int maxStreamsPerConnection = 1024;
        private int minConnections = 1;
        private int maxConnections = 1;
        private int chunk = 400;
        private int heartbeatSeconds = 120;

        private StreamBuilder() {}

        public StreamBuilder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public StreamBuilder url(String url) {
            this.url = url;
            return this;
        }

        public StreamBuilder maxStreamsPerConnection(int maxStreamsPerConnection) {
            this.maxStreamsPerConnection = maxStreamsPerConnection;
            return this;
        }

        public StreamBuilder connections(int min, int max) {
            this.minConnections = min;
            this.maxConnections = max;
            return this;
        }

        public StreamBuilder chunk(int chunk) {
            this.chunk = chunk;
            return this;
        }

        public StreamBuilder heartbeatSeconds(int heartbeatSeconds) {
            this.heartbeatSeconds = heartbeatSeconds;
            return this;
        }

        public StreamProperties build() {
            return new StreamProperties(enabled, url, maxStreamsPerConnection, minConnections, maxConnections,
                    chunk, heartbeatSeconds);
        }
    }

    public static final class VenueBuilder {
        private boolean enabled = true;
        private RestProperties rest = REST;
        private final Map<StreamKind, StreamProperties> streams = new EnumMap<>(StreamKind.class);
        private Double maxVisibleDistance;

        private VenueBuilder() {
            streams.put(StreamKind.DEPTH, TestVenueProps.stream().build());
        }

        public VenueBuilder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public VenueBuilder rest(RestProperties rest) {
            this.rest = rest;
            return this;
        }

        public VenueBuilder depth(StreamBuilder depth) {
            return depth(depth.build());
        }

        public VenueBuilder depth(StreamProperties depth) {
            return stream(StreamKind.DEPTH, depth);
        }

        /** Adds or replaces the block for {@code kind}. */
        public VenueBuilder stream(StreamKind kind, StreamBuilder stream) {
            return stream(kind, stream.build());
        }

        public VenueBuilder stream(StreamKind kind, StreamProperties stream) {
            streams.put(kind, stream);
            return this;
        }

        /** Drops every stream block, including the default {@code DEPTH} one. */
        public VenueBuilder noStreams() {
            streams.clear();
            return this;
        }

        public VenueBuilder maxVisibleDistance(Double maxVisibleDistance) {
            this.maxVisibleDistance = maxVisibleDistance;
            return this;
        }

        public VenueProperties build() {
            return new VenueProperties(enabled, rest, streams, maxVisibleDistance);
        }
    }
}
