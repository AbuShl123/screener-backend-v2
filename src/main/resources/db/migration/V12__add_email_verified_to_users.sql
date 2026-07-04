-- Email-confirmation gate for new registrations. A brand-new user cannot log in until they click a
-- verification link. Existing accounts are GRANDFATHERED verified — they already proved the address
-- is real (some have paid), so a re-verify wall would only risk locking out real customers.
--
-- No DB default is retained (consistent with the baselined `users` table — the app sets the value on
-- insert via @PrePersist). Add nullable → backfill every existing row → enforce NOT NULL.

ALTER TABLE users ADD COLUMN email_verified BOOLEAN;
-- Grandfather every existing account: they are already trusted (some have paid).
UPDATE users SET email_verified = TRUE;
ALTER TABLE users ALTER COLUMN email_verified SET NOT NULL;
