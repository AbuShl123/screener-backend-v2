-- Matches the adopted entity schema (VARCHAR(255) symbol/market, no DB defaults). The plain FK has
-- no ON DELETE CASCADE here, and the uq_rule_tier unique constraint + user_id index are NOT declared
-- here — all three move to V7 so they land in every environment (baselined prod/local never run this
-- file). The app layer already enforces tier uniqueness; V7 adds the DB backstop.
CREATE TABLE classification_rules (
    id           UUID             PRIMARY KEY,
    user_id      UUID             NOT NULL REFERENCES users(id),
    symbol       VARCHAR(255)     NOT NULL,
    market       VARCHAR(255)     NOT NULL,          -- 'SPOT' | 'FUTURES'
    tier_no      INTEGER          NOT NULL,          -- 1..4 (enforced in app layer)
    min_notional DOUBLE PRECISION NOT NULL,          -- USD, >= 0
    max_distance DOUBLE PRECISION NOT NULL,          -- fraction, (0, price-filter-threshold]
    created_at   TIMESTAMPTZ      NOT NULL,
    updated_at   TIMESTAMPTZ      NOT NULL
);
