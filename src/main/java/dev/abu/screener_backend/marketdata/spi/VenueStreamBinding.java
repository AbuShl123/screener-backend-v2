package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.Venue;

/**
 * Binds one venue to the {@link StreamProtocol} that speaks its WebSocket wire format.
 *
 * <p>Contributed as a Spring bean by an <em>adapter</em> package's configuration
 * ({@code adapter/binance/BinanceAdapterConfig}), never by core. {@link StreamProtocolRegistry}
 * collects every binding on the classpath, so adding an exchange is a new adapter config plus a
 * YAML block — no edit to anything under {@code core/}.
 */
public record VenueStreamBinding(Venue venue, StreamProtocol protocol) { }
