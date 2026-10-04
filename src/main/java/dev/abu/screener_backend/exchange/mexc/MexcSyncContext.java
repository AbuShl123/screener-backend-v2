package dev.abu.screener_backend.exchange.mexc;

import dev.abu.screener_backend.exchange.spi.BookSyncContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;

/** Per-book sync state for {@link MexcFuturesSyncStrategy}. One instance per book. */
@Slf4j
public class MexcSyncContext implements BookSyncContext {

    /** Package-private so the sync tests can drive an overflow without duplicating the constant. */
    static final int MAX_BUFFER_SIZE = 500;

    /** Raw push frames, buffered only while the book is {@code RECOVERING}. */
    final ArrayDeque<String> diffBuffer = new ArrayDeque<>();

    /**
     * The {@code end} of the last applied push, or the snapshot's {@code version} if none applied
     * yet. {@code -1} = no sync point.
     */
    long lastVersion = -1;

    /**
     * Buffers a push while the book is {@code RECOVERING}, waiting for its snapshot.
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
        lastVersion = -1;
        diffBuffer.clear();
    }
}
