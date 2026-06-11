# Plan: Live Classification Rule Propagation

## Goal

When a user calls `PUT /api/rules` or `DELETE /api/rules`, their updated thresholds must take
effect immediately for all of their currently-connected WebSocket sessions — no reconnect required.

---

## Background

### Current behaviour

At WebSocket connect time (`@OnOpen`), `UserFeedRegistry.onUserConnect(userId)` loads the user's
persisted rules from the database and builds a `UserClassificationContext` containing:

- `UserClassificationRules` — the per-`(symbol, market)` threshold lookup table
- `OrderBookFeedStore` — the user's personal feed store (accumulates classified levels)
- `ConcurrentHashMap<String, SymbolState>` — per-symbol activity state, one entry per shard

The context is pushed into a `volatile UserClassificationContext[] active` array and fanned out
to all shard classifiers via `DisruptorShardManager.setActiveUserContexts(active)`. From that
point on the context is immutable; rule edits only update the database — the in-memory context
is not touched until the user reconnects.

### What must change

When a rule write completes, three things must happen for every connected session of that user:

1. **Classifier uses new thresholds** — shard threads must start using the updated
   `UserClassificationRules` on the very next diff event.
2. **Personal feed store is consistent** — the old personal snapshot entries (built against the
   previous rules / previous configured key set) must not bleed into the next snapshot delivery.
3. **Client receives a fresh snapshot** — the client's local view must be reconciled by delivering
   a new merged snapshot that reflects the new rule's configured key set.

---

## Chosen Approach: Full Context Rebuild (Option 2)

When a rule is updated for a connected user, discard the old `UserClassificationContext` entirely
and construct a fresh one (new `OrderBookFeedStore`, empty `SymbolState` map). The old context
goes out of scope and is GC'd.

**Why not in-place rule swap (Option 1)?**

Option 1 would swap only the `(rule, feedStore)` pair inside the existing context via an
`AtomicReference`, preserving `SymbolState` entries across the update. The practical user-visible
difference is negligible: both options reset the personal feed store, so there is the same brief
gap (~1 diff cycle, ≤1 s) before the personal feed is repopulated. Option 2 is simpler to
implement because `UserClassificationContext` remains a fully immutable record — no internal
`AtomicReference`, no `updateRuleStore()` method, no partial-mutability reasoning. The cost of
rebuilding `active[]` and pushing to `DisruptorShardManager` is one small array allocation + 2
volatile writes; negligible.

**Stale `SymbolState` entries:** not a concern with Option 2 — the new context always starts
with an empty state map.

**Concurrent updates:** if two users update rules simultaneously, two `RuleUpdatedEvent`s fire on
two different Tomcat threads. `onRuleUpdated` is `synchronized`, so they serialise. If the _same_
user fires two rapid rule updates, both events are serialised; the second call reads the most
recent DB state and leaves the context in the correct final state. Sessions may receive two
snapshots ~100 ms apart — both correct, the second superseding the first.

---

## Threading Invariants

| Write | Read | Safety mechanism |
|-------|------|-----------------|
| `UserFeedRegistry` swaps `session.context` | broadcaster reads `session.getContext()` | `volatile` field |
| `UserFeedRegistry` sets `session.status = NEED_SNAPSHOT` | broadcaster reads `session.getStatus()` | `volatile` field |
| `UserFeedRegistry` swaps `active[]` | shard classifiers read `activeUserContexts` | `volatile` array reference |

**Critical ordering in `onRuleUpdated`:**

```
session.setContext(newCtx);          // volatile write A — happens first
session.setStatus(NEED_SNAPSHOT);    // volatile write B — happens second
```

Java memory model guarantees: when the broadcaster reads `status == NEED_SNAPSHOT` (read of B),
it has a happens-before chain back to write A, so it is guaranteed to also see the new `context`
when it reads `session.getContext()`. Reversing the order would break this guarantee — the
broadcaster could consume `NEED_SNAPSHOT`, build a snapshot from the old context, set status to
`READY`, and never deliver the new snapshot.

---

## Breaking the Circular Dependency

`UserFeedRegistry` injects `ClassificationRuleService`. If `ClassificationRuleService` injected
`UserFeedRegistry` back, Spring would detect a circular dependency. Solution: use a Spring
`ApplicationEvent`.

`ClassificationRuleService` injects `ApplicationEventPublisher` and publishes
`RuleUpdatedEvent(UUID userId)` after each successful rule write. `UserFeedRegistry` listens
with `@TransactionalEventListener(phase = AFTER_COMMIT)`, which fires on the HTTP request thread
after the DB transaction commits, ensuring `buildRuntimeRule(userId)` sees the committed data.

---

## Lock-Holding Strategy

`onRuleUpdated` does a DB read (`buildRuntimeRule`) whose cost we do not want to hide under
the registry's `synchronized` lock (which also serialises WebSocket connect/disconnect). The
solution:

```
1. Call buildRuntimeRule(userId) WITHOUT holding the lock  (DB read on Tomcat thread)
2. Enter synchronized block
3. Read / mutate contexts, sessionsByUser, active[]
4. Exit synchronized block
```

The brief window between step 1 and step 2 where another connect/disconnect could race is
harmless: we reconsider the current state inside the lock before mutating anything.

---

## Atomic Session Registration — `sessionsByUser` Replaces `refCounts`

Session tracking must be updated in the **same lock acquisition** as the context lifecycle.
If registration were a separate synchronized call (e.g. `onUserConnect(userId)` followed by a
standalone `registerSession(userId, session)`), two races open up against `onRuleUpdated`:

- **Connect race:** `onRuleUpdated` lands between the two calls, replaces the context, and
  updates every session in `sessionsByUser` — which does not yet contain the new session. That
  session keeps the old context forever: the old context is no longer in `active[]`, so its feed
  store is never populated or drained again, and every configured symbol goes permanently silent
  for that session (global updates for those keys are filtered out by `configuredKeys`, and
  personal updates never arrive) until reconnect.
- **Disconnect race:** with unregister and refcount-decrement as two separate lock acquisitions,
  `onRuleUpdated` (Case B) landing between them counts the user's sessions inconsistently with
  the refcount — depending on call order this either tears down a context a surviving session
  still uses, or leaks the context forever.

Therefore:

- `sessionsByUser` (`Map<UUID, List<UserWebSocketSession>>`) is the **single source of truth**
  for connected sessions. The `refCounts` map is **deleted** — the old refcount is simply
  `sessionsByUser.get(userId).size()`, and the context-teardown condition becomes "the user's
  session list just became empty".
- `onUserConnect(UUID userId, UserWebSocketSession session)` registers the session AND
  builds/reuses the context inside one `synchronized` block, setting the context directly on the
  session (it no longer returns the context).
- `onUserDisconnect(UUID userId, UserWebSocketSession session)` unregisters the session AND tears
  down the context (when the list empties) inside one `synchronized` block.
- Because `List.remove(session)` is naturally idempotent (the second call returns `false` and
  bails out), the `released`/`markReleased()` one-shot guard in `UserWebSocketSession` is no
  longer needed and is **deleted** — the Tomcat @OnClose/@OnError double-fire is absorbed by
  `onUserDisconnect` returning early when the session is already gone.

---

## Three Transition Cases in `onRuleUpdated`

### Case A — Rule update (has context → still has rules)

Most common case. User edits thresholds or adds/removes specific tickers but still has at
least one rule.

1. Build new `UserClassificationRules` from DB (outside lock).
2. Inside lock: look up existing context. Create a fresh `UserClassificationContext` with the
   new rules, a new empty `OrderBookFeedStore`, and an empty `ConcurrentHashMap<String, SymbolState>`.
3. Replace in `contexts` map.
4. For every session in `sessionsByUser.get(userId)`:
   - `session.setContext(newCtx)` (volatile write — happens first)
   - `session.setStatus(NEED_SNAPSHOT)` (volatile write — happens second)
5. Rebuild `active[]` and push to `DisruptorShardManager`.

### Case B — First rule added while connected (no context → has rules)

User had no custom rules and no context. They add their first rule via the REST API.

1. Build new `UserClassificationRules` from DB (outside lock). Result is non-empty.
2. Inside lock: confirm `contexts.get(userId) == null`. Retrieve sessions from
   `sessionsByUser.get(userId)`.
3. If no sessions are present, do nothing — user is not connected; rules load at next connect.
4. If sessions are present: create `UserClassificationContext`, store it in `contexts`.
5. For every session: `setContext(newCtx)` then `setStatus(NEED_SNAPSHOT)` (write order matters).
6. Rebuild `active[]` and push to shards.

### Case C — All rules deleted while connected (has context → no rules)

User deletes every rule. `buildRuntimeRule(userId)` returns `Optional.empty()`.

1. Outside lock: detect empty rule set.
2. Inside lock: look up existing context. Remove from `contexts`.
3. For every session in `sessionsByUser.get(userId)`:
   - `session.setContext(null)` (volatile write — happens first; session reverts to global feed)
   - `session.setStatus(NEED_SNAPSHOT)` (volatile write — happens second)
4. Rebuild `active[]` and push to shards.

> **Implementation note:** with `refCounts` gone, Cases A and B collapse into a single code
> branch — both build a fresh context and swap it in; they differ only in whether a previous
> context existed. Case C is the only structurally different branch.

---

## Implementation Steps

### Step 1 — `UserWebSocketSession`: make `context` mutable, drop the release guard

**File:** `src/main/java/dev/abu/screener_backend/ws/UserWebSocketSession.java`

- Change `private final UserClassificationContext context` → `private volatile UserClassificationContext context`; keep `@Getter`, add a `setContext(UserClassificationContext)` setter
- Remove `context` from the constructor — the session is now constructed without a context;
  `UserFeedRegistry.onUserConnect` sets it (before the broadcaster ever sees the session)
- Delete the `released` AtomicBoolean field and `markReleased()` — disconnect idempotency is now
  provided by `onUserDisconnect`'s list removal (see "Atomic Session Registration" above)
- `userId`, `sendQueue`, `status`, `running`, `seqNumber` are all unchanged

### Step 2 — `RuleUpdatedEvent`: new event class

**File:** `src/main/java/dev/abu/screener_backend/analysis/rule/RuleUpdatedEvent.java`

```java
public record RuleUpdatedEvent(UUID userId) {}
```

A plain record (not extending `ApplicationEvent`) — Spring's `@EventListener` handles plain
objects since Spring 4.2.

### Step 3 — `ClassificationRuleService`: publish event after writes

**File:** `src/main/java/dev/abu/screener_backend/analysis/rule/ClassificationRuleService.java`

- Inject `ApplicationEventPublisher eventPublisher` (constructor injection)
- At the end of `upsertRules(userId, req)` — after all DB writes, still inside `@Transactional` — call `eventPublisher.publishEvent(new RuleUpdatedEvent(userId))`
- At the end of `deleteRules(userId, req)` — same pattern

The event fires synchronously on the Tomcat thread. Spring's
`@TransactionalEventListener(AFTER_COMMIT)` in the registry defers actual execution until after
the transaction commits, so the DB read in `buildRuntimeRule` always sees committed data.

### Step 4 — `UserFeedRegistry`: atomic session lifecycle + rule-update listener

**File:** `src/main/java/dev/abu/screener_backend/analysis/UserFeedRegistry.java`

#### 4a — Replace `refCounts` with `sessionsByUser`; registration atomic with context lifecycle

Delete the `refCounts` map. Add:
```java
private final Map<UUID, List<UserWebSocketSession>> sessionsByUser = new HashMap<>();
```

Rewrite the connect/disconnect lifecycle — **signatures change**, both now take the session, and
registration happens inside the same `synchronized` block as the context mutation:

```java
public synchronized void onUserConnect(UUID userId, UserWebSocketSession session) {
    sessionsByUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(session);

    UserClassificationContext existing = contexts.get(userId);
    if (existing != null) {
        session.setContext(existing); // REUSE — no DB reload, no re-push
        return;
    }

    Optional<UserClassificationRules> rules = ruleService.buildRuntimeRule(userId);
    if (rules.isEmpty()) {
        return; // no rules → default-only session, context stays null
    }

    UserClassificationContext ctx = new UserClassificationContext(
            userId, rules.get(), new OrderBookFeedStore(), new ConcurrentHashMap<>());
    contexts.put(userId, ctx);
    session.setContext(ctx);
    rebuildActiveArray();
    shardManager.setActiveUserContexts(active);
}

public synchronized void onUserDisconnect(UUID userId, UserWebSocketSession session) {
    List<UserWebSocketSession> list = sessionsByUser.get(userId);
    if (list == null || !list.remove(session)) {
        return; // unknown session, or @OnClose/@OnError double-fire — idempotent no-op
    }
    if (!list.isEmpty()) {
        return; // user still has other sessions — context survives
    }
    sessionsByUser.remove(userId);
    if (contexts.remove(userId) != null) {
        rebuildActiveArray();
        shardManager.setActiveUserContexts(active);
    }
}
```

The DB read in `onUserConnect` stays under the lock, exactly as today — connects are rare, so
the lock-holding concern applies only to `onRuleUpdated`.

#### 4b — Add `onRuleUpdated` listener

With `refCounts` gone, Cases A and B share one branch:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onRuleUpdated(RuleUpdatedEvent event) {
    UUID userId = event.userId();

    // DB read outside the lock — we do not want to hold synchronized while doing I/O.
    Optional<UserClassificationRules> newRules = ruleService.buildRuntimeRule(userId);

    synchronized (this) {
        List<UserWebSocketSession> sessions =
                sessionsByUser.getOrDefault(userId, List.of());
        UserClassificationContext existing = contexts.get(userId);

        if (newRules.isEmpty()) {
            if (existing == null) {
                return; // no rules and no context — nothing to do
            }
            // Case C: deleted all rules while connected
            contexts.remove(userId);
            for (UserWebSocketSession s : sessions) {
                s.setContext(null);                                     // volatile write A
                s.setStatus(UserWebSocketSession.Status.NEED_SNAPSHOT); // volatile write B
            }
        } else {
            if (existing == null && sessions.isEmpty()) {
                return; // Case B with no sessions — rules load at next connect
            }
            // Case A (existing != null) or Case B (existing == null, sessions present):
            // build a fresh context and swap it in.
            UserClassificationContext ctx = new UserClassificationContext(
                    userId, newRules.get(), new OrderBookFeedStore(), new ConcurrentHashMap<>());
            contexts.put(userId, ctx);
            for (UserWebSocketSession s : sessions) {
                s.setContext(ctx);                                      // volatile write A
                s.setStatus(UserWebSocketSession.Status.NEED_SNAPSHOT); // volatile write B
            }
        }
        rebuildActiveArray();
        shardManager.setActiveUserContexts(active);
    }
}
```

#### 4c — Update `buildRuntimeRule` return type reference

`buildRuntimeRule` in `ClassificationRuleService` still returns `Optional<UserClassificationRules>`
(same content — just the class was renamed from `UserClassificationRule` to `UserClassificationRules`).
Verify all call sites use the renamed type.

### Step 5 — `ScreenerWebSocketEndpoint`: pass the session through the registry lifecycle

**File:** `src/main/java/dev/abu/screener_backend/ws/ScreenerWebSocketEndpoint.java`

`onOpen` — construct the session first (context null), then register. Registration must happen
**before** `broadcaster.addSession`, so the broadcaster never sees a custom user's session
without its context (it would deliver a plain global snapshot and mark the session READY,
leaving configured keys showing stale default-tier data until the first personal update):

```java
UserWebSocketSession userSession = new UserWebSocketSession(session, user.userId());
session.getUserProperties().put("session", userSession);
userFeedRegistry.onUserConnect(user.userId(), userSession); // registers + sets context
userSession.startSendLoop();
broadcaster.addSession(userSession);
```

`release(UserWebSocketSession userSession)` — replace the `markReleased()`-guarded decrement
with the unconditional, idempotent call (every session, including null-context sessions):

```java
private void release(UserWebSocketSession userSession) {
    userSession.disconnect();
    broadcaster.removeSession(userSession);
    userFeedRegistry.onUserDisconnect(userSession.getUserId(), userSession);
}
```

---

## What Changes and What Does Not

| Component | Change |
|-----------|--------|
| `UserWebSocketSession` | `context` becomes `volatile` + `setContext()`; constructor drops the context param; `released`/`markReleased()` deleted |
| `RuleUpdatedEvent` | New record class |
| `ClassificationRuleService` | Inject `ApplicationEventPublisher`; publish event after writes |
| `UserFeedRegistry` | `refCounts` replaced by `sessionsByUser`; `onUserConnect`/`onUserDisconnect` now take the session (atomic registration); add `onRuleUpdated` listener |
| `ScreenerWebSocketEndpoint` | Construct session before registering; pass session to `onUserConnect`/`onUserDisconnect`; drop the `markReleased()` guard |
| `UserClassificationContext` | **No change** — remains fully immutable |
| `UserClassificationRules` | **No change** — remains fully immutable |
| `OrderBookClassifier` | **No change** |
| `OrderBookBroadcaster` | **No change** — already reads `session.getContext()` and `session.getStatus()` correctly |
| `DisruptorShardManager` | **No change** |
| `ClassificationRuleController` | **No change** |

---

## Snapshot Delivery After Rule Update

After `onRuleUpdated` completes, on the next broadcaster drain tick (within 100 ms):

1. Broadcaster checks `session.getStatus() == NEED_SNAPSHOT` → true.
2. Calls `session.getContext()` — volatile read; guaranteed to see the new context (happens-before chain).
3. For a null context (Case C): delivers global snapshot.
4. For a non-null context (Cases A and B): calls `mergedSnapshot(ctx)`, which builds:
   - Global snapshot entries for symbols **not** in `ctx.rule().configuredKeys()`
   - Personal snapshot entries from `ctx.feedStore().getSnapshot()` — **empty** right after the rebuild
5. Resets session seq counter, enqueues snapshot, sets status to `READY`.

**Expected gap — configured symbols briefly disappear.** Because the fresh personal feed store
is empty and configured keys are excluded from the global half of the merge, the user's
configured symbols are entirely absent from this first post-update snapshot. On the next diff
for each configured book (≤ 1 s under normal conditions) the classifier runs against the new
context, emits ADDs into the personal feed store, and the symbols reappear with their custom
tiers. This disappear-then-reappear is identical to what a fresh connect of a custom-rules user
produces today. It is deliberate: configured keys are NOT seeded with global-tier data while
waiting for the personal feed to populate — keep it simple.

---

## Tests to Add / Update

- **`UserFeedRegistryTest`**: update the existing lifecycle tests for the new signatures
  (`onUserConnect(userId, session)` / `onUserDisconnect(userId, session)`) and the removal of
  `refCounts` — refcount assertions become assertions about context survival across session
  connects/disconnects (observable via `activeContexts()` and `session.getContext()`). Add:
  - Case A: update rules while connected → context replaced, sessions get new context + NEED_SNAPSHOT, `active[]` rebuilt
  - Case B: add first rule while connected → context created, sessions updated
  - Case C: delete all rules while connected → context removed, sessions revert to null context
  - No connected sessions: rule update is a no-op (no crash)
  - Disconnecting the same session twice is a no-op (replaces the old `markReleased()` guard semantics)
  - Concurrent same-user updates: last write wins, no state corruption
- **`ClassificationRuleServiceTest`** (new or existing): verify `RuleUpdatedEvent` is published after `upsertRules` and `deleteRules`; verify it is NOT published on validation failure
- *(Optional but recommended)* one Spring integration test asserting the
  `@TransactionalEventListener(AFTER_COMMIT)` path fires end-to-end — the listener silently
  no-ops if the publish ever moves outside an active transaction
