# Project Epics

**As of**: 2026-10-05.

This file defines the five Jira epics the screener's work is organized under. It says what each
epic covers and why the work is split this way. Each Jira user story belongs to exactly one epic.

**Frontend has no separate epic.** Every story is a vertical slice: it includes both the backend
work and the frontend work that delivers it to the user. A separate frontend epic would split each
feature in two and hide whether a feature is actually done.

---

## Overview

| # | Epic | Status | Character |
|---|------|--------|-----------|
| 1 | Market Data Engine | Nearly done | Infrastructure, performance-critical |
| 2 | Screener Signals & Visualization | Active | Product features users pay for |
| 3 | Platform & Operations | Mostly done | Ordinary CRUD, business model, ops |
| 4 | Trading & Journaling | Not started | High precision, custody of user API keys |
| 5 | AI & Prediction (research) | Exploratory | Research, open-ended |

---

## Epic 1 — Market Data Engine

**Goal:** collect accurate, real-time market data from every supported exchange and keep it
available to the rest of the system.

**Scope:**
- The multi-exchange adapter architecture (`spi/`, `adapter/*`) and venue identity (`Venue`,
  `Instrument`, `InstrumentRegistry`).
- Stream transport, Disruptor ingress shards, local order books, and per-venue sync strategies
  and snapshot recovery (`marketdata/core/`).
- Instrument discovery and universe refresh.
- New exchanges and venues (MEXC spot next, then others).
- New raw data streams: klines/candlesticks and trades. This covers *ingesting* them; displaying
  them belongs to Epic 2.
- **Historical data recording:** saving order-book and market data to disk for later use.
- Pipeline health metrics and throughput benchmarks.

**Status:** the architecture is settled. Binance spot and futures are live-verified, and the MEXC
futures adapter is implemented but disabled by default until it is enabled end to end. The remaining work is mostly adding exchanges, adding new streams,
and building the recorder.

**Definition of done for a story:** the data arrives correctly and stays in sync, at target scale.
Most stories here have no UI. When one does, it is an admin or diagnostic view.

---

## Epic 2 — Screener Signals & Visualization

**Goal:** turn raw market data into signals and views that traders pay for.

**Scope:**
- Order-book level classification (`analysis/`): global tiers and the default feed.
- Per-user custom classification rules (`analysis/rule/`) and how rule edits reach the live feed.
- Feed delivery: `feed/` broadcaster and per-user feeds, plus the `ws/` WebSocket server.
- New signals: price/volume spike detection, order clusters.
- Alerts, i.e. notifying users when a signal fires.
- Live candlestick charts and the other screener views on the frontend.

**Status:** classification, custom rules and the live feed are implemented. Spikes, clusters,
alerts and charts are upcoming.

**Definition of done for a story:** a user can see the signal or use the view in the screener.

---

## Epic 3 — Platform & Operations

**Goal:** everything the product needs to run as a business and as a service, apart from the
screener itself.

**Scope:**
- Accounts: registration, login, email verification, JWT auth, roles (`auth/`, `user/`, `email/`).
- Monetization: plan catalog, pricing, trial, entitlement, orders and payments (`billing/`,
  `entitlement/`, `payment/`), and new payment providers or subscription tiers.
- Admin tooling and diagnostics (`monitoring/`, `/api/admin/**`).
- Operations: deployment, CI, hosting, database backups, logging and monitoring.
- The frontend shell: layout, routing, design system, landing page, account and settings pages.

**Status:** largely implemented. Future work here, such as new payment options and tiers, is low
priority.

**Character:** this is ordinary Spring MVC + JPA code, and the hot-path performance rules do not
apply. It is the easiest epic technically, but it is where the money is made, so correctness of
payments and access matters more than speed.

---

## Epic 4 — Trading & Journaling

**Goal:** let users act on the market from inside the screener, and keep a record of what they
did.

**Scope:**
- **API-key custody:** encrypted storage of users' exchange API keys, permission scoping, and a
  rule that keys never appear in logs or responses. This is the foundation story and the riskiest
  part of the epic.
- **Execution:** placing and cancelling bid/ask orders on a chosen exchange and ticker without
  leaving the screener. Testnet only, at least initially.
- **Journaling:** a history of the user's trades with PnL and notes.

**Status:** not started. Planned for after Epics 1 and 2 have settled.

**Sequencing note:** journaling does not depend on execution. A user's trade history can be
imported with read-only API keys or entered by hand. That is lower risk, useful on its own, and can
ship before execution.

---

## Epic 5 — AI & Prediction (research)

**Goal:** explore whether models trained on the screener's recorded history can help predict
market movements.

**Scope:**
- Data preparation and feature extraction from the history Epic 1 records.
- Model training and evaluation, likely with Python libraries outside the JVM app.
- If it proves useful, exposing predictions to users through Epic 2's views.

**Status:** exploratory and loosely defined. It may not start this academic year. Stories here
are expected to be research spikes rather than shippable features.

---

## Justification

### Why these boundaries

The epics are split by **capability and risk profile**, not by layer or package. Each epic has its
own kind of difficulty, its own timeline and its own definition of done:

- **Engine** is about correctness and throughput under load.
- **Signals** is about product value.
- **Platform** is about business correctness and keeping the service running.
- **Trading** is about precision and security.
- **AI** is about research.

Mixing them in one epic would hide these differences when planning.

### Why the Engine and Signals are separate epics

The market-data work used to be planned as one epic. It was split because it contained two
different kinds of work:

- The **engine** is infrastructure that is close to finished. As its own epic it can reach Done,
  which is a clear milestone.
- The **signals** are open-ended product features: there is always one more signal to add. Kept
  together with the engine, the combined epic could never close, and the finished infrastructure
  work would be hard to report as finished.

Some stories involve both. For example, spike detection may need a new trade stream. Split such
stories in two: the stream goes in Epic 1 and the signal goes in Epic 2.

### Why historical recording is in Epic 1, not Epic 5

Recording history is market-data work, and several features need it, not only AI:

- **Spike detection** needs a baseline ("volume is 5× its usual level"), which comes from history.
- **Candlestick charts** need history behind the live candle.
- **Journaling and backtesting** need to replay past market conditions.
- **AI** needs months of data before training is worth attempting.

Timing matters too. Exchanges publish historical klines and trades, but none of them gives away
historical **order-book depth**. That data exists only if we record it ourselves, and anything we
don't record is lost for good. The recorder therefore has to start early, in Epic 1, even though
Epic 5 may be a year away.

### Why operations and the frontend shell are in Epic 3

Deployment, CI, monitoring, and frontend structure like layout, routing and the design system
don't fit any single feature epic. Without a home they become orphan stories. Epic 3 already holds
the product's non-screener foundations, so they belong there.

### Epics vs. components

Here, epics are long-lived areas of work, which is fine for a small team. If finite deliverables
also need tracking later, the area can move to a Jira **Component** or label, and epics can be
used for bounded chunks such as "MEXC spot support" or "Spike alerts v1".
