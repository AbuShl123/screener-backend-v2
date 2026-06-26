-- Payment hardening pass — adjustments to PRE-EXISTING tables only.
--
-- The orders and order_status_history tables are created in their final shape in V8/V9 (version column,
-- NUMERIC(38,18) amount, seq identity). This migration carries only the changes that touch tables which
-- predate the payment feature (V5 plan_prices, V6 user_entitlement) and therefore cannot be folded into
-- their CREATE statements.

-- 1. Optimistic-locking version column on user_entitlement (defense-in-depth, mirrors orders.version).
--    Future admin/gift grant paths that race surface a loud OptimisticLockException rather than a silent
--    lost update. Existing entitlement paths serialize today, so this never contends.
ALTER TABLE user_entitlement ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 2. Widen plan_prices.amount to crypto-grade precision, matching orders.amount NUMERIC(38,18).
--    The original NUMERIC(19,4) is fine for fiat (<= 2 dp) but silently truncates cryptocurrencies
--    (BTC = 8 dp, ETH = 18 dp) — a stated future direction. NUMERIC is variable-width, so the unused
--    scale on small fiat values is nearly free; existing rows are padded to scale 18, no value changes.
ALTER TABLE plan_prices ALTER COLUMN amount TYPE NUMERIC(38,18);
