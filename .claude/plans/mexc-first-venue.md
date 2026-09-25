# MEXC — The First Second Venue (Transport & Discovery First)

**Status**: agreed plan, no code written yet.
**Branch**: `feature/multi-exchange`, on top of `43546b4`.
**Written**: 2026-09-14.

**Goal**: MEXC futures instruments are discovered, their book slots allocated, their depth streams
subscribed at startup, and their frames flow into the Disruptor where a consumer counts them —
with Binance running exactly as healthy as it does today. MEXC spot follows afterwards as a
separate, clearly-fenced step.

**Explicit non-goal**: no order-book storage, no sync state machine, no snapshot fetching, no
classification for MEXC. A MEXC book slot exists and stays `PENDING` forever, by design.

**Related reading**

| File | Relationship |
|---|---|
| `.claude/plans/multi-exchange-architecture-vision.md` | The north star. This plan reorders its §12 phasing; §5 (transport seam) and §9 (discovery) are the chapters it implements. |
| `.claude/docs/multi-exchange-progress.md` | Current state of the tree. Its §8 "next actions" list is superseded by this plan for the next few commits. |
| `external-docs/mexc/mexc-api-contracts.md` | MEXC protocol notes from 2026-07-29. Re-verified 2026-09-14 — see §3 below for what changed and what held. |

---

## 1. The decision, and the answers that shaped it

The architecture vision phases this work as P2 step 3 (`SnapshotRequestQueue`) → step 4
(`RequestBudget`) → step 5 (`StreamProtocol`) → P3 (robustness) → P4 (Bybit). **We are deviating:
transport and discovery generalisation come first, driven by a real second venue, and the deferred
P2/P3 debt waits.**

The rationale is uncertainty reduction. The remaining P2/P3 items are all *known* work against a
*known* venue. The unknowns — does the transport seam actually fit a second exchange, does discovery
generalise, what breaks when `Venue.values().length` stops being 2 — only surface by adding a venue.
Paying down debt first defers every one of those discoveries.

### 1.1 Settled by the user

| Question | Answer |
|---|---|
| Split MEXC spot and futures into separate steps? | **Yes.** Futures first. Spot is added last, after futures is verified healthy. |
| MEXC spot inclusion policy (top-N by volume, hard cap, …)? | **Deferred.** Not designed now; revisit when spot work starts. |
| Fix the `feedKey` cross-exchange collision now? | **No.** `feedKey` touches the classification module only, and MEXC classification is not planned for a long time. Leave `Instrument.feedKey` as `SYMBOL:MARKET`. See §6.2 — the collision is real but latent, and there is a cheap containment for it. |

### 1.2 Why MEXC-first does not burn the "Bybit is venue #2" decision

Progress-doc decision #12 and vision §12.1 say MEXC must not be venue #2 because *"MEXC spot is
model A — Binance-shaped. It would pass through a Binance-shaped abstraction by luck, validating
nothing."*

That argument is **specifically about the sync SPI**, which this milestone excludes entirely. For
the *transport* and *discovery* seams, MEXC is the harsher test, not the softer one:

- 30 subscriptions per connection (Binance: 1024) — forces the derived connection count to actually
  matter for the first time.
- Two completely unrelated protocols across one exchange's two markets — the strongest possible
  vindication of "the adapter unit is a venue, not an exchange."
- Per-symbol subscribe frames on futures, batched frames on spot — forces subscribe-frame
  construction out of the client and into the protocol.
- Application-level JSON heartbeat at a 15s cadence vs. Binance's 120s protocol ping.
- A native symbol format (`ETH_USDT`) that is *not* the Binance format, which breaks any surviving
  assumption that symbol strings are interchangeable across venues.

Bybit remains venue #2 **for the sync SPI**. Nothing here commits a sync algorithm for MEXC.

---

## 2. Scope boundary, stated precisely

**In scope**

1. Binance keeps running at today's health: 353 spot + 525 futures books to `SYNCED` in ~6 minutes,
   same feed output, same connection counts.
2. MEXC futures universe discovery — `/api/v1/contract/detail`, inclusion policy in config,
   instruments registered, ids assigned, book slots allocated.
3. MEXC futures WebSocket connections established at application startup.
4. `sub.depth` subscription for every discovered MEXC futures instrument.
5. Frames routed to an instrument id, published into the correct Disruptor shard, and counted by the
   shard consumer. A per-venue message-rate number appears in the pipeline health line.
6. MEXC spot, same five properties, as the final step — including the protobuf decode path.

**Out of scope (and must stay out)**

- Any MEXC `DepthSyncStrategy` that touches an `OrderBook`.
- Any MEXC `RecoverySink`, snapshot fetch, or `depth_commits` replay.
- MEXC classification, feed output, or per-user rules.
- P2 step 3 (`SnapshotRequestQueue`) and step 4 (`RequestBudget`). Justified: nothing in this
  milestone fetches a depth snapshot. MEXC discovery is two REST calls per four hours, which does
  not need a budget abstraction to be safe. A `MexcRestClient` with its own `WebClient` and **no**
  budget filter is honest minimalism, not debt — the budget arrives with the first snapshot fetch.
- P3 robustness (reset lane, `tryNext()` backpressure, staleness watchdog, dynamic
  subscribe/unsubscribe). See §7 for the two places this hurts and why it is still acceptable.

---

## 3. MEXC protocol facts — verified 2026-09-14

`external-docs/mexc/mexc-api-contracts.md` held up well. Deltas and confirmations:

### 3.1 Confirmed

- Spot WS is `wss://wbs-api.mexc.com/ws` and is **Protobuf only**. The JSON endpoint
  `wss://wbs.mexc.com/ws` was discontinued for Open API users on **2025-08-04**. There is no JSON
  fallback for spot market data.
- Spot: **maximum 30 subscriptions per connection.** Connections are valid for **at most 24 hours**.
  Server closes a connection after 30s with no valid subscription, or 60s with subscriptions but no
  data flow.
- Futures WS is `wss://contract.mexc.com/edge` and is **plain JSON**.
- Futures keepalive: `{"method":"ping"}`, recommended every 10–20s; the server disconnects if no
  ping arrives within 60 seconds.
- Futures `push.depth` carries top-level `"channel"` and `"symbol"` fields, which is what makes a
  bounded `indexOf` routing scan possible:

  ```json
  {"channel":"push.depth","data":{"asks":[[6859.5,3251,1]],"bids":[],"version":96801927},
   "symbol":"BTC_USDT","ts":1587442022003}
  ```

- Symbol format split holds: spot `ETHUSDT`, futures `ETH_USDT`.

### 3.2 New or newly-important

- **Futures `sub.depth` takes one symbol per frame.** There is no batch form:
  `{"method":"sub.depth","param":{"symbol":"ETH_USDT"}}`. ~500 contracts means ~500 subscribe
  frames. This is the single biggest reason subscribe-frame construction must move into the
  protocol rather than staying in the client.
- **`compress` now defaults to `true` on `sub.depth`** (change dated April 2025). The docs do not
  state unambiguously whether this means frame-level compression or field merging/abbreviation.
  **Action: send `"compress": false` explicitly in the subscribe frame** and verify the wire format
  empirically before trusting either reading. Getting this wrong means the routing scan silently
  finds no `"symbol"` and every frame is dropped.
- **Futures per-connection subscription cap is undocumented.** A 2023 announcement mentions 200 per
  link for the V2 API. Treat 200 as the working assumption in config
  (`max-streams-per-connection: 200`), which for ~500 contracts derives 3 connections — comfortable,
  and adjustable in YAML if MEXC pushes back.
- **Futures has a gap-recovery endpoint spot does not**: `GET /api/v1/contract/depth_commits/{symbol}/1000`
  returns the last 1000 incremental commits ascending by version, letting a gap be patched without a
  full re-snapshot. Irrelevant to this milestone, but it means MEXC futures is not cleanly vision-§4.1
  model A — it is model A with a cheaper recovery path. Record it now so the eventual `RecoverySink`
  design does not assume "recovery == full snapshot."
- **MEXC's spot universe is far larger than Binance's** — order of thousands of USDT pairs. At 30
  subs/connection that is 90+ connections unfiltered. Inclusion policy is deferred by decision
  (§1.1), but it is a *blocker* for the spot step, not a nicety.

### 3.3 Reference endpoints

| | MEXC spot | MEXC futures |
|---|---|---|
| REST base | `https://api.mexc.com` | `https://api.mexc.com` |
| Universe | `GET /api/v3/exchangeInfo` | `GET /api/v1/contract/detail` |
| Inclusion filter | `quoteAsset == "USDT" && status online && isSpotTradingAllowed` | `quoteCoin == "USDT" && futureType == 1 && state == 0 && apiAllowed` |
| WS | `wss://wbs-api.mexc.com/ws` (protobuf) | `wss://contract.mexc.com/edge` (JSON) |
| Depth subscribe | `{"method":"SUBSCRIPTION","params":["spot@public.aggre.depth.v3.api.pb@100ms@BTCUSDT"]}` | `{"method":"sub.depth","param":{"symbol":"BTC_USDT","compress":false}}` |
| Depth cadence | 100ms or 10ms | ~200ms |
| Heartbeat | `{"method":"PING"}` | `{"method":"ping"}` |
| Subs/connection | 30 (documented) | 200 (assumed) |
| Proto definitions | https://github.com/mexcdevelop/websocket-proto | n/a |

---

## 4. Code audit — what is actually Binance-shaped

Assessed against the tree at `43546b4`. The `stream/` package is much less Binance-coupled than its
class names suggest; discovery is much *more* coupled than it looks.

### 4.1 Already generic (no change needed)

| Component | Why it is fine |
|---|---|
| `Instrument`, `InstrumentRegistry`, `Venue`, `BookSlot`, `BookSlotTable` | Nothing venue-specific. `Venue` just needs two more constants. |
| `DepthEvent`, `DisruptorShardManager`, shard routing | `id & (shardCount - 1)` is venue-blind. |
| `SubscriptionIndex` | `nativeSymbol → instrumentId`, per-connection, venue-agnostic by construction. |
| `RawDepthMessageHandler` | `handle(int, String)` — fine for MEXC futures. **Not** fine for spot protobuf (step 4).  |
| `DisruptorDepthMessageHandler` | Pure routing. |
| `SyncStrategyRegistry`, `VenueStrategyBinding`, `DepthSyncStrategy` | The SPI accommodates a new venue additively. It also contains a landmine — §6.1. |
| `BinanceConnectionPool` | ~90% generic already: reads `streamUrl`, `maxStreamsPerConnection`, `minConnections`, `maxConnections` from `VenueProperties`, and the derived-connection-count javadoc explicitly anticipates a venue with a ~30 cap. Only the class name and the `new BinanceStreamClient(...)` line are Binance-bound. |

### 4.2 Binance-shaped in `stream/` — exactly four things

All in `BinanceStreamClient`. Everything else in that class (exponential reconnect backoff, shutdown
handling, `SubscriptionIndex` construction, rate-limited unknown-symbol logging) is venue-neutral
and should be preserved verbatim.

| Location | Binance assumption |
|---|---|
| `onOpen` / `buildSubscribeFrame` | `{"method":"SUBSCRIBE","params":[…],"id":N}`, lowercased symbol + `depthStream` suffix, chunked by `subscribeChunkSize` |
| `onMessage`, line 1 | `message.charAt(2) == 'r'` — ack discrimination |
| `onMessage`, line 2 | `indexOf("\"s\":\"")` — routing token extraction |
| `sendHeartbeat` | `sendPing()` — protocol-level WebSocket ping, at a single global interval |
| *(absent)* | No `onMessage(ByteBuffer)` override — text frames only |

`BinanceWebSocketManager` additionally hardcodes the two Binance venues and constructs exactly two
pools.

### 4.3 Binance-shaped in discovery — the real work

`InstrumentUniverseService` is the most Binance-coupled class in the milestone:

- Constructor takes `BinanceRestClient` directly.
- `refresh()` hardcodes `/api/v3/exchangeInfo` and `/fapi/v1/exchangeInfo`.
- `selectCandidates` parses `ExchangeInfoResponse` / `BinanceSymbolDto` and applies Binance's
  `TRADING` status literal and `contractType` field.
- `Mono.zip` over exactly two calls — **all-or-nothing**. One failure retains the whole previous
  universe. With N sources this must become per-source isolation (vision §9.3): a MEXC outage must
  not stall Binance discovery, and must never be read as a mass delisting on either side.

This is the step with genuine Binance regression risk, which is why step 1 does it alone.

### 4.4 Config gaps

| Item | Problem |
|---|---|
| `ExchangeProperties.enabled` | **Read nowhere in the codebase.** `grep -rn "\.enabled()"` returns nothing. It is dead config today and must be wired for the first time in step 0, because it is the entire safe-rollout mechanism for this plan. |
| `WebSocketProperties.heartbeatIntervalSeconds` | Global, 120s. MEXC futures needs ~15s. Its own javadoc already flags this as transitional. Must move to the protocol. |
| `VenueProperties.depthStream` | Binance-shaped (`"@depth"`). Meaningless for MEXC futures. Leave it; MEXC simply does not read it. Noting the seam is imperfect rather than over-engineering it. |
| `VenueProperties.subscribeChunkSize` | Applied by the *client* today. Becomes a protocol-owned value once frame construction moves. |
| `ExchangesProperties.exchange(…)` | Throws on a missing block, so MEXC needs a YAML block from step 0 onward even while disabled. |

---

## 5. Implementation steps

Five steps. **Each is independently verifiable against live Binance**, which is the mechanism that
protects requirement #1. Do not merge two of them into one commit.

### Step 0 — Widen the enums and wire `enabled`, ship dark

The smallest possible change that makes `Venue.values().length == 4`, so that every latent
assumption about there being exactly two venues surfaces now, at startup, rather than in step 3.

**Changes**

- `Exchange`: add `MEXC`.
- `Venue`: add `MEXC_SPOT(Exchange.MEXC, Market.SPOT)` and `MEXC_FUTURES(Exchange.MEXC, Market.FUTURES)`.
  **Append them after the Binance constants.** `InstrumentUniverseService.selectCandidates` sorts
  candidates by `venue.ordinal()` then `nativeSymbol` specifically so ids are reproducible across
  restarts; keeping Binance at ordinals 0 and 1 keeps every Binance id byte-identical to today,
  which is what makes step 1's parity check meaningful.
- `application.yml`: a `screener.exchanges.mexc` block with `enabled: false`, a `rest` block, a
  `discovery` block, and `venues: { SPOT: …, FUTURES: … }`. Values from §3.3. Futures:
  `max-streams-per-connection: 200`, `min-connections: 1`, `max-connections: 8`.
- **Wire `enabled` for the first time.** It must gate at minimum: discovery source selection and
  connection-pool startup. Suggested single chokepoint — a small helper on `ExchangesProperties`,
  `Set<Venue> enabledVenues()`, that every consumer asks instead of each reading the flag itself.

**Watch for**

- `PipelineHealthLogger.appendResyncs` iterates all venues unconditionally, so the health line will
  grow `mexc/spot=0 mexc/futures=0` immediately. Harmless; consider skipping venues with no tracked
  books for symmetry with `appendBookCounts`, which already skips `total == 0`.
- `ClassificationRuleService:275` calls `Venue.of(Exchange.BINANCE, target.market())` — hardcoded to
  Binance, so `/api/rules` cannot accidentally target MEXC. Leave as is; it is the correct behaviour
  for this milestone. Just confirm it still compiles and that no other caller of `Venue.of` becomes
  ambiguous.
- `PipelineMetrics` sizes its arrays from `Venue.values().length`, so it grows automatically.

**Exit criterion**: application starts, Binance behaviour bit-for-bit unchanged, health line shows
the two new venues at zero.

---

### Step 1 — `InstrumentSource` SPI, Binance still the only source

A pure refactor. No MEXC code. This is the highest-regression-risk step in the plan and gets a
commit of its own.

**New SPI** in `exchange/spi/`:

```java
public interface InstrumentSource {
    Venue venue();
    /** Blocking. Returns this venue's tradable universe, already filtered by inclusion policy. */
    List<InstrumentCandidate> fetch();
}

public record InstrumentCandidate(String nativeSymbol, String base, String quote) { }
```

One source **per venue**, not per exchange — consistent with "venue is the adapter unit," and
necessary because MEXC's two markets hit different endpoints with different response shapes.

**`InstrumentUniverseService` becomes a merger**

- Injects `List<InstrumentSource>`, filters to enabled venues, and fetches each.
- **Per-source failure isolation**: wrap each `fetch()` in its own try/catch. On failure, log and
  **retain that venue's previous instrument set** — do not treat its absence as a delisting, and do
  not abort the other sources. This is vision §9.3 generalised, and it is new behaviour: today's
  `Mono.zip` is all-or-nothing. It needs a per-venue "last known ids" map rather than the single
  `activeIds` set.
- Run the sources concurrently (they are independent network calls) but perform **registration,
  slot allocation, publication and the event fire on the calling thread**, exactly as today. Id
  assignment must stay single-threaded.
- Preserve the sort (`venue.ordinal()`, then `nativeSymbol`) and the ordering invariant verbatim:
  **register all → allocate → publish → fire `InstrumentUniverseChangedEvent` → transport
  subscribes.** `BookSlotTable`'s javadoc explains why; a subscribe frame going out before
  `publish()` lets a reader resolve an id past the end of the published array.

**Binance logic moves to the adapter**

- New `exchange/binance/BinanceSpotInstrumentSource` and `BinanceFuturesInstrumentSource`, carrying
  the `TRADING` literal, the `contractType` filter, the quote-asset filter and the exclusion set.
- The `spot-requires-futures` rule is a *cross-venue* dependency inside one exchange, so it cannot
  live in a single per-venue source cleanly. Two options, decide during implementation:
  (a) keep it in the Binance adapter as a small `BinanceInstrumentSources` collaborator that fetches
  both and yields two filtered lists, exposed as two `InstrumentSource` beans; or
  (b) let the spot source fetch `/fapi/v1/exchangeInfo` itself for the intersection. (a) is cleaner
  and avoids a duplicate REST call; prefer it.
- `BinanceRestClient` stays exactly as it is.

**Exit criterion — the parity check that makes this step safe**: a live run produces **the same 353
spot + 525 futures instruments, with the same ids**, and reaches the same synced counts on the same
~6-minute ramp. Diff the startup `Instrument universe updated` line and the 30s health lines against
a pre-change run.

---

### Step 2 — `StreamProtocol` + generic `ConnectionPool` / `StreamClient`, Binance-only

Rename-and-extract. Still no MEXC code, still two Binance pools, still the same frames on the wire.

**New SPI** in `exchange/spi/`:

```java
public interface StreamProtocol {
    Venue venue();
    URI endpoint();
    int maxStreamsPerConnection();

    /** Frames to send on open. One venue may need one frame per symbol (MEXC futures),
     *  one frame per N symbols (Binance), or one frame total. Chunking is the protocol's job. */
    List<String> buildSubscribeFrames(List<Instrument> batch);
    List<String> buildUnsubscribeFrames(List<Instrument> batch);   // unused until P3; define it now

    /** True for acks, pongs and error envelopes — anything that is not a depth frame. */
    boolean isControlFrame(String msg);

    /** @return instrument id, or -1 if the routing token does not resolve. */
    int resolveInstrumentId(String msg, SubscriptionIndex index);

    Heartbeat heartbeat();
    boolean binaryFrames();          // wired but unused until step 4
}

public record Heartbeat(Duration interval, /* null = use protocol-level WebSocket ping */ String frame) { }
```

**Transport changes**

- `BinanceConnectionPool` → `ConnectionPool` (in `exchange/stream/`), taking a `StreamProtocol`
  instead of reading `streamUrl` / `maxStreamsPerConnection` off `VenueProperties`. The derived
  connection-count formula stays untouched.
- `BinanceStreamClient` → `StreamClient` (in `exchange/stream/`), delegating the four Binance-shaped
  spots to the protocol. Reconnect backoff, shutdown, `SubscriptionIndex` construction and the
  rate-limited unknown-symbol counter stay exactly as they are.
- `BinanceWebSocketManager` → `StreamManager` (in `exchange/stream/`), injecting
  `List<StreamProtocol>`, filtering to enabled venues, and building one pool per protocol. The
  `onUniverseChanged` first-event-starts-the-pools behaviour and the
  "dynamic re-subscription not yet implemented" log stay as they are — P3 owns that.
- New `exchange/binance/BinanceStreamProtocol`, contributed as a bean per venue from
  `BinanceAdapterConfig` (mirroring how `VenueStrategyBinding` is already contributed). Two
  configured instances, one per venue — one set of adapter beans per venue, even sharing a class.
- Move `heartbeatIntervalSeconds` out of `WebSocketProperties` into the protocol's `Heartbeat`.
  `reconnectInitialDelayMs` / `reconnectMaxDelayMs` stay global — they are genuinely uniform.
- Move `subscribeChunkSize` reading into `BinanceStreamProtocol`.

**Define `binaryFrames()` now but leave the byte path unwired.** Declaring the method costs nothing
and records the requirement; implementing `onMessage(ByteBuffer)` before there is a consumer for it
would be speculative.

**Exit criterion**: same 2 spot + 3 futures connections, same subscribe frames on the wire
(verify with debug logging or a capture), same sync counts, same health line.

---

### Step 3 — MEXC futures adapter, live and counting

The first genuinely additive step. Everything below lands in a new `exchange/mexc/` package plus
YAML; **if any core file outside `application.yml` needs editing here, the abstraction leaked and
steps 1–2 were not finished.** (The one sanctioned exception is `PipelineMetrics` — see below.)

**New files in `exchange/mexc/`**

| File | Role |
|---|---|
| `MexcRestClient` | Thin `WebClient` wrapper on `https://api.mexc.com`. **No budget filter** — see §2. Mirror `BinanceRestClient`'s shape, including the enlarged codec buffer (`contract/detail` is large). |
| `dto/ContractDetailResponse`, `dto/MexcContractDto` | Full-POJO deserialization; cold path, so ordinary Jackson is correct here, not streaming. |
| `MexcFuturesInstrumentSource` | `GET /api/v1/contract/detail`; filter `quoteCoin == "USDT" && futureType == 1 && state == 0 && apiAllowed`. Emits `InstrumentCandidate(symbol, baseCoin, quoteCoin)` with the native `ETH_USDT` form. |
| `MexcFuturesStreamProtocol` | See below. |
| `CountingSyncStrategy` + `CountingSyncContext` | No-op strategy: increments a per-venue counter, touches no book. See §6.1. |
| `MexcAdapterConfig` | Contributes the `StreamProtocol` bean, the `InstrumentSource` bean and the `VenueStrategyBinding` for `MEXC_FUTURES`. |

**`MexcFuturesStreamProtocol` specifics**

- `endpoint()` → `wss://contract.mexc.com/edge` from `VenueProperties.streamUrl`.
- `maxStreamsPerConnection()` → 200 (assumed; config-driven so it can be corrected without a rebuild).
- `buildSubscribeFrames(batch)` → **one frame per instrument**:
  `{"method":"sub.depth","param":{"symbol":"ETH_USDT","compress":false}}`. Note `compress:false` is
  explicit and deliberate (§3.2).
- `isControlFrame(msg)` → true for `rs.sub.depth` acks, `pong`, and `rs.error` envelopes. Prefer a
  bounded scan for the `"channel":"` value over a `contains` on the whole frame.
- `resolveInstrumentId(msg, index)` → bounded `indexOf("\"symbol\":\"")` scan, matching the shape of
  the existing Binance scan. **`SubscriptionIndex` needs no change** — it maps native symbols, and
  MEXC's native symbol is what appears in the frame.
- `heartbeat()` → `new Heartbeat(Duration.ofSeconds(15), "{\"method\":\"ping\"}")`. 15s sits inside
  MEXC's recommended 10–20s window with margin against the 60s disconnect.
- `binaryFrames()` → `false`.

**Counting**

`PipelineMetrics` is core, but its javadoc explicitly invites growth: *"Grow it when a number is
actually needed, not before."* Requirement #5 makes a per-venue message count a needed number.

- Add `recordMessage(Venue)` (a `LongAdder` array, same shape as `resyncs`) and `messages(Venue)`.
- **Call it from `CountingSyncStrategy`, not from `DepthEventHandler`.** Putting the increment in the
  handler would add a per-message `LongAdder` write to Binance's hot path for no benefit; putting it
  in the MEXC strategy keeps Binance's path byte-identical and costs MEXC nothing it isn't already
  paying.
- Add a `| msgs` section to `PipelineHealthLogger`, rate-computed from deltas like `resyncs`.

**YAML**: flip `screener.exchanges.mexc.enabled` to `true`, with the spot venue left out of the
enabled set (or the spot `InstrumentSource`/`StreamProtocol` simply not yet existing, which achieves
the same thing more simply).

**Exit criterion**: startup log shows the MEXC futures universe size; `mexc/futures=0/N` appears in
the health line's `synced` section (zero synced is correct and expected); the `msgs` section shows a
non-zero, stable MEXC rate; Binance's numbers are unchanged from the step-2 baseline.

---

### Step 4 — MEXC spot adapter (protobuf)

Fenced off deliberately. Everything here is new *mechanism*, not new *architecture*, and none of it
validates a seam that steps 1–3 did not already validate.

**Blocked on**: the deferred spot inclusion policy decision (§1.1, §3.2). Unfiltered, MEXC spot is
90+ connections. Resolve this before starting.

**Work**

1. **Protobuf toolchain** — add `protobuf-java`, pull `.proto` definitions from
   `mexcdevelop/websocket-proto`, and either generate stubs at build time (`protoc` plugin) or check
   generated sources in. Decide which; a build-time plugin on Windows dev machines has historically
   been the fussier option.
2. **Binary frame path** — implement `onMessage(ByteBuffer)` on `StreamClient`, gated on
   `protocol.binaryFrames()`.
3. **`DepthEvent` payload** — the slot holds `String rawJson`. A protobuf frame is bytes. This needs
   a decision: a second `byte[] payload` field, or a `CharSequence`/`Object` payload with the
   provenance discriminator telling the consumer how to read it. **This is the one genuine
   architectural question in step 4** and it interacts with vision §11's "no normalised DTO" rule —
   design it before writing the decoder.
4. **Routing on the reader thread** — resolving the instrument id needs the channel/symbol out of
   `PushDataV3ApiWrapper`. A full `parseFrom` allocates a whole message object on the hottest thread.
   Open question: partial/lazy field extraction vs. accepting the allocation and measuring. Do the
   simple thing first, measure, and record the number.
5. **Connection fan-out** — ~17–25 connections at the 30-sub cap. This is precisely the case vision
   §5.4 warns about, so a **connect/subscribe pacer** stops being optional here. One pacer per
   *exchange* (the limit is IP-scoped), applied to `connect()`.
6. **24h connection lifetime** — every MEXC spot connection is force-closed daily. The existing
   reconnect path re-subscribes everything in `onOpen`, which is correct, but it will now run
   ~25 times a day instead of approximately never. Watch the reconnect logs for a full cycle.
7. **`feedKey` containment** — see §6.2. This must be handled *before* MEXC spot instruments get
   book slots, not after.

**Exit criterion**: same five properties as step 3, for spot; Binance still unchanged.

---

## 6. Landmine register

Ordered by how much each will hurt if missed.

### 6.1 `SyncStrategyRegistry.forVenue` throws on an unmapped venue — **startup failure**

`BookSlotTable.allocate` calls `strategyRegistry.forVenue(instrument.venue())` for **every**
instrument, and the registry deliberately throws rather than returning null:

```java
throw new IllegalStateException(
    "No DepthSyncStrategy bound for venue " + venue + " — its adapter config is missing");
```

The moment step 3 registers its first MEXC instrument, startup dies.

**Resolution — a no-op `CountingSyncStrategy` bound from `MexcAdapterConfig`:**

```java
final class CountingSyncStrategy implements DepthSyncStrategy {
    private final Venue venue; private final PipelineMetrics metrics;
    public BookSyncContext newContext() { return CountingSyncContext.INSTANCE; }
    public void onEvent(BookSlot slot, DepthEvent event) { metrics.recordMessage(venue); }
}
```

~15 lines in the adapter package, **zero core edits**, satisfies the registry's invariant, and it is
exactly what the SPI exists for. It is also a genuine proof that the SPI accommodates a
venue-with-no-sync-yet additively — worth calling out in the commit message. It gets deleted when
the real MEXC strategy lands.

Do **not** "fix" this by relaxing the registry to return null. The throw is load-bearing: a null
strategy surfaces as an NPE on a consumer thread minutes later.

### 6.2 `feedKey` collides across exchanges — latent, contained for now

`Instrument.of` builds `feedKey = nativeSymbol + ":" + market.name()`. `OrderBookClassifier` keys
three things on it: `defaultStates`, `UserClassificationRules.configuredKeys()`, and
`OrderBookFeedStore.submit`.

- **MEXC futures does not collide.** `ETH_USDT:FUTURES` ≠ `ETHUSDT:FUTURES`. The symbol-format split
  saves us by accident.
- **MEXC spot does collide.** `BTCUSDT:SPOT` is identical on both exchanges.

**Per §1.1, `feedKey` stays unchanged.** But the collision is not merely cosmetic, and the reason
matters. `DepthEventHandler.onEvent` calls `classificationModule.process(...)` **unconditionally**
after the strategy, for every event including MEXC's. `classifyOne` opens with:

```java
if (ob.getState() != OrderBookState.SYNCED) {
    if (state.level == ActivityLevel.HIGH) { submitDropUpdate(...); state.level = LOW; }
    return;
}
```

A MEXC book is permanently `PENDING`. If it shares a `SymbolState` with a healthy, `SYNCED` Binance
book, every MEXC frame will flip that shared state from `HIGH` to `LOW` and emit a spurious `DROP` —
and the next Binance frame re-emits `ADD`. **That is active corruption of the Binance feed**, at
MEXC spot's 100ms cadence.

- **Step 3 (futures) is safe with no change.** No key collision; the shared-nothing `SymbolState`
  stays `LOW` from birth and nothing is ever submitted. The only cost is a `computeIfAbsent` and an
  `isHighLiquidity` call per MEXC event, plus ~500 extra map entries.
- **Step 4 (spot) requires containment before the first MEXC spot slot is allocated.** The cheapest
  correct fix is to skip classification entirely for venues that have no real sync strategy — e.g. a
  `boolean classify()` on `BookSlot` (set at allocation from a property of the bound strategy), or a
  marker interface on `DepthSyncStrategy` that `DepthEventHandler` checks. **Do not** gate
  `DepthEventHandler` on `state == SYNCED`: Binance's `DROP`-on-desync emission depends on the
  classifier being called *while* the book is not synced.

### 6.3 `enabled` is dead config

`grep -rn "\.enabled()"` returns nothing. The entire safe-rollout story of this plan rests on a flag
nobody reads. Wire it in step 0 — before it is needed — and give it a single chokepoint
(`ExchangesProperties.enabledVenues()`) rather than scattering the check.

### 6.4 `compress` defaults to true on MEXC futures `sub.depth`

If the April-2025 default means anything other than what we assume, the routing scan finds no
`"symbol"` field and every frame is silently dropped — with no error, just a flat message rate.
Send `"compress": false` explicitly, and **log the first raw frame per connection at debug** during
step 3 bring-up so the wire format is confirmed rather than assumed.

### 6.5 Reader-thread and connection budget

MEXC futures adds ~3 java-websocket reader threads. MEXC spot adds ~25 more, on top of Binance's 5.
All of them publish into 2 Disruptor shards (`screener.disruptor.shard-count: 2`). The readers are
not the bounded resource — the shards are — but `ring free` in the health line is the number to
watch, and vision §8.1 recommends scaling shard count with cores. Revisit after step 4, driven by
measurement, not by anticipation.

### 6.6 Blocking `next()` on the ring buffer

`DisruptorDepthMessageHandler.handle` uses blocking `rb.next()`. P3's `tryNext()` change is not in
scope. With MEXC futures (~3 connections) the blast radius of a stall is small. With MEXC spot
(~25 connections, each carrying 30 streams) it is still far smaller than Binance's (1 connection =
hundreds of streams), so the risk *decreases* per connection as venues are added. Acceptable to
defer, but it is now a known, named exposure rather than an unexamined default.

### 6.7 Venue enum ordering affects id reproducibility

`selectCandidates` sorts by `venue.ordinal()` then `nativeSymbol` so that ids are reproducible across
restarts and parity runs are diffable. Appending MEXC constants after Binance preserves every
Binance id. Inserting them before, or reordering the enum later, silently reshuffles every id — which
is harmless at runtime (ids are never persisted) but destroys the ability to diff two runs.

---

## 7. Deferred debt, and where it bites

| Deferred | Where it bites in this plan | Why it is still acceptable |
|---|---|---|
| P2 step 3 — `SnapshotRequestQueue` | Nowhere. No snapshots are fetched. | Arrives with the first real MEXC sync strategy, which is when its parameterisation is actually informed by a second venue. |
| P2 step 4 — `RequestBudget` | `MexcRestClient` has no rate-limit protection. | Two calls per four hours against a 10-req/2s limit. A budget abstraction here would be binding config for an absent abstraction — the exact mistake `ExchangesProperties` already documents avoiding. |
| P3 — reset lane | A MEXC reconnect cannot tell its books to restart. | MEXC books hold no state to invalidate. Becomes mandatory the moment a MEXC sync strategy exists. |
| P3 — `tryNext()` backpressure | §6.6. | Per-connection blast radius is smaller on MEXC than on Binance. |
| P3 — dynamic subscribe/unsubscribe | MEXC listings/delistings need a restart, same as Binance today. | Pre-existing behaviour, not a regression. MEXC's higher churn rate makes this more urgent than it was — flag it, don't fix it here. |
| P3 — staleness watchdog | A silently-dead MEXC subscription is invisible. | Partially mitigated: the new per-venue `msgs` rate in the health line makes a *total* MEXC stall visible at a glance, which is most of the value at this stage. A per-instrument watchdog is still needed later. |
| P3 — connect pacer | Not needed at step 3 (~3 connections). **Needed at step 4** (~25). | Listed as step-4 work, not deferred past it. |

---

## 8. Open questions

1. **`DepthEvent` payload representation for binary frames** (step 4). `String rawJson` plus a
   `byte[]`? A polymorphic payload? This is the one place where protobuf and vision §11's
   "no normalised DTO, raw frame → venue-owned streaming parse" rule genuinely tension against each
   other. Decide before writing the decoder.
2. **Spot protobuf routing cost** (step 4). Full `parseFrom` on the reader thread vs. partial field
   extraction. Do the simple thing, measure it against the existing benchmark baseline, then decide.
3. **MEXC spot inclusion policy** (step 4 blocker). Top-N by 24h quote volume — which costs a second
   REST call to `/api/v3/ticker/24hr` — or a hard cap, or a mirror of `spot-requires-futures`.
   Deferred by decision; must be answered before step 4 starts.
4. **`spot-requires-futures` placement** (step 1). A cross-venue rule inside one exchange does not
   decompose cleanly into two per-venue `InstrumentSource`s. Option (a) in step 1 is preferred; confirm
   during implementation.
5. **MEXC futures per-connection cap.** Undocumented; 200 assumed from a 2023 V2 announcement. If
   MEXC drops connections during step-3 bring-up, lower `max-streams-per-connection` in YAML and
   record the real number here.
6. **Shard count.** Still 2. Revisit after step 4 with measured `ring free` numbers, per vision §8.1.

---

## 9. Verification checklist

Run after every step, against live exchanges, comparing to the previous step's baseline.

**Binance non-regression (all steps)**

- [ ] `Instrument universe updated — N tracked` shows 878 (353 spot + 525 futures).
- [ ] `synced binance/spot=353/353 binance/futures=525/525` reached within ~6 minutes.
- [ ] `resyncs binance/spot` and `binance/futures` settle near zero at steady state.
- [ ] `msgs/s` per shard stays balanced between the two shards.
- [ ] `ring free` shows no persistent dip.
- [ ] `drain max` stays inside budget; `slow` count unchanged.
- [ ] Connection count: 2 spot + 3 futures.
- [ ] `./mvnw clean package` green, including `BinanceDepthSyncStrategyTest` (23 cases),
      `BinanceAdapterConfigTest` and `OrderBookTest`.

**Step 1 additionally**

- [ ] Binance instrument **ids** identical to a pre-change run (diff the debug universe log).
- [ ] Kill MEXC connectivity artificially (or point a source at a bad URL) and confirm Binance
      discovery still completes and no mass delisting is reported.

**Step 2 additionally**

- [ ] Subscribe frames on the wire byte-identical to pre-change (debug log or capture).

**Step 3 additionally**

- [ ] MEXC futures universe size logged and plausible (~500 contracts).
- [ ] `synced mexc/futures=0/N` — zero synced is the *correct* result.
- [ ] `msgs mexc/futures` non-zero and stable across several health intervals.
- [ ] No `No book slot for instrument id` warnings.
- [ ] No `Depth frame for unsubscribed symbol` warnings (would mean the routing scan or
      `compress` assumption is wrong).
- [ ] First raw frame per connection logged at debug and manually inspected for field shape.
- [ ] Connections survive > 10 minutes (proves the 15s heartbeat is working against the 60s cutoff).

**Step 4 additionally**

- [ ] Binance spot feed shows no spurious `DROP`/`ADD` churn (proves §6.2 containment works).
- [ ] MEXC spot connection count matches the derived formula at the 30-sub cap.
- [ ] A full 24h run observes the forced reconnect cycle re-subscribing cleanly.
