# Market Event Generalization — Implementation Plan

Phase 1 of `.claude/plans/team-plans/spike-alerts-plan.md` (ticket 1), narrowed. The ring buffer,
its event, the consumer and the per-instrument slot stop being depth-specific. Behavior does not
change: only `DEPTH` exists after this work.

**Read first**: `.claude/docs/images/marketdata_pipeline.drawio.png` (Router → KLINE / DEPTH branches,
`slot[id] = {OrderBook, CandleStore}`), `.claude/docs/multi-exchange-progress.md`, and
`.claude/plans/team-plans/spike-alerts-plan.md` D2–D4, D9.

## Scope

**In**: ring event + provenance enum + stream kind, handler dispatch, publisher, slot + slot table,
the SPI types that mention them, docs, tests.

**Out (deferred to the transport work, ticket 2)**: `ConnectionPool` / `StreamManager` keyed by kind,
per-kind YAML and `VenueProperties` helpers, `StreamProtocol` / `StreamProtocolRegistry` /
`VenueStreamBinding`, the concrete `*StreamProtocol` classes, `MexcSpotFrameReader`,
`PipelineMetrics` split by kind, reconnect thread names and log prefixes. Those classes change only
where a type they reference is renamed (see step 3).

**Untouched by design**: `DepthSyncStrategy`, `SyncStrategyRegistry`, `VenueStrategyBinding`,
`BookSyncContext`, `OrderBook`, `OrderBookState`, `SnapshotFetcher`, `feed/depth/*`.

## Target names

| Today | After |
|---|---|
| `core/ingress/DepthEvent` | `MarketEvent` (+ field `StreamKind stream`; field `type` → `source`) |
| `core/ingress/EventType` | `EventSource` (`WS_MSG`, `REST_MSG`, `REST_FAILED`) |
| — | `core/ingress/StreamKind { DEPTH }` |
| `DepthEventHandler` | `MarketEventHandler` |
| `DepthEventFactory` | `MarketEventFactory` |
| `DepthEventPublisher` | `MarketEventPublisher` |
| `DisruptorDepthEventPublisher` | `DisruptorMarketEventPublisher` |
| `core/book/BookSlot` | `core/slot/InstrumentSlot` |
| `core/book/BookSlotTable` | `core/slot/InstrumentSlotTable` |

`InstrumentSlot` fields: `instrument`, `book`, `syncStrategy`, `syncCtx` (were `strategy`, `ctx`).
`DepthSyncStrategy.onEvent(InstrumentSlot, MarketEvent)`; `RecoverySink.requestRecovery(InstrumentSlot)`.

## Design points

- **`StreamKind`** is a new enum with the single value `DEPTH`. Later tickets add values.
- **`source` vs `stream`**: `source` stays provenance and still drives parse path and backpressure
  (REST events are never dropped). `stream` says what the payload is. Rewrite the `EventSource`
  Javadoc so the REST no-drop rule is stated independent of `stream`.
- **Publisher**: `publishFrame(StreamKind, int, String)` and `publishFrame(StreamKind, int, ByteBuffer)`.
  `publishSnapshot` and `publishSnapshotFailure` keep their signatures and set `stream = DEPTH`
  internally (snapshot recovery is a depth-sync concept). The private `publish(...)` writes
  `source`, `stream`, `instrumentId`, `rawJson`, `rawBytes` every time — the slot is reused.
- **`MarketEvent.clear()`** resets `stream = null` along with the rest.
- **Handler**: after the missing-slot check, `switch (event.stream)` with one arm,
  `DEPTH -> onDepth(slot, event)`, holding today's body unchanged (`syncStrategy().onEvent`, then
  `classificationModule.process`). `event.clear()` stays after the switch. The classifier call and
  the future order-cluster call belong in the depth arm only. No allocation, no new logging.
- **`StreamConnection`** passes the literal `StreamKind.DEPTH` in its two `publishFrame` calls with a
  `// TODO(transport): kind comes from the pool` comment. This is the only behavioral-shaped edit in
  transport code.
- **Package move** `core/book/` → `core/slot/` for the two slot classes only; `OrderBook`,
  `OrderBookState`, `PriceLevelEntry` stay in `core/book/`.

## Steps

Run `./mvnw test` after each step. Expected result every time: `Tests run: 638, Failures: 0,
Errors: 1` (the known `contextLoads`). Any other failure is real. Use `git mv` so history follows
the files. Leave changes uncommitted.

1. **Provenance enum.** Rename `EventType` → `EventSource` and the field `type` → `source` on the
   event. Update `DisruptorDepthEventPublisher`, `DepthEventHandler` (uses only via event),
   every sync strategy (`event.type` → `event.source`) and tests. Rewrite the Javadoc.
2. **Slot.** `git mv` the two classes to `core/slot/`, rename to `InstrumentSlot` /
   `InstrumentSlotTable`, rename accessors `strategy()` → `syncStrategy()`, `ctx()` → `syncCtx()`.
   Update imports and usages: handler, `SnapshotRequestQueue`, `BatchOutcome`, `RecoverySink`,
   `SnapshotQueueFactory` (Javadoc), `PipelineHealthLogger`, `MonitoringController`,
   `InstrumentUniverseService`, all adapter sync strategies, tests. Update the Javadoc ("every
   depth event" → "every event" etc.).
3. **Event, factory, publisher, handler.** Rename `DepthEvent` → `MarketEvent`,
   `DepthEventFactory`, `DepthEventHandler`, `DepthEventPublisher`, `DisruptorDepthEventPublisher`.
   Type-only edits in `DisruptorShardManager`, `StreamManager`, `ConnectionPool`,
   `StreamConnection`, `SnapshotRequestQueue`, `SnapshotQueueFactory`, `BatchOutcome`,
   `DepthSyncStrategy` and its implementations, tests. Fix the comment at `OrderBookClassifier:134`.
4. **Stream kind.** Add `StreamKind { DEPTH }` and the `stream` field + `clear()`. Add the
   `StreamKind` parameter to both `publishFrame` methods and set it in `publish(...)`; set `DEPTH`
   in the snapshot methods. Pass `StreamKind.DEPTH` from `StreamConnection`. Add the dispatch switch
   to the handler. Update tests that call `publishFrame` or build events directly
   (`StreamConnectionTest`, `JavaWebSocketBufferOwnershipTest`, sync test support classes).
5. **Docs.** Update `CLAUDE.md` (module map: ingress class names, `InstrumentSlotTable`; Disruptor
   section), `.claude/docs/multi-exchange-progress.md`, and the names in
   `spike-alerts-plan.md`, `order-cluster-plan.md`, `mexc-spot-impl-plan.md`. Leave
   `.claude/plans/old/` and finished multi-exchange plans as historical.
6. **Final check.** `grep -r "DepthEvent\|EventType\b\|BookSlot" src` returns nothing outside
   `feed/depth/FeedEventType`. Full `./mvnw test` matches the baseline.

## New / adjusted tests

- `MarketEventHandlerTest` (new, small): a `DEPTH` event reaches the slot's strategy and the
  classifier path; an event for a missing slot is counted and cleared.
- `DisruptorMarketEventPublisher`: frame events carry `source = WS_MSG` and the given `stream`;
  snapshot events carry `REST_MSG` / `REST_FAILED` and `DEPTH`; both payload fields are overwritten
  on reuse.
- `MarketEvent.clear()` resets `stream`.

## Verification beyond tests

Start locally against one venue and compare `PipelineMetrics` (frames/s, processed per shard, synced
books, ring free capacity) with a run before the change. They must match within noise. No new
allocation in the handler or publisher.

## Follow-up: what changes in the spike-alerts plan

Phase 1 shrinks to this plan. Pools / registries keyed by (venue, kind), per-kind YAML, and the
kline `StreamProtocol` move into ticket 2 (Phase 3), together with the `StreamKind.KLINE` value, the
kline arm of the handler switch, and the kline decoder SPI from the diagram. `spike-alerts-plan.md`
D5, D9, §5, §8 Phase 1 and ticket 1's estimate need adjusting once this plan is accepted.
