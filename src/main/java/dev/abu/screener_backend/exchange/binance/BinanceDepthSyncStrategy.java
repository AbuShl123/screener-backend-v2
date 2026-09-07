package dev.abu.screener_backend.exchange.binance;

import ch.randelshofer.fastdoubleparser.JavaDoubleParser;
import dev.abu.screener_backend.exchange.book.BookSlot;
import dev.abu.screener_backend.exchange.book.OrderBook;
import dev.abu.screener_backend.exchange.book.OrderBookState;
import dev.abu.screener_backend.exchange.ingress.DepthEvent;
import dev.abu.screener_backend.exchange.ingress.EventType;
import dev.abu.screener_backend.exchange.spi.BookSyncContext;
import dev.abu.screener_backend.exchange.spi.DepthSyncStrategy;
import dev.abu.screener_backend.exchange.spi.RecoverySink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;


@Slf4j
@RequiredArgsConstructor
public abstract class BinanceDepthSyncStrategy implements DepthSyncStrategy {

    protected static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    protected abstract CheckResult check(JsonParser p, BinanceSyncContext ctx);

    protected enum CheckResult {
        OK, IGNORE, DE_SYNCED;
    }

    private final RecoverySink recoverSink;

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

        if (state == OrderBookState.SYNCED) {
            if (!handleDiff(slot, event.rawJson)) {
                recover(slot, ctx);
            }
            return;
        }

        if (state == OrderBookState.RECOVERING && !ctx.bufferDiff(event.rawJson)) {
            recover(slot, ctx);
            return;
        }

        if (state == OrderBookState.PENDING && recoverSink.requestRecovery(slot)) {
            slot.book().markRecovering();
            ctx.bufferDiff(event.rawJson);
        }
    }

    protected boolean handleDiff(BookSlot slot, String rawJson) {
        BinanceSyncContext ctx = (BinanceSyncContext) slot.ctx();

        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            p.nextToken();
            CheckResult result = check(p, ctx);

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

    private void recover(BookSlot slot, BinanceSyncContext ctx) {
        ctx.reset();
        slot.book().clearLevels();
        slot.book().markPending();
        if (recoverSink.requestRecovery(slot)) {
            slot.book().markRecovering();
        }
    }
}
