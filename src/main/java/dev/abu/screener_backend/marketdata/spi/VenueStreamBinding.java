package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.StreamKey;

/**
 * Binds one stream — a venue and the kind it carries — to the {@link StreamProtocol} that speaks its
 * WebSocket wire format.
 *
 * <p>Contributed as a Spring bean by an <em>adapter</em> package's configuration
 * ({@code adapter/binance/BinanceAdapterConfig}), never by core. {@link StreamProtocolRegistry}
 * collects every binding on the classpath, so adding an exchange is a new adapter config plus a
 * YAML block — no edit to anything under {@code core/}.
 */
public record VenueStreamBinding(StreamKey stream, StreamProtocol protocol) { }
