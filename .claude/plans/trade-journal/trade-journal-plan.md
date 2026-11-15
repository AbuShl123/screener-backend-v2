# Trade Journal: Implementation Plan

**Updated**: 2026-11-03.
**Epic**: 4, Trading & Journaling (UC-9 connect an exchange account, UC-11 keep a trading journal).
See `.claude/docs/epics.md` and `.claude/docs/use-cases-foundation.md`.

**Source material**: the "Дневник Трейдера" (Trader's Diary) 1.7.8 handoff package. It is a
Windows desktop journal (Python, PySide6, SQLite). It currently lives outside the repo in
`Downloads/Дневник Трейдера — для TC Screener/`, called `<diary>/` below. The files that matter:

| File | What we take from it |
|---|---|
| `<diary>/Техническое описание.md` | Feature list, data model, algorithms, exchange notes |
| `<diary>/source/services/exchange_common.py` | `build_positions_from_fills`, `apply_funding`: **the reference for the assembly port** |
| `<diary>/source/services/live_ingest.py` | Live fill → position update |
| `<diary>/source/analytics/metrics.py` | Statistics and the equity curve |
| `<diary>/source/database/db.py` | `sync_exchange_positions` (re-import) |
| `<diary>/source/services/{binance,bybit,okx}_service.py` | Endpoints, pagination, fill mapping, ban handling |
| `<diary>/source/gui/help_content.py` and the PDF | The behaviour the screens must have |

The Python is a **reference specification**, not code to translate line by line. Its assembly
rules and the bug notes in its comments carry over in full. Its storage and re-import design
does not (see D1, D2).

---

## 1. Goal

A user connects read-only exchange keys and gets a journal built automatically: their trades
assembled from executions, with PnL, fees, funding and statistics, plus notes. Analysis tools
(strategies, an error catalogue, screenshots, a trade chart) come after that. All of it lives in
this backend as a normal cold-path feature slice and is served to the React frontend.

---

## 2. Decisions made in this plan

### D1. Fills are the source of truth; positions are derived

The desktop app assembles positions from whatever window it just downloaded, then reconciles them
against saved positions. Bugs come from that. An import window that starts mid-position yields a
wrong position (on spot, a fake SHORT). A late fill shifts the entry time and the trade is deleted
and re-inserted. The deletion pass needed a special guard after it wiped history.

Here:
- Every execution is stored once in `journal_fills`, unique on `(connection_id, symbol, trade_id)`.
  An import inserts with `ON CONFLICT DO NOTHING`, so repeating an import is a no-op.
- Positions for one **group** `(connection, market, symbol)` are recomputed from **all** of that
  group's stored fills plus its funding rows. They are never assembled from a download window.
- Executions are final on every supported exchange, so nothing is ever deleted by an import. The
  desktop app's "delete trades that disappeared" pass goes away, and so does its bug class.

**Recompute boundary.** Assembly resets to flat at every closed position. A new fill at time `t`
therefore only affects positions from the first one whose exit is `null` or `>= t`. Everything
earlier stays as it is. Imports recompute whole groups. Live fills recompute from that boundary,
which in practice is the open position.

### D2. Position identity: overlapping fills, not entry time

After a recompute, each new position is matched to old positions in the same group that share at
least one fill id:
- **No match**: a new row.
- **One match**: reuse that row's id, so notes, strategies, errors and screenshots stay attached.
- **Several old positions match one new one** (a late fill merged them): keep the oldest id,
  merge the annotations into it (notes joined with a separator, link tables unioned), delete the rest.
- **One old position matches several new ones** (a late fill split it): the earliest new position
  takes the id, and the others get copies of the annotations.

Manual positions (no fills) are never touched by a recompute.

### D3. Binance journal traffic shares the screener's weight budget

Binance request-weight limits apply **per IP**, and signed account endpoints count too: spot is
6000/min, USD-M futures 2400/min. Order-book recovery spends the same budget, tracked today by a
`WeightGuard` that `BinanceSnapshotFetcher` creates privately. One unthrottled full spot import
(about 600 USDT pairs × `myTrades` weight 20) is two minutes' worth of budget. It would delay
snapshot recovery for every Binance book, or get the server IP banned.

So:
- Each Binance venue's `WeightGuard` becomes a **bean shared** between that venue's snapshot fetcher
  and the journal's Binance client. Both `observe()` every response, so each side sees what the
  other spent.
- Journal requests go through a `JournalRequestBudget` that only sends while
  `remaining > reserve`, where `reserve` is a fraction of the limit set in YAML (start at 50%) and
  held back for snapshot recovery. While banned, it sends nothing. The snapshot fetcher's own
  behaviour does not change.
- Discovery is narrow by default (§7.2). A full scan of every pair is an opt-in "deep scan" and
  runs under the same budget, so it may take several minutes.
- One import job runs per exchange at a time on a small dedicated executor (§9). Concurrent
  users queue up instead of competing.

A separate egress IP or proxy for journal traffic would remove the coupling entirely. That is
open decision Q2. The design above is needed either way, because the journal must not get the
server banned.

### D4. Keys: AES-256-GCM on the server, write-only through the API

See §8. Keys are never returned, logged or sent to the browser.

### D5. The assembly port is faithful, with listed deviations

`build_positions_from_fills`, `apply_funding` and the metrics are ported with the same rules and
thresholds (0.2% dust ratio, $1 dust value), and the comments are carried over. The only
intentional differences are listed in §6.2. Each one has its own test, and the parity tests mark
it as a deliberate deviation.

### D6. One journal per user in v1, no "accounts"

The desktop app has several independent accounts per user, each with its own trades, strategies
and errors. That was stage 3 in their plan. Connections already split trades by source, and the
API can filter by connection. Accounts are left out of v1. Adding them later would mean adding
`journal_account_id` to the journal tables and back-filling it with a default account per user.

### D7. A separate feature slice: `journal/`

The journal is entirely **cold path**: `BigDecimal`, full POJO deserialization, normal logging,
standard transactional services. It shares nothing with `marketdata/core`. Its exchange clients
are **not** market-data adapters, because they are signed, per-user and low-volume. They reuse
`ExchangeWebClientFactory`, YAML base URLs, and (Binance only) the shared `WeightGuard` beans.

---

## 3. Open decisions (owner: you)

| # | Question | Recommendation |
|---|---|---|
| Q1 | Is the journal behind entitlement (paid), or free? | Gate it like `/api/rules`: `EntitlementService.hasAccess`, admins bypass. Live streams only run for users with access. |
| Q2 | A dedicated egress IP or proxy for journal exchange traffic? | Not for v1. D3 is enough at low user counts. Revisit when imports queue for minutes. |
| Q3 | Keys with **trade** permission: reject, or accept for read-only use? | Reject in v1: the journal only needs read access, and the UC-10 execution work can relax this. **Withdrawal permission is always rejected** (UC-9). |
| Q4 | Commission in non-stable assets (BNB, other coins) | Convert to USDT using the asset's 1m kline close at fill time (public, cheap). Until conversion is built, store those fees and show them separately rather than counting them as zero (§6.2). |
| Q5 | Screenshot storage (stage 5) | S3-compatible object storage (R2 or MinIO). Postgres `bytea` only if no bucket can be provisioned. |
| Q6 | MOEX through QUIK (Lua bridge) | Defer until the crypto journal ships and there is demand. Needs an ingest token, rouble handling, MOEX candles. |
| Q7 | Journal exchanges in the existing `Exchange` enum (adds `OKX`), or a separate `JournalExchange` enum? | Reuse `Exchange` if a constant with no `screener.exchanges.*` entry and no market-data adapter is harmless (check `ExchangesProperties` and the adapter configs). Otherwise a separate enum. Bybit arrives on `feature/abu_bybit_exchange` anyway. |

---

## 4. Architecture

### 4.1 Package layout

```
journal/
  assembly/      Fill, FundingRecord, AssembledPosition, PositionAssembler (pure, no Spring),
                 FundingAllocator, DustPolicy                     ← port of exchange_common.py
  stats/         JournalMetrics (pure), StatsService, StatsController  ← port of metrics.py
  connection/    JournalConnection (entity), ConnectionService, ConnectionController,
                 KeyCipher (AES-GCM), KeyPermissionCheck
  exchange/      JournalExchangeClient (SPI: testKey, permissions, discoverSymbols,
                 fetchFills, fetchFunding), JournalRequestBudget
    binance/     BinanceJournalClient (spot + futures signed REST)
    bybit/       BybitJournalClient
    okx/         OkxJournalClient
  importer/      ImportJob (entity), ImportJobService, ImportWorker, ImportController
  position/      JournalFill, JournalFunding, JournalPosition (entities), PositionRecomputer
                 (D1 + D2), PositionService, PositionController, ManualPositionService
  live/          (stage 4) PrivateStreamManager, per-exchange private stream sessions,
                 JournalChannel (FeedChannel)
  catalog/       (stage 5) strategies, errors, screenshots
config/JournalProperties.java   ← screener.journal.*
```

### 4.2 How it connects to existing code

| Existing piece | Use |
|---|---|
| `user/User` | `user_id` FK on connections, positions and jobs. Ownership is checked in every service method (`userId` from the JWT principal, never from the request). |
| `auth/`, `SecurityConfig` | `/api/journal/**` requires a JWT (it falls under "everything else", so no rule change is needed). |
| `entitlement/` | Per Q1, an `hasAccess` check in the journal controllers or a shared guard, as `/api/rules` does. |
| `error/ApiException` | All errors. New codes as needed (`KEY_PERMISSION_REJECTED`, `IMPORT_ALREADY_RUNNING`, `EXCHANGE_RATE_LIMITED`). |
| `config/AsyncConfig` | A new `journalImportExecutor` bean next to `emailExecutor`. |
| `marketdata/core/rest/ExchangeWebClientFactory` | WebClients for the journal clients. |
| `marketdata/adapter/binance/WeightGuard` | Shared per venue (D3). Needs a refactor in `BinanceAdapterConfig`. |
| `feed/FeedChannel`, `feed/Envelope`, `ws/` | Stage 4: a `JournalChannel` pushes per-user position updates over the existing `/ws`. It fits the `drain` / `collectUpdates` / `collectSnapshot` contract. Its snapshot is empty, because the REST API is the source of truth on (re)connect. |
| Flyway | `V16__create_journal.sql` (stage 2), later migrations for catalog, live and MOEX. |

### 4.3 Flows

**Import**: `POST /imports` → job row `QUEUED` → `ImportWorker` takes it → `discoverSymbols` →
for each symbol `fetchFills` (paged, budgeted) → insert fills (dedupe) → `fetchFunding` (futures)
→ insert funding → recompute every group that received new fills or funding → job `DONE` with
counts.

**Recompute** (`PositionRecomputer`, one transaction per group): load the group's fills from the
boundary (D1) ordered by `(executed_at, id)` → `PositionAssembler.assemble` → `FundingAllocator`
→ match to old positions (D2) → upsert positions and replace their fill slices → publish a
`JournalPositionsChangedEvent(userId, positionIds)` (used by the live push in stage 4).

**Live** (stage 4): private stream event → map to `Fill` → insert (a duplicate means it was
already applied, so stop) → recompute the group from the boundary → event → `JournalChannel`.

---

## 5. Data model (stage 2 migration, sketch)

Conventions follow the existing schema: UUID ids, `TIMESTAMPTZ` (millisecond precision is kept),
money as `NUMERIC(38,18)`, statuses as `VARCHAR` with `CHECK` constraints. Exchange trade and
order ids are `VARCHAR`, because MOEX ids have 19 digits and Bybit ids are UUIDs.

```sql
CREATE TABLE journal_connections (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    exchange         VARCHAR(16) NOT NULL,                 -- BINANCE | BYBIT | OKX
    market           VARCHAR(16) NOT NULL,                 -- SPOT | FUTURES (one connection = one market)
    name             VARCHAR(64) NOT NULL,
    api_key_enc      TEXT NOT NULL,
    api_secret_enc   TEXT NOT NULL,
    passphrase_enc   TEXT,                                 -- OKX only
    key_version      SMALLINT NOT NULL,                    -- which master key encrypted it (rotation)
    api_key_hint     VARCHAR(8) NOT NULL,                  -- last 4 chars, for display
    cashback_percent NUMERIC(9,4) NOT NULL DEFAULT 0,
    live_enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_journal_connection_name UNIQUE (user_id, name)
);

CREATE TABLE journal_fills (
    id               BIGSERIAL PRIMARY KEY,                -- also the tie-break for same-ms fills (insert order = API order)
    connection_id    UUID NOT NULL REFERENCES journal_connections(id) ON DELETE CASCADE,
    symbol           VARCHAR(32) NOT NULL,
    trade_id         VARCHAR(64) NOT NULL,
    order_id         VARCHAR(64),
    executed_at      TIMESTAMPTZ NOT NULL,
    side             VARCHAR(4)  NOT NULL,                 -- BUY | SELL
    price            NUMERIC(38,18) NOT NULL,
    qty              NUMERIC(38,18) NOT NULL,
    quote_qty        NUMERIC(38,18) NOT NULL,
    commission       NUMERIC(38,18) NOT NULL,
    commission_asset VARCHAR(16) NOT NULL,
    realized_pnl     NUMERIC(38,18),
    CONSTRAINT uq_journal_fill UNIQUE (connection_id, symbol, trade_id)
);
CREATE INDEX ix_journal_fills_group ON journal_fills (connection_id, symbol, executed_at, id);

CREATE TABLE journal_funding (
    id               BIGSERIAL PRIMARY KEY,
    connection_id    UUID NOT NULL REFERENCES journal_connections(id) ON DELETE CASCADE,
    symbol           VARCHAR(32) NOT NULL,
    external_id      VARCHAR(64) NOT NULL,                 -- exchange record id (tranId / transaction id / billId)
    paid_at          TIMESTAMPTZ NOT NULL,
    amount           NUMERIC(38,18) NOT NULL,              -- signed income
    asset            VARCHAR(16) NOT NULL,
    CONSTRAINT uq_journal_funding UNIQUE (connection_id, external_id)
);

CREATE TABLE journal_positions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    connection_id    UUID REFERENCES journal_connections(id) ON DELETE CASCADE,  -- NULL = manual
    market           VARCHAR(16) NOT NULL,
    symbol           VARCHAR(32) NOT NULL,
    direction        VARCHAR(5)  NOT NULL,                 -- LONG | SHORT
    status           VARCHAR(6)  NOT NULL,                 -- OPEN | CLOSED
    currency         VARCHAR(8)  NOT NULL,                 -- USDT (crypto), RUB (MOEX later); never summed together
    entry_at         TIMESTAMPTZ NOT NULL,
    exit_at          TIMESTAMPTZ,
    entry_price      NUMERIC(38,18) NOT NULL,
    exit_price       NUMERIC(38,18),
    quantity         NUMERIC(38,18) NOT NULL,
    quote_entry      NUMERIC(38,18),
    quote_exit       NUMERIC(38,18),
    gross_pnl        NUMERIC(38,18),
    fees             NUMERIC(38,18),
    funding          NUMERIC(38,18),
    net_pnl          NUMERIC(38,18),
    pnl_percent      NUMERIC(38,18),
    cashback         NUMERIC(38,18),
    notes            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_journal_positions_user_exit ON journal_positions (user_id, exit_at);
CREATE INDEX ix_journal_positions_group ON journal_positions (connection_id, symbol, market, entry_at);

-- The slice of each fill that belongs to a position. A reversing fill is split between the
-- closing and the opening position (the desktop app's position_fills rows). Rewritten on every
-- recompute of its group.
CREATE TABLE journal_position_fills (
    position_id      UUID   NOT NULL REFERENCES journal_positions(id) ON DELETE CASCADE,
    fill_id          BIGINT NOT NULL REFERENCES journal_fills(id) ON DELETE CASCADE,
    qty              NUMERIC(38,18) NOT NULL,
    quote_qty        NUMERIC(38,18) NOT NULL,
    commission       NUMERIC(38,18) NOT NULL,
    PRIMARY KEY (position_id, fill_id)
);

CREATE TABLE journal_import_jobs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    connection_id    UUID NOT NULL REFERENCES journal_connections(id) ON DELETE CASCADE,
    kind             VARCHAR(16) NOT NULL,                 -- FULL | SYMBOL | DEEP_SCAN
    symbol           VARCHAR(32),
    from_at          TIMESTAMPTZ NOT NULL,
    status           VARCHAR(16) NOT NULL,                 -- QUEUED | RUNNING | DONE | FAILED | CANCELED
    progress         VARCHAR(255),
    fills_added      INTEGER NOT NULL DEFAULT 0,
    positions_changed INTEGER NOT NULL DEFAULT 0,
    error            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_journal_job_active ON journal_import_jobs (connection_id)
    WHERE status IN ('QUEUED','RUNNING');
```

Deleting a connection cascades to its fills, positions and jobs, and the frontend asks for
confirmation first. Rotating keys is a `PATCH` and keeps everything.

Stage 5 adds `journal_strategies`, `journal_position_strategies`, `journal_errors` (with the
`is_approval` "no errors" marker and the default seed list from the desktop app),
`journal_position_errors`, `journal_screenshots`, and `journal_chart_drawings (user_id, symbol,
data JSONB)`. View settings (columns, period, layout) go in a `journal_user_settings (user_id,
data JSONB)` row so they follow the user across devices.

---

## 6. The assembly port

### 6.1 What is ported as-is

From `exchange_common.py`:
- Sort by time (here `(executed_at, id)`, which keeps the API order inside one millisecond,
  matching Python's stable sort).
- The first fill opens the position (BUY → LONG, SELL → SHORT, except as noted in §6.2).
- Same-side fills add to the position. Commission in the base asset reduces quantity
  (`_net_qty_after_base_fee`), and the quote cost is unaffected.
- An opposite fill closes `min(qty, open)` with its proportional commission. The mid-sequence
  close check uses **only** the ratio threshold (0.2% of the size just before this close).
- Reversal: the excess opens the opposite position **on futures only**. On spot the excess is
  dropped.
- End of the fill list: if there was exit activity and the remainder is dust (by ratio **or** by
  value ≤ $1 at the last price), finalize at the last fill time. Otherwise the position is open.
- `_finalize_position` formulas: average prices, `gross = sells − buys`,
  `net = gross − fees + funding`, `pnl% = net / quote_entry × 100`,
  `cashback = fees × cashback% / 100`.
- `apply_funding`: each record goes to the first position whose `[entry, exit ?? now]` window
  contains it, then closed positions get `net`/`%` recomputed. The unmatched sum is returned and
  logged at debug level.
- `DustPolicy` holds the ratio and the value threshold, and takes a currency (RUB's value
  threshold is 80 for MOEX later).
- Metrics (`metrics.py`), over closed positions only: total, win rate, net, gross profit and loss,
  profit factor (`null` when there are no losses), average win, loss and trade, expectancy, max
  drawdown, current streak (returned as `{sign, count}`; the frontend formats it), equity curve by
  exit time.

The bug notes in the comments are carried over next to the code they justify.

### 6.2 Intentional deviations (each has a test)

1. **A spot SELL with no open position is an orphan, not a SHORT.** Spot has no short selling.
   This is the same reasoning the desktop app already applies to reversals, applied to the first
   fill too. Orphans are counted and shown in the import summary ("N sells without a matching
   buy: import from an earlier date").
2. **Non-stable commissions are not zero.** Q4: converted at the 1m close of `<ASSET>USDT`, or
   until then stored as `fees_other` and shown as unconverted, with net PnL marked "excludes N BNB
   fees".
3. **Binance pagination by `fromId`**, not `lastTime + 1`. The desktop app's `time + 1` skips any
   fills that share the last millisecond of a full page. That happens when a market close produces
   a burst of fills in one millisecond.
4. **No "deleted on exchange" reconciliation** (D1). Not needed, since executions are final.

### 6.3 Numbers

`BigDecimal` throughout. Divisions (`avg price`, `pnl%`, the commission share) use
`MathContext(28, HALF_EVEN)`, Python's default `Decimal` context, so results match the desktop
app. Values are stored at scale 18. The parity tests compare at scale 12 to absorb the
difference: Python rounds sums and products to 28 significant digits, while Java's are exact.

---

## 7. Exchange access and rate limits

### 7.1 SPI

```java
interface JournalExchangeClient {
    Exchange exchange();
    KeyPermissions checkPermissions(JournalCredentials c);                 // test + permission scope
    Set<String> discoverSymbols(JournalCredentials c, Market m, Instant from);
    void fetchFills(JournalCredentials c, Market m, String symbol, Instant from, Consumer<List<Fill>> page);
    void fetchFunding(JournalCredentials c, String symbol, Instant from, Consumer<List<FundingRecord>> page);  // futures
}
```

`JournalCredentials` holds the decrypted key only for the duration of one job or one call. It is
never a Spring bean and never in a log line (`toString()` is overridden).

### 7.2 Per exchange

API facts marked *(verify)* must be checked against the current exchange docs during
implementation.

**Binance**
- Permissions: `GET /sapi/v1/account/apiRestrictions` (`enableReading`, `enableWithdrawals`,
  `enableSpotAndMarginTrading`, `enableFutures`).
- Futures fills: `GET /fapi/v1/userTrades`, weight 5. The first page uses `startTime`, later pages
  use `fromId`. *(verify: `fromId` cannot be combined with a time range, and time ranges are
  limited to 7 days.)*
- Futures discovery: the symbols in `GET /fapi/v1/income` over the period, plus symbols already
  in `journal_fills`. This finds delisted symbols too, as the desktop app does. The same calls
  return funding (`incomeType=FUNDING_FEE`), so it is one pass.
- Spot fills: `GET /api/v3/myTrades`, weight 20, paged by `fromId`. *(verify the
  `startTime`/`endTime` range limit, believed to be 24h.)*
- Spot discovery: spot has no account-wide trade history. Default set = assets with a non-zero
  balance (`GET /api/v3/account`) ∪ symbols already in the journal ∪ symbols the user lists. The
  full scan over every USDT pair is `DEEP_SCAN`: opt-in, budgeted, possibly minutes long, with
  visible progress.
- Bans: `-1003` and HTTP 418/429 → `WeightGuard` ban state → the job fails with "Binance limit
  reached, retry after HH:MM". It never retries during a ban.

**Bybit** (`/v5/execution/list`, funding via `/v5/account/transaction-log`; permissions via
`/v5/user/query-api` *(verify)*). Private limits are mainly per UID *(verify)*, so a simple
per-connection pacer is enough, plus a global IP guard well under the documented IP limit.

**OKX** (`/api/v5/trade/fills-history`, funding via `/api/v5/account/bills-history`; permissions
via `/api/v5/account/config` `perm` *(verify)*; needs a passphrase). Limits are per user id
*(verify)*, so a per-connection pacer is enough.

Pacing, page sizes and reserve fractions go in `screener.journal.exchanges.<exchange>.*`.

---

## 8. Key custody

- **Cipher**: AES-256-GCM, a random 96-bit nonce per value, stored as
  `base64(version ‖ nonce ‖ ciphertext+tag)`. The connection id is bound as associated data, so
  a ciphertext cannot be copied to another row.
- **Master key**: `JOURNAL_KEY_ENCRYPTION_KEY` (base64, 32 bytes), plus an optional
  `JOURNAL_KEY_ENCRYPTION_KEY_PREVIOUS` for rotation (decrypt with either, re-encrypt with the
  current key on next use). Startup fails if journal is enabled and the key is missing or the
  wrong length.
- **Adding or replacing keys** requires the user's password in the request (UC-9
  "re-authenticates"). The permission check runs before saving: withdrawal is rejected always,
  trading is rejected per Q3, and a key that cannot read is rejected.
- **Never** returned (responses show `exchange`, `market`, `name`, `apiKeyHint` "…a1b2"), never
  logged (no header logging on journal WebClients, `JournalCredentials.toString()` is
  overridden, request DTOs exclude secrets from `toString()`), never sent to the browser.
- An audit log line (INFO, no secrets) is written for key add, replace and delete.

---

## 9. Import jobs

- Executor `journalImportExecutor`: one worker per exchange (a keyed queue), so jobs for
  different exchanges run in parallel and jobs for the same exchange run one after another (D3).
- At most one active job per connection (the partial unique index), and a second request
  returns `409 IMPORT_ALREADY_RUNNING`.
- Progress goes in `progress` / `fills_added` and is updated per symbol. The frontend polls
  `GET /api/journal/imports/{id}` in v1, and stage 4 also pushes it over `/ws`.
- On startup, jobs left `RUNNING` become `FAILED` ("interrupted by restart"). Fills already
  inserted stay, and a re-run continues cheaply because of the dedupe.
- Kinds: `FULL` (discovery + all symbols from `from`), `SYMBOL` (one ticker; a bare ticker is
  completed to `…USDT` as the desktop app does), `DEEP_SCAN` (Binance spot, §7.2).
- A cashback change on a connection triggers a recompute of all its groups (local only, no
  exchange calls).

---

## 10. REST API (stage 2)

All under `/api/journal`, JWT required, entitlement per Q1. A contract doc goes in
`.claude/docs/for-frontend/journal.md` when stage 2 lands.

| Method | Path | Notes |
|---|---|---|
| GET | `/connections` | Masked |
| POST | `/connections` | `{exchange, market, name, apiKey, apiSecret, passphrase?, cashbackPercent, password}`, runs the permission check |
| PATCH | `/connections/{id}` | Name, cashback, live flag; keys only with `password` |
| DELETE | `/connections/{id}` | Cascades |
| POST | `/connections/{id}/test` | Re-runs the permission check |
| POST | `/connections/{id}/imports` | `{kind, from, symbol?}` → job |
| GET | `/imports/{id}`, `/imports?connectionId=` | Job status |
| GET | `/positions` | Filters: `from`, `to` (by exit; open positions always included), `currency`, `connectionId`, `symbol`, `status`, `direction`; paged; open first |
| GET | `/positions/{id}` | With fill slices |
| POST | `/positions` | Manual entry, computed with the same formulas |
| PATCH | `/positions/{id}` | Notes (any); trade fields (manual only) |
| DELETE | `/positions/{id}` | Manual only. Assembled positions come back on recompute, so deleting them is not offered. |
| GET | `/stats` | `from`, `to`, `currency` (required: dollars and roubles are never mixed), `connectionId?` → metrics + equity curve |
| GET | `/positions/export.csv` | Same filters as `/positions` |

Grouping by day, week or month and "Hide PnL" are frontend-only.

---

## 11. Live streams (stage 4)

- `PrivateStreamManager`: one private WebSocket per connection with `live_enabled` whose user has
  access. Each runs on a virtual thread, with reconnect backoff. It is separate from the
  market-data `StreamManager`, which is built for shared public streams.
- Binance: user data stream (`ORDER_TRADE_UPDATE` futures, `executionReport` spot). *(verify:
  Binance has been moving spot user-data streams from REST listen keys to a WebSocket API
  subscription.)* Bybit: `wss://stream.bybit.com/v5/private`, topic `execution`. OKX: private WS
  with login.
- After a reconnect: re-sync by REST every symbol with an open position or any event since
  the last confirmed message. Dedupe makes overlap harmless.
- During a manual import of the same connection, live events are still inserted (dedupe makes
  that safe), but the group recompute waits for the job to finish. That replaces the desktop
  app's "pause the stream" workaround.
- Bursts (a market close = dozens of fills in under a second) are collected per group for a short
  window (YAML, ~250ms) and recomputed once.
- Browser push: `JournalChannel implements FeedChannel` sends `JOURNAL_POSITION` messages
  (position upserts) to the owning user's sessions. Its snapshot is empty.
- Open-position live PnL (needs a mark price) can use the screener's own book mid for
  Binance/MEXC symbols when available. Otherwise it is left out in v1.

---

## 12. Stages and acceptance

| Stage | Content | Accepted when |
|---|---|---|
| **0. Groundwork** | Q1–Q7 answered. The `WeightGuard` bean refactor in `BinanceAdapterConfig` (no behaviour change for snapshots). `JournalProperties`. The parity harness (§13). | Existing tests green. The harness produces expected JSON for the scenario fixtures. |
| **1. Assembly core** | `journal/assembly`, `journal/stats`, pure Java and no I/O. | Every parity fixture matches the Python at scale 12. Every §6.2 deviation has a test with a hand-computed expected value. |
| **2. Binance journal** | Migration V16, connections + key custody, import jobs, Binance futures then spot, recompute + D2, positions/stats/manual API, frontend contract doc. | Importing twice adds nothing and keeps notes. Importing a later window, then an earlier one, gives the same positions as one full import. Keys never appear in responses or logs (test with a log appender). User A gets 404 for user B's ids. Under a simulated import, snapshot requests still get `≥ reserve` weight. |
| **3. Bybit + OKX** | Two clients behind the SPI. | Recorded API responses → expected fills → the same positions as the desktop app on the same data. |
| **4. Live** | `PrivateStreamManager`, re-sync, `JournalChannel`. | A fill appears in the open position within seconds without any action. Killing the socket mid-burst loses nothing after reconnect. |
| **5. Analysis** | Strategies, errors (seed list + "no errors"), screenshots (Q5), chart candles, "Analysis summary" stats, drawings, view settings. | "Analysis summary" figures match the desktop app on the same data. |
| **6. Optional** | MOEX/QUIK (Q6), import of the desktop app's account ZIP, multiple journal accounts (D6). | Decided later. |

The frontend (`screener-frontend-new`) is planned separately. Backend stage 2 is what unblocks
it.

---

## 13. Testing: the parity harness

The requirement that the web version match the desktop app is checked mechanically, not by eye:

1. `.claude/tools/journal-parity/` holds a copy of `exchange_common.py`, `metrics.py` and a
   minimal `domain/models.py`, plus `run_parity.py`. That script reads fixture files
   (`{fills, funding, symbol, market, cashbackPercent}`) and writes the expected positions and
   metrics as JSON. Run it with `python -I`, and keep the copies separate from the downloaded
   package.
2. Fixtures live in `src/test/resources/journal/parity/`. There is one per scenario named in the
   desktop app's comments and in their acceptance list: partial entries and exits, a futures
   reversal, a spot excess sell, dust by ratio, dust by value only at the end, a mid-sequence
   partial close that is refilled, base-asset commission on spot, funding inside and outside
   windows, cashback, and many fills in the same millisecond. Add anonymized real exports if the
   diary's author can provide them.
3. `PositionAssemblerParityTest` (parameterized) runs the Java code on each fixture and compares
   with the expected JSON. Fixtures for §6.2 deviations hold a hand-written expected file and a
   `"deviation": "<reason>"` field instead of Python output.
4. Integration tests (stage 2) use recorded exchange responses served by a mock server (the same
   approach as the existing WebClient tests), never live keys.

---

## 14. Risks

| Risk | Mitigation |
|---|---|
| Journal traffic starves or bans order-book recovery | D3: shared `WeightGuard`, a reserve, serialized jobs, narrow discovery. Q2 as an escape hatch. |
| A key leak | §8. Keys are read-only by policy, so a leak exposes history, not funds. |
| Spot discovery misses symbols the user traded but no longer holds | Shown in the UI: "add a ticker" and the opt-in deep scan. Known symbols accumulate over time. |
| Exchange API details changed since the desktop app was written | The *(verify)* items are checked first in stages 2–4. Recorded fixtures are refreshed at the same time. |
| Recompute cost for very active scalpers | Boundary recompute (D1), the `(connection_id, symbol, executed_at, id)` index, burst coalescing in live. Measure with a 50k-fill group before stage 4. |
