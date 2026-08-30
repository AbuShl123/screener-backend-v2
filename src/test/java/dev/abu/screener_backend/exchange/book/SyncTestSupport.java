package dev.abu.screener_backend.exchange.book;

import dev.abu.screener_backend.exchange.Instrument;
import dev.abu.screener_backend.exchange.Venue;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.StringJoiner;

/**
 * Fixtures for driving a real {@link OrderBook} through the Binance sync algorithm.
 *
 * <p>These tests are <b>characterization</b> tests written against the pre-SPI code, per
 * {@code .claude/plans/p2-step2-sync-spi.md} §8.4: they pin what the algorithm does today so the
 * same assertions can be ported onto {@code DepthSyncStrategy} in commit C, where only the driving
 * changes. That is the parity evidence — nothing else in the plan provides it.
 *
 * <p>JSON is built as strings on purpose. The point is to exercise the real {@code JsonParser} +
 * {@code JavaDoubleParser} path, including the field ordering the parser relies on: Binance
 * guarantees {@code U}/{@code u}/{@code pu} precede {@code b}/{@code a}, and
 * {@link OrderBook#applyLiveDiff} validates the sequence on first sight of {@code b} or {@code a}.
 */
final class SyncTestSupport {

    /** Matches {@code screener.orderbook.price-filter-threshold} — a fraction, not a percentage. */
    static final double FILTER = 0.1;

    private SyncTestSupport() {
    }

    // --- Harness -------------------------------------------------------------------------------

    /**
     * Replicates {@code OrderBookProcessor.process} around one book, with a fake recovery sink in
     * place of {@code SnapshotFetchQueue}.
     *
     * <p>The handshake is the part that matters and the part commit C must preserve: the queue may
     * <b>refuse</b> at capacity, and on refusal the book must stay {@code PENDING} and drop the
     * diff rather than start buffering against a snapshot nobody is fetching.
     */
    static final class Harness {

        final OrderBook book;

        /** Fake sink verdict — {@code false} models a snapshot queue at capacity. */
        boolean sinkAccepts = true;

        /** Counts enqueue attempts, i.e. calls that would reach {@code SnapshotFetchQueue}. */
        int recoveryRequests = 0;

        Harness(Venue venue, double filterThreshold) {
            this.book = new OrderBook(
                    Instrument.of(1, venue, "BTCUSDT", "BTC", "USDT"), filterThreshold);
        }

        /** Feed a stream frame — the {@code WS_MSG} lane. */
        void wsMsg(String rawJson) {
            dispatch(book.onDiff(rawJson), rawJson);
        }

        /** Feed a REST snapshot body — the {@code REST_MSG} lane. */
        void restMsg(String rawJson) {
            dispatch(book.applySnapshot(rawJson), rawJson);
        }

        private void dispatch(OrderBookResult result, String rawJson) {
            if (result != OrderBookResult.NEEDS_SNAPSHOT && result != OrderBookResult.NEEDS_RESYNC) {
                return;
            }
            recoveryRequests++;
            if (!sinkAccepts) {
                return;
            }
            book.markSnapshotRequested();
            if (result == OrderBookResult.NEEDS_SNAPSHOT) {
                // The processor re-feeds the triggering diff so it lands in the buffer.
                book.onDiff(rawJson);
            }
        }
    }

    /**
     * Drives a fresh book to {@link OrderBookState#SYNCED} holding exactly the given snapshot
     * levels, leaving {@code lastUpdateId = 120}. Live diffs must therefore continue from
     * {@code U=121 / pu=120}.
     */
    static Harness synced(Venue venue, String snapshotBids, String snapshotAsks) {
        return synced(venue, FILTER, snapshotBids, snapshotAsks);
    }

    static Harness synced(Venue venue, double filterThreshold, String snapshotBids, String snapshotAsks) {
        Harness h = new Harness(venue, filterThreshold);
        h.wsMsg(diff(venue, 100, 110, 99, "", ""));    // PENDING → buffered as the first event
        h.wsMsg(diff(venue, 111, 120, 110, "", ""));   // buffered, drained after the snapshot
        h.restMsg(snapshot(105, snapshotBids, snapshotAsks));
        return h;
    }

    // --- JSON builders -------------------------------------------------------------------------

    /**
     * A {@code depthUpdate} frame. {@code pu} is emitted only for futures — Binance spot frames do
     * not carry it, and the spot sequence rule must not depend on it.
     */
    static String diff(Venue venue, long firstUpdateId, long lastUpdateId, long prevUpdateId,
                       String bids, String asks) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"e\":\"depthUpdate\",\"E\":1700000000000,\"s\":\"BTCUSDT\"");
        sb.append(",\"U\":").append(firstUpdateId);
        sb.append(",\"u\":").append(lastUpdateId);
        if (venue == Venue.BINANCE_FUTURES) {
            sb.append(",\"pu\":").append(prevUpdateId);
        }
        sb.append(",\"b\":[").append(bids).append("]");
        sb.append(",\"a\":[").append(asks).append("]}");
        return sb.toString();
    }

    /** A REST depth snapshot body. */
    static String snapshot(long lastUpdateId, String bids, String asks) {
        return "{\"lastUpdateId\":" + lastUpdateId
                + ",\"bids\":[" + bids + "]"
                + ",\"asks\":[" + asks + "]}";
    }

    /** One {@code ["price","qty"]} pair — quoted, as Binance sends them. */
    static String lvl(double price, double qty) {
        return "[\"" + price + "\",\"" + qty + "\"]";
    }

    static String levels(String... levels) {
        StringJoiner joiner = new StringJoiner(",");
        for (String level : levels) {
            joiner.add(level);
        }
        return joiner.toString();
    }

    // --- Inspection ----------------------------------------------------------------------------

    /**
     * Reads the private diff buffer. Several cases assert the buffer was cleared or that exactly
     * one diff was retained, and there is no accessor for it — the buffer is precisely the state
     * commit C moves off {@code OrderBook} and into {@code BinanceSyncContext}, where the same
     * assertions become direct field reads.
     */
    static int bufferSize(OrderBook book) {
        try {
            Field field = OrderBook.class.getDeclaredField("diffBuffer");
            field.setAccessible(true);
            return ((ArrayDeque<?>) field.get(book)).size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("OrderBook.diffBuffer moved — update SyncTestSupport", e);
        }
    }
}
