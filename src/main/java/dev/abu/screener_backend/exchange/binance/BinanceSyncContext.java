package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.spi.BookSyncContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;

@Slf4j
public class BinanceSyncContext implements BookSyncContext {

    private static final int MAX_BUFFER_SIZE = 500;

    final ArrayDeque<String> diffBuffer;
    long lastUpdateId;

    // futures-only concept
    boolean syncPointFound = false;

    public BinanceSyncContext() {
        this.lastUpdateId = -1;
        this.diffBuffer = new ArrayDeque<>();
    }

    public boolean bufferDiff(String diff) {
        if (diffBuffer.size() >= MAX_BUFFER_SIZE) {
            log.warn("BUFFER OVERFLOW!!! {} diffs buffered", MAX_BUFFER_SIZE);
            return false;
        }
        return diffBuffer.add(diff);
    }

    public void reset() {
        lastUpdateId = -1;
        diffBuffer.clear();
        syncPointFound = false;
    }
}