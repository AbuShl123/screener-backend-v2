# Trade Streams Transport — Implementation Plan

Phase 1 of `.claude/plans/trade-alerts-plan.md`, narrowed: wire Binance spot and futures `aggTrade`
streams through the transport and the Disruptor. **No decoder, no detection.** Trade frames are
routed, published to the shard ring, counted, and dropped by an explicit `TRADE` arm in the
consumer. The decoder SPI moves to Phase 2.

**Read first**: `.claude/docs/market-data-pipeline-architecture.md` §13 (the recipe this follows).

**No core transport edit.** `StreamManager`, `ConnectionPool`, `StreamConnection`,
`SubscriptionIndex`, the ring, `MarketEvent`, the publisher, shard routing and the health log stay
as they are. The health log gets its `trade/s` column without an edit.

---

## 1. Binance facts (verified 2026-11-17)

### 1.1 Futures endpoints are split by path

USD-M futures routes streams by URL path. A stream on the wrong path is **acked with
`{"result":null,"id":N}` and then stays silent**. No error frame is sent. Probe result, one
`SUBSCRIBE` per row, 6 s listen:

| Futures URL | `btcusdt@depth@500ms` | `btcusdt@aggTrade` |
|---|---|---|
| `wss://fstream.binance.com/public/ws` | data | ack, no data |
| `wss://fstream.binance.com/market/ws` | ack, no data | data |
| `wss://fstream.binance.com/ws` (legacy) | data | ack, no data |
| `wss://fstream.binance.com/ws/market` | data | ack, no data |

- Depth belongs on **`/public/ws`**, aggTrade on **`/market/ws`**.
- Legacy `/ws` serves public streams only. Binance announced its retirement for 2026-04-23. It
  still works, but we stop depending on it. `/ws/market` is the same legacy endpoint (`market` is
  read as a stream name), not a routed path.
- Because of the silent ack, a wrong URL shows up only as **0 frames/s** in the health log. That
  is the check after any URL change.

Spot (`wss://stream.binance.com/ws`) has no split: depth and aggTrade both stream there.

### 1.2 Subscribe frame

Same shape as depth, topic `<lower-case symbol>@aggTrade` on both venues:

```json
{"method":"SUBSCRIBE","params":["btcusdt@aggTrade","ethusdt@aggTrade"],"id":1}
```

### 1.3 Data frames

Spot:

```json
{"e":"aggTrade","E":1794857964130,"s":"BTCUSDT","a":4120467472,"p":"64826.00000000",
 "q":"0.02711000","f":6870090619,"l":6870090619,"T":1794857964129,"m":true,"M":true}
```

Futures (`"a"` comes before `"s"`; extra `nq` and `st` fields):

```json
{"e":"aggTrade","E":1794860803575,"a":3528412961,"s":"BTCUSDT","p":"65079.50","q":"0.006",
 "nq":"0.006","f":8285985083,"l":8285985083,"T":1794860803450,"m":true,"st":1}
```

Routing needs no change. `BinanceStreamProtocol.route` tells frames apart by the first key
(`{"result"` ack, `{"error"` error, otherwise data), and every aggTrade frame starts `{"e":"aggTrade"`.
It then reads `"s":"`. `"st":1` does not match that needle (no `":"` after `st`), and the order of
`"a"` and `"s"` does not matter to `indexOf`.

### 1.4 Limits

1024 streams per connection on both venues. Incoming messages: 5/s per connection on spot, 10/s
on futures. 300 connection attempts per 5 minutes per IP. A TRADE pool of one connection per venue
with two subscribe frames is far below every limit.

---

## 2. Stage 1 — move futures depth to `/public/ws`

A config-only change, verified on its own before trades are added, so a depth regression cannot
hide behind the new stream.

- `application.yml`: `BINANCE` → `FUTURES` → `streams.DEPTH.stream-url` becomes
  `wss://fstream.binance.com/public/ws`.
- `application-local.yml` is gitignored and sets its own futures depth URL. The owner changes it
  by hand.
- Docs: `CLAUDE.md` (the Streams bullet names `fstream.binance.com`) and any Binance futures URL in
  `.claude/docs/multi-exchange-progress.md`.

**Verify**: local run. The health log's `binance/futures` row shows the same `synced` count and a
`depth/s` rate like before (about 2 per symbol per second), and no resync storm.

---

## 3. Stage 2 — wire the `TRADE` streams

### 3.1 `StreamKind.TRADE`

`marketdata/StreamKind`: add `TRADE` with a Javadoc line. It carries executed trades (Binance
`aggTrade`): events, not state. No sync and no REST recovery. Update the class Javadoc where it
implies `DEPTH` is the only kind.

### 3.2 `BinanceStreamProtocol`: channel per instance

The topic suffix stops being derived from the market in the constructor. The channel names stay
fixed inside the protocol class (CLAUDE.md: "each channel name is fixed in its `StreamProtocol`"),
exposed through two static factories:

```java
public static BinanceStreamProtocol depth(Venue venue, StreamProperties props)    // "@depth" / "@depth@500ms"
public static BinanceStreamProtocol aggTrade(Venue venue, StreamProperties props) // "@aggTrade"
private BinanceStreamProtocol(Venue venue, StreamProperties props, String topicSuffix)
```

- `subscribeFrame`, `routingKey`, `route` and `heartbeat` are unchanged.
- Update the class Javadoc and the comment in `route` that says "every depth frame" so they cover
  both channels.

### 3.3 `BinanceAdapterConfig`: two more bindings

- Rename `binanceSpotStreamBinding` / `binanceFuturesStreamBinding` to
  `binanceSpotDepthStreamBinding` / `binanceFuturesDepthStreamBinding` (they now use
  `BinanceStreamProtocol.depth(...)`).
- Add `binanceSpotTradeStreamBinding` / `binanceFuturesTradeStreamBinding`, binding
  `new StreamKey(venue, StreamKind.TRADE)` to `BinanceStreamProtocol.aggTrade(venue,
  exchanges.stream(key))`.
- `exchanges.stream(key)` throws when the block is missing, so both venues ship a `streams.TRADE`
  block (3.4). The binding exists even while the stream is off; `StreamProtocolRegistry` accepts a
  binding for a disabled stream.
- Update the class Javadoc where it lists what the config registers.

### 3.4 YAML

`application.yml`, under each Binance venue's `streams`:

```yaml
# SPOT
            TRADE:
              enabled: ${BINANCE_SPOT_TRADE_ENABLED:false}
              stream-url: wss://stream.binance.com/ws
              max-streams-per-connection: 1024
              min-connections: 1
              max-connections: 2
              subscribe-chunk-size: 400
              heartbeat-interval-seconds: 120
# FUTURES
            TRADE:
              enabled: ${BINANCE_FUTURES_TRADE_ENABLED:false}
              # aggTrade is served only on /market; on /public or legacy /ws it is acked and stays silent.
              stream-url: wss://fstream.binance.com/market/ws
              max-streams-per-connection: 1024
              min-connections: 1
              max-connections: 2
              subscribe-chunk-size: 400
              heartbeat-interval-seconds: 120
```

- `min-connections: 1` puts the whole venue on one connection (`ConnectionPool.connectionCount`:
  `ceil(n / 1024)` = 1, clamped to `[min, max]`). This is a starting point. The final fan-out is
  decided from live data.
- Off by default. Spring merges map keys across profile files, so the local profile inherits these
  blocks. A local run turns them on with the two environment variables.

### 3.5 `MarketEventHandler`: explicit `TRADE` arm

```java
case DEPTH -> onDepth(slot, event);
// Routed and counted (processed, per-stream frame counter); decoding and detection arrive in Phase 2.
case TRADE -> { }
default -> noteUnhandledKind(event.stream);
```

- An explicit arm instead of letting `default` drop TRADE. `default` logs a WARN every minute and
  exists to catch a kind that has no arm by mistake. Shipping with a warning we expect would teach
  everyone to ignore it.
- Update the `default` comment ("Unreachable while DEPTH is the only kind" → unreachable while
  every kind has an arm).
- `event.clear()` still runs after the switch, so the ring slot drops the frame reference.

---

## 4. Tests

| Test | Change |
|---|---|
| `BinanceStreamProtocolTest` | Build instances through the new factories. Add: spot and futures `@aggTrade` subscribe frames; the spot and futures aggTrade frames from §1.3 route to their ids (the futures one covers `"a"` before `"s"` and the `"st"` field); an aggTrade frame for an unsubscribed symbol is `UNKNOWN`. |
| `BinanceAdapterConfigTest` | Renamed bean methods. Add: both Binance venues have a TRADE binding with its own `BinanceStreamProtocol`, distinct from the venue's depth protocol. |
| `TestVenueProps` | The venue builder can add a stream block for any kind (today it only knows `DEPTH`). |
| `MarketEventHandlerTest` | A `TRADE` event does not reach the sync strategy or the classifier, is counted in `processed()`, and is cleared. |
| `ExchangesPropertiesTest` | The shipped-YAML test also binds the TRADE blocks: both Binance venues have one, disabled by default. If useful, the "stream block enabled: false" case can now use a disabled TRADE block on an enabled venue (the comment at line 153 says it could not before). |

Run `-Dtest=BinanceStreamProtocolTest,BinanceAdapterConfigTest,MarketEventHandlerTest,ExchangesPropertiesTest,StreamProtocolRegistryTest`,
then the full suite: only the known `contextLoads` error is expected.

---

## 5. Verification (local run)

With `BINANCE_SPOT_TRADE_ENABLED=true` and `BINANCE_FUTURES_TRADE_ENABLED=true`:

1. Startup log: one `[BINANCE_SPOT/TRADE]` and one `[BINANCE_FUTURES/TRADE]` connection open and
   subscribe the venue's full instrument list (two frames each at chunk 400).
2. Health log: `trade/s` is non-zero on both Binance rows. Depth `synced` counts and `depth/s`
   rates match a run without trades.
3. Ring free stays near full on every shard. Record the peak `trade/s` seen, ideally during a
   volatile period. That is the input for Phase 2.
4. No `No handler arm for stream kind` WARN and no `Data frame for an unsubscribed routing key`
   WARN.
5. With both variables unset, no TRADE connection opens and the health log's `trade/s` column
   shows `0`.

---

## 6. Docs

- `CLAUDE.md`: Module Map and Streams/Disruptor bullets: `StreamKind` has `TRADE`, bound for
  Binance only, with Binance futures streams split across `/public/ws` (depth) and `/market/ws`
  (aggTrade).
- `.claude/docs/market-data-pipeline-architecture.md`: §12 "Trade streams" row (transport done,
  decoder/state/arm logic pending); the `MarketEvent.stream` row ("`DEPTH` today").
- `.claude/docs/multi-exchange-progress.md`: the transport row ("only `DEPTH` bound") and the
  `StreamKind` note, plus the Binance futures URLs.
- `.claude/plans/trade-alerts-plan.md`: Phase 1 row: transport only, the decoder SPI moves to
  Phase 2.
- `.claude/plans/team-plans/spike-alerts-plan.md` §2.2 says the futures `/market` "`/ws` variant
  does not work". The probe shows `/market/ws` works for aggTrade. Leave a one-line correction
  for the klines work.

---

## 7. Out of scope

- Trade decoder SPI, trade state in `InstrumentSlot`, detection, delivery (Phases 2–4).
- Bybit and MEXC trade streams (Phase 5).
- Final TRADE connection fan-out; decided from live data.
- Raw-frame capture for sweep coalescing (Q5); done when the detection algorithm starts.
