-- Append-only audit of every order state transition, so a payment can be reconstructed end-to-end.
--
-- `reason` stores a canonical OrderReason enum code (e.g. SUPERSEDED, AMOUNT_MISMATCH) — the single
-- source of truth for what each reason means lives in the enum, not in scattered string literals.
-- `reason_detail` carries free-form text (e.g. a raw provider error message) for forensic value;
-- it is NULL for self-explanatory reasons. The orders table deliberately has no `reason` column —
-- the audit trail lives only here.
--
-- `seq` is a monotonic, DB-generated identity column: the deterministic ordering key for the "latest
-- transition" lookup (E7). Two transitions can share a created_at tick (app-clock Instant.now()),
-- making a created_at-ordered "latest reason" lookup ambiguous; ordering by seq DESC is deterministic.

CREATE TABLE order_status_history (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      UUID         NOT NULL REFERENCES orders(id),
    from_status   VARCHAR(16),
    to_status     VARCHAR(16)  NOT NULL,
    reason        VARCHAR(32),                            -- canonical OrderReason enum code
    reason_detail TEXT,                                   -- free-form detail (e.g. raw provider error); NULL when self-explanatory
    source        VARCHAR(16)  NOT NULL,                  -- API | CALLBACK | RECONCILIATION | SYSTEM
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    seq           BIGINT       GENERATED ALWAYS AS IDENTITY
);

-- (order_id, seq DESC) fully covers both the per-order "latest transition" lookup and the ordered-list
-- query, so no separate plain order_id index is needed.
CREATE INDEX idx_osh_order_seq ON order_status_history(order_id, seq DESC);
