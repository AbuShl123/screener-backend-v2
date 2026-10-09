package dev.abu.screener_backend.marketdata.adapter.mexc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One contract from MEXC's {@code GET /api/v1/contract/detail}. Only the fields discovery needs are
 * mapped; the response carries ~80 per contract.
 *
 * <p>Boxed types on purpose: a field missing from the response stays {@code null} instead of
 * silently reading as {@code 0} / {@code false}, which for {@code state} would mean "enabled".
 *
 * @param symbol       native symbol, {@code BASE_QUOTE}, e.g. {@code "BTC_USDT"}
 * @param baseCoin     e.g. {@code "BTC"}; on some contracts an internal id rather than the ticker
 *                     ({@code FILECOIN_USDT} has {@code baseCoin} {@code "FILECOIN"})
 * @param baseCoinName the display ticker, e.g. {@code "FIL"} for {@code FILECOIN_USDT}. Equals MEXC
 *                     spot's {@code baseAsset} for the same coin, so it is what spot discovery
 *                     matches on; the futures instrument itself keeps {@code baseCoin}
 * @param quoteCoin    e.g. {@code "USDT"}
 * @param futureType   {@code 1} perpetual, {@code 2} delivery
 * @param state        {@code 0} enabled, {@code 1} delivery, {@code 2} delivered, {@code 3} offline,
 *                     {@code 4} paused
 * @param apiAllowed   whether the contract is open to API trading
 * @param contractSize base asset per contract, e.g. {@code 0.0001} for BTC_USDT. Depth quantities
 *                     are contract counts, so this is the instrument's quantity multiplier
 * @param conceptPlate MEXC's sector tags, e.g. {@code ["mc-trade-zone-mainly", "mc-trade-zone-pow"]};
 *                     {@code mc-trade-zone-tradfi} marks stocks, ETFs, indices, commodities and forex
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MexcContractDto(
        String symbol,
        String baseCoin,
        String baseCoinName,
        String quoteCoin,
        Integer futureType,
        Integer state,
        Boolean apiAllowed,
        Double contractSize,
        List<String> conceptPlate
) {}
