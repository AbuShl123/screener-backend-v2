package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.Venue;

/**
 * Binds one venue to the {@link TradeDecoder} that parses its {@code TRADE} frames.
 *
 * <p>Contributed as a Spring bean by an adapter package's configuration, like
 * {@link VenueStrategyBinding}. {@link TradeDecoderRegistry} collects every binding on the
 * classpath. A venue without a {@code TRADE} stream contributes none.
 */
public record VenueTradeDecoderBinding(Venue venue, TradeDecoder decoder) { }
