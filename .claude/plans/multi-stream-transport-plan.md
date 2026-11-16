# Multi-Stream Transport — Implementation Plan

Makes the market-data pipeline ready for stream kinds beyond `DEPTH` (next: `TRADE`, then `KLINE`).
The ring, `MarketEvent.stream` and the handler `switch` already carry a kind
(`market-event-generalization-plan.md`). This plan does the same for the **transport and its
configuration**, which still assume "one venue = one depth stream".

**Every stage is a no-behavior-change refactor.** After the last stage only `DEPTH` is bound and
the pipeline behaves exactly as before. No `TRADE` / `KLINE` value, decoder or state is added here.

**Read first**: `.claude/docs/market-data-pipeline-architecture.md` (§3, §5, §13),
`.claude/plans/team-plans/spike-alerts-plan.md` §2.2 and D1/D5.

---

## Decisions

### D1. One connection carries one stream kind; the transport unit is a `StreamKey`

`StreamKey = (Venue, StreamKind)`. One `ConnectionPool` per `StreamKey`, and every connection in it
subscribes one channel. Kinds are never mixed on one socket.

- **The venues require it.** Binance futures serves klines and trades on
  `fstream.binance.com/market/...`, not on the depth endpoint (`spike-alerts-plan.md` §2.2).
  MEXC spot allows 30 subscriptions per connection, and mixing kinds would split that cap.
- **The hot path does not change.** The kind is a `final` field of the connection and is not parsed
  from the frame. `StreamProtocol.route()`, `SubscriptionIndex` and shard routing stay as they are.
- **Isolation.** A trade firehose has its own sockets, heartbeats and reconnects.
- **Cost.** More sockets (for example, MEXC spot goes from ~17 to ~34 with trades). Check each
  exchange's per-IP connection limit in the plan that adds a kind.

If a venue ever forces kinds onto one socket, `route()` can return a packed `(kind, id)` later.
Nothing here prevents that.

### D2. `StreamKind` is an identity axis and moves to `marketdata/`

`StreamKind` is now used by config, `spi/` and core, the same way `Venue` is. It moves from
`core/ingress/` to the `marketdata/` root, next to `Venue`. `StreamKey` goes there too.

```java
public record StreamKey(Venue venue, StreamKind kind) {
    public static final int COUNT = Venue.values().length * StreamKind.values().length;
    public int index() { return venue.ordinal() * StreamKind.values().length + kind.ordinal(); }
    public String logName() { … }   // "BINANCE_FUTURES/DEPTH"
}
```

`index()` is dense, for per-stream counter arrays (D6). `StreamKind.values()` is cached in a static
field so `index()` does not clone the array. `logName()` returns a string precomputed per key,
because it is used in log lines and thread names (cold path only).

### D3. Per-stream config blocks under each venue

Some settings belong to the venue and stay where they are: `enabled`, `rest`, `snapshot` (bound by
the adapter), `max-visible-distance`. The transport settings move under `streams.<KIND>`:

```yaml
venues:
  FUTURES:
    enabled: ${BINANCE_FUTURES_ENABLED:true}
    rest: { … }
    snapshot: { … }
    streams:
      DEPTH:
        stream-url: wss://fstream.binance.com/ws
        max-streams-per-connection: 1024
        min-connections: 3
        max-connections: 8
        subscribe-chunk-size: 400
        heartbeat-interval-seconds: 120
```

- New record `StreamProperties(@DefaultValue("true") boolean enabled, streamUrl,
  maxStreamsPerConnection, minConnections, maxConnections, subscribeChunkSize,
  heartbeatIntervalSeconds)`. It takes over the validation that `VenueProperties` does today for
  those fields.
- `VenueProperties(enabled, rest, Map<StreamKind, StreamProperties> streams, maxVisibleDistance)`.
  Map keys are upper case (`DEPTH`), the same as `SPOT` / `FUTURES`.
- **A stream is on** when its venue is on, its block exists, and the block's `enabled` is true.
  `ExchangesProperties.isEnabled(StreamKey)` is the single place that decides this, as
  `isEnabled(Venue)` is today. `ExchangesProperties.stream(StreamKey)` throws on a missing block,
  like `venue(Venue)`.
- **Fail fast on a stale config.** Spring ignores unknown keys. A YAML file that still has the old
  flat keys would bind a venue with no streams, and it would go silently dark. So: **an enabled venue
  with no enabled stream fails startup**, with a message that points at `streams.DEPTH`. This
  matters because `application-local.yml` is gitignored, and any server-side override file has to
  be migrated by hand.

### D4. Protocols are bound per stream

- `VenueStreamBinding(StreamKey stream, StreamProtocol protocol)`.
- `StreamProtocolRegistry` is keyed by `StreamKey`:
  - Two bindings for the same key fail startup (as today).
  - **Completeness is per stream:** every enabled stream must have a binding. A binding whose stream
    is disabled or not configured is fine, so an adapter can ship a kind dark.
  - The lookup becomes `forStream(StreamKey)`.
- **The `StreamProtocol` interface does not change.** One protocol instance serves one stream. When
  a second kind arrives, an adapter reuses its exchange's subscribe, ack, heartbeat and error-frame
  handling by passing the channel in (for example, Bybit's topic prefix or Binance's topic suffix
  becomes a constructor argument). That change belongs to the plan that adds the kind, not to this
  one. Protocols read their settings from `StreamProperties` instead of `VenueProperties`.

### D5. `StreamManager` starts every enabled stream of a venue on its first appearance

- Pools live in a `Map<StreamKey, ConnectionPool>`. "Has this venue started?" is tracked per venue
  (a `Set<Venue>` or `EnumSet`), because the invariant is per venue: the first time a venue appears
  in `added`, that list is its whole universe.
- On a venue's first appearance, a pool starts for each kind with `isEnabled(StreamKey)`, and each
  pool gets the venue's **full** instrument list. If a later kind only needs some instruments, its
  own plan adds a filter. Nothing in this plan assumes "all instruments".
- **Startup failures are isolated per stream.** If one stream's pool throws, it is logged and the
  venue's other streams still start.
- The disabled-venue warning and the "dynamic re-subscription not yet implemented" log stay per
  venue.

### D6. Metrics and health per stream

- `PipelineMetrics.recordFrame(StreamKey)` increments `frames[key.index()]`. It is still one striped
  `LongAdder` per counter, with no allocation on the hot path. `frames(StreamKey)` is the reader.
- `resyncs` and `snapshotFailures` stay per venue, because they are depth-sync concepts.
- `PipelineHealthLogger`'s venue table gets one frames/s column per `StreamKind` (header
  `depth/s`, built by iterating `StreamKind.values()`). A new kind then shows up without editing
  the logger. The table stays one row per venue.

### D7. Kinds without a handler arm are counted, not silently cleared

`MarketEventHandler`'s `switch` gets a `default` arm. It increments a counter and logs a WARN at
most once a minute, like `noteMissingSlot`. Without it, a kind that is bound and streaming but has
no handler arm would be routed, published and then thrown away with no sign. The arm is unreachable
today.

### Not in this plan (deliberately)

| Item | Why not now |
|---|---|
| Grouping `InstrumentSlot`'s depth fields into a `DepthState` | Renames only. It touches ~83 accessor uses across every sync strategy. A new kind adds its own field to the record instead |
| A generic `VenueKindRegistry<T>` | There are two registries with two different key types. Revisit when a third registry appears |
| `TradeDecoder` / `CandleDecoder` SPIs, trade and candle state | They depend on the feature (large-trade alerts, the 5-minute kline ring). They belong to those plans. The pattern they follow is in the architecture doc recipe (stage 3) |
| A `StreamKind` parameter on `publishSnapshot` / `publishSnapshotFailure` | Only depth recovers over REST. Add it when a kline REST backfill exists |
| Non-blocking ingress, reset lane | A separate design (`multi-exchange-progress.md` §13). The per-stream frame counters from D6 let us measure the trade rate first. If the ring dips, the drop policy should depend on the kind: a dropped trade or kline is counted and forgotten, while a dropped depth event needs a reset |

---

## Stage 1 — Stream identity and per-stream config

**Goal**: `StreamKind` / `StreamKey` in `marketdata/`, the `streams.<KIND>` config shape, and
protocols and transport reading `StreamProperties`. Pools are still keyed by venue. Only `DEPTH`
exists.

1. `git mv` `core/ingress/StreamKind.java` → `marketdata/StreamKind.java`. Fix imports (main and test).
   Add `marketdata/StreamKey.java` (D2), with a small unit test: `index()` is unique and dense over
   all keys, and `logName()` has the expected format.
2. **Config records** (D3): add `StreamProperties`. Reshape `VenueProperties`. Add
   `ExchangesProperties.isEnabled(StreamKey)`, `stream(StreamKey)`, and the startup check "an
   enabled venue has at least one enabled stream". Put the check where the other config validation
   lives: `ExchangesProperties`' compact constructor if it can see everything it needs, otherwise
   `StreamProtocolRegistry`'s constructor. Pick one and say so in its Javadoc.
3. **YAML**: move the six transport keys of every venue under `streams.DEPTH` in `application.yml`
   **and** `application-local.yml`. Keep the explanatory comments (MEXC 30-cap, Bybit spot 10-args,
   Bybit linear 21 000 chars) next to the keys they explain.
4. **Protocols**: the four `*StreamProtocol` constructors take `StreamProperties`. Their startup
   checks (MEXC caps, Bybit chunk limits, MEXC futures chunk = 1) read the same fields from the new
   record. Adapter configs pass `exchanges.stream(new StreamKey(venue, StreamKind.DEPTH))`.
5. **Transport**: `ConnectionPool` and `StreamConnection` take `StreamProperties` in place of
   `VenueProperties`. `StreamManager`'s pool factory passes `exchanges.stream(…DEPTH)`.
6. **Tests**: many tests build a `VenueProperties` with a 9-argument constructor (`StreamManagerTest`,
   `StreamConnectionTest`, `JavaWebSocketBufferOwnershipTest`, `StreamProtocolRegistryTest`,
   `VenuePropertiesTest`, `ExchangesPropertiesTest`, the adapter `*ConfigTest`, `*StreamProtocolTest`,
   `*SnapshotFetcherTest`, `BybitSnapshotPropertiesTest`, `InstrumentUniverseServiceTest`,
   `SnapshotRequestQueueTest`, and the sync test supports). Add one test fixture (for example
   `config/TestVenueProps`) with `stream(...)` / `venue(...)` builders and route them all through
   it, so the next record change touches one file. `ExchangesPropertiesTest`'s YAML map builder
   (line ~121) moves its keys under `streams.DEPTH`. New tests:
   - `isEnabled(StreamKey)`: venue off → off; block missing → off; block `enabled: false` → off;
     otherwise on.
   - An enabled venue with no streams fails with the `streams.DEPTH` hint.
   - The shipped `application.yml` binds a `DEPTH` stream for every configured venue.
   - `VenuePropertiesTest`'s validation tests move to `StreamPropertiesTest`.

**Done when**: `./mvnw test` shows only the known `contextLoads` error. A local run streams and
syncs every enabled venue as before.

## Stage 2 — Transport keyed by stream

**Goal**: pools, protocol bindings and frame metrics keyed by `StreamKey`. The kind on a published
frame comes from the connection. The `TODO(transport)` comments are removed.

1. **SPI**: `VenueStreamBinding(StreamKey, StreamProtocol)`. `StreamProtocolRegistry` is keyed by
   `StreamKey`, with per-stream completeness (D4) and `forStream(StreamKey)`. Adapter configs bind
   `new StreamKey(venue, StreamKind.DEPTH)`.
2. **`StreamManager`** (D5): `Map<StreamKey, ConnectionPool>`, plus a started-venues set. The pool
   factory signature becomes `create(StreamKey, StreamProtocol)`. Rewrite the class Javadoc's
   invariant paragraph in terms of streams.
3. **`ConnectionPool` / `StreamConnection`** take a `StreamKey` instead of a `Venue`:
   - `StreamConnection` keeps `private final StreamKind kind` and publishes
     `publisher.publishFrame(kind, …)` on both the text and binary paths.
   - The reconnect thread is named `reconnect-<venue>-<kind>` in lower case.
   - Log prefixes use `key.logName()`.
4. **`PipelineMetrics`** (D6): `recordFrame(StreamKey)` / `frames(StreamKey)`. `StreamConnection`
   passes its key, which it holds already, so nothing is computed per frame beyond `index()`.
5. **`PipelineHealthLogger`** (D6): one frames/s column per `StreamKind`. The previous-sample array
   is sized `StreamKey.COUNT`.
6. **Tests**:
   - `StreamManagerTest`: a venue with two enabled streams starts two pools, each with the full
     list. To get a second kind in the test without adding an enum value, either make the
     enabled-kinds lookup injectable, or test the per-stream loop through a fake `isEnabled`. Pick
     whichever keeps production code simplest. A pool failure for one stream does not stop the
     venue's other streams. A venue already started is not started again.
   - `StreamProtocolRegistryTest`: a duplicate `StreamKey` fails. An enabled stream with no binding
     fails. A binding for a disabled stream is accepted.
   - `StreamConnectionTest`: a routed frame is published with the connection's kind, on both the
     text and binary paths.
   - `PipelineMetrics`: frames are counted per key and do not leak between keys.

**Done when**: tests as in stage 1, and a local run produces the same health-log numbers
(synced/total, frames/s per venue under `depth/s`, ring free capacity) as before the change, within
noise.

## Stage 3 — Handler guard and docs

1. **`MarketEventHandler`** (D7): a `default` arm that counts and rate-limit-logs an unhandled kind,
   then falls through to `event.clear()`. No allocation. Expose the counter only if the health log
   needs it. It should stay zero.
2. **Architecture doc** (`.claude/docs/market-data-pipeline-architecture.md`):
   - §3.1 `StreamProtocol` row: bound per stream.
   - §4 / §5.1: one pool per stream.
   - §9: reconnect thread name.
   - §12: transport keyed by kind is now built. Trades are a planned row next to klines.
   - **Rewrite §13 "Add a stream kind" as a recipe** that the trade and kline plans follow:
     1. Add the `StreamKind` value.
     2. Add a `streams.<KIND>` block per venue in YAML (`enabled: false` until ready).
     3. Add a `StreamProtocol` per venue for the new channel, reusing the exchange's protocol class
        with the channel passed in where the control frames are the same.
     4. Add a decoder SPI, bound per venue. For kinds that need no sync, the adapter **only
        decodes**: it parses the frame in place and reports each item to a core-owned sink as
        primitives (one callback per trade or candle update, so a batched frame needs no
        allocation, with quantities converted through `Instrument.quantityMultiplier`). **Core owns
        the state.** Depth stays the only kind with an adapter-owned sync strategy.
     5. Add the kind's state as a new field in `InstrumentSlot`, `null` for venues that don't stream
        it, allocated in `InstrumentSlotTable.allocate`.
     6. Add an arm to the `MarketEventHandler` switch.
     7. Delivery, if any, goes through a `FeedChannel`.

     Then list what does **not** change: transport classes, ring, publisher, shard routing.
3. **`multi-exchange-progress.md`**: config keys section (the `streams.<KIND>` shape) and the
   stream-protocol section (bound per stream).
4. **`CLAUDE.md`**: module-map row for `marketdata/` (`StreamKind` and `StreamKey` at the root,
   `StreamManager` starts one `ConnectionPool` per stream), the "Streams" bullet, and the Conventions
   line that sends venue-shaped settings to `screener.exchanges.<exchange>.venues.<market>.*`. Note
   there that transport settings live under `.streams.<KIND>`.
5. **`spike-alerts-plan.md`**: mark D5's transport generalization as done by this plan. The
   kline-specific parts (protocol, `klines-enabled` → `streams.KLINE.enabled`) stay in that plan.

**Done when**: tests as in stage 1. `grep -rn "TODO(transport)" src` returns nothing.
`grep -rn "core.ingress.StreamKind" src` returns nothing.

---

## Verification beyond tests

Before stage 1, record one local health-log sample with all enabled venues `SYNCED`: synced/total,
frames/s per venue, msgs/s per shard, ring free capacity. After stages 1 and 2, the numbers must
match within noise, and the startup log must show the same connection counts per venue. Stage 2 adds
the stream name to the connection log lines.

## Rollout notes

- The YAML shape change breaks any config file that sets the old flat keys. The D3 startup check
  turns that into a clear startup failure instead of a silently dark venue. Update
  `application-local.yml` in the same change, and check the server for override files before
  deploying.
- Leave changes uncommitted. Each stage can be reviewed and committed on its own.
