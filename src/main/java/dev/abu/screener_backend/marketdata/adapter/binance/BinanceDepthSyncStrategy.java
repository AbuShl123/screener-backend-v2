package dev.abu.screener_backend.marketdata.adapter.binance;

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


@Slf4j
public abstract class BinanceDepthSyncStrategy implements DepthSyncStrategy {

    protected static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    /**
     * Validates one depth diff against the book's sequence cursor — the only thing each Binance
     * venue implements. Spot and futures read different fields and apply different rules, so field
     * parsing belongs here rather than in the base.
     *
     * <h3>Parser hand-off contract — read this before editing an implementation</h3>
     * {@code check} is called with the parser positioned on the diff object's {@code START_OBJECT}.
     * It must:
     * <ol>
     *   <li>read only the sequence fields it needs ({@code U}, {@code u}, and on futures {@code pu}),
     *       breaking out of its scan as soon as it has them;</li>
     *   <li>return leaving the parser positioned <b>before</b> the {@code b} / {@code a} fields, and
     *       <b>without</b> consuming the object's {@code END_OBJECT}.</li>
     * </ol>
     * {@link #applyDiff} resumes from exactly that position. This works because Binance guarantees
     * {@code U}, {@code u} and {@code pu} precede {@code b} and {@code a} in the frame.
     *
     * <p><b>Failure mode if the contract is broken:</b> an implementation that over-consumes leaves
     * {@code applyDiff} with no levels to read. Nothing throws — the book simply stops receiving
     * updates while continuing to report {@code SYNCED}, and drifts silently. No other layer can
     * detect this, which is why it is pinned by a test.
     *
     * @param p       positioned on START_OBJECT; see the hand-off contract above
     * @param ctx     the book's sync cursor; an {@code OK} return must have advanced it
     * @param logName {@code slot.instrument().logName()}, for the de-sync log line only
     * @return {@code OK} to apply the diff, {@code IGNORE} to drop it and stay synced,
     *         {@code DE_SYNCED} to trigger recovery
     */
    protected abstract CheckResult check(JsonParser p, BinanceSyncContext ctx, String logName);

    protected enum CheckResult {
        OK, IGNORE, DE_SYNCED;
    }

    private final RecoverySink recoverSink;
    private final PipelineMetrics metrics;

    protected BinanceDepthSyncStrategy(RecoverySink recoverSink, PipelineMetrics metrics) {
        this.recoverSink = recoverSink;
        this.metrics = metrics;
    }

    @Override
    public BookSyncContext newContext() {
        return new BinanceSyncContext();
    }

    @Override
    public void onEvent(BookSlot slot, DepthEvent event) {
        BinanceSyncContext ctx = (BinanceSyncContext) slot.ctx();
        OrderBookState state = slot.book().getState();

        if (event.type == EventType.REST_MSG) {
            if (state != OrderBookState.RECOVERING) return;
            if (!handleSnapshot(slot, event)) {
                recover(slot, ctx);
            } else {
                slot.book().markSynced();
            }
            return;
        }

        if (event.type == EventType.REST_FAILED) {
            // Not recover(): that would re-request at once and count as a resync. The book re-asks
            // on its next diff through the PENDING path, like any refused request. No clearLevels()
            // either — a RECOVERING book is already empty. A failure reaching a book that is not
            // RECOVERING is late or superseded, and is dropped like a late REST_MSG.
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

    protected boolean handleDiff(BookSlot slot, String rawJson) {
        BinanceSyncContext ctx = (BinanceSyncContext) slot.ctx();

        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            CheckResult result = check(p, ctx, slot.instrument().logName());

            if (result == CheckResult.DE_SYNCED) {
                return false;
            }

            if (result == CheckResult.OK) {
                applyDiff(p, slot.book());
                slot.book().computeDistance();
            }
            return true;

        } catch (Exception e) {
            log.warn("[{}] Failed to parse diff: {}", slot.instrument().logName(), e.getMessage());
            return false;
        }
    }

    protected boolean handleSnapshot(BookSlot slot, DepthEvent event) {
        try {
            // the book is guaranteed to be empty here
            long lastUpdateId = applySnapshot(slot, event.rawJson);
            if (lastUpdateId == -1) return false;
            slot.book().computeDistance();

            BinanceSyncContext ctx = (BinanceSyncContext) slot.ctx();
            ctx.lastUpdateId = lastUpdateId;

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

    private void applyDiff(JsonParser p, OrderBook orderBook) {
        long millis = System.currentTimeMillis();

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "b" -> applyLevel(true, millis, p, orderBook);
                case "a" -> applyLevel(false, millis, p, orderBook);
                default -> p.skipChildren();
            }
        }
    }

    protected long applySnapshot(BookSlot slot, String rawJson) {
        long millis = System.currentTimeMillis();
        long lastUpdateId = -1;

        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "lastUpdateId" -> lastUpdateId = p.getLongValue();
                    case "bids" -> applyLevel(true, millis, p, slot.book());
                    case "asks" -> applyLevel(false, millis, p, slot.book());
                    default -> p.skipChildren();
                }
            }
        } catch (Exception e) {
            log.warn("[{}] Failed to parse snapshot: {}", slot.instrument().logName(), e.getMessage());
            lastUpdateId = -1;
        }

        return lastUpdateId;
    }

    private void applyLevel(boolean isBid, long millis, JsonParser p, OrderBook orderBook) {
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

            orderBook.applyLevel(isBid, price, qty, millis);
            p.nextToken(); // END_ARRAY of [price, qty]
        }
    }

    /**
     * The single recovery path — called from {@link #onEvent} and nowhere else, which is what
     * bounds the sink to at most one request per event. Every helper reports failure by returning
     * {@code false} rather than recovering on its own.
     */
    private void recover(BookSlot slot, BinanceSyncContext ctx) {
        metrics.recordResync(slot.instrument().venue());
        ctx.reset();
        slot.book().clearLevels();
        slot.book().markPending();
        boolean queued = recoverSink.requestRecovery(slot);
        if (queued) {
            slot.book().markRecovering();
        }
        // Refusal is the normal startup path with a queue of 10 against ~800 books: the book stays
        // PENDING and re-asks on its next diff, so a refusal here is not a stuck book.
        log.debug("[{}] recovering - snapshot {}", slot.instrument().logName(),
                queued ? "requested" : "refused, will retry on next diff");
    }
}
