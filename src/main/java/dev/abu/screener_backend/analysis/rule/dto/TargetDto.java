package dev.abu.screener_backend.analysis.rule.dto;

import dev.abu.screener_backend.binance.websocket.Market;

/**
 * A single {@code (symbol, market)} a rule applies to.
 *
 * @param symbol Binance symbol, e.g. {@code "BTCUSDT"}
 * @param market {@code SPOT} or {@code FUTURES}
 */
public record TargetDto(String symbol, Market market) {}
