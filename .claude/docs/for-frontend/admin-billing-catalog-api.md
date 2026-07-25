# API Documentation: Admin Billing Catalog Management

**ADMIN-only** CRUD over the plan catalog (`plans` + `plan_prices` tables) — the endpoints behind
an admin screen for creating/editing subscription plans and their per-currency prices. Distinct
from the public, read-only `GET /api/billing-catalog` (covered in `monetization-api.md`), which
returns only **active** plans priced in the caller's resolved currency; these admin endpoints
return the **full** catalog (inactive plans, every currency, ids) so an admin can manage it.

This doc covers the four endpoints requested for the plan management screen — `GET
/api/admin/billing/plans`, `POST /api/admin/billing/plans`, `PUT
/api/admin/billing/plans/{id}`, and `PUT /api/admin/billing/plans/{id}/prices` — plus the two
related delete endpoints on the same controller, mentioned for completeness.

---

## Authentication

Every endpoint requires a valid **Bearer JWT** for a user whose role is `ADMIN` — same gate as
`/api/monitoring/**` and `/api/admin/users`:

```
Authorization: Bearer <access_token>
```

Missing/invalid token, or a valid token for a non-admin user, both return an **empty-body `403
Forbidden`** — there's no way to tell the two cases apart from the response.

---

## Background: Plan vs. Price

- **A plan** (`plans` table) is the catalog entry itself: a `code` (immutable, stable identifier
  the frontend keys off — e.g. `"PRO_MONTHLY"`), a `displayName`, a `type`, and (for `FIXED` plans
  only) `durationDays`.
- **A price** (`plan_prices` table) is one `(plan, currency)` row: how much that plan costs in one
  currency. A plan can have zero, one, or several price rows — one per supported currency. A plan
  with no price row in the caller's currency simply won't be purchasable in that currency.

**Plan types** (`PlanType`):

| Type | Meaning |
|------|---------|
| `FIXED` | A pre-priced bundle of a fixed number of days (weekly/monthly/yearly). `durationDays` is **required** and `> 0`. The price row is the price of the whole bundle. |
| `PER_DAY` | Pay-as-you-go. `durationDays` **must be `null`**. The price row is the price of **one day**; the number of days purchased is chosen by the user at checkout, not stored on the plan. |

**Supported currencies** (`Currency` enum) — an admin-supplied currency is validated against this
fixed list, case-insensitively:

| Code | Decimal places |
|------|-----------------|
| `UZS` | 2 |
| `USD` | 2 |
| `BTC` | 8 |
| `ETH` | 18 |

**Soft delete only**: both `DELETE /api/admin/billing/plans/{id}` and `DELETE
/api/admin/billing/prices/{id}` set `active = false` rather than hard-deleting — plans and prices
may be referenced by historical orders, so rows are never removed. Both deletes are idempotent
(deleting an already-inactive row is a no-op, still `204`).

---

## Endpoints

---

### `GET /api/admin/billing/plans`

Returns the **entire** catalog — every plan (active and inactive), each with **all** of its price
rows (every currency, active and inactive). Not paginated; the catalog is expected to stay small
(a handful of plans).

**Request**: no body, no query parameters.

**Response `200 OK`**:

```json
[
  {
    "id": "b3f1c2a4-1234-4a2b-9c3d-abcdef123456",
    "code": "PRO_MONTHLY",
    "displayName": "Pro Monthly",
    "type": "FIXED",
    "durationDays": 30,
    "active": true,
    "prices": [
      { "id": "c4d5e6f7-...", "currency": "UZS", "amount": 149000.00, "active": true },
      { "id": "d5e6f7a8-...", "currency": "USD", "amount": 12.99, "active": true }
    ]
  },
  {
    "id": "a1b2c3d4-...",
    "code": "PAY_AS_YOU_GO",
    "displayName": "Pay As You Go",
    "type": "PER_DAY",
    "durationDays": null,
    "active": true,
    "prices": [
      { "id": "e6f7a8b9-...", "currency": "UZS", "amount": 6000.00, "active": true }
    ]
  }
]
```

Sorted by `code` ascending. Returns `[]` if no plans exist yet.

**Field meanings**:

| Field | Type | Meaning |
|-------|------|---------|
| `id` | UUID string | Plan id — use in `PUT /api/admin/billing/plans/{id}` and `PUT /api/admin/billing/plans/{id}/prices` |
| `code` | string | Immutable stable identifier |
| `displayName` | string | Human-readable name shown to users |
| `type` | string | `"FIXED"` or `"PER_DAY"` |
| `durationDays` | number \| null | Bundle length in days for `FIXED`; always `null` for `PER_DAY` |
| `active` | boolean | Whether the plan is currently purchasable/visible in the public catalog |
| `prices` | array | **All** price rows for this plan, every currency, active and inactive |
| `prices[].id` | UUID string | Price row id — use in `DELETE /api/admin/billing/prices/{id}` |
| `prices[].currency` | string | ISO 4217-style code (`"UZS"`, `"USD"`, `"BTC"`, `"ETH"`) |
| `prices[].amount` | number | Price in **major units**, already rescaled to the currency's canonical decimal places (e.g. `149000.00` for UZS, not a raw high-precision DB value) |
| `prices[].active` | boolean | Whether this specific price row is currently usable |

---

### `POST /api/admin/billing/plans`

Creates a new plan. No price rows are created here — add them afterward via `PUT
/api/admin/billing/plans/{id}/prices`.

**Request body**:

```json
{
  "code": "PRO_YEARLY",
  "displayName": "Pro Yearly",
  "type": "FIXED",
  "durationDays": 365,
  "active": true
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `code` | string | yes | Non-blank; must be **globally unique** — `409` if it already exists. Immutable after creation. |
| `displayName` | string | yes | Non-blank. |
| `type` | string | yes | `"FIXED"` or `"PER_DAY"`. |
| `durationDays` | integer | conditional | **Required and `> 0`** when `type` is `"FIXED"`; **must be omitted/`null`** when `type` is `"PER_DAY"` — sending it for a `PER_DAY` plan is a `400`. |
| `active` | boolean | no | Defaults to `true` when omitted/`null`. |

**Response `201 Created`**: the created plan, in the same shape as one entry in the `GET` list
above, with `prices: []` (none exist yet).

**Error cases**:

| Status | When |
|--------|------|
| `400 Bad Request` | Missing body; blank `code`/`displayName`; missing/invalid `type`; `durationDays` violates the `FIXED`/`PER_DAY` invariant above; `durationDays <= 0` |
| `409 Conflict` | `code` already exists |

---

### `PUT /api/admin/billing/plans/{id}`

Updates an existing plan's mutable fields. **`code` and `type` are immutable** — `code` in the
request body is silently ignored (the path `id` is the only identifier used), and `type` cannot
be changed at all (there's no field for it in the response to "change"; the duration invariant is
validated against the plan's **existing, stored** type).

**Request body**:

```json
{
  "displayName": "Pro Yearly (Best Value)",
  "durationDays": 365,
  "active": true
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `code` | string | — | Accepted in the DTO but **ignored** — do not rely on this to rename a plan's code. |
| `displayName` | string | yes | Non-blank; replaces the current value. |
| `type` | string | — | Accepted in the DTO but **ignored** — the plan keeps its original type. |
| `durationDays` | integer | conditional | Validated against the plan's **existing** type: required/`>0` if the plan is `FIXED`, must be `null` if `PER_DAY`. Always sent — there's no partial-update semantics for this field, so omitting it clears `durationDays` to `null` (would fail validation on a `FIXED` plan). |
| `active` | boolean | no | Only applied if non-null — omit/`null` to leave the current `active` flag unchanged (this field, unlike the others, **is** a true partial update). |

**Response `200 OK`**: the updated plan, in the same shape as `GET`, including its current
`prices` array (unlike `POST`, which always returns `[]`).

**Error cases**:

| Status | When |
|--------|------|
| `400 Bad Request` | Missing body; blank `displayName`; `durationDays` violates the invariant for this plan's stored type |
| `404 Not Found` | No plan with that `id` |

**Gotcha for the frontend**: because `displayName` and `durationDays` are always applied (not
partial-update), an edit form must submit the plan's **current** `displayName`/`durationDays`
alongside whatever field the admin actually changed — sending only the changed field will null
out or reject the others.

---

### `PUT /api/admin/billing/plans/{id}/prices`

Upserts one `(plan, currency)` price row — creates it if that plan has no price yet in the given
currency, or overwrites the existing row if it does. There's no separate "create price" vs.
"update price" endpoint; this one call handles both, keyed by `(planId, currency)`.

**Request body**:

```json
{
  "currency": "USD",
  "amount": "12.99",
  "active": true
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `currency` | string | yes | 3-letter code, case-insensitive, must be one of the [supported currencies](#background-plan-vs-price) — unsupported/malformed codes are `400`. |
| `amount` | **string** | yes | Price in **major units**. Sent as a string (not a JSON number) so precision isn't lost in transit — parsed server-side to `BigDecimal`. Must be `>= 0`, and must not carry more decimal places than the currency allows (e.g. `"12.999"` for USD, a 2-dp currency, is a `400`; trailing zeros like `"12.900"` are fine). |
| `active` | boolean | no | Defaults to `true` when omitted/`null`. |

**Response `200 OK`**: the upserted price row alone (not the whole plan):

```json
{
  "id": "d5e6f7a8-1234-4a2b-9c3d-abcdef123456",
  "currency": "USD",
  "amount": 12.99,
  "active": true
}
```

**Error cases**:

| Status | When |
|--------|------|
| `400 Bad Request` | Missing body; invalid/unsupported `currency`; missing/malformed `amount`; `amount < 0`; `amount` has too many decimal places for the currency |
| `404 Not Found` | No plan with the given `id` |

---

## Related Endpoints (Same Controller, Context Only)

Not part of this doc's requested scope, but on `PlanAdminController` and worth knowing about:

| Endpoint | Purpose |
|----------|---------|
| `DELETE /api/admin/billing/plans/{id}` | Soft-disables a plan (`active = false`). `204 No Content`. Idempotent. `404` if the id doesn't exist. |
| `DELETE /api/admin/billing/prices/{id}` | Soft-disables one price row. `204 No Content`. Idempotent. `404` if the id doesn't exist. Note: this is keyed by the **price row's own `id`**, not `(planId, currency)`. |

---

## HTTP Error Shapes

All `4xx` errors (other than the empty-body `403`) return the standard JSON error body:

```json
{
  "message": "plan code already exists: PRO_MONTHLY",
  "status": 409,
  "path": "/api/admin/billing/plans"
}
```

| Status | Meaning |
|--------|---------|
| `403 Forbidden` (empty body) | Missing/invalid JWT, or a valid JWT for a non-admin user |
| `400 Bad Request` | Malformed request — see per-endpoint tables above |
| `404 Not Found` | Plan or price id doesn't exist |
| `409 Conflict` | `POST /api/admin/billing/plans` only — `code` already in use |

All validation runs **before** any DB write, so a rejected request never partially applies.

---

## Practical Notes for the Frontend

- **`code` is permanent**: don't build a "rename code" affordance — there isn't one. If a plan's
  code is truly wrong, the workaround is soft-deleting it and creating a new one (which loses the
  purchase-history linkage's readability, so treat this as a rare admin operation, not a UI flow).
- **`type` is permanent too**: a plan can't flip between `FIXED` and `PER_DAY` after creation —
  the create form is the only place `type` is chosen.
- **`amount` in requests is a string, in responses a number**: send `"12.99"` (quoted) when
  upserting a price; expect `12.99` (unquoted) back from both `GET` and the upsert response.
- **The update-plan endpoint isn't a true PATCH** for `displayName`/`durationDays` — always
  round-trip the plan's current values for fields the admin isn't intentionally changing (see the
  gotcha under `PUT /api/admin/billing/plans/{id}` above). `active` is the one field that's safe
  to omit.
- **This is the full admin catalog, not what end users see**: cross-check against
  `GET /api/billing-catalog` (public) if you need to verify what's actually purchasable —
  `active: false` plans/prices here still show up in this admin listing but are filtered out of
  the public one.
