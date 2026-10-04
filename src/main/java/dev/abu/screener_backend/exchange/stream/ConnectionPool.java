package dev.abu.screener_backend.exchange.stream;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.exchange.Instrument;
import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.health.PipelineMetrics;
import dev.abu.screener_backend.exchange.ingress.DepthEventPublisher;
import dev.abu.screener_backend.exchange.spi.StreamProtocol;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * One venue's set of {@link StreamConnection}s, splitting its instruments evenly across them.
 * Non-{@code final} only so tests can record calls without opening sockets.
 */
@Slf4j
public class ConnectionPool {

    private final Venue venue;
    private final VenueProperties venueProps;
    private final WebSocketProperties wsProps;
    private final StreamProtocol protocol;
    private final DepthEventPublisher publisher;
    private final PipelineMetrics metrics;

    private final List<StreamConnection> connections = new ArrayList<>();
    private final ScheduledExecutorService reconnectScheduler;
    private boolean started;

    public ConnectionPool(Venue venue,
                          VenueProperties venueProps,
                          WebSocketProperties wsProps,
                          StreamProtocol protocol,
                          DepthEventPublisher publisher,
                          PipelineMetrics metrics) {
        this.venue = venue;
        this.venueProps = venueProps;
        this.wsProps = wsProps;
        this.protocol = protocol;
        this.publisher = publisher;
        this.metrics = metrics;
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "reconnect-" + venue.name().toLowerCase(Locale.ROOT))
        );
    }

    /**
     * Opens the pool's connections. Not idempotent — {@code StreamManager} calls it once per pool.
     *
     * @throws IllegalStateException on a second call
     */
    public void start(List<Instrument> instruments) {
        if (started) throw new IllegalStateException("Connection pool for " + venue + " already started");
        started = true;

        if (instruments.isEmpty()) {
            log.warn("[{}] No instruments to subscribe — no connections opened", venue);
            return;
        }

        int connectionCount = connectionCount(instruments.size());
        log.info("Starting {} connection(s) for {} streams in {}", connectionCount, instruments.size(), venue);

        for (int i = 0; i < connectionCount; i++) {
            int from = i * instruments.size() / connectionCount;
            int to = (i + 1) * instruments.size() / connectionCount;
            List<Instrument> batch = instruments.subList(from, to);

            try {
                URI uri = new URI(venueProps.streamUrl());
                StreamConnection connection = new StreamConnection(
                        uri, venue, batch, protocol, publisher, metrics, reconnectScheduler, venueProps, wsProps);
                connection.connect();
                connections.add(connection);
            } catch (URISyntaxException e) {
                log.error("[{}] Invalid WebSocket URL: {}", venue, venueProps.streamUrl(), e);
            }
        }
    }

    /**
     * Derives the connection count from the stream count and the venue's own per-connection cap,
     * clamped into {@code [min-connections, max-connections]}.
     *
     * <p>The configured minimum is a <b>floor, not the authority</b>. With a venue with a large
     * per-connection cap (Binance: 1024) and a few hundred streams per venue the ceiling term
     * evaluates to 1, so today's fan-out comes entirely from {@code min-connections} — which is why
     * those values must stay at the counts the pool used before this became derived. The ceiling
     * term only starts dominating at a venue with a small per-connection cap (some exchanges allow
     * ~30), where a fixed hand-picked count would badly under-provision.
     */
    private int connectionCount(int streamCount) {
        int perConnection = Math.max(1, venueProps.maxStreamsPerConnection());
        int required = (streamCount + perConnection - 1) / perConnection;
        return Math.min(Math.max(required, venueProps.minConnections()), venueProps.maxConnections());
    }

    public void shutdown() {
        connections.forEach(StreamConnection::shutdown);
        reconnectScheduler.shutdownNow();
        log.info("[{}] Connection pool shut down", venue);
    }
}
