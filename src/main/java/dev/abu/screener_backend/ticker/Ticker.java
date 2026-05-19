package dev.abu.screener_backend.ticker;

/**
 * Immutable descriptor for a Binance ticker that is eligible for screening.
 *
 * <p>All tracked tickers are USDT-quoted and have an active PERPETUAL futures contract.
 * Spot market presence is optional and captured by {@link #hasSpot()}.
 *
 * @param symbol     Binance symbol string, e.g. {@code "BTCUSDT"}
 * @param hasFutures {@code true} if an active USDT PERPETUAL futures contract exists;
 *                   always {@code true} for stored tickers — a futures contract is required for inclusion
 * @param hasSpot    {@code true} if the ticker also has an active USDT spot market
 */
public record Ticker(String symbol, boolean hasFutures, boolean hasSpot) {}
