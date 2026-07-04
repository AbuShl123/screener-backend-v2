-- One active email-verification token per user (delete-then-insert on register/resend). Mirrors
-- refresh_tokens: the raw value never persists — only its SHA-256 hash. Single-use is enforced by
-- DELETING the row on successful verification (no consumed_at column needed). created_at doubles as
-- the resend-cooldown clock.

CREATE TABLE email_verification_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens(user_id);
