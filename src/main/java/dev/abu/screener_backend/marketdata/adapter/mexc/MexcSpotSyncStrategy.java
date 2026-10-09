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

import java.nio.ByteBuffer;

/**
 * MEXC spot depth sync. Not a {@code @Component}: the adapter config will instantiate it.
 *
 * <p>Same shape as {@link MexcFuturesSyncStrategy} (progress doc §7): one flat dispatch in
 * {@link #onEvent}, {@link #recover} called only from there, buffering only while
 * {@code RECOVERING}, {@code PENDING} retrying on every push, and every helper reporting failure by
 * returning rather than touching the sink. Two things differ.
 *
 * <h3>Pushes are Protobuf, snapshots are JSON</h3>
 * A {@code WS_MSG} is always a binary {@code aggre.depth} frame in {@code rawBytes}, read by
 * {@link MexcSpotFrameReader}. A {@code REST_MSG} is a {@code GET /api/v3/depth} body in
 * {@code rawJson}, Binance-spot-shaped: {@code {"lastUpdateId":…,"bids":[["p","q"],…],"asks":[…]}}.
 *
 * <h3>Sequence rule</h3>
 * {@code fromVersion} / {@code toVersion} play the part of futures' {@code begin} / {@code end},
 * and {@code lastUpdateId} that of the snapshot's {@code version}; the predicate is
 * {@link MexcVersionRange}. Phase 0 measured the same shape as futures: consecutive ranges exactly
 * contiguous, snapshots often landing inside a push's range, and {@code lastUpdateId} inclusive
 * ({@code external-docs/mexc/mexc-spot-depth-empirical.md} §2). As on futures, the versions follow
 * the levels on the wire, so a push is validated before any of its levels are read.
 *
 * <p>Quantities are base asset already; the instrument's {@code quantityMultiplier} is not applied.
 */
@Slf4j
public class MexcSpotSyncStrategy implements DepthSyncStrategy {

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    private final RecoverySink recoverSink;
    private final PipelineMetrics metrics;

    public MexcSpotSyncStrategy(RecoverySink recoverSink, PipelineMetrics metrics) {
        this.recoverSink = recoverSink;
        this.metrics = metrics;
    }

    @Override
    public BookSyncContext newContext() {
        return new MexcSpotSyncContext();
    }

    @Override
    public void onEvent(BookSlot slot, DepthEvent event) {
        MexcSpotSyncContext ctx = (MexcSpotSyncContext) slot.ctx();
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
            if (!handleDiff(slot, event.rawBytes)) {
                recover(slot, ctx);
            }
            return;
        }

        if (state == OrderBookState.RECOVERING && !ctx.bufferDiff(event.rawBytes, slot.instrument().logName())) {
            recover(slot, ctx);
            return;
        }

        if (state == OrderBookState.PENDING && recoverSink.requestRecovery(slot)) {
            slot.book().markRecovering();
            ctx.bufferDiff(event.rawBytes, slot.instrument().logName());
        }
    }

    /**
     * Validates one push against the book's cursor and applies its levels if accepted. Moves the
     * cursor to {@code toVersion} on {@code OK}.
     *
     * @return {@code false} on a gap or a malformed frame; the caller resyncs
     */
    boolean handleDiff(BookSlot slot, ByteBuffer frame) {
        MexcSpotSyncContext ctx = (MexcSpotSyncContext) slot.ctx();

        try {
            long body = MexcSpotFrameReader.bodyRange(frame);
            long from = MexcSpotFrameReader.fromVersion(frame, body);
            long to = MexcSpotFrameReader.toVersion(frame, body);

            CheckResult result = MexcVersionRange.check(from, to, ctx.lastVersion);

            if (result == CheckResult.DE_SYNCED) {
                log.debug("[{}] sequence gap: expected fromVersion <= {}, got fromVersion={} (toVersion={})",
                        slot.instrument().logName(), ctx.lastVersion + 1, from, to);
                return false;
            }

            if (result == CheckResult.OK) {
                ctx.lastVersion = to;
                MexcSpotFrameReader.applyLevels(frame, body, slot.book(), System.currentTimeMillis());
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
            long lastUpdateId = applySnapshot(slot, rawJson);
            if (lastUpdateId == -1) return false;
            slot.book().computeDistance();

            MexcSpotSyncContext ctx = (MexcSpotSyncContext) slot.ctx();
            ctx.lastVersion = lastUpdateId;

            while (!ctx.diffBuffer.isEmpty()) {
                ByteBuffer diff = ctx.diffBuffer.pollFirst();
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
     * Streams a {@code GET /api/v3/depth} body into the (empty) book.
     *
     * @return {@code lastUpdateId}, or {@code -1} if the body is malformed or has none — which
     *         covers an error body ({@code {"code":…,"msg":…}}), should one get past the fetcher
     */
    long applySnapshot(BookSlot slot, String rawJson) {
        OrderBook book = slot.book();
        long millis = System.currentTimeMillis();
        long lastUpdateId = -1;

        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "lastUpdateId" -> lastUpdateId = p.getLongValue();
                    case "bids" -> applySide(true, p, book, millis);
                    case "asks" -> applySide(false, p, book, millis);
                    default -> p.skipChildren();
                }
            }
        } catch (Exception e) {
            log.warn("[{}] Failed to parse snapshot: {}", slot.instrument().logName(), e.getMessage());
            return -1;
        }
        return lastUpdateId;
    }

    /**
     * One side's {@code [["price","qty"], …]}. Snapshot numbers are padded to tick precision
     * ({@code "375.80"}) where pushes are trimmed ({@code 375.8}); both parse to the same
     * {@code double} key.
     */
    private void applySide(boolean isBid, JsonParser p, OrderBook book, long millis) {
        while (p.nextToken() != JsonToken.END_ARRAY) {
            p.nextToken();
            double price = JavaDoubleParser.parseDouble(p.getStringCharacters(), p.getStringOffset(), p.getStringLength());

            p.nextToken();
            double qty = JavaDoubleParser.parseDouble(p.getStringCharacters(), p.getStringOffset(), p.getStringLength());

            book.applyLevel(isBid, price, qty, millis);

            // anything MEXC appends to the row later
            while (p.nextToken() != JsonToken.END_ARRAY) {
                p.skipChildren();
            }
        }
    }

    /**
     * The single recovery path — called from {@link #onEvent} and nowhere else, which is what
     * bounds the sink to at most one request per event.
     */
    private void recover(BookSlot slot, MexcSpotSyncContext ctx) {
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
