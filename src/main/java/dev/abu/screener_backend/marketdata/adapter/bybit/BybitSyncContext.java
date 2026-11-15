package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.marketdata.spi.BookSyncContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;

/** Per-book sync state for {@link BybitSyncStrategy}. One instance per book. */
@Slf4j
public class BybitSyncContext implements BookSyncContext {

    /** Package-private so the sync tests can drive an overflow without duplicating the constant. */
    static final int MAX_BUFFER_SIZE = 500;

    /** Raw delta frames, buffered only while the book is {@code RECOVERING}. */
    final ArrayDeque<String> diffBuffer = new ArrayDeque<>();

    /** The {@code u} of the last applied delta or snapshot. {@code -1} = no sync point. */
    long lastU = -1;

    /**
     * Buffers a delta while the book is {@code RECOVERING}, waiting for its REST snapshot.
     *
     * @param logName {@code slot.instrument().logName()}, so the overflow line names its instrument
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
        lastU = -1;
        diffBuffer.clear();
    }
}
