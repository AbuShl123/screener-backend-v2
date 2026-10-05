package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Exchange-WebSocket reconnect timing.
 *
 * <p>Everything venue-shaped that used to live here — stream URLs, connection counts, subscribe
 * chunk size, and the heartbeat interval — moved to {@code screener.exchanges.<exchange>.venues.*}
 * (see {@link ExchangesProperties}); the heartbeat's <em>kind</em> (protocol ping vs. text frame) is
 * the venue's {@code StreamProtocol}'s call. Only reconnect backoff stays uniform across venues.
 *
 * @param reconnectInitialDelayMs base delay for the exponential reconnect backoff
 * @param reconnectMaxDelayMs     backoff ceiling
 */
@ConfigurationProperties(prefix = "screener.websocket")
public record WebSocketProperties(
        long reconnectInitialDelayMs,
        long reconnectMaxDelayMs
) {}
