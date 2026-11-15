package dev.abu.screener_backend.marketdata.adapter.bybit;

import ch.randelshofer.fastdoubleparser.JavaDoubleParser;
import dev.abu.screener_backend.marketdata.core.book.BookSlot;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;
import dev.abu.screener_backend.marketdata.core.book.OrderBookState;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.DepthEvent;
import dev.abu.screener_backend.marketdata.core.ingress.EventType;
import dev.abu.screener_backend.marketdata.spi.BookSyncContext;
import dev.abu.screener_backend.marketdata.spi.DepthSyncStrategy;
import dev.abu.screener_backend.marketdata.spi.RecoverySink;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

/**
 * Bybit depth sync, for {@code orderbook.1000} on spot and linear alike: one class, one instance
 * per venue. Instantiated by {@code BybitAdapterConfig} — deliberately not a {@code @Component}.
 *
 * <p>The progress doc's §7 shape (one flat dispatch in {@link #onEvent}, {@link #recover} called
 * only from there, buffering only while {@code RECOVERING}, {@code PENDING} retrying on every
 * delta), with one addition and a simpler sequence rule.
 *
 * <h3>The in-stream snapshot is a full replacement, in any state</h3>
 * Bybit sends the book itself as the first frame of every subscription
 * ({@code external-docs/bybit/bybit-api-contracts.md} §5.3), and again after a service restart, then
 * flagged {@code u == 1} (§5.4). Either one resets the context, replaces the levels and marks the
 * book {@code SYNCED}, whatever its state. So a cold start and a reconnect cost no REST call. A
 * REST outcome that lands afterwards is dropped by the usual state guard, and deltas buffered for it
 * are discarded: they all precede the snapshot on the stream.
 *
 * <h3>Sequence rule — {@code u} is strictly {@code +1}</h3>
 * <pre>
 * u &lt;= lastU        → IGNORE     (already in the book; only seen while draining the buffer)
 * u == lastU + 1    → OK         (cursor → u)
 * otherwise         → DE_SYNCED  (a gap)
 * </pre>
 * A REST snapshot's {@code u} is on the {@code orderbook.1000} counter (contracts §7.1), so the
 * same rule stitches it to the buffered deltas. {@code seq} is not used.
 *
 * <h3>Sequence fields come after the levels</h3>
 * {@code {"topic":…,"type":…,"ts":…,"data":{"s":…,"b":[…],"a":[…],"u":…,"seq":…},"cts":…}} on
 * linear; spot swaps {@code ts} and {@code type}. {@code type} is found near the start of the frame
 * and {@code u} near its end, both by string scan with no allocation, and only a frame that passes
 * the check has its levels streamed into the book. The WS frame's {@code data} and the REST body's
 * {@code result} hold the same inner shape, so one walk serves both. Prices and sizes are strings,
 * padded on linear and trimmed on spot; both parse to the same {@code double} key. Size {@code 0}
 * deletes the level, which is also how the server reports a level leaving the top-1000 window, so no
 * local trim is needed.
 */
@Slf4j
public class BybitSyncStrategy implements DepthSyncStrategy {

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    private static final String TYPE_FIELD = "\"type\":\"";
    private static final String SNAPSHOT_TYPE = "snapshot";
    private static final String U_FIELD = "\"u\":";

    /** A delta carrying this {@code u} is a full book sent after a service restart (contracts §5.4). */
    static final long RESTART_U = 1;

    private final RecoverySink recoverSink;
    private final PipelineMetrics metrics;

    public BybitSyncStrategy(RecoverySink recoverSink, PipelineMetrics metrics) {
        this.recoverSink = recoverSink;
        this.metrics = metrics;
    }

    @Override
    public BookSyncContext newContext() {
        return new BybitSyncContext();
    }

    @Override
    public void onEvent(BookSlot slot, DepthEvent event) {
        BybitSyncContext ctx = (BybitSyncContext) slot.ctx();
        OrderBookState state = slot.book().getState();

        if (event.type == EventType.REST_MSG) {
            if (state != OrderBookState.RECOVERING) return;
            if (!handleSnapshot(slot, event.rawJson)) {
                recover(slot, ctx);
            } else {
                slot.book().markSynced();
            }
            return;
        }

        if (event.type == EventType.REST_FAILED) {
            // As on Binance: no recover() (it would re-request at once and count a resync), and no
            // clearLevels() (a RECOVERING book is already empty). The next delta re-asks.
            if (state == OrderBookState.RECOVERING) {
                ctx.reset();
                slot.book().markPending();
                log.debug("[{}] snapshot request failed - back to PENDING", slot.instrument().logName());
            }
            return;
        }

        String frame = event.rawJson;
        long u = readU(frame);

        if (u == RESTART_U || isSnapshotType(frame)) {
            if (!replaceBook(slot, ctx, frame, state)) {
                recover(slot, ctx);
            }
            return;
        }

        if (state == OrderBookState.SYNCED) {
            if (!handleDiff(slot, frame, u)) {
                recover(slot, ctx);
            }
            return;
        }

        if (state == OrderBookState.RECOVERING && !ctx.bufferDiff(frame, slot.instrument().logName())) {
            recover(slot, ctx);
            return;
        }

        if (state == OrderBookState.PENDING && recoverSink.requestRecovery(slot)) {
            slot.book().markRecovering();
            ctx.bufferDiff(frame, slot.instrument().logName());
        }
    }

    /**
     * {@code true} if the frame's {@code type} is {@code snapshot}. {@code type} precedes
     * {@code data} in both field orders, so the scan stops within the first hundred chars.
     */
    static boolean isSnapshotType(String frame) {
        int at = frame.indexOf(TYPE_FIELD);
        return at != -1 && frame.startsWith(SNAPSHOT_TYPE, at + TYPE_FIELD.length());
    }

    /**
     * Reads {@code data.u} by scanning from the end of the frame, where Bybit puts it: only
     * {@code seq} and {@code cts} follow. No parser, no allocation. No other field is named
     * {@code u}, and prices are quoted strings, so the last {@code "u":} is the one.
     *
     * @return {@code u}, or {@code -1} if the frame has none or it is not an integer
     */
    static long readU(String frame) {
        int at = frame.lastIndexOf(U_FIELD);
        if (at == -1) return -1;
        int i = at + U_FIELD.length();
        int start = i;
        long value = 0;
        char c;
        while (i < frame.length() && (c = frame.charAt(i)) >= '0' && c <= '9') {
            value = value * 10 + (c - '0');
            i++;
        }
        return i == start ? -1 : value;
    }

    /**
     * Validates one delta's {@code u} against the book's cursor, and moves the cursor on {@code OK}.
     *
     * @throws IllegalStateException if the delta has no readable {@code u}: a frame that cannot be
     *         sequenced must resync rather than be skipped as stale
     */
    CheckResult check(long u, BybitSyncContext ctx, String logName) {
        if (u < 0) {
            throw new IllegalStateException("delta has no readable data.u — Bybit frame shape changed?");
        }
        if (u <= ctx.lastU) {
            return CheckResult.IGNORE;
        }
        if (u == ctx.lastU + 1) {
            ctx.lastU = u;
            return CheckResult.OK;
        }
        log.debug("[{}] sequence gap: expected u={}, got u={}", logName, ctx.lastU + 1, u);
        return CheckResult.DE_SYNCED;
    }

    boolean handleDiff(BookSlot slot, String frame, long u) {
        BybitSyncContext ctx = (BybitSyncContext) slot.ctx();
        try {
            CheckResult result = check(u, ctx, slot.instrument().logName());

            if (result == CheckResult.DE_SYNCED) {
                return false;
            }

            if (result == CheckResult.OK) {
                applyBook(slot.book(), frame);
                slot.book().computeDistance();
            }
            return true;

        } catch (Exception e) {
            log.warn("[{}] Failed to parse diff: {}", slot.instrument().logName(), e.getMessage());
            return false;
        }
    }

    /**
     * Replaces the book with an in-stream snapshot, whatever its state, and marks it
     * {@code SYNCED}. Any buffered deltas are discarded and a pending REST outcome will be dropped
     * by the state guard.
     */
    boolean replaceBook(BookSlot slot, BybitSyncContext ctx, String frame, OrderBookState previous) {
        ctx.reset();
        slot.book().clearLevels();
        try {
            long u = applyBook(slot.book(), frame);
            if (u < 0) {
                log.warn("[{}] in-stream snapshot has no data.u", slot.instrument().logName());
                return false;
            }
            slot.book().computeDistance();
            ctx.lastU = u;
            slot.book().markSynced();
            log.debug("[{}] in-stream snapshot at u={} replaced a {} book", slot.instrument().logName(), u, previous);
            return true;
        } catch (Exception e) {
            log.warn("[{}] Failed to parse in-stream snapshot: {}", slot.instrument().logName(), e.getMessage());
            return false;
        }
    }

    /**
     * Loads a REST snapshot into the (empty) book and drains the buffer through {@link #handleDiff},
     * check included. An empty or all-stale buffer is success.
     */
    boolean handleSnapshot(BookSlot slot, String body) {
        try {
            long u = applySnapshot(slot, body);
            if (u == -1) return false;
            slot.book().computeDistance();

            BybitSyncContext ctx = (BybitSyncContext) slot.ctx();
            ctx.lastU = u;

            while (!ctx.diffBuffer.isEmpty()) {
                String diff = ctx.diffBuffer.pollFirst();
                if (!handleDiff(slot, diff, readU(diff))) {
                    ctx.diffBuffer.clear();
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("[{}] Failed in handleSnapshot(): {}", slot.instrument().logName(), e.getMessage());
            return false;
        }
    }

    /**
     * Streams a REST snapshot body, {@code {"retCode":0,…,"result":{"s":…,"a":[…],"b":[…],"u":…,…}}},
     * into the (empty) book.
     *
     * @return the snapshot's {@code u}, or {@code -1} if the body is malformed or has no
     *         {@code result.u}
     */
    long applySnapshot(BookSlot slot, String body) {
        try {
            long u = applyBook(slot.book(), body);
            if (u == -1) {
                log.warn("[{}] REST snapshot has no result.u", slot.instrument().logName());
            }
            return u;
        } catch (Exception e) {
            log.warn("[{}] Failed to parse snapshot: {}", slot.instrument().logName(), e.getMessage());
            return -1;
        }
    }

    /**
     * Applies the {@code b} / {@code a} of the top-level {@code data} (WS frame) or {@code result}
     * (REST body) object. Stops at the end of that object, so a frame's trailing {@code cts} is never
     * read.
     *
     * @return its {@code u}, or {@code -1} if there is no such object or it has no {@code u}
     */
    private static long applyBook(OrderBook book, String json) {
        long millis = System.currentTimeMillis();
        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), json)) {
            p.nextToken();
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if (value == JsonToken.START_OBJECT && (field.equals("data") || field.equals("result"))) {
                    return applyLevels(p, book, millis);
                }
                p.skipChildren();
            }
            return -1;
        }
    }

    /** Walks the book object, with the parser on its {@code START_OBJECT}. */
    private static long applyLevels(JsonParser p, OrderBook book, long millis) {
        long u = -1;
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "b" -> applySide(true, p, book, millis);
                case "a" -> applySide(false, p, book, millis);
                case "u" -> u = p.getLongValue();
                default -> p.skipChildren();
            }
        }
        return u;
    }

    /**
     * One side's {@code [["price","size"], …]}. Both are strings, parsed from the parser's character
     * buffer with {@code JavaDoubleParser}: no {@code String} per level.
     */
    private static void applySide(boolean isBid, JsonParser p, OrderBook book, long millis) {
        while (p.nextToken() != JsonToken.END_ARRAY) {
            p.nextToken();
            double price = JavaDoubleParser.parseDouble(p.getStringCharacters(), p.getStringOffset(), p.getStringLength());

            p.nextToken();
            double size = JavaDoubleParser.parseDouble(p.getStringCharacters(), p.getStringOffset(), p.getStringLength());

            book.applyLevel(isBid, price, size, millis);

            // anything Bybit appends to the row later
            while (p.nextToken() != JsonToken.END_ARRAY) {
                p.skipChildren();
            }
        }
    }

    /**
     * The single recovery path — called from {@link #onEvent} and nowhere else, which is what
     * bounds the sink to at most one request per event.
     */
    private void recover(BookSlot slot, BybitSyncContext ctx) {
        metrics.recordResync(slot.instrument().venue());
        ctx.reset();
        slot.book().clearLevels();
        slot.book().markPending();
        boolean queued = recoverSink.requestRecovery(slot);
        if (queued) {
            slot.book().markRecovering();
        }
        log.debug("[{}] recovering - snapshot {}", slot.instrument().logName(),
                queued ? "requested" : "refused, will retry on next diff");
    }

    enum CheckResult {
        OK, IGNORE, DE_SYNCED
    }
}
