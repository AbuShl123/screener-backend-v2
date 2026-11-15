package dev.abu.screener_backend.marketdata.adapter.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One instrument from {@code GET /v5/market/instruments-info}, spot or linear. Only the fields
 * discovery needs are mapped; the two categories share them except where noted, and a field the
 * category does not send stays {@code null}.
 *
 * @param symbol       native symbol, concatenated and uppercase, identical across categories:
 *                     {@code "BTCUSDT"}. Multiplier prefixes are part of it on linear
 *                     ({@code "1000000BABYDOGEUSDT"})
 * @param contractType linear only: {@code "LinearPerpetual"} or {@code "LinearFutures"} (dated)
 * @param status       {@code "Trading"}, {@code "PreLaunch"}, {@code "Delivering"} or {@code "Closed"}
 * @param baseCoin     e.g. {@code "BTC"}; keeps the multiplier prefix on linear
 *                     ({@code "1000000BABYDOGE"}), as Binance futures does
 * @param quoteCoin    e.g. {@code "USDT"}
 * @param stTag        spot only: {@code "1"} marks a Special Treatment (risk-flagged) pair
 * @param symbolType   the product class. Linear: {@code ""} (crypto), {@code "innovation"},
 *                     {@code "stock"}, {@code "ETF"}, {@code "commodity"}, {@code "forex"}; spot:
 *                     {@code ""}, {@code "xstocks"}, {@code "adventure"}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BybitInstrumentDto(
        String symbol,
        String contractType,
        String status,
        String baseCoin,
        String quoteCoin,
        String stTag,
        String symbolType
) {}
