-- 1:1 with users. Holds ACCESS facts only: the authoritative access_expires_at plus has_paid
-- (which distinguishes a TRIAL from a paid ACTIVE subscription). Account/localization facts
-- (currency, country, locale) deliberately live elsewhere (a future user_settings table).
--
-- Access state ({TRIAL, ACTIVE, EXPIRED, ADMIN}) is DERIVED on read from these facts + role,
-- never stored as a column (a stored state goes stale the instant a timestamp passes).
--
-- IMPORTANT: existing production users are backfilled MANUALLY (see
-- scripts/backfill_user_entitlement.sql) — intentionally NOT as a Flyway migration, so a future
-- redeploy can never re-grant trials to the whole user base.

CREATE TABLE user_entitlement (
    user_id           UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    access_expires_at TIMESTAMPTZ,                    -- single authoritative field; NULL = never granted
    has_paid          BOOLEAN     NOT NULL DEFAULT FALSE, -- distinguishes TRIAL from ACTIVE
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
