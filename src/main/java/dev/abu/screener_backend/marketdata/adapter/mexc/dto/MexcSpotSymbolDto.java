package dev.abu.screener_backend.marketdata.adapter.mexc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One symbol from MEXC's spot {@code GET /api/v3/exchangeInfo}. Only the fields discovery needs are
 * mapped.
 *
 * <p>Boxed types on purpose, as in {@link MexcContractDto}: a missing field stays {@code null}
 * rather than reading as {@code false}.
 *
 * @param symbol               native symbol, {@code base + quote} with no separator, e.g.
 *                             {@code "BTCUSDT"}. Not always ASCII ({@code "龙虾USDT"})
 * @param status               {@code "1"} online, {@code "2"} paused, {@code "3"} offline — a string
 *                             on the wire
 * @param baseAsset            e.g. {@code "BTC"}; matches a futures contract's {@code baseCoinName}
 * @param quoteAsset           e.g. {@code "USDT"}
 * @param spotTradingAllowed   {@code isSpotTradingAllowed} on the wire, named explicitly so the
 *                             {@code is} prefix cannot be stripped by bean-style naming
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MexcSpotSymbolDto(
        String symbol,
        String status,
        String baseAsset,
        String quoteAsset,
        @JsonProperty("isSpotTradingAllowed") Boolean spotTradingAllowed
) {}
