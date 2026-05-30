package dev.abu.screener_backend.analysis.rule.dto;

import dev.abu.screener_backend.binance.websocket.Market;

import java.util.List;

/**
 * GET response shape: the caller's configured rule for one {@code (symbol, market)}, with its
 * tier rows grouped together.
 */
public record RuleResponse(String symbol, Market market, List<TierDto> tiers) {}
