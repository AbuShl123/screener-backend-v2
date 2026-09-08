package dev.abu.screener_backend.exchange.spi;

import dev.abu.screener_backend.exchange.Venue;

/**
 * Binds one venue to the {@link DepthSyncStrategy} that syncs its books.
 *
 * <p>Contributed as a Spring bean by an <em>adapter</em> package's configuration
 * ({@code exchange/binance/BinanceAdapterConfig}), never by core. {@link SyncStrategyRegistry}
 * collects every binding on the classpath, so adding an exchange is a new adapter config plus a
 * YAML block — no edit to anything under {@code exchange/} core.
 */
public record VenueStrategyBinding(Venue venue, DepthSyncStrategy strategy) { }
