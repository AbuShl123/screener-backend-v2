# Use Cases Foundation

**As of**: 2026-10-16.

This file is the starting point for writing the screener's use cases. It does not contain the
finished use cases. It says which use cases are worth writing, how detailed each one should be,
and which actors, extensions and open questions each one should cover. The full use cases can be
written from it later.

It builds on [`requirements-foundation.md`](requirements-foundation.md) and
[`sequence-diagrams-foundation.md`](sequence-diagrams-foundation.md), and follows the epic split in
[`epics.md`](epics.md).

---

## How use cases fit next to the other two documents

The three documents look at the same system from three angles. Keeping them apart stops them from
repeating each other.

| Document | Answers | Viewpoint |
|---|---|---|
| Requirements | *What* must the system do, and how well? | The system, as testable statements |
| Use cases | *Why* does an actor use the system, and what happens when things go wrong? | The actor's goal |
| Sequence diagrams | *How* do the components cooperate, in what order? | The inside of the system |

A use case is written in the actor's language. It does not mention Disruptor shards, `TreeMap`s or
`SnapshotRequestQueue`s. "The system shows the level" is enough. The internal "how" belongs in the
sequence diagrams, and a use case can point to the matching diagram.

---

## The "read-only" concern again

The requirements foundation notes that the screener looks read-only, so it can seem to have few
functional requirements. Use cases have the same problem in a sharper form: the product's most
valuable use case, "watch the market," has a main scenario of about four steps.

The answer is that **the value of a use case is in its extensions, not its main scenario.** For the
screener, the extensions are where the product's real behaviour shows: what the user sees when a
venue disconnects, when a book is resyncing, when access expires mid-session, when the payment
callback is lost. Write a short main scenario and spend the effort on the extensions.

---

## System-initiated flows: use cases or not?

Most of the interesting work is started by an exchange, a timer or the payment gateway, not by a
person. There are two common ways to handle this:

1. **Make the scheduler or exchange the primary actor** ("Scheduler: refresh the instrument
   universe"). This is allowed, but these "use cases" have no goal anyone cares about directly.
   They duplicate the sequence diagrams and the requirements.
2. **Treat them as subfunction use cases** that the user-goal use cases include. "Keep order books
   synchronized" exists because "Monitor significant order-book levels" needs it.

**Recommendation: option 2.** Write the user-goal use cases in full, and list the system flows as a
small set of subfunction use cases (§4) that are included by them and point to the sequence
diagrams for the detail. The exchanges, Multicard and the scheduler still appear, as **supporting
(secondary) actors** of the user-goal use cases. This keeps the use-case view honest about where
the complexity is, without writing the pipeline twice.

The one exception is the payment reconciliation sweep. It is system-initiated, but it exists to
serve a user goal ("I paid, so I should get access"). Put it in that use case as an extension, not
as a separate use case.

---

## Actors

| Kind | Actor | Notes |
|---|---|---|
| Primary | **Guest** | Not registered, or not logged in. Can see the plan catalog. |
| Primary | **Subscriber** | A registered, verified user with access: `TRIAL` or `ACTIVE`. |
| Primary | **Expired user** | Registered, but access has lapsed. Can log in and pay, cannot use the screener. Worth naming separately, because it is the actor of the renewal flow. |
| Primary | **Admin** | `ADMIN` role. Bypasses entitlement. Manages the catalog, watches system health. |
| Supporting | **Exchange** (Binance, MEXC) | Supplies market data; later (Epic 4) receives orders. |
| Supporting | **Payment gateway** (Multicard) | Takes the payment, sends the callback, answers status queries. |
| Supporting | **Email service** (SMTP) | Delivers verification emails. |
| Supporting | **Scheduler / clock** | Triggers the universe refresh, reconciliation sweep and feed drain. |
| Supporting (later) | **KMS** | Holds the master key for users' exchange API keys (Epic 4). |

Subscriber, Expired user and Admin can be drawn as specialisations of a general **Registered
user** in the use-case diagram.

---

## Detail levels

Not every use case needs the full template. Use two levels:

- **Fully dressed:** primary and supporting actors, stakeholders and interests, preconditions,
  trigger, main success scenario, extensions, postconditions (success guarantee and minimal
  guarantee), and related requirements and sequence diagrams.
- **Brief:** a goal, the actor and one paragraph. Enough for CRUD and for features not designed
  yet.

The **minimal guarantee** (what the system promises even when the use case fails) is worth filling
in carefully for this product. It is where the domain's key promises live: "never present an
unsynced book as live," "a payment is never lost and never granted twice," "an API key is never
exposed."

---

## 1. Core set: implemented features (write these fully dressed)

### UC-1 Monitor significant order-book levels (Epic 2)

The product's main use case and the reason anyone pays. Write it first.

- **Primary actor:** Subscriber. **Supporting:** Exchanges, Scheduler.
- **Stakeholders and interests:**
  - Subscriber: sees large resting orders near the price, quickly, and can trust they are real.
  - Business: only paying or trial users get the feed.
- **Preconditions:** logged in; access is `TRIAL`, `ACTIVE` or `ADMIN`.
- **Main success scenario:**
  1. The subscriber opens the screener.
  2. The system authenticates the session and checks access.
  3. The system sends the current classified levels for every venue (snapshot).
  4. The system keeps sending changes as levels appear, grow, shrink or disappear.
  5. The subscriber reads the levels: venue, ticker, side, price, size, tier, distance, lifetime.
- **Extensions (the interesting part):**
  - 2a. Invalid or expired token: the connection is refused. The client refreshes the token and
    retries.
  - 2b. No access (expired): the connection is refused. The subscriber is directed to UC-4.
  - 3a. The subscriber has custom rules: the snapshot is classified with their rules, not the
    global defaults (UC-2).
  - 4a. A venue's book goes out of sync (gap, disconnect): its levels stop being reported until
    the book is resynced. The subscriber never sees an unsynced book as live. *(Today they also
    get no explicit signal that the venue is stale. See the staleness NFR.)*
  - 4b. The subscriber's connection is too slow to keep up: the session is evicted, and the client
    reconnects and gets a fresh snapshot.
  - 4c. Access expires while connected. **Open question:** entitlement is checked only when the
    session opens (`@OnOpen`), so today the feed continues until the next reconnect. Decide
    whether that is the intended behaviour and write it down either way.
  - 4d. A new instrument is listed on the exchange: it is registered at the next universe refresh,
    but not streamed until the pool is restarted (no dynamic re-subscription yet).
- **Minimal guarantee:** the subscriber never sees a level from a book that is not synced.
- **Includes:** UC-S1, UC-S2.
- **Related:** sequence diagrams 1.2 and 1.4; requirements §1a "Delivery", the staleness NFR.

### UC-2 Customize classification rules (Epic 2)

The main thing a subscriber *configures*. It is interesting because the change reaches an
already-open feed without a reconnect.

- **Primary actor:** Subscriber.
- **Preconditions:** logged in, has access.
- **Main success scenario:**
  1. The subscriber opens the rules for a ticker and market (for example `BTCUSDT`, futures).
  2. The subscriber sets their own tier thresholds (notional, distance) and saves.
  3. The system validates and stores the rule.
  4. The system re-classifies that ticker for the subscriber on every venue that lists it, and
     sends the new snapshot to every open session of the subscriber.
- **Extensions:**
  - 1a/2a. No access: rejected with "Active subscription required."
  - 3a. Invalid rule (unknown market, inconsistent tiers): rejected with a validation error,
    nothing stored.
  - 4a. The subscriber has no open session: the rule is stored and used on the next connect.
  - 4b. The subscriber has several open sessions (two tabs or devices): all of them are updated.
  - 2b. The subscriber deletes the rule: that ticker falls back to the global defaults, live.
- **Domain point worth stating:** a rule is per ticker and market but **not per exchange**. One
  rule for `BTCUSDT` futures applies to Binance futures and MEXC futures alike.
- **Related:** sequence diagram 1.5.

### UC-3 Register and start a free trial (Epic 3)

A standard flow, but it holds a business rule: the trial starts at registration, not at first use.

- **Primary actor:** Guest. **Supporting:** Email service.
- **Main success scenario:**
  1. The guest registers with email and password.
  2. The system creates an unverified account, starts the free trial (default 7 days) and sends a
     verification email.
  3. The guest opens the link; the system marks the email verified.
  4. The guest logs in and receives tokens. They are now a Subscriber on `TRIAL`.
- **Extensions:**
  - 1a. Email already registered: rejected.
  - 3a. Link expired: the guest asks for a new one (resend, with a cooldown).
  - 4a. The guest tries to log in before verifying: rejected with "Email not verified."
  - 2a. The email never arrives: resend (same as 3a).
- **Business rule to state explicitly:** the trial clock runs from registration, including the time
  before verification.
- **Related:** sequence diagram 3.1.

### UC-4 Purchase or renew access (Epic 3)

The money use case. It has the most supporting actors and the most important minimal guarantee.

- **Primary actor:** Subscriber (on trial) or Expired user. **Supporting:** Payment gateway,
  Scheduler.
- **Stakeholders and interests:**
  - User: pays once, gets the days they paid for, immediately.
  - Business: never grants access without payment, never loses a payment.
- **Main success scenario:**
  1. The user views the plan catalog with prices in their region's currency.
  2. The user picks a plan: a fixed bundle of days, or a number of days at the per-day price.
  3. The system creates an order and redirects the user to the gateway.
  4. The user pays on the gateway's page.
  5. The gateway notifies the system; the system marks the order paid and extends access.
  6. The user returns and sees the new expiry date.
- **Extensions:**
  - 2a. The user already has paid access far from expiry and picks a fixed plan: rejected. They
    can renew within the renewal window (default 5 days) or use pay-by-days at any time.
  - 3a. The user already has an unpaid open order: it is reused instead of creating a second one.
  - 4a. The user cancels or the payment fails: the order ends unpaid, access does not change.
  - 5a. The gateway's notification is lost: the reconciliation sweep queries the gateway, finds
    the payment and grants the same access.
  - 5b. The notification arrives twice, or the sweep and the notification race: access is granted
    exactly once.
  - 5c. The user still has time left: the new days are added on top of the current expiry, not
    from today.
- **Minimal guarantee:** every paid order eventually grants access exactly once, and no access is
  granted without a paid order. Every grant is recorded in the ledger.
- **Related:** sequence diagram 1.3; requirements NFR "a payment is granted exactly once."

### UC-5 Administer the platform (Epic 3) (brief)

Write as brief use cases. They are ordinary CRUD and read-only views.

- **UC-5a Manage the plan catalog:** create and edit plans and per-currency prices, activate and
  deactivate them.
- **UC-5b View users and their access:** who is on trial, paid or expired.
- **UC-5c Monitor system health:** who is connected (presence), and inspect a live order book to
  check the pipeline is correct.

One point worth a sentence in UC-5c: it is the admin's tool for answering "is the screener showing
the truth right now?" This connects it to the domain promise in UC-1.

---

## 2. Planned features (write as features are designed)

These are worth including in a project document because they show where the product is going.
Write them brief now, and fully dressed once the design is settled.

### UC-6 Receive signal alerts (Epic 2)

One use case covering price/volume spikes, order clusters and large executed orders, since the
user's goal is the same: "tell me when something unusual happens on a ticker I care about."

- **Primary actor:** Subscriber. **Supporting:** Exchanges (depth and trade streams).
- **Two sub-goals**, which can be two use cases:
  - **Subscribe to alerts:** pick signal types, tickers or venues, thresholds; mute; cooldown.
  - **Receive an alert:** the alert arrives on the open feed, and is kept in a history.
- **Extensions worth listing:**
  - The same event keeps firing: cooldown and deduplication.
  - A burst keeps growing after the alert fired: update the same alert, or wait for the burst to
    close (the open design choice from the requirements foundation §4).
  - The subscriber is offline when it fires: it appears in the notification history. (Push
    channels such as Telegram are a later extension point.)
  - No baseline yet for a new ticker: spike detection is not active for it.

### UC-7 Analyse a ticker on a chart (Epic 2)

- **Primary actor:** Subscriber.
- **Goal:** open a ticker from the screener, choose an interval, see history plus the live candle,
  and scroll back.
- **Extensions:** gap in recorded history; interval not available for that venue.
- A good place to show the screener's levels and alerts overlaid on the chart, which is what ties
  the views together.

### UC-8 Organise the screener view (Epic 2) (brief)

Filter and sort by exchange, market and ticker; keep a watchlist. Likely to be the input for
UC-6 ("alert me on my watchlist").

---

## 3. Trading and journaling (Epic 4): the most interesting future use cases

These are the use cases where the product stops being read-only and real money is at risk. They
deserve fully dressed use cases even before implementation, because writing the extensions is how
the design gets decided. They are also the strongest material in the document for discussing
security.

### UC-9 Connect an exchange account

- **Primary actor:** Subscriber. **Supporting:** Exchange, KMS.
- **Main success scenario:** the subscriber re-authenticates → pastes the API key and secret → the
  system checks the key's permissions with the exchange → stores the key encrypted → shows it
  masked.
- **Extensions:**
  - The key has withdrawal permission: **rejected**, with an explanation.
  - The key is invalid or revoked on the exchange.
  - The key lacks trading permission: accepted as read-only (journaling only).
  - The subscriber removes the key: it is deleted and can no longer be used.
- **Minimal guarantee:** the key is never shown, logged or returned after it is stored.

### UC-10 Place an order from the screener

The use case that justifies the whole product direction: see a large level, act on it without
leaving the screener.

- **Primary actor:** Subscriber. **Supporting:** Exchange (REST and user-data stream).
- **Main success scenario:** the subscriber clicks a level → the order form opens with the price
  pre-filled → they set side and size → confirm → the system places the order → the order and its
  fills appear in their open orders.
- **Extensions:**
  - The clicked price is not a valid tick: snapped to the tick size before sending.
  - Size below the minimum or not a valid step: rejected before sending, not by the exchange.
  - **Timeout: the order's status is unknown.** The system checks the order on the exchange
    before showing any result and never resends blindly.
  - The exchange rejects the order (balance, rate limit): the reason is shown.
  - The level disappears before the order arrives: the order may rest or not fill. This is a
    normal market outcome and the use case should say so, so it is not treated as a bug.
- **Minimal guarantee:** no order is ever sent twice for one confirmation, and every order action
  is in the audit log.
- **Scope note:** v1 is testnet, spot, limit and market orders (see `epics.md`).

### UC-11 Keep a trading journal (brief)

Import trade history with a read-only key, or enter trades by hand; see PnL; add notes. Does not
depend on UC-10 and can ship first.

---

## 4. Subfunction use cases (system flows)

Short entries only. Each is *included* by the user-goal use cases above and points to its sequence
diagram for the detail.

| ID | Subfunction | Included by | Supporting actor | Detail in |
|---|---|---|---|---|
| UC-S1 | Keep the instrument universe current | UC-1 | Scheduler, Exchange REST | Sequence 1.1 |
| UC-S2 | Keep each order book synchronized | UC-1, UC-6, UC-10 | Exchange WS + REST | Sequence 1.2 |
| UC-S3 | Authenticate a request or session | every UC except UC-3 | — | Sequence 1.4, 3.1 |
| UC-S4 | Check access (entitlement) | UC-1, UC-2, UC-6, UC-7 | — | Sequence 1.4, 1.5 |
| UC-S5 | Record market history | UC-6 (baselines), UC-7, Epic 5 | Exchange WS | — (not designed) |

UC-S5 is worth a sentence in the document even though no user triggers it: it is the reason
recording started early (order-book depth history can't be obtained afterwards).

---

## Use-case diagram

One diagram for the whole system is enough. Suggested layout:

- Human actors on the left (Guest, Registered user with its three specialisations, Admin).
- External systems on the right (Exchange, Payment gateway, Email service, KMS), with the
  Scheduler at the bottom.
- Group the use cases into packages by epic: Signals (UC-1, 2, 6, 7, 8), Platform (UC-3, 4, 5),
  Trading (UC-9, 10, 11).
- Show the subfunctions with `<<include>>` only for UC-S2 (sync) and UC-S4 (access). Including
  authentication everywhere adds arrows without adding information; state it once as a note.
- Mark planned use cases (UC-6 onwards) visually, for example dashed or grey, so the diagram shows
  both the current product and its direction.

---

## Recommended priority for writing

1. **UC-1 Monitor significant order-book levels.** The product's core. Its extensions carry the
   main domain promise.
2. **UC-4 Purchase or renew access.** The richest real extensions (lost callback, duplicate
   callback, stacking, renewal window) and a clear minimal guarantee.
3. **UC-2 Customize classification rules.** Shows the live-update behaviour and the
   exchange-independent rule identity.
4. **UC-10 Place an order from the screener** (with UC-9). The most interesting future use case;
   writing it now forces the safety decisions early.
5. **UC-6 Receive signal alerts.** Shows the product's growth path.
6. UC-3, then the brief ones (UC-5, UC-7, UC-8, UC-11) and the subfunction table.

---

## Open questions surfaced while drafting

- **Access expiring mid-session (UC-1, 4c):** keep the feed until reconnect, or close the session
  at expiry?
- **Venue staleness (UC-1, 4a):** should the subscriber be told that a venue is resyncing or down,
  instead of its levels quietly disappearing?
- **New listings (UC-1, 4d):** should newly listed instruments start streaming without a restart?
- **Alert lifecycle (UC-6):** one alert per burst, or an updating alert?
