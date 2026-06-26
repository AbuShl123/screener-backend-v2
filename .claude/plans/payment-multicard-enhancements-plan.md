# Payment Gateway (Multicard) — Enhancements & Hardening Plan

## Status & Scope

This plan follows `payment-gateway-multicard-plan.md` (the initial, already-implemented build). It is a
**review-driven hardening pass**: it does not add new features, it closes correctness, concurrency, and
robustness gaps found while reviewing the implemented payment/Multicard modules. Each item below records
the **problem**, the **decision** (agreed in review), and an **implementation sketch** with the files
touched.

**Prerequisites already in place:** `orders` + `order_status_history` + `entitlement_ledger`,
`PaymentProvider` + `MulticardPaymentProvider`, order create/reuse, success-callback handler,
reconciliation sweep, `OrderStateMachine`, `EntitlementService.extend(...)` with ledger.

**In scope (this plan):**
- Centralize all entitlement grants to the callback + sweep only (E1).
- Make all `orders` (and related) status mutations concurrency-safe (E2) — backed by a full scan of
  unsafe sites.
- Handle the undocumented Multicard `cancelled` invoice status (E3).
- Document the null-`provider_uuid` sweep branch as defensive-only + add a loud log (E4).
- Add amount verification to the reconciliation sweep, mirroring the callback (E5).
- Allow an authoritative late `SUCCESS` to rescue a non-`PENDING` order to `PAID`, excluding
  `REVERTED` (E6).
- Make the "latest status reason" lookup deterministic via a monotonic sequence, not a timestamp (E7).
- Record (not fix) the `X-Forwarded-For` source-IP trust risk in code + this plan (E8).
- Config note on `ofd-enabled` (E9).

**Explicitly NOT in scope (unchanged deferrals):** auto-renewal / saved cards, refund initiation,
per-status (full) webhooks, access-gate enforcement, multi-provider selection wiring, production `ofd`
tax-data payload, the `X-Forwarded-For` spoofing fix (recorded only).

---

## Decisions Locked (this review)

| # | Decision | Rationale |
|---|----------|-----------|
| E1 | **Only the success callback and the reconciliation sweep grant access / mark orders `PAID`.** `createOrReuse` never grants — `grantIfProviderPaid` is removed. | One mutation surface, both funnelling through the pessimistically-locked `markPaidAndGrant`. Also removes a blocking Multicard HTTP call (`fetchPayment`) made inside the create DB transaction. |
| E2 | **All `orders` status mutations go through a locked reload (`findByIdForUpdate`) + a re-check of current state.** Add `@Version` to `Order` as defense-in-depth. The two unlocked `createOrReuse` transitions are the only offenders. **The locked supersede/expire helpers must `flush()` their UPDATE before `createNew`'s INSERT**, preserving the one-open-order partial-index invariant. | `Order` has no `@Version`; unlocked read-then-mutate is last-write-wins, racing the sweep/callback. Pessimistic lock + re-check is already the established safe pattern (`markPaidAndGrant`, `expire`). The flush ordering matters because Hibernate emits INSERTs before UPDATEs at autoflush; without an explicit flush the new `CREATED` row and the not-yet-`EXPIRED`/`CANCELED` old row both satisfy `WHERE status IN ('CREATED','PENDING')` for an instant → spurious unique-violation. |
| E3 | **Treat Multicard `cancelled` (and `canceled`) as a terminal, closed, unpaid status** → maps to `ProviderStatus.CANCELED`; the sweep moves such an order to `EXPIRED` (`INVOICE_EXPIRED`). | Confirmed empirically: an invoice becomes `cancelled` when its TTL expires or it is deleted before payment. The docs' `PaymentStatusEnum` omits it — reality wins. The current `default → PENDING` fallback would otherwise re-poll a dead invoice until `expires_at`. |
| E4 | **Keep the null-`provider_uuid` sweep branch but mark it defensive-only; emit a loud `WARN` if ever hit.** | By design a persisted `PENDING` order always has a `uuid` (create is single-transaction; a failed `createCheckout` rolls the row back). The branch is unreachable today; a hit signals a design/DB invariant break worth shouting about. |
| E5 | **The reconciliation sweep verifies `payment.amountTiyin == toTiyin(order.amount)` before granting on `SUCCESS`**, mirroring the callback's `AMOUNT_MISMATCH` guard. | The callback guards amount; the sweep currently does not (the field is captured but unused). Symmetric defense; the durable `GET /payment/{uuid}` carries `total_amount`. |
| E6 | **An authoritative late `SUCCESS` may rescue a non-`PENDING` order to `PAID`** from `EXPIRED`/`FAILED`/`CANCELED` — **callback source only**. The reconciliation sweep grants only orders still `PENDING` at lock time; it never resurrects a terminal order. **`REVERTED → PAID` stays permanently illegal.** | A success callback with a valid `uuid` + amount means the user paid; a race that expired the order first (e.g. a concurrent stale-expire in `createOrReuse`) must not let the callback throw an illegal transition and strand a paying user. The sweep only ever *scans* `PENDING` orders, so "sweep rescue of a terminal order" is unreachable in practice and is deliberately disallowed — keeping the resurrection privilege to the callback, the one authoritative push from the provider. Refunded (`REVERTED`) money must never grant. |
| E7 | **`order_status_history` gets a monotonic `seq` (DB identity); "latest reason" orders by `seq DESC`, not `created_at DESC`.** | Two transitions can share a `created_at` tick (app-clock `Instant.now()`), making the latest-row lookup ambiguous. A monotonic sequence is deterministic. |
| E8 | **Record the `X-Forwarded-For` source-IP trust risk** in code + plan; do not fix now. | If the app is reachable bypassing the trusted proxy, `X-Forwarded-For` can be spoofed to defeat the IP allow-list, leaving only the shared-secret MD5 signature. Accepted for now; must be revisited before exposing the callback outside a trusted proxy. |
| E9 | **`ofd-enabled` stays `true` in committed `application.yml` but becomes env-overridable; `application-local.yml` forces it `false`.** The `buildOfd` payload is a **placeholder** and must carry real `mxik`/`package_code` tax data before it is relied on in production. | Dev/sandbox uses `application-local.yml` (ofd off, accepted by sandbox). Production leans `ofd` on, but enabling it with the current placeholder payload sends incomplete fiscal data — a go-to-production dependency, flagged loudly. |

---

## Concurrency: the full picture (E2)

### The unsafe sites (scan result)

Only **two** order-mutation sites read without a lock and then mutate — both in
`OrderService.createOrReuse`:

| Site | Line (current) | Read | Mutation | Race |
|------|----------------|------|----------|------|
| Stale-expire branch | `OrderService.java:80` → `:86` | `findFirstByUser_IdAndStatusIn` (no lock) | `transition(existing → EXPIRED)` | Sweep `expire()` (locked) or a callback may move the same row first → lost update / duplicate history / illegal-transition throw |
| Supersede-cancel branch | `OrderService.java:80` → `:100` | same unlocked read | `provider.cancelCheckout` + `transition(existing → CANCELED)` | Same as above |

**Already safe (locked reload + state re-check), for reference — do not change the locking:**
- `markPaidAndGrant` (`:191`) — `findByIdForUpdate` + `if status == PAID return`.
- `expire` (`:218`) — `findByIdForUpdate` + `isOpen()` check.
- `transitionPendingOnly` (`markFailed`/`markReverted`, `:226`) — `findByIdForUpdate` + `status == PENDING` check.
- `createNew` (`:141`, `CREATED → PENDING`) — fresh row, same transaction, not yet visible to the sweep (`PENDING`-scan) until commit; the partial-unique index guards the concurrent-create race.
- Callback-vs-sweep "both mark paid" — **safe**: each is a separate transaction through `findByIdForUpdate`; the row lock serializes them and the loser short-circuits on `status == PAID`. No change needed.

### Related entity (note, low priority)

- `UserEntitlement` also has **no `@Version`**; `EntitlementService.extend` reads via `findByUserId`
  (no lock) then mutates. Concurrent extends on one user are currently impossible (the one-open-order
  index ⇒ at most one payable order per user, and trial seeding happens only at registration). Add
  `@Version` to `UserEntitlement` defensively for future admin/gift grants, but no behavioural change
  is required now.

### The fix (E2)

1. **Route `createOrReuse`'s two branches through locked helpers** that reload by id under
   `findByIdForUpdate` and re-check `isOpen()` before transitioning:
   - Stale branch → reuse the existing `expire(orderId, OrderSource.API)`.
   - Supersede branch → add `supersede(orderId)`: locked reload, `isOpen()` re-check, transition
     `→ CANCELED` (`SUPERSEDED`); call `provider.cancelCheckout` **best-effort** (the external call
     stays out of the lock's critical intent — cancel after the local transition; a provider failure
     is logged, never blocks the supersede). If the re-check finds the order no longer open (a
     concurrent sweep/callback won), no-op and fall through to create.
   - **L1-cache caveat (why `@Version` is load-bearing, not just defensive here).** Because
     `createOrReuse` already loaded the open order via the unlocked `findFirstByUser_IdAndStatusIn`, it
     sits in the same persistence context; the helper's `findByIdForUpdate` returns that **cached
     instance and upgrades the lock without refreshing its fields**. So the `isOpen()` re-check is
     accurate in the common case (and in the unit tests, whose in-memory repo reflects current state),
     but under a *genuine* concurrent close it reads the stale snapshot, lets the transition proceed, and
     the flush then fails the version check → `OptimisticLockException` (safe rollback; the SPA retries
     and the next lookup sees the committed state). The opposite ordering — we win the lock first — is
     handled by E6 (the late callback rescues `EXPIRED`/`CANCELED → PAID`). Both orderings are safe:
     no lost update, no corruption. We deliberately do **not** introduce `EntityManager.refresh` to force
     a perfectly graceful no-op — the codebase is repository-only, the race is vanishingly rare, and
     `@Version` already makes it safe.
   - **Preserve flush ordering.** Each helper must `orderRepository.flush()` after its transition (or
     `createOrReuse` must flush after invoking it) **before** `createNew` issues its INSERT. This keeps
     the existing inline behaviour (`orderRepository.flush()` after the EXPIRED/CANCELED transition):
     Hibernate orders INSERTs ahead of UPDATEs at autoflush, so without the explicit flush the new
     `CREATED` row and the old still-open row momentarily collide on the
     `uq_orders_one_open_per_user` partial index (`WHERE status IN ('CREATED','PENDING')`), throwing a
     spurious `DataIntegrityViolationException`. Putting the flush inside `expire`/`supersede` is the
     cleanest spot since they are the ones doing the open→terminal transition.
2. **Add `@Version` (`version BIGINT NOT NULL DEFAULT 0`) to `Order`.** Not merely future insurance: per
   the L1-cache caveat above it is the actual guarantor for the concurrent-close race in `createOrReuse`
   (stale re-check → write fails loudly with `OptimisticLockException` instead of a silent lost update).
   It also catches any future unlocked path. The freshly-loaded locked paths (callback/sweep
   `markPaidAndGrant`, sweep `expire`) never contend, so it never fires spuriously there.
3. **Make `OrderStateMachine.transition` tolerant of benign no-ops**: if `from == to`, return without
   writing a duplicate history row (covers a re-check miss under race). Illegal transitions still throw.

> Self-invocation note: `createOrReuse` calling `this.expire(...)`/`this.supersede(...)` runs in the
> same transaction (already `@Transactional`); the pessimistic lock is a repository-level
> `SELECT … FOR UPDATE` and works regardless of the proxy boundary. This is correct — but it means the
> `@Transactional` on those helpers is a no-op when self-invoked, which is fine here.

---

## Enhancement Items

### E1 — Centralize grants (remove `grantIfProviderPaid`)
- **Delete** `OrderService.grantIfProviderPaid(...)` and its call in the same-plan reuse branch.
- The same-plan branch simply returns
  `new CreateOrderResponse(existing.getId(), existing.getStatus(), existing.getCheckoutUrl(), false)`.
- A user who paid on a lost tab and re-opens the app sees `PENDING` until the next sweep cycle
  (`reconciliation-interval`, default `PT1M`) grants and flips status to `PAID`. Acceptable.
- **Files:** `OrderService.java`. **Tests:** drop the double-pay-on-reuse test; assert reuse returns
  the existing `PENDING` without any provider/grant call.

### E2 — Concurrency safety
See "Concurrency: the full picture" above. **Files:** `OrderService.java` (locked `supersede`, reuse
`expire`), `Order.java` (`@Version`), `OrderStateMachine.java` (no-op on `from == to`),
`UserEntitlement.java` (`@Version`, optional). **Migration:** add `version` columns (see Migrations).

### E3 — Handle the `cancelled` provider status
- Add `ProviderStatus.CANCELED`.
- `MulticardPaymentProvider.mapStatus`: map both `"cancelled"` and `"canceled"` → `ProviderStatus.CANCELED`
  (guard the spelling — docs use one `l`, the observed value had two). Keep `default → PENDING` with the
  existing `WARN` for anything still unknown.
- `PaymentReconciliationService.reconcileOne`: add a `CANCELED` arm → `orderService.expire(orderId,
  RECONCILIATION)` (terminal `EXPIRED` / `INVOICE_EXPIRED`). A cancelled invoice is closed and unpaid.
- **Files:** `ProviderStatus.java`, `MulticardPaymentProvider.java`, `PaymentReconciliationService.java`.
- **Tests:** `mapStatus("cancelled")`/`"canceled"` → `CANCELED`; sweep `CANCELED` → order `EXPIRED`,
  no entitlement change.

### E4 — Null-`provider_uuid` sweep branch: defensive-only + loud log
- Keep the branch; add a `log.error(...)` (loud — it indicates a broken invariant: a persisted
  `PENDING` order must always have a `provider_uuid` because create is single-transaction) before the
  stale-expire fallback. Add a code comment + a line in `CURRENT_STATE.md`/this plan that the branch is
  defensive-only and unreachable by design.
- **Files:** `PaymentReconciliationService.java`.

### E5 — Amount verification in the sweep
- In `reconcileOne`'s `SUCCESS` arm, before `markPaidAndGrant`, compare
  `payment.amountTiyin()` to `MulticardPaymentProvider.toTiyin(order.getAmount())`. On mismatch: do
  **not** grant; transition `→ FAILED` with `AMOUNT_MISMATCH` (`reason_detail` = the observed vs
  expected tiyin), and log a `WARN`. (Reuse `AMOUNT_MISMATCH`; it is no longer callback-only.)
- Handle a `null` `amountTiyin` defensively (treat as mismatch / skip-and-log; do not NPE).
- **Files:** `PaymentReconciliationService.java`, possibly a small helper on `OrderService`
  (`markAmountMismatch(orderId, detail)` following the `transitionPendingOnly` pattern).
- **Tests:** sweep `SUCCESS` with wrong amount → no grant, order `FAILED`; correct amount → grants.

### E6 — Late-success rescue of a non-`PENDING` order (callback-only)
- `OrderStateMachine.ALLOWED`: add `PAID` as a legal target from `EXPIRED`, `FAILED`, and `CANCELED`
  (so the callback path is structurally permitted). `PENDING → PAID` stays. `REVERTED` stays terminal
  (no `PAID`). The **source gating** lives in `markPaidAndGrant`, not the state machine.
- `markPaidAndGrant(orderId, ps, source)` re-check under lock becomes:
  - `status == PAID` → return (idempotent replay).
  - `status == REVERTED` → `log.warn` and return **without granting** (refunded money never resurrects).
  - `status == PENDING` → transition `→ PAID` and grant (the normal path; both sources).
  - terminal `EXPIRED`/`FAILED`/`CANCELED`:
    - `source == CALLBACK` → **rescue**: transition `→ PAID` and grant (the user demonstrably paid; a
      race expired the order first, and the authoritative success callback must not be left to throw an
      illegal transition / 500 and have Multicard reverse a good payment).
    - `source == RECONCILIATION` → `log.warn` and return **without granting**. The sweep only *scans*
      `PENDING`, so it reaches a terminal order here only via a rare lock-time race; we deliberately do
      not let it resurrect — the callback is the authoritative resurrection path.
- Amount is already verified upstream before this is reached (callback today; sweep via E5).
- **Residual edge (accepted):** a *lost* success callback **and** a concurrent stale-expire in
  `createOrReuse` could strand a paid order in `EXPIRED` (the sweep won't rescue it, the callback never
  arrived). Vanishingly rare; recovery is an admin/manual concern, consistent with the refund-handling
  deferral. Flagged, not solved here.
- **Files:** `OrderStateMachine.java`, `OrderService.java`.
- **Tests:** `EXPIRED → PAID` via callback grants once; `EXPIRED → PAID` via **reconciliation does
  NOT grant** (order stays `EXPIRED`, warn logged); `REVERTED` stays `REVERTED`, no grant; idempotent
  replay still no-ops.

### E7 — Deterministic "latest reason" ordering
- Add a monotonic `seq BIGINT GENERATED ALWAYS AS IDENTITY` (or `BIGSERIAL`) to
  `order_status_history`; map it on `OrderStatusHistory` (read-only, DB-generated).
- Replace `findFirstByOrderIdOrderByCreatedAtDesc` with `findFirstByOrderIdOrderBySeqDesc` (keep the
  list query similarly ordered for stable display).
- **Files:** migration, `OrderStatusHistory.java`, `OrderStatusHistoryRepository.java`,
  `OrderService.toStatusResponse`.
- **Tests:** two transitions in the same `created_at` tick → latest reason is the higher `seq`.

### E8 — `X-Forwarded-For` source-IP trust (record only)
- Add a prominent comment in `MulticardCallbackController.resolveSourceIp` warning that the first XFF
  hop is trusted and is spoofable if the app is reachable without the trusted proxy in front; the IP
  allow-list is then only as strong as the shared-secret MD5 signature.
- No behavioural change. Captured here as a known risk to revisit before the callback is exposed
  outside a trusted-proxy deployment.
- **Files:** `MulticardCallbackController.java` (comment only).

### E9 — `ofd-enabled` config
- `application.yml`: `ofd-enabled: ${MULTICARD_OFD_ENABLED:true}` (env-overridable; prod default true).
- `application-local.yml`: `ofd-enabled: false` (sandbox accepts invoices without `ofd`).
- **Flag loudly (code comment on `buildOfd` + this plan):** the current `ofd` payload is a
  **placeholder** (`qty/price/total/name` only). Real `mxik`/`package_code`/tax-rate data is required
  before enabling `ofd` against a production merchant agreement — enabling it as-is sends incomplete
  fiscal data. Go-to-production dependency, not closed by this plan.
- **Files:** `application.yml`, `application-local.yml`, comment on `MulticardPaymentProvider.buildOfd`.
- *(Comment cleanup in `application.yml` is owned by the user — do not revert removed comments.)*

---

## Migrations

`V11__payment_concurrency_and_audit.sql` (single migration; existing run through `V10`):
```sql
-- Optimistic-locking version columns (defense-in-depth; locked paths never contend).
ALTER TABLE orders            ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE user_entitlement  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;  -- optional, future-proofing

-- Deterministic ordering of the status audit trail (replaces created_at tiebreak ambiguity).
ALTER TABLE order_status_history ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE INDEX idx_osh_order_seq ON order_status_history(order_id, seq DESC);
```
> No data backfill needed: `version` defaults to 0; `seq` is assigned to existing rows in insertion
> order by the identity column. Keep the existing `idx_osh_order` or drop it in favour of the composite.

---

## Build Order

1. **Migration `V11`** (version columns + history `seq`).
2. **E2 concurrency** — `@Version` on `Order` (+ `UserEntitlement`); `OrderStateMachine` `from == to`
   no-op; locked `supersede`, reuse `expire` in `createOrReuse` (each flushing before the new INSERT).
3. **E1** — remove `grantIfProviderPaid`; simplify the same-plan reuse branch.
4. **E6** — state-machine rescue transitions + `markPaidAndGrant` source-gated rescue (callback-only)
   + `REVERTED` guard.
5. **E3** — `ProviderStatus.CANCELED` + `mapStatus` + sweep arm.
6. **E5** — sweep amount verification (+ optional `markAmountMismatch` helper).
7. **E4** — defensive-only log + comments on the null-uuid branch.
8. **E7** — history `seq` mapping + repository/read switch to `seq DESC`.
9. **E8** — `resolveSourceIp` risk comment.
10. **E9** — `ofd-enabled` env-overridable; `application-local.yml` false; `buildOfd` placeholder comment.
11. **Tests** (below).

---

## Tests (plain JUnit, matching existing style)

- **`OrderService`** — reuse same plan returns existing `PENDING` with **no** provider/grant call (E1);
  supersede different plan locks + cancels + `CANCELED`/`SUPERSEDED`, and no-ops if the order was
  concurrently closed (E2); stale-open expire via locked `expire` (E2); supersede/expire-then-create
  in one transaction does **not** trip `uq_orders_one_open_per_user` (flush ordering, E2).
- **`OrderStateMachine`** — `from == to` is a no-op (no history row) (E2); `EXPIRED/FAILED/CANCELED →
  PAID` legal, `REVERTED → PAID` illegal (E6).
- **`MulticardCallbackService` / `markPaidAndGrant`** — late `SUCCESS` on `EXPIRED` via **callback**
  grants once; the same via **reconciliation does NOT grant** (stays `EXPIRED`) (E6); `REVERTED` order
  never grants; idempotent replay no-ops; concurrent callback+sweep grant exactly once (existing) (E6).
- **`MulticardPaymentProvider`** — `mapStatus("cancelled")` and `"canceled"` → `CANCELED` (E3); `ofd`
  attached only when enabled (existing).
- **`PaymentReconciliationService`** — `CANCELED` → order `EXPIRED`, no grant (E3); `SUCCESS` with
  mismatched amount → `FAILED`/`AMOUNT_MISMATCH`, no grant; matching amount → grants (E5); null-uuid
  branch logs at error and expires when stale (E4); one bad order doesn't abort the sweep (existing).
- **History ordering** — two transitions sharing `created_at` → latest reason is the higher `seq` (E7).

---

## CURRENT_STATE.md updates (after implementation)

- Note the `payment` module hardening: `Order`/`UserEntitlement` `@Version`; `ProviderStatus.CANCELED`;
  the sweep's amount verification and `CANCELED` handling; the late-success rescue rule
  (`EXPIRED/FAILED/CANCELED → PAID`, `REVERTED` terminal); the `order_status_history.seq` monotonic
  ordering; the defensive-only null-uuid branch; the `X-Forwarded-For` trust caveat; the `V11`
  migration. Record `ofd` production payload + the XFF spoofing fix as still-open go-to-production
  items.
