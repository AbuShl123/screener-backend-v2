# Trade Delivery — Implementation Plan

Phase 3 of `.claude/plans/trade-alerts-plan.md`: deliver the large-trade alerts that Phase 2 detects
to every connected `/ws` session, live and as a replay on connect. The first end-to-end release of
trade alerts.

**Status**: built; unit and broadcaster tests pass. The §6 local-run check is still open.

**Read first**: `.claude/docs/for-frontend/websocket-feed-api.md` §3.2, §3.7, §3.8 (the wire
contract, agreed with the frontend and already published: this plan implements it, it does not
change it), `.claude/plans/trade-detection-plan.md` §2 and §6 (what produces the alerts),
`.claude/docs/market-data-pipeline-architecture.md` §8.2 (feed stores and the broadcaster).

---

## 1. Decisions

| # | Decision |
|---|---|
| T1 | Alerts travel on the existing `/ws` socket as a new envelope `type`, **`TRADE`**. Phase 4 bursts get their own type, `BURST`, because their `data` differs. |
| T2 | **Always on.** Every session gets every alert; there is no client subscription message. Clients ignore unknown types, and the traffic is a few hundred messages an hour. |
| T3 | **One global stream.** No per-user filtering or thresholds on the server (Phase 6). The UI filters. Custom classification rules do not affect trades. |
| T4 | **Replay**: every `SNAPSHOT` carries the most recent **100** alerts across all venues (`replay-size`). No per-symbol history endpoint. In memory: empty after a restart. |
| T5 | **A snapshot does not clear trades.** Alerts are history, not state: the client merges snapshot `TRADE` entries by `id` and does not notify for them. Stable ids (`TradeAlert.id()`) make this safe across reconnects and restarts. |
| T6 | `data` is a named object (not a tuple): `id, side, time, notional, qty, avgPrice, firstPrice, lastPrice, levels, threshold` (contract §3.8). `levels` is `TradeAlert.fills`: the continuation rule makes every fill of a sweep a distinct price, so fills are exactly the price levels taken. `threshold` lets the UI show relative size across symbols whose thresholds differ 30K–5M, and becomes the user's own value in Phase 6 without a shape change. `coalescedOnly`, `detectedAt` and the trade ids stay admin-only. |

---

## 2. Design

### 2.1 Flow

```
shard thread                          broadcaster thread (@Scheduled 100 ms)
TradeDetector.close                   TradeChannel.drain()
  └─ TradeAlertStore.add(alert)  ──►    └─ store.drainSince(seen, scratch)   new alerts, oldest first
       ring of recent-capacity            ├─ build one TRADE body per alert  (shared Strings)
       + running count `added`            ├─ tickBodies  ← the bodies        (live, this tick)
                                          └─ replay      ← append, trim to replay-size
                                      TradeChannel.collectUpdates(session) → out += tickBodies
                                      TradeChannel.collectSnapshot(session) → out += replay
```

Core stays unaware of the feed: the store is the hand-off, as `OrderBookFeedStore` is for depth.

### 2.2 Hand-off: `TradeAlertStore.drainSince`

The store already holds the last `recent-capacity` (500) alerts, newest first, under its own lock.
It gains a running count of alerts ever added (`long added`, guarded by the same lock) and:

```java
/** Appends, oldest first, every retained alert added after the first {@code seen}; returns the new count. */
public long drainSince(long seen, List<TradeAlert> out)
```

- Under the lock: `n = added − seen`; copy the newest `min(n, size)` alerts into `out` in oldest-first
  order; return `added`.
- **Overflow**: if more than `recent-capacity` alerts arrived since the last drain, the oldest are
  gone. The channel sees it as `(returned − seen) > out.size()` and logs one WARN with the count. At
  ~1,000 alerts/h peak against a 100 ms tick this needs a broadcaster stall of minutes, so it is a
  diagnostic, not a design case.
- Non-destructive: `/api/monitoring/trade-alerts` keeps reading the same ring.

A sequence over the existing ring needs no second queue and no core → feed callback.

### 2.3 `TradeChannel` (`feed/trade/`, new)

`@Component @Order(1)` implementing `FeedChannel`, after `DepthChannel` (`@Order(0)`). All methods run
on the broadcaster thread, so its state is plain fields.

- **State**: `long seen`; a reused `List<TradeAlert> scratch`; `List<String> tickBodies` (cleared each
  drain); `ArrayDeque<String> replay`, oldest first, at most `replay-size`; a reused `StringBuilder`.
- **`drain()`**: `scratch.clear()`; `seen = store.drainSince(seen, scratch)` (overflow check first);
  `tickBodies.clear()`; for each alert build the body, add it to `tickBodies`, append it to `replay`,
  drop the oldest past `replay-size`. A tick with no alerts allocates nothing. The broadcaster drains
  with no sessions too, so the replay fills from startup.
- **`collectUpdates(session, out)`**: `out.addAll(tickBodies)`, whatever the session's context.
- **`collectSnapshot(session, out)`**: `out.addAll(replay)`, oldest first.
- **Exactly once**: the replay holds only drained alerts, so an alert added after a tick's drain is
  neither in that tick's snapshot nor its updates; it arrives next tick. A `NEED_SNAPSHOT` session
  gets this tick's alerts inside the snapshot and no update batch (the broadcaster's existing rule).
  This is the `FeedChannel.collectSnapshot` contract with no relaxation needed.

**Body** (`Envelope.head(sb, "TRADE", alert.instrument())`, then `data`), fields in contract order:

| Wire | From | Notes |
|---|---|---|
| `id` | `alert.id()` | Venue name, native symbol, digits: no JSON escaping needed (`Envelope.head` makes the same assumption for symbols). |
| `side` | `aggressorBuy ? "BUY" : "SELL"` | |
| `time` | `tradeTimeMs` | |
| `notional` | `Math.round(notional * 100) / 100.0` | 2 dp. |
| `qty` | `qty` | raw. |
| `avgPrice` | `vwap` | raw; the client formats it (contract §3.8.6). |
| `firstPrice`, `lastPrice` | as is | |
| `levels` | `fills` | |
| `threshold` | `minNotional` (new field, 2.4) | |

Doubles go through `StringBuilder.append(double)`, as in `DepthChannel`; exponent output (`2.5E7`) is
valid JSON and the contract says so. NaN cannot occur (`qty > 0` for any alert).

Alerts are rare, so building a body allocates (the `id` string, the body) on the broadcaster thread;
that is the rare path, like alert creation on the shard.

### 2.4 `TradeAlert` carries its threshold

`TradeDetector.close` already computes `min = thresholds.minNotional(instrument)`. `TradeAlert` gains
`double minNotional` (after `notional`), filled with it. The admin `TradeAlertView` shows it as
`threshold` too, which helps tuning. In Phase 6 this becomes the threshold the alert was judged
against for its audience.

### 2.5 Config

```yaml
screener:
  trade-alerts:
    replay-size: 100                     # alerts replayed in every /ws SNAPSHOT
```

`TradeAlertProperties` gains `int replaySize`, validated `> 0`. Independent of `recent-capacity`: the
channel keeps its own replay of built bodies, so a snapshot reuses Strings instead of re-serializing.

### 2.6 What does not change

`FeedBroadcaster`, `UserWebSocketSession`, `Envelope`, `DepthChannel`, the detector's rules,
entitlement (the socket is already gated at connect). A `READY` session with trade bodies and no depth
changes gets a non-empty batch and is enqueued as usual; eviction is unaffected at these rates.

---

## 3. Steps

1. `TradeAlert.minNotional` + `TradeDetector.close` passes it; `TradeAlertView.threshold` in
   `MonitoringController`.
2. `TradeAlertStore`: `added` count and `drainSince` (2.2). Update its class Javadoc (no longer only a
   debug surface).
3. `TradeAlertProperties.replaySize` + `application.yml` (2.5).
4. `feed/trade/TradeChannel` (2.3).
5. `FeedChannel` Javadoc: "depth today; spikes and clusters later" → depth and trades today.
6. Docs (§5).

---

## 4. Tests

| Test | Covers |
|---|---|
| `TradeAlertStoreTest` | `drainSince`: new alerts oldest first and the new count; nothing new → empty, same count; more than capacity since `seen` → the retained alerts and a count showing the loss; `recent()` unchanged by draining. |
| `TradeChannelTest` (new, like `DepthChannelTest`) | Exact JSON of one body (envelope with normalized symbol, field order, `side`, notional rounded to 2 dp, `levels`, `threshold`); a default and a custom-rules session both get every body; no alerts → no output; alerts added while no session exists still reach the replay; snapshot is oldest first and bounded at `replay-size`; an alert added after `drain()` is not in that tick's snapshot and is in the next tick's updates; an overflow logs and continues. |
| `FeedBroadcasterTest` | With the real `TradeChannel` and a store: an alert reaches a `READY` session once as a live `TRADE`; a session connecting that tick gets it only inside the `SNAPSHOT`; a later `SNAPSHOT_REQUEST` replays it again. |
| `TradeDetectorTest` | The alert carries the instrument's threshold (default and override). |
| `TradeAlertPropertiesTest` | `replay-size` binds from the shipped YAML; `0` fails. |
| `MonitoringControllerTradeAlertsTest` | `threshold` in the view. |

`TradeReplayTest` is unaffected (it compares through the detector, not the record constructor).

---

## 5. Docs (as the code lands)

- `websocket-feed-api.md`: drop the two "contract agreed, backend in progress" notes (§1, §3.8).
- `CLAUDE.md`: `feed/` row gains `trade/`: `TradeChannel` (global trade alerts, last-N replay in
  `SNAPSHOT`); `monitoring/` row mentions `threshold` only if useful.
- `market-data-pipeline-architecture.md`: §8.2 "`DepthChannel` is the one channel today" → two
  channels; the diagram's store line gains `TradeAlertStore`; §12 trade-alerts row → delivered.
- `trade-alerts-plan.md`: Phase 3 row → done.

---

## 6. Verify (local run)

1. Connect a client to `ws://localhost:8080/ws?token=<admin access token>` (websocat, or a few lines of
   JS) and log frames.
2. After a few minutes of running: the first `SNAPSHOT` holds `TRADE` entries (up to 100, oldest
   first), and live `TRADE` messages follow. Their ids match `/api/monitoring/trade-alerts`.
3. Every alert: `notional ≥ threshold`; `levels == 1` ⇔ `firstPrice == lastPrice`; for `BUY`
   `lastPrice ≥ firstPrice`, for `SELL` `≤`. Spot check a multi-level one against the Binance trade
   list.
4. Reconnect: the new snapshot's `TRADE` ids overlap the ones already seen (merge is a no-op).
   `SNAPSHOT_REQUEST` does the same.
5. Health log: broadcaster drain time and slow-drain count unchanged.

---

## 7. Out of scope

- Per-user thresholds and filtering (Phase 6). Note for it: a user threshold below the global one
  means detection must judge against the lowest threshold any connected user set, and delivery filters
  per session; the wire shape stays the same, with `threshold` the user's value.
- Bursts (Phase 4), as their own `BURST` type and probably a second channel or a second body kind here.
- Persisting alerts across restarts; a per-symbol history endpoint; replay filtered per user.
- Other venues (Phase 5): their alerts flow through unchanged once their decoders exist.
- A client → server subscription protocol.
