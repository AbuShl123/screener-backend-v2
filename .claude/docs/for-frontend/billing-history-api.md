# API Documentation: Order History & Entitlement History

Three read endpoints under the `/api/billing` umbrella that let the frontend show a user their
purchase history, one order's own status-transition audit trail, and their access-granting history.
All three are pure reads — no writes happen here. For creating/canceling orders see
`POST /api/billing/orders` and `POST /api/billing/orders/current/cancel` (covered briefly below
since `GET /api/billing/orders` returns the same shape); for the live access-state poll see
`GET /api/billing/entitlement`.

---

## Background: Orders vs. Entitlement Ledger

These are two related but distinct records of a user's billing activity:

- **An order** (`orders` table) is one purchase attempt — a plan, a price, a payment-provider
  invoice, and a status that moves through a lifecycle (`CREATED → PENDING → PAID`, or off to
  `EXPIRED`/`FAILED`/`CANCELED`/`REVERTED`). Most orders never get paid (abandoned checkouts,
  expired invoices) — the order list is a full audit trail, not just successful purchases.
- **An entitlement ledger row** (`entitlement_ledger` table) is one event that pushed the user's
  `accessExpiresAt` forward — i.e. one *grant*. A grant happens for the free trial seed, for a
  successfully **paid** order, or (future) an admin gift. Failed/expired/canceled orders never
  produce a ledger row.

So: every ledger row of source `PURCHASE` corresponds to exactly one `PAID` order (embedded in
full), but not every order produces a ledger row, and the trial/admin rows have no order at all.

---

## Authentication

Both endpoints require a valid **Bearer JWT** in the `Authorization` header — covered by the
`.anyRequest().authenticated()` catch-all in `SecurityConfig`:

```
Authorization: Bearer <access_token>
```

The user's identity is always derived from the JWT — neither endpoint takes a user id parameter,
and there is no way to read another user's history.

**Missing or invalid token**: Spring Security rejects the request before it reaches the
controller, returning an **empty-body `403 Forbidden`** — not `401`, and not the JSON error shape
described below.

**No subscription gate**: unlike the classification-rules endpoints, `GET /api/billing/orders`
and `GET /api/billing/entitlement/history` are **not** gated by an active-subscription check — a
lapsed or trial user can always see their own billing history (that's how they'd find their way
back to renewing).

---

## Endpoints

---

### `GET /api/billing/orders`

Returns the caller's own orders, **newest first**, capped at the **100 most recent**. This is a
full audit trail — includes abandoned, expired, and failed orders, not just paid ones.

**Request**: No body, no query parameters, no pagination.

**Response `200 OK`**:

```json
[
  {
    "orderId": "b3f1c2a4-1234-4a2b-9c3d-abcdef123456",
    "status": "PAID",
    "planCode": "PRO_MONTHLY",
    "amount": 149000,
    "accessDurationSeconds": 2592000,
    "currency": "UZS",
    "provider": "multicard",
    "reason": "CALLBACK_GRANT",
    "reasonDetail": null,
    "checkoutUrl": "https://checkout.multicard.uz/pay/abc123",
    "providerUuid": "9f8e7d6c-...",
    "receiptUrl": "https://mesh.multicard.uz/receipt/9f8e7d6c",
    "expiresAt": "2026-07-20T10:00:00Z",
    "paidAt": "2026-06-11T09:15:32Z",
    "createdAt": "2026-06-11T09:10:00Z"
  },
  {
    "orderId": "a1b2c3d4-...",
    "status": "EXPIRED",
    "planCode": "PRO_MONTHLY",
    "amount": 149000,
    "accessDurationSeconds": 2592000,
    "currency": "UZS",
    "provider": "multicard",
    "reason": "INVOICE_EXPIRED",
    "reasonDetail": null,
    "checkoutUrl": "https://checkout.multicard.uz/pay/xyz789",
    "providerUuid": "1a2b3c4d-...",
    "receiptUrl": null,
    "expiresAt": null,
    "paidAt": null,
    "createdAt": "2026-06-05T14:22:10Z"
  }
]
```

Returns an **empty array** `[]` if the user has never created an order.

**Field meanings** (same `OrderDetailsEntry` shape used across all order-returning endpoints —
create, `/current`, `/{id}`, this list, and embedded in entitlement ledger rows):

| Field | Type | Meaning |
|-------|------|---------|
| `orderId` | UUID string | Order identifier |
| `status` | string | One of `CREATED`, `PENDING`, `PAID`, `EXPIRED`, `FAILED`, `CANCELED`, `REVERTED` — see [Order Status Lifecycle](#order-status-lifecycle) |
| `planCode` | string | The plan purchased (e.g. `"PRO_MONTHLY"`) |
| `amount` | number | Price in **major units** (e.g. `149000` UZS, not minor units) — for a `FIXED` plan this is the plan price; for pay-by-days it's the amount the user requested |
| `accessDurationSeconds` | number | Access duration this order buys/bought, in seconds |
| `currency` | string | ISO-ish currency code, server-resolved from the request's region (today always `"UZS"`) |
| `provider` | string | Payment provider (today always `"multicard"`) |
| `reason` | string \| null | Canonical reason code from the **latest** status transition (see [Order Reason Codes](#order-reason-codes)) — `null` if the order has no history row yet (shouldn't normally happen) |
| `reasonDetail` | string \| null | Free-form text accompanying `reason` (e.g. a raw provider error message); usually `null` |
| `checkoutUrl` | string \| null | Hosted payment page link. Non-null while the order is `PENDING`/re-payable; a terminal order may still carry the last-known URL (now stale/unusable) |
| `providerUuid` | string \| null | Multicard's transaction uuid — useful for support/debugging, not needed for normal UI flows |
| `receiptUrl` | string \| null | Provider's bank-receipt page link. Present only on a `PAID` order — and even then may be `null` (Multicard marks it optional). Render as a "View receipt" link when non-null; it opens Multicard's hosted receipt page (not a file download — the user can print/save from there) |
| `expiresAt` | ISO-8601 string \| null | When the invoice/checkout expires. `null` once the order leaves `PENDING` in most terminal states |
| `paidAt` | ISO-8601 string \| null | When the order was marked `PAID`; `null` if never paid |
| `createdAt` | ISO-8601 string | When the order was created |

#### Order Status Lifecycle

```
CREATED → PENDING → PAID
              ├→ EXPIRED   (invoice TTL elapsed, no payment)
              ├→ FAILED    (provider reported an error)
              └→ CANCELED  (superseded by a new order, or user-canceled)
PENDING/PAID → REVERTED    (refund detected — recorded only; access is NOT revoked)
```

`CREATED` and `PENDING` are the two "open" states — a user has at most **one** open order at a
time; starting a new order for a different plan supersedes (cancels) the old one.

#### Order Reason Codes

The `reason` field on the latest transition, when present:

| Code | Meaning |
|------|---------|
| `SUPERSEDED` | Replaced by a new order for a different plan |
| `USER_CANCELED` | Canceled by the user before payment (via `/current/cancel`) |
| `INVOICE_EXPIRED` | Invoice TTL elapsed; no payment received |
| `AMOUNT_MISMATCH` | Payment amount did not match the order amount |
| `UNKNOWN_ORDER` | Provider callback referenced an unknown order (rare/internal) |
| `PROVIDER_ERROR` | Provider reported the payment failed |
| `PROVIDER_REVERT` | Provider reversed/refunded the payment; access not revoked |
| `CALLBACK_GRANT` | Paid and granted via the provider's success callback (the normal happy path) |
| `RECONCILED_GRANT` | Paid and granted via the reconciliation sweep (a lost callback recovered) |

---

### `GET /api/billing/orders/{id}/history`

Full status-transition audit trail for **one order** — every row of `order_status_history`,
**newest first** by `seq`. This is the low-level "what exactly happened to this order and when"
view; most UIs won't need it (the `status`/`reason` on the order itself already summarizes the
latest transition), but it's useful for a support/debug panel or an expandable "details" view on
an order row.

**Request**: `id` is the order UUID, as a path parameter. No body, no query parameters.

**Ownership**: owner-only. Requesting another user's order id, or an id that doesn't exist, returns
**`404 Not Found`** — same non-leaking behavior as `GET /api/billing/orders/{id}`.

**Response `200 OK`**:

```json
[
  {
    "fromStatus": "PENDING",
    "toStatus": "PAID",
    "reason": "CALLBACK_GRANT",
    "reasonDetail": null,
    "source": "CALLBACK",
    "createdAt": "2026-06-11T09:15:32Z",
    "seq": 42
  },
  {
    "fromStatus": "CREATED",
    "toStatus": "PENDING",
    "reason": null,
    "reasonDetail": null,
    "source": "API",
    "createdAt": "2026-06-11T09:10:00Z",
    "seq": 41
  }
]
```

**Field meanings**:

| Field | Type | Meaning |
|-------|------|---------|
| `fromStatus` | string | Order status before this transition (one of the [Order Status Lifecycle](#order-status-lifecycle) values) |
| `toStatus` | string | Order status after this transition |
| `reason` | string \| null | Canonical reason code for *this specific* transition — same vocabulary as [Order Reason Codes](#order-reason-codes); `null` when the transition is self-explanatory (e.g. the initial `CREATED → PENDING` move on order creation carries no reason) |
| `reasonDetail` | string \| null | Free-form text accompanying `reason` (e.g. a raw provider error message); usually `null` |
| `source` | string | What drove the transition — `API` (a user-facing order endpoint: create/reuse/supersede/cancel), `CALLBACK` (the payment provider's success callback), `RECONCILIATION` (the scheduled stale-order sweep that recovers lost callbacks), or `SYSTEM` (any other internal transition) |
| `createdAt` | ISO-8601 string | When this transition happened |
| `seq` | number | DB-generated monotonic ordering key; the array is sorted by this, descending. Only meaningful as a sort/tiebreak key, not a count or id |

**Notes**:

- **Every order has at least one row**, and the first (oldest) row is always `CREATED → PENDING`
  with `reason: null` and `source: "API"` — order creation itself just sets the initial `CREATED`
  status without going through the state machine, so there's no separate "order created" row and
  `fromStatus` is never `null` in practice.
- This endpoint returns the **same transitions** that produce the order's own `status`/`reason`
  fields in [`OrderDetailsEntry`](#get-apibillingorders) — the latest row here is always consistent
  with what `GET /api/billing/orders/{id}` reports for that order at the same point in time.
- No pagination or cap: an order's history is at most a handful of rows (one per lifecycle hop), so
  the full list is always returned.

---

### `GET /api/billing/entitlement/history`

Returns the caller's **entitlement ledger** — every event that pushed their `accessExpiresAt`
forward — **newest first**. No pagination or limit (ledger rows are far rarer than orders: one
per trial seed, paid purchase, or admin grant).

**Request**: No body, no query parameters.

**Response `200 OK`**:

```json
[
  {
    "source": "PURCHASE",
    "grantedDurationSeconds": 2592000,
    "previousExpiresAt": "2026-06-11T00:00:00Z",
    "newExpiresAt": "2026-07-11T00:00:00Z",
    "order": {
      "orderId": "b3f1c2a4-1234-4a2b-9c3d-abcdef123456",
      "status": "PAID",
      "planCode": "PRO_MONTHLY",
      "amount": 149000,
      "accessDurationSeconds": 2592000,
      "currency": "UZS",
      "provider": "multicard",
      "reason": "CALLBACK_GRANT",
      "reasonDetail": null,
      "checkoutUrl": "https://checkout.multicard.uz/pay/abc123",
      "providerUuid": "9f8e7d6c-...",
      "receiptUrl": "https://mesh.multicard.uz/receipt/9f8e7d6c",
      "expiresAt": "2026-07-20T10:00:00Z",
      "paidAt": "2026-06-11T09:15:32Z",
      "createdAt": "2026-06-11T09:10:00Z"
    },
    "reason": null,
    "createdAt": "2026-06-11T09:15:32Z"
  },
  {
    "source": "TRIAL",
    "grantedDurationSeconds": 604800,
    "previousExpiresAt": null,
    "newExpiresAt": "2026-06-11T00:00:00Z",
    "order": null,
    "reason": null,
    "createdAt": "2026-06-04T00:00:00Z"
  }
]
```

Returns an **empty array** `[]` if the user has no ledger rows yet (shouldn't normally happen —
every user gets a `TRIAL` row on registration).

**Field meanings**:

| Field | Type | Meaning |
|-------|------|---------|
| `source` | string | What kind of grant: `TRIAL` (free week seeded on registration), `PURCHASE` (a paid order), `ADMIN` (future admin grant/gift — no call site exists yet) |
| `grantedDurationSeconds` | number | How much access this event added, in seconds |
| `previousExpiresAt` | ISO-8601 string \| null | The user's `accessExpiresAt` before this grant — `null` for the very first grant (e.g. the initial trial) |
| `newExpiresAt` | ISO-8601 string | The user's `accessExpiresAt` after this grant |
| `order` | object \| null | The full `OrderDetailsEntry` (same shape as [`GET /api/billing/orders`](#get-apibillingorders)) for a `PURCHASE` grant; `null` for `TRIAL`/`ADMIN` grants (no order backs them) |
| `reason` | string \| null | Free-form note on the ledger row itself (distinct from the order's own `reason`); typically `null` today |
| `createdAt` | ISO-8601 string | When this grant was recorded |

**Using this to build a billing history UI**: `PURCHASE` rows carry the full order detail inline —
no need for a second call to `/api/billing/orders/{id}` to show what was bought, when it was paid,
and what it cost. `TRIAL`/`ADMIN` rows have `order: null`; render those as plain grant events
("7-day free trial started").

---

## Related Endpoints (Context, Not Documented Here)

These live in the same controllers but aren't history endpoints — mentioned for completeness
since they share the `OrderDetailsEntry` shape documented above:

| Endpoint | Purpose |
|----------|---------|
| `POST /api/billing/orders` | Create or reuse an order for a plan (`{ "planCode": "...", "amount": "..." }`, `amount` only for pay-by-days) |
| `GET /api/billing/orders/current` | The user's current order — the open one, or else the most recent |
| `POST /api/billing/orders/current/cancel` | Cancel the current order's unpaid invoice (`409` if it's not `PENDING`) |
| `GET /api/billing/orders/{id}` | One order by id (owner-only, `404` otherwise) |
| `GET /api/billing/entitlement` | Cheap current-state poll — `{ "state": "TRIAL"\|"ACTIVE"\|"EXPIRED"\|"ADMIN", "accessExpiresAt": "..." }` |

`GET /api/billing/orders/{id}` and [`GET /api/billing/orders/{id}/history`](#get-apibillingordersidhistory)
are **owner-only**: requesting another user's order id returns `404 Not Found` (not `403`) — the
backend does not reveal whether the order exists.

---

## HTTP Error Shapes

All `4xx` errors (other than the empty-body auth failure) return a JSON body:

```json
{
  "message": "No orders for user",
  "status": 404,
  "path": "/api/billing/orders/current"
}
```

| Status | Meaning | Where it applies |
|--------|---------|-------------------|
| `403 Forbidden` (empty body) | Missing or invalid JWT — rejected by Spring Security before reaching the controller | All three endpoints |
| `404 Not Found` | No matching order (nonexistent id, or owned by another user) | `GET /api/billing/orders/{id}/history` only (and the related single-order endpoints `/current`, `/{id}`) |

`GET /api/billing/orders` and `GET /api/billing/entitlement/history` return `200` with an **empty
array** rather than `404` when the user has no history — there is no "not found" case for those two
list-all endpoints. `GET /api/billing/orders/{id}/history` is scoped to one order id, so it follows
the single-order `404` behavior instead: an empty array is never a valid response for it (every
existing, owned order has at least one history row).

---

## Practical Notes for the Frontend

- **No pagination**: `GET /api/billing/orders` is capped server-side at 100 rows; the entitlement
  ledger has no cap (grants are rare). Neither endpoint takes `page`/`limit` query parameters
  today.
- **`amount` is already in major units**: no need to divide by 100 or similar — display directly
  with the `currency` field.
- **Dates are ISO-8601 UTC strings**: parse with the standard `Date`/`Temporal` APIs; format in
  the user's locale client-side.
- **`checkoutUrl` on a non-`PENDING` order may be stale**: only treat it as clickable/usable when
  `status === "PENDING"`. For a `PAID` order it's historical trivia, not an action.
- **`receiptUrl` is the "View receipt" action on a `PAID` order**: show the link only when
  `status === "PAID" && receiptUrl != null`. It opens Multicard's hosted receipt page in a new tab —
  it is *not* a direct file download, so label it "View receipt" (the user prints/saves from that
  page). Older orders paid before this field existed, and paid orders where Multicard omitted the
  URL, will have `receiptUrl: null` — just hide the link in that case.
- **Correlating order history with entitlement history**: match `order.orderId` on a `PURCHASE`
  ledger row against the `orderId` in the orders list if you need to cross-reference — but usually
  the embedded `order` object is all you need, so a second fetch isn't necessary.
- **Empty states are normal, not errors**: a brand-new user's `/orders` list is `[]` until their
  first purchase attempt; their `/entitlement/history` should already have one `TRIAL` row from
  registration.
