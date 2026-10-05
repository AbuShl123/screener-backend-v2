package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceSyncContext;
import dev.abu.screener_backend.marketdata.core.book.OrderBookState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static dev.abu.screener_backend.marketdata.adapter.binance.SyncTestSupport.FILTER;
import static dev.abu.screener_backend.marketdata.adapter.binance.SyncTestSupport.Harness;
import static dev.abu.screener_backend.marketdata.adapter.binance.SyncTestSupport.diff;
import static dev.abu.screener_backend.marketdata.adapter.binance.SyncTestSupport.levels;
import static dev.abu.screener_backend.marketdata.adapter.binance.SyncTestSupport.lvl;
import static dev.abu.screener_backend.marketdata.adapter.binance.SyncTestSupport.snapshot;
import static dev.abu.screener_backend.marketdata.adapter.binance.SyncTestSupport.synced;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Binance sync algorithm as it stands <b>after</b> the SPI extraction.
 *
 * <p>These descend from the pre-extraction characterization suite but are <b>not</b> a port of it.
 * The algorithm was rewritten rather than moved, so several of the old expectations encoded
 * behaviour that was deliberately changed; each such case is re-derived here and carries a comment
 * naming what changed and why. See {@code .claude/docs/multi-exchange-progress.md} §7 (the
 * shared contract) and §8.1 (Binance's sequence rules) — that document, not the old tests, is the oracle.
 */
class BinanceDepthSyncStrategyTest {

    private static final Venue SPOT = Venue.BINANCE_SPOT;
    private static final Venue FUTURES = Venue.BINANCE_FUTURES;

    @Nested
    @DisplayName("reaching SYNCED")
    class HappyPath {

        @Test
        @DisplayName("snapshot plus ordered diffs syncs the book and applies every level")
        void snapshotAndOrderedDiffsReachSynced() {
            Harness h = new Harness(FUTURES, FILTER);

            // First diff: PENDING → RECOVERING, and the triggering diff is buffered.
            h.wsMsg(diff(FUTURES, 100, 110, 99, levels(lvl(99, 2.0)), ""));
            // Second diff: buffered, drained after the snapshot lands.
            h.wsMsg(diff(FUTURES, 111, 120, 110, levels(lvl(98, 0)), levels(lvl(104, 1.5))));

            h.restMsg(snapshot(105,
                    levels(lvl(99, 1), lvl(98, 1), lvl(97, 1)),
                    levels(lvl(101, 1), lvl(102, 1), lvl(103, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1, h.requests(), "only the initial PENDING diff should request recovery");

            // The buffered first event updated 99 in place; the second removed 98 and added 104.
            assertEquals(2.0, h.book().getBids().get(99.0).quantity);
            assertFalse(h.book().getBids().containsKey(98.0), "zero quantity must remove the level");
            assertEquals(1.5, h.book().getAsks().get(104.0).quantity);
            assertEquals(2, h.book().getBids().size());
            assertEquals(4, h.book().getAsks().size());

            // A live diff continuing the sequence is applied immediately.
            h.wsMsg(diff(FUTURES, 121, 130, 120, levels(lvl(97, 3.0)), ""));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(3.0, h.book().getBids().get(97.0).quantity);
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("a diff whose u equals lastUpdateId is KEPT (deliberately stricter than Binance's spot docs)")
        void strictDiscardKeepsTheDiffWhoseUEqualsSnapshotId() {
            Harness h = new Harness(SPOT, FILTER);
            h.wsMsg(diff(SPOT, 100, 110, 0, "", ""));
            h.wsMsg(diff(SPOT, 111, 120, 0, "", ""));

            // snapshotId == u of the first buffered diff. The IGNORE test is a strict
            // u < lastUpdateId, so that diff survives and establishes the sync point. Binance's
            // spot docs say to discard u <= lastUpdateId; in practice the snapshot's lastUpdateId
            // very often equals a buffered event's u, and under the documented rule spot books
            // never sync. Decision #9 — do not "correct" this.
            h.restMsg(snapshot(110, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("CHANGED — an all-stale buffer is success: the book syncs and waits for a live event")
        void allStaleBufferStillSyncs() {
            Harness h = new Harness(FUTURES, FILTER);
            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));

            // u=110 < snapshotId=200, so the only buffered diff is IGNOREd and the buffer empties
            // without establishing a sync point.
            //
            // Was: "a snapshot newer than every buffered diff empties the buffer and resyncs".
            // A missing sync point in the buffer does not mean one will not arrive on the next
            // frame — resyncing here threw away a perfectly good snapshot.
            h.restMsg(snapshot(200, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1, h.requests(), "no second recovery request");
            assertEquals(200, h.ctx().lastUpdateId);
            assertFalse(h.ctx().syncPointFound, "still hunting: no buffered event was in range");

            // The next in-range stream event establishes the sync point.
            h.wsMsg(diff(FUTURES, 195, 210, 194, levels(lvl(99, 5.0)), ""));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertTrue(h.ctx().syncPointFound);
            assertEquals(5.0, h.book().getBids().get(99.0).quantity);
        }

        @Test
        @DisplayName("CHANGED — computeDistance runs on the snapshot path, so far levels are swept immediately")
        void snapshotPathSweepsAndSetsDistance() {
            Harness h = new Harness(FUTURES, FILTER);
            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));    // the only buffered diff

            // Was: "syncing on a single buffered diff leaves distances uncomputed and far levels
            // unswept". The old code only swept from the diff path, so a book could reach SYNCED
            // holding 1000 unswept levels all reporting distance == 0.0 and the classifier would
            // see every level as at-mid for one pass. The rewrite made that case routine rather
            // than rare, so it was fixed.
            h.restMsg(snapshot(105, levels(lvl(99, 1), lvl(50, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertFalse(h.book().getBids().containsKey(50.0), "far level swept on the snapshot path");
            assertEquals(0.01, h.book().getBids().get(99.0).distance, 1e-9);
        }

        @Test
        @DisplayName("CHANGED — the buffer is fully drained once the book syncs")
        void bufferIsFullyDrainedOnceSynced() {
            Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));

            // Was: "diffs buffered before the snapshot are retained after SYNCED, not cleared".
            // The old drain iterated the buffer without removing from it and left residue behind;
            // pollFirst() genuinely empties it.
            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(0, h.bufferSize());
        }
    }

    @Nested
    @DisplayName("sequence validation")
    class SequenceValidation {

        @Test
        @DisplayName("spot U gap resets the book and requests recovery exactly once")
        void spotSequenceGapTriggersResync() {
            Harness h = synced(SPOT, levels(lvl(99, 1)), levels(lvl(101, 1)));
            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1, h.requests());

            // lastUpdateId is 120, so U must be <= 121. It is not.
            h.wsMsg(diff(SPOT, 130, 140, 0, levels(lvl(99, 5)), ""));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize(), "the gap diff must not be buffered");
        }

        @Test
        @DisplayName("spot accepts an overlapping event, because depth levels are absolute quantities")
        void spotAcceptsOverlappingEvent() {
            Harness h = synced(SPOT, levels(lvl(99, 1)), levels(lvl(101, 1)));

            // U=115 < lastUpdateId+1=121, so this event's range overlaps ground already applied.
            // Re-applying it cannot corrupt the book: Binance sends absolute quantities, and the
            // IGNORE guard means any accepted event has u >= lastUpdateId, so its values are at
            // least as new as what is there. Last write wins, and it is the newest write.
            h.wsMsg(diff(SPOT, 115, 130, 0, levels(lvl(99, 6.0)), ""));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(6.0, h.book().getBids().get(99.0).quantity);
            assertEquals(130, h.ctx().lastUpdateId);
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("futures pu gap resets the book and requests recovery exactly once")
        void futuresPuGapTriggersResync() {
            Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));
            assertEquals(OrderBookState.SYNCED, h.book().getState());

            // lastUpdateId is 120, so pu must be 120. It is not.
            h.wsMsg(diff(FUTURES, 121, 130, 999, levels(lvl(99, 5)), ""));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());
        }

        @Test
        @DisplayName("futures regime 1 hunts the sync point inside [U, u] and latches syncPointFound")
        void futuresRegimeOneLatchesSyncPoint() {
            Harness h = new Harness(FUTURES, FILTER);
            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));
            assertFalse(h.ctx().syncPointFound, "still hunting before the snapshot lands");

            // snapshotId 105 falls inside the buffered event's [100, 110] range.
            h.restMsg(snapshot(105, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertTrue(h.ctx().syncPointFound);
            assertEquals(110, h.ctx().lastUpdateId, "the cursor advances to the accepted event's u");
        }

        @Test
        @DisplayName("futures regime 2 advances the cursor on u, not just pu")
        void futuresRegimeTwoAdvancesCursorOnU() {
            Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));
            assertEquals(120, h.ctx().lastUpdateId);

            // Two consecutive events. If regime 2 validated pu but failed to advance lastUpdateId
            // to u, the second event's pu (130) would not match and every futures book would
            // resync every two events — a real bug during development.
            h.wsMsg(diff(FUTURES, 121, 130, 120, "", ""));
            assertEquals(130, h.ctx().lastUpdateId);

            h.wsMsg(diff(FUTURES, 131, 140, 130, "", ""));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(140, h.ctx().lastUpdateId);
            assertEquals(1, h.requests(), "no resync across two contiguous events");
        }

        @Test
        @DisplayName("CHANGED — the first buffered event is validated like any other, not applied blindly")
        void firstBufferedEventIsValidated() {
            Harness h = new Harness(SPOT, FILTER);

            // Was: "the first buffered event is applied without sequence validation" — the old
            // code used it to establish lastUpdateId rather than checking it. Now the generic
            // predicate handles the first event and the rest identically, so a stale first event
            // is IGNOREd and its levels never reach the book.
            h.wsMsg(diff(SPOT, 100, 110, 0, levels(lvl(99, 4.0)), ""));
            h.wsMsg(diff(SPOT, 111, 120, 0, "", ""));
            h.restMsg(snapshot(200, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1.0, h.book().getBids().get(99.0).quantity,
                    "the stale buffered diff must not have overwritten the snapshot level");
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("check() leaves the parser before b/a, so levels still land after validation")
        void checkLeavesParserPositionedForLevels() {
            // The failure mode this guards is silent: a check() that over-consumes leaves applyDiff
            // with no levels to read, and the book drifts while still reporting SYNCED. Nothing
            // else in the suite would catch it. Both venues, because each has its own scan.
            Harness spot = synced(SPOT, levels(lvl(99, 1)), levels(lvl(101, 1)));
            spot.wsMsg(diff(SPOT, 121, 130, 0, levels(lvl(99, 9.0)), levels(lvl(101, 8.0))));
            assertEquals(9.0, spot.book().getBids().get(99.0).quantity);
            assertEquals(8.0, spot.book().getAsks().get(101.0).quantity);

            Harness futures = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));
            futures.wsMsg(diff(FUTURES, 121, 130, 120, levels(lvl(99, 9.0)), levels(lvl(101, 8.0))));
            assertEquals(9.0, futures.book().getBids().get(99.0).quantity);
            assertEquals(8.0, futures.book().getAsks().get(101.0).quantity);
        }
    }

    @Nested
    @DisplayName("snapshot handling")
    class SnapshotHandling {

        @Test
        @DisplayName("CHANGED — a snapshot arriving while SYNCED is dropped, not applied")
        void snapshotWhileSyncedIsDropped() {
            Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));
            assertEquals(OrderBookState.SYNCED, h.book().getState());

            // Was: "a snapshot arriving while SYNCED knocks the book back (preserved quirk)". The
            // old code ran the full snapshot path on a late or duplicate response and desynced a
            // healthy book. The REST_MSG state guard now drops it.
            h.restMsg(snapshot(500, levels(lvl(90, 7)), levels(lvl(110, 7))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1, h.requests());
            assertEquals(120, h.ctx().lastUpdateId, "the cursor is untouched");
            assertEquals(1.0, h.book().getBids().get(99.0).quantity, "the levels are untouched");
            assertFalse(h.book().getBids().containsKey(90.0));
        }

        @Test
        @DisplayName("a snapshot below every buffered event's range resyncs once")
        void snapshotBelowBufferedWindowResyncs() {
            Harness h = new Harness(FUTURES, FILTER);
            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));
            assertEquals(1, h.requests());

            // snapshotId 50 < U 100: the event is not stale (u >= 50) but the snapshot does not
            // fall inside its range, so there is a genuine gap between the two.
            h.restMsg(snapshot(50, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());
        }

        @Test
        @DisplayName("a gap part-way through the buffer drain resyncs with exactly one request")
        void gapDuringBufferDrainResyncsOnce() {
            Harness h = new Harness(SPOT, FILTER);
            h.wsMsg(diff(SPOT, 100, 110, 0, "", ""));
            h.wsMsg(diff(SPOT, 111, 120, 0, "", ""));
            h.wsMsg(diff(SPOT, 200, 210, 0, levels(lvl(99, 1)), ""));  // gap: expected U <= 121
            assertEquals(1, h.requests());

            h.restMsg(snapshot(105, levels(lvl(99, 1)), levels(lvl(101, 1))));

            // The drain aborts, clears the buffer and returns false; onEvent recovers once. The
            // helpers never touch the sink, which is what keeps this at a single request.
            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());
        }

        @Test
        @DisplayName("a malformed snapshot resyncs and leaves no partial levels behind")
        void malformedSnapshotResyncs() {
            Harness h = new Harness(FUTURES, FILTER);
            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));
            assertEquals(1, h.requests());

            h.restMsg("{\"lastUpdateId\":105,\"bids\":[[\"99.0\",");

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());
            // applySnapshot streams straight into the live book, so a truncated body can leave
            // junk. recover()'s clearLevels() is what makes that safe — see invariant 3.
            assertTrue(h.book().getBids().isEmpty());
            assertTrue(h.book().getAsks().isEmpty());
        }
    }

    @Nested
    @DisplayName("the recovery handshake")
    class RecoveryHandshake {

        @Test
        @DisplayName("a diff in PENDING with the sink accepting buffers the triggering diff")
        void pendingDiffIsBufferedWhenSinkAccepts() {
            Harness h = new Harness(FUTURES, FILTER);

            h.wsMsg(diff(FUTURES, 100, 110, 99, levels(lvl(99, 1)), ""));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(1, h.requests());
            assertEquals(1, h.bufferSize(), "the triggering diff is buffered, not dropped");
        }

        @Test
        @DisplayName("a diff in PENDING with the sink refusing stays PENDING and buffers nothing")
        void pendingDiffIsDroppedWhenSinkRefuses() {
            Harness h = new Harness(FUTURES, FILTER);
            h.sink.accepts = false;

            h.wsMsg(diff(FUTURES, 100, 110, 99, levels(lvl(99, 1)), ""));

            // The refusal is what bounds how many books hold a 500-entry buffer during the ramp.
            assertEquals(OrderBookState.PENDING, h.book().getState());
            assertEquals(1, h.requests());
            assertEquals(0, h.bufferSize(), "a refused book must not start buffering");
        }

        @Test
        @DisplayName("a refused book re-asks on every subsequent diff")
        void refusedBookRetriesOnEveryDiff() {
            Harness h = new Harness(FUTURES, FILTER);
            h.sink.accepts = false;

            // With a queue of 10 against ~1400 books, refusal is the normal startup path. A book
            // that stopped re-asking would park forever and never sync.
            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));
            h.wsMsg(diff(FUTURES, 111, 120, 110, "", ""));
            h.wsMsg(diff(FUTURES, 121, 130, 120, "", ""));

            assertEquals(OrderBookState.PENDING, h.book().getState());
            assertEquals(3, h.requests(), "one request per diff while refused");
            assertEquals(0, h.bufferSize());

            h.sink.accepts = true;
            h.wsMsg(diff(FUTURES, 131, 140, 130, "", ""));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(4, h.requests());
            assertEquals(1, h.bufferSize());
        }

        @Test
        @DisplayName("overflowing the diff buffer resyncs and discards the triggering diff")
        void bufferOverflowResyncs() {
            Harness h = new Harness(FUTURES, FILTER);

            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));   // buffered on the PENDING transition
            for (int i = 0; i < BinanceSyncContext.MAX_BUFFER_SIZE - 1; i++) {
                h.wsMsg(diff(FUTURES, 111 + i, 111 + i, 110 + i, "", ""));
            }
            assertEquals(BinanceSyncContext.MAX_BUFFER_SIZE, h.bufferSize());
            assertEquals(1, h.requests());

            h.wsMsg(diff(FUTURES, 900, 901, 899, "", ""));  // the 501st

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize(), "overflow clears the buffer and drops the trigger");
        }

        @Test
        @DisplayName("at most one recovery request per event, across every failure shape")
        void atMostOneRecoveryRequestPerEvent() {
            // recover() has exactly one call site — onEvent — and every helper reports failure by
            // returning false rather than touching the sink. This is the invariant that guarantees
            // it; the old code achieved it only accidentally, via OrderBookProcessor holding the
            // sole enqueue call.

            // (a) sequence gap
            Harness gap = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));
            gap.wsMsg(diff(FUTURES, 121, 130, 999, "", ""));
            assertEquals(2, gap.requests());

            // (b) malformed diff while SYNCED — the catch is scoped to Exception, so a Jackson
            // RuntimeException becomes a resync instead of escaping onto the consumer thread.
            Harness malformed = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));
            malformed.wsMsg("{\"e\":\"depthUpdate\",\"U\":121,\"u\":130,\"pu\":120,\"b\":[[\"99.0\",");
            assertEquals(OrderBookState.RECOVERING, malformed.book().getState());
            assertEquals(2, malformed.requests(), "one resync, not one per nested failure");

            // (c) buffer overflow
            Harness overflow = new Harness(FUTURES, FILTER);
            overflow.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));
            for (int i = 0; i < BinanceSyncContext.MAX_BUFFER_SIZE; i++) {
                overflow.wsMsg(diff(FUTURES, 111 + i, 111 + i, 110 + i, "", ""));
            }
            assertEquals(2, overflow.requests());
        }

        @Test
        @DisplayName("the resync counter tracks recover() calls, not recovery requests")
        void resyncCounterCountsRecoveries() {
            // The health line reports this number as "churn", so it must mean recoveries and not
            // sink calls: the two differ by exactly the cold-start request, which is not a resync.
            // Counting at the sink instead would report thousands of phantom resyncs during the
            // startup ramp, when a refused request is the normal path.
            Harness h = new Harness(FUTURES, FILTER);
            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));            // PENDING → RECOVERING
            assertEquals(1, h.requests(), "the cold-start request reached the sink");
            assertEquals(0, h.metrics.resyncs(FUTURES), "...but nothing has de-synced yet");

            h.restMsg(snapshot(105, levels(lvl(99, 1)), levels(lvl(101, 1))));
            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(0, h.metrics.resyncs(FUTURES));

            h.wsMsg(diff(FUTURES, 111, 120, 999, "", ""));           // pu gap
            assertEquals(1, h.metrics.resyncs(FUTURES), "one de-sync, one count");

            // Refused re-asks must not inflate it either — the book is already counted as churning.
            h.sink.accepts = false;
            h.wsMsg(diff(FUTURES, 121, 130, 120, "", ""));
            h.wsMsg(diff(FUTURES, 131, 140, 130, "", ""));
            assertEquals(1, h.metrics.resyncs(FUTURES), "a PENDING retry is not a new de-sync");

            // And the counter is per venue, so one venue's churn never shows up under the other.
            assertEquals(0, h.metrics.resyncs(Venue.BINANCE_SPOT));
        }

        @Test
        @DisplayName("invariant 3 — a book leaving SYNCED is emptied, so the snapshot can stream in directly")
        void recoverClearsPriceLevels() {
            Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));
            assertFalse(h.book().getBids().isEmpty());

            h.wsMsg(diff(FUTURES, 121, 130, 999, "", ""));   // pu gap

            // Was: "a resync clears the diff buffer but leaves the price levels in place". The old
            // resync() left stale levels until the next snapshot overwrote them. Clearing is what
            // licenses applySnapshot's single-pass stream straight into the live book: a book in
            // PENDING or RECOVERING always has empty levels, so there is nothing to protect.
            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertTrue(h.book().getBids().isEmpty());
            assertTrue(h.book().getAsks().isEmpty());
            assertEquals(0, h.bufferSize());
            assertEquals(-1, h.ctx().lastUpdateId, "the cursor is reset");
            assertFalse(h.ctx().syncPointFound, "futures goes back to hunting");
        }

        @Test
        @DisplayName("a fresh snapshot fully replaces the pre-desync contents")
        void snapshotReplacesPreviousLevels() {
            Harness h = synced(FUTURES, levels(lvl(99, 1)), levels(lvl(101, 1)));

            h.wsMsg(diff(FUTURES, 500, 510, 499, "", ""));   // pu gap → recover
            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            h.wsMsg(diff(FUTURES, 600, 610, 599, "", ""));
            h.restMsg(snapshot(605, levels(lvl(90, 1)), levels(lvl(110, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertFalse(h.book().getBids().containsKey(99.0), "stale levels must not survive");
            assertFalse(h.book().getAsks().containsKey(101.0));
            assertTrue(h.book().getBids().containsKey(90.0));
            assertTrue(h.book().getAsks().containsKey(110.0));
        }
    }

    @Nested
    @DisplayName("a failed snapshot request (REST_FAILED)")
    class SnapshotFailure {

        @Test
        @DisplayName("RECOVERING goes back to PENDING with an empty buffer and context")
        void recoveringBookReturnsToPending() {
            Harness h = new Harness(FUTURES, FILTER);
            h.wsMsg(diff(FUTURES, 100, 110, 99, levels(lvl(99, 1)), ""));
            h.wsMsg(diff(FUTURES, 111, 120, 110, "", ""));
            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.bufferSize());

            h.restFailed();

            // Invariant: a PENDING book has empty levels and an empty context.
            assertEquals(OrderBookState.PENDING, h.book().getState());
            assertEquals(0, h.bufferSize(), "buffered diffs are useless without the snapshot");
            assertEquals(-1, h.ctx().lastUpdateId);
            assertFalse(h.ctx().syncPointFound);
            assertTrue(h.book().getBids().isEmpty());
            assertTrue(h.book().getAsks().isEmpty());
        }

        @Test
        @DisplayName("is ignored on a SYNCED book — a late failure must not knock a healthy book back")
        void ignoredWhenSynced() {
            Harness h = synced(SPOT, levels(lvl(99, 1)), levels(lvl(101, 1)));

            h.restFailed();

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(120, h.ctx().lastUpdateId, "the cursor is untouched");
            assertTrue(h.book().getBids().containsKey(99.0));
            assertEquals(1, h.requests(), "no new request");
        }

        @Test
        @DisplayName("is ignored on a PENDING book")
        void ignoredWhenPending() {
            Harness h = new Harness(SPOT, FILTER);
            h.sink.accepts = false;
            h.wsMsg(diff(SPOT, 100, 110, 0, "", ""));
            assertEquals(OrderBookState.PENDING, h.book().getState());

            h.restFailed();

            assertEquals(OrderBookState.PENDING, h.book().getState());
            assertEquals(1, h.requests(), "a failure never asks the sink itself");
        }

        @Test
        @DisplayName("does not re-request at once — the next diff does, through the PENDING path")
        void nextDiffReRequests() {
            Harness h = new Harness(SPOT, FILTER);
            h.wsMsg(diff(SPOT, 100, 110, 0, "", ""));
            assertEquals(1, h.requests());

            h.restFailed();
            assertEquals(1, h.requests(), "REST_FAILED itself must not call the sink");

            h.wsMsg(diff(SPOT, 111, 120, 0, "", ""));

            assertEquals(2, h.requests());
            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(1, h.bufferSize(), "only the diff that re-asked is buffered");
        }

        @Test
        @DisplayName("is not counted as a resync")
        void notCountedAsResync() {
            Harness h = new Harness(FUTURES, FILTER);
            h.wsMsg(diff(FUTURES, 100, 110, 99, "", ""));

            h.restFailed();

            assertEquals(0, h.metrics.resyncs(FUTURES), "nothing de-synced: the request just failed");
        }
    }
}
