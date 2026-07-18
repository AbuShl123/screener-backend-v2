package dev.abu.screener_backend.entitlement.dto;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /api/admin/entitlement/gift} — a bulk admin gift of free access. Every listed
 * user has their {@code accessExpiresAt} pushed forward by the <em>same</em> {@code addPeriodDays}
 * (stacked on top of any remaining time). {@code reason} is an optional free-form note recorded on each
 * user's ledger row.
 *
 * @param userIds       users to gift (must all exist; duplicates are ignored)
 * @param addPeriodDays whole days of access to add to every listed user (must be &gt; 0)
 * @param reason        optional note stored on every resulting ledger row
 */
public record AdminGiftRequest(
        List<UUID> userIds,
        Integer addPeriodDays,
        String reason
) {}
