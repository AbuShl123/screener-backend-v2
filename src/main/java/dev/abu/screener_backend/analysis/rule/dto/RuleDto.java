package dev.abu.screener_backend.analysis.rule.dto;

import java.util.List;

/**
 * A complete tier set for one logical rule. The {@code tiers} must form a contiguous set
 * starting at 1 (e.g. {@code [1,2,3]}) — gaps such as {@code [1,2,4]} are rejected.
 */
public record RuleDto(List<TierDto> tiers) {}
