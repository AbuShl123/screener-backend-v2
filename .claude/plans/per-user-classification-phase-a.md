# Per-User Classification — Phase A: Persistence + REST

## Scope

**Building now**: the database table, JPA entity, repository, validation, service, and
REST controller that let an authenticated user create, read, update, and delete their
custom classification rules — including applying one rule to many `(symbol, market)`
targets in a single call.

**Not building now**: any runtime/hot-path integration. No `ClassificationRule` interface,
no `UserClassificationContext`, no `UserFeedRegistry`, no broadcaster changes. Phase A
persists rules and nothing reads them at runtime yet. The Disruptor side is untouched.

This is the "start from the end" slice: it is fully testable against PostgreSQL with zero
changes to the orderbook pipeline, and it produces the source of truth that Phase C will
load at WebSocket connect time.

See [per-user-classification-vision.md](per-user-classification-vision.md) for the full
runtime design and [auth-plan.md](auth-plan.md) for the auth/JPA foundation Phase A builds on.

---

## Design Decisions (settled)

| Decision | Choice | Rationale |
|---|---|---|
| Granularity | `(user_id, symbol, market)` | A user may want different thresholds on spot vs futures for the same coin (spot books are thinner). Matches the classifier's own `symbol:market` key. |
| Table shape | **One row per tier** | A logical rule for one `(user, symbol, market)` is 1–4 rows. Makes the tier range extensible later without a schema change. |
| `market` representation | `Market` enum stored as string (`@Enumerated(EnumType.STRING)`) | Consistent with the existing `Market` enum and how `UserRole` is persisted. A boolean `is_spot` would diverge from the domain model and cap us at two markets. **(Open to override — flagged in review.)** |
| Tier range | Restrict to `[1, 4]`, validated on every write | Matches the current 4-tier model in `ClassifiedLevel` / `computeTier`. The row-per-tier shape leaves room to widen later. |
| Write semantics | **Replace** — PUT for a `(symbol, market)` deletes all existing tier rows for that key, then inserts the new set, in one transaction | A logical rule spans rows; diffing individual rows is needless complexity. PUT is idempotent: "this is now the complete tier set for these targets." |
| Live updates | **Out of scope for Phase A** | Connect-time loading only (Phase C). REST stays entirely off the hot path. |

---

## Database

### Migration: `V3__create_classification_rules.sql`

```sql
CREATE TABLE classification_rules (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    symbol       VARCHAR(32)  NOT NULL,
    market       VARCHAR(16)  NOT NULL,          -- 'SPOT' | 'FUTURES'
    tier_no      SMALLINT     NOT NULL,          -- 1..4 (enforced in app layer)
    min_notional DOUBLE PRECISION NOT NULL,      -- USD, >= 0
    max_distance DOUBLE PRECISION NOT NULL,      -- fraction, (0, 0.30]
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_rule_tier UNIQUE (user_id, symbol, market, tier_no)
);

-- Connect-time load (Phase C) and the GET endpoint both query by user_id.
CREATE INDEX idx_classification_rules_user_id ON classification_rules(user_id);
```

Notes:
- `ON DELETE CASCADE` — deleting a user removes their rules, same pattern as `refresh_tokens`.
- `DOUBLE PRECISION` mirrors the project's primitive-`double` rule for market data — **no `NUMERIC`/`BigDecimal`**, consistent with CLAUDE.md.
- The `[1,4]` and `(0,0.30]` bounds are validated in the service (clear error messages) rather
  than as `CHECK` constraints, so the API returns a readable 400 instead of a DB exception.
  A `CHECK (tier_no BETWEEN 1 AND 4)` may be added as a defense-in-depth backstop — optional.

---

## JPA Layer

### Entity: `analysis/rule/ClassificationRuleEntity.java`

```java
@Entity
@Table(name = "classification_rules")
@Getter @Setter @NoArgsConstructor
public class ClassificationRuleEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Market market;

    @Column(name = "tier_no", nullable = false)
    private int tierNo;

    @Column(name = "min_notional", nullable = false)
    private double minNotional;

    @Column(name = "max_distance", nullable = false)
    private double maxDistance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist  private void prePersist()  { createdAt = updatedAt = Instant.now(); }
    @PreUpdate   private void preUpdate()   { updatedAt = Instant.now(); }
}
```

`fetch = FetchType.LAZY` (not EAGER like `RefreshToken`) — we never need the full `User` graph
when loading rules; we filter by `user_id` directly.

### Repository: `analysis/rule/ClassificationRuleRepository.java`

```java
public interface ClassificationRuleRepository
        extends JpaRepository<ClassificationRuleEntity, UUID> {

    List<ClassificationRuleEntity> findByUserId(UUID userId);

    List<ClassificationRuleEntity> findByUserIdAndSymbolAndMarket(
            UUID userId, String symbol, Market market);

    @Modifying
    void deleteByUserIdAndSymbolAndMarket(UUID userId, String symbol, Market market);
}
```

Spring Data derives `findByUserId(...)` through the `user` association automatically.

---

## REST API

New `ClassificationRuleController` under `/api/screener/rules`. This path already falls under
`.requestMatchers("/api/screener/**").authenticated()` in `SecurityConfig`, so **no security
config change is needed**. `userId` always comes from the JWT principal (same pattern as
`AuthController.me()`), never from the request body.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/screener/rules` | List all of the caller's configured rules, grouped by `(symbol, market)` |
| `GET` | `/api/screener/rules/{symbol}/{market}` | The caller's rule for one pair (404 if none) |
| `PUT` | `/api/screener/rules` | **Bulk upsert** — one or more rules, each applied to many targets |
| `DELETE` | `/api/screener/rules` | **Bulk delete** (reset to default) — list of targets |

### `PUT /api/screener/rules` — request body

A list of assignments; each assignment is one rule body applied to many targets. This single
shape covers both "same rule for 10 tickers" and "different rules in one call":

```jsonc
{
  "assignments": [
    {
      "rule": {
        "tiers": [
          { "tier": 4, "minNotional": 5000000, "maxDistance": 0.04 },
          { "tier": 1, "minNotional": 200000,  "maxDistance": 0.01 }
        ]
      },
      "targets": [
        { "symbol": "BTCUSDT", "market": "FUTURES" },
        { "symbol": "ETHUSDT", "market": "FUTURES" },
        { "symbol": "SOLUSDT", "market": "SPOT" }
      ]
    }
  ]
}
```

Semantics: for each `(assignment, target)` pair, within **one transaction**, delete all existing
rows for `(userId, symbol, market)` then insert the assignment's tier rows. Replace, not merge.

### `DELETE /api/screener/rules` — request body

```jsonc
{ "targets": [ { "symbol": "BTCUSDT", "market": "FUTURES" } ] }
```

Deletes all tier rows for each target. Deleting a non-existent rule is a no-op (idempotent).

### `GET /api/screener/rules` — response body

```jsonc
[
  {
    "symbol": "BTCUSDT",
    "market": "FUTURES",
    "tiers": [
      { "tier": 4, "minNotional": 5000000, "maxDistance": 0.04 },
      { "tier": 1, "minNotional": 200000,  "maxDistance": 0.01 }
    ]
  }
]
```

### DTOs (`analysis/rule/dto/`)

- `TierDto(int tier, double minNotional, double maxDistance)`
- `RuleDto(List<TierDto> tiers)`
- `TargetDto(String symbol, Market market)`
- `RuleAssignmentDto(RuleDto rule, List<TargetDto> targets)`
- `BulkRuleRequest(List<RuleAssignmentDto> assignments)`
- `BulkDeleteRequest(List<TargetDto> targets)`
- `RuleResponse(String symbol, Market market, List<TierDto> tiers)` — GET shape

Records, mirroring the auth DTO style.

---

## Validation (`ClassificationRuleService`)

All checks run before any DB write; the whole request is rejected atomically on the first
failure (`400 Bad Request` with a clear message). No partial application.

Per assignment / tier:
1. `tiers` non-empty.
2. Every `tier` ∈ `[1, 4]`.
3. No duplicate `tier` within a single assignment's rule.
4. Tiers must form a **contiguous set starting at 1** (no gaps) — `{1,2,4}` is rejected because
   tier 3 is missing. Enforced as `maxTier == tiers.size()`.
5. `minNotional` ≥ 0.
6. `maxDistance` ∈ `(0, priceFilterThreshold]` — the upper bound is read from the live
   `OrderbookProperties.priceFilterThreshold` (`screener.orderbook.price-filter-threshold`,
   currently `0.1`), **not** a hardcoded constant. Values beyond the orderbook's price filter
   are meaningless: those levels are already swept, so the rule could never match them.

Per target:
7. `symbol` + `market` must be a **currently-tracked ticker** — validate against
   `TickerRegistry.find(symbol)` and confirm the ticker covers the requested market.
   Reject unknown symbols/markets so a user gets a clear error rather than a silently dead rule.

Request-level guardrails:
8. Cap total targets per request (`screener.classification.max-targets-per-request`, default 200)
   to bound a single transaction's size — configurable.

> Monotonicity across tiers (higher tier ⇒ higher notional) is intentionally **not** enforced.
> The highest-first evaluation loop tolerates non-monotonic configs, and power users may want
> unusual shapes. Validate ranges, not taste.

---

## Service: `ClassificationRuleService` (`@Service`)

Tomcat-thread component. Owns the repository. For Phase A it is purely CRUD + validation;
it does **not** touch any runtime state.

```
upsertRules(UUID userId, BulkRuleRequest req)
  validate(req)                                  // throws -> 400
  @Transactional:
    for each assignment, for each target:
      deleteByUserIdAndSymbolAndMarket(userId, symbol, market)
      insert one row per tier

deleteRules(UUID userId, BulkDeleteRequest req)
  @Transactional:
    for each target: deleteByUserIdAndSymbolAndMarket(...)

getRules(UUID userId) -> List<RuleResponse>      // group rows by (symbol, market)

getRule(UUID userId, String symbol, Market market) -> RuleResponse  // 404 if empty
```

> **Phase C seam (do not build now):** a future `ClassificationRuleService` will add an
> in-memory `ConcurrentHashMap<UUID, UserRuleSet>` cache and a translation step that turns
> these rows into an immutable runtime `ClassificationRule`, consumed by `UserFeedRegistry`
> at WebSocket connect time. Phase A deliberately stops at persistence so that seam stays clean.

---

## Package Layout (Phase A additions)

```
src/main/java/dev/abu/screener_backend/analysis/rule/
├── ClassificationRuleEntity.java
├── ClassificationRuleRepository.java
├── ClassificationRuleService.java
├── ClassificationRuleController.java
└── dto/
    ├── TierDto.java
    ├── RuleDto.java
    ├── TargetDto.java
    ├── RuleAssignmentDto.java
    ├── BulkRuleRequest.java
    ├── BulkDeleteRequest.java
    └── RuleResponse.java

src/main/resources/db/migration/
└── V3__create_classification_rules.sql
```

Placed under `analysis/` (alongside `OrderBookClassifier`) since these rules are conceptually
part of the classification domain, and Phase B/C code that consumes them lives there too.

---

## Implementation Steps

1. Write `V3__create_classification_rules.sql`.
2. Create `ClassificationRuleEntity` + `ClassificationRuleRepository`.
3. Create the DTO records.
4. Implement `ClassificationRuleService` — validation + transactional CRUD.
5. Implement `ClassificationRuleController` — principal extraction + delegation.
6. Smoke test (below).

---

## Smoke Test

1. `PUT /api/screener/rules` with one rule + 3 targets → 200; DB has the expected rows.
2. `GET /api/screener/rules` → the 3 configured pairs, tiers grouped correctly.
3. `PUT` the same target with a different tier set → old rows replaced, not duplicated.
4. `GET /api/screener/rules/BTCUSDT/FUTURES` → that pair; unknown pair → 404.
5. `DELETE /api/screener/rules` with a target → rows gone; second delete → still 200 (idempotent).
6. `PUT` with `tier: 5` → 400. `maxDistance: 0.5` → 400. Unknown symbol → 400.
7. All endpoints without a Bearer token → 401 (existing security chain).
8. User A cannot see or modify User B's rules (rules are always scoped by JWT `userId`).

---

## What Phase A Does NOT Do

- No `ClassificationRule` interface or classifier refactor — that is **Phase B**.
- No runtime context, registry, feed store, or broadcaster changes — that is **Phase C/D**.
- No live propagation to connected users — connect-time loading only, added in Phase C.
- No reads of these rows by any Disruptor-thread code — the hot path is untouched.
