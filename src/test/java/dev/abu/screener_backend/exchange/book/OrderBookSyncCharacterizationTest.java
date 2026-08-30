package dev.abu.screener_backend.exchange.book;

import dev.abu.screener_backend.exchange.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

import static dev.abu.screener_backend.exchange.book.SyncTestSupport.Harness;
import static dev.abu.screener_backend.exchange.book.SyncTestSupport.bufferSize;
import static dev.abu.screener_backend.exchange.book.SyncTestSupport.diff;
import static dev.abu.screener_backend.exchange.book.SyncTestSupport.levels;
import static dev.abu.screener_backend.exchange.book.SyncTestSupport.lvl;
import static dev.abu.screener_backend.exchange.book.SyncTestSupport.snapshot;
import static dev.abu.screener_backend.exchange.book.SyncTestSupport.synced;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for the Binance local-orderbook sync algorithm as it stands <b>before</b>
 * the sync-SPI extraction.
 *
 * <p>Until now the market-data pipeline had no tests at all, so the only available verification was
 * to run against live Binance and watch {@code sync count: spot=… fut=…} settle. These cases pin
 * the algorithm's decisions — including the ones that look wrong and are preserved deliberately —
 * so that commit C can port the same expectations onto {@code DepthSyncStrategy} and have the
 * answer to "is it at parity?" be a test run rather than a careful read.
 *
 * <p>Numbering follows {@code .claude/plans/p2-step2-sync-spi.md} §8.2.
 */
class OrderBookSyncCharacterizationTest {

    private static final Venue SPOT = Venue.BINANCE_SPOT;
    private static final Venue FUTURES = Venue.BINANCE_FUTURES;

    @Nested
    @DisplayName("reaching SYNCED")
    class HappyPath {

        @Test
        @DisplayName("1 — snapshot plus ordered diffs syncs the book and applies every level")
        void snapshotAndOrderedDiffsReachSynced() {
            Harness h = new Harness(FUTURES, SyncTestSupport.FILTER);

            // First diff: drives PENDING → SNAPSHOT_REQUESTED and is re-fed into the buffer.
            h.wsMsg(diff(FUTURES, 100, 110, 99, levels(lvl(99, 2.0)), ""));
            // Second diff: buffered, drained after the snapshot lands.
            h.wsMsg(diff(FUTURES, 111, 120, 110, levels(lvl(98, 0)), levels(lvl(104, 1.5))));

            h.restMsg(snapshot(105,
                    levels(lvl(99, 1), lvl(98, 1), lvl(97, 1)),
                    levels(lvl(101, 1), lvl(102, 1), lvl(103, 1))));

            assertEquals(OrderBookState.SYNCED, h.book.getState());
            assertEquals(1, h.recoveryRequests, "only the initial PENDING diff should request recovery");

            // The buffered first event updated 99 in place; the second removed 98 and added 104.
            assertEquals(2.0, h.book.getBids().get(99.0).quantity);
            assertFalse(h.book.getBids().containsKey(98.0), "zero quantity must remove the level");
            assertEquals(1.5, h.book.getAsks().get(104.0).quantity);
            assertEquals(2, h.book.getBids().size());
            assertEquals(4, h.book.getAsks().size());

            // A live diff continuing the sequence is applied immediately.
            h.wsMsg(diff(FUTURES, 121, 130, 120, levels(lvl(97, 3.0)), ""));

            assertEquals(OrderBookState.SYNCED, h.book.getState());
            assertEquals(3.0, h.book.getBids().get(97.0).quantity);
            assertEquals(1, h.recoveryRequests);
        }

        @Test
        @DisplayName("8 — a diff whose u equals snapshotId is KEPT (deliberately stricter than Binance's spot docs)")
        void strictDiscardKeepsTheDiffWhoseUEqualsSnapshotId() {
            Harness h = new Harness(SPOT, SyncTestSupport.FILTER);
            h.wsMsg(diff(SPOT, 100, 110, 0, "", ""));
            h.wsMsg(diff(SPOT, 111, 120, 0, "", ""));

            // snapshotId == u of the first buffered diff. discardInvalidDiffsFromBuffer uses a
            // strict u < snapshotId, so that diff survives and becomes the sync point. Under the
            // documented u <= snapshotId rule it would be discarded, the next diff's U (111) would
            // exceed snapshotId, and the book would never sync — which is why the deviation exists.
            h.restMsg(snapshot(110, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book.getState());
            assertEquals(1, h.recoveryRequests);
        }
    }

    @Nested
    @DisplayName("sequence gaps")
    class SequenceGaps {

        @Test
        @DisplayName("2 — spot U gap resets the book, clears the buffer and requests recovery once")
        void spotSequenceGapTriggersResync() {
            Harness h = synced(SPOT, levels(lvl(99, 1)), levels(lvl(101, 1)));
            assertEquals(OrderBookState.SYNCED, h.book.getState());
            assertEquals(1, h.recoveryRequests);

            // lastUpdateId is 120, so U must be 121. It is not.
            h.wsMsg(diff(SPOT, 130, 140, 0, levels(lvl(99, 5)), ""));

            assertEquals(OrderBookState.SNAPSHOT_REQUESTED, h.book.getState());
            assertEquals(2, h.recoveryRequests);
            assertEquals(0, bufferSize(h.book), "the gap diff must not be buffered");
        }

        @Test
        @DisplayName("3 — futures pu gap resets the book, clears the buffer and requests recovery once")
        void futuresPuGapTriggersResync() {
            Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));
            assertEquals(OrderBookState.SYNCED, h.book.getState());

            // lastUpdateId is 120, so pu must be 120. It is not.
            h.wsMsg(diff(FUTURES, 121, 130, 999, levels(lvl(99, 5)), ""));

            assertEquals(OrderBookState.SNAPSHOT_REQUESTED, h.book.getState());
            assertEquals(2, h.recoveryRequests);
            assertEquals(0, bufferSize(h.book));
        }

        /**
         * <b>Pins a live defect — do not read this as the intended behaviour.</b>
         *
         * <p>{@code OrderBook} guards every parse with {@code catch (IOException e) { … resync(); }},
         * but the project is on Jackson 3, where {@code tools.jackson.core.JacksonException extends
         * RuntimeException}. Those catch blocks are therefore dead for parse failures: a malformed
         * frame propagates out of {@code onDiff} instead of resyncing. The book is left {@code SYNCED}
         * with levels partially applied (the parser mutates the {@code TreeMap} as it walks) and a
         * stale {@code lastUpdateId}, no recovery is requested, and the throw reaches the Disruptor
         * consumer thread.
         *
         * <p>Pinned rather than fixed so that the fix is a deliberate, visible change. The plan's
         * case 10 expects {@code SNAPSHOT_REQUESTED} plus exactly one recovery request; that
         * expectation is the correct one and should replace this test when the catch clauses are
         * widened.
         */
        @Test
        @DisplayName("10 — a malformed diff while SYNCED escapes instead of resyncing (Jackson 3 defect)")
        void malformedDiffWhileSyncedThrows() {
            Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));
            assertEquals(OrderBookState.SYNCED, h.book.getState());

            String truncated = "{\"e\":\"depthUpdate\",\"U\":121,\"u\":130,\"pu\":120,\"b\":[[\"99.0\",";

            assertThrows(JacksonException.class, () -> h.wsMsg(truncated));

            // No recovery, and the book still believes it is live.
            assertEquals(OrderBookState.SYNCED, h.book.getState());
            assertEquals(1, h.recoveryRequests);
        }

        /** The same dead-catch defect on the REST lane. See {@link #malformedDiffWhileSyncedThrows}. */
        @Test
        @DisplayName("a malformed snapshot escapes applySnapshot instead of resyncing (Jackson 3 defect)")
        void malformedSnapshotThrows() {
            Harness h = new Harness(FUTURES, SyncTestSupport.FILTER);
            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));
            assertEquals(1, h.recoveryRequests);

            String truncated = "{\"lastUpdateId\":105,\"bids\":[[\"99.0\",";

            assertThrows(JacksonException.class, () -> h.restMsg(truncated));

            assertEquals(1, h.recoveryRequests, "no resync is requested for an unparseable snapshot");
        }
    }

    @Nested
    @DisplayName("snapshot validation")
    class SnapshotValidation {

        @Test
        @DisplayName("4 — a snapshot older than the first buffered diff's [U,u] window resyncs")
        void snapshotOutsideSyncPointWindowResyncs() {
            Harness h = new Harness(FUTURES, SyncTestSupport.FILTER);
            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));
            assertEquals(1, h.recoveryRequests);

            // snapshotId 50 < U 100: no valid sync point. (The upper half of the check is
            // unreachable — the discard step guarantees u >= snapshotId by the time it runs.)
            h.restMsg(snapshot(50, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SNAPSHOT_REQUESTED, h.book.getState());
            assertEquals(2, h.recoveryRequests);
            assertEquals(0, bufferSize(h.book));
        }

        @Test
        @DisplayName("5 — a snapshot newer than every buffered diff empties the buffer and resyncs")
        void emptyBufferAfterDiscardResyncs() {
            Harness h = new Harness(FUTURES, SyncTestSupport.FILTER);
            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));

            // u 110 < snapshotId 200, so the only buffered diff is discarded and nothing remains
            // to establish a sync point against.
            h.restMsg(snapshot(200, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SNAPSHOT_REQUESTED, h.book.getState());
            assertEquals(2, h.recoveryRequests);
            assertEquals(0, bufferSize(h.book));
        }

        @Test
        @DisplayName("11 — a snapshot arriving while SYNCED knocks the book back (preserved quirk)")
        void snapshotWhileSyncedResyncs() {
            Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));
            assertEquals(OrderBookState.SYNCED, h.book.getState());

            // A late or duplicate snapshot is not guarded: applySnapshot runs in full, finds no
            // usable sync point, and desyncs a healthy book. Arguably wrong; pinned here so that
            // fixing it is a deliberate, visible change rather than a silent one.
            h.restMsg(snapshot(500, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SNAPSHOT_REQUESTED, h.book.getState());
            assertEquals(2, h.recoveryRequests);
        }

        @Test
        @DisplayName("12 — a gap part-way through the buffer drain resyncs with exactly one request")
        void gapDuringBufferDrainResyncsOnce() {
            Harness h = new Harness(SPOT, SyncTestSupport.FILTER);
            h.wsMsg(diff(SPOT, 100, 110, 0, "", ""));
            h.wsMsg(diff(SPOT, 111, 120, 0, "", ""));
            h.wsMsg(diff(SPOT, 200, 210, 0, levels(lvl(99, 1)), ""));  // expected U=121
            assertEquals(1, h.recoveryRequests);

            h.restMsg(snapshot(105, levels(lvl(99, 1)), levels(lvl(101, 1))));

            // The drain calls applyLiveDiff, which resyncs internally; applySnapshot then resyncs
            // again. Both are state-only — only the outer return value reaches the processor, so
            // the book is enqueued once. Commit C must keep that single-enqueue property when the
            // strategy starts calling the sink directly.
            assertEquals(OrderBookState.SNAPSHOT_REQUESTED, h.book.getState());
            assertEquals(2, h.recoveryRequests);
            assertEquals(0, bufferSize(h.book));
        }
    }

    @Nested
    @DisplayName("buffering and the recovery handshake")
    class BufferingAndHandshake {

        @Test
        @DisplayName("9 — a diff in PENDING with the sink accepting buffers the triggering diff")
        void pendingDiffIsBufferedWhenSinkAccepts() {
            Harness h = new Harness(FUTURES, SyncTestSupport.FILTER);

            h.wsMsg(diff(FUTURES, 100, 110, 99, levels(lvl(99, 1)), ""));

            assertEquals(OrderBookState.SNAPSHOT_REQUESTED, h.book.getState());
            assertEquals(1, h.recoveryRequests);
            assertEquals(1, bufferSize(h.book), "the triggering diff is re-fed into the buffer");
        }

        @Test
        @DisplayName("7 — a diff in PENDING with the sink refusing stays PENDING and buffers nothing")
        void pendingDiffIsDroppedWhenSinkRefuses() {
            Harness h = new Harness(FUTURES, SyncTestSupport.FILTER);
            h.sinkAccepts = false;

            h.wsMsg(diff(FUTURES, 100, 110, 99, levels(lvl(99, 1)), ""));

            // The refusal bounds how many books hold a 500-entry buffer during the startup ramp.
            assertEquals(OrderBookState.PENDING, h.book.getState());
            assertEquals(1, h.recoveryRequests);
            assertEquals(0, bufferSize(h.book), "a refused book must not start buffering");
        }

        @Test
        @DisplayName("6 — overflowing the diff buffer resyncs and discards the triggering diff")
        void bufferOverflowResyncs() {
            Harness h = new Harness(FUTURES, SyncTestSupport.FILTER);

            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));   // buffered via the re-feed
            for (int i = 0; i < OrderBook.MAX_BUFFER_SIZE - 1; i++) {
                h.wsMsg(diff(FUTURES, 111 + i, 111 + i, 110 + i, "", ""));
            }
            assertEquals(OrderBook.MAX_BUFFER_SIZE, bufferSize(h.book));
            assertEquals(1, h.recoveryRequests);

            h.wsMsg(diff(FUTURES, 900, 901, 899, "", ""));  // the 501st

            assertEquals(OrderBookState.SNAPSHOT_REQUESTED, h.book.getState());
            assertEquals(2, h.recoveryRequests);
            assertEquals(0, bufferSize(h.book), "overflow clears the buffer and drops the trigger");
        }

        @Test
        @DisplayName("the first buffered event is applied without sequence validation")
        void firstBufferedEventSkipsSequenceValidation() {
            Harness h = new Harness(FUTURES, SyncTestSupport.FILTER);

            // pu here is nonsense relative to the snapshot, and is never checked: the first event
            // out of the buffer establishes lastUpdateId rather than being validated against it.
            h.wsMsg(diff(FUTURES, 100, 110, 42, levels(lvl(99, 4.0)), ""));
            h.wsMsg(diff(FUTURES, 111, 120, 110, "", ""));
            h.restMsg(snapshot(105, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book.getState());
            assertEquals(4.0, h.book.getBids().get(99.0).quantity);
            assertEquals(1, h.recoveryRequests);
        }

        @Test
        @DisplayName("diffs buffered before the snapshot are retained after SYNCED, not cleared")
        void bufferIsNotClearedOnceSynced() {
            Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));

            // Step 4 polls the first buffered diff; step 5 only iterates the rest. The residue is
            // harmless in the live flow (any resync clears it) but it is what case 11 reads, so it
            // is pinned rather than left implicit.
            assertEquals(OrderBookState.SYNCED, h.book.getState());
            assertTrue(bufferSize(h.book) > 0, "the drained buffer is left populated");
        }
    }
}
