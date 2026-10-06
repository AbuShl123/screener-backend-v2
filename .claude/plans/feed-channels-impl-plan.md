# Feed Channels and the Common WS Envelope — Implementation Plan

**Created**: 2026-10-06. Implements ticket 4 of `.claude/plans/team-plans/spike-alerts-plan.md`
(D16 envelope, D17 feed channels). Read D8, D16 and D17 there first. Only depth is delivered
after this change; spike alerts (`SpikeChannel`) and order clusters (`ClusterChannel`) plug in later
without touching the broadcaster.

**Read first**: `feed/OrderBookBroadcaster`, `feed/OrderBookFeedStore`, `ws/UserWebSocketSession`,
`ws/ScreenerWebSocketEndpoint`, `analysis/UserFeedRegistry`, `feed/OrderBookBroadcasterTest`,
`.claude/docs/for-frontend/websocket-feed-api.md`.

Steps run in order, one commit each, on one branch. Steps 1 and 3 change the wire format, so the
branch is released together with the frontend parser switch (step 5), never partially.

---

## 1. Remove `seq`

Nothing reads it (the frontend hardcodes `seq: 0`), and delivery cannot gap without a disconnect
(spike plan D8). It also forces a per-session copy of every body: `injectSeq` rebuilds each
message once per session per tick.

- `OrderBookBroadcaster`: delete `injectSeq`; batches hold the built bodies directly. A default
  session's batch becomes references to the tick's shared `String`s.
- `UserWebSocketSession`: delete `seqNumber`, `resetSeq()`, `getAndIncrementSeq()`.
- Add a comment at the enqueue sites: a batch that cannot be enqueued **evicts** the session; it
  is never skipped. Gap-free delivery depends on that.
- `OrderBookBroadcasterTest.payloadCarriesExchange`: drop the `"seq":1,` prefix from the expected
  snapshot.

## 2. Extract `FeedChannel` and `DepthChannel` (no wire change)

Pure refactor. The JSON produced is byte-identical to step 1's.

### 2.1 Interface — `feed/FeedChannel`

```java
public interface FeedChannel {
    /** Once per tick, on the broadcaster thread, before any collect call. Drains this channel's
     *  stores and builds the tick's shared bodies. Runs even when no session is connected. */
    void drain();

    /** Appends this session's live messages for the current tick. */
    void collectUpdates(UserWebSocketSession session, List<String> out);

    /** Appends this session's SNAPSHOT entries. They must reflect the state as of the last
     *  drain(): anything not yet drained arrives as a live message next tick, so it must not
     *  also be in the snapshot unless re-sending it is harmless (a state upsert). */
    void collectSnapshot(UserWebSocketSession session, List<String> out);
}
```

All three methods run on the broadcaster's single `@Scheduled` thread, so a channel may keep
per-tick state in plain fields between `drain()` and the collect calls, and reuse one
`StringBuilder`.

The snapshot contract is what keeps event channels correct. Depth gets it for free: re-sending an
upsert is harmless. `SpikeChannel` must fill its recent-N ring inside `drain()` (spike plan D8);
if producers wrote the ring directly, an alert landing between the drain and a snapshot would
reach the client twice, once as a snapshot entry and once as a live toast.

### 2.2 `FeedBroadcaster` — the loop only

Rename `OrderBookBroadcaster` → `FeedBroadcaster` (it no longer knows about order books). Callers:
`ScreenerWebSocketEndpoint`, `PipelineHealthLogger`.

Fields kept: `sessions`, the drain timing (`maxDrainNanos`, `slowDrains`, `takeDrainStats`,
`SLOW_DRAIN_NANOS`), `@PreDestroy shutdown`, `addSession` / `removeSession`. New:
`List<FeedChannel> channels`, injected by Spring; order fixed with `@Order` on each channel.

```java
private void drainOnce() {
    for (FeedChannel ch : channels) ch.drain();   // every channel, before any session
    if (sessions.isEmpty()) return;

    for (UserWebSocketSession session : sessions) {
        if (!session.isRunning()) continue;
        List<String> batch = new ArrayList<>();
        if (session.getStatus() == Status.NEED_SNAPSHOT) {
            List<String> entries = new ArrayList<>();
            for (FeedChannel ch : channels) ch.collectSnapshot(session, entries);
            batch.add(Envelope.snapshot(entries));
            if (!session.enqueueBatch(batch)) { session.disconnect(); continue; }
            session.setStatus(Status.READY);     // the snapshot covers this tick's updates
        } else {
            for (FeedChannel ch : channels) ch.collectUpdates(session, batch);
            if (!batch.isEmpty() && !session.enqueueBatch(batch)) session.disconnect();
        }
    }
}
```

Rules this loop must keep:

- **Drain before collect, for all channels.** Same ordering as today's `drainPending()` before
  `getSnapshot()`, now applied to every channel.
- **Drain with no sessions.** Today `drain()` returns early when `sessions` is empty. Move that
  check below the channel drains. Depth doesn't need it (pending holds at most one entry per
  instrument), but a bounded alert queue would overflow and lose alerts the recent-N ring should
  keep. The drain timing still wraps the whole `drainOnce()`. Drop the empty-sessions early return
  from `drain()` and keep the slow-drain log.
- **One batch per session per tick.** Gather from all channels into one list and enqueue it once.
  Enqueuing per channel would split a tick across several of the 32 queue slots and quietly shrink
  the ~3.2s eviction budget.
- **A snapshot replaces that tick's updates.** As today: a `NEED_SNAPSHOT` session gets only the
  snapshot.

### 2.3 `DepthChannel` — today's depth logic, moved

`@Component @Order(0)`. Dependencies: `OrderBookFeedStore globalFeed`, `UserFeedRegistry`.

Moved unchanged from the broadcaster:

| Broadcaster today | `DepthChannel` |
|---|---|
| Global drain + per-context drain (`ctxPending`) at the top of `drainOnce()` | `drain()`: drains global and each active context's store once. Builds the global bodies eagerly. Resets the per-context body cache. |
| Lazy `globalBodies` / `ctxBodies` | `globalBodies` field (built in `drain()`), `ctxBodies` `IdentityHashMap` filled lazily on the first session of each context, as today |
| READY branch, default session | `collectUpdates`, `ctx == null`: append every global body |
| READY branch, custom session | `collectUpdates`, `ctx != null`: personal bodies + global bodies whose `ruleKey` isn't in `ctx.rule().configuredKeys()` |
| `buildSnapshotBody(globalFeed.getSnapshot())` / `mergedSnapshot(ctx)` | `collectSnapshot`: one entry per book, from the global snapshot or `mergedSnapshot(ctx)` |
| `KeyedBody`, `buildKeyedBodies`, `buildUpdateBody`, `appendLevels` | Same, private to the channel |

Keep the existing comments: the cold-start gap for a context that appears mid-drain, the
`feedKey`-keyed merge, and the "a context backs several sessions, drain once" note.

Build global bodies eagerly in `drain()` and drop the "only if some session needs them" laziness.
The broadcaster no longer tells a channel whether sessions exist, and with no sessions the pending
map is small and the build is cheap. Keep the per-context laziness: a context with no session
this tick is still drained but its bodies never built.

Snapshot entries are built per session, as today. Snapshots are rare (connect, `SNAPSHOT_REQUEST`,
rule edit).

### 2.4 Packages

```
feed/
  FeedBroadcaster, FeedChannel, Envelope
  depth/
    DepthChannel, OrderBookFeedStore, OrderBookUpdate, ClassifiedLevel, FeedEventType
```

`SpikeChannel` will live in `alert/` and `ClusterChannel` in its own feature package, which keeps
the broadcaster honest about not knowing its channels. Import updates: `analysis/OrderBookClassifier`,
`SymbolState`, `UserClassificationContext`, `UserFeedRegistry`,
`marketdata/core/ingress/DisruptorShardManager`, and `OrderBookClassifierTest`.

### 2.5 Tests

- `OrderBookBroadcasterTest` → `feed/depth/DepthChannelTest`. Same three cases; call `drain()` then
  `collectSnapshot` / `collectUpdates` with a session directly. Assertions are unchanged after
  step 1.
- New `feed/FeedBroadcasterTest` with two fake channels that record calls and return fixed strings:
  - every channel's `drain()` runs before any `collect*`, and drain runs with no sessions;
  - a READY session receives one batch holding both channels' updates, in channel order;
  - a NEED_SNAPSHOT session receives exactly one `SNAPSHOT` containing both channels' entries, no
    updates, and moves to READY;
  - a failed `enqueueBatch` disconnects the session.

## 3. The envelope (wire change)

### 3.1 `feed/Envelope`

Static helpers writing into a caller-owned `StringBuilder` (single-thread per caller, no
allocation beyond the final `toString()`):

```java
/** {"type":"<type>","exchange":"…","market":"…","symbol":"…","data":   — caller writes data, then '}' */
static void head(StringBuilder sb, String type, Instrument inst)
/** {"type":"SNAPSHOT","data":[e1,e2,…]} */
static String snapshot(List<String> entries)
```

Field order is `type`, `exchange`, `market`, `symbol`, `data`, as in D16. `symbol` stays the
normalized `BASEQUOTE` form. Type names are plain `String` constants on each channel
(`"DEPTH"`), not a shared enum: the broadcaster must not list the types that exist.

### 3.2 Depth on the wire

`FeedEventType` stays internal: coalescing needs `ADD` vs. `UPDATE` (ADD then DROP in one tick
cancels out). Only serialization changes, in `DepthChannel`:

| Internal | Wire |
|---|---|
| `ADD`, `UPDATE` | `{"type":"DEPTH",…,"data":{"bids":[…],"asks":[…]}}` |
| `DROP` | `{"type":"DEPTH",…,"data":null}` |
| snapshot entry | identical to a live `ADD`/`UPDATE` message |

One builder serves both live bodies and snapshot entries. The classifier, the store and the
coalescing table are untouched.

### 3.3 Tests

Update `DepthChannelTest.payloadCarriesExchange` to the new shapes: a live entry starting
`{"type":"DEPTH","exchange":"BINANCE","market":"SPOT","symbol":"ETHUSDT","data":{"bids":`, a drop
ending `"data":null}`, a snapshot starting `{"type":"SNAPSHOT","data":[{"type":"DEPTH",`. Add one
assertion that a snapshot entry equals the live body for the same update.

## 4. Docs

- Rewrite `.claude/docs/for-frontend/websocket-feed-api.md`: §0 describes the breaking change
  (envelope, `data`, `DEPTH` replaces `ADD`/`UPDATE`/`DROP`, `null` data removes, no `seq`); §3/§4
  become the envelope, the `DEPTH` type and the rule "ignore unknown `type`s"; drop §5 (`seq`);
  update the TypeScript shapes and the quick reference. Keep the level fields, identity, client →
  server and lifecycle sections.
- `CLAUDE.md` module map: `feed/` now names `FeedBroadcaster` and the channel split.

## 5. Frontend (same release)

`screener-frontend-new`: `src/lib/ws/feedClient.ts` (parser) and
`src/features/orderbook/types.ts` (message types) switch to the envelope: dispatch on `type`,
`DEPTH` with `data` → upsert, `DEPTH` with `data: null` → remove, unknown types ignored,
`SNAPSHOT.data` entries go through the same per-type handler. `orderbookStore.ts` keeps its state
logic. Done by FE within this story.

---

## Out of scope

- Per-session state for channels other than depth. `DepthChannel` reads
  `session.getContext()`; spikes and clusters need no per-session state. Add a per-channel
  session slot only when a channel needs one.
- Caching the built JSON per `OrderBookUpdate` so snapshot entries reuse live bodies.
- Several messages per WebSocket frame, client-side channel subscriptions, Jackson serialization of
  depth bodies.

## Verification

- `./mvnw test`: green after every step. After step 2 the depth output is byte-identical to
  step 1's.
- Run locally against the frontend branch: snapshot on connect, live updates, a book dropping
  out, a rule edit pushing a fresh snapshot, `SNAPSHOT_REQUEST`.
- `PipelineHealthLogger` drain stats: max drain time no worse than before (it should drop, since
  `injectSeq` is gone).
