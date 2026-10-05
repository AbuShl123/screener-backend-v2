package dev.abu.screener_backend.marketdata.adapter.binance;

/**
 * The two REST path prefixes Binance's spot and futures APIs diverge on. Everything else about a
 * request (query params, response shape) is identical between the two.
 *
 * @param exchangeInfoPath path of the {@code exchangeInfo} endpoint
 * @param depthPath        path of the order-book depth endpoint
 */
public record BinancePaths(String exchangeInfoPath, String depthPath) {

    public static final BinancePaths SPOT = new BinancePaths("/api/v3/exchangeInfo", "/api/v3/depth");
    public static final BinancePaths FUTURES = new BinancePaths("/fapi/v1/exchangeInfo", "/fapi/v1/depth");
}
