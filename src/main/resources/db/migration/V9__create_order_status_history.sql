-- Append-only audit of every order state transition, so a payment can be reconstructed end-to-end.
--
-- `reason` stores a canonical OrderReason enum code (e.g. SUPERSEDED, AMOUNT_MISMATCH) — the single
-- source of truth for what each reason means lives in the enum, not in scattered string literals.
-- `reason_detail` carries free-form text (e.g. a raw provider error message) for forensic value;
-- it is NULL for self-explanatory reasons. The orders table deliberately has no `reason` column —
-- the audit trail lives only here.

CREATE TABLE order_status_history (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      UUID         NOT NULL REFERENCES orders(id),
    from_status   VARCHAR(16),
    to_status     VARCHAR(16)  NOT NULL,
    reason        VARCHAR(32),                            -- canonical OrderReason enum code
    reason_detail TEXT,                                   -- free-form detail (e.g. raw provider error); NULL when self-explanatory
    source        VARCHAR(16)  NOT NULL,                  -- API | CALLBACK | RECONCILIATION | SYSTEM
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_osh_order ON order_status_history(order_id);
