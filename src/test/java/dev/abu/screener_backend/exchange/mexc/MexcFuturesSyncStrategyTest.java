package dev.abu.screener_backend.exchange.mexc;

import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.book.OrderBookState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static dev.abu.screener_backend.exchange.mexc.MexcSyncTestSupport.Harness;
import static dev.abu.screener_backend.exchange.mexc.MexcSyncTestSupport.VENUE;
import static dev.abu.screener_backend.exchange.mexc.MexcSyncTestSupport.levels;
import static dev.abu.screener_backend.exchange.mexc.MexcSyncTestSupport.lvl;
import static dev.abu.screener_backend.exchange.mexc.MexcSyncTestSupport.push;
import static dev.abu.screener_backend.exchange.mexc.MexcSyncTestSupport.pushDocumentedOrder;
import static dev.abu.screener_backend.exchange.mexc.MexcSyncTestSupport.snapshot;
import static dev.abu.screener_backend.exchange.mexc.MexcSyncTestSupport.synced;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MEXC futures sync algorithm. Modelled on {@code BinanceDepthSyncStrategyTest}: the dispatch
 * and recovery cases are the same contract (progress doc §4.3, §4.9, §4.10), the sequence cases are
 * MEXC's {@code begin} / {@code end} rule ({@code external-docs/mexc/mexc-depth-versioning-empirical.md}).
 */
class MexcFuturesSyncStrategyTest {

    @Nested
    @DisplayName("reaching SYNCED")
    class HappyPath {

        @Test
        @DisplayName("snapshot plus buffered pushes syncs the book and applies every level")
        void snapshotAndBufferedPushesReachSynced() {
            Harness h = new Harness();

            h.wsMsg(push(100, 110, levels(lvl(99, 2.0)), ""));
            h.wsMsg(push(111, 120, levels(lvl(98, 0)), levels(lvl(104, 1.5))));

            h.restMsg(snapshot(105,
                    levels(lvl(99, 1), lvl(98, 1), lvl(97, 1)),
                    levels(lvl(101, 1), lvl(102, 1), lvl(103, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1, h.requests());
            assertEquals(2.0, h.book().getBids().get(99.0).quantity);
            assertFalse(h.book().getBids().containsKey(98.0), "vol 0 must remove the level");
            assertEquals(1.5, h.book().getAsks().get(104.0).quantity);
            assertEquals(2, h.book().getBids().size());
            assertEquals(4, h.book().getAsks().size());

            h.wsMsg(push(121, 130, levels(lvl(97, 3.0)), ""));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(3.0, h.book().getBids().get(97.0).quantity);
            assertEquals(130, h.ctx().lastVersion);
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("a snapshot inside a push's [begin, end] range syncs — the straddling case")
        void straddlingSnapshotSyncs() {
            // V1: 6 of 21 snapshots had begin <= version < end for the push carrying version + 1
            // (e.g. ZEC_USDT snapshot 8903933980, next push [8903933911, 8903933982]).
            Harness h = new Harness();
            h.wsMsg(push(8903933911L, 8903933982L, levels(lvl(99, 4.0)), ""));

            h.restMsg(snapshot(8903933980L, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(8903933982L, h.ctx().lastVersion);
            assertEquals(4.0, h.book().getBids().get(99.0).quantity, "the straddling push is applied");
        }

        @Test
        @DisplayName("a snapshot on a push boundary syncs — begin == version + 1")
        void boundarySnapshotSyncs() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, levels(lvl(99, 7.0)), ""));   // stale: wholly before the snapshot
            h.wsMsg(push(121, 130, levels(lvl(99, 4.0)), ""));

            h.restMsg(snapshot(120, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(130, h.ctx().lastVersion);
            assertEquals(4.0, h.book().getBids().get(99.0).quantity);
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("a push whose end equals the snapshot version is kept, not discarded")
        void pushEndingAtSnapshotVersionIsKept() {
            // Strict end < lastVersion for IGNORE, as Binance spot (decision log #10). The push
            // re-applies commits already in the snapshot, which absolute quantities make harmless.
            Harness h = new Harness();
            h.wsMsg(push(100, 110, levels(lvl(99, 1.0)), ""));

            h.restMsg(snapshot(110, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(110, h.ctx().lastVersion);
        }

        @Test
        @DisplayName("aggregated pushes are accepted — version jumps by hundreds between contiguous pushes")
        void aggregatedPushesAreContiguous() {
            // The documented `version == previous + 1` rule would desync on every one of these.
            Harness h = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));

            h.wsMsg(push(121, 490, levels(lvl(99, 2.0)), ""));      // +370, like BTC's first push
            h.wsMsg(push(491, 491, levels(lvl(99, 3.0)), ""));      // +1
            h.wsMsg(push(492, 1182, levels(lvl(99, 4.0)), ""));     // +691

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1182, h.ctx().lastVersion);
            assertEquals(4.0, h.book().getBids().get(99.0).quantity);
            assertEquals(1, h.requests(), "no resync across aggregated pushes");
        }

        @Test
        @DisplayName("an all-stale buffer is success: the book syncs and waits for a live push")
        void allStaleBufferStillSyncs() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, "", ""));

            h.restMsg(snapshot(200, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1, h.requests());
            assertEquals(200, h.ctx().lastVersion);

            h.wsMsg(push(195, 210, levels(lvl(99, 5.0)), ""));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(5.0, h.book().getBids().get(99.0).quantity);
        }

        @Test
        @DisplayName("computeDistance runs on the snapshot path, so far levels are swept immediately")
        void snapshotPathSweepsAndSetsDistance() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, "", ""));

            h.restMsg(snapshot(105, levels(lvl(99, 1), lvl(50, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertFalse(h.book().getBids().containsKey(50.0), "far level swept on the snapshot path");
            assertEquals(0.01, h.book().getBids().get(99.0).distance, 1e-9);
        }

        @Test
        @DisplayName("the buffer is fully drained once the book syncs")
        void bufferIsFullyDrainedOnceSynced() {
            Harness h = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(0, h.bufferSize());
        }
    }

    @Nested
    @DisplayName("levels and quantities")
    class Levels {

        @Test
        @DisplayName("vol is in contracts: snapshot and push quantities are scaled by the contract size")
        void quantitiesAreScaledByContractSize() {
            // BTC_USDT's contractSize is 0.0001: a 49,886-contract row is ~5 BTC, not 49,886 BTC.
            Harness h = new Harness(0.0001);
            h.wsMsg(push(100, 110, "", ""));

            h.restMsg(snapshot(105, "[99,49886,12]", "[101,276746,40]"));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(4.9886, h.book().getBids().get(99.0).quantity, 1e-9);
            assertEquals(27.6746, h.book().getAsks().get(101.0).quantity, 1e-9);

            h.wsMsg(push(111, 120, "[99,20000,5]", "[101,0,0]"));

            assertEquals(2.0, h.book().getBids().get(99.0).quantity, 1e-9);
            assertFalse(h.book().getAsks().containsKey(101.0), "a scaled zero still removes the level");
        }

        @Test
        @DisplayName("integer and fractional number tokens land on the same price key")
        void integerAndFractionalPricesShareAKey() {
            // Snapshot sends 99 as an integer token, the push as 99.0. Both must hit one level.
            Harness h = new Harness();
            h.wsMsg(push(100, 110, "", ""));
            h.restMsg(snapshot(105, "[99,1,1]", "[101,1,1]"));

            h.wsMsg(push(111, 120, "[99.0,6.5,2]", ""));

            assertEquals(1, h.book().getBids().size());
            assertEquals(6.5, h.book().getBids().get(99.0).quantity);
        }

        @Test
        @DisplayName("sequence fields after the levels: levels still land once the push is validated")
        void sequenceFieldsAfterLevelsStillApply() {
            // The delivered frame puts end/begin/version after asks/bids, and channel last. A
            // single-pass check-then-apply (Binance's shape) would have consumed the levels before
            // reaching the sequence fields; the pre-scan must leave them for the apply pass.
            Harness h = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));

            h.wsMsg(push(121, 130, levels(lvl(99, 9.0)), levels(lvl(101, 8.0))));

            assertEquals(9.0, h.book().getBids().get(99.0).quantity);
            assertEquals(8.0, h.book().getAsks().get(101.0).quantity);
        }

        @Test
        @DisplayName("the documented field order (channel first, sequence before levels) works too")
        void documentedFieldOrderStillApplies() {
            Harness h = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));

            h.wsMsg(pushDocumentedOrder(121, 130, levels(lvl(99, 9.0)), levels(lvl(101, 8.0))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(130, h.ctx().lastVersion);
            assertEquals(9.0, h.book().getBids().get(99.0).quantity);
            assertEquals(8.0, h.book().getAsks().get(101.0).quantity);
        }
    }

    @Nested
    @DisplayName("sequence validation")
    class SequenceValidation {

        @Test
        @DisplayName("a gap (begin > lastVersion + 1) resets the book and requests recovery exactly once")
        void gapTriggersResync() {
            Harness h = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));
            assertEquals(1, h.requests());

            // lastVersion is 120, so begin must be <= 121. It is not.
            h.wsMsg(push(130, 140, levels(lvl(99, 5)), ""));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize(), "the gap push must not be buffered");
        }

        @Test
        @DisplayName("an overlapping push is accepted, because quantities are absolute")
        void overlappingPushIsAccepted() {
            Harness h = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));

            h.wsMsg(push(115, 130, levels(lvl(99, 6.0)), ""));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(6.0, h.book().getBids().get(99.0).quantity);
            assertEquals(130, h.ctx().lastVersion);
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("a stale push while SYNCED is ignored: nothing applied, cursor unmoved, no resync")
        void stalePushIsIgnored() {
            Harness h = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));

            h.wsMsg(push(100, 119, levels(lvl(99, 7.0)), ""));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1.0, h.book().getBids().get(99.0).quantity);
            assertEquals(120, h.ctx().lastVersion);
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("the first buffered push is validated like any other, not applied blindly")
        void firstBufferedPushIsValidated() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, levels(lvl(99, 4.0)), ""));
            h.wsMsg(push(111, 120, "", ""));

            h.restMsg(snapshot(200, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1.0, h.book().getBids().get(99.0).quantity,
                    "the stale buffered push must not have overwritten the snapshot level");
        }

        @Test
        @DisplayName("a push without begin/end resyncs — the fields are undocumented, so losing them must be loud")
        void missingBeginEndResyncs() {
            Harness h = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));

            h.wsMsg("{\"symbol\":\"BTC_USDT\",\"data\":{\"asks\":[],\"bids\":[[99,5,1]],\"version\":121},"
                    + "\"channel\":\"push.depth\",\"ts\":1}");

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
        }

        @Test
        @DisplayName("readVersionField parses the integer after the key and rejects a non-integer")
        void readVersionField() {
            assertEquals(8903933982L, MexcFuturesSyncStrategy.readVersionField("{\"end\":8903933982,\"x\":1}", "\"end\":"));
            assertEquals(7, MexcFuturesSyncStrategy.readVersionField("{\"end\":7}", "\"end\":"));
            assertThrows(IllegalStateException.class,
                    () -> MexcFuturesSyncStrategy.readVersionField("{\"end\":null}", "\"end\":"));
            assertThrows(IllegalStateException.class,
                    () -> MexcFuturesSyncStrategy.readVersionField("{\"version\":7}", "\"end\":"));
        }
    }

    @Nested
    @DisplayName("snapshot handling")
    class SnapshotHandling {

        @Test
        @DisplayName("a snapshot arriving while SYNCED is dropped, not applied")
        void snapshotWhileSyncedIsDropped() {
            Harness h = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));

            h.restMsg(snapshot(500, levels(lvl(90, 7)), levels(lvl(110, 7))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1, h.requests());
            assertEquals(120, h.ctx().lastVersion);
            assertEquals(1.0, h.book().getBids().get(99.0).quantity);
            assertFalse(h.book().getBids().containsKey(90.0));
        }

        @Test
        @DisplayName("a stale snapshot — older than the first buffered push's begin - 1 — resyncs once")
        void staleSnapshotResyncs() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, "", ""));

            // version 50: the push is not stale (end >= 50), but begin 100 > 51, so the commits
            // 51..99 are in neither the snapshot nor the buffer.
            h.restMsg(snapshot(50, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());
            assertTrue(h.book().getBids().isEmpty(), "the snapshot's levels are cleared by recover()");
        }

        @Test
        @DisplayName("a gap part-way through the buffer drain resyncs with exactly one request")
        void gapDuringBufferDrainResyncsOnce() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, "", ""));
            h.wsMsg(push(111, 120, "", ""));
            h.wsMsg(push(200, 210, levels(lvl(99, 1)), ""));  // gap: expected begin <= 121

            h.restMsg(snapshot(105, levels(lvl(99, 1)), levels(lvl(101, 1))));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());
        }

        @Test
        @DisplayName("a malformed snapshot resyncs and leaves no partial levels behind")
        void malformedSnapshotResyncs() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, "", ""));

            h.restMsg("{\"success\":true,\"code\":0,\"data\":{\"asks\":[[101,1,1]],\"bids\":[[99.0,");

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());
            assertTrue(h.book().getBids().isEmpty());
            assertTrue(h.book().getAsks().isEmpty());
        }

        @Test
        @DisplayName("a throttled 510 body that slips past the fetcher resyncs instead of syncing an empty book")
        void throttledBodyResyncs() {
            // Keeping 510s out of the ring is the fetcher's job (Phase 4b); this is the backstop.
            Harness h = new Harness();
            h.wsMsg(push(100, 110, "", ""));

            h.restMsg("{\"success\":false,\"code\":510,\"message\":\"Requests are too frequent, please try again later\"}");

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
        }

        @Test
        @DisplayName("a snapshot without a version resyncs")
        void snapshotWithoutVersionResyncs() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, "", ""));

            h.restMsg("{\"success\":true,\"code\":0,\"data\":{\"asks\":[[101,1,1]],\"bids\":[[99,1,1]]}}");

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertTrue(h.book().getAsks().isEmpty());
        }
    }

    @Nested
    @DisplayName("the recovery handshake")
    class RecoveryHandshake {

        @Test
        @DisplayName("a push in PENDING with the sink accepting buffers the triggering push")
        void pendingPushIsBufferedWhenSinkAccepts() {
            Harness h = new Harness();

            h.wsMsg(push(100, 110, levels(lvl(99, 1)), ""));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(1, h.requests());
            assertEquals(1, h.bufferSize());
        }

        @Test
        @DisplayName("a refused book stays PENDING, buffers nothing, and re-asks on every push")
        void refusedBookRetriesOnEveryPush() {
            Harness h = new Harness();
            h.sink.accepts = false;

            h.wsMsg(push(100, 110, "", ""));
            h.wsMsg(push(111, 120, "", ""));
            h.wsMsg(push(121, 130, "", ""));

            assertEquals(OrderBookState.PENDING, h.book().getState());
            assertEquals(3, h.requests());
            assertEquals(0, h.bufferSize());

            h.sink.accepts = true;
            h.wsMsg(push(131, 140, "", ""));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(4, h.requests());
            assertEquals(1, h.bufferSize());
        }

        @Test
        @DisplayName("overflowing the buffer resyncs and discards the triggering push")
        void bufferOverflowResyncs() {
            Harness h = new Harness();

            h.wsMsg(push(100, 110, "", ""));
            for (int i = 0; i < MexcSyncContext.MAX_BUFFER_SIZE - 1; i++) {
                h.wsMsg(push(111 + i, 111 + i, "", ""));
            }
            assertEquals(MexcSyncContext.MAX_BUFFER_SIZE, h.bufferSize());
            assertEquals(1, h.requests());

            h.wsMsg(push(900, 901, "", ""));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());
        }

        @Test
        @DisplayName("at most one recovery request per event, across every failure shape")
        void atMostOneRecoveryRequestPerEvent() {
            Harness gap = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));
            gap.wsMsg(push(999, 1000, "", ""));
            assertEquals(2, gap.requests());

            Harness malformed = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));
            malformed.wsMsg("{\"symbol\":\"BTC_USDT\",\"data\":{\"asks\":[[101,");
            assertEquals(OrderBookState.RECOVERING, malformed.book().getState());
            assertEquals(2, malformed.requests());

            // Passes check() (begin/end are well-formed) but the levels are not.
            Harness badLevels = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));
            badLevels.wsMsg("{\"symbol\":\"BTC_USDT\",\"data\":{\"asks\":[[101,oops]],\"bids\":[],"
                    + "\"end\":130,\"begin\":121,\"version\":130},\"channel\":\"push.depth\",\"ts\":1}");
            assertEquals(OrderBookState.RECOVERING, badLevels.book().getState());
            assertEquals(2, badLevels.requests());

            Harness overflow = new Harness();
            overflow.wsMsg(push(100, 110, "", ""));
            for (int i = 0; i < MexcSyncContext.MAX_BUFFER_SIZE; i++) {
                overflow.wsMsg(push(111 + i, 111 + i, "", ""));
            }
            assertEquals(2, overflow.requests());
        }

        @Test
        @DisplayName("the resync counter tracks recover() calls, not recovery requests")
        void resyncCounterCountsRecoveries() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, "", ""));
            assertEquals(1, h.requests());
            assertEquals(0, h.metrics.resyncs(VENUE));

            h.restMsg(snapshot(105, levels(lvl(99, 1)), levels(lvl(101, 1))));
            assertEquals(0, h.metrics.resyncs(VENUE));

            h.wsMsg(push(500, 510, "", ""));                         // gap
            assertEquals(1, h.metrics.resyncs(VENUE));

            h.sink.accepts = false;
            h.wsMsg(push(511, 520, "", ""));
            h.wsMsg(push(521, 530, "", ""));
            assertEquals(1, h.metrics.resyncs(VENUE), "a PENDING retry is not a new de-sync");
            assertEquals(0, h.metrics.resyncs(Venue.BINANCE_FUTURES));
        }

        @Test
        @DisplayName("invariant 3 — a book leaving SYNCED is emptied, and its context reset")
        void recoverClearsLevelsAndContext() {
            Harness h = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));

            h.wsMsg(push(500, 510, "", ""));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertTrue(h.book().getBids().isEmpty());
            assertTrue(h.book().getAsks().isEmpty());
            assertEquals(0, h.bufferSize());
            assertEquals(-1, h.ctx().lastVersion);
        }

        @Test
        @DisplayName("a fresh snapshot fully replaces the pre-desync contents")
        void snapshotReplacesPreviousLevels() {
            Harness h = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));

            h.wsMsg(push(500, 510, "", ""));                 // gap → recover
            h.wsMsg(push(600, 610, "", ""));
            h.restMsg(snapshot(605, levels(lvl(90, 1)), levels(lvl(110, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertFalse(h.book().getBids().containsKey(99.0));
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
            Harness h = new Harness();
            h.wsMsg(push(100, 110, levels(lvl(99, 1)), ""));
            h.wsMsg(push(111, 120, "", ""));

            h.restFailed();

            assertEquals(OrderBookState.PENDING, h.book().getState());
            assertEquals(0, h.bufferSize());
            assertEquals(-1, h.ctx().lastVersion);
            assertTrue(h.book().getBids().isEmpty());
            assertEquals(0, h.metrics.resyncs(VENUE), "nothing de-synced: the request just failed");
        }

        @Test
        @DisplayName("is ignored on a SYNCED book")
        void ignoredWhenSynced() {
            Harness h = synced(levels(lvl(99, 1)), levels(lvl(101, 1)));

            h.restFailed();

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(120, h.ctx().lastVersion);
            assertTrue(h.book().getBids().containsKey(99.0));
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("is ignored on a PENDING book")
        void ignoredWhenPending() {
            Harness h = new Harness();
            h.sink.accepts = false;
            h.wsMsg(push(100, 110, "", ""));

            h.restFailed();

            assertEquals(OrderBookState.PENDING, h.book().getState());
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("does not re-request at once — the next push does, through the PENDING path")
        void nextPushReRequests() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, "", ""));

            h.restFailed();
            assertEquals(1, h.requests());

            h.wsMsg(push(111, 120, "", ""));

            assertEquals(2, h.requests());
            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(1, h.bufferSize());
        }
    }
}
