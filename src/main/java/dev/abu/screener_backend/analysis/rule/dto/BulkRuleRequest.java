package dev.abu.screener_backend.analysis.rule.dto;

import java.util.List;

/**
 * Body of {@code PUT /api/screener/rules}. A single shape covers both "same rule for many
 * tickers" and "different rules in one call". Each assignment replaces (not merges) the
 * existing tier set for each of its targets.
 */
public record BulkRuleRequest(List<RuleAssignmentDto> assignments) {}
