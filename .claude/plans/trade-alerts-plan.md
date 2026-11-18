# Trade Alerts — Vision and Plan

Adds a `TRADE` stream to the market-data pipeline, detects **large executed trades** and
**same-side bursts**, and delivers them to users. This is a vision document: it fixes what we are
building and in what order. Each phase gets its own implementation plan when it starts.

**Read first**: `.claude/docs/market-data-pipeline-architecture.md` (§12 planned items, §13 recipe for
adding a stream kind), `.claude/plans/multi-stream-transport-plan.md` (the transport this builds on).

---

## 1. Why

The depth feed reports **resting orders** (walls): offers that have not been executed and may never
be. They cost nothing to place or cancel, so some of them are noise or spoofing.

A **trade** is an execution: someone crossed the spread and real money changed hands. Large
executions are a different signal from walls, and clients want to see them.

Trades also carry something depth cannot: the fills. When a level disappears from the book, depth
cannot say whether it was cancelled or eaten. The trade stream can.

---

## 2. Domain model

- A resting limit order is never "executed" by its owner. It is filled when another participant (the
  **taker**, or aggressor) sends an opposite-side order that reaches its price. Matching is by price,
  then time.
- Every trade has a buyer and a seller. What we call a trade's **side** is the **aggressor's side**:
  - **Taker buy** consumed asks.
  - **Taker sell** consumed bids.
  - Binance aggTrade: `m = true` (buyer is maker) means the seller was the aggressor, so a **sell**.
  - Side is not bullish/bearish. Show it as "aggressor bought/sold".
- **Distance from mid is not a trade dimension.** Trades print at or very near the best bid/ask.
  A sweep prints further out, but that is price impact, not importance. The only measure of size is
  **notional = price × quantity**.
- **Trades are events, not state.** The depth feed is a book of levels with snapshots and diffs. A
  trade has no "current state"; a client sees things that happened.
- **aggTrade is fragmented.** Binance aggregates fills of one taker order per price level. A large
  market order sweeping ten levels arrives as about ten events. Checking each event alone would
  miss the biggest trades. Verified against raw trades (`trade-detection-plan.md` §10.1): a spot
  aggTrade is one taker order at one price. A futures aggTrade is a run of same-price, same-side
  fills over up to ~100 ms, so it can merge several orders.
- Large sizes mean different things per coin: $30K is routine on BTC and big on a small altcoin. See
  open question Q1.

---

## 3. Decisions

### D1. `aggTrade` first, on Binance

`aggTrade` sends far fewer updates than raw `trade` and is enough for a screener. Binance spot and
futures first. Other venues follow once the model is proven (Bybit `publicTrade`, MEXC trades).
`StreamProtocol` and a decoder SPI absorb each venue's format, as §13 of the architecture doc
describes.

### D2. No tiers: one static USD threshold

No tier hierarchy and no reuse of `DefaultClassificationRule` numbers. Those thresholds were tuned
for resting walls and include a distance dimension that does not apply to trades.

- A trade is **large** when its notional is at or above one configured threshold (starting idea:
  **$30K**, tuned from live data).
- The threshold is a `screener.*` tunable bound to a `@ConfigurationProperties` record, with a
  default plus an optional per-symbol override map (see Q1). It is never a literal.
- Because the threshold is a single value looked up by instrument, the v2 per-user override (D6)
  is a lookup layered on top, not a redesign.

### D3. Burst detection is part of the core idea, not an extra

Many smaller same-side trades over N seconds can add up to a large volume, and that is an alert in
itself. Two layers solve two problems:

1. **Sweep coalescing (milliseconds).** Events from one taker sweep are summed so a sweep is judged
   as one trade, not ten fragments.
2. **Burst window (seconds).** Same-side volume per instrument is accumulated over a rolling
   window of N seconds. When the sum crosses the burst threshold, a burst alert fires.

Both are per-instrument state owned by the shard thread (like the order book), held as primitives
in the instrument's slot. No locks, no allocation per event. The burst window, the burst
threshold and the re-alert rule (Q3) are tunables.

### D4. Delivery: a `FeedChannel`, discrete events, plus a recent-trades store

- A new channel bean (working name `TradeChannel`) delivers alerts through the existing
  `FeedBroadcaster`. The broadcaster does not change.
- Each message is a discrete event, not a snapshot/diff of state. Working payload: venue/market,
  symbol, kind (`TRADE` or `BURST`), aggressor side, price (last/volume-weighted for a burst),
  quantity, notional, time, and for a burst the window length and number of trades.
- **On connect, the user receives the last N large trades and bursts.** This needs a bounded
  in-memory **recent-trades store**: a ring of the last N alerts, written by the shard threads and read
  by the broadcaster. N is a tunable. In-memory first (lost on restart); persistence is out of scope.
- The same entitlement gate as the depth feed applies (`EntitlementService.hasAccess`, admins bypass).

### D5. Pipeline integration follows the §13 recipe

`StreamKind.TRADE`; a `streams.TRADE` block per venue (off until ready); a `StreamProtocol` per
venue; a trade decoder SPI that reports primitives to a core-owned sink; trade state in
`InstrumentSlot`; a `TRADE` arm in `MarketEventHandler`; and a `FeedChannel`. The trade arm is a
stateless threshold check plus the two accumulators. There is no book and no sync state machine.
Hot-path rules from `CLAUDE.md` apply to the decoder and the arm. Alert emission is rare, so the
shard thread allocates the alert and adds it to `TradeAlertStore` under a lock (Phase 2). How the
channel reads it from there is a Phase 3 decision.

### D6. Per-user thresholds are the last step

Per-user configuration of the threshold **per ticker per exchange** is deliberately last. It will
reuse the rule-update propagation pattern used for classification rules. Note that trade
thresholds are keyed by exchange as well as symbol, unlike depth rules, which are exchange-independent.
Not designed here.

---

## 4. Phases

Each phase is shippable and builds on the one before. The first phases ship behind
`enabled: false` until verified.

| # | Phase | Outcome |
|---|---|---|
| 1 | **Transport** | **Done.** `StreamKind.TRADE`; Binance spot and futures `aggTrade` protocol; per-kind frame counters show trade traffic. An explicit `TRADE` arm in `MarketEventHandler` drops the frames. No decoder, no alerts. Plan: `.claude/plans/trade-streams-transport-plan.md`. |
| 2 | **Decode and large-trade detection** | **Done** (slice 3's live comparison with the capture still open). Trade decoder SPI. Trade logic in the `TRADE` arm: side, notional, single static threshold, sweep coalescing (D3 layer 1). Fired alerts go to the recent-trades store. Metrics for trade rate and alert rate. Plan: `.claude/plans/trade-detection-plan.md`. |
| 3 | **Delivery** | **Done.** `TradeChannel` bean; last-N replay on connect; entitlement gate; contract doc under `.claude/docs/for-frontend/`. First end-to-end release. Plan: `.claude/plans/trade-delivery-plan.md`; contract: `websocket-feed-api.md` §3.8. |
| 4 | **Burst detection** | Per-instrument same-side window (D3 layer 2); burst alerts with a re-alert rule; burst tunables. |
| 5 | **More venues** | Bybit and MEXC trade protocols and decoders, one venue at a time. **MEXC first** (trade alerts matter most on smaller exchanges): plan `.claude/plans/trade-mexc-plan.md`; transport, decoding and the futures idle close done (slices 1–3); slice 4 moved the thresholds to `trade-thresholds.yml` with a per-venue default, the MEXC values are provisional (4 h, no US open) until re-derived over busy hours, then the live check; streams off by default until then. Contract and reconnaissance: `external-docs/mexc/mexc-trades-empirical.md`. MEXC deals are fills against individual resting orders, not per-price aggregates, and include clock-driven deals that must be filtered. |
| 6 | **Per-user thresholds** | Per ticker per exchange user override (D6), with live updates to connected sessions. |

Possible later ideas, not committed: correlating trades with depth to label a vanished wall as
**filled** vs **pulled**; per-ticker adaptive thresholds from rolling volume; futures liquidation
streams as a related signal.

---

## 5. Open questions

- **Q1. Is one threshold enough for noisy tickers?** $30K will fire very often on BTC, ETH and
  SOL. The per-symbol override map in D2 (or a multiplier for the high-liquidity set) is the likely
  answer. The default value and which symbols get overrides are decided from live data in Phase 2.
  *Starting values*: a $30K default plus overrides that cap each symbol at ~10 alerts/h (7 spot,
  27 futures symbols; BTC futures $5M). See `trade-detection-plan.md` §10.2. A threshold scaled from
  each symbol's rolling volume fits those overrides closely (§10.5).
- **Q2. Is the burst threshold the same as the single-trade threshold?** Probably higher, since a
  busy ticker accumulates $30K in seconds. Decide in Phase 4 from data.
- **Q3. Burst alert behavior.** Sliding or tumbling window? Alert once when the threshold is
  crossed, or update while it keeps growing? What cooldown stops one long run from spamming?
- **Q4. Recent-trades store scope.** One global last-N, or per venue / per symbol? Does a user's
  filter apply on replay? *Answered*: one global list of the last 100, replayed in every `SNAPSHOT`
  and merged by id on the client (a snapshot does not clear trades); no server-side filter until
  Phase 6 (`trade-delivery-plan.md` §1).
- **Q5. Sweep coalescing rule.** Which events count as one sweep (same timestamp, same side, within
  a few ms)? Depends on the Binance aggregation behavior verified in Phase 1. *Answered*: same
  side, same trade time, price moving in the aggressor's direction, and contiguous ids when known
  (`trade-detection-plan.md` §10.3).
- **Q6. Connection limits.** Trade pools add sockets per venue. Check Binance's per-IP limits before
  enabling on all symbols.
