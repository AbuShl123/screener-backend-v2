package dev.abu.screener_backend.marketdata.adapter.mexc;

import ch.randelshofer.fastdoubleparser.JavaDoubleParser;
import dev.abu.screener_backend.marketdata.core.book.OrderBook;

import java.nio.ByteBuffer;

import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.WIRE_LEN;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.fieldNumber;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.lengthDelimited;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.malformed;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.next;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.range;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.readVarint32;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.skipScalar;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.wireType;

/**
 * Hand-written Protobuf wire reader for MEXC spot's {@code aggre.depth} push frames. Static and
 * allocation-free: no generated classes, no {@code String} per price (plan decision 2). The wire
 * primitives live in {@link MexcProtobufWire}, shared with {@link MexcSpotDealsDecoder}.
 *
 * <h3>What a frame looks like</h3>
 * Measured on every push of the Phase 0 runs ({@code external-docs/mexc/mexc-spot-depth-empirical.md} §1):
 * <pre>
 * PushDataV3ApiWrapper
 *   1    channel            string  "spot@public.aggre.depth.v3.api.pb@100ms@BTCUSDT"
 *   3    symbol             string  "BTCUSDT"
 *   6    sendTime           varint
 *   313  publicAggreDepths  message  ← the body
 *          1  asks                 repeated item  (all asks, then all bids; an empty side is absent)
 *          2  bids                 repeated item
 *          3  eventType            string
 *          4  fromVersion          string, ASCII digits
 *          5  toVersion            string, ASCII digits
 *          6  lastOrderCreateTime  varint
 *        item: 1 price, 2 quantity — both strings; quantity "0" deletes the level
 * </pre>
 * The versions come <i>after</i> the levels, so the strategy reads them first with a walk that
 * skips each level item by its length prefix, then walks the body again to apply the levels. Every
 * walk matches fields by number, not position, and skips unknown fields by wire type.
 *
 * <h3>Indexes</h3>
 * Every index is absolute, between the frame's {@code position()} and {@code limit()}, and no
 * method moves either: the reader thread routes the same buffer the shard thread later parses
 * (plan decision 5). Ranges are packed into a {@code long} as {@code (start << 32) | end}, end
 * exclusive; read them back with {@link #start} and {@link #end}.
 *
 * <h3>Failure</h3>
 * The format is external and partly undocumented, so drift must be loud: a malformed varint, a
 * field running past its enclosing message, a missing body or version, or a level without a price
 * or quantity throws {@link IllegalStateException}. The sync strategy turns that into a resync.
 */
final class MexcSpotFrameReader {

    // PushDataV3ApiWrapper
    static final int WRAPPER_CHANNEL = 1;
    static final int WRAPPER_SYMBOL = 3;
    static final int WRAPPER_AGGRE_DEPTHS = 313;

    // PublicAggreDepthsV3Api
    static final int DEPTHS_ASKS = 1;
    static final int DEPTHS_BIDS = 2;
    static final int DEPTHS_FROM_VERSION = 4;
    static final int DEPTHS_TO_VERSION = 5;

    // PublicAggreDepthV3ApiItem
    static final int ITEM_PRICE = 1;
    static final int ITEM_QUANTITY = 2;

    /** Returned by {@link #symbolRange} when the frame carries no routing key. */
    static final long NO_RANGE = MexcProtobufWire.NO_RANGE;

    /** 18 digits always fit a {@code long}; a version longer than that is not a version. */
    private static final int MAX_VERSION_DIGITS = 18;

    private MexcSpotFrameReader() {
    }

    // --- Routing (reader thread) ---------------------------------------------------------------

    /**
     * The byte range of the frame's routing key, for {@code SubscriptionIndex.resolve}: the
     * wrapper's {@code symbol}, or failing that the suffix of {@code channel} after its last
     * {@code @}. {@code symbol} precedes the body on the wire, so the walk normally stops before
     * reaching the levels.
     *
     * @return the packed range, or {@link #NO_RANGE} if the frame has neither field
     * @throws IllegalStateException if the wrapper is malformed
     */
    static long symbolRange(ByteBuffer frame) {
        int pos = frame.position();
        int limit = frame.limit();
        long channel = NO_RANGE;

        while (pos < limit) {
            long tag = readVarint32(frame, pos, limit);
            pos = next(tag);
            int field = fieldNumber(tag);
            int wireType = wireType(tag);

            if (wireType != WIRE_LEN) {
                pos = skipScalar(frame, wireType, pos, limit);
                continue;
            }
            long value = lengthDelimited(frame, pos, limit);
            if (field == WRAPPER_SYMBOL) {
                return value;
            }
            if (field == WRAPPER_CHANNEL) {
                channel = value;
            }
            pos = end(value);
        }

        if (channel == NO_RANGE) {
            return NO_RANGE;
        }
        for (int i = end(channel) - 1; i >= start(channel); i--) {
            if (frame.get(i) == '@') {
                return range(i + 1, end(channel));
            }
        }
        return NO_RANGE;
    }

    // --- Parsing (shard thread) ----------------------------------------------------------------

    /**
     * The byte range of the {@code publicAggreDepths} body.
     *
     * @throws IllegalStateException if the frame has no field 313, or is malformed
     */
    static long bodyRange(ByteBuffer frame) {
        int pos = frame.position();
        int limit = frame.limit();

        while (pos < limit) {
            long tag = readVarint32(frame, pos, limit);
            pos = next(tag);
            int field = fieldNumber(tag);
            int wireType = wireType(tag);

            if (wireType != WIRE_LEN) {
                pos = skipScalar(frame, wireType, pos, limit);
                continue;
            }
            long value = lengthDelimited(frame, pos, limit);
            if (field == WRAPPER_AGGRE_DEPTHS) {
                return value;
            }
            pos = end(value);
        }
        throw malformed("publicAggreDepths (" + WRAPPER_AGGRE_DEPTHS + ") not found");
    }

    /** The body's {@code fromVersion}. @throws IllegalStateException if absent or not digits */
    static long fromVersion(ByteBuffer frame, long body) {
        return readVersion(frame, body, DEPTHS_FROM_VERSION);
    }

    /** The body's {@code toVersion}. @throws IllegalStateException if absent or not digits */
    static long toVersion(ByteBuffer frame, long body) {
        return readVersion(frame, body, DEPTHS_TO_VERSION);
    }

    /**
     * Applies every {@code asks} and {@code bids} item of the body to the book, in wire order.
     * Prices and quantities are parsed straight from the backing array with
     * {@code JavaDoubleParser}, so trimmed push numbers ({@code 82990.7}) and padded snapshot
     * numbers ({@code 82990.70}) land on the same {@code double} key.
     *
     * @throws IllegalArgumentException if the frame is a direct buffer — java-websocket hands out
     *         heap buffers, and the parser needs the array
     * @throws IllegalStateException on a malformed item; levels before it are already applied, so
     *         the caller must resync
     */
    static void applyLevels(ByteBuffer frame, long body, OrderBook book, long millis) {
        if (!frame.hasArray()) {
            throw new IllegalArgumentException("MEXC spot frame: direct buffers are not supported");
        }
        byte[] array = frame.array();
        int arrayOffset = frame.arrayOffset();
        int pos = start(body);
        int limit = end(body);

        while (pos < limit) {
            long tag = readVarint32(frame, pos, limit);
            pos = next(tag);
            int field = fieldNumber(tag);
            int wireType = wireType(tag);

            if (wireType != WIRE_LEN) {
                pos = skipScalar(frame, wireType, pos, limit);
                continue;
            }
            long value = lengthDelimited(frame, pos, limit);
            if (field == DEPTHS_ASKS) {
                applyLevel(frame, array, arrayOffset, value, false, book, millis);
            } else if (field == DEPTHS_BIDS) {
                applyLevel(frame, array, arrayOffset, value, true, book, millis);
            }
            pos = end(value);
        }
    }

    private static void applyLevel(ByteBuffer frame, byte[] array, int arrayOffset, long item,
                                   boolean isBid, OrderBook book, long millis) {
        long price = NO_RANGE;
        long quantity = NO_RANGE;
        int pos = start(item);
        int limit = end(item);

        while (pos < limit) {
            long tag = readVarint32(frame, pos, limit);
            pos = next(tag);
            int field = fieldNumber(tag);
            int wireType = wireType(tag);

            if (wireType != WIRE_LEN) {
                pos = skipScalar(frame, wireType, pos, limit);
                continue;
            }
            long value = lengthDelimited(frame, pos, limit);
            if (field == ITEM_PRICE) {
                price = value;
            } else if (field == ITEM_QUANTITY) {
                quantity = value;
            }
            pos = end(value);
        }

        if (price == NO_RANGE || quantity == NO_RANGE) {
            throw malformed("depth item without " + (price == NO_RANGE ? "price" : "quantity"));
        }
        book.applyLevel(isBid, parseDouble(array, arrayOffset, price), parseDouble(array, arrayOffset, quantity), millis);
    }

    private static double parseDouble(byte[] array, int arrayOffset, long range) {
        return JavaDoubleParser.parseDouble(array, arrayOffset + start(range), end(range) - start(range));
    }

    /** Walks the body for one string-encoded version field and parses its ASCII digits by hand. */
    private static long readVersion(ByteBuffer frame, long body, int versionField) {
        int pos = start(body);
        int limit = end(body);

        while (pos < limit) {
            long tag = readVarint32(frame, pos, limit);
            pos = next(tag);
            int field = fieldNumber(tag);
            int wireType = wireType(tag);

            if (wireType != WIRE_LEN) {
                pos = skipScalar(frame, wireType, pos, limit);
                continue;
            }
            long value = lengthDelimited(frame, pos, limit);
            if (field == versionField) {
                return parseDigits(frame, value, versionField);
            }
            pos = end(value);
        }
        throw malformed("version field " + versionField + " not found");
    }

    private static long parseDigits(ByteBuffer frame, long range, int versionField) {
        int from = start(range);
        int to = end(range);
        if (from == to || to - from > MAX_VERSION_DIGITS) {
            throw malformed("version field " + versionField + " has " + (to - from) + " digits");
        }
        long value = 0;
        for (int i = from; i < to; i++) {
            int digit = frame.get(i) - '0';
            if (digit < 0 || digit > 9) {
                throw malformed("version field " + versionField + " is not an integer");
            }
            value = value * 10 + digit;
        }
        return value;
    }

    // --- Packed ranges (MexcProtobufWire) ------------------------------------------------------

    static int start(long range) {
        return MexcProtobufWire.start(range);
    }

    static int end(long range) {
        return MexcProtobufWire.end(range);
    }
}
