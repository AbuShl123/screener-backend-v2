package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Exchange-agnostic instrument-discovery tunables. Inclusion policy is <b>not</b> here — it is
 * exchange-shaped and bound by each adapter under {@code screener.exchanges.<exchange>.discovery}.
 *
 * @param sourceTimeout how long one refresh waits for each {@code InstrumentSource}; a source that
 *                      overruns is interrupted and its venues keep their previous universe
 */
@ConfigurationProperties(prefix = "screener.discovery")
public record DiscoveryProperties(Duration sourceTimeout) {

    public DiscoveryProperties {
        if (sourceTimeout == null) sourceTimeout = Duration.ofSeconds(30);
    }
}
