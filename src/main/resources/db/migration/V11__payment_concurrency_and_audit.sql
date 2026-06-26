-- Payment hardening pass (enhancements plan E2 + E7).
--
-- 1. Optimistic-locking version columns (defense-in-depth). The existing payment paths already serialize
--    order mutations with SELECT ... FOR UPDATE, so these never contend and @Version never fires
--    spuriously; they exist so any FUTURE unlocked path that races surfaces a loud OptimisticLockException
--    instead of a silent lost update.
ALTER TABLE orders           ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE user_entitlement ADD COLUMN version BIGINT NOT NULL DEFAULT 0;  -- future admin/gift grants

-- 2. Deterministic ordering of the status audit trail. Two transitions can share a created_at tick
--    (app-clock Instant.now()), making a "latest reason" lookup ordered by created_at ambiguous. A
--    monotonic identity seq is deterministic. Existing rows are assigned seq values in physical
--    insertion order; no backfill needed.
ALTER TABLE order_status_history ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;

-- The composite (order_id, seq DESC) fully covers the per-order "latest transition" and ordered-list
-- queries, so it replaces the plain order_id index.
DROP INDEX IF EXISTS idx_osh_order;
CREATE INDEX idx_osh_order_seq ON order_status_history(order_id, seq DESC);
