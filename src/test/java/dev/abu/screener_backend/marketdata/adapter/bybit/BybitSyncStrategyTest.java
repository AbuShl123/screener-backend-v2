package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.book.OrderBookState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static dev.abu.screener_backend.marketdata.adapter.bybit.BybitSyncTestSupport.Harness;
import static dev.abu.screener_backend.marketdata.adapter.bybit.BybitSyncTestSupport.delta;
import static dev.abu.screener_backend.marketdata.adapter.bybit.BybitSyncTestSupport.levels;
import static dev.abu.screener_backend.marketdata.adapter.bybit.BybitSyncTestSupport.lvl;
import static dev.abu.screener_backend.marketdata.adapter.bybit.BybitSyncTestSupport.quantities;
import static dev.abu.screener_backend.marketdata.adapter.bybit.BybitSyncTestSupport.rest;
import static dev.abu.screener_backend.marketdata.adapter.bybit.BybitSyncTestSupport.snapshot;
import static dev.abu.screener_backend.marketdata.adapter.bybit.BybitSyncTestSupport.spotFrame;
import static dev.abu.screener_backend.marketdata.adapter.bybit.BybitSyncTestSupport.synced;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Bybit sync algorithm: the progress doc's §7 contract, plus the in-stream snapshot as a full
 * replacement in any state, and the strict {@code u + 1} rule
 * ({@code external-docs/bybit/bybit-api-contracts.md} §5.3–§5.5, §7.1).
 */
class BybitSyncStrategyTest {

    private static final String BIDS = levels(lvl("99", "1"), lvl("98", "1"), lvl("97", "1"));
    private static final String ASKS = levels(lvl("101", "1"), lvl("102", "1"), lvl("103", "1"));

    @Nested
    @DisplayName("in-stream snapshot")
    class InStreamSnapshot {

        @Test
        @DisplayName("cold start: the first frame of a subscription syncs the book with no REST call")
        void coldStartSyncsInStream() {
            Harness h = new Harness();

            h.wsMsg(snapshot(500, BIDS, ASKS));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(0, h.requests(), "no REST snapshot on cold start");
            assertEquals(0, h.resyncs());
            assertEquals(500, h.ctx().lastU);
            assertEquals(3, h.book().getBids().size());
            assertEquals(3, h.book().getAsks().size());
            assertEquals(0.01, h.book().getBids().get(99.0).distance, 1e-9, "distance computed on the snapshot path");
        }

        @Test
        @DisplayName("spot field order (ts before type) is recognised as a snapshot too")
        void spotFieldOrder() {
            Harness h = new Harness(Venue.BYBIT_SPOT, BybitSyncTestSupport.FILTER);

            h.wsMsg(spotFrame("snapshot", 500, BIDS, ASKS));
            h.wsMsg(spotFrame("delta", 501, levels(lvl("99", "4")), ""));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(0, h.requests());
            assertEquals(4.0, h.book().getBids().get(99.0).quantity);
            assertEquals(501, h.ctx().lastU);
        }

        @Test
        @DisplayName("a snapshot on a SYNCED book replaces it outright — never merged, never gap-checked")
        void resnapshotReplacesSyncedBook() {
            // u keeps advancing server-side while unsubscribed (§5.4), so the new snapshot is far ahead.
            Harness h = synced(500, BIDS, ASKS);

            h.wsMsg(snapshot(9_000, levels(lvl("99.5", "2")), levels(lvl("100.5", "3"))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(List.of(99.5), List.copyOf(h.book().getBids().keySet()), "old levels are gone");
            assertEquals(List.of(100.5), List.copyOf(h.book().getAsks().keySet()));
            assertEquals(9_000, h.ctx().lastU);
            assertEquals(0, h.requests());
            assertEquals(0, h.resyncs(), "a re-snapshot is not a resync");

            h.wsMsg(delta(9_001, levels(lvl("99.5", "5")), ""));
            assertEquals(5.0, h.book().getBids().get(99.5).quantity);
        }

        @Test
        @DisplayName("u == 1 is a service restart: a full replacement even when typed delta")
        void restartMarkerReplaces() {
            Harness h = synced(500, BIDS, ASKS);

            h.wsMsg(delta(1, levels(lvl("99.5", "2")), levels(lvl("100.5", "3"))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(List.of(99.5), List.copyOf(h.book().getBids().keySet()), "replaced, not merged");
            assertEquals(1, h.ctx().lastU);
            assertEquals(0, h.requests());

            h.wsMsg(delta(2, levels(lvl("99.5", "6")), ""));
            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(6.0, h.book().getBids().get(99.5).quantity);
        }

        @Test
        @DisplayName("a snapshot on a RECOVERING book syncs it; buffered deltas go, the late REST outcome is dropped")
        void snapshotWhileRecoveringWinsTheRace() {
            Harness h = synced(500, BIDS, ASKS);
            h.wsMsg(delta(510, levels(lvl("99", "7")), ""));         // gap → RECOVERING
            h.wsMsg(delta(511, levels(lvl("99", "8")), ""));         // buffered
            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(1, h.bufferSize());

            h.wsMsg(snapshot(600, levels(lvl("99", "1")), levels(lvl("101", "1"))));   // e.g. a reconnect

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(0, h.bufferSize(), "deltas before the snapshot are superseded");
            assertEquals(600, h.ctx().lastU);

            h.restMsg(rest(505, levels(lvl("99", "9")), levels(lvl("101", "9"))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1.0, h.book().getBids().get(99.0).quantity, "the late REST snapshot never touches the book");
            assertEquals(600, h.ctx().lastU);
            assertEquals(1, h.requests());
        }

        @Test
        @DisplayName("a snapshot with no u resyncs through the REST path")
        void snapshotWithoutUResyncs() {
            Harness h = new Harness();

            h.wsMsg("{\"topic\":\"orderbook.1000.ARBUSDT\",\"type\":\"snapshot\",\"ts\":1,\"data\":{\"s\":\"ARBUSDT\","
                    + "\"b\":[[\"99\",\"1\"]],\"a\":[[\"101\",\"1\"]],\"seq\":5},\"cts\":1}");

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertTrue(h.book().getBids().isEmpty(), "a PENDING / RECOVERING book has empty levels");
            assertEquals(1, h.requests());
            assertEquals(-1, h.ctx().lastU);
        }

        @Test
        @DisplayName("an unparseable snapshot resyncs, leaving no half-loaded levels")
        void malformedSnapshotResyncs() {
            Harness h = synced(500, BIDS, ASKS);

            h.wsMsg(snapshot(900, levels(lvl("99", "1"), "[\"oops\",\"1\"]"), ASKS));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertTrue(h.book().getBids().isEmpty());
            assertTrue(h.book().getAsks().isEmpty());
            assertEquals(1, h.resyncs());
        }
    }

    @Nested
    @DisplayName("deltas")
    class Deltas {

        @Test
        @DisplayName("u == lastU + 1 applies: size replaces, a new price inserts, size 0 deletes")
        void contiguousDeltaApplies() {
            Harness h = synced(500, BIDS, ASKS);

            h.wsMsg(delta(501, levels(lvl("99", "2.5"), lvl("98", "0"), lvl("96", "4")), levels(lvl("101", "0"))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(2.5, h.book().getBids().get(99.0).quantity);
            assertFalse(h.book().getBids().containsKey(98.0), "size 0 must remove the level");
            assertEquals(4.0, h.book().getBids().get(96.0).quantity);
            assertFalse(h.book().getAsks().containsKey(101.0));
            assertEquals(501, h.ctx().lastU);
        }

        @Test
        @DisplayName("an empty side ([]) is normal: a delta touching only asks still carries \"b\":[]")
        void emptySide() {
            Harness h = synced(500, BIDS, ASKS);

            h.wsMsg(delta(501, "", levels(lvl("102", "9"))));

            assertEquals(3, h.book().getBids().size());
            assertEquals(9.0, h.book().getAsks().get(102.0).quantity);
        }

        @Test
        @DisplayName("padded (linear) and trimmed (spot) numbers land on the same price key")
        void paddedAndTrimmedShareAKey() {
            Harness h = synced(500, levels(lvl("99.50", "1.000")), levels(lvl("101.00", "1")));

            h.wsMsg(delta(501, levels(lvl("99.5", "3")), ""));

            assertEquals(1, h.book().getBids().size());
            assertEquals(3.0, h.book().getBids().get(99.5).quantity);
        }

        @Test
        @DisplayName("seq is ignored: it jumps arbitrarily and never triggers a resync")
        void seqIgnored() {
            Harness h = synced(500, BIDS, ASKS);

            h.wsMsg(delta(501, "", "").replaceFirst("\"seq\":\\d+", "\"seq\":1"));
            h.wsMsg(delta(502, "", "").replaceFirst("\"seq\":\\d+", "\"seq\":999999999999"));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(502, h.ctx().lastU);
        }

        @Test
        @DisplayName("a gap resyncs: levels cleared, context reset, one REST snapshot requested")
        void gapResyncs() {
            Harness h = synced(500, BIDS, ASKS);

            h.wsMsg(delta(502, levels(lvl("99", "5")), ""));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertTrue(h.book().getBids().isEmpty());
            assertTrue(h.book().getAsks().isEmpty());
            assertEquals(-1, h.ctx().lastU);
            assertEquals(0, h.bufferSize(), "the frame that exposed the gap is not buffered");
            assertEquals(1, h.requests());
            assertEquals(1, h.resyncs());
        }

        @Test
        @DisplayName("a stale delta (u <= lastU) is ignored without a resync")
        void staleDeltaIgnored() {
            Harness h = synced(500, BIDS, ASKS);

            h.wsMsg(delta(500, levels(lvl("99", "8")), ""));
            h.wsMsg(delta(499, levels(lvl("99", "8")), ""));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1.0, h.book().getBids().get(99.0).quantity, "nothing applied");
            assertEquals(500, h.ctx().lastU);
            assertEquals(0, h.requests());
        }

        @ParameterizedTest
        @ValueSource(strings = {"no u", "u not a number"})
        @DisplayName("a delta whose u cannot be read resyncs rather than being skipped as stale")
        void unreadableUResyncs(String variant) {
            Harness h = synced(500, BIDS, ASKS);
            String frame = variant.equals("no u")
                    ? delta(501, "", "").replace("\"u\":501,", "")
                    : delta(501, "", "").replace("\"u\":501", "\"u\":\"501\"");

            h.wsMsg(frame);

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(1, h.resyncs());
        }

        @Test
        @DisplayName("an unparseable delta resyncs")
        void malformedDeltaResyncs() {
            Harness h = synced(500, BIDS, ASKS);

            h.wsMsg(delta(501, levels("[\"99\",\"x\"]"), ""));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(1, h.resyncs());
        }
    }

    @Nested
    @DisplayName("REST recovery")
    class RestRecovery {

        /** A book that hit a gap after u=500 and is now RECOVERING, its request accepted. */
        private Harness recovering() {
            Harness h = synced(500, BIDS, ASKS);
            h.wsMsg(delta(510, "", ""));
            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            return h;
        }

        @Test
        @DisplayName("stitch on u: buffered deltas at or below the snapshot's u are ignored, the rest applied")
        void stitchOnU() {
            Harness h = recovering();
            h.wsMsg(delta(511, levels(lvl("99", "11")), ""));
            h.wsMsg(delta(512, levels(lvl("99", "12")), ""));
            h.wsMsg(delta(513, levels(lvl("99", "13")), ""));
            h.wsMsg(delta(514, levels(lvl("98", "0")), ""));

            h.restMsg(rest(512, levels(lvl("99", "12"), lvl("98", "1")), levels(lvl("101", "1"))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(514, h.ctx().lastU);
            assertEquals(13.0, h.book().getBids().get(99.0).quantity);
            assertFalse(h.book().getBids().containsKey(98.0));
            assertEquals(0, h.bufferSize());
            assertEquals(1, h.requests());

            h.wsMsg(delta(515, levels(lvl("99", "15")), ""));
            assertEquals(15.0, h.book().getBids().get(99.0).quantity);
        }

        @Test
        @DisplayName("an empty or all-stale buffer is success: the book syncs on the snapshot")
        void allStaleBufferSyncs() {
            Harness h = recovering();
            h.wsMsg(delta(511, levels(lvl("99", "11")), ""));

            h.restMsg(rest(520, levels(lvl("99", "2")), levels(lvl("101", "2"))));

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(520, h.ctx().lastU);
            assertEquals(2.0, h.book().getBids().get(99.0).quantity);
        }

        @Test
        @DisplayName("a REST snapshot older than the buffer leaves a gap: resync")
        void snapshotBehindBufferResyncs() {
            Harness h = recovering();
            h.wsMsg(delta(511, "", ""));
            h.wsMsg(delta(512, "", ""));

            h.restMsg(rest(505, BIDS, ASKS));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertTrue(h.book().getBids().isEmpty());
            assertEquals(0, h.bufferSize());
            assertEquals(2, h.requests());
            assertEquals(2, h.resyncs());
        }

        @Test
        @DisplayName("a REST body without result.u resyncs")
        void restWithoutUResyncs() {
            Harness h = recovering();

            h.restMsg("{\"retCode\":0,\"retMsg\":\"OK\",\"result\":{\"s\":\"ARBUSDT\",\"a\":[],\"b\":[]},\"time\":1}");

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(2, h.requests());
        }

        @Test
        @DisplayName("REST_FAILED on a RECOVERING book: back to PENDING, empty context, no re-request")
        void restFailedGoesPending() {
            Harness h = recovering();
            h.wsMsg(delta(511, "", ""));

            h.restFailed();

            assertEquals(OrderBookState.PENDING, h.book().getState());
            assertEquals(0, h.bufferSize());
            assertEquals(-1, h.ctx().lastU);
            assertEquals(1, h.requests());
            assertEquals(1, h.resyncs(), "a failed request is not another resync");

            h.wsMsg(delta(512, "", ""));
            assertEquals(OrderBookState.RECOVERING, h.book().getState(), "the next delta re-asks");
            assertEquals(2, h.requests());
            assertEquals(1, h.bufferSize());
        }

        @Test
        @DisplayName("REST outcomes for a book that is not RECOVERING are dropped")
        void restOutcomeOnSyncedBookDropped() {
            Harness h = synced(500, BIDS, ASKS);

            h.restMsg(rest(400, levels(lvl("99", "9")), ""));
            h.restFailed();

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1.0, h.book().getBids().get(99.0).quantity);
            assertEquals(500, h.ctx().lastU);
        }

        @Test
        @DisplayName("a PENDING book that gets a delta asks for recovery and buffers it")
        void pendingDeltaRequests() {
            Harness h = new Harness();

            h.wsMsg(delta(42, "", ""));

            assertEquals(OrderBookState.RECOVERING, h.book().getState());
            assertEquals(1, h.requests());
            assertEquals(1, h.bufferSize());
            assertEquals(0, h.resyncs(), "asking from PENDING is not a resync");
        }

        @Test
        @DisplayName("refused: the delta is dropped and the book stays PENDING, asking again on every delta")
        void refusedRetriesOnEveryDelta() {
            Harness h = new Harness();
            h.sink.accepts = false;

            h.wsMsg(delta(42, "", ""));
            h.wsMsg(delta(43, "", ""));

            assertEquals(OrderBookState.PENDING, h.book().getState());
            assertEquals(2, h.requests());
            assertEquals(0, h.bufferSize());

            h.sink.accepts = true;
            h.wsMsg(delta(44, "", ""));
            assertEquals(OrderBookState.RECOVERING, h.book().getState());
        }

        @Test
        @DisplayName("buffer overflow resyncs")
        void bufferOverflowResyncs() {
            Harness h = recovering();
            for (int i = 0; i < BybitSyncContext.MAX_BUFFER_SIZE; i++) {
                h.wsMsg(delta(511 + i, "", ""));
            }
            assertEquals(BybitSyncContext.MAX_BUFFER_SIZE, h.bufferSize());
            assertEquals(1, h.requests());

            h.wsMsg(delta(511 + BybitSyncContext.MAX_BUFFER_SIZE, "", ""));

            assertEquals(2, h.requests());
            assertEquals(2, h.resyncs());
            assertEquals(0, h.bufferSize());
        }
    }

    @Nested
    @DisplayName("frame scanning")
    class Scanning {

        @Test
        @DisplayName("readU finds data.u from the end, after the levels, in either field order")
        void readU() {
            assertEquals(1628793, BybitSyncStrategy.readU(delta(1628793, levels(lvl("0.13558", "1803.1")), "")));
            assertEquals(77, BybitSyncStrategy.readU(spotFrame("delta", 77, "", "")));
            assertEquals(-1, BybitSyncStrategy.readU("{\"retCode\":0}"));
        }

        @Test
        @DisplayName("isSnapshotType reads type wherever it sits before data")
        void isSnapshotType() {
            assertTrue(BybitSyncStrategy.isSnapshotType(snapshot(1, "", "")));
            assertTrue(BybitSyncStrategy.isSnapshotType(spotFrame("snapshot", 1, "", "")));
            assertFalse(BybitSyncStrategy.isSnapshotType(delta(2, "", "")));
            assertFalse(BybitSyncStrategy.isSnapshotType(spotFrame("delta", 2, "", "")));
        }
    }

    @Nested
    @DisplayName("captured stream (BYBIT-0 spike fixtures)")
    class Capture {

        /**
         * Each capture is one {@code orderbook.1000.ARBUSDT} subscription: its snapshot, then every
         * delta through the matching REST snapshot's {@code u}, plus 20 more.
         */
        @ParameterizedTest
        @ValueSource(strings = {"linear", "spot"})
        @DisplayName("in-stream snapshot + deltas up to the REST snapshot's u equals that REST snapshot")
        void replayEqualsRestSnapshot(String category) {
            List<String> frames = frames(category);
            String restBody = BybitRestClientTest.fixture("rest-orderbook-" + category + "-ARBUSDT-1000.json");
            Harness expected = unfiltered(category);
            long restU = expected.strategy().applySnapshot(expected.slot, restBody);

            Harness h = unfiltered(category);
            for (String frame : frames) {
                if (BybitSyncStrategy.readU(frame) > restU) break;
                h.wsMsg(frame);
            }

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(restU, h.ctx().lastU);
            assertEquals(0, h.requests(), "no gap across the capture");
            assertEquals(quantities(expected.book().getBids()), quantities(h.book().getBids()));
            assertEquals(quantities(expected.book().getAsks()), quantities(h.book().getAsks()));
            assertTrue(h.book().getBids().size() <= BybitStreamProtocol.DEPTH, "the server keeps the window: no trim needed");
            assertTrue(h.book().getAsks().size() <= BybitStreamProtocol.DEPTH);
        }

        @ParameterizedTest
        @ValueSource(strings = {"linear", "spot"})
        @DisplayName("a mid-stream gap recovered from the REST snapshot ends on the same book as the uninterrupted stream")
        void gapRecoveredByRestStitch(String category) {
            List<String> frames = frames(category);
            String restBody = BybitRestClientTest.fixture("rest-orderbook-" + category + "-ARBUSDT-1000.json");
            Harness scratch = unfiltered(category);
            long restU = scratch.strategy().applySnapshot(scratch.slot, restBody);

            Harness reference = unfiltered(category);
            frames.forEach(reference::wsMsg);

            // Drop one delta well before the REST snapshot's u: the next one exposes the gap, and
            // every delta after it is buffered until the REST snapshot lands.
            int dropped = 50;
            Harness h = unfiltered(category);
            for (int i = 0; i < frames.size(); i++) {
                if (i == dropped) continue;
                h.wsMsg(frames.get(i));
                if (BybitSyncStrategy.readU(frames.get(i)) == restU + 5) {
                    assertEquals(OrderBookState.RECOVERING, h.book().getState());
                    h.restMsg(restBody);
                    assertEquals(OrderBookState.SYNCED, h.book().getState(), "stitched on u");
                }
            }

            assertEquals(OrderBookState.SYNCED, h.book().getState());
            assertEquals(1, h.requests());
            assertEquals(1, h.resyncs());
            assertEquals(reference.ctx().lastU, h.ctx().lastU);
            assertEquals(quantities(reference.book().getBids()), quantities(h.book().getBids()));
            assertEquals(quantities(reference.book().getAsks()), quantities(h.book().getAsks()));
        }

        /** No price filter: the comparison is on the full 1000-level book. */
        private Harness unfiltered(String category) {
            return new Harness(category.equals("spot") ? Venue.BYBIT_SPOT : Venue.BYBIT_FUTURES, Double.POSITIVE_INFINITY);
        }

        private List<String> frames(String category) {
            List<String> frames = Arrays.stream(BybitRestClientTest.fixture("ws-" + category + "-ARBUSDT-1000.jsonl")
                    .split("\\R")).filter(line -> !line.isBlank()).toList();
            assertTrue(BybitSyncStrategy.isSnapshotType(frames.getFirst()), "a capture starts with its snapshot");
            return frames;
        }
    }
}
