package dev.abu.screener_backend.binance.websocket;

import dev.abu.screener_backend.config.WebSocketProperties;
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

@Slf4j
public class BinanceStreamClient extends WebSocketClient {

    private final Market market;
    private final List<String> symbols;
    private final RawDepthMessageHandler handler;
    private final ScheduledExecutorService reconnectScheduler;
    private final WebSocketProperties props;

    private volatile boolean shuttingDown = false;
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private volatile ScheduledFuture<?> heartbeatTask;

    public BinanceStreamClient(
            URI serverUri,
            Market market,
            List<String> symbols,
            RawDepthMessageHandler handler,
            ScheduledExecutorService reconnectScheduler,
            WebSocketProperties props
    ) {
        super(serverUri);
        setConnectionLostTimeout(0);
        this.market = market;
        this.symbols = List.copyOf(symbols);
        this.handler = handler;
        this.reconnectScheduler = reconnectScheduler;
        this.props = props;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        reconnectAttempt.set(0);
        startHeartbeat();

        int chunkSize = props.subscribeChunkSize();
        int totalChunks = (symbols.size() + chunkSize - 1) / chunkSize;

        for (int i = 0; i < totalChunks; i++) {
            int from = i * chunkSize;
            int to = Math.min(from + chunkSize, symbols.size());
            List<String> chunk = symbols.subList(from, to);
            send(buildSubscribeFrame(chunk, i));
        }

        log.info("[{}] WebSocket opened — subscribing {} streams across {} frames",
                market, symbols.size(), totalChunks);
    }

    @Override
    public void onMessage(String message) {
        if (message.length() <= 4) return;
        // O(1) discrimination: SUBSCRIBE responses start with {"result":, depth events with {"e":
        if (message.charAt(2) == 'r') {
            log.debug("[{}] SUBSCRIBE ack received", market);
            return;
        }

        int sPos = message.indexOf("\"s\":\"");
        if (sPos == -1) return;
        int start = sPos + 5;
        int end = message.indexOf('"', start);
        if (end == -1) return;

        String symbol = message.substring(start, end).intern();
        handler.handle(symbol, market, message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        cancelHeartbeat();
        if (shuttingDown) return;

        long delay = Math.min(
                props.reconnectInitialDelayMs() * (1L << Math.min(reconnectAttempt.getAndIncrement(), 8)),
                props.reconnectMaxDelayMs()
        );

        log.warn("[{}] Connection closed (code={}, reason='{}', remote={}). Reconnecting in {}ms",
                market, code, reason, remote, delay);

        reconnectScheduler.schedule(this::reconnect, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onError(Exception ex) {
        log.warn("[{}] WebSocket error: {} — {}", market, ex.getClass().getSimpleName(), ex.getMessage());
    }

    public void shutdown() {
        shuttingDown = true;
        cancelHeartbeat();
        close();
    }

    private void startHeartbeat() {
        cancelHeartbeat();
        int intervalSeconds = props.heartbeatIntervalSeconds();
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
                log.debug("[{}] Heartbeat ping failed: {}", market, e.getMessage());
            }
        }
    }

    private String buildSubscribeFrame(List<String> chunk, int id) {
        List<String> params = new ArrayList<>(chunk.size());
        for (String symbol : chunk) {
            params.add("\"" + symbol.toLowerCase() + market.streamSuffix() + "\"");
        }
        return "{\"method\":\"SUBSCRIBE\",\"params\":[" + String.join(",", params) + "],\"id\":" + id + "}";
    }
}
