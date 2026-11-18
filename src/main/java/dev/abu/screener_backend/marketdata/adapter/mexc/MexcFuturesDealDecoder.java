package dev.abu.screener_backend.marketdata.adapter.mexc;

import ch.randelshofer.fastdoubleparser.JavaDoubleParser;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeDecoder;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

import java.util.Arrays;

/**
 * Decodes MEXC futures {@code push.deal} frames (JSON). Measured contract:
 * {@code external-docs/mexc/mexc-trades-empirical.md} §2.
 *
 * <pre>
 * {"symbol":"BTC_USDT","data":[{"p":64670.8,"v":1,"T":1,"O":1,"M":2,"t":1794983337848,"i":"17067813085","cts":"1794983337848"},
 *                              {"p":64670.8,"v":1,"T":1,"O":1,"M":2,"t":1794983337847,"i":"17067813084",…}, …],
 *  "channel":"push.deal","ts":1794983337848}
 * </pre>
 *
 * <ul>
 *   <li>{@code data} is an array, <b>newest deal first</b>. The deals are collected into a small
 *       per-thread buffer and reported in reverse, oldest first. A frame usually carries one deal
 *       and at most 50; the buffer grows on demand and is reused, so steady state allocates
 *       nothing.</li>
 *   <li>{@code v} is a contract count: the base quantity is {@code v × contractSize}
 *       ({@code instrument.quantityMultiplier()}), as on futures depth.</li>
 *   <li>{@code T}: 1 buy, 2 sell, the aggressor side. {@code t}: trade time, ms. {@code i}: the
 *       trade id, a numeric string from a sequence shared across contracts, parsed in place.</li>
 *   <li>{@code M = 1} is a <b>self-trade</b> (one account on both sides): 72% of futures deals. It
 *       goes to {@link TradeSink#onFiltered} and is never judged. {@code M = 2} is reported.</li>
 *   <li>{@code p}, {@code v}, {@code T}, {@code t}, {@code i} and {@code M} are required in every
 *       deal; {@code O}, {@code cts} and unknown fields are skipped. A frame with a missing field,
 *       an out-of-range value or bad JSON reports nothing and returns {@code false}.</li>
 * </ul>
 *
 * <p>MEXC futures publishes no taker-order id: {@code takerOrderId} is {@code -1}, and core groups
 * fills by side, time, consecutive ids and price. One deal is one fill against one resting order, so
 * {@link #reportsMakerFills} is {@code true}. A sweep's fills are pushed as they match and often span
 * frames, so a sweep cannot close at frame end.
 *
 * <p>Stateless apart from the per-thread scratch buffer.
 */
@Slf4j
public final class MexcFuturesDealDecoder implements TradeDecoder {

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    private static final int PRICE = 1;
    private static final int VOL = 1 << 1;
    private static final int SIDE = 1 << 2;
    private static final int TIME = 1 << 3;
    private static final int ID = 1 << 4;
    private static final int SELF_TRADE = 1 << 5;
    private static final int REQUIRED = PRICE | VOL | SIDE | TIME | ID | SELF_TRADE;

    /** 18 digits always fit a {@code long}; {@code i} is 11 digits today. */
    private static final int MAX_ID_DIGITS = 18;

    /** One per shard thread, read once per frame. */
    private static final ThreadLocal<Deals> SCRATCH = ThreadLocal.withInitial(Deals::new);

    @Override
    public boolean decode(InstrumentSlot slot, String rawJson, TradeSink sink) {
        Deals deals = SCRATCH.get();
        deals.size = 0;
        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return reject(slot, rawJson);
            boolean sawData = false;
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if (field.equals("data")) {
                    if (value != JsonToken.START_ARRAY || !readDeals(p, deals)) return reject(slot, rawJson);
                    sawData = true;
                } else {
                    p.skipChildren();
                }
            }
            if (!sawData) return reject(slot, rawJson);
        } catch (Exception e) {
            log.debug("[{}] Failed to parse MEXC futures deal frame: {}", slot.instrument().logName(), e.getMessage());
            return false;
        }

        double multiplier = slot.instrument().quantityMultiplier();
        for (int k = deals.size - 1; k >= 0; k--) {
            if (deals.selfTrade[k]) {
                sink.onFiltered(slot);
            } else {
                sink.onTrade(slot, deals.price[k], deals.vol[k] * multiplier, deals.buy[k], deals.time[k],
                        deals.id[k], deals.id[k], -1);
            }
        }
        return true;
    }

    @Override
    public boolean reportsMakerFills() {
        return true;
    }

    /** Walks {@code data}, with the parser on its {@code START_ARRAY}. {@code false} on a bad deal. */
    private static boolean readDeals(JsonParser p, Deals deals) {
        while (p.nextToken() != JsonToken.END_ARRAY) {
            if (p.currentToken() != JsonToken.START_OBJECT) return false;
            double price = 0;
            double vol = 0;
            int side = 0;
            long time = 0;
            long id = -1;
            int selfTrade = 0;
            int seen = 0;
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "p" -> { price = parseNumber(p); seen |= PRICE; }
                    case "v" -> { vol = parseNumber(p); seen |= VOL; }
                    case "T" -> { side = p.getIntValue(); seen |= SIDE; }
                    case "t" -> { time = p.getLongValue(); seen |= TIME; }
                    case "i" -> { id = parseId(p); seen |= ID; }
                    case "M" -> { selfTrade = p.getIntValue(); seen |= SELF_TRADE; }
                    default -> p.skipChildren();
                }
            }
            if (seen != REQUIRED || !positiveFinite(price) || !positiveFinite(vol)
                    || (side != 1 && side != 2) || time <= 0 || id < 0
                    || (selfTrade != 1 && selfTrade != 2)) {
                return false;
            }
            deals.add(price, vol, side == 1, time, id, selfTrade == 1);
        }
        return true;
    }

    /** A JSON number, parsed from the parser's buffer. */
    private static double parseNumber(JsonParser p) {
        JsonToken t = p.currentToken();
        if (t != JsonToken.VALUE_NUMBER_FLOAT && t != JsonToken.VALUE_NUMBER_INT) {
            throw new IllegalArgumentException("expected a number, got " + t);
        }
        return JavaDoubleParser.parseDouble(p.getStringCharacters(), p.getStringOffset(), p.getStringLength());
    }

    /** {@code i}: a numeric string, parsed in place; {@code -1} if it is not one. */
    private static long parseId(JsonParser p) {
        if (p.currentToken() != JsonToken.VALUE_STRING) return -1;
        char[] chars = p.getStringCharacters();
        int from = p.getStringOffset();
        int length = p.getStringLength();
        if (length == 0 || length > MAX_ID_DIGITS) return -1;
        long value = 0;
        for (int i = from; i < from + length; i++) {
            int digit = chars[i] - '0';
            if (digit < 0 || digit > 9) return -1;
            value = value * 10 + digit;
        }
        return value;
    }

    /** Also rejects {@code NaN}, which fastdoubleparser accepts as text. */
    private static boolean positiveFinite(double v) {
        return v > 0 && v < Double.POSITIVE_INFINITY;
    }

    private static boolean reject(InstrumentSlot slot, String rawJson) {
        log.debug("[{}] Rejected MEXC futures deal frame: {}", slot.instrument().logName(), rawJson);
        return false;
    }

    /** One frame's deals, in frame order (newest first). Reused per thread. */
    private static final class Deals {
        int size;
        double[] price = new double[8];
        double[] vol = new double[8];
        boolean[] buy = new boolean[8];
        long[] time = new long[8];
        long[] id = new long[8];
        boolean[] selfTrade = new boolean[8];

        void add(double p, double v, boolean b, long t, long i, boolean self) {
            if (size == price.length) grow();
            price[size] = p;
            vol[size] = v;
            buy[size] = b;
            time[size] = t;
            id[size] = i;
            selfTrade[size] = self;
            size++;
        }

        private void grow() {
            int n = price.length * 2;
            price = Arrays.copyOf(price, n);
            vol = Arrays.copyOf(vol, n);
            buy = Arrays.copyOf(buy, n);
            time = Arrays.copyOf(time, n);
            id = Arrays.copyOf(id, n);
            selfTrade = Arrays.copyOf(selfTrade, n);
        }
    }
}
