# Bybit Exchange — High-Level Plan

**Created**: 2026-10-16. **Updated**: 2026-11-15. Status: **phases 0–1, 2a and 2b done; 2c not started**.
Adds `BYBIT_SPOT` and `BYBIT_FUTURES` (linear USDT perpetuals) as a new adapter, **with no change to
core**. It covers phases, the recovery design, open questions and a short ticket split. It is not an
implementation plan. Each ticket owner writes their own.

**Read first**
- `.claude/docs/multi-exchange-progress.md`: §5 SPI, §7 sync contract, §11 "Adding an exchange".
- `external-docs/bybit/bybit-api-contracts.md`: the measured Bybit contracts, including the spike
  results (§7.1). Section numbers below (`§4.5`, `§6.3` …) refer to that file.
- `marketdata/adapter/mexc/`: the closest template (one `@Configuration`, one shared source, two
  venues, fixed-pacing snapshot fetchers).

---

## 1. Why Bybit is "just an adapter"

Every part of Bybit fits a seam that already exists:

| Seam | Bybit | Fit |
|---|---|---|
| Discovery | `GET /v5/market/instruments-info`, `category=spot` / `linear` | `InstrumentSource`, as-is |
| Transport | JSON text frames, `orderbook.{depth}.{symbol}` topics, app-level `{"op":"ping"}` | `StreamProtocol` + `TextPing`, as-is |
| Sync | `u` strictly `+1` per message, simpler than Binance/MEXC | `DepthSyncStrategy`, as-is |
| Snapshot | **arrives in-stream** as the first message of every subscription (§5.3) | fits: `EventType` is provenance, so the strategy can treat a `WS_MSG` as a snapshot |
| Recovery after a mid-stream gap | rare (§2); REST snapshot as a safety net | `SnapshotRequestQueue` + a `SnapshotFetcher`, as-is |

Spot and linear differ only in details: spot allows 10 args per subscribe frame, field order and
number padding differ, and the ack quirks differ. That means **one protocol class and one strategy
class, two instances**, as with Binance.

Cold start costs no REST calls: every book syncs from its in-stream snapshot. A reconnect costs none
either: core already resubscribes everything, and every topic then gets a fresh snapshot.

---

## 2. Recovery: REST snapshot as a safety net, no core change

### Why gaps should be rare

The stream runs over TCP, and core's ring publish blocks instead of dropping, so nothing is lost
between Bybit's socket and the book. A disconnect is handled by the reconnect + resubscribe that
core already does, and the new snapshot fully replaces the book. Measured gaps: zero over 70 s
(§5.4).

A gap without a disconnect is still possible: Bybit skipping messages on its side (restart,
failover), or a frame we fail to parse, which leaves the book just as unreliable. Without a
recovery path such a book stays out of sync until its connection happens to drop, possibly for
hours.

### The mechanism

Bybit's native recovery is unsubscribe + subscribe on the same connection (§6.3). It needs a core
seam that doesn't exist (an `id → (connection, topic)` lookup, sending from the shard side, a
`RECOVERING` timeout), so it is **out of scope**.

Instead, a gap recovers through the existing `SnapshotRequestQueue`, exactly as on Binance and MEXC
(progress doc §7): clear, request `GET /v5/market/orderbook?limit=1000`, buffer deltas, apply the
snapshot, drain the buffer.

**Stitch on `u` (measured, §7.1).** REST `u` is on the `orderbook.1000` topic's counter, so the drain
rule is the usual one: `u <= lastU` → `IGNORE`, `u == lastU + 1` → `OK`, otherwise `DE_SYNCED`.
`seq` is not used anywhere. This also fixes the subscription depth at 1000: REST `u` never matched
the 50/200 topics.

One change to §7's dispatch: **a `WS_MSG` with `type:"snapshot"` (or `u == 1`) is a full
replacement in any state.** It resets the context, loads the book and marks it `SYNCED`. A REST
outcome that arrives afterwards is dropped by the existing state guard. A `PENDING` book that gets a
delta requests recovery as usual. In practice that only happens after a gap, because the first
frame of every subscription is a snapshot.

### The fetcher

- **Fixed pacing**, as on MEXC, well under the limit (e.g. 10 req/s per venue). The budget is
  600 req / 5 s per IP, with no weight system and no budget headers (§3.6).
- **The limit is per IP**, shared by both venues and every Bybit host, and breaking it costs a
  **10-minute ban** (HTTP 403). Both fetchers are built in `BybitAdapterConfig`, so they share one
  cooldown object there. This stays inside the adapter.
- **Sort responses by `retCode`, not HTTP status**: `10006` / `10018` (rate limit) come back as HTTP
  200 (§3.1). Same idea as MEXC's `classifyDepth`.

---

## 3. Phases

| Phase | What | Gate to next |
|---|---|---|
| **0. Spike** ✅ | Answer the §5 questions with a throwaway client and record them in `external-docs/bybit/`. Capture fixtures. | Done 2026-11-15: stitch on `u`, depth 1000 |
| **1. Skeleton** ✅ | `Exchange.BYBIT`, `Venue.BYBIT_SPOT` / `BYBIT_FUTURES`, an empty `BybitAdapterConfig`, a YAML block **disabled by default** (`BYBIT_ENABLED=false`). | Merged first, so phases 2a–2c don't conflict |
| **2a. Discovery** ✅ | REST client + `BybitInstrumentSource` | Unit tests on captured payloads |
| **2b. Transport** ✅ | `BybitStreamProtocol` | Unit tests on captured frames |
| **2c. Sync + recovery** | `BybitSyncStrategy` + context + `BybitSnapshotFetcher` | Replay test: captured snapshot + N deltas == a later snapshot |
| **3. Integration & rollout** | Wire the beans, run locally, soak, check feed noise, update docs, roll out to prod | Books reach and hold `SYNCED`, health log is clean |

2a, 2b and 2c run **in parallel** after phase 1. Each codes against an SPI interface and touches
only its own files under `adapter/bybit/`.

---

## 4. Expected footprint

**New:** `marketdata/adapter/bybit/` (config, REST client, source, protocol, strategy, context,
fetcher, `dto/`), its tests, the `screener.exchanges.bybit` YAML block, and test fixtures under
`src/test/resources/bybit/`.

**Touched outside the adapter:**
- `Exchange`, `Venue`: one constant each. This is expected per §11 of the progress doc.
- `application.yml`: the new block.
- `.claude/docs/multi-exchange-progress.md`: a Bybit section, and the §11 "not yet exercised" note
  updated (in-stream snapshot is now exercised; resubscribe-to-recover still isn't).
- Frontend: add `BYBIT` to `src/features/orderbook/exchanges.ts` (one logo + one line). An exchange
  missing there is dropped silently, so Bybit would be invisible rather than broken.

**Core: none.** Levels leaving the top-N window are deleted by the server (§7.1), so no trim helper.

---

## 5. Open questions

**Answered**
- **REST reachability from the production host** (Japan): `instruments-info` and
  `orderbook?limit=1000` (spot, so spot `limit=1000` works too) return `retCode 0`. Checked
  2026-11-15.
- **WS reachability from the production host**: `stream.bybit.com` is reachable from the Japan
  host. Checked 2026-11-15.

**Technical (answered by the spike, 2026-11-15, details in contracts §7.1)**
1. **REST/WS stitching:** REST `u` is on the `orderbook.1000` counter, on spot and linear, at every
   REST `limit`. Stitch on `u` (§2).
2. **Window exits:** the server deletes levels leaving the top-N window (size `0`). A 5-minute replay
   never exceeded `depth` levels and equalled a fresh snapshot. No trim needed.
3. **Depth: 1000.** Forced by question 1, and the deepest anyway. Level 1000 sits ~0.5% from mid on
   BTC linear, ~1% on ETH linear, ~1.3% / 2% on BTC / ETH spot; from about rank 3–5 by turnover
   onward it reaches past the 10% price filter or covers the whole book.
4. **Spot subscribe burst:** 30 frames × 10 args sent back to back on one connection all acked. Core
   needs no pacing; `max-streams-per-connection` can be sized freely (300 tested).
5. **Multiplier symbols:** `baseCoin` keeps the prefix (`1000000BABYDOGE`), as on Binance futures, so
   rule keys match across exchanges.

**Product (answered 2026-11-15)**
- **Shallow visibility on majors: accepted for v1.** Bybit books are capped at 1000 levels, so a BTC
  wall 2% away is never visible on Bybit, though it is on Binance. Majors stay listed.
- **Eligibility.** Futures = `LinearPerpetual` ∧ quote `USDT` ∧ `Trading` (no dated
  `LinearFutures`, no USDC) ∧ **not TradFi** (`symbolType` ∉ {`stock`, `ETF`, `commodity`,
  `forex`}, as on MEXC: 305 of 821 USDT perps on 2026-11-15). Spot = quote `USDT` ∧ `Trading` ∧
  symbol ∈ eligible futures symbols (the Binance rule, so TradFi is inherited) ∧ **not Special
  Treatment** (`stTag = 1` excluded). Innovation Zone pairs are kept, and so are ST-tagged perps
  (linear `tags: ["ST"]`). Live result on 2026-11-15: 516 futures, 287 spot.

**Product (deferred to phase 3)**
- **Noise.** MEXC needed per-venue tuning (`max-visible-distance`, the MM filter work). Bybit may
  too. Decide after phase 3 measures it, not before. Two sources specific to a depth-capped venue
  to watch for:
  - **Window-edge flicker.** Levels near level 1000 leave and re-enter the window as mid moves.
    Each time they are deleted and re-inserted, so their age resets and they drop out of and come
    back into the feed. `max-visible-distance` can't fix it, since the edge sits at a different
    distance per symbol.
  - **Ages reset on a full replace.** Every in-stream snapshot (each reconnect, `u == 1`) replaces
    a `SYNCED` book, which resets every level's `firstSeenMillis`. If that shows up in the feed, the
    fix is an `OrderBook` replace that keeps the age of levels the snapshot re-sends, a core touch
    to decide on then.

---

## 6. Jira tickets

Five tickets. The skeleton is small enough to be the first commit of ticket 1, not a ticket of its
own.

### BYBIT-0: Spike: verify the open contracts ✅ *(done 2026-11-15)*
Answered §5 technical questions 1–5 with a throwaway client (not merged). Record the answers as
**[measured]** additions to `external-docs/bybit/bybit-api-contracts.md`. Capture fixtures:
snapshots, deltas and acks/pongs/errors for both categories, plus REST snapshots taken alongside the
stream for the stitching test. The other tickets' tests use them.
**Output:** stitch on `u`, depth 1000. Fixtures in `src/test/resources/bybit/`: one `orderbook.1000`
capture per category (snapshot + deltas through a REST snapshot's `u` + 20 more), the matching REST
snapshot, trimmed `instruments-info` payloads, and acks / pongs / a rejected subscribe.
**Blocks:** BYBIT-3. It doesn't block BYBIT-1/2, which can start in parallel.

### BYBIT-1: Skeleton + discovery
- First commit, merged fast: `Exchange.BYBIT`, both `Venue` constants, an empty
  `BybitAdapterConfig`, and a YAML block with both venues disabled.
- `BybitRestClient`. It branches on `retCode`, never on HTTP status (§3.1). Linear paginates with
  `limit=1000` + `cursor`; spot has no `nextPageCursor` field at all (§3.2). It also serves the
  depth endpoint for BYBIT-3's fetcher.
- `BybitInstrumentSource`, one for both venues, with the §5 eligibility rules. Spot depends on the
  futures list, as on Binance/MEXC.
- Tests on captured `instruments-info` payloads.

### BYBIT-2: Stream protocol
- `BybitStreamProtocol`, one class with an instance per venue. It builds
  `{"op":"subscribe","args":[...]}` and routes on the `topic` suffix.
- It ignores acks and pongs. The pong arrives as `op:"ping"` + `ret_msg:"pong"` (§4.6). A
  `success:false` is logged at WARN. A subscribe frame is all-or-nothing (§4.3), so a rejected one
  leaves every book in it `PENDING` with no traffic. That WARN and the health log's synced/tracked
  count are the only signs.
- Heartbeat `TextPing(20s, {"op":"ping"})`.
- The constructor rejects configs that break server limits: spot `subscribe-chunk-size > 10`, and
  a chunk size whose worst-case serialized args (chunk size × longest topic) could exceed 21 000
  chars. This check is required: an oversized frame gets a `1009` close (§4.4), and core's
  reconnect would resend the same frame forever.
- The YAML transport keys for both venues.
- Tests on captured frames, including both field orders.

### BYBIT-3: Sync strategy + recovery *(after BYBIT-0)*
- `BybitSyncStrategy` + `BybitSyncContext`, following §7 of the progress doc with the §2 change.
  `type:"snapshot"` or `u == 1` → full replace → `SYNCED`. A delta with `u != lastU + 1` →
  `recover()`. `seq` is ignored.
- Hot-path rules apply: Jackson streaming, `JavaDoubleParser` on string prices, no allocation. Size
  `0` deletes the level. The WS frame (`data`) and the REST body (`result`) hold the same inner
  shape, so one walk serves both, as on MEXC futures.
- `BybitSnapshotFetcher` (§2 "The fetcher") and a `bybit.snapshot-queue` block.
- Replay test: a captured snapshot plus deltas must equal a later snapshot (the MEXC spot MINTUSDT
  test is the model). Gap, restart (`u == 1`), re-snapshot and REST-stitch cases.

### BYBIT-4: Integration, soak & rollout *(after 1–3)*
- Bind every bean in `BybitAdapterConfig`, plus a config test asserting both venues are bound.
- Local run: books reach and hold `SYNCED`. Watch resyncs, frames/s and ring headroom in the health
  log over a 30–60 min soak, on both venues.
- Feed check: messages/s and books shown vs. Binance (the MEXC noise method), including the two
  depth-cap effects in §5. Raise a follow-up only if it's noisy.
- Frontend: add `BYBIT` to `exchanges.ts` and confirm it renders.
- Docs: a Bybit section in `multi-exchange-progress.md`, and the §11 / decisions list updated.
- Prod: enable behind `BYBIT_ENABLED` / `BYBIT_<MARKET>_ENABLED`, one venue first.

**Suggested split for three people:** A takes BYBIT-3, B takes BYBIT-1, C takes BYBIT-2. Whoever
finishes first takes BYBIT-4.
