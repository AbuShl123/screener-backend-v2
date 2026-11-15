package dev.abu.screener_backend.marketdata.adapter.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One page of {@code GET /v5/market/instruments-info}: the {@code result} object.
 *
 * @param category       {@code "spot"} or {@code "linear"}, echoed back
 * @param list           the instruments on this page, unfiltered
 * @param nextPageCursor linear: the cursor for the next page, {@code ""} once exhausted. Spot: the
 *                       field is absent entirely ({@code null}), since spot always returns its whole
 *                       universe in one page (contracts §3.2)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BybitInstrumentsPage(String category, List<BybitInstrumentDto> list, String nextPageCursor) {}
