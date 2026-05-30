package dev.abu.screener_backend.analysis.rule.dto;

import java.util.List;

/**
 * One rule applied to many targets. The same {@code rule} (tier set) is written for every
 * {@code (symbol, market)} in {@code targets}.
 */
public record RuleAssignmentDto(RuleDto rule, List<TargetDto> targets) {}
