package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Exchange-WebSocket connection timing.
 *
 * <p>Everything venue-shaped that used to live here — stream URLs, connection counts, subscribe
 * chunk size — moved to {@code screener.exchanges.binance.venues.*} (see {@link ExchangesProperties}).
 * What is left is transport timing that is currently uniform across venues.
 *
 * <p>Transitional: heartbeat behaviour is <em>not</em> uniform across exchanges (Binance uses a
 * protocol-level ping, others require an application-level JSON ping at a venue-specific interval),
 * so these fields belong with a per-venue stream protocol once that abstraction exists.
 *
 * @param reconnectInitialDelayMs  base delay for the exponential reconnect backoff
 * @param reconnectMaxDelayMs      backoff ceiling
 * @param heartbeatIntervalSeconds how often to ping, preventing a server-side idle close
 */
@ConfigurationProperties(prefix = "screener.websocket")
public record WebSocketProperties(
        long reconnectInitialDelayMs,
        long reconnectMaxDelayMs,
        int heartbeatIntervalSeconds
) {}
