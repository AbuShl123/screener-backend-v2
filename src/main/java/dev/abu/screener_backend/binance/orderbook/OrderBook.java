package dev.abu.screener_backend.binance.orderbook;

import ch.randelshofer.fastdoubleparser.JavaDoubleParser;
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

    @Getter
    final String symbol;

    @Getter
    final Market market;

    @Getter
    volatile OrderBookState state;

    private final double filterThreshold;

    /** Live bids TreeMap. Must only be accessed by this shard's consumer thread. */
    @Getter
    private final TreeMap<Double, PriceLevelEntry> bids; // reverseOrder → firstKey() = best bid

    /** Live asks TreeMap. Must only be accessed by this shard's consumer thread. */
    @Getter
    private final TreeMap<Double, PriceLevelEntry> asks; // natural order → firstKey() = best ask

    private final ArrayDeque<String> diffBuffer;         // raw diff JSON; populated only in SNAPSHOT_REQUESTED

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

    // --- State transitions called from outside the consumer thread ---

    /** Called by SnapshotFetchQueue scheduler thread. */
    public void markSnapshotRequested() {
        log.debug("[{}/{}] OrderBook queued for snapshot", symbol, market);
        this.state = OrderBookState.SNAPSHOT_REQUESTED;
    }

    // --- Consumer thread entry points ---

    /** Route an incoming diff to the appropriate state handler. */
    public OrderBookResult onDiff(String rawJson) {
        switch (state) {
            case PENDING:
                log.debug("[{}/{}] Diff received: need snapshot", symbol, market);
                return OrderBookResult.NEEDS_SNAPSHOT;

            case SNAPSHOT_REQUESTED:
                if (diffBuffer.size() >= MAX_BUFFER_SIZE) {
                    log.warn("[{}/{}] Diff buffer overflow — forcing re-sync", symbol, market);
                    diffBuffer.clear();
                    state = OrderBookState.PENDING;
                    return OrderBookResult.NEEDS_RESYNC;
                }
                diffBuffer.addLast(rawJson);
                return OrderBookResult.OK;

            case SYNCED:
                return applyLiveDiff(rawJson);

            default:
                return OrderBookResult.DROPPED;
        }
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
            if (snapshotId == -1) {
                return resync();
            }
            log.debug("[{}/{}] Snapshot received: snapshotId={}, buffered events={}", symbol, market, snapshotId, diffBuffer.size());

            // Step 1: Discard invalid buffered diffs
            discardInvalidDiffsFromBuffer(snapshotId);
            if (diffBuffer.isEmpty()) {
                log.warn("[{}/{}] Empty diff buffer after snapshot — re-syncing", symbol, market);
                return resync();
            }

            // Step 2: snapshotId should be in [U;u] range of the first buffered diff
            long u = parseUField(diffBuffer.peekFirst());
            long _U = parseUpperUField(diffBuffer.peekFirst());
            if (snapshotId < _U || snapshotId > u) {
                log.warn("[{}/{}] no valid sync point (snapshotId={}, u={}, U={}) — re-syncing", symbol, market, snapshotId, u, _U);
                return resync();
            }

            // Step 3: Load snapshot into TreeMaps
            bids.clear();
            asks.clear();
            long now = System.currentTimeMillis();
            for (double[] level : snapshotBids) {
                if (level[1] > 0) bids.put(level[0], new PriceLevelEntry(level[1], now));
            }
            for (double[] level : snapshotAsks) {
                if (level[1] > 0) asks.put(level[0], new PriceLevelEntry(level[1], now));
            }

            // Step 4: apply level updates for the first event (special case)
            var result = applyLevelUpdatesFirstEvent(diffBuffer.pollFirst());
            if (result != OrderBookResult.OK) {
                return resync();
            }
            lastUpdateId = u;

            // Step 5: apply level updates for all the remaining buffered diffs
            for (String bufferedJson : diffBuffer) {
                if (applyLiveDiff(bufferedJson) != OrderBookResult.OK) {
                    return resync();
                }
            }

            state = OrderBookState.SYNCED;
            log.debug("[{}/{}] OrderBook SYNCED: — {} bid levels, {} ask levels", symbol, market, bids.size(), asks.size());
            return OrderBookResult.OK;

        } catch (IOException e) {
            log.warn("[{}/{}] Failed to apply snapshot: {}", symbol, market, e.getMessage());
            return resync();
        }
    }

    // --- Private helpers ---

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
                            if (market == Market.SPOT && U != lastUpdateId + 1) {
                                log.debug("[{}/{}] Sequence gap: expected U={}, got U={}", symbol, market, lastUpdateId + 1, U);
                                sequenceOk = false;
                            } else if (market == Market.FUTURES && pu != lastUpdateId) {
                                log.debug("[{}/{}] pu gap: expected pu={}, got pu={}", symbol, market, lastUpdateId, pu);
                                sequenceOk = false;
                            }
                        }
                        if (sequenceOk) {
                            applyLevelsDirectly(p, "b".equals(field) ? bids : asks);
                        } else {
                            p.skipChildren();
                        }
                    }
                    default -> p.skipChildren();
                }
            }
        } catch (IOException e) {
            log.warn("[{}/{}] Failed to parse diff: {}", symbol, market, e.getMessage());
            return resync();
        }

        if (!sequenceOk) return resync();

        computeDistance();
        lastUpdateId = u;
        return OrderBookResult.OK;
    }

    private OrderBookResult applyLevelUpdatesFirstEvent(String rawJson) {
        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "b" -> applyLevelsDirectly(p, bids);
                    case "a" -> applyLevelsDirectly(p, asks);
                    default -> p.skipChildren();
                }
            }
        } catch (IOException e) {
            log.warn("[{}/{}] Failed to parse first buffered diff: {}", symbol, market, e.getMessage());
            return OrderBookResult.DROPPED;
        }
        return OrderBookResult.OK;
    }

    private long parseSnapshotEvent(String rawJson, ArrayDeque<double[]> snapshotBids, ArrayDeque<double[]> snapshotAsks) {
        long snapshotId = -1;
        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "lastUpdateId" -> snapshotId = p.getLongValue();
                    case "bids" -> parseLevelsInto(p, snapshotBids);
                    case "asks" -> parseLevelsInto(p, snapshotAsks);
                    default -> p.skipChildren();
                }
            }
        } catch (IOException e) {
            log.warn("[{}/{}] Failed to parse snapshot event: {}", symbol, market, e.getMessage());
            return -1;
        }
        return snapshotId;
    }

    private void applyLevelsDirectly(JsonParser p, TreeMap<Double, PriceLevelEntry> map) throws IOException {
        while (p.nextToken() != JsonToken.END_ARRAY) {
            p.nextToken();
            char[] buf = p.getStringCharacters();
            int offset = p.getStringOffset();
            int len    = p.getStringLength();
            double price = JavaDoubleParser.parseDouble(buf, offset, len);

            p.nextToken();
            buf = p.getStringCharacters();
            offset = p.getStringOffset();
            len    = p.getStringLength();
            double qty   = JavaDoubleParser.parseDouble(buf, offset, len);
            p.nextToken(); // END_ARRAY of [price, qty]

            if (qty == 0.0) {
                map.remove(price);
            } else {
                PriceLevelEntry entry = map.get(price);
                if (entry == null) {
                    map.put(price, new PriceLevelEntry(qty, System.currentTimeMillis()));
                } else {
                    entry.quantity = qty;
                }
            }
        }
    }

    /** Parse only the lowercase {@code u} (final update id) field from a diff JSON. */
    private long parseUField(String rawJson) throws IOException {
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

    /** Parse only the uppercase {@code U} (first update id) field from a diff JSON. */
    private long parseUpperUField(String rawJson) throws IOException {
        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                if ("U".equals(field)) return p.getLongValue();
                p.skipChildren();
            }
        }
        return 0;
    }

    /**
     * Parse bid or ask levels from a JSON array currently positioned at START_ARRAY.
     * Each level is a two-element string array: [price, qty].
     * Used only by parseSnapshotEvent (cold path).
     */
    private void parseLevelsInto(JsonParser p, ArrayDeque<double[]> levels) throws IOException {
        while (p.nextToken() != JsonToken.END_ARRAY) {
            // p is at START_ARRAY for [price, qty]
            p.nextToken();
            double price = Double.parseDouble(p.getString());
            p.nextToken();
            double qty = Double.parseDouble(p.getString());
            p.nextToken(); // END_ARRAY
            levels.add(new double[]{price, qty});
        }
    }

    /**
     * Sweep all levels outside ±filterThreshold of mid-price and update distance on survivors.
     * <p>
     * {@code distance} is stored as a <b>fraction</b> of mid-price ({@code 0.05} = 5%), never a
     * percentage. This is the project-wide unit for proximity: the classifier and every
     * {@link dev.abu.screener_backend.analysis.ClassificationRule} compare it directly against
     * fractional thresholds, and the orderbook's own {@code filterThreshold} is a fraction too.
     */
    private void computeDistance() {
        if (bids.isEmpty() || asks.isEmpty()) return;
        double midPrice = (bids.firstKey() + asks.firstKey()) / 2.0;
        double lower = midPrice * (1.0 - filterThreshold);
        double upper = midPrice * (1.0 + filterThreshold);

        var bidIt = bids.entrySet().iterator();
        while (bidIt.hasNext()) {
            var e = bidIt.next();
            double key = e.getKey();
            if (key < lower || key > upper) {
                bidIt.remove();
            } else {
                e.getValue().distance = Math.abs(key - midPrice) / midPrice;
            }
        }

        var askIt = asks.entrySet().iterator();
        while (askIt.hasNext()) {
            var e = askIt.next();
            double key = e.getKey();
            if (key < lower || key > upper) {
                askIt.remove();
            } else {
                e.getValue().distance = Math.abs(key - midPrice) / midPrice;
            }
        }
    }

    private void discardInvalidDiffsFromBuffer(long snapshotId) throws IOException {
        while (!diffBuffer.isEmpty()) {
            long u = parseUField(diffBuffer.peekFirst());

            // Binance Docs state that for SPOT, events where u<=snapshotId should be discarded
            // BUT when this rule is used in practice, SPOT order books NEVER sync: snapshotId always equals to u in the buffered events
            // Therefore, applying STRICT comparison, just like in futures docs:
            if (u < snapshotId) {
                diffBuffer.pollFirst();
            } else break;
        }
    }

    public TreeMap<Double, PriceLevelEntry> snapshotBids() {
        return new TreeMap<>(bids);
    }

    public TreeMap<Double, PriceLevelEntry> snapshotAsks() {
        return new TreeMap<>(asks);
    }

    private OrderBookResult resync() {
        diffBuffer.clear();
        state = OrderBookState.PENDING;
        return OrderBookResult.NEEDS_RESYNC;
    }
}
