-- Matches the adopted entity schema exactly so baseline-at-4 is literally true:
--   * VARCHAR(255) for all string columns (Hibernate default; live DBs show 255).
--   * NO DB-level DEFAULTs — the app sets every value (@GeneratedValue id, @PrePersist
--     created_at / role / enabled). The live DBs carry no column defaults.
-- The cascade FK, indexes, unique constraints, and role CHECK that close real gaps live in V7
-- (V4 introduces the role CHECK on a fresh chain; V7 re-asserts it everywhere).
CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    first_name    VARCHAR(255) NOT NULL,
    last_name     VARCHAR(255) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(255) NOT NULL,
    enabled       BOOLEAN      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);
