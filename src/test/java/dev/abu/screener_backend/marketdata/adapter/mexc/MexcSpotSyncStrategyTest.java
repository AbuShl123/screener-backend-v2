package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.book.OrderBookState;
import dev.abu.screener_backend.marketdata.core.book.PriceLevelEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.WRAPPER_AGGRE_DEPTHS;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.WRAPPER_SYMBOL;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.Harness;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.Proto;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.VENUE;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.fixtureFrames;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.fixtureText;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.item;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.lvl;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.push;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.row;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.rows;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.snapshot;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.synced;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MEXC spot sync algorithm. Mirrors {@code MexcFuturesSyncStrategyTest}: the dispatch and
 * recovery cases are the same contract (progress doc §7), and the sequence rule is the shared
 * {@link MexcVersionRange}, here on {@code fromVersion} / {@code toVersion} against
 * {@code lastUpdateId} ({@code external-docs/mexc/mexc-spot-depth-empirical.md} §2).
 */
class MexcSpotSyncStrategyTest {

    private static final List<MexcSpotTestSupport.Level> NONE = List.of();

    @Nested
    @DisplayName("reaching SYNCED")
    class HappyPath {

        @Test
        @DisplayName("snapshot plus buffered pushes syncs the book and applies every level")
        void snapshotAndBufferedPushesReachSynced() {
            Harness h = new Harness();

            h.wsMsg(push(100, 110, List.of(lvl(99, 2.0)), NONE));
            h.wsMsg(push(111, 120, List.of(lvl(98, 0)), List.of(lvl(104, 1.5))));

            h.restMsg(snapshot(105,
                    rows(row(99, 1), row(98, 1), row(97, 1)),
                    rows(row(101, 1), row(102, 1), row(103, 1))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1, h.requests());
            assertEquals(2.0, h.book().getBids().get(99.0).quantity);
            assertFalse(h.book().getBids().containsKey(98.0), "quantity 0 must remove the level");
            assertEquals(1.5, h.book().getAsks().get(104.0).quantity);
            assertEquals(2, h.book().getBids().size());
            assertEquals(4, h.book().getAsks().size());

            h.wsMsg(push(121, 130, List.of(lvl(97, 3.0)), NONE));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(3.0, h.book().getBids().get(97.0).quantity);
            assertEquals(130, h.ctx().lastVersion);
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("a snapshot inside a push's [fromVersion, toVersion] range syncs — the straddling case")
        void straddlingSnapshotSyncs() {
            // Phase 0: 58 of 106 snapshots landed inside a push's range (BTC ~87%).
            Harness h = new Harness();
            h.wsMsg(push(83321028320L, 83321028630L, List.of(lvl(99, 4.0)), NONE));

            h.restMsg(snapshot(83321028500L, row(99, 1), row(101, 1)));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(83321028630L, h.ctx().lastVersion);
            assertEquals(4.0, h.book().getBids().get(99.0).quantity, "the straddling push is applied");
        }

        @Test
        @DisplayName("a snapshot on a push boundary syncs — fromVersion == lastUpdateId + 1")
        void boundarySnapshotSyncs() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, List.of(lvl(99, 7.0)), NONE));   // stale: wholly before the snapshot
            h.wsMsg(push(121, 130, List.of(lvl(99, 4.0)), NONE));

            h.restMsg(snapshot(120, row(99, 1), row(101, 1)));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(130, h.ctx().lastVersion);
            assertEquals(4.0, h.book().getBids().get(99.0).quantity);
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("a push whose toVersion equals lastUpdateId is kept, not discarded")
        void pushEndingAtSnapshotVersionIsKept() {
            // lastUpdateId is inclusive (Phase 0 §2), so re-applying this push is harmless; strict <
            // keeps the predicate shared with futures.
            Harness h = new Harness();
            h.wsMsg(push(100, 110, List.of(lvl(99, 1.0)), NONE));

            h.restMsg(snapshot(110, row(99, 1), row(101, 1)));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(110, h.ctx().lastVersion);
        }

        @Test
        @DisplayName("single-commit pushes (fromVersion == toVersion) are contiguous")
        void singleCommitPushes() {
            Harness h = synced(row(99, 1), row(101, 1));

            h.wsMsg(push(121, 121, List.of(lvl(99, 2.0)), NONE));
            h.wsMsg(push(122, 122, List.of(lvl(99, 3.0)), NONE));
            h.wsMsg(push(123, 1256, List.of(lvl(99, 4.0)), NONE));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1256, h.ctx().lastVersion);
            assertEquals(4.0, h.book().getBids().get(99.0).quantity);
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("an all-stale buffer is success: the book syncs and waits for a live push")
        void allStaleBufferStillSyncs() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, NONE, NONE));

            h.restMsg(snapshot(200, row(99, 1), row(101, 1)));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(200, h.ctx().lastVersion);

            h.wsMsg(push(195, 210, List.of(lvl(99, 5.0)), NONE));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(5.0, h.book().getBids().get(99.0).quantity);
        }

        @Test
        @DisplayName("computeDistance runs on the snapshot path, so far levels are swept immediately")
        void snapshotPathSweepsAndSetsDistance() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, NONE, NONE));

            h.restMsg(snapshot(105, rows(row(99, 1), row(50, 1)), row(101, 1)));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertFalse(h.book().getBids().containsKey(50.0), "far level swept on the snapshot path");
            assertEquals(0.01, h.book().getBids().get(99.0).distance, 1e-9);
        }

        @Test
        @DisplayName("the buffer is fully drained once the book syncs")
        void bufferIsFullyDrainedOnceSynced() {
            Harness h = synced(row(99, 1), row(101, 1));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(0, h.bufferSize());
        }
    }

    @Nested
    @DisplayName("levels and quantities")
    class Levels {

        @Test
        @DisplayName("padded snapshot numbers and trimmed push numbers share a key")
        void paddedAndTrimmedNumbersShareAKey() {
            // Snapshots pad to tick precision ("375.80"), pushes trim ("375.8"). A book keyed by
            // the raw text would hold two levels.
            Harness h = new Harness();
            h.wsMsg(push(100, 110, NONE, NONE));
            h.restMsg(snapshot(105, row("375.80", "1.500"), row("376.10", "2.000")));

            h.wsMsg(push(111, 120, List.of(lvl("375.8", "6.5")), List.of(lvl("376.1", "0"))));

            assertEquals(1, h.book().getBids().size());
            assertEquals(6.5, h.book().getBids().get(375.8).quantity);
            assertTrue(h.book().getAsks().isEmpty(), "the trimmed delete removes the padded level");
        }

        @Test
        @DisplayName("quantities are base asset: no multiplier is applied")
        void noMultiplier() {
            Harness h = synced(row(99, 1), row(101, 1));

            h.wsMsg(push(121, 130, List.of(lvl("99", "49886.25")), NONE));

            assertEquals(49886.25, h.book().getBids().get(99.0).quantity);
        }

        @Test
        @DisplayName("levels after a validated push land, with the versions after them on the wire")
        void sequenceAfterLevelsStillApplies() {
            Harness h = synced(row(99, 1), row(101, 1));

            h.wsMsg(push(121, 130, List.of(lvl(99, 9.0)), List.of(lvl(101, 8.0))));

            assertEquals(9.0, h.book().getBids().get(99.0).quantity);
            assertEquals(8.0, h.book().getAsks().get(101.0).quantity);
        }

        @Test
        @DisplayName("a frame at a non-zero arrayOffset is applied correctly")
        void slicedFrame() {
            Harness h = synced(row(99, 1), row(101, 1));
            byte[] frame = push(121, 130, List.of(lvl(99, 9.0)), NONE);
            byte[] padded = new byte[frame.length + 20];
            System.arraycopy(frame, 0, padded, 10, frame.length);

            h.wsMsg(ByteBuffer.wrap(padded, 10, frame.length).slice());

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(9.0, h.book().getBids().get(99.0).quantity);
        }
    }

    @Nested
    @DisplayName("sequence validation")
    class SequenceValidation {

        @Test
        @DisplayName("a gap (fromVersion > lastVersion + 1) resets the book and requests recovery exactly once")
        void gapTriggersResync() {
            Harness h = synced(row(99, 1), row(101, 1));
            assertEquals(1, h.requests());

            h.wsMsg(push(130, 140, List.of(lvl(99, 5)), NONE));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize(), "the gap push must not be buffered");
        }

        @Test
        @DisplayName("an overlapping push is accepted, because quantities are absolute")
        void overlappingPushIsAccepted() {
            Harness h = synced(row(99, 1), row(101, 1));

            h.wsMsg(push(115, 130, List.of(lvl(99, 6.0)), NONE));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(6.0, h.book().getBids().get(99.0).quantity);
            assertEquals(130, h.ctx().lastVersion);
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("a stale push while SYNCED is ignored: nothing applied, cursor unmoved, no resync")
        void stalePushIsIgnored() {
            Harness h = synced(row(99, 1), row(101, 1));

            h.wsMsg(push(100, 119, List.of(lvl(99, 7.0)), NONE));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1.0, h.book().getBids().get(99.0).quantity);
            assertEquals(120, h.ctx().lastVersion);
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("the first buffered push is validated like any other, not applied blindly")
        void firstBufferedPushIsValidated() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, List.of(lvl(99, 4.0)), NONE));
            h.wsMsg(push(111, 120, NONE, NONE));

            h.restMsg(snapshot(200, row(99, 1), row(101, 1)));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1.0, h.book().getBids().get(99.0).quantity,
                    "the stale buffered push must not have overwritten the snapshot level");
        }

        @Test
        @DisplayName("a push without toVersion resyncs — format drift must be loud")
        void missingVersionResyncs() {
            Harness h = synced(row(99, 1), row(101, 1));

            h.wsMsg(new Proto()
                    .string(WRAPPER_SYMBOL, "BTCUSDT")
                    .message(WRAPPER_AGGRE_DEPTHS, new Proto().message(2, item(lvl(99, 5))).string(4, "121"))
                    .toByteArray());

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
        }

        @Test
        @DisplayName("a frame without the depth body resyncs")
        void missingBodyResyncs() {
            Harness h = synced(row(99, 1), row(101, 1));

            h.wsMsg(new Proto().string(WRAPPER_SYMBOL, "BTCUSDT").varint(6, 1).toByteArray());

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
        }
    }

    @Nested
    @DisplayName("snapshot handling")
    class SnapshotHandling {

        @Test
        @DisplayName("a snapshot arriving while SYNCED is dropped, not applied")
        void snapshotWhileSyncedIsDropped() {
            Harness h = synced(row(99, 1), row(101, 1));

            h.restMsg(snapshot(500, row(90, 7), row(110, 7)));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1, h.requests());
            assertEquals(120, h.ctx().lastVersion);
            assertEquals(1.0, h.book().getBids().get(99.0).quantity);
            assertFalse(h.book().getBids().containsKey(90.0));
        }

        @Test
        @DisplayName("a stale snapshot — older than the first buffered push's fromVersion - 1 — resyncs once")
        void staleSnapshotResyncs() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, NONE, NONE));

            h.restMsg(snapshot(50, row(99, 1), row(101, 1)));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());
            assertTrue(h.book().getBids().isEmpty(), "the snapshot's levels are cleared by recover()");
        }

        @Test
        @DisplayName("a gap part-way through the buffer drain resyncs with exactly one request")
        void gapDuringBufferDrainResyncsOnce() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, NONE, NONE));
            h.wsMsg(push(111, 120, NONE, NONE));
            h.wsMsg(push(200, 210, List.of(lvl(99, 1)), NONE));   // gap: expected fromVersion <= 121

            h.restMsg(snapshot(105, row(99, 1), row(101, 1)));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());
        }

        @Test
        @DisplayName("a malformed snapshot resyncs and leaves no partial levels behind")
        void malformedSnapshotResyncs() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, NONE, NONE));

            h.restMsg("{\"lastUpdateId\":105,\"bids\":[[\"99\",\"1\"]],\"asks\":[[\"101\",");

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());
            assertTrue(h.book().getBids().isEmpty());
            assertTrue(h.book().getAsks().isEmpty());
        }

        @Test
        @DisplayName("an error body that slips past the fetcher resyncs instead of syncing an empty book")
        void errorBodyResyncs() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, NONE, NONE));

            h.restMsg("{\"code\":-1121,\"msg\":\"Invalid symbol.\"}");

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
        }

        @Test
        @DisplayName("a snapshot without lastUpdateId resyncs")
        void snapshotWithoutLastUpdateIdResyncs() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, NONE, NONE));

            h.restMsg("{\"bids\":[[\"99\",\"1\"]],\"asks\":[[\"101\",\"1\"]],\"timestamp\":1}");

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

            h.wsMsg(push(100, 110, List.of(lvl(99, 1)), NONE));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(1, h.requests());
            assertEquals(1, h.bufferSize());
        }

        @Test
        @DisplayName("a refused book stays PENDING, buffers nothing, and re-asks on every push")
        void refusedBookRetriesOnEveryPush() {
            Harness h = new Harness();
            h.sink.accepts = false;

            h.wsMsg(push(100, 110, NONE, NONE));
            h.wsMsg(push(111, 120, NONE, NONE));
            h.wsMsg(push(121, 130, NONE, NONE));

            assertEquals(OrderBookState.PENDING, h.book().getState());
            assertEquals(3, h.requests());
            assertEquals(0, h.bufferSize());

            h.sink.accepts = true;
            h.wsMsg(push(131, 140, NONE, NONE));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(4, h.requests());
            assertEquals(1, h.bufferSize());
        }

        @Test
        @DisplayName("overflowing the buffer resyncs and discards the triggering push")
        void bufferOverflowResyncs() {
            Harness h = new Harness();

            h.wsMsg(push(100, 110, NONE, NONE));
            for (int i = 0; i < MexcSpotSyncContext.MAX_BUFFER_SIZE - 1; i++) {
                h.wsMsg(push(111 + i, 111 + i, NONE, NONE));
            }
            assertEquals(MexcSpotSyncContext.MAX_BUFFER_SIZE, h.bufferSize());
            assertEquals(1, h.requests());

            h.wsMsg(push(900, 901, NONE, NONE));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());
        }

        @Test
        @DisplayName("at most one recovery request per event, across every failure shape")
        void atMostOneRecoveryRequestPerEvent() {
            Harness gap = synced(row(99, 1), row(101, 1));
            gap.wsMsg(push(999, 1000, NONE, NONE));
            assertEquals(2, gap.requests());

            Harness truncated = synced(row(99, 1), row(101, 1));
            byte[] frame = push(121, 130, List.of(lvl(99, 1)), NONE);
            truncated.wsMsg(ByteBuffer.wrap(frame, 0, frame.length - 3));
            assertEquals(OrderBookState.RECOVERING, truncated.book().getState());
            assertEquals(2, truncated.requests());

            // Passes the version check but the levels are not numbers.
            Harness badLevels = synced(row(99, 1), row(101, 1));
            badLevels.wsMsg(push(121, 130, List.of(lvl("99", "oops")), NONE));
            assertEquals(OrderBookState.RECOVERING, badLevels.book().getState());
            assertEquals(2, badLevels.requests());

            Harness overflow = new Harness();
            overflow.wsMsg(push(100, 110, NONE, NONE));
            for (int i = 0; i < MexcSpotSyncContext.MAX_BUFFER_SIZE; i++) {
                overflow.wsMsg(push(111 + i, 111 + i, NONE, NONE));
            }
            assertEquals(2, overflow.requests());
        }

        @Test
        @DisplayName("the resync counter tracks recover() calls, not recovery requests")
        void resyncCounterCountsRecoveries() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, NONE, NONE));
            assertEquals(1, h.requests());
            assertEquals(0, h.metrics.resyncs(VENUE));

            h.restMsg(snapshot(105, row(99, 1), row(101, 1)));
            assertEquals(0, h.metrics.resyncs(VENUE));

            h.wsMsg(push(500, 510, NONE, NONE));                         // gap
            assertEquals(1, h.metrics.resyncs(VENUE));

            h.sink.accepts = false;
            h.wsMsg(push(511, 520, NONE, NONE));
            h.wsMsg(push(521, 530, NONE, NONE));
            assertEquals(1, h.metrics.resyncs(VENUE), "a PENDING retry is not a new de-sync");
            assertEquals(0, h.metrics.resyncs(Venue.BINANCE_SPOT));
        }

        @Test
        @DisplayName("invariant 3 — a book leaving SYNCED is emptied, and its context reset")
        void recoverClearsLevelsAndContext() {
            Harness h = synced(row(99, 1), row(101, 1));

            h.wsMsg(push(500, 510, NONE, NONE));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertTrue(h.book().getBids().isEmpty());
            assertTrue(h.book().getAsks().isEmpty());
            assertEquals(0, h.bufferSize());
            assertEquals(-1, h.ctx().lastVersion);
        }

        @Test
        @DisplayName("a fresh snapshot fully replaces the pre-desync contents")
        void snapshotReplacesPreviousLevels() {
            Harness h = synced(row(99, 1), row(101, 1));

            h.wsMsg(push(500, 510, NONE, NONE));                 // gap → recover
            h.wsMsg(push(600, 610, NONE, NONE));
            h.restMsg(snapshot(605, row(90, 1), row(110, 1)));

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
            h.wsMsg(push(100, 110, List.of(lvl(99, 1)), NONE));
            h.wsMsg(push(111, 120, NONE, NONE));

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
            Harness h = synced(row(99, 1), row(101, 1));

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
            h.wsMsg(push(100, 110, NONE, NONE));

            h.restFailed();

            assertEquals(OrderBookState.PENDING, h.book().getState());
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("does not re-request at once — the next push does, through the PENDING path")
        void nextPushReRequests() {
            Harness h = new Harness();
            h.wsMsg(push(100, 110, NONE, NONE));

            h.restFailed();
            assertEquals(1, h.requests());

            h.wsMsg(push(111, 120, NONE, NONE));

            assertEquals(2, h.requests());
            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(1, h.bufferSize());
        }
    }

    @Nested
    @DisplayName("Phase 0 capture: MINTUSDT end to end")
    class Capture {

        @Test
        @DisplayName("a straddling snapshot plus 120 pushes equals the next snapshot on the full book")
        void replayMatchesNextSnapshot() {
            List<byte[]> pushes = fixtureFrames("pushes-mintusdt-44408968-44409143.frames");
            String first = fixtureText("snapshot-mintusdt-44408969.json");
            String next = fixtureText("snapshot-mintusdt-44409143.json");
            assertEquals(120, pushes.size());

            // No price filter: the comparison is on the full book, far junk orders included.
            Harness h = new Harness(Double.POSITIVE_INFINITY);
            h.wsMsg(pushes.getFirst());                 // [44408968, 44408970] → RECOVERING, buffered
            h.restMsg(first);                           // lastUpdateId 44408969, inside that range

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(44408970L, h.ctx().lastVersion, "the straddling push is accepted");

            for (byte[] push : pushes.subList(1, pushes.size())) {
                h.wsMsg(push);
            }

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(44409143L, h.ctx().lastVersion);
            assertEquals(1, h.requests(), "no gap across the capture");
            assertEquals(0, h.metrics.resyncs(VENUE));

            Harness expected = new Harness(Double.POSITIVE_INFINITY);
            MexcSpotSyncStrategy strategy = (MexcSpotSyncStrategy) expected.slot.strategy();
            assertEquals(44409143L, strategy.applySnapshot(expected.slot, next));

            assertEquals(quantities(expected.book().getBids()), quantities(h.book().getBids()));
            assertEquals(quantities(expected.book().getAsks()), quantities(h.book().getAsks()));
        }

        private Map<Double, Double> quantities(TreeMap<Double, PriceLevelEntry> side) {
            Map<Double, Double> out = new TreeMap<>();
            side.forEach((price, entry) -> out.put(price, entry.quantity));
            return out;
        }
    }
}
