package dev.abu.screener_backend.marketdata.adapter.mexc;

import ch.randelshofer.fastdoubleparser.JavaDoubleParser;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeDecoder;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;

import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.NO_RANGE;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.WIRE_LEN;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.WIRE_VARINT;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.end;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.fieldNumber;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.lengthDelimited;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.malformed;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.next;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.readVarint32;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.readVarint64;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.skipScalar;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.skipVarint64;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.start;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcProtobufWire.wireType;

/**
 * Decodes MEXC spot {@code aggre.deals} push frames (binary Protobuf), with the same hand-written,
 * allocation-free wire reading as depth ({@link MexcProtobufWire}). Measured contract:
 * {@code external-docs/mexc/mexc-trades-empirical.md} §1.
 *
 * <pre>
 * PushDataV3ApiWrapper
 *   1    channel           string  "spot@public.aggre.deals.v3.api.pb@100ms@XRPUSDT"
 *   3    symbol            string
 *   6    sendTime          varint
 *   314  publicAggreDeals  message  ← the body
 *          1  deals      repeated item, in time order
 *          2  eventType  string
 *        item: 1 price (string), 2 quantity (string, base), 3 tradeType (1 buy, 2 sell: the
 *              aggressor), 4 time (ms), 5 tradeId (string), 6 trade sequence (varint, real fills only)
 * </pre>
 *
 * <h3>{@code tradeId}</h3>
 * {@code <A>X<n>_<B>X<m>}: {@code A} is the taker order's id and {@code n} its fill number,
 * {@code B} the resting order's id and {@code m} its fill number. It is split in place, without a
 * {@code String}.
 * <ul>
 *   <li>A deal with {@code n} and {@code m} both {@code 0} is an <b>{@code X0_X0} deal</b>: a
 *       synthetic paired order on a per-symbol 500 ms clock, not taker flow (85% of spot deals, 93.5%
 *       of spot notional). It goes to {@link TradeSink#onFiltered} and is never judged.</li>
 *   <li>Every other deal is a real fill. {@code A} becomes the taker-order id, which groups an
 *       order's fills exactly, and field 6 becomes both trade ids (the alert's identity). A real fill
 *       without field 6 fails the frame.</li>
 * </ul>
 *
 * <h3>Validation</h3>
 * The frame is walked twice: once to validate every deal, once to report them. A frame that fails
 * anywhere reports nothing and returns {@code false}. The frames are small (one deal typical, at
 * most a few hundred), so the second walk is cheap.
 *
 * <p>One deal is one fill against one resting order, and an order's fills always share one 100 ms
 * frame, so {@link #reportsMakerFills} and {@link #closesSweepAtFrameEnd} are {@code true}.
 *
 * <p>Stateless. Binary only: a text frame is refused.
 */
@Slf4j
public final class MexcSpotDealsDecoder implements TradeDecoder {

    // PushDataV3ApiWrapper
    static final int WRAPPER_AGGRE_DEALS = 314;

    // PublicAggreDealsV3Api
    static final int DEALS_ITEM = 1;

    // PublicAggreDealsV3ApiItem
    static final int DEAL_PRICE = 1;
    static final int DEAL_QUANTITY = 2;
    static final int DEAL_TRADE_TYPE = 3;
    static final int DEAL_TIME = 4;
    static final int DEAL_TRADE_ID = 5;
    static final int DEAL_SEQUENCE = 6;

    private static final int TRADE_TYPE_BUY = 1;
    private static final int TRADE_TYPE_SELL = 2;

    /** {@link #digitsUntil} terminator meaning "the end of the field": no byte equals it. */
    private static final int FIELD_END = Integer.MIN_VALUE;

    @Override
    public boolean decode(InstrumentSlot slot, String rawJson, TradeSink sink) {
        return false;
    }

    @Override
    public boolean decode(InstrumentSlot slot, ByteBuffer frame, TradeSink sink) {
        if (!frame.hasArray()) {
            log.debug("[{}] MEXC spot deals frame: direct buffers are not supported", slot.instrument().logName());
            return false;
        }
        long body;
        try {
            body = bodyRange(frame);
            walk(frame, body, slot, null);
        } catch (IllegalStateException | NumberFormatException e) {
            log.debug("[{}] Rejected MEXC spot deals frame: {}", slot.instrument().logName(), e.getMessage());
            return false;
        }
        walk(frame, body, slot, sink);
        return true;
    }

    @Override
    public boolean reportsMakerFills() {
        return true;
    }

    @Override
    public boolean closesSweepAtFrameEnd() {
        return true;
    }

    /** The byte range of the {@code publicAggreDeals} body. */
    private static long bodyRange(ByteBuffer frame) {
        int pos = frame.position();
        int limit = frame.limit();

        while (pos < limit) {
            long tag = readVarint32(frame, pos, limit);
            pos = next(tag);
            int wireType = wireType(tag);
            if (wireType != WIRE_LEN) {
                pos = skipScalar(frame, wireType, pos, limit);
                continue;
            }
            long value = lengthDelimited(frame, pos, limit);
            if (fieldNumber(tag) == WRAPPER_AGGRE_DEALS) {
                return value;
            }
            pos = end(value);
        }
        throw malformed("publicAggreDeals (" + WRAPPER_AGGRE_DEALS + ") not found");
    }

    /** Walks every deal of the body in wire order: validates only when {@code sink} is {@code null}. */
    private static void walk(ByteBuffer frame, long body, InstrumentSlot slot, TradeSink sink) {
        int pos = start(body);
        int limit = end(body);

        while (pos < limit) {
            long tag = readVarint32(frame, pos, limit);
            pos = next(tag);
            int wireType = wireType(tag);
            if (wireType != WIRE_LEN) {
                pos = skipScalar(frame, wireType, pos, limit);
                continue;
            }
            long value = lengthDelimited(frame, pos, limit);
            if (fieldNumber(tag) == DEALS_ITEM) {
                deal(frame, value, slot, sink);
            }
            pos = end(value);
        }
    }

    private static void deal(ByteBuffer frame, long item, InstrumentSlot slot, TradeSink sink) {
        long price = NO_RANGE;
        long quantity = NO_RANGE;
        long tradeId = NO_RANGE;
        long tradeType = -1;
        long time = -1;
        long sequence = -1;
        int pos = start(item);
        int limit = end(item);

        while (pos < limit) {
            long tag = readVarint32(frame, pos, limit);
            pos = next(tag);
            int field = fieldNumber(tag);
            int wireType = wireType(tag);

            if (wireType == WIRE_VARINT) {
                switch (field) {
                    case DEAL_TRADE_TYPE -> tradeType = readVarint64(frame, pos, limit);
                    case DEAL_TIME -> time = readVarint64(frame, pos, limit);
                    case DEAL_SEQUENCE -> sequence = readVarint64(frame, pos, limit);
                    default -> { }
                }
                pos = skipVarint64(frame, pos, limit);
            } else if (wireType == WIRE_LEN) {
                long value = lengthDelimited(frame, pos, limit);
                switch (field) {
                    case DEAL_PRICE -> price = value;
                    case DEAL_QUANTITY -> quantity = value;
                    case DEAL_TRADE_ID -> tradeId = value;
                    default -> { }
                }
                pos = end(value);
            } else {
                pos = skipScalar(frame, wireType, pos, limit);
            }
        }

        if (price == NO_RANGE || quantity == NO_RANGE || tradeId == NO_RANGE) {
            throw malformed("deal without " + (price == NO_RANGE ? "price" : quantity == NO_RANGE ? "quantity" : "tradeId"));
        }
        if (tradeType != TRADE_TYPE_BUY && tradeType != TRADE_TYPE_SELL) {
            throw malformed("deal with tradeType " + tradeType);
        }
        if (time <= 0) {
            throw malformed("deal without time");
        }

        // tradeId: <A>X<n>_<B>X<m>
        int from = start(tradeId);
        int to = end(tradeId);
        int takerX = digitsUntil(frame, from, to, 'X');
        int underscore = digitsUntil(frame, takerX + 1, to, '_');
        int makerX = digitsUntil(frame, underscore + 1, to, 'X');
        int makerFillEnd = digitsUntil(frame, makerX + 1, to, FIELD_END);
        long takerOrderId = parseDigits(frame, from, takerX);
        boolean synthetic = parseDigits(frame, takerX + 1, underscore) == 0
                && parseDigits(frame, makerX + 1, makerFillEnd) == 0;

        if (synthetic) {
            if (sink != null) sink.onFiltered(slot);
            return;
        }
        if (sequence < 0) {
            throw malformed("real fill without trade sequence (field " + DEAL_SEQUENCE + ")");
        }
        double p = parseDouble(frame, price);
        double q = parseDouble(frame, quantity);
        if (!positiveFinite(p) || !positiveFinite(q)) {
            throw malformed("deal with price " + p + ", quantity " + q);
        }
        if (sink != null) {
            sink.onTrade(slot, p, q, tradeType == TRADE_TYPE_BUY, time, sequence, sequence, takerOrderId);
        }
    }

    /**
     * The index of {@code terminator} at or after {@code from}, checking that everything before it
     * is at least one ASCII digit. {@link #FIELD_END} means the end of the field ({@code to}).
     */
    private static int digitsUntil(ByteBuffer frame, int from, int to, int terminator) {
        int i = from;
        while (i < to && frame.get(i) != terminator) {
            int digit = frame.get(i) - '0';
            if (digit < 0 || digit > 9) {
                throw malformed("tradeId is not <A>X<n>_<B>X<m>");
            }
            i++;
        }
        if (i == from || (terminator != FIELD_END && i == to)) {
            throw malformed("tradeId is not <A>X<n>_<B>X<m>");
        }
        return i;
    }

    /** Parses ASCII digits already checked by {@link #digitsUntil}; a value past {@code long} is malformed. */
    private static long parseDigits(ByteBuffer frame, int from, int to) {
        long value = 0;
        for (int i = from; i < to; i++) {
            int digit = frame.get(i) - '0';
            if (value > (Long.MAX_VALUE - digit) / 10) {
                throw malformed("tradeId part exceeds long");
            }
            value = value * 10 + digit;
        }
        return value;
    }

    private static double parseDouble(ByteBuffer frame, long range) {
        return JavaDoubleParser.parseDouble(frame.array(), frame.arrayOffset() + start(range), end(range) - start(range));
    }

    /** Also rejects {@code NaN}, which fastdoubleparser accepts as text. */
    private static boolean positiveFinite(double v) {
        return v > 0 && v < Double.POSITIVE_INFINITY;
    }
}
