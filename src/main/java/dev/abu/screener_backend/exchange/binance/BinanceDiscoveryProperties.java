package dev.abu.screener_backend.exchange.binance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Binance's instrument-universe inclusion policy, applied by {@link BinanceInstrumentSource}.
 *
 * <p>Adapter-owned: registered by {@link BinanceAdapterConfig}, not by core config, so nothing
 * outside {@code exchange/binance/} names Binance's policy shape.
 *
 * @param quoteAsset          only pairs quoted in this asset are tracked
 * @param futuresContractType futures contract type to accept, e.g. {@code PERPETUAL}
 * @param spotRequiresFutures when {@code true}, a spot symbol is tracked only if the same symbol
 *                            has an eligible futures contract. Flipping it to {@code false} takes
 *                            spot from "futures ∩ spot" to every quoted spot pair, a large load
 *                            change that belongs in its own phase
 * @param excludedSymbols     stablecoin / metal pairs whose books carry no signal, in native form
 */
@ConfigurationProperties(prefix = "screener.exchanges.binance.discovery")
public record BinanceDiscoveryProperties(
        String quoteAsset,
        String futuresContractType,
        boolean spotRequiresFutures,
        Set<String> excludedSymbols
) {}
