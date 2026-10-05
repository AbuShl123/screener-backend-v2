# OrderBook.java — Readability Polish

## Changes at a glance

| What | Before | After |
|---|---|---|
| `onDiff()` | old `switch` statement, 21 lines | switch expression, delegates to `bufferDiff()` |
| `applySnapshot()` | 59-line procedural script | 25-line orchestration; each phase is a named method |
| `parseUField` + `parseUpperUField` | two 13-line twins, parse same string twice | removed; replaced by `parseUpdateIdRange()` (one pass) and `parseLastUpdateId()` |
| `discardInvalidDiffsFromBuffer` | loop with inner if/break | while-condition one-liner; renamed `discardStaleDiffs` |
| sequence validation in `applyLiveDiff` | 6-line nested if/else in switch arm | extracted to `isSequenceValid(U, pu)` |
| `applyLevelUpdatesFirstEvent` | vague name, caller does `pollFirst` | renamed `applyFirstBufferedDiff`, absorbs `pollFirst` |
| method order | mixed concerns | grouped: state transitions → diff → snapshot → JSON → lifecycle → observability |

No behaviour changes. No new public methods.

---

## Proposed full file

```java
package dev.abu.screener_backend.binance.orderbook;

import dev.abu.screener_backend.binance.websocket.Market;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.TreeMap;

/**
 * Local orderbook for a single (symbol, market) pair.
 * <p>
 * All methods except {@link #markSnapshotRequested()} are called
 * exclusively by the Disruptor consumer thread assigned to this orderbook's shard.
 * {@code state} is volatile because the SnapshotFetchQueue scheduler thread also writes it.
 */
@Slf4j
public class OrderBook {

    static final int MAX_BUFFER_SIZE = 500;
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    @Getter final String symbol;
    @Getter final Market market;
    @Getter volatile OrderBookState state;

    private final double filterThreshold;

    /** Live bids TreeMap. Must only be accessed by this shard's consumer thread. */
    @Getter
    private final TreeMap<Double, PriceLevelEntry> bids; // reverseOrder → firstKey() = best bid

    /** Live asks TreeMap. Must only be accessed by this shard's consumer thread. */
    @Getter
    private final TreeMap<Double, PriceLevelEntry> asks; // natural order → firstKey() = best ask

    private final ArrayDeque<String> diffBuffer; // raw diff JSON; populated only in SNAPSHOT_REQUESTED
    private long lastUpdateId;

    public OrderBook(String symbol, Market market, double filterThreshold) {
        this.symbol = symbol;
        this.market = market;
        this.filterThreshold = filterThreshold;
        this.state = OrderBookState.PENDING;
        this.bids = new TreeMap<>(Comparator.reverseOrder());
        this.asks = new TreeMap<>();
        this.diffBuffer = new ArrayDeque<>();
    }

    // -------------------------------------------------------------------------
    // State transitions (called from outside the consumer thread)
    // -------------------------------------------------------------------------

    /** Called by the SnapshotFetchQueue scheduler thread. Volatile write → SNAPSHOT_REQUESTED. */
    public void markSnapshotRequested() {
        log.debug("[{}/{}] OrderBook queued for snapshot", symbol, market);
        this.state = OrderBookState.SNAPSHOT_REQUESTED;
    }

    // -------------------------------------------------------------------------
    // Consumer thread entry points
    // -------------------------------------------------------------------------

    public OrderBookResult onDiff(String rawJson) {
        return switch (state) {
            case PENDING -> {
                log.debug("[{}/{}] Diff received: need snapshot", symbol, market);
                yield OrderBookResult.NEEDS_SNAPSHOT;
            }
            case SNAPSHOT_REQUESTED -> bufferDiff(rawJson);
            case SYNCED             -> applyLiveDiff(rawJson);
            default                 -> OrderBookResult.DROPPED;
        };
    }

    /**
     * Apply a REST snapshot and drain the diff buffer per the Binance local orderbook algorithm.
     * Transitions SNAPSHOT_REQUESTED → SYNCED on success, or → PENDING on any failure.
     */
    public OrderBookResult applySnapshot(String rawJson) {
        try {
            ArrayDeque<double[]> snapshotBids = new ArrayDeque<>();
            ArrayDeque<double[]> snapshotAsks = new ArrayDeque<>();
            long snapshotId = parseSnapshotEvent(rawJson, snapshotBids, snapshotAsks);
            if (snapshotId == -1) return resync();

            log.debug("[{}/{}] Snapshot received: snapshotId={}, buffered={}", symbol, market, snapshotId, diffBuffer.size());

            discardStaleDiffs(snapshotId);
            if (diffBuffer.isEmpty()) {
                log.warn("[{}/{}] Empty diff buffer after snapshot — re-syncing", symbol, market);
                return resync();
            }

            // snapshotId must fall within [U, u] of the first remaining diff.
            long[] boundary = parseUpdateIdRange(diffBuffer.peekFirst());
            if (snapshotId < boundary[0] || snapshotId > boundary[1]) {
                log.warn("[{}/{}] No valid sync point (snapshotId={}, U={}, u={}) — re-syncing",
                        symbol, market, snapshotId, boundary[0], boundary[1]);
                return resync();
            }

            loadSnapshotLevels(snapshotBids, snapshotAsks);

            // First buffered diff is applied without sequence validation (range already verified above).
            // lastUpdateId must be primed before the remaining diffs run their sequence checks.
            if (applyFirstBufferedDiff() != OrderBookResult.OK) return resync();
            lastUpdateId = boundary[1];

            if (drainRemainingDiffs() != OrderBookResult.OK) return resync();

            state = OrderBookState.SYNCED;
            log.debug("[{}/{}] OrderBook SYNCED: {} bid levels, {} ask levels", symbol, market, bids.size(), asks.size());
            return OrderBookResult.OK;

        } catch (IOException e) {
            log.warn("[{}/{}] Failed to apply snapshot: {}", symbol, market, e.getMessage());
            return resync();
        }
    }

    // -------------------------------------------------------------------------
    // Diff handling
    // -------------------------------------------------------------------------

    private OrderBookResult bufferDiff(String rawJson) {
        if (diffBuffer.size() >= MAX_BUFFER_SIZE) {
            log.warn("[{}/{}] Diff buffer overflow — forcing re-sync", symbol, market);
            return resync();
        }
        diffBuffer.addLast(rawJson);
        return OrderBookResult.OK;
    }

    private OrderBookResult applyLiveDiff(String rawJson) {
        long U = 0, u = 0, pu = 0;
        boolean sequenceValidated = false;
        boolean sequenceOk = true;

        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "U"  -> U  = p.getLongValue();
                    case "u"  -> u  = p.getLongValue();
                    case "pu" -> pu = p.getLongValue();
                    case "b", "a" -> {
                        // Binance guarantees U/u/pu always precede b/a in the diff JSON object.
                        if (!sequenceValidated) {
                            sequenceValidated = true;
                            sequenceOk = isSequenceValid(U, pu);
                        }
                        if (sequenceOk) applyLevelsDirectly(p, "b".equals(field) ? bids : asks);
                        else            p.skipChildren();
                    }
                    default -> p.skipChildren();
                }
            }
        } catch (IOException e) {
            log.warn("[{}/{}] Failed to parse diff: {}", symbol, market, e.getMessage());
            return resync();
        }

        if (!sequenceOk) return resync();

        apply30PercentFilter();
        lastUpdateId = u;
        return OrderBookResult.OK;
    }

    private boolean isSequenceValid(long U, long pu) {
        if (market == Market.SPOT && U != lastUpdateId + 1) {
            log.warn("[{}/{}] Sequence gap: expected U={}, got U={}", symbol, market, lastUpdateId + 1, U);
            return false;
        }
        if (market == Market.FUTURES && pu != lastUpdateId) {
            log.warn("[{}/{}] pu gap: expected pu={}, got pu={}", symbol, market, lastUpdateId, pu);
            return false;
        }
        return true;
    }

    private void applyLevelsDirectly(JsonParser p, TreeMap<Double, PriceLevelEntry> map) throws IOException {
        while (p.nextToken() != JsonToken.END_ARRAY) {
            p.nextToken();
            double price = Double.parseDouble(p.getString());
            p.nextToken();
            double qty   = Double.parseDouble(p.getString());
            p.nextToken(); // END_ARRAY of [price, qty]

            if (qty == 0.0) {
                map.remove(price);
            } else {
                PriceLevelEntry entry = map.get(price);
                if (entry == null) map.put(price, new PriceLevelEntry(qty, System.currentTimeMillis()));
                else               entry.quantity = qty;
            }
        }
    }

    private void apply30PercentFilter() {
        if (bids.isEmpty() || asks.isEmpty()) return;
        double midPrice = (bids.firstKey() + asks.firstKey()) / 2.0;
        double lower = midPrice * (1.0 - filterThreshold);
        double upper = midPrice * (1.0 + filterThreshold);
        bids.entrySet().removeIf(e -> {
            if (e.getKey() < lower || e.getKey() > upper) return true;
            e.getValue().distance = Math.abs(e.getKey() - midPrice) / midPrice * 100.0;
            return false;
        });
        asks.entrySet().removeIf(e -> {
            if (e.getKey() < lower || e.getKey() > upper) return true;
            e.getValue().distance = Math.abs(e.getKey() - midPrice) / midPrice * 100.0;
            return false;
        });
    }

    // -------------------------------------------------------------------------
    // Snapshot application helpers
    // -------------------------------------------------------------------------

    private void discardStaleDiffs(long snapshotId) throws IOException {
        // Binance docs say discard where u <= snapshotId for SPOT, but in practice snapshotId equals
        // the u of the most recent buffered diff, so <= empties the entire buffer. Strict < retains
        // the overlap event needed for the sync point check, matching the futures rule.
        while (!diffBuffer.isEmpty() && parseLastUpdateId(diffBuffer.peekFirst()) < snapshotId) {
            diffBuffer.pollFirst();
        }
    }

    private void loadSnapshotLevels(ArrayDeque<double[]> snapshotBids, ArrayDeque<double[]> snapshotAsks) {
        bids.clear();
        asks.clear();
        long now = System.currentTimeMillis();
        for (double[] level : snapshotBids) {
            if (level[1] > 0) bids.put(level[0], new PriceLevelEntry(level[1], now));
        }
        for (double[] level : snapshotAsks) {
            if (level[1] > 0) asks.put(level[0], new PriceLevelEntry(level[1], now));
        }
    }

    private OrderBookResult applyFirstBufferedDiff() {
        String rawJson = diffBuffer.pollFirst();
        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "b" -> applyLevelsDirectly(p, bids);
                    case "a" -> applyLevelsDirectly(p, asks);
                    default  -> p.skipChildren();
                }
            }
        } catch (IOException e) {
            log.warn("[{}/{}] Failed to parse first buffered diff: {}", symbol, market, e.getMessage());
            return OrderBookResult.DROPPED;
        }
        return OrderBookResult.OK;
    }

    private OrderBookResult drainRemainingDiffs() {
        for (String bufferedJson : diffBuffer) {
            if (applyLiveDiff(bufferedJson) != OrderBookResult.OK) return OrderBookResult.NEEDS_RESYNC;
        }
        return OrderBookResult.OK;
    }

    // -------------------------------------------------------------------------
    // JSON parsing helpers
    // -------------------------------------------------------------------------

    /** Parses both {@code U} and {@code u} from a diff JSON in one pass. Returns {@code [U, u]}. */
    private long[] parseUpdateIdRange(String rawJson) throws IOException {
        long U = 0, u = 0;
        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "U" -> U = p.getLongValue();
                    case "u" -> u = p.getLongValue();
                    default  -> p.skipChildren();
                }
            }
        }
        return new long[]{U, u};
    }

    /** Parses only the {@code u} (last update ID) field from a diff JSON. */
    private long parseLastUpdateId(String rawJson) throws IOException {
        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                if ("u".equals(field)) return p.getLongValue();
                p.skipChildren();
            }
        }
        return 0;
    }

    /** Parses a REST snapshot JSON. Returns lastUpdateId, or -1 on parse failure. Used only on the cold snapshot path. */
    private long parseSnapshotEvent(String rawJson, ArrayDeque<double[]> snapshotBids, ArrayDeque<double[]> snapshotAsks) {
        long snapshotId = -1;
        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "lastUpdateId" -> snapshotId = p.getLongValue();
                    case "bids"         -> parseLevelsInto(p, snapshotBids);
                    case "asks"         -> parseLevelsInto(p, snapshotAsks);
                    default             -> p.skipChildren();
                }
            }
        } catch (IOException e) {
            log.warn("[{}/{}] Failed to parse snapshot event: {}", symbol, market, e.getMessage());
            return -1;
        }
        return snapshotId;
    }

    /** Used only by parseSnapshotEvent (cold path). */
    private void parseLevelsInto(JsonParser p, ArrayDeque<double[]> levels) throws IOException {
        while (p.nextToken() != JsonToken.END_ARRAY) {
            p.nextToken();
            double price = Double.parseDouble(p.getString());
            p.nextToken();
            double qty   = Double.parseDouble(p.getString());
            p.nextToken(); // END_ARRAY
            levels.add(new double[]{price, qty});
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    private OrderBookResult resync() {
        diffBuffer.clear();
        state = OrderBookState.PENDING;
        return OrderBookResult.NEEDS_RESYNC;
    }

    // -------------------------------------------------------------------------
    // Observability
    // -------------------------------------------------------------------------

    public TreeMap<Double, PriceLevelEntry> snapshotBids() {
        return new TreeMap<>(bids);
    }

    public TreeMap<Double, PriceLevelEntry> snapshotAsks() {
        return new TreeMap<>(asks);
    }
}
```
