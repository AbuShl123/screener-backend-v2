package dev.abu.screener_backend.marketdata.adapter.mexc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * MEXC's spot {@code GET /api/v3/exchangeInfo}, trimmed to its symbol list. Unlike the futures API
 * there is no {@code {success, code, data}} envelope: errors arrive as a non-2xx status.
 *
 * @param symbols every spot pair MEXC lists, of every quote asset and state — unfiltered
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MexcSpotExchangeInfo(List<MexcSpotSymbolDto> symbols) {}
