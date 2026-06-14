-- Subscription catalog: plans (named bundles of access days) + per-(plan, currency) prices.
--
-- All money is stored in MAJOR units (UZS sum). Minor-unit (tiyin, x100) conversion is a
-- provider-boundary concern handled later in the payment plan by the Multicard adapter — the
-- billing core never touches tiyin.

CREATE TABLE plans (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code          VARCHAR(64)  NOT NULL UNIQUE,    -- stable id: 'weekly','monthly','yearly','pay_as_you_go'
    display_name  VARCHAR(255) NOT NULL,           -- internal/admin label + English fallback ONLY
    type          VARCHAR(16)  NOT NULL,           -- 'FIXED' | 'PER_DAY'
    duration_days INTEGER,                         -- 7/30/365 for FIXED; NULL for PER_DAY
    active        BOOLEAN      NOT NULL DEFAULT TRUE, -- soft-disable; never hard-delete referenced plans
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT plans_type_check CHECK (type IN ('FIXED', 'PER_DAY')),
    CONSTRAINT plans_fixed_has_duration
        CHECK ((type = 'FIXED'   AND duration_days IS NOT NULL)
            OR (type = 'PER_DAY' AND duration_days IS NULL))
);

CREATE TABLE plan_prices (
    id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id    UUID          NOT NULL REFERENCES plans(id),
    currency   VARCHAR(3)    NOT NULL,             -- ISO 4217: 'UZS' (VARCHAR to match PlanPrice.currency @Column(length=3))
    amount     NUMERIC(19,4) NOT NULL,             -- MAJOR units (sum). FIXED: full period price; PER_DAY: ONE day
    active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT plan_prices_amount_nonneg CHECK (amount >= 0),
    CONSTRAINT plan_prices_unique UNIQUE (plan_id, currency)
);

CREATE INDEX idx_plan_prices_plan_id ON plan_prices(plan_id);

-- Seed: weekly / monthly / yearly + pay-as-you-go, one UZS price row each.
-- Amounts are PLACEHOLDERS in SUM (major units) pending final business pricing.
INSERT INTO plans (code, display_name, type, duration_days) VALUES
    ('weekly',        'Weekly',      'FIXED',   7),
    ('monthly',       'Monthly',     'FIXED',   30),
    ('yearly',        'Yearly',      'FIXED',   365),
    ('pay_as_you_go', 'Pay by days', 'PER_DAY', NULL);

INSERT INTO plan_prices (plan_id, currency, amount)
SELECT id, 'UZS',
       CASE code
           WHEN 'weekly'        THEN 50000.00
           WHEN 'monthly'       THEN 150000.00
           WHEN 'yearly'        THEN 1500000.00
           WHEN 'pay_as_you_go' THEN 8000.00
       END
FROM plans;
