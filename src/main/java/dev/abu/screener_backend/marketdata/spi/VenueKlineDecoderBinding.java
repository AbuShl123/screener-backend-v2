package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.Venue;

/**
 * Binds one venue to the {@link KlineDecoder} that parses its {@code KLINE} frames.
 *
 * <p>Contributed as a Spring bean by an adapter package's configuration, like
 * {@link VenueTradeDecoderBinding}. A venue without a {@code KLINE} stream contributes none.
 */
public record VenueKlineDecoderBinding(Venue venue, KlineDecoder decoder) { }
