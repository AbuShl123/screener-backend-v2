-- One row per purchase attempt. Provider-neutral; the Multicard transaction uuid is stored in
-- provider_uuid once the invoice is created.
--
-- All money is in MAJOR units (UZS sum) as NUMERIC(19,4) — tiyin (minor units) never touches the DB;
-- the Multicard adapter converts at the provider boundary. The granted access duration is snapshotted
-- in SECONDS (BIGINT) so later re-pricing or plan edits never alter a past grant, and so the value
-- stays generic for future non-day grants (hourly promos, gift hours).

CREATE TABLE orders (
    id                       UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID          NOT NULL REFERENCES users(id),
    plan_id                  UUID          NOT NULL REFERENCES plans(id),
    status                   VARCHAR(16)   NOT NULL,            -- CREATED|PENDING|PAID|EXPIRED|FAILED|CANCELED|REVERTED
    granted_duration_seconds BIGINT        NOT NULL,            -- snapshot: FIXED=duration_days*86400; PER_DAY=ceil(amount/pricePerDay)*86400
    amount                   NUMERIC(19,4) NOT NULL,            -- snapshot, MAJOR units (sum)
    currency                 VARCHAR(3)    NOT NULL,            -- ISO 4217 (VARCHAR to match the JPA @Column(length=3))
    payment_provider         VARCHAR(32)   NOT NULL DEFAULT 'multicard',
    provider_uuid            VARCHAR(64),                       -- Multicard transaction uuid (set after invoice creation)
    ps                       VARCHAR(32),                       -- payment service from callback (uzcard/humo/payme/…)
    checkout_url             TEXT,
    expires_at               TIMESTAMPTZ,                       -- now + invoice ttl (30m)
    paid_at                  TIMESTAMPTZ,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT orders_status_check CHECK (status IN ('CREATED','PENDING','PAID','EXPIRED','FAILED','CANCELED','REVERTED')),
    CONSTRAINT orders_amount_nonneg CHECK (amount >= 0)
);

-- Idempotency: one order per Multicard transaction. Retried callbacks repeat for the same uuid.
CREATE UNIQUE INDEX uq_orders_provider_uuid ON orders(provider_uuid) WHERE provider_uuid IS NOT NULL;

-- At most one OPEN order per user (Decision #7): handles the lost-tab re-pay scenario and prevents a
-- user accidentally paying two different invoices.
CREATE UNIQUE INDEX uq_orders_one_open_per_user ON orders(user_id) WHERE status IN ('CREATED','PENDING');

CREATE INDEX idx_orders_user_status ON orders(user_id, status);
CREATE INDEX idx_orders_open ON orders(status) WHERE status = 'PENDING';  -- reconciliation sweep scan
