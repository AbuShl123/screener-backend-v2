package dev.abu.screener_backend.marketdata.adapter.mexc;

import java.nio.ByteBuffer;

/**
 * Protobuf wire primitives shared by MEXC spot's hand-written frame readers: depth
 * ({@link MexcSpotFrameReader}) and deals ({@link MexcSpotDealsDecoder}). Static and
 * allocation-free.
 *
 * <h3>Indexes</h3>
 * Every index is absolute, and no method moves the buffer's {@code position()} or {@code limit()}:
 * the reader thread routes the same buffer the shard thread later parses. Two values that must come
 * back together are packed into one {@code long}, {@code (hi << 32) | lo}:
 * <ul>
 *   <li>a <b>range</b> is {@code (start << 32) | end}, end exclusive; read it back with
 *       {@link #start} and {@link #end};</li>
 *   <li>a <b>tag</b> from {@link #readVarint32} is {@code (key << 32) | next}; take the field with
 *       {@link #fieldNumber}, the wire type with {@link #wireType} and the index after it with
 *       {@link #next}.</li>
 * </ul>
 *
 * <h3>Failure</h3>
 * The format is external and partly undocumented, so drift must be loud: a malformed varint, a field
 * running past its enclosing message or an unknown wire type throws {@link IllegalStateException}.
 */
final class MexcProtobufWire {

    static final int WIRE_VARINT = 0;
    static final int WIRE_I64 = 1;
    static final int WIRE_LEN = 2;
    static final int WIRE_I32 = 5;

    /** A range that does not exist. No real range is negative. */
    static final long NO_RANGE = -1;

    private MexcProtobufWire() {
    }

    /**
     * Reads a varint that must fit a non-negative {@code int}: a tag or a length.
     *
     * @return {@code (value << 32) | next}, {@code next} being the index after the varint
     */
    static long readVarint32(ByteBuffer buf, int pos, int limit) {
        long value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (pos >= limit) {
                throw malformed("truncated varint");
            }
            byte b = buf.get(pos++);
            value |= (long) (b & 0x7F) << shift;
            if (b >= 0) {
                if (value > Integer.MAX_VALUE) {
                    throw malformed("varint " + value + " exceeds int");
                }
                return (value << 32) | pos;
            }
        }
        throw malformed("varint longer than 5 bytes");
    }

    /**
     * Reads a 64-bit varint at {@code pos}: a timestamp or an id. A value at or above 2^63 comes back
     * negative; callers that need a non-negative value check it. Get the index after it with
     * {@link #skipVarint64}.
     */
    static long readVarint64(ByteBuffer buf, int pos, int limit) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos >= limit) {
                throw malformed("truncated varint");
            }
            byte b = buf.get(pos++);
            value |= (long) (b & 0x7F) << shift;
            if (b >= 0) {
                return value;
            }
        }
        throw malformed("varint longer than 10 bytes");
    }

    /** Skips a varint of up to 64 bits, returning the index after it. */
    static int skipVarint64(ByteBuffer buf, int pos, int limit) {
        for (int i = 0; i < 10; i++) {
            if (pos >= limit) {
                throw malformed("truncated varint");
            }
            if (buf.get(pos++) >= 0) {
                return pos;
            }
        }
        throw malformed("varint longer than 10 bytes");
    }

    /** Reads a length prefix at {@code pos}, checks the field fits its enclosing message, and returns its range. */
    static long lengthDelimited(ByteBuffer buf, int pos, int limit) {
        long length = readVarint32(buf, pos, limit);
        int start = lo(length);
        if (hi(length) > limit - start) {
            throw malformed("field of " + hi(length) + " bytes runs past its message");
        }
        return range(start, start + hi(length));
    }

    /** Skips a field that is not length-delimited, returning the index after it. */
    static int skipScalar(ByteBuffer buf, int wireType, int pos, int limit) {
        int next = switch (wireType) {
            case WIRE_VARINT -> skipVarint64(buf, pos, limit);
            case WIRE_I64 -> pos + 8;
            case WIRE_I32 -> pos + 4;
            default -> throw malformed("unsupported wire type " + wireType);
        };
        if (next > limit) {
            throw malformed("fixed-width field runs past its message");
        }
        return next;
    }

    // --- Tags ----------------------------------------------------------------------------------

    /** The field number of a tag read by {@link #readVarint32}. */
    static int fieldNumber(long tag) {
        int field = hi(tag) >>> 3;
        if (field == 0) {
            throw malformed("field number 0");
        }
        return field;
    }

    /** The wire type of a tag read by {@link #readVarint32}. */
    static int wireType(long tag) {
        return hi(tag) & 7;
    }

    /** The index after a varint read by {@link #readVarint32}. */
    static int next(long varint) {
        return lo(varint);
    }

    // --- Packed ranges -------------------------------------------------------------------------

    static int start(long range) {
        return hi(range);
    }

    static int end(long range) {
        return lo(range);
    }

    static long range(int start, int end) {
        return ((long) start << 32) | end;
    }

    private static int hi(long packed) {
        return (int) (packed >>> 32);
    }

    private static int lo(long packed) {
        return (int) packed;
    }

    static IllegalStateException malformed(String what) {
        return new IllegalStateException("MEXC spot frame: " + what);
    }
}
