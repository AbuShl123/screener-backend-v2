package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param priceFilterThreshold levels further than this fraction from mid are dropped
 * @param ageResetGrowth       a level's {@code firstSeenMillis} resets when its quantity reaches this
 *                             multiple of the lowest quantity it has had since the last reset
 */
@ConfigurationProperties(prefix = "screener.orderbook")
public record OrderbookProperties(
        double priceFilterThreshold,
        double ageResetGrowth
) {
    public OrderbookProperties {
        if (!(ageResetGrowth > 1.0)) throw new IllegalArgumentException("age-reset-growth must be > 1");
    }
}
