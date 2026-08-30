package dev.abu.screener_backend.exchange.stream;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.exchange.Instrument;
import dev.abu.screener_backend.exchange.Venue;
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

@Slf4j
public class BinanceStreamClient extends WebSocketClient {

    private static final long UNKNOWN_SYMBOL_LOG_INTERVAL_MS = 60_000;

    private final Venue venue;
    private final List<Instrument> instruments;
    private final RawDepthMessageHandler handler;
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
    private final AtomicLong unknownSymbols = new AtomicLong();
    private final AtomicLong unknownSymbolsLoggedAt = new AtomicLong();

    public BinanceStreamClient(
            URI serverUri,
            Venue venue,
            List<Instrument> instruments,
            RawDepthMessageHandler handler,
            ScheduledExecutorService reconnectScheduler,
            VenueProperties venueProps,
            WebSocketProperties wsProps
    ) {
        super(serverUri);
        setConnectionLostTimeout(0);
        this.venue = venue;
        this.instruments = List.copyOf(instruments);
        this.handler = handler;
        this.reconnectScheduler = reconnectScheduler;
        this.venueProps = venueProps;
        this.wsProps = wsProps;
        this.index = new SubscriptionIndex(this.instruments);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        reconnectAttempt.set(0);
        startHeartbeat();

        int chunkSize = venueProps.subscribeChunkSize();
        int totalChunks = (instruments.size() + chunkSize - 1) / chunkSize;

        for (int i = 0; i < totalChunks; i++) {
            int from = i * chunkSize;
            int to = Math.min(from + chunkSize, instruments.size());
            List<Instrument> chunk = instruments.subList(from, to);
            send(buildSubscribeFrame(chunk, i));
        }

        log.info("[{}] WebSocket opened — subscribing {} streams across {} frames",
                venue, instruments.size(), totalChunks);
    }

    @Override
    public void onMessage(String message) {
        if (message.length() <= 4) return;
        // O(1) discrimination: SUBSCRIBE responses start with {"result":, depth events with {"e":
        if (message.charAt(2) == 'r') {
            log.debug("[{}] SUBSCRIBE ack received", venue);
            return;
        }

        int sPos = message.indexOf("\"s\":\"");
        if (sPos == -1) return;
        int start = sPos + 5;
        int end = message.indexOf('"', start);
        if (end == -1) return;

        int instrumentId = index.resolve(message, start, end);
        if (instrumentId < 0) {
            // Binance only pushes what was subscribed, so this should never fire; a partial
            // resubscribe after a reconnect is the one plausible source. Routing an unresolved
            // symbol onward would be exactly the mis-identification this design exists to prevent.
            noteUnknownSymbol(message.substring(start, end));
            return;
        }
        handler.handle(instrumentId, message);
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
        int intervalSeconds = wsProps.heartbeatIntervalSeconds();
        heartbeatTask = reconnectScheduler.scheduleAtFixedRate(
                this::sendHeartbeat, intervalSeconds, intervalSeconds, TimeUnit.SECONDS
        );
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    private void sendHeartbeat() {
        if (isOpen()) {
            try {
                sendPing();
            } catch (Exception e) {
                log.debug("[{}] Heartbeat ping failed: {}", venue, e.getMessage());
            }
        }
    }

    private void noteUnknownSymbol(String symbol) {
        long total = unknownSymbols.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = unknownSymbolsLoggedAt.get();
        if (now - last >= UNKNOWN_SYMBOL_LOG_INTERVAL_MS && unknownSymbolsLoggedAt.compareAndSet(last, now)) {
            log.warn("[{}] Depth frame for unsubscribed symbol {} — dropped ({} total)", venue, symbol, total);
        }
    }

    private String buildSubscribeFrame(List<Instrument> chunk, int id) {
        List<String> params = new ArrayList<>(chunk.size());
        for (Instrument instrument : chunk) {
            params.add("\"" + instrument.nativeSymbol().toLowerCase() + venueProps.depthStream() + "\"");
        }
        return "{\"method\":\"SUBSCRIBE\",\"params\":[" + String.join(",", params) + "],\"id\":" + id + "}";
    }
}
