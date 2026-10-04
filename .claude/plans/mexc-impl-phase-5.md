# MEXC Futures — Phase 5: Package Restructure and Wrap-up

**Created**: 2026-10-04, branch `feature/multi-exchange`. Follows Phases 0–4 of
`.claude/plans/mexc-impl-plan.md` (all done and verified live). Backend only: the frontend's
`exchange: "MEXC"` handling is a separate task.

Steps run in order. Each one is its own commit.

---

## 1. Package restructure (no behavior change)

Today core and adapter code sit side by side under `exchange/`, and only the names `binance` and
`mexc` tell them apart. Rename the module to `marketdata` and split it into identity, contracts,
core and adapters:

```
marketdata/
  Exchange, Market, Venue, Instrument, InstrumentRegistry,
  InstrumentUniverseService, InstrumentUniverseChangedEvent
  spi/
  core/
    stream/  ingress/  book/  recovery/  rest/  health/
  adapter/
    binance/  (+ dto/)
    mexc/     (+ dto/)
```

- **Root**: identity and the universe, which is what `analysis`, `feed`, `ws`, `config`,
  `monitoring` and `ticker` import. It stays at the root so those imports stay short and
  meaningful.
- **`spi/`**: the contract between core and the adapters. It sits beside both, inside neither.
- **`core/`**: the venue-agnostic pipeline.
- **`adapter/`**: one package per exchange. Singular, to match `book` and `stream`.
- **Name**: `marketdata`, not `livemarket`. The module isn't all hot path: discovery, the REST
  clients and the universe service are cold.

**Rules**
- Move every existing package whole, never split one, so package-private access survives.
- Use IntelliJ's Move Package. Then update the string references the IDE won't catch:
  - logger names in `application.yml` / `application-local.yml` (the local file is gitignored,
    so edit it by hand)
  - `CLAUDE.md` (module map, hot-path rules)
  - `.claude/docs/` (paths)
  - memory notes
- Leave the old plans as they are; they're historical.
- **Pass**: the full test suite is green and the app boots with all three venues `SYNCED`.

**Optional, decide when doing it**: fold `ticker/` (universe refresh scheduler +
`/api/tickers`) into `marketdata/`. `monitoring/` stays where it is, since it's an admin API.

## 2. Live correctness check (contract size)

The health logs prove MEXC books stay in sync, not that their quantities are right. A wrong
contract-size multiplier would put every MEXC level off by ~10⁴ while the logs stay perfectly
healthy.

- [ ] Compare BTCUSDT's top levels on MEXC and Binance through `/api/monitoring/orderbook`: the
      notional of the large walls should be the same order of magnitude. Spot-check one MEXC
      level against MEXC's website.
- [ ] The global feed carries both `MEXC:FUTURES:…` and `BINANCE:FUTURES:…` entries.

## 3. Enable MEXC by default

- [ ] `application.yml`: `enabled: ${MEXC_ENABLED:true}`, and update the comment above it.

## 4. Progress doc rewrite

Write `.claude/docs/multi-exchange-progress.md` against the new paths:
- [ ] Phase table.
- [ ] A MEXC sync + recovery section beside the Binance one.
- [ ] §5 "what adding an exchange requires".
- [ ] Decision log: #12 "Bybit is venue #2" is superseded.

## 5. Long run (recommended, not blocking)

- [ ] A multi-hour run, ideally from the server's IP. It covers what the 25-minute local run
      couldn't:
  - **A dropped MEXC socket.** Expected: 300 books back in ~75s.
  - **The firewall.** Whether Akamai treats a datacenter IP differently; every rate measurement
    so far came from the dev box.

---

## Carried open decisions

- **D1 — turnover filter** for the MEXC universe. It only affects startup time and memory.
- **D2 — global tier calibration** for MEXC liquidity. A product decision.

## Inputs for the MEXC Spot plan (not this phase)

- **Binary frames.** The pipeline is text end to end: `DepthEvent.rawJson` is a `String`,
  `StreamProtocol.route(String)`, and `StreamConnection.onMessage(ByteBuffer)` only warns.
  Protobuf spot is the first change to the adapter contracts since P2, and the plan's central
  design question.
- **Shared rate limit?** Check whether spot depth (`/api/v3/depth` on `api.mexc.com`) shares the
  per-IP window with futures depth. If it does, two fetchers each pacing at 4 req/s would trip
  it, and the per-venue fetcher model would need a shared budget per exchange.
- **REST client.** Spot gets its own `MexcSpotRestClient` (decision 3 of the main plan).
- **Universe policy.** Binance spot is limited to symbols that also trade as futures (spot ⊆
  futures). MEXC spot needs its own rule.
