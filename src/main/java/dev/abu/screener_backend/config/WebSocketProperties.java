package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "screener.websocket")
public record WebSocketProperties(
        String spotStreamUrl,
        String futuresStreamUrl,
        int maxStreamsPerConnection,
        int subscribeChunkSize,
        long reconnectInitialDelayMs,
        long reconnectMaxDelayMs,
        int heartbeatIntervalSeconds
) {}
