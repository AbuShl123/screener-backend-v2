# Bybit Exchange — High-Level Plan

**Created**: 2026-10-16. Status: **proposal / pre-implementation**.
Adds `BYBIT_SPOT` and `BYBIT_FUTURES` (linear USDT perpetuals) as a new adapter. It covers phases,
the main design decision, open questions and a short ticket split. It is not an implementation plan.
Each ticket owner writes their own.

**Read first**
- `.claude/docs/multi-exchange-progress.md`: §5 SPI, §7 sync contract, §11 "Adding an exchange".
- `external-docs/bybit/bybit-api-contracts.md`: the measured Bybit contracts. Section numbers below
  (`§4.5`, `§6.3` …) refer to that file.
- `marketdata/adapter/mexc/`: the closest template (one `@Configuration`, one shared source, two
  venues).

---

## 1. Why Bybit is mostly "just an adapter", and where it isn't

Most of Bybit fits seams that already exist:

| Seam | Bybit | Fit |
|---|---|---|
| Discovery | `GET /v5/market/instruments-info`, `category=spot` / `linear` | `InstrumentSource`, as-is |
| Transport | JSON text frames, `orderbook.{depth}.{symbol}` topics, app-level `{"op":"ping"}` | `StreamProtocol` + `TextPing`, as-is |
| Sync | `u` strictly `+1` per message, simpler than Binance/MEXC | `DepthSyncStrategy`, as-is |
| Snapshot | **arrives in-stream** as the first message of every subscription (§5.3) | fits: `EventType` is provenance, so the strategy can treat a `WS_MSG` as a snapshot |
| Recovery after a gap | Bybit's documented path is **unsubscribe + subscribe** on the same connection (§6.3) | **no seam for this yet** (progress doc §11, "Not yet exercised") |

Spot and linear differ only in details: spot allows 10 args per subscribe frame, field order and
number padding differ, and the ack quirks differ. That means **one protocol class and one strategy
class, two instances**, as with Binance.

Cold start is cheap. Every book syncs from its in-stream snapshot, with **zero REST calls**. So is a
reconnect: core already resubscribes everything, and every topic then gets a fresh snapshot. Only
**mid-stream gap recovery** needs a decision.

---

## 2. The one real design decision: gap recovery

### Option B (preferred): REST snapshot through the existing `SnapshotRequestQueue`, with no core change

`GET /v5/market/orderbook?limit=1000` returns `u` and `seq`, documented as "same meaning as in the
stream" (§3.4). If that REST `u` is on the same counter as the `orderbook.1000` stream topic, a gap
recovers exactly like Binance spot: clear, request, buffer deltas, apply the snapshot, then drain the
buffer with `u == lastU + 1` (`u <= lastU` → `IGNORE`). It reuses §7 of the progress doc unchanged.
The budget is generous: 600 req / 5 s per IP, no weight system. And gaps were measured at zero over
70 s, so recovery is rare.

One small change to §7's dispatch: **a `WS_MSG` with `type:"snapshot"` (or `u == 1`) is an
authoritative full replacement in any state.** It loads the book and marks it `SYNCED`. A REST
outcome that arrives afterwards is dropped by the existing state guard. A `PENDING` book that gets a
delta requests recovery as usual. In practice that only happens after a gap, because the first frame
of every subscription is a snapshot.

### Option A (fallback): resubscribe-to-recover, which needs a core seam

This is used only if the spike (ticket 0) shows the REST `u` does **not** line up with the stream.
Core would need:
- a reverse `id → (connection, topic)` lookup. This is the same missing piece that blocks dynamic
  subscribe (progress doc §12), so it is not wasted work.
- a `RecoverySink` implementation in core that queues the id from the shard thread without locks
  or allocation. The connection's thread then sends `unsubscribe` + `subscribe`, both built by new
  default methods on `StreamProtocol`.
- a timeout for a book stuck in `RECOVERING`. A snapshot that never arrives breaks invariant 4
  ("exactly one outcome per request"), and nothing else would fire.

This is the "Bybit-native" way and it uses no REST budget. But it changes core and the SPI, and it
needs its own design review. **Don't build it unless B is ruled out.**

---

## 3. Phases

| Phase | What | Gate to next |
|---|---|---|
| **0. Spike** | Answer the §5 questions with a throwaway client and record them in `external-docs/bybit/`. Pick recovery option B or A. | Recovery path decided, depth decided, prod host reachability confirmed |
| **1. Skeleton** | `Exchange.BYBIT`, `Venue.BYBIT_SPOT` / `BYBIT_FUTURES`, an empty `BybitAdapterConfig`, a YAML block **disabled by default** (`BYBIT_ENABLED=false`). | Merged first, so phases 2a–2c don't conflict |
| **2a. Discovery** | REST client + `BybitInstrumentSource` | Unit tests on captured payloads |
| **2b. Transport** | `BybitStreamProtocol` | Unit tests on captured frames |
| **2c. Sync + recovery** | `BybitSyncStrategy` + context (+ `BybitSnapshotFetcher` under option B) | Replay test: captured snapshot + N deltas == a later snapshot |
| **3. Integration & rollout** | Wire the beans, run locally, soak, check feed noise, update docs, roll out to prod | Books reach and hold `SYNCED`, health log is clean |

2a, 2b and 2c run **in parallel** after phase 1. Each codes against an SPI interface and touches
only its own files under `adapter/bybit/`.

---

## 4. Expected footprint

**New:** `marketdata/adapter/bybit/` (config, REST client, source, protocol, strategy, context,
optional fetcher, `dto/`), its tests, the `screener.exchanges.bybit` YAML block, and test fixtures
under `src/test/resources/bybit/`.

**Touched outside the adapter:**
- `Exchange`, `Venue`: one constant each. This is expected per §11 of the progress doc.
- `application.yml`: the new block.
- `.claude/docs/multi-exchange-progress.md`: a Bybit section, with the §11 "not yet exercised" note
  updated.
- Frontend: the payload already carries `exchange`. Check that a new value `BYBIT` renders (label,
  filter) and doesn't break an exhaustive switch.
- **Option A only:** `core/stream/`, `spi/StreamProtocol`, a core `RecoverySink`.
- **Maybe:** an `OrderBook` trim helper, if spike question 2 shows levels that leave the top-N
  window are not deleted.

---

## 5. Open questions (spike ticket)

**Technical: these decide the design**
1. **Is the REST `u` on the same counter as the stream topic's `u`?** Subscribe to
   `orderbook.1000.X`, fetch REST `limit=1000` for X, then compare. Check both spot and linear. This
   decides option B vs A.
2. **When a level drops out of the top-N window, does a delta delete it** (size `0`)? If it doesn't,
   the local book collects phantom far levels. Replay a few minutes of deltas from one snapshot and
   diff the result against a fresh snapshot.
3. **Depth: 1000 @ 200 ms or 200 @ 100 ms?** Depth is a hard ceiling (§5.1). On BTC with a 0.1
   tick, 1000 dense levels reach only ~0.1–0.2% from mid, far short of the 10% price filter. Measure
   how far from mid level 1000 actually sits on majors vs. alts. 1000 is the likely answer, but the
   consequence goes to product (below).
4. **Spot subscribe burst.** Core sends every subscribe frame of a connection back to back, with no
   pacing. Is that fine at 10 args/frame? If not, use a low `max-streams-per-connection` (e.g. 50 →
   5 frames per connection) so core doesn't have to change.
5. **Reachability from the production host** (§9 of the contracts doc): US and Mainland China are
   blocked. Run one REST call and one WS subscribe from the server.
6. **Multiplier symbols.** Does `1000BONKUSDT` report `baseCoin = 1000BONK`? Binance futures uses the
   same names, so if it does, user rules match across exchanges. Confirm the linear quantity is in
   base units (multiplier 1).

**Product: answers needed before rollout**
- **Shallow visibility on majors.** Bybit books are capped at 1000 levels, so a BTC wall 2% away is
  never visible on Bybit, though it is on Binance. Is that acceptable for v1?
- **Eligibility.** Proposed: futures = `LinearPerpetual` ∧ quote `USDT` ∧ `Trading` (no dated
  `LinearFutures`, no USDC). Spot = quote `USDT` ∧ `Trading` ∧ base ∈ eligible futures bases (the
  Binance/MEXC rule). Should Special Treatment (`stTag = 1`) or Innovation Zone spot pairs be
  excluded?
- **Noise.** MEXC needed per-venue tuning (`max-visible-distance`, the MM filter work). Bybit may
  too. Decide after phase 3 measures it, not before.

---

## 6. Jira tickets

Five tickets. The skeleton is small enough to be the first commit of ticket 1, not a ticket of its
own.

### BYBIT-0: Spike: verify the open contracts *(1–2 days, one person)*
Answer §5 technical questions 1–6 with a throwaway client (not merged). Record the answers as
**[measured]** additions to `external-docs/bybit/bybit-api-contracts.md`. Capture fixtures:
snapshots, deltas and acks/pongs/errors for both categories. The other tickets' tests use them.
**Output:** recovery option chosen (B or A), depth chosen, prod reachability confirmed.
**Blocks:** BYBIT-3. It doesn't block BYBIT-1/2, which can start in parallel.

### BYBIT-1: Skeleton + discovery
- First commit, merged fast: `Exchange.BYBIT`, both `Venue` constants, an empty
  `BybitAdapterConfig`, and a YAML block with both venues disabled.
- `BybitRestClient`. It branches on `retCode`, never on HTTP status (§3.1). Linear paginates with
  `limit=1000` + `cursor`; spot has no `nextPageCursor` field at all (§3.2).
- `BybitInstrumentSource`, one for both venues, with the §5 eligibility rules. Spot depends on the
  futures list, as on Binance/MEXC.
- Tests on captured `instruments-info` payloads.

### BYBIT-2: Stream protocol
- `BybitStreamProtocol`, one class with an instance per venue. It builds
  `{"op":"subscribe","args":[...]}` and routes on the `topic` suffix.
- It ignores acks and pongs. The pong arrives as `op:"ping"` + `ret_msg:"pong"` (§4.6). A
  `success:false` is logged at WARN.
- Heartbeat `TextPing(20s, {"op":"ping"})`.
- The constructor rejects configs that break server limits: spot `subscribe-chunk-size > 10`, and
  any chunk whose serialized args could exceed 21 000 chars (a `1009` close, §4.4).
- The YAML transport keys for both venues.
- Tests on captured frames, including both field orders.

### BYBIT-3: Sync strategy + recovery *(after BYBIT-0)*
- `BybitSyncStrategy` + `BybitSyncContext`, following §7 of the progress doc with the §2 change.
  `type:"snapshot"` or `u == 1` → full replace → `SYNCED`. A delta with `u != lastU + 1` →
  `recover()`. `seq` is never gap-checked.
- Hot-path rules apply: Jackson streaming, `JavaDoubleParser` on string prices, no allocation. Size
  `0` deletes the level.
- Under option B: `BybitSnapshotFetcher` with fixed pacing well under 600 req / 5 s, plus a
  `snapshot-queue` block. Under option A: hand off the core seam design for review before coding.
- Replay test: a captured snapshot plus deltas must equal a later snapshot (the MEXC spot MINTUSDT
  test is the model). Gap, restart (`u == 1`) and re-snapshot cases.

### BYBIT-4: Integration, soak & rollout *(after 1–3)*
- Bind every bean in `BybitAdapterConfig`, plus a config test asserting both venues are bound.
- Local run: books reach and hold `SYNCED`. Watch resyncs, frames/s and ring headroom in the health
  log over a 30–60 min soak, on both venues.
- Feed check: messages/s and books shown vs. Binance (the MEXC noise method). Raise a follow-up only
  if it's noisy.
- Frontend: confirm `BYBIT` renders.
- Docs: a Bybit section in `multi-exchange-progress.md`, and the §11 / decisions list updated.
- Prod: enable behind `BYBIT_ENABLED` / `BYBIT_<MARKET>_ENABLED`, one venue first.

**Suggested split for three people:** A takes BYBIT-0 → BYBIT-3 (the spike feeds straight into the
sync work). B takes BYBIT-1. C takes BYBIT-2. Whoever finishes first takes BYBIT-4.
