# Requirements Foundation

**As of**: 2026-10-16.

This file is the starting point for writing the screener's functional, non-functional and domain
requirements. It does not list detailed requirements. It explains how to think about each category
for this product and names the main items each one should cover. Detailed requirements (IDs,
priorities, acceptance criteria) can be written from it later.

It follows the epic split in [`epics.md`](epics.md). Each section notes which epic an item belongs to.

---

## The "read-only" concern

The screener mostly *shows* data: classified order books, signals and charts. The user rarely
changes state, so it can look as if there is little to write under functional requirements.

This view comes from treating functional requirements as "things the user clicks." **Functional
requirements describe what the system does.** A screener does a lot without being asked: it
ingests, syncs, classifies, detects and delivers. Each of these is a testable functional requirement.

For example, this is a functional requirement, even though no user action triggers it:

> The system shall classify a price level as tier N when its notional is ≥ X and its distance from
> the mid price is ≤ Y%.

The user-facing side is mostly **configuration**, not transactions: rules, filters, watchlists and
alert subscriptions. Configuration is a real functional area, and it grows with every new signal.

Trade execution (Epic 4) is the one feature that turns the product from read-only into read-write
with real money involved. It gets its own section below.

---

## 1. Functional requirements

Group them into three layers.

### 1a. System behaviour (Epics 1 and 2)

**Market data engine**
- Discover the eligible instruments per venue and refresh the universe on a schedule.
- Keep a local order book per instrument that matches the exchange's book.
- Detect sequence gaps, parse errors and disconnects, and resync automatically.
- Ingest new raw streams: trades (see §4) and klines.
- Record historical market data, including order-book depth, which no exchange provides afterwards.

**Signals**
- Classify order-book levels into tiers by notional and distance, and rank the top 5 per side.
- Apply each connected user's custom rules on top of the global defaults.
- Detect price and volume spikes.
- Detect layered order clusters.
- Detect large executed orders from the trade stream (§4).
- Serve candlestick and volume data for a selected ticker and interval: history plus the live candle.

**Delivery**
- Authenticate WebSocket sessions and gate them on entitlement.
- Send each session a snapshot followed by incremental updates.
- Deliver signal notifications (spikes, clusters, executed orders) over the same `/ws` feed.

### 1b. User interactions: configuration (Epic 2)

- Custom classification rules: create, edit and delete them. Edits apply to the live feed without
  a reconnect. *(Implemented.)*
- Filter and sort the screener by exchange, market and ticker.
- Watchlists or favourites. *(Candidate.)*
- Alert subscriptions per signal type: thresholds, which tickers or venues, mute, and
  cooldown/deduplication.
- Notification history: what fired, and when.
- Charts: pick a ticker and interval, and scroll back through history.
- Delivery channels beyond in-app, such as Telegram or browser push. *(Later.)*

### 1c. Accounts, monetization, admin (Epic 3)

- Registration, email verification, login, refresh, logout, `USER`/`ADMIN` roles, and admin bootstrap.
- Plan catalog with regional pricing, a free trial, and `FIXED` and `PER_DAY` prices.
- Order creation, the payment gateway callback, the reconciliation sweep, and stacking entitlement
  grants with a ledger. Payment history.
- Admin: monitoring (presence, order books), the users and usage views, and the billing catalog.

These have real state machines (`OrderStateMachine`, the entitlement states) and are easy to
specify precisely.

### Actors

List external systems as actors, not only people:

- **People:** Guest, Subscriber (trial or paid), Admin.
- **Systems:** each exchange (its WebSocket streams and its REST API), and the Multicard payment
  gateway.

This makes the use-case view honest about where the complexity is: in integration, not in the UI.

---

## 2. Non-functional requirements

### Write outcomes, not tactics

The hot-path rules (no allocation in `onEvent`/`onMessage`, primitive `double`s, streaming JSON
parsing, single-writer books) are **design tactics, not requirements**. A requirement is the
measurable outcome a tactic protects. Write each NFR as:

**requirement (measurable) → tactic that meets it → how it is verified**

| Requirement | Tactic | Verification |
|---|---|---|
| Exchange event → broadcast to client within X ms p99, measured inside the JVM | Disruptor shards, single-writer books, 100 ms drain loop | `PipelineMetrics` latency histogram |
| Sustain the full target universe (1000+ depth streams, N msgs/s) on one VM of size Z | Primitive doubles, streaming JSON, hand-written protobuf reader | Load test or replay |
| GC pause p99 < Y ms under full load | No allocation on the hot path | GC logs |
| **Never present an unsynced book as live** | `PENDING` → `RECOVERING` → `SYNCED` state machine | Gap-injection tests |
| Resync within N s after a gap or disconnect | Per-venue `SnapshotRequestQueue`, rate-limit-aware `SnapshotFetcher`s | Chaos test: kill a connection |
| Never exceed exchange rate limits or get IP-banned | Weight and pacing in each `SnapshotFetcher` | Metrics: no 418/429 responses |
| Support M concurrent WebSocket clients | Virtual-thread send loops, slow-session eviction | Load test |
| A payment is granted exactly once | Idempotent `markPaidAndGrant`, entitlement ledger | Duplicate-callback tests |
| Adding an exchange only touches `adapter/` | The `spi/` boundary | Architecture test (e.g. ArchUnit) |

The numbers (X, Y, N, M, Z) are still to be set. The 2026-09-08 benchmark
([`pipeline-benchmark-2026-09-08.md`](pipeline-benchmark-2026-09-08.md)) gives a measured baseline
for Binance: about 844 msgs/s ingested, and young-GC pauses of p99 31 ms and max 35 ms. Targets
should be set against that baseline, not against the "hundreds of thousands of messages per second"
estimate in `CLAUDE.md`. That estimate is a ceiling for the design, not a measured load.

### NFRs specific to this domain

- **Data honesty and staleness.** Mark a venue or ticker as stale after T seconds without updates,
  and show venue status in the UI instead of silently showing old numbers. For a screener this
  matters more than raw speed.
- **Fairness between users.** The per-user classification pass costs something for every connected
  custom-rules user. A heavy user must not degrade the feed for others. State a budget for it.
- **Signal latency.** Each notification (spike, cluster, executed order) should reach the user
  within a stated time after the triggering event. A late alert is worth much less than a late
  book update.

### General NFRs (short list)

- Security: JWT auth, the `SecurityConfig` allow-list, OWASP basics, secrets only from environment
  variables.
- Availability and recovery: automatic reconnects, and safe restarts (books rebuild from snapshots).
- Observability: pipeline metrics, admin diagnostics, structured logs.
- Data retention: how long recorded history, notifications and the payment audit trail are kept.
- Privacy: user data handling and account deletion.
- Maintainability: the feature-slice packages, plus the rule that the hot-path austerity applies
  only to the hot path.

---

## 3. Domain requirements

This is the richest section for this project. It captures knowledge a general developer would not
have, and it is where the project's expertise shows.

**Order book semantics, per venue**
- Snapshot + diff sync rules: Binance `U` / `u` / `pu`; MEXC version fields.
- A zero quantity means "remove the level."
- The price-filter band: levels further than the threshold from the mid price are dropped.

**Instrument identity**
- The same `BTCUSDT` is four different instruments: Binance spot, Binance futures, MEXC spot and
  MEXC futures. Each has its own book.
- User rules are exchange-independent (`ruleKey` = `base + quote : MARKET`). Classification state
  and feed entries are per venue (`feedKey`).

**Instrument eligibility**
- Quote asset, contract type (perpetual), trading status, and excluded symbols such as stablecoin
  pairs.

**What "significant" means**
- Tiers defined by notional and distance from the mid price.
- Level lifetime (`firstSeenMillis`).
- Market-maker ladders (see the MEXC findings): currently treated as noise.

**Spikes**
- Price: % move within a window compared with a rolling baseline.
- Volume: e.g. a z-score against the usual volume for that ticker and time.
- Cooldowns, so one event does not alert repeatedly.
- The baseline needs history, which ties spikes to the recorder (Epic 1).

**Layered order clusters**
- N levels within X% of each other, on the same side, with similar sizes.
- The MEXC market-maker ladders currently filtered as noise are exactly this pattern. Cluster
  detection and the noise filter must share one definition and must not contradict each other.

**Candles**
- Intervals aligned to UTC.
- Base volume vs. quote volume.
- Exchange-sourced klines vs. self-aggregated from trades.
- Charts bring the first real historical-storage requirement: retention, backfill and gap handling.

**Executed trades:** see §4.

---

## 4. Large executed order notifications (Epic 1 stream + Epic 2 signal)

### What it does

The screener subscribes to trade streams on every enabled venue over WebSocket. Each trade update
says how much was traded at a given price. The screener detects **large executed orders** and
notifies users through the `/ws` feed, the same way as price and volume spikes.

One large resting order is often filled in many small pieces: several takers hit it one after
another. Each piece alone looks small. The detector therefore aggregates prints:

> If many trades happen **at the same price level**, each within a **small time gap** of the
> previous one, together they indicate one large executed volume.

When the combined volume crosses a threshold, the screener emits one notification.

Following the epic rule, this splits into two stories: the **trade stream** (ingestion, Epic 1)
and the **executed-order signal** (detection and notification, Epic 2).

### Functional requirements

- Subscribe to the trade stream for every instrument in each enabled venue's universe.
- Group trades into a **burst** by (venue, instrument, price, aggressor side).
- Extend a burst while each new trade arrives within Δt of the previous one. Close the burst when
  no trade arrives for Δt.
- Emit a notification when the burst's total notional crosses the threshold. The payload carries:
  venue, ticker, price, side, total notional, total quantity, number of prints, duration, and start
  and end time.
- Emit **one** notification per burst. Do not repeat it for every additional print. If a burst
  keeps growing after it fired, either send updates to the same notification ID or wait until the
  burst closes. This is a design choice still to be made.
- Users can subscribe and set thresholds, as with other alerts (§1b).

### Domain requirements

- **Aggressor side.** Trade streams report whether the buyer was the maker (Binance `m`). Taker
  buys lift asks and taker sells hit bids. The side should be part of the burst key, so buys and
  sells at the same price are not merged.
- **Pre-aggregated trades.** Binance `aggTrade` already merges the fills of one taker order at one
  price into a single message. So one taker sweeping a large resting order arrives as one large
  print, and the burst logic is needed for the *several takers* case. Confirm what each venue's
  trade stream emits (raw fills or aggregated ones), because Δt and the thresholds depend on it.
- **Thresholds per instrument.** A fixed notional threshold is too high for small caps and too low
  for BTC. Use per-instrument or per-tier thresholds, or a threshold relative to the instrument's
  normal volume (which needs history again).
- **Link to the order book.** A burst at a price where a classified level just disappeared is a
  strong signal: a wall was eaten. A burst at a price where the resting level keeps refilling
  suggests an iceberg order. Both are worth noting as possible extensions, because the screener
  already has the order book for the same instrument.
- **Δt is a tuning parameter.** It goes in `application.yml` under `screener.*`, as the conventions
  require.

### Non-functional considerations

- **Volume.** Trade streams for 1000+ instruments can carry far more messages than depth at
  1 s / 500 ms. This stream is hot-path work, and the allocation and parsing rules apply to it.
- **Ingress shape.** The ring buffer currently carries `DepthEvent`. Trades need either an
  equivalent event type or a generalised event. Each instrument must still map to a single shard,
  so the burst state for an instrument stays single-threaded, like its book.
- **Notification latency.** The notification should go out within a stated time after the
  threshold is crossed.
- **Bounded state.** Open bursts are kept per (instrument, price, side). Closed bursts must be
  evicted, so memory stays bounded during volatile periods.

---

## 5. Trade execution (Epic 4)

Execution turns the product from "wrong data = bad UX" into "wrong data = the user loses money."
It is a separate bounded context, and its main NFRs are **correctness and confidentiality, not
latency**. Write it as its own requirements section.

### Security: API key custody

- Envelope-encrypt keys at rest, with the master key outside the database (a KMS or similar).
- Never return a key to the client. Never log it. Mask it in every DTO and every error message.
- When a key is added, check its permissions through the exchange's API (Binance has an
  API-restrictions endpoint). **Reject keys that have withdrawal enabled.**
- Encourage users to IP-whitelist the key on the exchange. This needs a stable server egress IP,
  which becomes an infrastructure requirement.
- Require re-authentication or 2FA to add a key, enable trading, or raise limits.
- Keep an append-only audit log of every order action, like the entitlement ledger.
- Consider isolating the code that signs requests (a separate module or process), so a bug in the
  feed or rules code cannot reach the secrets.

### Precision and correctness

- Use `BigDecimal` from the moment of user intent. Prices on screen come from the hot path as
  `double`s. When a user clicks a level to trade, snap the price to the instrument's `tickSize`
  using exact arithmetic before building the order.
- Enforce `stepSize` / lot size and `minNotional` from the exchange's instrument info **before**
  sending, not after the exchange rejects the order.
- Make orders idempotent with client order IDs. **On a timeout the order's status is unknown.**
  Query it before any retry, and never resend blindly. This is the same pattern as payment
  reconciliation.
- Track order and fill state from the exchange's user-data stream, with REST reconciliation as a
  backup. Model the order lifecycle as a state machine, as payments already are.
- Keep the server clock in sync (`timestamp` / `recvWindow`) and respect per-key order rate limits.

### Domain and legal

- Order types, time-in-force, reduce-only, margin mode and leverage on futures, and fees. Scope v1
  explicitly, for example limit and market orders on spot first, on testnet (as `epics.md` says).
- Check each exchange's API terms. MEXC in particular has restricted futures order placement
  through the API for many accounts in the past, so confirm what is allowed before promising it.
- Check whether placing trades on users' behalf creates regulatory obligations in the target
  jurisdiction. Non-custodial "bring your own key" terminals are usually treated more lightly, but
  this should be confirmed. The terms of service need matching disclaimers.
- Journaling can ship before execution, using read-only keys or manual entry (see `epics.md`). It
  carries much less risk and is useful on its own.

---

## Summary

1. Count system behaviour and user configuration as functional requirements. "Read-only" does not
   mean "few requirements."
2. Write NFRs as measurable outcomes. Cite the hot-path rules as the tactics that meet them, and set
   the targets against the measured benchmark.
3. Treat domain requirements as a first-class section. Order book sync, instrument identity, signal
   definitions and trade semantics are where the project's real expertise is.
4. Keep trade execution as a separate, security-first section. Its key qualities are correctness
   and confidentiality.
