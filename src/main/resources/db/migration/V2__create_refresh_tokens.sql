-- Matches the adopted entity schema (VARCHAR(255) token_hash, no DB defaults). The plain FK has no
-- ON DELETE CASCADE here and there is no user_id index — both are added in V7 so they land in
-- every environment (baselined prod/local never run this file).
CREATE TABLE refresh_tokens (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
