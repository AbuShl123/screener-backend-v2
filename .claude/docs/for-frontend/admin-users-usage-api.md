# API Documentation: Admin User Directory, Entitlement Gifting & Usage Analytics

Four **ADMIN-only** endpoints that back an admin dashboard: browse registered users, gift free
access to one or more of them, check who is online right now, and pull historical
connection-activity analytics. All four are gated by `hasRole("ADMIN")` in `SecurityConfig` — a
non-admin (or unauthenticated) caller never reaches the controller.

---

## Authentication

Every endpoint here requires a valid **Bearer JWT** for a user whose role is `ADMIN`:

```
Authorization: Bearer <access_token>
```

- **Missing/invalid token**: empty-body `403 Forbidden` (Spring Security rejects before the
  controller runs), same as every other protected endpoint in this API.
- **Valid token, non-admin user**: also an empty-body `403 Forbidden` — `hasRole("ADMIN")` fails
  the same way as no token at all. There is no way to distinguish "not logged in" from "logged in
  but not an admin" from the response alone.

---

## Endpoints

---

### `GET /api/admin/users`

Paginated listing of **all** registered users, newest first, each row carrying its derived access
state — the screen an admin uses to find the `id`(s) to hand to
[`POST /api/admin/entitlement/gift`](#post-apiadminentitlementgift) below.

**Query parameters** (both optional):

| Param | Type | Default | Notes |
|-------|------|---------|-------|
| `page` | int | `0` | Zero-based. Negative values are clamped to `0`, not rejected. |
| `size` | int | `20` | Clamped server-side to `[1, 100]` — `size=0` or negative falls back to the default `20`; anything above `100` is capped at `100`. There is no error for an out-of-range value, it's silently clamped. |

**Request**: no body.

**Response `200 OK`**:

```json
{
  "users": [
    {
      "id": "b3f1c2a4-1234-4a2b-9c3d-abcdef123456",
      "firstName": "Jane",
      "lastName": "Doe",
      "email": "jane@example.com",
      "role": "USER",
      "emailVerified": true,
      "enabled": true,
      "createdAt": "2026-06-01T08:00:00Z",
      "accessState": "TRIAL",
      "accessExpiresAt": "2026-07-29T00:00:00Z",
      "hasPaid": false,
      "lastSeenAt": "2026-07-24T19:42:11Z"
    },
    {
      "id": "a1b2c3d4-...",
      "firstName": "Admin",
      "lastName": "User",
      "email": "admin@example.com",
      "role": "ADMIN",
      "emailVerified": true,
      "enabled": true,
      "createdAt": "2026-01-15T00:00:00Z",
      "accessState": "ADMIN",
      "accessExpiresAt": null,
      "hasPaid": false,
      "lastSeenAt": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 137,
  "totalPages": 7
}
```

**Field meanings**:

| Field | Type | Meaning |
|-------|------|---------|
| `users` | array | Page of user rows, sorted by `createdAt` descending (newest first) |
| `page` | number | Zero-based page index actually applied |
| `size` | number | Page size actually applied (after clamping — echo this back, don't assume the requested `size`) |
| `totalElements` | number | Total users across all pages |
| `totalPages` | number | Total number of pages at this `size` |

**Per-user fields** (`AdminUserView`):

| Field | Type | Meaning |
|-------|------|---------|
| `id` | UUID string | User id — pass this in `userIds` to the gift endpoint |
| `firstName` / `lastName` | string | Account names |
| `email` | string | Account email |
| `role` | string | `"USER"` or `"ADMIN"` |
| `emailVerified` | boolean | Whether the user verified their email |
| `enabled` | boolean | Account enabled flag |
| `createdAt` | ISO-8601 string | Registration timestamp |
| `accessState` | string | Derived, same vocabulary as `GET /api/billing/entitlement`: `TRIAL` \| `ACTIVE` \| `EXPIRED` \| `ADMIN` |
| `accessExpiresAt` | ISO-8601 string \| null | Current access expiry; `null` for admins or a user with no entitlement row yet |
| `hasPaid` | boolean | Whether the user has ever completed a paid order (distinguishes a paid `ACTIVE` from a gifted/trial one) |
| `lastSeenAt` | ISO-8601 string \| null | Timestamp of the user's last successful, entitled WebSocket connection; `null` if they've never connected |

**Notes**:
- `accessState`/`accessExpiresAt`/`hasPaid` are derived the **same way** as the user-facing
  `GET /api/billing/entitlement` — an admin row and that user's own entitlement poll always agree.
- No search/filter query params exist today (no `email=`, no `role=`) — the frontend must
  paginate/filter client-side if needed.

---

### `POST /api/admin/entitlement/gift`

Bulk-grants free access to one or more users — extends every listed user's `accessExpiresAt` by
the **same** `addPeriodDays`, stacked on top of whatever time they already have left. Writes one
`ADMIN`-sourced entitlement ledger row per user, stamped with the acting admin's id, so every gift
is auditable via that user's `GET /api/billing/entitlement/history`.

**The gift is unpaid** — it never sets `hasPaid`. A gifted user's `accessState` reports as
`TRIAL` (not `ACTIVE`) and they remain free to buy a paid plan later.

**Request body**:

```json
{
  "userIds": [
    "b3f1c2a4-1234-4a2b-9c3d-abcdef123456",
    "a1b2c3d4-5678-90ab-cdef-1234567890ab"
  ],
  "addPeriodDays": 30,
  "reason": "Beta tester compensation"
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `userIds` | array of UUID strings | yes | Must be non-empty. Duplicates are de-duplicated (a repeated id is gifted once), order preserved. |
| `addPeriodDays` | integer | yes | Whole days to add. Must be `> 0` — omitted (`null`) or `<= 0` is rejected. |
| `reason` | string | no | Free-form note recorded on every resulting ledger row; omit or `null` for none. |

**Validated all-or-nothing**: every `userIds` entry is checked to exist **before** any write
happens. If even one id is unknown, the entire request is rejected with `404` and **nobody** is
gifted (the write is transactional, so a partial application never happens).

**Response `200 OK`**:

```json
{
  "updatedCount": 2,
  "grantedDurationSeconds": 2592000,
  "results": [
    { "userId": "b3f1c2a4-1234-4a2b-9c3d-abcdef123456", "newExpiresAt": "2026-08-24T00:00:00Z" },
    { "userId": "a1b2c3d4-5678-90ab-cdef-1234567890ab", "newExpiresAt": "2026-08-15T12:30:00Z" }
  ]
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `updatedCount` | number | Number of distinct users gifted (post-dedup) — equals `results.length` |
| `grantedDurationSeconds` | number | `addPeriodDays` converted to seconds, for reference |
| `results` | array | One entry per gifted user, in request order (post-dedup) |
| `results[].userId` | UUID string | The gifted user |
| `results[].newExpiresAt` | ISO-8601 string | That user's `accessExpiresAt` **after** the grant stacked on top of any remaining time — not simply "now + addPeriodDays" if they already had access left |

**Error cases**:

| Status | When |
|--------|------|
| `400 Bad Request` | Missing/empty `userIds`; missing, `null`, zero, or negative `addPeriodDays` |
| `404 Not Found` | Any `userIds` entry doesn't correspond to a real user — message lists the unknown id(s), e.g. `"Unknown user id(s): [...]"` |

---

### `GET /api/monitoring/presence`

Live snapshot of who is connected **right now** — the number of distinct users with an open
screener WebSocket session, their per-user session (tab/client) counts, and whether each is on a
custom-rules feed. This is the endpoint to answer "how many users are active at the moment": read
`onlineUsers` for the headline number.

Purely **in-memory, instantaneous, no persistence and no history** — it reflects the instant the
request is served, nothing more. It is captured under the same lock that guards WebSocket
connect/disconnect, so a single response is always internally consistent (no half-applied
connect/disconnect straddling the read). For a historical trend instead of a live count, use
[`GET /api/monitoring/usage`](#get-apimonitoringusage) below.

**Request**: no body, no query parameters.

**Response `200 OK`**:

```json
{
  "onlineUsers": 2,
  "totalSessions": 3,
  "users": [
    { "userId": "b3f1c2a4-1234-4a2b-9c3d-abcdef123456", "sessions": 2, "custom": true },
    { "userId": "a1b2c3d4-5678-90ab-cdef-1234567890ab", "sessions": 1, "custom": false }
  ]
}
```

**Field meanings**:

| Field | Type | Meaning |
|-------|------|---------|
| `onlineUsers` | number | Count of **distinct** connected users — `users.length` |
| `totalSessions` | number | Total open WebSocket sessions across all users — a user with two browser tabs open counts as 2 sessions but 1 user |
| `users` | array | Per-user breakdown, sorted by `sessions` descending (most-connected first) |
| `users[].userId` | UUID string | The connected user |
| `users[].sessions` | number | Number of open WebSocket sessions (tabs/clients) this user currently has |
| `users[].custom` | boolean | Whether this user has an active custom classification-rules context; `false` means they're on the default-only feed |

Returns `{"onlineUsers": 0, "totalSessions": 0, "users": []}` when nobody is connected — never an
error.

**Notes**:
- No pagination — the connected-user set is expected to be small relative to the whole user base
  by nature (only currently-online users appear at all).
- This does **not** reflect entitlement/billing state — a connected user is, by construction,
  already past the WebSocket's entitlement gate (`EntitlementService.hasAccess`), so everyone
  listed here has active access at the time of connection.

---

### `GET /api/monitoring/usage`

Persisted, historical usage report — aggregates the append-only `connection_events` log (written
on every successful, entitled WebSocket open) into **distinct-user counts per time-slice** over a
date range. Distinct from the live `GET /api/monitoring/presence` above: presence is an
instantaneous in-memory read with no history; `/usage` reads persisted data.

**Query parameters** (all optional):

| Param | Type | Default | Notes |
|-------|------|---------|-------|
| `start` | ISO date (`YYYY-MM-DD`) | today, in `zone` | Inclusive. Interpreted as a calendar date in `zone`, not UTC. |
| `end` | ISO date (`YYYY-MM-DD`) | today, in `zone` | Inclusive. Must not be before `start`. |
| `slice` | ISO-8601 duration, or `P1M` / `P1Y` | `PT1H` | e.g. `PT30M`, `PT1H`, `PT24H`, `P7D`. Minimum `PT1M`. `P1M` and `P1Y` are the two special **calendar-unit** literals — see below. |
| `zone` | IANA zone id | `Asia/Tashkent` | Determines where slice boundaries fall (local midnight / local `:00`/`:30`/month/year) and how output timestamps are labeled. |

Example: `GET /api/monitoring/usage?start=2026-07-20&end=2026-07-26&slice=PT24H` — one row per
calendar day (in the default `Asia/Tashkent` zone) from 2026-07-20 through 2026-07-26 inclusive.

**Calendar-unit slicing (`P1M` / `P1Y`)**: every other `slice` value is a fixed-length ISO-8601
`Duration` — bucket boundaries are `windowStart + n * sliceLength`, which works for anything from
minutes up to a fixed `P7D` week. Months and years aren't fixed-length (28-31 days; 365-366 days),
so they can't be expressed that way. `slice=P1M` and `slice=P1Y` are handled as a **separate,
calendar-aware path**: buckets fall on real local calendar-month/-year boundaries (e.g. Jan 1 →
Feb 1 → Mar 1 in `zone`, each bucket spanning that month's actual length) instead of a fixed
duration. Everything else about the response — `slices[].start`/`end`, omitted empty slices,
`mostActive`, `totalConnections` — behaves identically; only how the buckets are computed differs.
No other calendar multiples (`P3M`, `P2Y`, etc.) are supported today — only the exact literals
`P1M` and `P1Y`; anything else is parsed as a plain ISO-8601 duration and will `400` if it isn't
one.

**Response `200 OK`**:

```json
{
  "start": "2026-07-20T00:00:00+05:00",
  "end": "2026-07-27T00:00:00+05:00",
  "zone": "Asia/Tashkent",
  "slice": "PT24H",
  "sliceCount": 6,
  "totalConnections": 412,
  "mostActive": {
    "start": "2026-07-23T00:00:00+05:00",
    "end": "2026-07-24T00:00:00+05:00",
    "uniqueConnections": 98
  },
  "slices": [
    { "start": "2026-07-20T00:00:00+05:00", "end": "2026-07-21T00:00:00+05:00", "uniqueConnections": 51 },
    { "start": "2026-07-23T00:00:00+05:00", "end": "2026-07-24T00:00:00+05:00", "uniqueConnections": 98 }
  ]
}
```

(`sliceCount: 6` above means 6 of the 7 requested days had at least one connection — note that
`slices` in this example only shows 2 of those 6 for brevity.)

**Field meanings**:

| Field | Type | Meaning |
|-------|------|---------|
| `start` | ISO-8601 offset datetime | Window start (inclusive), projected into `zone` — the start of the `start` calendar date at local midnight |
| `end` | ISO-8601 offset datetime | Window end (**exclusive**) — the day *after* `end`, at local midnight in `zone` |
| `zone` | string | Echoed IANA zone id actually used |
| `slice` | string | Echoed ISO-8601 slice duration actually used, e.g. `"PT24H"` |
| `sliceCount` | number | Count of **non-empty** slices returned (see note below) |
| `totalConnections` | number | Sum of `uniqueConnections` across all returned slices — **not** distinct users over the whole range; a user active in two different slices is counted once per slice |
| `mostActive` | object \| null | The single slice with the highest `uniqueConnections`; ties keep the earliest slice; `null` only when the range produces zero non-empty slices |
| `slices` | array | Non-empty slices only, ordered by `start` ascending |
| `slices[].start` / `slices[].end` | ISO-8601 offset datetime | That slice's boundaries in `zone` |
| `slices[].uniqueConnections` | number | Distinct users who opened at least one entitled WebSocket connection during that slice |

**Important: empty slices are omitted, not zero-filled.** A slice with zero unique connections is
simply absent from `slices` — `sliceCount` counts only the non-empty ones, and gaps in the
requested range are expected. If the frontend renders a chart with fixed buckets (e.g. one bar
per day), it must **synthesize the missing buckets as zero** itself — don't assume `slices` is
contiguous or covers every slice in the requested range.

**Error cases**:

| Status | When |
|--------|------|
| `400 Bad Request` | `end` before `start`; `slice` isn't `P1M`/`P1Y` and isn't a valid ISO-8601 duration `>= PT1M`; `zone` isn't a valid IANA zone id |

---

## HTTP Error Shapes

All `4xx` errors (other than the empty-body `403` from the auth/role gate) return the standard
JSON error body:

```json
{
  "message": "Unknown user id(s): [b3f1c2a4-1234-4a2b-9c3d-abcdef123456]",
  "status": 404,
  "path": "/api/admin/entitlement/gift"
}
```

| Status | Meaning |
|--------|---------|
| `403 Forbidden` (empty body) | Missing/invalid JWT, or a valid JWT for a non-admin user |
| `400 Bad Request` | Malformed request (see per-endpoint tables above) |
| `404 Not Found` | `POST /api/admin/entitlement/gift` only — one or more `userIds` don't exist |

---

## Practical Notes for the Frontend

- **No pagination on `/usage`**: the whole date range is returned in one response — for a wide
  range with a fine `slice` this can be a lot of rows; pick a coarser `slice` (e.g. `PT24H` /
  `P1D`-equivalent) for multi-week ranges, or `P1M`/`P1Y` for multi-month/-year ranges.
- **Use `slice=P1M` for "usage per calendar month" charts** (e.g. a 1-year range bucketed into 12
  bars) instead of approximating with `PT720H` — `PT720H` is a fixed 30-day window that drifts out
  of alignment with real month boundaries (31-day months, February), while `P1M` buckets on actual
  local calendar-month boundaries. Same idea for `slice=P1Y` on multi-year ranges.
- **`accessState` on the user listing uses the same enum as everywhere else** (`TRIAL` /
  `ACTIVE` / `EXPIRED` / `ADMIN`) — reuse existing state-badge rendering logic from the billing
  UI rather than building a new one.
- **Gifting is idempotent-ish but not "topping up to N days"**: `addPeriodDays` always *adds* to
  whatever the user currently has, it never sets an absolute expiry. Gifting the same user twice
  stacks both grants.
- **`newExpiresAt` in the gift response already reflects stacking** — use it directly to update
  any optimistic UI state for that user; don't re-derive it as `now + addPeriodDays`.
- **For a live "N users online" counter, poll `/presence`, not `/usage`**: `/usage`'s finest
  granularity is `PT1M` and it reads persisted history, so it's the wrong tool for a real-time
  count — `/presence` is cheap, in-memory, and reflects the current instant.
