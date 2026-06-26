-- Append-only audit of every access grant. Reverses the foundation plan's "no ledger" decision now
-- that real money grants access: trial seeding, purchases, and future admin grants all write a row
-- here so access changes are auditable. No change to user_entitlement — EntitlementService.extend(...)
-- simply writes one ledger row per grant.

CREATE TABLE entitlement_ledger (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID         NOT NULL REFERENCES users(id),
    source                   VARCHAR(16)  NOT NULL,             -- TRIAL | PURCHASE | ADMIN
    granted_duration_seconds BIGINT       NOT NULL,             -- the Duration applied (consistent with orders.granted_duration_seconds)
    previous_expires_at      TIMESTAMPTZ,
    new_expires_at           TIMESTAMPTZ  NOT NULL,
    order_id                 UUID         REFERENCES orders(id), -- set for PURCHASE
    admin_id                 UUID         REFERENCES users(id),  -- set for future ADMIN grants
    reason                   TEXT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_user ON entitlement_ledger(user_id);
