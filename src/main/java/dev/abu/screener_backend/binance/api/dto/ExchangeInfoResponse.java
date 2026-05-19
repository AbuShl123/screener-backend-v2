package dev.abu.screener_backend.binance.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;

import java.util.List;

/**
 * Minimal representation of the Binance {@code /exchangeInfo} REST response
 * (compatible with both the Spot and Futures variants of the endpoint).
 *
 * <p>Only the {@code symbols} array is mapped. All other top-level fields
 * (timezone, serverTime, rateLimits, exchangeFilters) are explicitly ignored
 * to avoid unnecessary deserialization work.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExchangeInfoResponse {

    /** All symbol entries returned by the exchange. */
    private List<BinanceSymbolDto> symbols;
}
