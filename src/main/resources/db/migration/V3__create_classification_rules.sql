CREATE TABLE classification_rules (
    id           UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID             NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    symbol       VARCHAR(32)      NOT NULL,
    market       VARCHAR(16)      NOT NULL,          -- 'SPOT' | 'FUTURES'
    tier_no      SMALLINT         NOT NULL,          -- 1..4 (enforced in app layer)
    min_notional DOUBLE PRECISION NOT NULL,          -- USD, >= 0
    max_distance DOUBLE PRECISION NOT NULL,          -- fraction, (0, price-filter-threshold]
    created_at   TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_rule_tier UNIQUE (user_id, symbol, market, tier_no)
);

-- Connect-time load (Phase C) and the GET endpoint both query by user_id.
CREATE INDEX idx_classification_rules_user_id ON classification_rules(user_id);
