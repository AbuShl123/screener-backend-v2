package dev.abu.screener_backend.exchange.stream;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.exchange.Instrument;
import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.ingress.DepthEventPublisher;
import dev.abu.screener_backend.exchange.spi.Heartbeat;
import dev.abu.screener_backend.exchange.spi.StreamProtocol;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One WebSocket connection serving a fixed subset of one venue's instruments.
 *
 * <p>Owns lifecycle, reconnect backoff, subscribe chunking and heartbeat scheduling. Everything
 * about the venue's bytes — subscribe frames, frame routing, heartbeat kind — comes from its
 * {@link StreamProtocol}.
 */
@Slf4j
public class StreamConnection extends WebSocketClient {

    private static final long UNKNOWN_FRAME_LOG_INTERVAL_MS = 60_000;
    private static final int UNKNOWN_FRAME_LOG_CHARS = 120;

    private final Venue venue;
    private final List<Instrument> instruments;
    private final StreamProtocol protocol;
    private final DepthEventPublisher publisher;
    private final ScheduledExecutorService reconnectScheduler;
    private final VenueProperties venueProps;
    private final WebSocketProperties wsProps;

    /**
     * Built once in the constructor from this connection's own subscription list. Becomes a
     * {@code volatile} copy-on-write reference when dynamic subscribe/unsubscribe lands.
     */
    private final SubscriptionIndex index;

    private volatile boolean shuttingDown = false;
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private volatile ScheduledFuture<?> heartbeatTask;

    /** Should be permanently zero — see {@link #onMessage(String)}. */
    private final AtomicLong unknownFrames = new AtomicLong();
    private final AtomicLong unknownFramesLoggedAt = new AtomicLong();

    public StreamConnection(
            URI serverUri,
            Venue venue,
            List<Instrument> instruments,
            StreamProtocol protocol,
            DepthEventPublisher publisher,
            ScheduledExecutorService reconnectScheduler,
            VenueProperties venueProps,
            WebSocketProperties wsProps
    ) {
        super(serverUri);
        setConnectionLostTimeout(0);
        this.venue = venue;
        this.instruments = List.copyOf(instruments);
        this.protocol = protocol;
        this.publisher = publisher;
        this.reconnectScheduler = reconnectScheduler;
        this.venueProps = venueProps;
        this.wsProps = wsProps;
        this.index = new SubscriptionIndex(this.instruments, protocol::routingKey);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        reconnectAttempt.set(0);
        startHeartbeat();

        List<String> frames = subscribeFrames(protocol, instruments, venueProps.subscribeChunkSize());
        for (String frame : frames) {
            send(frame);
        }

        log.info("[{}] WebSocket opened — subscribing {} streams across {} frames",
                venue, instruments.size(), frames.size());
    }

    /** One frame per chunk of {@code chunkSize} instruments; the request id is the chunk's index. */
    static List<String> subscribeFrames(StreamProtocol protocol, List<Instrument> instruments, int chunkSize) {
        int totalChunks = (instruments.size() + chunkSize - 1) / chunkSize;
        List<String> frames = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            int from = i * chunkSize;
            int to = Math.min(from + chunkSize, instruments.size());
            frames.add(protocol.subscribeFrame(instruments.subList(from, to), i));
        }
        return frames;
    }

    @Override
    public void onMessage(String message) {
        int instrumentId = protocol.route(message, index);
        if (instrumentId >= 0) {
            publisher.publishFrame(instrumentId, message);
        } else if (instrumentId == StreamProtocol.UNKNOWN) {
            // Venues only push what was subscribed, so this should never fire; a partial
            // resubscribe after a reconnect is the one plausible source. Routing an unresolved
            // key onward would be exactly the mis-identification this design exists to prevent.
            noteUnknownFrame(message);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        cancelHeartbeat();
        if (shuttingDown) return;

        long delay = Math.min(
                wsProps.reconnectInitialDelayMs() * (1L << Math.min(reconnectAttempt.getAndIncrement(), 8)),
                wsProps.reconnectMaxDelayMs()
        );

        log.warn("[{}] Connection closed (code={}, reason='{}', remote={}). Reconnecting in {}ms",
                venue, code, reason, remote, delay);

        reconnectScheduler.schedule(this::reconnect, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onError(Exception ex) {
        log.warn("[{}] WebSocket error: {} — {}", venue, ex.getClass().getSimpleName(), ex.getMessage());
    }

    public void shutdown() {
        shuttingDown = true;
        cancelHeartbeat();
        close();
    }

    private void startHeartbeat() {
        cancelHeartbeat();
        Heartbeat heartbeat = protocol.heartbeat();
        long intervalMs = heartbeat.interval().toMillis();
        heartbeatTask = reconnectScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(heartbeat), intervalMs, intervalMs, TimeUnit.MILLISECONDS
        );
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    private void sendHeartbeat(Heartbeat heartbeat) {
        if (isOpen()) {
            try {
                switch (heartbeat) {
                    case Heartbeat.ProtocolPing p -> sendPing();
                    case Heartbeat.TextPing t -> send(t.payload());
                }
            } catch (Exception e) {
                log.debug("[{}] Heartbeat failed: {}", venue, e.getMessage());
            }
        }
    }

    private void noteUnknownFrame(String message) {
        long total = unknownFrames.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = unknownFramesLoggedAt.get();
        if (now - last >= UNKNOWN_FRAME_LOG_INTERVAL_MS && unknownFramesLoggedAt.compareAndSet(last, now)) {
            log.warn("[{}] Data frame for an unsubscribed routing key — dropped ({} total): {}",
                    venue, total, message.substring(0, Math.min(message.length(), UNKNOWN_FRAME_LOG_CHARS)));
        }
    }
}
