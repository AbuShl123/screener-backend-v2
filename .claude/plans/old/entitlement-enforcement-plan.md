# Entitlement Enforcement Plan — WebSocket Connect + `/api/rules`

> Wires the already-built `EntitlementService.hasAccess(User)` into the two access gates called
> out as deferred in `payment-gateway-multicard.md` §15 ("Access-gate enforcement — `hasAccess(...)`
> exists but isn't wired into REST/`@OnOpen`"). Read that doc first for the entitlement/payment
> data model this plan builds on.

## Context

`EntitlementService.hasAccess(User)` (`entitlement/EntitlementService.java:133-142`) already
implements the correct rule — ADMIN bypass, then `accessExpiresAt` in the future — but its own
Javadoc says *"not wired into any gate yet"*. Right now anyone holding a valid JWT can open `/ws`
and hit `/api/rules` regardless of whether their trial/subscription has expired. This plan wires
`hasAccess` into:

1. The WebSocket `@OnOpen` handshake (`ws/ScreenerWebSocketEndpoint.java`)
2. All authenticated `/api/rules` endpoints (`analysis/rule/ClassificationRuleController.java`)

**Explicitly out of scope** (a separate deferred item in the doc, not silently dropped here):
re-checking entitlement on an *already-open* WS session. A session opened while access is valid
keeps streaming until it disconnects, even if `accessExpiresAt` passes mid-session (access tokens
are valid 3h, so the exposure window is bounded but real). This plan is connect-time gating only;
a follow-up plan would be needed to close the mid-session gap.

## Design

Both gates reuse the exact existing pattern already used in `payment/OrderController.java`:
resolve the JWT principal's `userId`, load the full `User` via `AuthService.getUser(UUID)`
(required because `hasAccess` branches on `user.getRole()`), then call
`entitlementService.hasAccess(user)`. No new abstractions are introduced:

- No new exception type — reuse `ApiException.forbidden(...)`, already wired into
  `GlobalExceptionHandler` → HTTP 403 with the message as the JSON error body.
- No new WS close-code convention — reuse `CloseReason.CloseCodes.VIOLATED_POLICY`, the only close
  code this codebase already uses for connection rejection.

### 1. WebSocket connect gate — `ws/ScreenerWebSocketEndpoint.java`

Insert the check in `onOpen`, immediately after the existing token-validity null-check (currently
lines 38-41) and **before** any registration side effects run (`UserWebSocketSession` construction,
`userFeedRegistry.onUserConnect`, `broadcaster.addSession` — currently line 43 onward). This
mirrors how the existing auth failure is an early return before any of that wiring happens, so
rejecting on entitlement needs no cleanup either:

```java
AuthenticatedUser user = jwtService.validateAndExtract(tokens.get(0));
if (user == null) {
    closeUnauthorized(session, "Invalid or expired token");
    return;
}
User fullUser = authService.getUser(user.userId());
if (!entitlementService.hasAccess(fullUser)) {
    closeUnauthorized(session, "Subscription required");
    return;
}
```

- Add `@Autowired AuthService authService;` and `@Autowired EntitlementService entitlementService;`
  fields alongside the existing `broadcaster` / `jwtService` / `userFeedRegistry` fields.
- Reuse the existing `closeUnauthorized(session, reason)` helper as-is (same `VIOLATED_POLICY`
  close code, just a different reason string) — no new helper method or close-code enum.
- Cost: one extra DB round-trip (`AuthService.getUser` + `EntitlementService.hasAccess`'s
  `repository.findByUserId`) per connection open only — not on the hot per-message path the
  CLAUDE.md performance rules govern, so this is acceptable.

### 2. `/api/rules` gate — `analysis/rule/ClassificationRuleController.java`

There's no shared service-layer chokepoint today (each of the 4 authenticated endpoints calls a
different `ClassificationRuleService` method directly), so the check belongs in the controller,
one level above where `userId(authentication)` is currently called — matching `OrderController`'s
existing `currentUser(Authentication)` helper:

```java
private final ClassificationRuleService ruleService;
private final DefaultClassificationRule defaultRule;
private final AuthService authService;
private final EntitlementService entitlementService;

// constructor grows to take authService + entitlementService

private UUID authorizedUserId(Authentication authentication) {
    UUID userId = userId(authentication);
    User user = authService.getUser(userId);
    if (!entitlementService.hasAccess(user)) {
        throw ApiException.forbidden("Active subscription required");
    }
    return userId;
}
```

Replace `userId(authentication)` with `authorizedUserId(authentication)` at the 4 call sites:
`getRules`, `getRule`, `upsertRules`, `deleteRules` (currently lines 47, 54, 59, 64). Keep the
private static `userId(Authentication)` helper unchanged — `authorizedUserId` calls it internally.

**`GET /api/rules/default` is left ungated.** It returns global default thresholds, takes no
`Authentication` parameter today, and isn't per-user data — gating it would mean adding a principal
param purely to check entitlement on a static payload. If you want lapsed users to lose visibility
into default thresholds too, the same `authorizedUserId` pattern extends there (discarding the
returned UUID) — flag it and it's a one-line addition.

## Files to touch

- `src/main/java/dev/abu/screener_backend/ws/ScreenerWebSocketEndpoint.java` — add 2 autowired
  fields + a 4-line check in `onOpen`.
- `src/main/java/dev/abu/screener_backend/analysis/rule/ClassificationRuleController.java` — add 2
  constructor deps + an `authorizedUserId` helper + swap 4 call sites.

No DB migration, no new config, no new exception/close-code types.

## Verification

- **Unit**: add/extend controller tests to assert `403` when `EntitlementService.hasAccess` returns
  `false`, and pass-through when `true`. `EntitlementServiceTest` already covers `hasAccess` itself
  (ADMIN bypass, expiry boundary), so the new tests only need to verify the gate calls it and reacts
  correctly, not re-test its logic.
- **Manual**: run the backend locally; register a user (starts in `TRIAL` per `startTrial`, so
  `hasAccess` is immediately `true`) — confirm `/ws` connects and `/api/rules` works normally. Then
  set `accessExpiresAt` to the past on that user's `user_entitlement` row in the dev DB (or wait out
  a short trial) and confirm: the `/ws` connection is closed immediately with reason "Subscription
  required", and `/api/rules` calls return `403`. Confirm an `ADMIN`-role user bypasses both gates
  regardless of `accessExpiresAt`.
