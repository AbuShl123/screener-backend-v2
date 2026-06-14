# Flyway Migration — Investigation, Findings & Remediation Runbook

Status: **investigation complete and verified against live prod + local schema dumps. Fix partially
applied; V1–V4/V7 edits and config NOT yet made.**
Decision taken: **"Entities + add V7"** — edit V1–V4 to match the existing/entity schema, then add a
new `V7` migration to close the functional gaps across all environments.

> The schema comparison in §3 is no longer an assumption — it was confirmed by running full
> `information_schema` / `pg_indexes` introspection on **both** prod and local. Raw outputs are saved in
> `query-results.md`.

---

## 1. Why Flyway was never working (root cause + fix)

**Symptom:** Migrations never ran. `plans` / `plan_prices` were empty even after seeding via V5, and
IntelliJ could not resolve `spring.flyway.enabled` / `spring.flyway.locations`. The DB had **no
`flyway_schema_history` table at all** — proof Flyway had literally never executed once. Every table
present was created by Hibernate `ddl-auto: update`, which builds structure but does not run the seed
`INSERT`s in the migration scripts.

> **Verified:** `SELECT to_regclass('public.flyway_schema_history') IS NOT NULL` returns `f` in **both**
> prod and local — Flyway has never run in either environment.

**Root cause:** The project declared only `org.flywaydb:flyway-core` (the Flyway *library*). In
**Spring Boot 3.x** that was sufficient because `FlywayAutoConfiguration` lived inside the always-present
`spring-boot-autoconfigure` jar. In **Spring Boot 4.0** (this project is on **4.0.6**) the monolithic
autoconfigure module was split into dedicated per-technology modules (`spring-boot-jdbc`,
`spring-boot-jpa`, `spring-boot-flyway`, …). Without `spring-boot-flyway` on the classpath there is no
`FlywayAutoConfiguration` → Spring never creates a Flyway bean → migrations never run regardless of
`spring.flyway.enabled=true`. And with no `FlywayProperties` class present, IntelliJ cannot resolve the
`spring.flyway.*` keys — the *same* single cause.

Verified against the actual classpath: `FlywayAutoConfiguration` is present in cached
`spring-boot-autoconfigure-3.x` jars but **absent from 4.0.6**; the resolved dependency list had
`spring-boot-jdbc`/`spring-boot-jpa`/`spring-boot-hibernate` but **no `spring-boot-flyway`**.

**Fix applied (done):** added `org.springframework.boot:spring-boot-starter-flyway` to `pom.xml`
(it pulls in the `spring-boot-flyway` autoconfiguration module + `flyway-core`; `flyway-database-postgresql`
is kept for PostgreSQL support). Confirmed `spring-boot-flyway:4.0.6` now resolves on the classpath.
After a Maven reload the IntelliJ `spring.flyway.*` warnings also clear.

**Also fixed (done):** `V5__create_plans.sql` had `currency CHAR(3)`, but `PlanPrice.currency` is
`@Column(length = 3)` on a `String` → Hibernate expects `VARCHAR(3)`. Under `ddl-auto: validate` a
`CHAR` vs `VARCHAR` mismatch (different JDBC type codes) can fail startup. Changed to `VARCHAR(3)`.
(Local already shows `character varying(3)` for `plan_prices.currency`, confirming the entity mapping.)

---

## 2. Why we can't just drop the whole schema and rebuild

The screener backend is **already deployed to production with real users**. Dropping the schema would
force every existing user to re-register — unacceptable. So Flyway must **adopt** the existing schema
rather than recreate it.

After the dependency fix, starting the app produced the expected, intended error:

```
org.flywaydb.core.api.FlywayException: Found non-empty schema(s) "public" but no schema history table.
Use baseline() or set baselineOnMigrate to true to initialize the schema history table.
```

Flyway never deletes data here (`clean` is disabled by default); on a non-empty schema with no history
table it simply **refuses to start** until the gap is reconciled.

**Key asymmetry between environments (confirmed by table list):**

| Environment | Tables present | Handling |
|---|---|---|
| **Prod** | `classification_rules`, `refresh_tokens`, `users` (V1–V4 only; **no** `plans` / `plan_prices` / `user_entitlement`) | Baseline at v4, then Flyway runs **V5 + V6 + V7** fresh. |
| **Local** | All 6 tables — V5/V6 tables created by Hibernate but **empty (0 rows each)** | Baseline-at-4 would try to run V5/V6 and fail ("table already exists"). First `DROP TABLE user_entitlement, plan_prices, plans;` (all empty — safe) to return local to the exact V4 state, then baseline-at-4 runs V5/V6/V7 identically to prod. |

> Local billing-table row counts at verification time: `plans` 0, `plan_prices` 0, `user_entitlement` 0.
> Earlier app-data snapshot: 2 users, 2 refresh_tokens, 4 classification_rules.

---

## 3. Discrepancies — migration files vs the actual DBs (verified prod + local)

The existing DBs were built by Hibernate `ddl-auto: update` from the entities, so **"actual DB" == "what
the entities produce."** The three V1–V4 tables are **byte-for-byte identical between prod and local** on
every dimension that matters to `validate` (column names, types, lengths, nullability, absence of DB
defaults). Differences below are split into cosmetic vs functional.

### 3a. Cosmetic — no functional or `validate` impact

| Item | Migration file says | Actual DB (prod = local) |
|---|---|---|
| `users.first_name` / `last_name` / `role` | `VARCHAR(100)` / `VARCHAR(50)` | `VARCHAR(255)` |
| `classification_rules.symbol` / `market` | `VARCHAR(32)` / `VARCHAR(16)` | `VARCHAR(255)` |
| `refresh_tokens.token_hash` | `TEXT` | `VARCHAR(255)` |
| `id` / `created_at` / `role` / `enabled` defaults | DB-level `DEFAULT gen_random_uuid()` / `NOW()` / `'USER'` / `TRUE` | **no DB defaults** (app sets every value via `@GeneratedValue` / `@PrePersist`) |
| **Column ordinal order** | n/a | Differs prod vs local (e.g. `refresh_tokens`, `users` reordered). Hibernate `validate` and Flyway ignore column position — **no impact.** |
| **Unique-index names** | n/a | Prod uses Hibernate hashes (`uko2ml…`, `uk6dot…`); local uses Postgres-native names (`refresh_tokens_token_hash_key`, `users_email_key`). Same columns + uniqueness — **no impact.** |

### 3b. Functional gaps — present in the migrations, MISSING in both live DBs

| Item | Migration | Prod | Local | Why it matters |
|---|---|---|---|---|
| `idx_refresh_tokens_user_id` | created | **missing** | **missing** | token lookups by `user_id` unindexed |
| `idx_classification_rules_user_id` | created | **missing** | **missing** | connect-time rule load by `user_id` unindexed |
| `uq_rule_tier` UNIQUE(`user_id, symbol, market, tier_no`) | present | **missing** | **missing** | no DB guard against duplicate tiers per (user, symbol, market) |
| FK `ON DELETE CASCADE` on `refresh_tokens.user_id` **and** `classification_rules.user_id` | cascade | `NO ACTION` | `NO ACTION` | deleting a user with tokens/rules fails on FK violation |

All four are addressed by **V7** (§4 Step B) so they land in every environment.

### 3c. ⚠️ Environment drift — stale `users_role_check` in PROD

Hibernate auto-generates `CHECK (col IN (...))` constraints for `@Enumerated(STRING)` columns. The dumps
revealed these are **out of sync between prod and local**:

| Constraint | Prod | Local |
|---|---|---|
| `users_role_check` | `role = 'USER'` **(USER only)** | `role IN ('USER','ADMIN')` |
| `classification_rules_market_check` | `market IN ('SPOT','FUTURES')` | `market IN ('SPOT','FUTURES')` (identical — fine) |

**Why:** prod's `users` table was built **before** commit `b7cdcfe` introduced the `ADMIN` role; local was
rebuilt afterward.

**Impact:** this is **not** a Flyway/`validate` blocker — `ddl-auto: validate` ignores CHECK constraints,
so prod starts fine. But it is a **latent prod bug**: the monitoring endpoints now require `ADMIN`, and the
first attempt to create or promote an ADMIN user in prod will be **rejected** by this constraint. Because
`validate` never reconciles CHECK constraints, it will sit silently until it bites.

**Decision:** normalize it in **V7** (§4 Step B) — drop `users_role_check` if it exists, re-add as
`role IN ('USER','ADMIN')` with a stable name. Drop-by-name works on both envs (both use the default name
`users_role_check`); on a fresh DB the drop is a harmless no-op.

> **FK name caveat:** FK constraint names in the live DBs are Hibernate-generated hashes (prod
> `fkqhfboitvhl5h7uyhyyfsg1ha9`, `fk1lih5y2npsf8u5o3vhdb9y0os`) and differ per environment, so any
> FK-altering SQL in V7 must locate the constraint **by table/column, not by name**.

### 3d. Pre-flight for `uq_rule_tier` — PASSED

The duplicate check on prod returned **0 rows**:

```sql
SELECT user_id, symbol, market, tier_no, COUNT(*)
FROM classification_rules
GROUP BY user_id, symbol, market, tier_no HAVING COUNT(*) > 1;
-- (0 rows)
```

`uq_rule_tier` will apply cleanly in prod. (Re-run immediately before deploy if there has been write
traffic since.)

---

## 4. Solution — "Entities + add V7"

Rationale: the cosmetic differences aren't worth ALTERing prod for, but the functional gaps (§3b) and the
prod role-check drift (§3c) are **real defects in prod** that should be fixed everywhere, not frozen in.

### Part 0 — Shared code prep (one commit; deploys to both environments)

These are repo changes, done once, identical for local and prod.

**Step A — Edit V1–V4 to match the existing/entity schema.**
- `VARCHAR(255)` (or no explicit length) for `first_name`, `last_name`, `role`, `symbol`, `market`;
  `VARCHAR(255)` for `token_hash`.
- Remove the DB-level `DEFAULT` clauses (`gen_random_uuid()`, `NOW()`, `'USER'`, `TRUE`).
- Remove the inline indexes, the `uq_rule_tier` unique constraint, and the `ON DELETE CASCADE` from the
  FKs (these move to V7).
- Result: baseline-at-4 becomes **literally true** — the existing schema exactly equals what V1–V4 now
  produce. No ALTERs on prod; zero risk.

**Step B — Add `V7__align_constraints_and_indexes.sql`** that converges every environment (prod via run,
local via run, fresh via run after V1–V4):
- `CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);`
- `CREATE INDEX idx_classification_rules_user_id ON classification_rules(user_id);`
- `ALTER TABLE classification_rules ADD CONSTRAINT uq_rule_tier UNIQUE (user_id, symbol, market, tier_no);`
- Replace both plain FKs with `ON DELETE CASCADE` versions — drop the existing FK **found by
  table/column** (names are random Hibernate hashes) inside a `DO $$ … $$` block, then re-add with cascade
  and a stable name.
- **Normalize the role check** (§3c): `ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;`
  then `ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER','ADMIN'));`

Because V1–V4 no longer create the indexes / unique / cascade, V7 needs no `IF NOT EXISTS` guards for them
— neither the baselined path nor the fresh path has them before V7. All environments converge to:
**entity base schema + two indexes + `uq_rule_tier` + cascading FKs + `role IN ('USER','ADMIN')` check.**

**Step C — Config** (`application.yml` + `application-local.yml`):
- `spring.flyway.baseline-on-migrate: true`
- `spring.flyway.baseline-version: 4`
- Safe to commit permanently — inert once `flyway_schema_history` exists.

> **Already applied (code):** `spring-boot-starter-flyway` dependency in `pom.xml`; V5
> `currency CHAR(3)` → `VARCHAR(3)`.

---

## 5. Runbooks

### Part 1 — LOCAL

1. **Stop the app.**
2. `DROP TABLE user_entitlement, plan_prices, plans;` — all verified **0 rows**; returns local to the exact
   V4 state. (Drop, don't keep — so V5/V6 recreate them with migration-defined names/constraints.)
3. **Pull the Part 0 changes** (edited V1–V4, new V7, baseline config).
4. **Start the app** → Flyway writes a baseline marker at v4, then runs **V5 → V6 → V7**; Hibernate
   `validate` then passes against the migrated schema.
5. **Verify** (see §6).

### Part 2 — PROD

1. **`pg_dump` a full backup first** — the safety net for the one non-reversible path.
2. **Pre-flight:** duplicate-tier check already returned 0 rows (§3d). Re-run if there's been write traffic.
3. **Deploy** the app build carrying the Part 0 changes (new dependency + edited V1–V4 + V7 + baseline
   config).
4. On startup Flyway **baselines at v4** and runs **V5 → V6 → V7** — creates `plans` / `plan_prices` /
   `user_entitlement` fresh, adds the two indexes + `uq_rule_tier` + cascade FKs, and normalizes the role
   check. Hibernate `validate` passes.
5. **Verify** (§6) + a quick app smoke test (login, rules CRUD, WS connect).
6. **Rollback posture:** Postgres DDL is transactional, so a failing V7 self-rolls-back that migration; if
   V5/V6 already committed and something is wrong, restore from the step-1 dump.

---

## 6. Post-migration verification (run in each environment)

```sql
-- 1. History: baseline marker at v4 + V5/V6/V7 all succeeded
SELECT version, description, type, success
FROM flyway_schema_history ORDER BY installed_rank;

-- 2. New indexes present
SELECT indexname FROM pg_indexes
WHERE schemaname='public'
  AND indexname IN ('idx_refresh_tokens_user_id','idx_classification_rules_user_id');

-- 3. uq_rule_tier present
SELECT conname FROM pg_constraint WHERE conname='uq_rule_tier';

-- 4. FKs now cascade (expect delete_rule = CASCADE for both)
SELECT tc.table_name, kcu.column_name, rc.delete_rule
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
  ON tc.constraint_name=kcu.constraint_name AND tc.table_schema=kcu.table_schema
JOIN information_schema.referential_constraints rc
  ON tc.constraint_name=rc.constraint_name AND tc.table_schema=rc.constraint_schema
WHERE tc.table_schema='public' AND tc.constraint_type='FOREIGN KEY'
  AND tc.table_name IN ('refresh_tokens','classification_rules');

-- 5. Role check normalized (expect USER + ADMIN)
SELECT check_clause FROM information_schema.check_constraints
WHERE constraint_name='users_role_check';

-- 6. Prod only: billing tables now exist and are seeded by V5/V6
SELECT table_name FROM information_schema.tables
WHERE table_schema='public'
  AND table_name IN ('plans','plan_prices','user_entitlement');
```

---

## 7. Key semantics & gotchas

- **`baseline-version: 4` semantics:** Flyway writes one baseline marker row at version 4 and applies only
  migrations with version **> 4** (V5, V6, V7). V1–V4 are **never executed** on a baselined DB — they only
  run on a truly fresh environment (V1→V7 in order), which converges to the same final schema.
- **Run order is correct for `validate`:** Spring Boot makes the JPA `EntityManagerFactory` depend on
  Flyway, so Flyway migrates first, then Hibernate `validate` runs against the migrated schema.
- **FK name caveat (repeat):** existing FK names are environment-specific Hibernate hashes — V7 drops by
  table/column, not by name.
- **CHECK constraints & `validate`:** `validate` neither creates nor checks CHECK constraints. Fresh
  migration-built DBs won't carry the `classification_rules_market_check` that the live DBs have — harmless
  asymmetry, accepted. The `users_role_check` is normalized in V7 specifically because the prod copy was
  stale (USER-only) and would block ADMIN creation.

---

## 8. Status checklist

- ✅ Added `spring-boot-starter-flyway` to `pom.xml`.
- ✅ `V5`: `currency CHAR(3)` → `VARCHAR(3)`.
- ✅ Verified prod + local schema (full introspection) — assumptions confirmed; drift found (§3c).
- ⬜ Step A: edit V1–V4 to match existing schema.
- ⬜ Step B: add `V7__align_constraints_and_indexes.sql` (indexes + unique + cascading FKs + role-check
  normalization).
- ⬜ Step C: add `baseline-on-migrate` / `baseline-version: 4` config.
- ⬜ Part 1: local — drop the 3 empty billing tables, deploy, verify.
- ⬜ Part 2: prod — backup, deploy, verify.
