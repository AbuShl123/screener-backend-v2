package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "screener.orderbook")
public record OrderbookProperties(
        double priceFilterThreshold,
        long spotSnapshotDispatchRateMs,
        long futuresSnapshotDispatchRateMs
) {}
