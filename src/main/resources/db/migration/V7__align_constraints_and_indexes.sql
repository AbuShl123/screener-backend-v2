-- V7 — Align constraints & indexes across all environments.
--
-- Context: Flyway never ran historically; prod (V1–V4 tables) and local (all 6 tables) were both built
-- by Hibernate ddl-auto, which created STRUCTURE but skipped the indexes / uniqueness / cascade rules /
-- role-check that the migration files declared. V1–V4 are being edited to match the adopted entity
-- schema exactly (so baseline-at-4 is literally true), and the four "good things" they used to declare
-- move here so they land in EVERY environment:
--   * baselined prod   — runs V5,V6,V7 after the v4 baseline marker
--   * baselined local  — same, after dropping the empty V5/V6 tables
--   * truly fresh DB    — runs V1→V7 in order
--
-- Verified facts this migration relies on (see .claude/docs/flyway-issues.md):
--   * prod has 0 duplicate (user_id, symbol, market, tier_no) rows → uq_rule_tier applies cleanly
--   * prod's users_role_check is stale ('USER' only) → must be normalized to allow ADMIN
--   * FK names are environment-specific Hibernate hashes → drop by table/column, never by name
--
-- Guards (IF NOT EXISTS / lookup-then-act) make this safe to run regardless of whether the Step-A edits
-- to V1–V4 have been applied yet, and against the live schemas where none of these objects exist.

-- 1. Indexes on the FK columns used for connect-time rule loading and token lookup.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id       ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_classification_rules_user_id ON classification_rules(user_id);

-- 2. DB-level guard against duplicate tiers per (user, symbol, market). The app already enforces this;
--    this is the backstop. (No ADD CONSTRAINT IF NOT EXISTS in Postgres — guard manually.)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_rule_tier') THEN
        ALTER TABLE classification_rules
            ADD CONSTRAINT uq_rule_tier UNIQUE (user_id, symbol, market, tier_no);
    END IF;
END $$;

-- 3. Replace the plain FKs with ON DELETE CASCADE so deleting a user removes its tokens & rules.
--    Existing FK names are random Hibernate hashes and differ per environment, so locate each FK by
--    table + column (user_id), drop it, then re-add with a stable, cascading definition.
DO $$
DECLARE
    tbl     text;
    fk_name text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['refresh_tokens', 'classification_rules'] LOOP
        SELECT con.conname
          INTO fk_name
          FROM pg_constraint con
          JOIN pg_class     rel ON rel.oid = con.conrelid
          JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
         WHERE con.contype = 'f'
           AND nsp.nspname = 'public'
           AND rel.relname = tbl
           AND con.conkey = ARRAY[
                 (SELECT attnum FROM pg_attribute
                   WHERE attrelid = rel.oid AND attname = 'user_id' AND NOT attisdropped)
               ]::smallint[];

        IF fk_name IS NOT NULL THEN
            EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', tbl, fk_name);
        END IF;
    END LOOP;
END $$;

ALTER TABLE refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_user_id
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE classification_rules
    ADD CONSTRAINT fk_classification_rules_user_id
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- 4. Normalize the role check. Prod's copy predates the ADMIN role ('USER' only) and would reject any
--    ADMIN user; ddl-auto: validate never reconciles CHECK constraints, so fix it explicitly here.
--    Idempotent drop-then-add (same pattern as V4); the name is the Hibernate default in both live DBs.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD  CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));
