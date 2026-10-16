# Sequence Diagrams Foundation

**As of**: 2026-10-16.

This file lists which processes in the screener are worth drawing as sequence diagrams, and which
actors and participants each one has. It does not contain the diagrams themselves. They can be
drawn from it later.

It builds on [`requirements-foundation.md`](requirements-foundation.md) and follows the same epic
split.

---

## What earns a sequence diagram

A sequence diagram is worth drawing when **several participants exchange messages, the order
matters, and there are failure or async branches**. Plain CRUD (the plan catalog, admin views,
monitoring endpoints) does not need one.

In this project most of the interesting flows are started by an exchange, a timer or the payment
gateway, not by a user click. This is the same point as "the read-only concern" in the
requirements foundation.

---

## 1. Core set (draw these first)

### 1.1 Pipeline startup (Epic 1)

How the market-data pipeline comes up at boot, from an empty JVM to subscribed depth streams. It
ends where 1.2 begins: the first diff reaching a `PENDING` book.

There is no separate startup routine. The first run of the scheduled universe refresh is what
brings the pipeline up, and every later refresh goes through the same code. This diagram therefore
also covers the universe refresh.

- **Actors:** Scheduler, Exchange REST, Exchange WS.
- **Participants:** `DisruptorShardManager`, `InstrumentUniverseService`, the adapters'
  `InstrumentSource`s, `InstrumentRegistry`, `BookSlotTable`, `StreamManager`, connection pool
  (`ConnectionPool` with its `StreamConnection`s, drawn as one lifeline).
- **Main flow:**
  1. The Spring context starts. `DisruptorShardManager` validates the shard count and starts the
     shards (`@PostConstruct`), before any data can arrive.
  2. The scheduler fires the first `refresh()`.
  3. Every enabled source fetches its instruments from Exchange REST concurrently, each on its own
     virtual thread, under one shared timeout.
  4. On the discovery thread: apply the exclusion list → register instruments in
     `(venue, nativeSymbol)` order (dense ids) → allocate a book slot per new instrument →
     `publish()` the slot table → fire `InstrumentUniverseChangedEvent`.
  5. `StreamManager` handles the event synchronously on the same thread: for each venue that
     appears for the first time it starts a pool, which splits the instruments evenly across N
     connections and calls `connect()` on each.
  6. Async, on each connection's own thread: `onOpen` → start the heartbeat → send subscribe
     frames, one per chunk of `subscribe-chunk-size` instruments.
  7. Depth diffs start arriving. Every book is `PENDING`, so recovery begins (see 1.2).
- **Ordering invariant (the reason to draw it):** register all → allocate slots → publish → fire
  the event → subscribe. If a subscribe frame went out first, a message could resolve to an id
  past the end of the published slot array. `InstrumentUniverseService` asserts this order.
- **Fragments:**
  - `par` one fetch per source.
  - `alt` a source throws, times out, reports the wrong venues, or empties a previously non-empty
    venue: its venues keep their previous universe, the other sources apply normally.
  - `alt` every source failed: no event is fired, and no pool starts.
  - `loop` per venue on its first appearance: start a pool. A disabled venue is skipped.
  - `loop` per connection: `connect()`, then a nested `loop` per chunk: send a subscribe frame.
  - `alt` connection closed: reconnect with exponential backoff, and `onOpen` resubscribes.
- **Notes:**
  - Mark the async boundary at `connect()`. The discovery thread returns before any socket is
    open.
  - A later refresh registers new instruments and allocates their slots, but does not subscribe
    them: a venue's pool already exists, and dynamic re-subscription is not implemented yet. Show
    this as a note or a separate small `alt`, so the diagram doesn't imply new listings stream
    automatically.

### 1.2 Order-book sync and recovery (Epic 1)

The most important diagram. It shows the system's main correctness guarantee: never present an
unsynced book as live.

- **Actors:** Exchange WS and Exchange REST, as two separate lifelines (they are independent
  channels).
- **Participants:** connection pool (`StreamManager` / `ConnectionPool`), Disruptor shard, book
  consumer (`DepthEventHandler` + the venue's `DepthSyncStrategy`), `SnapshotRequestQueue`,
  `SnapshotFetcher`.
- **Main flow:** a diff arrives while the book is `PENDING` → a snapshot is requested → diffs are
  buffered while `RECOVERING` → the snapshot comes back as a ring event (`REST_MSG`) → the buffer
  is replayed → `SYNCED`.
- **Fragments:**
  - `alt` sequence gap / parse error / buffer overflow: clear the book, back to `PENDING`,
    re-request.
  - `alt` `REST_FAILED`: the snapshot fetch failed.
  - `alt` request refused by the queue: the diff is dropped.
- **Note:** draw one generic exchange and add a note that the sequence checks differ per venue
  (Binance `U` / `u` / `pu`, MEXC version fields).

### 1.3 Payment → access grant (Epic 3)

The money flow. It has two paths into the same idempotent grant.

- **Actors:** Subscriber, Multicard.
- **Participants:** frontend, `OrderService`, `MulticardCallbackService`,
  `PaymentReconciliationService` (scheduler), `EntitlementService`, `EntitlementLedger`.
- **Main flow:** create order → redirect to Multicard → user pays → Multicard callback →
  `markPaidAndGrant` → `EntitlementService.extend` → one ledger row → the callback is
  acknowledged. The grant happens **before** the acknowledgement.
- **Fragments:**
  - `alt` / `par` the reconciliation sweep finds the same payment and reaches the same grant.
  - `alt` duplicate callback: no second grant.

### 1.4 WebSocket session and feed delivery (Epic 2)

How the product reaches the user.

- **Actors:** Subscriber; Admin (bypasses entitlement).
- **Participants:** `/ws` endpoint, JWT validation, `EntitlementService`, per-session state,
  `FeedBroadcaster` (100 ms drain loop), `DepthChannel`.
- **Main flow:** connect with a JWT → auth and entitlement checks → first snapshot → incremental
  updates.
- **Fragments:**
  - `alt` invalid JWT or no entitlement: the session is rejected in `@OnOpen`.
  - `loop` every 100 ms: drain and send updates.
  - `alt` slow session: evicted.

### 1.5 Rule edit → live feed without reconnect (Epic 2)

Crosses REST and WebSocket, which is why it is worth a diagram.

- **Actors:** Subscriber.
- **Participants:** `/api/rules` controller, entitlement check, rule service + DB, the user's
  classification context, connected sessions, `FeedBroadcaster`.
- **Main flow:** `PUT /api/rules` → persist the rule → rebuild the classification context →
  retarget the user's connected sessions → push a fresh snapshot. The user's WebSocket stays open
  throughout.
- **Fragments:**
  - `alt` no entitlement: request rejected.
  - `alt` user has no connected session: persist only, nothing pushed.

---

## 2. Worth drawing as the features land

### 2.1 Large executed-order detection (Epic 1 stream + Epic 2 signal)

See §4 of the requirements foundation.

- **Actors:** Exchange WS (trade stream), Subscriber.
- **Participants:** Disruptor shard, burst aggregator, notification channel, `FeedBroadcaster`.
- **Main flow:** a print opens a burst keyed by (venue, instrument, price, aggressor side) →
  further prints within Δt extend it → total notional crosses the threshold → one notification →
  no print for Δt → the burst closes and is evicted.
- **Fragments:** `loop` prints within Δt; `alt` threshold crossed.
- **Note:** this diagram is a good place to settle the open design choice: send updates to the
  same notification ID, or emit once when the burst closes.

### 2.2 Trade execution (Epic 4)

Once designed, this becomes the most important diagram of that epic.

- **Actors:** Subscriber, Exchange REST, Exchange user-data stream, KMS.
- **Participants:** order-intent endpoint, validation (tick size, lot size, `minNotional`), key
  custody / signer, execution order state machine, audit log.
- **Main flow:** user intent → snap the price to `tickSize` with exact arithmetic → check lot size
  and `minNotional` → decrypt the key via KMS → place the order with a client order ID → fills
  arrive on the user-data stream → audit log entry.
- **Fragments:**
  - `alt` timeout: the order's status is unknown; query it before any retry, never resend
    blindly.
  - `alt` the user-data stream misses an update: REST reconciliation as a backup.
- **Also worth a short diagram:** adding an API key (re-authentication, permission check against
  the exchange, reject keys with withdrawal enabled, encrypt at rest).

---

## 3. Optional / lower value

### 3.1 Registration → email verification → login → refresh (Epic 3)

A standard flow, but expected in a formal document.

- **Actors:** Guest, SMTP server.
- **Participants:** auth controller, user service + DB, async registration-email listener,
  `EmailService`, JWT issue / validate.
- **Fragments:** `alt` expired verification token / resend; refresh-token rotation on refresh.

---

## Actors across the set

| Kind | Actors | Appears in |
|---|---|---|
| People | Guest | 3.1 |
| | Subscriber (trial or paid) | 1.3, 1.4, 1.5, 2.1, 2.2 |
| | Admin | 1.4 (entitlement bypass) |
| External systems | Binance / MEXC, each split into WS and REST | 1.1, 1.2, 2.1, 2.2 |
| | Multicard | 1.3 |
| | SMTP server | 3.1 |
| | KMS (later) | 2.2 |
| Time | Scheduler / clock | 1.1 (universe refresh), 1.3 (reconciliation), 1.4 (drain loop) |

Include the scheduler or clock as a lifeline. Without it, the system-triggered flows look like
they have no trigger.

---

## Drawing conventions

- Keep lifelines at the component level, about 5–8 per diagram (for example "Disruptor shard",
  not individual class names).
- Spend the detail on the `alt` / `loop` / `par` fragments. The failure and concurrency branches
  are what these diagrams are for.
- Draw the happy path first, then add the branches the requirements call out (gaps, duplicate
  callbacks, timeouts, rejections).
