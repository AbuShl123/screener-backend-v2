# Payment Gateway (Multicard) — Detailed Implementation Plan

## Status & Scope

This is the **second concrete implementation plan** under the monetization milestone (see
`monetization-plan.md` for the vision and `subscription-entitlement-foundation-plan.md` for the
catalog/entitlement foundation already shipped). It builds the **money flow**: orders, the
provider-agnostic payment boundary, the Multicard adapter, the success-callback handler, the
reconciliation sweep, pay-by-days math, and the audit ledgers.

**Prerequisites already in place:** `plans` + `plan_prices` catalog, `user_entitlement` +
`EntitlementService.extend(...)`, `RegionResolver` (UZ/UZS stub), `Money` semantics (major-unit
`BigDecimal`), `/api/billing/plans`, ADMIN-only `/api/admin/**`.

**What this plan delivers:**
- An **`orders`** table + **`order_status_history`** + **`entitlement_ledger`** (the ledger reverses
  the foundation plan's "no ledger" decision now that real money grants access).
- A **`PaymentProvider`** seam + **`MulticardPaymentProvider`** adapter (auth-token caching,
  invoice creation, durable status fetch, tiyin conversion at the boundary).
- **Order endpoints**: create-or-reuse (returns `checkoutUrl` as JSON), and status read for UI polling.
- A **public Multicard success-callback endpoint** — signature (MD5) + source-IP verified,
  grant-before-acknowledge, idempotent on the provider `uuid`, transient-vs-permanent failure
  semantics.
- A **reconciliation sweep** (`@Scheduled`) that resolves stale `PENDING` orders via the durable
  `GET /payment/{uuid}`, catching abandonment, lost callbacks, and refunds.
- **Pay-by-days** purchase (`days = ceil(amount / pricePerDay)`).

**Explicitly NOT in this plan (unchanged from the vision doc):**
- Auto-renewal / saved cards / recurring charges.
- Refund **initiation** and refund-driven access revocation (a future admin-console concern). A
  `revert` observed during reconciliation is **recorded only**, never auto-revokes access.
- Per-status (full) webhooks. We use the **default success-only callback**. The adapter is shaped so
  switching to full webhooks (SHA-1 sign) is a localized change.
- Access-gate enforcement at REST/`@OnOpen` (its own plan), multi-provider/multi-currency,
  production fiscalization (`ofd`) data.

---

## Decisions Locked (this session)

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | **Success-only callback, not full webhooks.** | Works on test creds with no Multicard support ticket. Full webhooks add 5 status types **and still require** the reconciliation sweep (abandonment never pushes an event; callbacks can be lost), so success-only is the simpler complete system. |
| 2 | **Callback sign = MD5 of `{store_id}{invoice_id}{amount}{secret}`**, source IP `195.158.26.90`. Verify both. | Per `payments-invoice.md` → *Callback (success)*. (Full webhooks would be **SHA-1** of `{uuid}{invoice_id}{amount}{secret}` — isolated in the adapter for a future switch.) |
| 3 | **Grant before acknowledge.** Mark `PAID` + extend entitlement + write ledger/history in one transaction; **commit, then** return `200 {"success": true}`. | A "200 OK" must never be sent for a payment the user didn't actually receive access for. |
| 4 | **Transient vs permanent failure.** Our-side failure → **HTTP 500** (Multicard freezes + retries). Deliberate rejection (amount mismatch / unknown order) → **`{"success": false, "message": …}`** (Multicard reverses/refunds). Bad signature / wrong IP → **HTTP 400**, unprocessed. | Per the doc: non-200 **or** `success != true` ⇒ refund; timeout/500 ⇒ freeze + retry. Never refund a good payment over a transient error. |
| 5 | **Idempotency key = Multicard `uuid`**, enforced by a unique index on `orders.provider_uuid` + a state-guarded `PENDING → PAID` transition. | Retried callbacks repeat for the same `uuid`; grant must happen exactly once. Webhook and sweep funnel into the **same** idempotent `markPaidAndGrant(uuid)`. |
| 6 | **Reconciliation uses `GET /payment/{uuid}`** (durable), **not** `GET /payment/invoice/{uuid}`. | The invoice endpoint returns `ERROR_TRANS_NOT_READY`/`ERROR_NOT_FOUND` once expired/cancelled. `GET /payment/{uuid}` always returns `PaymentModel` with authoritative `status` (incl. `revert`). |
| 7 | **At most one open order per user** (DB partial-unique index). Same plan → reuse stored `checkoutUrl`; different plan → cancel old + create new. | Handles the lost-tab re-pay scenario; prevents a user accidentally paying two different invoices. |
| 8 | **`checkoutUrl` returned as JSON**, backend never issues a 302. | Keeps the SPA in control; reuse / already-paid responses are expressible. |
| 9 | **Order TTL 30 min**, aligned with Multicard invoice `ttl`. | So "is it still payable?" is a local `age < 30m` check without an extra API call on the reuse happy-path. |
| 10 | **`entitlement_ledger` + `order_status_history` added now.** Reverses foundation Decision #4 ("no ledger"). | Real money moving entitlement must be auditable; future admin grant/extend writes to the ledger. |
| 11 | **`ofd` (fiscalization) is config-driven, off by default.** | Verified: sandbox accepts invoice creation without `ofd`. Populate in production once a merchant agreement + tax data exist. |
| 12 | **`days_granted` and `amount` are snapshotted on the order.** | Later re-pricing or plan edits never alter a past grant. |

---

## Multicard facts that drive the design (quick reference)

From `external-docs/payments/multicard/docs/` — full detail there; the essentials:

- **Auth.** `POST /auth` (`application_id` + `secret`) → Bearer token (~24h). Cache and reuse; on a
  `401`, refetch once and retry.
- **Amounts are integer tiyin** (1 UZS = 100 tiyin). Conversion happens **only** in the adapter.
- **Create invoice.** `POST /payment/invoice` with `store_id`, `amount` (tiyin), `invoice_id` (our
  order id), `callback_url`, `return_url`/`return_error_url`, `ttl`, optional `ofd`, `lang` →
  returns `data.uuid` (persist!) and `data.checkout_url`.
- **Success callback** (`POST` to our `callback_url`): fields `store_id, amount, invoice_id,
  billing_id, payment_time, phone, card_pan, ps, card_token, uuid, receipt_url, sign`. No `status`
  field (always success). MD5 sign. Respond `200 {"success": true}`.
- **Durable status.** `GET /payment/{uuid}` → `{ success, data: PaymentModel }`; `PaymentModel.status`
  ∈ `draft|progress|billing|success|error|revert`.
- **Cancel unpaid invoice.** `DELETE /payment/invoice/{uuid}` (used when superseding an open order).

---

## Data Model

Migrations **`V8` / `V9` / `V10`** (existing run through `V7`). All money in **major units**
(`NUMERIC(19,4)`); tiyin never touches the DB.

### `orders` — one row per purchase attempt
```sql
-- V8__create_orders.sql
CREATE TABLE orders (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users(id),
    plan_id          UUID NOT NULL REFERENCES plans(id),
    status           TEXT NOT NULL,                 -- CREATED|PENDING|PAID|EXPIRED|FAILED|CANCELED|REVERTED
    reason           TEXT,                          -- detail for non-happy transitions
    days_granted     INT  NOT NULL,                 -- snapshot: FIXED=duration_days; PER_DAY=ceil(amount/pricePerDay)
    amount           NUMERIC(19,4) NOT NULL,        -- snapshot, major units (sum)
    currency         CHAR(3) NOT NULL,
    payment_provider TEXT NOT NULL DEFAULT 'multicard',
    provider_uuid    TEXT,                          -- Multicard transaction uuid (set after invoice creation)
    ps               TEXT,                          -- payment service from callback (uzcard/humo/payme/…)
    checkout_url     TEXT,
    expires_at       TIMESTAMPTZ,                   -- now + invoice ttl (30m)
    paid_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT orders_amount_nonneg CHECK (amount >= 0)
);
-- Idempotency: one order per Multicard transaction.
CREATE UNIQUE INDEX uq_orders_provider_uuid ON orders(provider_uuid) WHERE provider_uuid IS NOT NULL;
-- At most one OPEN order per user (Decision #7).
CREATE UNIQUE INDEX uq_orders_one_open_per_user ON orders(user_id) WHERE status IN ('CREATED','PENDING');
CREATE INDEX idx_orders_user_status ON orders(user_id, status);
CREATE INDEX idx_orders_open ON orders(status) WHERE status = 'PENDING';  -- sweep scan
```

### `order_status_history` — append-only audit of every transition
```sql
-- V9__create_order_status_history.sql
CREATE TABLE order_status_history (
    id          UUID PRIMARY KEY,
    order_id    UUID NOT NULL REFERENCES orders(id),
    from_status TEXT,
    to_status   TEXT NOT NULL,
    reason      TEXT,
    source      TEXT NOT NULL,                      -- API | CALLBACK | RECONCILIATION | SYSTEM
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_osh_order ON order_status_history(order_id);
```

### `entitlement_ledger` — append-only audit of every access grant
```sql
-- V10__create_entitlement_ledger.sql
CREATE TABLE entitlement_ledger (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id),
    source              TEXT NOT NULL,              -- TRIAL | PURCHASE | ADMIN
    granted_seconds     BIGINT NOT NULL,           -- the Duration applied
    previous_expires_at TIMESTAMPTZ,
    new_expires_at      TIMESTAMPTZ NOT NULL,
    order_id            UUID REFERENCES orders(id), -- set for PURCHASE
    admin_id            UUID REFERENCES users(id),  -- set for future ADMIN grants
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_user ON entitlement_ledger(user_id);
```

> No change to `user_entitlement`. `EntitlementService.extend(...)` gains a ledger write (below).

---

## Domain & Service Layer

New package `dev.abu.screener_backend.payment` (orders + provider boundary + Multicard adapter).
Ledger lives with the entitlement domain.

### Order domain
- **`Order`** — JPA entity for `orders`; `status` is `OrderStatus` enum (STRING-mapped),
  `payment_provider` a plain string for now. `@PrePersist`/`@PreUpdate` timestamps (mirror `Plan`).
- **`OrderStatus`** — enum `{ CREATED, PENDING, PAID, EXPIRED, FAILED, CANCELED, REVERTED }`.
- **`OrderRepository`** —
  `Optional<Order> findFirstByUser_IdAndStatusIn(UUID, Collection<OrderStatus>)` (open-order lookup),
  `Optional<Order> findByProviderUuid(String)` (callback/reconciliation),
  `List<Order> findByStatus(OrderStatus)` (sweep over `PENDING`).
- **`OrderStatusHistory`** entity + **`OrderStatusHistoryRepository`**. A small
  **`OrderStateMachine`** helper centralizes legal transitions and writes a history row on every
  change (so no caller forgets the audit trail).

### Provider boundary
```java
public interface PaymentProvider {
    String id();                                       // "multicard"
    CheckoutSession createCheckout(Order order);       // create invoice → {providerUuid, checkoutUrl}
    ProviderPayment fetchPayment(String providerUuid); // durable GET — for reconciliation
}
// CheckoutSession(String providerUuid, String checkoutUrl)
// ProviderPayment(ProviderStatus status, String ps, Long amountTiyin)   // status: SUCCESS/ERROR/REVERT/PENDING/NOT_FOUND
```
The **success callback is provider-specific** (different providers post different shapes), so it is
**not** on this interface — it is handled by a dedicated `MulticardCallbackController` + service.
The interface stays minimal; a future Kaspi/crypto adapter implements the same two methods plus its
own callback controller.

- **`MulticardPaymentProvider implements PaymentProvider`** — converts `Money` → tiyin at the edge
  (`amount.movePointRight(2).longValueExact()`), builds the invoice request (adds `ofd` only when
  `ofd-enabled`), maps `PaymentStatusEnum` → `ProviderStatus`.
- **`MulticardClient`** — wraps a dedicated `WebClient` (base URL from config). Responsibilities:
  - **Token cache**: `AtomicReference<CachedToken>`; `POST /auth` lazily, reuse until near expiry; on
    `401` refetch once and retry the call.
  - `createInvoice(...)`, `getPayment(uuid)` — `.retrieve().bodyToMono(...).block()` (low-frequency,
    blocking is fine; mirrors `BinanceRestClient` style). Unwrap the `{success,data}`/`{success,error}`
    envelope; throw `ApiException`/a provider exception on `success=false`.
  - **`MulticardSignature`** util: `md5Hex(storeId + invoiceId + amount + secret)`, constant-time
    compare against `sign`. (A `sha1Hex(uuid + invoiceId + amount + secret)` variant is stubbed for
    the future full-webhook switch.)

### `OrderService` — create / reuse / pay-by-days
`@Service @Transactional`. Entry point for `POST /api/billing/orders`.
```
createOrReuse(user, planCode, amountMoneyOrNull):
    plan  = planRepository.findByCode(planCode) (active) else 400
    price = planPriceRepository ... (plan, resolvedCurrency) else 400   // currency via RegionResolver
    open  = orderRepository.findFirstByUser_IdAndStatusIn(user, {CREATED,PENDING})
    if open present:
        if open.expiresAt <= now: expire(open) (sweep also would) and fall through to create
        else if open.planId == plan.id:
            // lost-tab re-pay: optionally fetchPayment(open.providerUuid) to catch the double-pay race
            if provider says SUCCESS: markPaidAndGrant(open.providerUuid); return AlreadyPaid
            return reuse(open.checkoutUrl)
        else:  // different plan
            cancelAtProvider(open); transition(open → CANCELED, reason="superseded"); // then create new
    // compute grant
    days  = plan.type == FIXED ? plan.durationDays
                               : ceil(amountMoney / price.amount)       // pay-by-days, BigDecimal ceil
    grantAmount = plan.type == FIXED ? price.amount : amountMoney
    order = new Order(user, plan, status=CREATED, days, grantAmount, currency, expiresAt=now+ttl)
    save(order)                                                          // partial-unique index guards races
    session = provider.createCheckout(order)                            // calls Multicard
    order.providerUuid = session.providerUuid; order.checkoutUrl = session.checkoutUrl
    transition(order → PENDING, source=API)
    return { orderId, checkoutUrl, status=PENDING }
```
Notes:
- **Pay-by-days math:** `amountMoney.divide(pricePerDay, 0, RoundingMode.CEILING)` → int days. Reject
  non-positive amounts (`400`). `grantAmount` is exactly what the user pays; the order snapshots it.
- **One-open-order race:** two concurrent creates hit `uq_orders_one_open_per_user` → the loser
  catches the constraint violation and retries the reuse branch.
- **Hard rule preserved:** client sends only `planCode` (+ `amount` for pay-by-days). Never a price
  or currency — resolved server-side.

### `MulticardCallbackService` — the heart of the money flow
`@Service`. Called by the public controller. Returns a `CallbackOutcome` the controller maps to HTTP.
```
handle(payload, sourceIp):
    if sourceIp != allowedIp:                      return REJECT_BAD_SOURCE   // → HTTP 400
    if !MulticardSignature.valid(payload, secret): return REJECT_BAD_SIGN     // → HTTP 400
    order = orderRepository.findByProviderUuid(payload.uuid)
    if order == null:                              return REJECT("unknown order") // → 200 {success:false}
    if order.status == PAID:                       return OK   // idempotent replay → 200 {success:true}
    if payload.amountTiyin != toTiyin(order.amount): return REJECT("amount mismatch") // → 200 {success:false} (refund)
    try {
        markPaidAndGrant(order, payload.ps, source=CALLBACK)   // @Transactional, commits here
    } catch (transient/db error) {
        return RETRY                                // → HTTP 500 (Multicard freezes + retries)
    }
    return OK                                       // → 200 {success:true}
```
**`markPaidAndGrant(order, ps, source)`** — `@Transactional`, the single idempotent grant shared with
the sweep:
```
reload order FOR UPDATE; if status == PAID: return        // re-check under lock (idempotent)
order.ps = ps; order.paidAt = now
transition(order → PAID, source)                          // writes order_status_history
entitlementService.extend(order.userId, Duration.ofDays(order.daysGranted),
                          paid=true, source=PURCHASE, orderId=order.id, reason=null)  // writes entitlement_ledger
```

### `PaymentReconciliationService` — the safety net (`@Scheduled`)
`@Component`, `@Scheduled(fixedDelayString = "${screener.payment.reconciliation-interval}")`
(e.g. `PT1M`). `@EnableScheduling` already on the app.
```
for order in orderRepository.findByStatus(PENDING):
    if order.providerUuid == null:               // never reached provider; expire if stale
        if order.expiresAt <= now: expire(order, source=RECONCILIATION); continue
    p = provider.fetchPayment(order.providerUuid)   // durable GET /payment/{uuid}
    switch p.status:
        SUCCESS:  markPaidAndGrant(order, p.ps, RECONCILIATION)   // lost callback recovered
        ERROR:    transition(order → FAILED,  reason=p, RECONCILIATION)
        REVERT:   transition(order → REVERTED, reason="provider revert", RECONCILIATION)  // access NOT revoked
        PENDING:  if order.expiresAt <= now: transition(order → EXPIRED, RECONCILIATION)  // else leave
        NOT_FOUND:transition(order → EXPIRED, RECONCILIATION)
```
Low volume ⇒ scanning open orders each minute is cheap (no Binance-style weight pressure). Catch and
log per-order failures so one bad order never aborts the sweep.

### `EntitlementService.extend` — now writes the ledger
Signature evolves so every grant is auditable. Backward-compatible call sites updated:
```java
public void extend(UUID userId, Duration granted, boolean paid,
                   GrantSource source, UUID orderId, UUID adminId, String reason) {
    UserEntitlement e = repository.findByUserId(userId).orElseThrow(...);
    Instant now = Instant.now();
    Instant prev = e.getAccessExpiresAt();
    Instant base = (prev == null || prev.isBefore(now)) ? now : prev;
    Instant next = base.plus(granted);
    e.setAccessExpiresAt(next);
    if (paid) e.setHasPaid(true);
    repository.save(e);
    ledgerRepository.save(new EntitlementLedger(userId, source, granted.toSeconds(),
                                                prev, next, orderId, adminId, reason));
}
```
- `startTrial(user)` writes a ledger row with `source = TRIAL`.
- `GrantSource` enum `{ TRIAL, PURCHASE, ADMIN }`. The stale "There is no audit ledger" Javadoc in
  `EntitlementService` is corrected.

---

## Controllers & Endpoints

### `OrderController` (`/api/billing/orders`, Bearer JWT — catch-all `authenticated()`)
| Method | Path | Body | Returns |
|--------|------|------|---------|
| `POST` | `/api/billing/orders` | `{ "planCode": "monthly" }` or `{ "planCode": "pay_as_you_go", "amount": 790000 }` | `{ orderId, status, checkoutUrl, alreadyPaid? }` |
| `GET`  | `/api/billing/orders/current` | — | latest open/most-recent order status (UI polls this) |
| `GET`  | `/api/billing/orders/{id}` | — | that order's status (`PENDING/PAID/EXPIRED/FAILED/…`) |

DTOs: `CreateOrderRequest(String planCode, BigDecimal amount /*nullable*/)`,
`CreateOrderResponse(UUID orderId, OrderStatus status, String checkoutUrl, boolean alreadyPaid)`,
`OrderStatusResponse(UUID orderId, OrderStatus status, String reason, Instant expiresAt, Instant paidAt)`.

### `MulticardCallbackController` (`/api/payment/multicard/callback`, **public**)
- `POST` consumes the callback JSON. Reads source IP (mind `X-Forwarded-For` behind a proxy),
  delegates to `MulticardCallbackService.handle`, maps `CallbackOutcome` → HTTP:
  - `OK` → `200 {"success": true}`
  - `REJECT(message)` → `200 {"success": false, "message": message}` (Multicard refunds)
  - `REJECT_BAD_SIGN` / `REJECT_BAD_SOURCE` → `400` (unprocessed; likely forged)
  - `RETRY` → `500` (Multicard freezes + retries)
- No CORS concerns (server-to-server). Never reads/writes session.

---

## Configuration

New `screener.payment.*` (record `PaymentProperties` + nested `MulticardProperties`), registered in
`WebClientConfig`'s `@EnableConfigurationProperties` set. Secrets via env vars (mirroring `jwt`/`admin`):
```yaml
screener:
  payment:
    reconciliation-interval: PT1M
    multicard:
      base-url: https://dev-mesh.multicard.uz        # prod: https://mesh.multicard.uz
      application-id: ${MULTICARD_APP_ID:}
      secret:         ${MULTICARD_SECRET:}
      store-id:       ${MULTICARD_STORE_ID:}
      callback-url:   ${MULTICARD_CALLBACK_URL:}      # public URL of our callback endpoint
      return-url:     ${MULTICARD_RETURN_URL:}        # SPA post-payment landing page
      invoice-ttl:    PT30M
      allowed-ip:     195.158.26.90
      lang:           ru
      ofd-enabled:    false                           # true in prod once tax data exists
```
A dedicated **`multicardWebClient`** bean (base URL + JSON codec) added to `WebClientConfig`,
alongside the spot/futures clients. `application-local.yml` carries the sandbox test creds from the
docs README for local runs.

---

## SecurityConfig change
Add **before** the catch-all, after the existing `permitAll` matchers:
```java
.requestMatchers(HttpMethod.POST, "/api/payment/multicard/callback").permitAll()
```
`/api/billing/orders/**` needs no new rule — it falls under `anyRequest().authenticated()`. The
callback is the only new public endpoint; it is protected by signature + IP, not JWT.

---

## Edge Cases & Real-World Scenarios (consolidated)

| Scenario | Handling |
|----------|----------|
| **Abandoned checkout** (user never pays) | No callback ever fires. Sweep marks `EXPIRED` once `expires_at` passes. |
| **Lost / failed success callback** | Sweep's durable `GET /payment/{uuid}` sees `success` and runs the same `markPaidAndGrant`. |
| **Duplicate / retried callback** | `provider_uuid` unique + `PAID`-state guard ⇒ second call is a no-op returning `200 {success:true}`. |
| **Lost-tab re-pay, same plan** | One-open-order lookup returns the existing `PENDING`; reuse its `checkoutUrl`. |
| **Re-pay with a different plan** | Cancel old invoice (`DELETE /payment/invoice/{uuid}`), mark old `CANCELED`, create fresh. |
| **Double-pay race** (paid on lost tab, returns before callback) | On reuse, `fetchPayment` shows `success` → grant immediately, respond `alreadyPaid`. Grant is idempotent regardless. |
| **Refund / `revert`** | Detected only via sweep (success-only webhooks don't push it). Recorded `REVERTED`; access **not** revoked (Decision: ignore-but-record). |
| **Amount mismatch** | Callback rejected with `{success:false}` ⇒ Multicard refunds; order left `PENDING` for the sweep. |
| **Transient DB failure mid-grant** | Callback returns `500` ⇒ Multicard freezes funds + retries; sweep is the backstop. |
| **Multicard token expired** | `MulticardClient` catches `401`, refetches token, retries once. |
| **Pay-by-days uneven division** | `ceil` — $790 at $1/day ⇒ 790 days. Non-positive amount ⇒ `400`. |
| **Browser redirect arrives before grant** | `return_url` grants nothing; SPA polls `/orders/current` until `PAID`. |
| **Production fiscalization** | `ofd-enabled=true` makes the adapter attach the `ofd` block; off in sandbox/local. |

---

## Build Order

1. **Migrations** `V8` (orders), `V9` (order_status_history), `V10` (entitlement_ledger).
2. **Order domain** — `Order`, `OrderStatus`, repositories, `OrderStatusHistory` + repo,
   `OrderStateMachine`.
3. **Ledger** — `EntitlementLedger` entity + repo; extend `EntitlementService.extend(...)` +
   `startTrial`; `GrantSource` enum. Update existing call site (registration) + the stale Javadoc.
4. **`PaymentProperties`/`MulticardProperties`** + `multicardWebClient` bean + local sandbox creds.
5. **Multicard adapter** — `MulticardClient` (auth cache, invoice, getPayment), `MulticardSignature`,
   `MulticardPaymentProvider` (+ `PaymentProvider` interface, DTOs).
6. **`OrderService`** — create/reuse + pay-by-days; **`OrderController`**.
7. **`MulticardCallbackService`** + **`MulticardCallbackController`** (outcome→HTTP mapping).
8. **`PaymentReconciliationService`** (`@Scheduled`).
9. **`SecurityConfig`** — public callback matcher.
10. **Tests** (below).

---

## Tests (plain JUnit, matching existing style)

- **`OrderService`** — fixed-plan create; pay-by-days `ceil` (exact, +1 partial, reject non-positive);
  reuse same plan; supersede different plan; expired-open recreate; currency resolved server-side.
- **`MulticardSignature`** — known-vector MD5 from the doc example; tamper → invalid.
- **`MulticardCallbackService`** — happy grant; idempotent replay (already `PAID`); unknown order;
  amount mismatch → reject; bad IP / bad sign → 400 outcome; transient failure → RETRY.
- **`MulticardPaymentProvider`** — `Money`→tiyin (`movePointRight(2).longValueExact()`); `ofd`
  attached only when enabled; status mapping (`success/error/revert/…` → `ProviderStatus`).
- **`PaymentReconciliationService`** — `PENDING`+success → grants; `error` → FAILED; `revert` →
  REVERTED (no entitlement change); stale `PENDING` → EXPIRED; one bad order doesn't abort the sweep.
- **`EntitlementService`** — `extend` writes a ledger row with correct prev/new expiry; `startTrial`
  ledger row; stacking from past vs future expiry unchanged.
- **Idempotency** — two concurrent `markPaidAndGrant` for one `uuid` extend entitlement exactly once.

---

## CURRENT_STATE.md updates (after implementation)

Add a `payment/` package section; the `V8`/`V9`/`V10` migrations; `orders` /
`order_status_history` / `entitlement_ledger` tables; the new `/api/billing/orders/**` and public
`/api/payment/multicard/callback` endpoints; the `screener.payment.*` config + `multicardWebClient`
bean; the reconciliation scheduler; the extended `EntitlementService.extend` (ledger). Move the
"payment gateway" rows out of the "What Is Not Yet Implemented" table; note refunds/full-webhooks/
production-`ofd` as still deferred.
```
