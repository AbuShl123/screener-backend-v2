# MEXC Spot Depth: Empirically Tested (Phase 0)

> **Empirically tested** on 2026-10-09 (~17:20–17:55 UTC) from a single residential IP, against
> `wss://wbs-api.mexc.com/ws` (`spot@public.aggre.depth.v3.api.pb@100ms@<SYMBOL>`) and
> `GET https://api.mexc.com/api/v3/depth`. This is Phase 0 of `.claude/plans/mexc-spot/mexc-spot-plan.md`.
> Where it disagrees with the docs-derived `mexc-api-contracts.md` §3.1, this file wins. Tools are in
> `.claude/tools/mexc-spot-capture/`. Frames were decoded with `pbwire.py`, a hand-written wire
> reader equivalent to `protoc --decode_raw` that also keeps wire order and byte offsets.

## TL;DR

| Question | Docs / expectation | Measured |
|---|---|---|
| Body field | `publicIncreaseDepths` (302) per `mexc-api-contracts.md` | **313 `publicAggreDepths`**, on 100% of pushes |
| Field numbers | per `mexcdevelop/websocket-proto` | **Match exactly.** Wrapper `channel=1`, `symbol=3`, `sendTime=6`. Body `asks=1`, `bids=2`, `eventType=3`, `fromVersion=4`, `toVersion=5`, `lastOrderCreateTime=6` (varint; not in the plan's list). Level `price=1`, `quantity=2` |
| `symbol` before or after the body | ? | **Before.** Wire order is always `channel, symbol, sendTime, body` |
| Version continuity | `fromVersion == prev toVersion + 1` | **Holds exactly.** 15,766 consecutive pairs over 8 symbols in two runs, 0 gaps, 0 overlaps |
| Snapshot `lastUpdateId` vs push ranges | aligned to push boundaries | **Not aligned.** 58 of 106 snapshots landed *inside* a push's range (BTC ~87%). Same as futures |
| Is `lastUpdateId` inclusive? | — | **Yes.** On boundary snapshots the push with `toVersion == V` always agrees 100% with the snapshot. `<` and `<=` are both correct, so keep `<` (decision 8) |
| Snapshot + pushes reproduce a later snapshot | — | **Yes**, on every comparable pair (46), including replays that start from a straddling snapshot. On the fixture pair it matches the **full** book |
| Snapshot `limit` | max 5000 | **Server cap is 2000 per side.** `limit` ≥ 2000 returns 2000; with no `limit` it returns 100 |
| Spot depth shares the futures 510 window | ? | **No.** 4 req/s spot + 4 req/s futures at the same time: 0 rejections |
| Spot WAF 403 blocks futures | ? | **Not established** (stopped on request, see item 4) |
| Spot ∩ futures | — | **533 matched on `baseCoin`, 549 on `baseCoinName`.** `baseCoin` is sometimes an internal id (`FILECOIN`, `ZKSYNC`). Decision: match on `baseCoinName`, keep futures `base = baseCoin` |

## 1. Wire layout

### Frame as delivered

`protoc --decode_raw`-style dump of a BTCUSDT push. Byte offsets are relative to the enclosing message:

```
1: "spot@public.aggre.depth.v3.api.pb@100ms@BTCUSDT"   # bytes 0..49
3: "BTCUSDT"                                           # bytes 49..58
6: 1791556844300                                       # varint, bytes 58..65
313 {                                                  # bytes 65..1211 (tag is 2 bytes: ca 13)
  1 { 1: "83067.57"  2: "0" }                          # asks …
  1 { 1: "83067.66"  2: "3.769948" }
  …
  2 { 1: "83067.55"  2: "5.786612" }                   # bids …
  3: "spot@public.aggre.depth.v3.api.pb@100ms"
  4: "83321028320"                                     # fromVersion, ASCII digits
  5: "83321028630"                                     # toVersion, ASCII digits
  6: 1791556844251                                     # lastOrderCreateTime, varint
}
```

### Observed on all 11,808 pushes of run A (and run B)

- **Top-level order is fixed**: `channel(1)`, `symbol(3)`, `sendTime(6)`, `publicAggreDepths(313)`.
  `symbolId(4)` and `createTime(5)` never appear. `symbol` sits before the body, so routing can stop
  at field 3 without touching the levels. `channel`'s suffix after the last `@` always equals `symbol`.
- **Body order is fixed**: all `asks`, then all `bids`, then `eventType`, `fromVersion`,
  `toVersion`, `lastOrderCreateTime`. **The versions come after the levels**, as on futures. A
  reader that checks the sequence first has to skip the level items by their length prefixes, which
  is cheap because each item is one length-delimited field, and then come back.
- **An empty side is absent**, not an empty repeated field. 8 body shapes were seen: both sides,
  asks only, bids only, each with one or many levels. No push had zero levels.
- **Levels have only `price` and `quantity`**, both strings. Unlike `PublicIncreaseDepthV3ApiItem`,
  there is no per-level `time` field. `quantity == "0"` removes the level, and a push can delete a
  level far from mid (e.g. a BTC bid at 9953.76).
- **Number formatting differs between pushes and snapshots.** Push prices and quantities are
  trimmed (`82990.7`, `1.7`): no push price in either run had a trailing zero. Snapshot prices are padded to
  tick precision (`0.1060`, `375.80`) on every symbol. Parsed to `double` they are equal. A book keyed
  by the raw string or by `BigDecimal` with scale would hold duplicate levels. No scientific notation
  was seen.
- **No unknown fields** at any level. `eventType` was always `spot@public.aggre.depth.v3.api.pb@100ms`.
- Frame size: min 152 B, p50 569 B, p99 4.1 KB, max 8.1 KB. Varint tags for 313 and the
  length-delimited body take 2 bytes; everything else takes 1.

## 2. Version continuity and the snapshot sync point

### Runs

| Run | Scope | Pushes | Snapshots |
|---|---|---|---|
| A, 300s | BTC, ETH (#0–1 by 24h quote volume, in spot ∩ futures), NEAR (#10), LINK (#50), APE (#100), TURBO (#200), CTK (#500), SAFE (#532 of 533) | 11,808 | 40 (`limit=5000`, every 60s) |
| B, 200s | BTC, ETH | 3,968 | 66 (`limit=2000`, every 6s) |

### Continuity

`fromVersion - prev toVersion` was **exactly 1 on every consecutive pair**. There were no
`from > to` pushes. `from == to` is common on illiquid pairs (single-commit pushes). Pushes are
aggregated, like futures: BTC spans p50 ≈ 100 versions (max 1134) and 64–72 levels. CTK spans p50 = 1.
Cadence is the 100ms window: the liquid p50 inter-arrival is 100ms. Illiquid pairs push only on change
(CTK max gap 18s; 13 of the 30 lowest-volume spot pairs pushed nothing in 90s).

### Snapshot alignment

For each snapshot `V`, the push that holds `V + 1`:

| | Boundary (`from == V+1`) | Inside (`from <= V < to`) |
|---|---|---|
| Run A (8 symbols) | 28 | 12 |
| Run B (BTC, ETH) | 20 | 46 |

So a snapshot often lands mid-aggregation, especially on busy books. The futures predicate covers it
without change:

```
toVersion   <  lastVersion       → IGNORE
fromVersion <= lastVersion + 1   → OK, lastVersion = toVersion
otherwise                        → DE_SYNCED
```

- **`lastUpdateId` is inclusive.** On all 28 run-A boundary snapshots, a push with `toVersion == V`
  exists and every level it carries (within the snapshot's range) equals the snapshot's quantity.
  So that push is already in the snapshot. Re-applying it (strict `<`) is harmless, and dropping it
  (`<=`) is also correct. **Keep `<`** so both MEXC strategies share one predicate.
- **A straddling push must be applied.** Its levels disagree with the snapshot on 10–50% of rows
  (the commits after `V`), which is why the predicate accepts `from <= V + 1 <= to`.
- **The stream runs ahead of REST.** The push holding `V + 1` usually arrived *before* the snapshot
  response (BTC/ETH by 130–430ms; snapshot latency ~250ms, one cold call 744ms). On illiquid pairs it
  arrived up to 1.9s after. Buffering must start before the request is sent, which `RECOVERING`
  already does. A snapshot ahead of every buffered push simply ignores them and syncs on the next live
  push.

### Replay

Snapshot `i`, plus every push with `toVersion > V_i`, applied as absolute upsert/delete, was compared
with each later snapshot that sits on a push boundary. A snapshot inside a range cannot be compared
exactly, because it holds part of a push.

- Run A: **25/25 OK** on the top 50 levels per side, 7 symbols (BTC had no boundary snapshot). ETH, NEAR and TURBO started
  from a *straddling* snapshot, which validates accepting it.
- Run B: **21/21 OK** (BTC 4, ETH 17).
- Fixture pair (SAFEUSDT, below): straddling snapshot + 120 pushes = the next snapshot on the **full
  book** (34 bids, 101 asks), exactly.

## 3. Snapshot `limit`

`GET /api/v3/depth?symbol=…&limit=N`. Reach is from mid to the last returned row. Latency is the
median of 3.

| Symbol (rank) | `limit` 1000: rows, bid / ask reach | `limit` 5000 → 2000 rows: bid / ask reach | KB (1000 → 2000) | ms (1000 → 2000) |
|---|---|---|---|---|
| BTCUSDT (#1) | 1000 / 1000, 1.75% / 1.58% | 2000 / 2000, **3.6% / 3.0%** | 51 → 102 | 278 → 312 |
| ETHUSDT (#0) | 1000 / 1000, 6.7% / 4.6% | 2000 / 2000, 26% / **8.3%** | 43 → 86 | 248 → 276 |
| NEARUSDT (#10) | 914 / 1000, book exhausted / 24% | 916 / 1894, full book | 35 → 52 | 260 → 262 |
| LINKUSDT (#50) | 734 / 1000, exhausted / 59% | full book | 31 → 47 | ~250 |
| APE, TURBO, SPACE, MOODENG, CTK, SAFE (#100–#532) | full book at 1000 (35–777 rows a side) | same | 3–21 | ~250 |

- **The cap is 2000 rows per side** (`limit` 2000, 2001, 3000, 5000, 6000 and 10000 all return 2000;
  1999 returns 1999). Without `limit` the default is 100.
- Only the two most liquid books fail to reach ±10% of mid, even at 2000 (BTC ~3%, ETH ask ~8%).
  From about rank 10 down, 1000 rows already return the whole book. Far rows on thin books are stale
  junk orders (ask reach in the millions of %), as on futures.
- Latency hardly depends on `limit`. Size roughly doubles from 1000 to 2000 on BTC/ETH only.
- **Recommendation: `depth-limit: 2000`.** It is the only value that improves the liquid books, it
  costs nothing measurable, and with a `max-visible-distance` of the order of futures' 1% even 1000
  would do. Whether `limit` changes MEXC's request weight was not tested; at the planned pace the
  limiter is far away anyway (§4).

## 4. REST budget

| Run | Outcome |
|---|---|
| Spot depth (`api.mexc.com`, BTCUSDT, `limit=2000`) 4 req/s **and** futures depth (`api.mexc.com`, BTC_USDT, `limit=1500`) 4 req/s, concurrently, 20s | 81 + 81, **all OK** |
| Spot burst, 40 keep-alive workers | 142 OK, then one 403 (Akamai) at t ≈ 5.8s (~24 req/s) |
| Probes every ~3s after that burst: spot `/api/v3/time`, futures depth on `api.mexc.com` and on `contract.mexc.com` | all 200 for 20s. The 403 did not grow into a block, so nothing could be observed |

- **Spot depth does not draw from the futures 510 window.** Futures alone trips at ~8 req/s within
  ~2s (`mexc-api-rate-limits-empirical.md` V2), and here 8 req/s combined produced no 510. The two
  fetchers can pace independently.
- **The cross-host WAF question was dropped on request**, after the one burst above. What is known:
  the futures fetcher already uses `api.mexc.com` (`venues.FUTURES.rest.base-url`), the same host as
  spot. The 2026-10-01 runs found a WAF block per IP and host-wide (`/api/v3/time` was blocked too).
  So a spot-triggered block would almost certainly stall futures snapshots as well. But the WAF fires
  at ~20–30 req/s sustained or ~130 requests in 1–2s, far above spot (planned ≤ 10 req/s) + futures
  (4 req/s). **No shared budget is needed.** Making the 403 cooldown shared across both fetchers
  would be cheap insurance, but it is optional.

## 5. Control frames and connection lifetime

All control replies are **text** frames shaped `{"id":0,"code":0,"msg":…}`. **`code` is 0 even on
rejection.**

| Event | Frame |
|---|---|
| Subscribe, all accepted | `{"id":0,"code":0,"msg":"<ch>,<ch>,…"}`. Channels come **in a different order from the request** (e.g. requested BTC, ETH, NEAR… → acked APE, TURBO, BTC…), so match by content, not position |
| Subscribe, partly rejected (unknown symbol, or lowercase `ethusdt`) | `{"id":0,"code":0,"msg":"Subscribed successful! [<ch>]. Not Subscribed successfully! [<ch>,<ch>].  Reason： Blocked! "}` |
| Subscribe beyond 30 per connection | same shape, `Reason： Exceeded maximum subscription limit! ` (`mexc-ws-limits-empirical.md`) |
| `{"method":"PING"}` | `{"id":0,"code":0,"msg":"PONG"}`, ~0.3s later |

- `Reason：` uses a full-width colon (U+FF1A), and there are two spaces before it. Lowercase symbols
  are rejected, so subscribe with the exchange's uppercase `symbol`.
- An all-rejected subscribe was not tried. Its `msg` may lack the `Subscribed successful! [` part,
  so key the WARN on `Not Subscribed successfully! [` alone.

| Connection | Outcome |
|---|---|
| Open, never subscribe, no PING | dropped at **33s** |
| Subscribe to 2 idle pairs (no pushes), no PING | dropped at **60.3s** |
| Subscribe to 2 idle pairs, PING every 20s | alive at 180s (end of test), every PING answered |

- The server **drops the TCP connection without a WebSocket close frame** (the client saw no close
  code). Expect java-websocket to report an abnormal close (1006) and the pool to reconnect.
- A connection whose 30 symbols are all quiet dies at 60s without PING. Even a busy connection should
  PING, because the "no data flow" rule is per subscription in the docs. Heartbeat: `TextPing`
  `{"method":"PING"}` at ~20s.
- The 24h connection lifetime was not tested.

## 6. Universe: spot ∩ eligible futures

Run at 2026-10-09 17:2x UTC (`universe.py`):

| Set | Count |
|---|---|
| Spot USDT pairs listed | 1491 (all `status "1"`) |
| Spot eligible (`status "1"` ∧ `isSpotTradingAllowed`) | 1417 (74 listed with trading not allowed) |
| Futures USDT perpetuals / eligible (non-TradFi, as `MexcInstrumentSource`) | 1090 / 582 |
| **Spot ∩ futures on `baseAsset == baseCoin`** | **533** |
| **Spot ∩ futures on `baseAsset == baseCoinName`** | **549** |

Spot symbols are always `baseAsset + quoteAsset`, and futures symbols always `baseCoin_quoteCoin`.
No eligible spot base appears twice.

**Naming mismatch: futures `baseCoin` is not always the ticker.** On 17 eligible contracts, `baseCoin`
(and so `symbol`) is an internal id, and `baseCoinName` is the real ticker:

| Futures `symbol` / `baseCoin` | `baseCoinName` (= spot `baseAsset`) |
|---|---|
| `FILECOIN_USDT` | `FIL` |
| `ZKSYNC_USDT` | `ZK` |
| `PUMPFUN_USDT` | `PUMP` |
| `MONAD_USDT` | `MON` |
| `TRUMPOFFICIAL_USDT` | `TRUMP` |
| `LUNANEW_USDT` | `LUNA` |
| `OPENLEDGER_USDT`, `SOONNETWORK_USDT`, `AEON1_USDT`, `AIGENSYN_USDT`, `PHAROS_USDT` | `OPEN`, `SOON`, `AEON`, `AI`, `PROS` |
| `BIANRENSHENG_USDT`, `LONGXIA_USDT`, `NIULAI_USDT`, `HAJIMI_USDT`, `WOTAMALAILE_USDT` | `币安人生`, `龙虾`, `牛来`, `哈基米`, `我踏马来了` |
| `SPORTFUN_USDT` | `FUN` (no spot `FUNUSDT`) |

16 of these have a spot pair under `baseCoinName` and none under `baseCoin`. No two eligible contracts
share a `baseCoinName`, and matching on it loses nothing that `baseCoin` finds.

- **For spot discovery: match on `baseCoinName`.** That is 549 pairs, not 533. On 2026-10-09 the
  spot and futures prices of all 16 extra pairs agreed within 1%, so each is the same coin. The
  match only decides inclusion: a spot instrument takes `nativeSymbol`, `base` and `quote` from the
  spot row, and spot symbols are unique, so nothing else depends on it.
- **The futures venue keeps `base = baseCoin`, on purpose.** These contracts therefore stay
  `FILECOINUSDT`, `TRUMPOFFICIALUSDT` and so on, next to `FILUSDT` and `TRUMPUSDT` on MEXC spot, and a
  Binance-written `FILUSDT:FUTURES` rule does not reach them. Switching futures to `baseCoinName` was
  considered and rejected as not worth its cost:
  - It is not a clean fix. Against Binance futures (2026-10-09) it fixes 12 of the 17 (FIL, ZK, PUMP,
    TRUMP, MON, SOON, OPEN and the five CJK names, all price-verified as the same coin) but breaks 3
    that match today, because Binance itself uses the long id: `AIGENSYNUSDT`, `SPORTFUNUSDT`,
    `PHAROSUSDT`. `AEON1` and `LUNANEW` match neither way (Binance lists `LUNA2USDT`).
  - `baseCoinName` is not unique by construction. It is unique today across every MEXC USDT
    perpetual in any state, but MEXC introduces internal ids precisely when display tickers clash. Two
    futures instruments with one `symbol` would share a `feedKey`, and so classification state, so the
    switch would need a uniqueness fallback in discovery.
  - It renames live `ruleKey`s, so any stored `FILECOINUSDT:FUTURES` rule would stop applying.
- **CJK spot symbols.** Matching on `baseCoinName` brings in five spot pairs whose `symbol` is not
  ASCII: `龙虾USDT`, `牛来USDT`, `币安人生USDT`, `我踏马来了USDT`, `哈基米USDT`. The binary routing
  key must be decoded as UTF-8, and the REST `symbol` parameter percent-encoded. A subscribe to a CJK
  channel was not tested.
- **Multiplier-prefixed contracts** (`1000BONK`, `1000BTT`, `1000RATS`, `1000000BABYDOGE`,
  `1000000MOG`) have no spot match, because spot lists `BONK`, `BTT` and so on. Binance's rule
  (`spot symbol ∈ futures symbols`) drops these the same way, so leave them out.
- 22 more eligible futures bases (`AUDIO`, `BAND`, `THETA`, `TRB`, `GMT`, …) are simply not listed
  on MEXC spot, and 5 (`BONER`, `OTC`, `PROM`, `PTB`, `RAVE`) are listed with
  `isSpotTradingAllowed: false`.
- TradFi inheritance removes 4 spot bases that have an ineligible futures contract: `4STOCK`, `BRL`,
  `EUR`, `EWT`.
- `USDCUSDT` is in the intersection (#2 by volume). `screener.discovery.excluded-symbols` already
  drops it.

## Fixtures: `src/test/resources/mexc/spot/`

| File | Content |
|---|---|
| `push-btcusdt.bin` | one raw frame, 979 B: `fromVersion 83332997012`, `toVersion 83332997062`. 13 asks + 30 bids, several `"0"` deletes (e.g. the bid at `82567.45`) |
| `push-paidusdt-bids-only.bin` | 298 B, bids only (`asks` field absent): 8 levels, versions 281293249–281293259 |
| `push-ionqonusdt-asks-only.bin` | 814 B, asks only (`bids` field absent): 47 levels, versions 256003640–256003687 |
| `snapshot-mintusdt-44408969.json` | `/api/v3/depth` body (whole book: 60 bids, 298 asks), re-serialized compactly with key order kept: `lastUpdateId` (JSON number), `bids`, `asks` (`[["price","qty"],…]`, padded), `timestamp` |
| `pushes-mintusdt-44408968-44409143.frames` | the 120 consecutive MINTUSDT pushes from the one that **straddles** the snapshot above (`[44408968, 44408970]`) through `toVersion 44409143`, each stored as `[4-byte big-endian length][frame bytes]` |
| `snapshot-mintusdt-44409143.json` | the next snapshot, on a push boundary. The first snapshot + the 120 pushes equals this one exactly, on the full book |

The last three files make an end-to-end sync test (`MexcSpotSyncStrategyTest`, `Capture`). It starts
from a straddling snapshot, must accept the first push (`from 44408968 <= V+1 <= to 44408970`), sees
no gaps, and ends equal to the second snapshot.

## Not tested

- Whether a spot WAF 403 stalls futures snapshots (§4, dropped on request).
- Whether `limit` changes the request weight on spot.
- A subscribe where every channel is rejected (its `msg` shape).
- A subscribe to a CJK symbol's channel (e.g. `spot@public.aggre.depth.v3.api.pb@100ms@龙虾USDT`), and
  how the wrapper's `symbol` field encodes it (expected UTF-8).
- The 24h connection lifetime, and behavior across a reconnect: whether the first push after
  resubscribing continues the range or jumps. A jump fails the gap check and resyncs either way.
- Long runs (hours) and the `10ms` channel variant.
