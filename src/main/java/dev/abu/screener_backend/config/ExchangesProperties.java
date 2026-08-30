package dev.abu.screener_backend.config;

import dev.abu.screener_backend.exchange.Exchange;
import dev.abu.screener_backend.exchange.Market;
import dev.abu.screener_backend.exchange.Venue;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;

/**
 * Binds {@code screener.exchanges.*} — the venue-dimensioned transport and discovery config.
 *
 * <p>The prefix is {@code screener} rather than {@code screener.exchanges} so that the single
 * {@code exchanges} component binds as a {@link Map} keyed by {@link Exchange}; Spring's relaxed
 * binding maps the YAML key {@code binance} onto {@code Exchange.BINANCE}, and {@code SPOT} /
 * {@code FUTURES} onto {@link Market}.
 *
 * <p>Deliberately <b>not</b> present: {@code budget: {type: …}} and {@code snapshot: {mode: …}}
 * discriminators. Those select between implementations that do not exist until the sync SPI lands,
 * and binding config for absent abstractions guarantees a rewrite.
 */
@ConfigurationProperties(prefix = "screener")
public record ExchangesProperties(Map<Exchange, ExchangeProperties> exchanges) {

    /** @throws IllegalStateException if the exchange has no configuration block */
    public ExchangeProperties exchange(Exchange exchange) {
        ExchangeProperties props = exchanges == null ? null : exchanges.get(exchange);
        if (props == null) {
            throw new IllegalStateException("Missing configuration for screener.exchanges."
                    + exchange.name().toLowerCase());
        }
        return props;
    }

    /** @throws IllegalStateException if the venue has no configuration block */
    public VenueProperties venue(Venue venue) {
        ExchangeProperties exchangeProps = exchange(venue.exchange());
        VenueProperties props = exchangeProps.venues() == null
                ? null
                : exchangeProps.venues().get(venue.market());
        if (props == null) {
            throw new IllegalStateException("Missing configuration for venue " + venue);
        }
        return props;
    }

    /**
     * @param enabled   safe-rollout switch — an adapter can ship dark and be turned on independently
     * @param rest      REST client tuning shared across the exchange's venues
     * @param discovery instrument-universe inclusion policy
     * @param venues    per-market transport config
     */
    public record ExchangeProperties(
            boolean enabled,
            RestProperties rest,
            DiscoveryProperties discovery,
            Map<Market, VenueProperties> venues
    ) {}

    /**
     * @param codecBufferSizeMb maximum in-memory buffer for WebClient response codecs; must hold a
     *                          full {@code exchangeInfo} response (well over the 256 KB default)
     */
    public record RestProperties(int codecBufferSizeMb) {}

    /**
     * Instrument-universe inclusion policy, previously hardcoded in {@code TickerService}.
     *
     * @param quoteAsset          only pairs quoted in this asset are tracked
     * @param futuresContractType futures contract type to accept, e.g. {@code PERPETUAL}
     * @param spotRequiresFutures when {@code true}, a spot symbol is tracked only if the same symbol
     *                            has an eligible futures contract — reproducing today's behaviour
     *                            exactly. Flipping it to {@code false} takes spot from
     *                            "futures ∩ spot" to every quoted spot pair, a large load change
     *                            that belongs in its own phase
     * @param excludedSymbols     stablecoin / metal pairs whose books carry no signal
     */
    public record DiscoveryProperties(
            String quoteAsset,
            String futuresContractType,
            boolean spotRequiresFutures,
            Set<String> excludedSymbols
    ) {}

    /**
     * @param streamUrl               WebSocket endpoint for this venue
     * @param restUrl                 REST base URL for this venue
     * @param depthStream             stream suffix appended to the lower-cased symbol, e.g. {@code "@depth"}
     * @param maxStreamsPerConnection venue's own per-connection subscription ceiling
     * @param minConnections          floor on the derived connection count. With Binance's 1024-stream
     *                                ceiling the derived term is 1, so this floor is what actually
     *                                sets the fan-out — see {@code BinanceConnectionPool}
     * @param maxConnections          ceiling on the derived connection count
     * @param subscribeChunkSize      streams per SUBSCRIBE frame (unrelated to connection count)
     */
    public record VenueProperties(
            String streamUrl,
            String restUrl,
            String depthStream,
            int maxStreamsPerConnection,
            int minConnections,
            int maxConnections,
            int subscribeChunkSize
    ) {}
}
