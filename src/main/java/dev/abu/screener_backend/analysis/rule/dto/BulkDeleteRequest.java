package dev.abu.screener_backend.analysis.rule.dto;

import java.util.List;

/**
 * Body of {@code DELETE /api/screener/rules}. Removes all tier rows for each listed target.
 * Deleting a non-existent rule is a no-op (idempotent).
 */
public record BulkDeleteRequest(List<TargetDto> targets) {}
