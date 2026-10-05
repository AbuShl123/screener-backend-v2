package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.marketdata.spi.BookSyncContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;

@Slf4j
public class BinanceSyncContext implements BookSyncContext {

    /** Package-private so the sync tests can drive an overflow without duplicating the constant. */
    static final int MAX_BUFFER_SIZE = 500;

    final ArrayDeque<String> diffBuffer;
    long lastUpdateId;

    // futures-only concept
    boolean syncPointFound = false;

    public BinanceSyncContext() {
        this.lastUpdateId = -1;
        this.diffBuffer = new ArrayDeque<>();
    }

    /**
     * Buffers a diff while the book is {@code RECOVERING}, waiting for its snapshot.
     *
     * @param logName {@code slot.instrument().logName()} — passed in rather than held on the
     *                context, which has no identity of its own, so the overflow line can name the
     *                instrument that produced it
     * @return {@code false} when the buffer is full; the caller must treat that as a de-sync
     */
    public boolean bufferDiff(String diff, String logName) {
        if (diffBuffer.size() >= MAX_BUFFER_SIZE) {
            log.warn("[{}] diff buffer overflow - {} diffs buffered without a snapshot", logName, MAX_BUFFER_SIZE);
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