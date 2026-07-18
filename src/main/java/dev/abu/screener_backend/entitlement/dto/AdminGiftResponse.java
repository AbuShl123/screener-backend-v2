package dev.abu.screener_backend.entitlement.dto;

import java.util.List;

/**
 * Result of {@code POST /api/admin/entitlement/gift}: how many users were gifted, how much access each
 * received (seconds), and the per-user new expiry.
 *
 * @param updatedCount            number of distinct users granted access
 * @param grantedDurationSeconds  access added to each user, in seconds
 * @param results                 per-user new expiry, in request order
 */
public record AdminGiftResponse(
        int updatedCount,
        long grantedDurationSeconds,
        List<AdminGiftResult> results
) {}
