package dev.abu.screener_backend.exchange.spi;

import dev.abu.screener_backend.exchange.Venue;

/**
 * Binds one venue to the {@link StreamProtocol} that speaks its WebSocket wire format.
 *
 * <p>Contributed as a Spring bean by an <em>adapter</em> package's configuration
 * ({@code exchange/binance/BinanceAdapterConfig}), never by core. {@link StreamProtocolRegistry}
 * collects every binding on the classpath, so adding an exchange is a new adapter config plus a
 * YAML block — no edit to anything under {@code exchange/} core.
 */
public record VenueStreamBinding(Venue venue, StreamProtocol protocol) { }
