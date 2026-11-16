package dev.abu.screener_backend.config;

import dev.abu.screener_backend.marketdata.Exchange;
import dev.abu.screener_backend.marketdata.Market;
import dev.abu.screener_backend.marketdata.StreamKey;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.Venue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Binds {@code screener.exchanges.*} — the venue-dimensioned transport config and the
 * {@code enabled} switches: one master switch per exchange, one per venue.
 *
 * <p>The prefix is {@code screener} rather than {@code screener.exchanges} so that the single
 * {@code exchanges} component binds as a {@link Map} keyed by {@link Exchange}; Spring's relaxed
 * binding maps the YAML key {@code binance} onto {@code Exchange.BINANCE}, and {@code SPOT} /
 * {@code FUTURES} onto {@link Market}.
 *
 * <p>An exchange's {@code snapshot-queue} block shapes core's snapshot request queue, one per
 * REST-recovering venue of that exchange. It is required only by an adapter that builds such a
 * queue. How a venue prices and paces those requests (weights, bans) is exchange-shaped: the adapter
 * binds it from the venue block itself, e.g. {@code BinanceSnapshotProperties} reads
 * {@code venues.<market>.snapshot}, which this record ignores.
 *
 * <p>Transport settings are per stream kind, under {@code venues.<market>.streams.<KIND>}. The
 * compact constructor is where the cross-block check lives: <b>an enabled venue must have at least
 * one enabled stream</b>. Spring ignores unknown keys, so a config file still written in the old
 * flat shape (transport keys directly under the venue) would otherwise bind a venue with no streams
 * that goes silently dark.
 */
@ConfigurationProperties(prefix = "screener")
public record ExchangesProperties(Map<Exchange, ExchangeProperties> exchanges) {

    public ExchangesProperties {
        if (exchanges != null) {
            exchanges.forEach(ExchangesProperties::requireStreamPerEnabledVenue);
        }
    }

    private static void requireStreamPerEnabledVenue(Exchange exchange, ExchangeProperties props) {
        if (props == null || !props.enabled() || props.venues() == null) return;
        props.venues().forEach((market, venueProps) -> {
            if (venueProps == null || !venueProps.enabled()) return;
            boolean anyStreamOn = venueProps.streams().values().stream().anyMatch(StreamProperties::enabled);
            if (!anyStreamOn) {
                String path = "screener.exchanges." + exchange.name().toLowerCase() + ".venues." + market.name();
                throw new IllegalArgumentException(path + " is enabled but has no enabled stream. Transport"
                        + " settings (stream-url, connection caps, subscribe-chunk-size, heartbeat) live under "
                        + path + ".streams.DEPTH, not directly under the venue; or set enabled: false");
            }
        });
    }

    /** @throws IllegalStateException if the exchange has no configuration block */
    public ExchangeProperties exchange(Exchange exchange) {
        ExchangeProperties props = exchanges == null ? null : exchanges.get(exchange);
        if (props == null) {
            throw new IllegalStateException("Missing configuration for screener.exchanges."
                    + exchange.name().toLowerCase());
        }
        return props;
    }

    /**
     * The single chokepoint for the {@code enabled} switches. The exchange's switch is the master:
     * when it is off, every venue of the exchange is off whatever its own switch says; when it is
     * on, each venue's own switch decides.
     *
     * <p>Unlike {@link #exchange} and {@link #venue}, a missing block does not throw: it means
     * disabled. That lets a {@link Venue} constant exist before its adapter has YAML.
     *
     * @return {@code false} when the venue's exchange is disabled or has no configuration block,
     *         the venue itself has no block under {@code venues}, or the venue's own switch is off.
     *         An enabled venue always has at least one enabled stream (checked at bind time)
     */
    public boolean isEnabled(Venue venue) {
        ExchangeProperties props = exchanges == null ? null : exchanges.get(venue.exchange());
        if (props == null || !props.enabled() || props.venues() == null) return false;
        VenueProperties venueProps = props.venues().get(venue.market());
        return venueProps != null && venueProps.enabled();
    }

    /**
     * The single chokepoint for whether a stream runs: its venue is on ({@link #isEnabled(Venue)}),
     * it has a {@code streams.<KIND>} block, and that block's own switch is on. Like
     * {@link #isEnabled(Venue)}, a missing block means disabled rather than an error.
     */
    public boolean isEnabled(StreamKey key) {
        if (!isEnabled(key.venue())) return false;
        StreamProperties props = venue(key.venue()).streams().get(key.kind());
        return props != null && props.enabled();
    }

    /** @throws IllegalStateException if the venue or its {@code streams.<KIND>} block is missing */
    public StreamProperties stream(StreamKey key) {
        StreamProperties props = venue(key.venue()).streams().get(key.kind());
        if (props == null) {
            throw new IllegalStateException("Missing configuration for stream " + key.logName()
                    + " (screener.exchanges." + key.venue().exchange().name().toLowerCase()
                    + ".venues." + key.venue().market().name() + ".streams." + key.kind().name() + ")");
        }
        return props;
    }

    /** @throws IllegalStateException if the venue has no configuration block */
    public VenueProperties venue(Venue venue) {
        ExchangeProperties exchangeProps = exchange(venue.exchange());
        VenueProperties props = exchangeProps.venues() == null
                ? null
                : exchangeProps.venues().get(venue.market());
        if (props == null) {
            throw new IllegalStateException("Missing configuration for venue " + venue);
        }
        return props;
    }

    /**
     * {@link VenueProperties#maxVisibleDistance} of every enabled venue that sets one. A venue
     * missing from the map has no cap.
     */
    public Map<Venue, Double> maxVisibleDistances() {
        Map<Venue, Double> caps = new EnumMap<>(Venue.class);
        for (Venue v : Venue.values()) {
            if (!isEnabled(v)) continue;
            Double cap = venue(v).maxVisibleDistance();
            if (cap != null) caps.put(v, cap);
        }
        return caps;
    }

    /**
     * Instrument-inclusion policy is not configured per exchange: each adapter's eligibility filters
     * are hardcoded in its {@code InstrumentSource}, and the exclusion list is the exchange-agnostic
     * {@code screener.discovery.excluded-symbols} ({@link DiscoveryProperties}).
     *
     * @param enabled       master switch, for safe rollout — an adapter can ship dark and be turned
     *                      on independently. Off overrides every venue's own
     *                      {@link VenueProperties#enabled}. Read through
     *                      {@link ExchangesProperties#isEnabled}
     * @param venues        per-market config, each with its per-stream transport blocks
     * @param snapshotQueue core snapshot request queue shape, shared by the exchange's venues;
     *                      {@code null} when none of them recovers from REST snapshots
     */
    public record ExchangeProperties(
            boolean enabled,
            Map<Market, VenueProperties> venues,
            SnapshotQueueProperties snapshotQueue
    ) {}

    /**
     * Core's {@code SnapshotRequestQueue}. How a venue paces a batch is the adapter's business; this
     * only shapes the queue.
     *
     * @param maxBatchSize  most requests one batch holds — and so the cap on books buffering diffs
     *                      at once
     * @param flushInterval how often the queue drains its pending requests as one batch
     * @param batchTimeout  safety net for a fetcher that never completes its batch: unreported slots
     *                      are failed and the queue reopens. Must exceed each venue's
     *                      {@code rest.response-timeout}, which bounds a single request — checked by
     *                      {@code SnapshotQueueFactory}
     */
    public record SnapshotQueueProperties(int maxBatchSize, Duration flushInterval, Duration batchTimeout) {
        public SnapshotQueueProperties {
            if (maxBatchSize <= 0) throw new IllegalArgumentException("snapshot-queue.max-batch-size must be > 0");
            if (flushInterval == null || !flushInterval.isPositive()) {
                throw new IllegalArgumentException("snapshot-queue.flush-interval must be positive");
            }
            if (batchTimeout == null || !batchTimeout.isPositive()) {
                throw new IllegalArgumentException("snapshot-queue.batch-timeout must be positive");
            }
        }
    }

    /**
     * A disabled venue keeps its block: its adapter's discovery may still read the {@code rest}
     * config (a source fetches all its venues, enabled or not), so a venue is turned off with
     * {@code enabled: false}, never by deleting the block.
     *
     * <p>Venue-wide settings live here; transport settings live per stream kind under
     * {@code streams.<KIND>} ({@link StreamProperties}). An adapter may also bind its own
     * venue-wide blocks, such as {@code snapshot}, which this record ignores.
     *
     * @param enabled            venue switch, defaulting to on; only consulted when the exchange's
     *                           master switch is on. Read through {@link ExchangesProperties#isEnabled}
     * @param rest               REST client config for this venue (base URL, codec buffer, timeouts)
     * @param streams            transport config per stream kind, keyed by the upper-case kind name
     *                           ({@code DEPTH}). An enabled venue needs at least one enabled stream —
     *                           checked by {@link ExchangesProperties}
     * @param maxVisibleDistance fraction of mid beyond which this venue's levels are never
     *                           classified, by the default rule or any user rule; {@code null}
     *                           means no cap. The book still keeps levels out to
     *                           {@code screener.orderbook.price-filter-threshold}: that bound
     *                           deletes levels, and a deleted level that drifts back in range is
     *                           missing until the venue re-sends it, so it must stay wide
     */
    public record VenueProperties(
            @DefaultValue("true") boolean enabled,
            RestProperties rest,
            Map<StreamKind, StreamProperties> streams,
            Double maxVisibleDistance
    ) {
        public VenueProperties {
            streams = streams == null ? Map.of() : Map.copyOf(streams);
            if (maxVisibleDistance != null && !(maxVisibleDistance > 0 && Double.isFinite(maxVisibleDistance))) {
                throw new IllegalArgumentException("max-visible-distance must be positive and finite, got: "
                        + maxVisibleDistance);
            }
        }

        /**
         * @param baseUrl            REST base URL for this venue
         * @param codecBufferSizeMb  in-memory codec buffer for the WebClient, in megabytes — Binance's
         *                           {@code /exchangeInfo} alone needs more than the 256 KB default
         * @param connectTimeout     TCP connect timeout
         * @param responseTimeout    reactor-netty network response timeout ({@code HttpClient.responseTimeout});
         *                           deliberately not a {@code Mono.timeout}, which would also count time a
         *                           request spends waiting on a request budget
         */
        public record RestProperties(
                String baseUrl,
                int codecBufferSizeMb,
                Duration connectTimeout,
                Duration responseTimeout
        ) {}
    }

    /**
     * Transport config of one stream kind on one venue — {@code venues.<market>.streams.<KIND>}.
     * One connection pool serves it, and every connection in that pool subscribes this one channel.
     *
     * @param enabled                  stream switch, defaulting to on; only consulted when the venue
     *                                 is on. Read through {@link ExchangesProperties#isEnabled(StreamKey)}
     * @param streamUrl                WebSocket endpoint for this stream
     * @param maxStreamsPerConnection  the venue's per-connection subscription ceiling for this channel
     * @param minConnections           floor on the derived connection count. With Binance's 1024-stream
     *                                 ceiling the derived term is 1, so this floor is what actually
     *                                 sets the fan-out — see {@code ConnectionPool}
     * @param maxConnections           ceiling on the derived connection count
     * @param subscribeChunkSize       instruments per subscribe frame (unrelated to connection count)
     * @param heartbeatIntervalSeconds how often a connection pings, preventing a server-side idle close
     */
    public record StreamProperties(
            @DefaultValue("true") boolean enabled,
            String streamUrl,
            int maxStreamsPerConnection,
            int minConnections,
            int maxConnections,
            int subscribeChunkSize,
            int heartbeatIntervalSeconds
    ) {
        public StreamProperties {
            if (subscribeChunkSize <= 0) throw new IllegalArgumentException("subscribe-chunk-size must be > 0");
            if (heartbeatIntervalSeconds <= 0) throw new IllegalArgumentException("heartbeat-interval-seconds must be > 0");
        }
    }
}
