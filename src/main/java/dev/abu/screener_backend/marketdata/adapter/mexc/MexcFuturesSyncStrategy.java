package dev.abu.screener_backend.marketdata.adapter.mexc;

import ch.randelshofer.fastdoubleparser.JavaDoubleParser;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcVersionRange.CheckResult;
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
 * MEXC futures depth sync. Instantiated by {@code MexcAdapterConfig} — deliberately not a
 * {@code @Component}.
 *
 * <p>Same shape as {@code BinanceDepthSyncStrategy} (progress doc §7): one flat dispatch in
 * {@link #onEvent}, {@link #recover} called only from there, buffering only while
 * {@code RECOVERING}, {@code PENDING} retrying on every push, and every helper reporting failure by
 * returning rather than touching the sink. Three things differ.
 *
 * <h3>Sequence rule — {@code begin} / {@code end}, not {@code version}</h3>
 * MEXC documents {@code version == previous + 1} per push. It is not true: pushes are aggregated
 * and {@code version} jumps by up to several hundred. Every push does carry undocumented
 * {@code begin} / {@code end} fields giving the version range it covers, and those are contiguous
 * ({@code external-docs/mexc/mexc-depth-versioning-empirical.md}). That makes the stream
 * Binance-spot-shaped, with {@code begin} ≙ {@code U} and {@code end} ≙ {@code u}, and one
 * predicate ({@link MexcVersionRange}, shared with MEXC spot) both finds the post-snapshot sync
 * point and validates every push after it. The snapshot's {@code version} is on the same counter but not aligned to push
 * boundaries — about a third of snapshots land inside a push's range, which the predicate accepts.
 *
 * <h3>Sequence fields come after the levels</h3>
 * A push is {@code {"symbol":…,"data":{"cts":…,"asks":[…],"bids":[…],"end":…,"begin":…,"version":…},
 * "channel":"push.depth","ts":…}}. Binance's single pass relies on its sequence fields preceding
 * the levels; here they follow them. So {@code begin} / {@code end} are located by a string scan
 * from the end of the raw frame and parsed by hand, the push is validated, and only then are the
 * levels streamed into the book in one pass. Allocation-free, and indifferent to field order.
 *
 * <h3>Levels are numbers, in contracts</h3>
 * Rows are {@code [price, vol, orderCount]} as JSON numbers, and {@code vol} counts contracts.
 * Each quantity is multiplied by the instrument's {@code quantityMultiplier} (its contract size) at
 * parse time, so the {@code OrderBook} holds base asset like every other venue's.
 */
@Slf4j
public class MexcFuturesSyncStrategy implements DepthSyncStrategy {

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    private static final String BEGIN_FIELD = "\"begin\":";
    private static final String END_FIELD = "\"end\":";

    private final RecoverySink recoverSink;
    private final PipelineMetrics metrics;

    public MexcFuturesSyncStrategy(RecoverySink recoverSink, PipelineMetrics metrics) {
        this.recoverSink = recoverSink;
        this.metrics = metrics;
    }

    @Override
    public BookSyncContext newContext() {
        return new MexcSyncContext();
    }

    @Override
    public void onEvent(BookSlot slot, DepthEvent event) {
        MexcSyncContext ctx = (MexcSyncContext) slot.ctx();
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
            // clearLevels() (a RECOVERING book is already empty). The next push re-asks.
            if (state == OrderBookState.RECOVERING) {
                ctx.reset();
                slot.book().markPending();
                log.debug("[{}] snapshot request failed - back to PENDING", slot.instrument().logName());
            }
            return;
        }

        if (state == OrderBookState.SYNCED) {
            if (!handleDiff(slot, event.rawJson)) {
                recover(slot, ctx);
            }
            return;
        }

        if (state == OrderBookState.RECOVERING && !ctx.bufferDiff(event.rawJson, slot.instrument().logName())) {
            recover(slot, ctx);
            return;
        }

        if (state == OrderBookState.PENDING && recoverSink.requestRecovery(slot)) {
            slot.book().markRecovering();
            ctx.bufferDiff(event.rawJson, slot.instrument().logName());
        }
    }

    /**
     * Validates one push against the book's cursor, by its {@code begin} / {@code end} range — the
     * rule in {@link MexcVersionRange#check}. Moves the cursor to {@code end} on {@code OK}.
     *
     * @throws IllegalStateException if the frame carries no {@code begin} / {@code end} — they are
     *         undocumented, so their disappearance must be loud rather than a silent drift
     */
    CheckResult check(String rawJson, MexcSyncContext ctx, String logName) {
        long begin = readVersionField(rawJson, BEGIN_FIELD);
        long end = readVersionField(rawJson, END_FIELD);

        CheckResult result = MexcVersionRange.check(begin, end, ctx.lastVersion);
        if (result == CheckResult.OK) {
            ctx.lastVersion = end;
        } else if (result == CheckResult.DE_SYNCED) {
            log.debug("[{}] sequence gap: expected begin <= {}, got begin={} (end={})",
                    logName, ctx.lastVersion + 1, begin, end);
        }
        return result;
    }

    /**
     * Reads a non-negative integer field by scanning from the end of the frame, where MEXC puts its
     * sequence fields. No parser, no allocation.
     */
    static long readVersionField(String json, String field) {
        int at = json.lastIndexOf(field);
        if (at == -1) {
            throw new IllegalStateException(field + " not found in push.depth frame — MEXC frame shape changed?");
        }
        int i = at + field.length();
        int start = i;
        long value = 0;
        char c;
        while (i < json.length() && (c = json.charAt(i)) >= '0' && c <= '9') {
            value = value * 10 + (c - '0');
            i++;
        }
        if (i == start) {
            throw new IllegalStateException(field + " is not an integer in push.depth frame");
        }
        return value;
    }

    boolean handleDiff(BookSlot slot, String rawJson) {
        MexcSyncContext ctx = (MexcSyncContext) slot.ctx();

        try {
            CheckResult result = check(rawJson, ctx, slot.instrument().logName());

            if (result == CheckResult.DE_SYNCED) {
                return false;
            }

            if (result == CheckResult.OK) {
                applyData(slot, rawJson);
                slot.book().computeDistance();
            }
            return true;

        } catch (Exception e) {
            log.warn("[{}] Failed to parse diff: {}", slot.instrument().logName(), e.getMessage());
            return false;
        }
    }

    boolean handleSnapshot(BookSlot slot, String rawJson) {
        try {
            // the book is guaranteed to be empty here
            long version = applySnapshot(slot, rawJson);
            if (version == -1) return false;
            slot.book().computeDistance();

            MexcSyncContext ctx = (MexcSyncContext) slot.ctx();
            ctx.lastVersion = version;

            while (!ctx.diffBuffer.isEmpty()) {
                String diff = ctx.diffBuffer.pollFirst();
                if (!handleDiff(slot, diff)) {
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
     * Streams a REST snapshot body, {@code {"success":true,"code":0,"data":{"asks":[…],"bids":[…],
     * "version":…,…}}}, into the (empty) book.
     *
     * @return the snapshot's {@code version}, or {@code -1} if the body is malformed or has no
     *         {@code data.version} — which covers a throttled {@code success:false} body, should one
     *         ever get past the fetcher
     */
    long applySnapshot(BookSlot slot, String rawJson) {
        try {
            return applyData(slot, rawJson);
        } catch (Exception e) {
            log.warn("[{}] Failed to parse snapshot: {}", slot.instrument().logName(), e.getMessage());
            return -1;
        }
    }

    /**
     * Applies the {@code asks} / {@code bids} of the top-level {@code data} object — the same walk
     * for a push frame and a snapshot body. Stops at the end of {@code data}, so a push's trailing
     * {@code channel} / {@code ts} are never read.
     *
     * @return {@code data.version}, or {@code -1} if there is no {@code data} object or it has no
     *         {@code version}
     */
    private long applyData(BookSlot slot, String rawJson) {
        OrderBook book = slot.book();
        double multiplier = slot.instrument().quantityMultiplier();
        long millis = System.currentTimeMillis();

        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if (value == JsonToken.START_OBJECT && field.equals("data")) {
                    return applyLevels(p, book, multiplier, millis);
                }
                p.skipChildren();
            }
            return -1;
        }
    }

    /** Walks the {@code data} object, with the parser on its {@code START_OBJECT}. */
    private long applyLevels(JsonParser p, OrderBook book, double multiplier, long millis) {
        long version = -1;
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "asks" -> applySide(false, p, book, multiplier, millis);
                case "bids" -> applySide(true, p, book, multiplier, millis);
                case "version" -> version = p.getLongValue();
                default -> p.skipChildren();
            }
        }
        return version;
    }

    /**
     * One side's {@code [[price, vol, orderCount], …]}. The numbers are read from the parser's
     * character buffer and parsed with {@code JavaDoubleParser} — no {@code String} per level, and
     * the same price text yields the same {@code double} key on the snapshot and the push paths.
     */
    private void applySide(boolean isBid, JsonParser p, OrderBook book, double multiplier, long millis) {
        while (p.nextToken() != JsonToken.END_ARRAY) {
            p.nextToken();
            double price = JavaDoubleParser.parseDouble(p.getStringCharacters(), p.getStringOffset(), p.getStringLength());

            p.nextToken();
            double vol = JavaDoubleParser.parseDouble(p.getStringCharacters(), p.getStringOffset(), p.getStringLength());

            book.applyLevel(isBid, price, vol * multiplier, millis);

            // skip orderCount, and anything MEXC appends to the row later
            while (p.nextToken() != JsonToken.END_ARRAY) {
                p.skipChildren();
            }
        }
    }

    /**
     * The single recovery path — called from {@link #onEvent} and nowhere else, which is what
     * bounds the sink to at most one request per event.
     */
    private void recover(BookSlot slot, MexcSyncContext ctx) {
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
}
