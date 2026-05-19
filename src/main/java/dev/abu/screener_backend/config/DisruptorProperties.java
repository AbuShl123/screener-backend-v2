package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "screener.disruptor")
public record DisruptorProperties(int shardCount, int ringBufferSize) {}
