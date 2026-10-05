package dev.abu.screener_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

/**
 * Exchange-agnostic instrument-discovery tunables.
 *
 * <p>What an exchange's instruments must be to be tracked at all (quote asset, contract type,
 * status) is not here: each adapter hardcodes those filters in its {@code InstrumentSource},
 * because they define what the pipeline can handle rather than a tunable.
 *
 * @param sourceTimeout   how long one refresh waits for each {@code InstrumentSource}; a source that
 *                        overruns is interrupted and its venues keep their previous universe
 * @param excludedSymbols instruments never tracked on any exchange or market — stablecoin / metal
 *                        pairs whose books carry no signal. Written as {@code base + quote}
 *                        ({@code USDCUSDT}), the normalized spelling of {@code Instrument.symbol},
 *                        never an exchange's native form ({@code USDC_USDT} on MEXC). Applied by
 *                        {@code InstrumentUniverseService} to every source's candidates
 */
@ConfigurationProperties(prefix = "screener.discovery")
public record DiscoveryProperties(Duration sourceTimeout, Set<String> excludedSymbols) {

    public DiscoveryProperties {
        if (sourceTimeout == null) sourceTimeout = Duration.ofSeconds(30);
        excludedSymbols = excludedSymbols == null ? Set.of() : Set.copyOf(excludedSymbols);
    }
}
