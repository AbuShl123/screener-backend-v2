package dev.abu.screener_backend.analysis.rule.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/rules/default}.
 * Exposes both threshold tables from {@link dev.abu.screener_backend.analysis.DefaultClassificationRule}
 * and the list of symbols that use the high-liquidity table.
 */
public record DefaultRuleResponse(
        List<TierDto> normalTiers,
        List<String> highLiquiditySymbols,
        List<TierDto> highLiquidityTiers
) {}
