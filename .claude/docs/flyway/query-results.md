
# PROD results


### 1. Tables present (the critical one — proves prod's V4-only state):

``` sql 
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;
```

```text
      table_name
----------------------
 classification_rules
 refresh_tokens
 users
(3 rows)
```


### 2. Flyway history existence (should be false in both, pre-fix):

```sql
SELECT to_regclass('public.flyway_schema_history') IS NOT NULL AS flyway_history_exists;
```

```text
 flyway_history_exists
-----------------------
 f
(1 row)
```


### 3. Columns — types, lengths, nullability, defaults:

``` sql 
SELECT table_name, ordinal_position, column_name, data_type,
character_maximum_length, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public'
ORDER BY table_name, ordinal_position;
```

```text
      table_name      | ordinal_position |  column_name  |        data_type         | character_maximum_length | is_nullable | column_default
----------------------+------------------+---------------+--------------------------+--------------------------+-------------+----------------
 classification_rules |                1 | id            | uuid                     |                          | NO          |
 classification_rules |                2 | created_at    | timestamp with time zone |                          | NO          |
 classification_rules |                3 | market        | character varying        |                      255 | NO          |
 classification_rules |                4 | max_distance  | double precision         |                          | NO          |
 classification_rules |                5 | min_notional  | double precision         |                          | NO          |
 classification_rules |                6 | symbol        | character varying        |                      255 | NO          |
 classification_rules |                7 | tier_no       | integer                  |                          | NO          |
 classification_rules |                8 | updated_at    | timestamp with time zone |                          | NO          |
 classification_rules |                9 | user_id       | uuid                     |                          | NO          |
 refresh_tokens       |                1 | id            | uuid                     |                          | NO          |
 refresh_tokens       |                2 | created_at    | timestamp with time zone |                          | NO          |
 refresh_tokens       |                3 | expires_at    | timestamp with time zone |                          | NO          |
 refresh_tokens       |                4 | token_hash    | character varying        |                      255 | NO          |
 refresh_tokens       |                5 | user_id       | uuid                     |                          | NO          |
 users                |                1 | id            | uuid                     |                          | NO          |
 users                |                2 | created_at    | timestamp with time zone |                          | NO          |
 users                |                3 | email         | character varying        |                      255 | NO          |
 users                |                4 | enabled       | boolean                  |                          | NO          |
 users                |                5 | first_name    | character varying        |                      255 | NO          |
 users                |                6 | last_name     | character varying        |                      255 | NO          |
 users                |                7 | password_hash | character varying        |                      255 | NO          |
 users                |                8 | role          | character varying        |                      255 | NO          |
(22 rows)
```


### 4. Indexes:

``` sql 
SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
ORDER BY tablename, indexname;
```

```text
      tablename       |          indexname          |                                             indexdef
----------------------+-----------------------------+---------------------------------------------------------------------------------------------------
 classification_rules | classification_rules_pkey   | CREATE UNIQUE INDEX classification_rules_pkey ON public.classification_rules USING btree (id)
 refresh_tokens       | refresh_tokens_pkey         | CREATE UNIQUE INDEX refresh_tokens_pkey ON public.refresh_tokens USING btree (id)
 refresh_tokens       | uko2mlirhldriil2y7krapq4frt | CREATE UNIQUE INDEX uko2mlirhldriil2y7krapq4frt ON public.refresh_tokens USING btree (token_hash)
 users                | uk6dotkott2kjsp8vw4d0m25fb7 | CREATE UNIQUE INDEX uk6dotkott2kjsp8vw4d0m25fb7 ON public.users USING btree (email)
 users                | users_pkey                  | CREATE UNIQUE INDEX users_pkey ON public.users USING btree (id)
(5 rows)
```


### 5. Constraints — PK / FK / UNIQUE incl. FK delete rule:

``` sql 
SELECT tc.table_name, tc.constraint_type, tc.constraint_name,
kcu.column_name, rc.delete_rule
FROM information_schema.table_constraints tc
LEFT JOIN information_schema.key_column_usage kcu
ON tc.constraint_name = kcu.constraint_name
AND tc.table_schema   = kcu.table_schema
LEFT JOIN information_schema.referential_constraints rc
ON tc.constraint_name = rc.constraint_name
AND tc.table_schema   = rc.constraint_schema
WHERE tc.table_schema = 'public'
ORDER BY tc.table_name, tc.constraint_type, tc.constraint_name, kcu.ordinal_position;
```

```text
      table_name      | constraint_type |          constraint_name          | column_name | delete_rule
----------------------+-----------------+-----------------------------------+-------------+-------------
 classification_rules | CHECK           | 2200_16386_1_not_null             |             |
 classification_rules | CHECK           | 2200_16386_2_not_null             |             |
 classification_rules | CHECK           | 2200_16386_3_not_null             |             |
 classification_rules | CHECK           | 2200_16386_4_not_null             |             |
 classification_rules | CHECK           | 2200_16386_5_not_null             |             |
 classification_rules | CHECK           | 2200_16386_6_not_null             |             |
 classification_rules | CHECK           | 2200_16386_7_not_null             |             |
 classification_rules | CHECK           | 2200_16386_8_not_null             |             |
 classification_rules | CHECK           | 2200_16386_9_not_null             |             |
 classification_rules | CHECK           | classification_rules_market_check |             |
 classification_rules | FOREIGN KEY     | fkqhfboitvhl5h7uyhyyfsg1ha9       | user_id     | NO ACTION
 classification_rules | PRIMARY KEY     | classification_rules_pkey         | id          |
 refresh_tokens       | CHECK           | 2200_16394_1_not_null             |             |
 refresh_tokens       | CHECK           | 2200_16394_2_not_null             |             |
 refresh_tokens       | CHECK           | 2200_16394_3_not_null             |             |
 refresh_tokens       | CHECK           | 2200_16394_4_not_null             |             |
 refresh_tokens       | CHECK           | 2200_16394_5_not_null             |             |
 refresh_tokens       | FOREIGN KEY     | fk1lih5y2npsf8u5o3vhdb9y0os       | user_id     | NO ACTION
 refresh_tokens       | PRIMARY KEY     | refresh_tokens_pkey               | id          |
 refresh_tokens       | UNIQUE          | uko2mlirhldriil2y7krapq4frt       | token_hash  |
 users                | CHECK           | 2200_16399_1_not_null             |             |
 users                | CHECK           | 2200_16399_2_not_null             |             |
 users                | CHECK           | 2200_16399_3_not_null             |             |
 users                | CHECK           | 2200_16399_4_not_null             |             |
 users                | CHECK           | 2200_16399_5_not_null             |             |
 users                | CHECK           | 2200_16399_6_not_null             |             |
 users                | CHECK           | 2200_16399_7_not_null             |             |
 users                | CHECK           | 2200_16399_8_not_null             |             |
 users                | CHECK           | users_role_check                  |             |
 users                | PRIMARY KEY     | users_pkey                        | id          |
 users                | UNIQUE          | uk6dotkott2kjsp8vw4d0m25fb7       | email       |
(31 rows)
```


### 6. CHECK constraints (verifies the Hibernate-generated market_check / role_check story):

``` sql 
SELECT tc.table_name, cc.constraint_name, cc.check_clause
FROM information_schema.check_constraints cc
JOIN information_schema.table_constraints tc
ON cc.constraint_name = tc.constraint_name
AND cc.constraint_schema = tc.table_schema
WHERE tc.table_schema = 'public'
ORDER BY tc.table_name, cc.constraint_name;
```

```text
      table_name      |          constraint_name          |                                            check_clause
----------------------+-----------------------------------+-----------------------------------------------------------------------------------------------------
 classification_rules | 2200_16386_1_not_null             | id IS NOT NULL
 classification_rules | 2200_16386_2_not_null             | created_at IS NOT NULL
 classification_rules | 2200_16386_3_not_null             | market IS NOT NULL
 classification_rules | 2200_16386_4_not_null             | max_distance IS NOT NULL
 classification_rules | 2200_16386_5_not_null             | min_notional IS NOT NULL
 classification_rules | 2200_16386_6_not_null             | symbol IS NOT NULL
 classification_rules | 2200_16386_7_not_null             | tier_no IS NOT NULL
 classification_rules | 2200_16386_8_not_null             | updated_at IS NOT NULL
 classification_rules | 2200_16386_9_not_null             | user_id IS NOT NULL
 classification_rules | classification_rules_market_check | (((market)::text = ANY ((ARRAY['SPOT'::character varying, 'FUTURES'::character varying])::text[])))
 refresh_tokens       | 2200_16394_1_not_null             | id IS NOT NULL
 refresh_tokens       | 2200_16394_2_not_null             | created_at IS NOT NULL
 refresh_tokens       | 2200_16394_3_not_null             | expires_at IS NOT NULL
 refresh_tokens       | 2200_16394_4_not_null             | token_hash IS NOT NULL
 refresh_tokens       | 2200_16394_5_not_null             | user_id IS NOT NULL
 users                | 2200_16399_1_not_null             | id IS NOT NULL
 users                | 2200_16399_2_not_null             | created_at IS NOT NULL
 users                | 2200_16399_3_not_null             | email IS NOT NULL
 users                | 2200_16399_4_not_null             | enabled IS NOT NULL
 users                | 2200_16399_5_not_null             | first_name IS NOT NULL
 users                | 2200_16399_6_not_null             | last_name IS NOT NULL
 users                | 2200_16399_7_not_null             | password_hash IS NOT NULL
 users                | 2200_16399_8_not_null             | role IS NOT NULL
 users                | users_role_check                  | (((role)::text = 'USER'::text))
(24 rows)
```


### 7. Duplicate rule-tier check (V7 pre-flight)

``` sql 
SELECT user_id, symbol, market, tier_no, COUNT(*)
FROM classification_rules
GROUP BY user_id, symbol, market, tier_no
HAVING COUNT(*) > 1;
```

```text
 user_id | symbol | market | tier_no | count
---------+--------+--------+---------+-------
(0 rows)
```

---

# LOCAL results


### 1. Tables present (the critical one — proves prod's V4-only state):

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;
```

```text
      table_name
----------------------
 classification_rules
 plan_prices
 plans
 refresh_tokens
 user_entitlement
 users
(6 rows)
```


### 2. Flyway history existence (should be false in both, pre-fix):

```sql
SELECT to_regclass('public.flyway_schema_history') IS NOT NULL AS flyway_history_exists;
```

```text
 flyway_history_exists
-----------------------
 f
(1 row)
```


### 3. Columns — types, lengths, nullability, defaults:

```sql
SELECT table_name, ordinal_position, column_name, data_type,
character_maximum_length, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public'
ORDER BY table_name, ordinal_position;
```

```text
      table_name      | ordinal_position |    column_name    |        data_type         | character_maximum_length | is_nullable | column_default
----------------------+------------------+-------------------+--------------------------+--------------------------+-------------+----------------
 classification_rules |                1 | id                | uuid                     |                          | NO          |
 classification_rules |                2 | created_at        | timestamp with time zone |                          | NO          |
 classification_rules |                3 | market            | character varying        |                      255 | NO          |
 classification_rules |                4 | max_distance      | double precision         |                          | NO          |
 classification_rules |                5 | min_notional      | double precision         |                          | NO          |
 classification_rules |                6 | symbol            | character varying        |                      255 | NO          |
 classification_rules |                7 | tier_no           | integer                  |                          | NO          |
 classification_rules |                8 | updated_at        | timestamp with time zone |                          | NO          |
 classification_rules |                9 | user_id           | uuid                     |                          | NO          |
 plan_prices          |                1 | id                | uuid                     |                          | NO          |
 plan_prices          |                2 | active            | boolean                  |                          | NO          |
 plan_prices          |                3 | amount            | numeric                  |                          | NO          |
 plan_prices          |                4 | created_at        | timestamp with time zone |                          | NO          |
 plan_prices          |                5 | currency          | character varying        |                        3 | NO          |
 plan_prices          |                6 | updated_at        | timestamp with time zone |                          | NO          |
 plan_prices          |                7 | plan_id           | uuid                     |                          | NO          |
 plans                |                1 | id                | uuid                     |                          | NO          |
 plans                |                2 | active            | boolean                  |                          | NO          |
 plans                |                3 | code              | character varying        |                      255 | NO          |
 plans                |                4 | created_at        | timestamp with time zone |                          | NO          |
 plans                |                5 | display_name      | character varying        |                      255 | NO          |
 plans                |                6 | duration_days     | integer                  |                          | YES         |
 plans                |                7 | type              | character varying        |                      255 | NO          |
 plans                |                8 | updated_at        | timestamp with time zone |                          | NO          |
 refresh_tokens       |                1 | created_at        | timestamp with time zone |                          | NO          |
 refresh_tokens       |                2 | expires_at        | timestamp with time zone |                          | NO          |
 refresh_tokens       |                3 | id                | uuid                     |                          | NO          |
 refresh_tokens       |                4 | user_id           | uuid                     |                          | NO          |
 refresh_tokens       |                5 | token_hash        | character varying        |                      255 | NO          |
 user_entitlement     |                1 | user_id           | uuid                     |                          | NO          |
 user_entitlement     |                2 | access_expires_at | timestamp with time zone |                          | YES         |
 user_entitlement     |                3 | has_paid          | boolean                  |                          | NO          |
 user_entitlement     |                4 | updated_at        | timestamp with time zone |                          | NO          |
 users                |                1 | enabled           | boolean                  |                          | NO          |
 users                |                2 | created_at        | timestamp with time zone |                          | NO          |
 users                |                3 | id                | uuid                     |                          | NO          |
 users                |                4 | email             | character varying        |                      255 | NO          |
 users                |                5 | first_name        | character varying        |                      255 | NO          |
 users                |                6 | last_name         | character varying        |                      255 | NO          |
 users                |                7 | password_hash     | character varying        |                      255 | NO          |
 users                |                8 | role              | character varying        |                      255 | NO          |
(41 rows)
```


### 4. Indexes:

```sql
SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
ORDER BY tablename, indexname;
```

```text
      tablename       |           indexname           |                                              indexdef
----------------------+-------------------------------+-----------------------------------------------------------------------------------------------------
 classification_rules | classification_rules_pkey     | CREATE UNIQUE INDEX classification_rules_pkey ON public.classification_rules USING btree (id)
 plan_prices          | plan_prices_pkey              | CREATE UNIQUE INDEX plan_prices_pkey ON public.plan_prices USING btree (id)
 plans                | plans_pkey                    | CREATE UNIQUE INDEX plans_pkey ON public.plans USING btree (id)
 plans                | ukbsiq2g7uq9l49v27bsijicsys   | CREATE UNIQUE INDEX ukbsiq2g7uq9l49v27bsijicsys ON public.plans USING btree (code)
 refresh_tokens       | refresh_tokens_pkey           | CREATE UNIQUE INDEX refresh_tokens_pkey ON public.refresh_tokens USING btree (id)
 refresh_tokens       | refresh_tokens_token_hash_key | CREATE UNIQUE INDEX refresh_tokens_token_hash_key ON public.refresh_tokens USING btree (token_hash)
 user_entitlement     | user_entitlement_pkey         | CREATE UNIQUE INDEX user_entitlement_pkey ON public.user_entitlement USING btree (user_id)
 users                | users_email_key               | CREATE UNIQUE INDEX users_email_key ON public.users USING btree (email)
 users                | users_pkey                    | CREATE UNIQUE INDEX users_pkey ON public.users USING btree (id)
(9 rows)
```


### 5. Constraints — PK / FK / UNIQUE incl. FK delete rule:

```sql
SELECT tc.table_name, tc.constraint_type, tc.constraint_name,
kcu.column_name, rc.delete_rule
FROM information_schema.table_constraints tc
LEFT JOIN information_schema.key_column_usage kcu
ON tc.constraint_name = kcu.constraint_name
AND tc.table_schema   = kcu.table_schema
LEFT JOIN information_schema.referential_constraints rc
ON tc.constraint_name = rc.constraint_name
AND tc.table_schema   = rc.constraint_schema
WHERE tc.table_schema = 'public'
ORDER BY tc.table_name, tc.constraint_type, tc.constraint_name, kcu.ordinal_position;
```

```text
      table_name      | constraint_type |          constraint_name          | column_name | delete_rule
----------------------+-----------------+-----------------------------------+-------------+-------------
 classification_rules | CHECK           | 2200_23146_1_not_null             |             |
 classification_rules | CHECK           | 2200_23146_2_not_null             |             |
 classification_rules | CHECK           | 2200_23146_3_not_null             |             |
 classification_rules | CHECK           | 2200_23146_4_not_null             |             |
 classification_rules | CHECK           | 2200_23146_5_not_null             |             |
 classification_rules | CHECK           | 2200_23146_6_not_null             |             |
 classification_rules | CHECK           | 2200_23146_7_not_null             |             |
 classification_rules | CHECK           | 2200_23146_8_not_null             |             |
 classification_rules | CHECK           | 2200_23146_9_not_null             |             |
 classification_rules | CHECK           | classification_rules_market_check |             |
 classification_rules | FOREIGN KEY     | fkqhfboitvhl5h7uyhyyfsg1ha9       | user_id     | NO ACTION
 classification_rules | PRIMARY KEY     | classification_rules_pkey         | id          |
 plan_prices          | CHECK           | 2200_23160_1_not_null             |             |
 plan_prices          | CHECK           | 2200_23160_2_not_null             |             |
 plan_prices          | CHECK           | 2200_23160_3_not_null             |             |
 plan_prices          | CHECK           | 2200_23160_4_not_null             |             |
 plan_prices          | CHECK           | 2200_23160_5_not_null             |             |
 plan_prices          | CHECK           | 2200_23160_6_not_null             |             |
 plan_prices          | CHECK           | 2200_23160_7_not_null             |             |
 plan_prices          | FOREIGN KEY     | fk3nj6fvlsm6p3l132u7xgwtb0m       | plan_id     | NO ACTION
 plan_prices          | PRIMARY KEY     | plan_prices_pkey                  | id          |
 plans                | CHECK           | 2200_23165_1_not_null             |             |
 plans                | CHECK           | 2200_23165_2_not_null             |             |
 plans                | CHECK           | 2200_23165_3_not_null             |             |
 plans                | CHECK           | 2200_23165_4_not_null             |             |
 plans                | CHECK           | 2200_23165_5_not_null             |             |
 plans                | CHECK           | 2200_23165_7_not_null             |             |
 plans                | CHECK           | 2200_23165_8_not_null             |             |
 plans                | CHECK           | plans_type_check                  |             |
 plans                | PRIMARY KEY     | plans_pkey                        | id          |
 plans                | UNIQUE          | ukbsiq2g7uq9l49v27bsijicsys       | code        |
 refresh_tokens       | CHECK           | 2200_23124_1_not_null             |             |
 refresh_tokens       | CHECK           | 2200_23124_2_not_null             |             |
 refresh_tokens       | CHECK           | 2200_23124_3_not_null             |             |
 refresh_tokens       | CHECK           | 2200_23124_4_not_null             |             |
 refresh_tokens       | CHECK           | 2200_23124_5_not_null             |             |
 refresh_tokens       | FOREIGN KEY     | fk1lih5y2npsf8u5o3vhdb9y0os       | user_id     | NO ACTION
 refresh_tokens       | PRIMARY KEY     | refresh_tokens_pkey               | id          |
 refresh_tokens       | UNIQUE          | refresh_tokens_token_hash_key     | token_hash  |
 user_entitlement     | CHECK           | 2200_23173_1_not_null             |             |
 user_entitlement     | CHECK           | 2200_23173_3_not_null             |             |
 user_entitlement     | CHECK           | 2200_23173_4_not_null             |             |
 user_entitlement     | FOREIGN KEY     | fk29t3ufoofg7mfj663uu7cquba       | user_id     | NO ACTION
 user_entitlement     | PRIMARY KEY     | user_entitlement_pkey             | user_id     |
 users                | CHECK           | 2200_23131_1_not_null             |             |
 users                | CHECK           | 2200_23131_2_not_null             |             |
 users                | CHECK           | 2200_23131_3_not_null             |             |
 users                | CHECK           | 2200_23131_4_not_null             |             |
 users                | CHECK           | 2200_23131_5_not_null             |             |
 users                | CHECK           | 2200_23131_6_not_null             |             |
 users                | CHECK           | 2200_23131_7_not_null             |             |
 users                | CHECK           | 2200_23131_8_not_null             |             |
 users                | CHECK           | users_role_check                  |             |
 users                | PRIMARY KEY     | users_pkey                        | id          |
 users                | UNIQUE          | users_email_key                   | email       |
(55 rows)
```


### 6. CHECK constraints (verifies the Hibernate-generated market_check / role_check story):

```sql
SELECT tc.table_name, cc.constraint_name, cc.check_clause
FROM information_schema.check_constraints cc
JOIN information_schema.table_constraints tc
ON cc.constraint_name = tc.constraint_name
AND cc.constraint_schema = tc.table_schema
WHERE tc.table_schema = 'public'
ORDER BY tc.table_name, cc.constraint_name;
```

```text
      table_name      |          constraint_name          |                                           check_clause
----------------------+-----------------------------------+---------------------------------------------------------------------------------------------------
 classification_rules | 2200_23146_1_not_null             | id IS NOT NULL
 classification_rules | 2200_23146_2_not_null             | created_at IS NOT NULL
 classification_rules | 2200_23146_3_not_null             | market IS NOT NULL
 classification_rules | 2200_23146_4_not_null             | max_distance IS NOT NULL
 classification_rules | 2200_23146_5_not_null             | min_notional IS NOT NULL
 classification_rules | 2200_23146_6_not_null             | symbol IS NOT NULL
 classification_rules | 2200_23146_7_not_null             | tier_no IS NOT NULL
 classification_rules | 2200_23146_8_not_null             | updated_at IS NOT NULL
 classification_rules | 2200_23146_9_not_null             | user_id IS NOT NULL
 classification_rules | classification_rules_market_check | ((market)::text = ANY ((ARRAY['SPOT'::character varying, 'FUTURES'::character varying])::text[]))
 plan_prices          | 2200_23160_1_not_null             | id IS NOT NULL
 plan_prices          | 2200_23160_2_not_null             | active IS NOT NULL
 plan_prices          | 2200_23160_3_not_null             | amount IS NOT NULL
 plan_prices          | 2200_23160_4_not_null             | created_at IS NOT NULL
 plan_prices          | 2200_23160_5_not_null             | currency IS NOT NULL
 plan_prices          | 2200_23160_6_not_null             | updated_at IS NOT NULL
 plan_prices          | 2200_23160_7_not_null             | plan_id IS NOT NULL
 plans                | 2200_23165_1_not_null             | id IS NOT NULL
 plans                | 2200_23165_2_not_null             | active IS NOT NULL
 plans                | 2200_23165_3_not_null             | code IS NOT NULL
 plans                | 2200_23165_4_not_null             | created_at IS NOT NULL
 plans                | 2200_23165_5_not_null             | display_name IS NOT NULL
 plans                | 2200_23165_7_not_null             | type IS NOT NULL
 plans                | 2200_23165_8_not_null             | updated_at IS NOT NULL
 plans                | plans_type_check                  | ((type)::text = ANY ((ARRAY['FIXED'::character varying, 'PER_DAY'::character varying])::text[]))
 refresh_tokens       | 2200_23124_1_not_null             | created_at IS NOT NULL
 refresh_tokens       | 2200_23124_2_not_null             | expires_at IS NOT NULL
 refresh_tokens       | 2200_23124_3_not_null             | id IS NOT NULL
 refresh_tokens       | 2200_23124_4_not_null             | user_id IS NOT NULL
 refresh_tokens       | 2200_23124_5_not_null             | token_hash IS NOT NULL
 user_entitlement     | 2200_23173_1_not_null             | user_id IS NOT NULL
 user_entitlement     | 2200_23173_3_not_null             | has_paid IS NOT NULL
 user_entitlement     | 2200_23173_4_not_null             | updated_at IS NOT NULL
 users                | 2200_23131_1_not_null             | enabled IS NOT NULL
 users                | 2200_23131_2_not_null             | created_at IS NOT NULL
 users                | 2200_23131_3_not_null             | id IS NOT NULL
 users                | 2200_23131_4_not_null             | email IS NOT NULL
 users                | 2200_23131_5_not_null             | first_name IS NOT NULL
 users                | 2200_23131_6_not_null             | last_name IS NOT NULL
 users                | 2200_23131_7_not_null             | password_hash IS NOT NULL
 users                | 2200_23131_8_not_null             | role IS NOT NULL
 users                | users_role_check                  | ((role)::text = ANY ((ARRAY['USER'::character varying, 'ADMIN'::character varying])::text[]))
(42 rows)
```


### 7. Local-only: confirm the 3 tables are safe to drop

```sql
SELECT 'plans' AS t, COUNT(*) FROM plans
UNION ALL SELECT 'plan_prices', COUNT(*) FROM plan_prices
UNION ALL SELECT 'user_entitlement', COUNT(*) FROM user_entitlement;
```

```text
        t         | count
------------------+-------
 plans            |     0
 plan_prices      |     0
 user_entitlement |     0
(3 rows)
```
