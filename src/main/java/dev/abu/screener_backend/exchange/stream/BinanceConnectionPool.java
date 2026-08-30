package dev.abu.screener_backend.exchange.stream;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.exchange.Instrument;
import dev.abu.screener_backend.exchange.Venue;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
public class BinanceConnectionPool {

    private final Venue venue;
    private final VenueProperties venueProps;
    private final WebSocketProperties wsProps;
    private final RawDepthMessageHandler handler;

    private final List<BinanceStreamClient> clients = new ArrayList<>();
    private final ScheduledExecutorService reconnectScheduler;

    public BinanceConnectionPool(Venue venue,
                                 VenueProperties venueProps,
                                 WebSocketProperties wsProps,
                                 RawDepthMessageHandler handler) {
        this.venue = venue;
        this.venueProps = venueProps;
        this.wsProps = wsProps;
        this.handler = handler;
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "reconnect-" + venue.name().toLowerCase())
        );
    }

    public void start(List<Instrument> instruments) {
        if (instruments.isEmpty()) {
            log.warn("[{}] No instruments to subscribe — no connections opened", venue);
            return;
        }

        int connectionCount = connectionCount(instruments.size());
        log.info("[{}] Starting {} connection(s) for {} streams", venue, connectionCount, instruments.size());

        for (int i = 0; i < connectionCount; i++) {
            int from = i * instruments.size() / connectionCount;
            int to = (i + 1) * instruments.size() / connectionCount;
            List<Instrument> batch = instruments.subList(from, to);

            try {
                URI uri = new URI(venueProps.streamUrl());
                BinanceStreamClient client = new BinanceStreamClient(
                        uri, venue, batch, handler, reconnectScheduler, venueProps, wsProps);
                client.connect();
                clients.add(client);
            } catch (URISyntaxException e) {
                log.error("[{}] Invalid WebSocket URL: {}", venue, venueProps.streamUrl(), e);
            }
        }
    }

    /**
     * Derives the connection count from the stream count and the venue's own per-connection cap,
     * clamped into {@code [min-connections, max-connections]}.
     *
     * <p>The configured minimum is a <b>floor, not the authority</b>. With Binance's 1024-stream
     * ceiling and a few hundred streams per venue the ceiling term evaluates to 1, so today's
     * fan-out comes entirely from {@code min-connections} — which is why those values must stay at
     * the counts the pool used before this became derived. The ceiling term only starts dominating
     * at a venue with a small per-connection cap (some exchanges allow ~30), where a fixed
     * hand-picked count would badly under-provision.
     */
    private int connectionCount(int streamCount) {
        int perConnection = Math.max(1, venueProps.maxStreamsPerConnection());
        int required = (streamCount + perConnection - 1) / perConnection;
        return Math.min(Math.max(required, venueProps.minConnections()), venueProps.maxConnections());
    }

    public void shutdown() {
        clients.forEach(BinanceStreamClient::shutdown);
        reconnectScheduler.shutdownNow();
        log.info("[{}] Connection pool shut down", venue);
    }
}
