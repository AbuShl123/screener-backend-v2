package dev.abu.screener_backend.analysis.rule.dto;

import java.util.List;

/**
 * A complete tier set for one logical rule. The {@code tiers} must cover every tier 1 through 4
 * exactly once — partial sets such as {@code [1,2]} or {@code [1,2,4]} are rejected.
 */
public record RuleDto(List<TierDto> tiers) {}
