package dev.abu.screener_backend.binance.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;

/**
 * Minimal representation of a single symbol entry from the Binance
 * {@code /exchangeInfo} endpoint (both spot and futures variants).
 *
 * <p>Jackson ignores the many other fields returned by Binance. Only the fields
 * required for ticker eligibility filtering are mapped.
 *
 * <p><strong>Null-safety note:</strong> {@code contractType} is present only in futures
 * responses and will be {@code null} for all spot symbols. All comparisons must place
 * the string literal on the left to be null-safe:
 * <pre>{@code "PERPETUAL".equals(dto.getContractType())}</pre>
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceSymbolDto {

    /** Binance symbol string, e.g. {@code "BTCUSDT"}. */
    private String symbol;

    /**
     * Quote asset of this symbol, e.g. {@code "USDT"}, {@code "BTC"}, {@code "ETH"}.
     * Used to filter for USDT-quoted pairs only — non-USDT pairs (e.g. {@code "ETHBTC"})
     * are excluded from tracking.
     */
    private String quoteAsset;

    /** Trading status. Only {@code "TRADING"} symbols are of interest. */
    private String status;

    /**
     * Contract type for futures symbols: {@code "PERPETUAL"}, {@code "CURRENT_QUARTER"}, etc.
     * {@code null} for all spot symbols — always use null-safe comparison.
     */
    private String contractType;
}
