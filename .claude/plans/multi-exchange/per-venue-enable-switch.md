# Per-Venue Enable Switch

**Created**: 2026-10-10, branch `feature/mexc-spot`. Backend only.

Today `screener.exchanges.<exchange>.enabled` turns a whole exchange on or off; there is no way to
run, say, MEXC spot without MEXC futures. This plan adds an `enabled` flag per venue.

---

## Semantics

```yaml
screener:
  exchanges:
    mexc:
      enabled: ${MEXC_ENABLED:false}          # master switch
      venues:
        SPOT:
          enabled: ${MEXC_SPOT_ENABLED:true}
          ...
        FUTURES:
          enabled: ${MEXC_FUTURES_ENABLED:true}
          ...
```

- Exchange-level `enabled: false` → every venue of the exchange is off, whatever its own flag says.
- Exchange-level `enabled: true` → each venue's own `enabled` decides.
- A venue without the flag is enabled, so existing YAML keeps its meaning.
- A disabled venue is never registered: it has no instruments in `InstrumentRegistry`, no book
  slots, no entries in the universe event, no WebSocket pool, and its symbols do not appear in
  `/api/tickers` (which reads the registry).
- A disabled venue **keeps its YAML block**. Discovery may still need its `rest` config (see
  below), so a venue is turned off with `enabled: false`, never by deleting its block.
- Switching is restart-only, like the exchange flag today.

## Discovery: cross-venue inclusion rules

Both adapters select spot as a subset of futures (`BinanceInstrumentSource`: spot symbol ∈
futures; `MexcInstrumentSource`: spot base ∈ futures `baseCoinName`). With futures disabled and
spot enabled:

- The source **still fetches futures** and applies the spot-requires-futures rule exactly as
  today.
- Core then **drops the disabled venue's list**: it is never registered or streamed.

So each venue's universe is the same whatever the other venue's switch says. The switches stay
independent: disabling futures changes nothing about spot's universe, its load, or its connection
sizing (MEXC spot's `max-connections` is sized for the futures-filtered set).

Costs:
- One extra REST call per universe refresh (every 4h): negligible.
- Spot discovery still depends on the futures REST endpoint being healthy. If that fetch fails,
  the source fails as a whole and spot **retains** its previous universe; it is never wiped. This is
  the same coupling that exists today.

The reverse case (spot disabled, futures enabled) also still fetches spot, which futures does not
need. Binance's spot `exchangeInfo` is large, but at one call per 4h it is not worth a source-side
branch. See *Later* for the optional refinement.

---

## Steps

### 1. Config: `ExchangesProperties`

- Add `@DefaultValue("true") boolean enabled` to `VenueProperties`.
- `isEnabled(Venue)` becomes: exchange block present ∧ exchange `enabled` ∧ venue block present ∧
  venue `enabled`.
- Update javadocs on `isEnabled`, `ExchangeProperties.enabled` and `VenueProperties` (master switch
  vs venue switch; a disabled venue keeps its block).
- YAML: add `enabled: ${<EXCHANGE>_<MARKET>_ENABLED:true}` under each venue in `application.yml`.
  Mirror it in `application-local.yml` by hand (gitignored).

`isEnabled` is already the single place every enable check goes through. `StreamManager`
(no pool for a disabled venue), `StreamProtocolRegistry` (binding required only for enabled
venues) and `maxVisibleDistances` all call it per venue, so they follow automatically.

### 2. Discovery: `InstrumentUniverseService`

Filter in core; no change to the `InstrumentSource` interface. Sources stay unaware of the switches.

- Remove the "only some are enabled" `IllegalStateException` in `enabledSources`.
- `SourceHandle` carries two sets:
  - `venues`: the source's full claim, used by `validate` (the result map must still cover every
    claimed venue, so the source contract is unchanged).
  - `enabledVenues`: the enabled subset.
- A source with zero enabled venues is skipped, as today. Log which venues are skipped when a
  source is only partly enabled.
- `accept`: put only `enabledVenues` into `fresh`, and log counts for those only.
- `retain`: add only `enabledVenues` to `retained`.
- `validate`'s empty-universe guard and `apply`'s added/removed diff need no change: a disabled
  venue never enters `activeByVenue`.

### 3. Adapter configs

No functional change. A disabled venue's beans (sync strategy binding, snapshot queue, stream
binding, REST client) are still built, which already happens today for a disabled exchange.
Update the `MexcAdapterConfig` javadoc ("Both venues are therefore enabled together, by
`mexc.enabled`") and the matching wording in `BinanceAdapterConfig` if any.

### 4. Tests

- `ExchangesPropertiesTest`:
  - venue `enabled: false` under an enabled exchange → that venue off, the sibling on;
  - exchange off overrides venue on;
  - missing venue flag → enabled.
- `InstrumentUniverseServiceTest`: replace "a source whose venues are only partly enabled throws"
  with:
  - futures disabled, spot enabled → only spot registered, event carries only spot;
  - partly enabled source fails → spot retains its previous universe, nothing removed;
  - partly enabled source returns a map missing the disabled venue → still rejected (contract
    unchanged).
- The ~15 test call sites that build `VenueProperties` positionally need the new argument.

### 5. Docs

- `CLAUDE.md` module map: "both venues share one `MEXC_ENABLED` switch" → exchange master switch
  plus per-venue switches.
- Javadocs: `InstrumentUniverseService` (class doc, *Sources* section), `InstrumentSource` (a
  source may be partly enabled; it always fetches all its venues).
- `.claude/docs/multi-exchange-progress.md`: describe the per-venue switch and the discovery
  behavior above.

**Pass**: full test suite green; boot with `MEXC_FUTURES_ENABLED=false`: MEXC spot `SYNCED` with
its usual universe size, no MEXC futures pool, no MEXC futures symbols in `/api/tickers`.

---

## Later (optional)

- Let a source skip a fetch it does not need, e.g. `fetch(Set<Venue> enabled)` so Binance
  futures-only skips spot `exchangeInfo`. Only worth it if the call ever becomes costly.
- Make a disabled venue's pipeline beans conditional, so no snapshot queue or binding is built for
  it.
