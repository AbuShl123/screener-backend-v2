package dev.abu.screener_backend.marketdata.adapter.binance;

import ch.randelshofer.fastdoubleparser.JavaDoubleParser;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.KlineDecoder;
import dev.abu.screener_backend.marketdata.spi.KlineSink;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

/**
 * Decodes Binance {@code kline_1m} frames. One candle per frame, the full state of the forming
 * candle so far:
 *
 * <pre>
 * {"e":"kline","E":1795689455628,"s":"BTCUSDT","k":{"t":1795689420000, "T":1795689479999,
 *  "s":"BTCUSDT", "i":"1m", "f":…, "L":…, "o":"60750.30", "c":"60659.80", "h":"60760.00",
 *  "l":"60654.40", "v":"302.461", "n":4650, "x":false, "q":"18359538.73310", "V":"…", "Q":"…", "B":"0"}}
 * </pre>
 *
 * <ul>
 *   <li>Used: {@code E} (event time) at the top level; {@code t} (open time), {@code h}, {@code l},
 *       {@code c} and {@code q} (quote volume, USDT) inside {@code k}. All are required. Everything
 *       else, including {@code x} (closed), is skipped: core rolls a candle over on a new
 *       {@code t}, which survives a lost final push.</li>
 *   <li>Prices must be positive and finite, the quote volume finite and not negative, and
 *       {@code l <= h}; otherwise the frame returns {@code false} and reports nothing.</li>
 *   <li>Field names are case-sensitive: {@code q} (quote volume) is not {@code Q} (taker buy quote
 *       volume), nor {@code T} (close time) {@code t}.</li>
 * </ul>
 *
 * <p>Stateless: one instance can serve every Binance venue.
 */
@Slf4j
public final class BinanceKlineDecoder implements KlineDecoder {

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    private static final int EVENT_TIME = 1;
    private static final int OPEN_TIME = 1 << 1;
    private static final int HIGH = 1 << 2;
    private static final int LOW = 1 << 3;
    private static final int CLOSE = 1 << 4;
    private static final int QUOTE_VOLUME = 1 << 5;
    private static final int REQUIRED = EVENT_TIME | OPEN_TIME | HIGH | LOW | CLOSE | QUOTE_VOLUME;

    @Override
    public boolean decode(InstrumentSlot slot, String rawJson, KlineSink sink) {
        long eventTime = 0;
        long openTime = 0;
        double high = 0;
        double low = 0;
        double close = 0;
        double quoteVolume = 0;
        int seen = 0;

        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return false;
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if (field.equals("E")) {
                    eventTime = p.getLongValue();
                    seen |= EVENT_TIME;
                } else if (field.equals("k") && value == JsonToken.START_OBJECT) {
                    while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                        String k = p.currentName();
                        p.nextToken();
                        switch (k) {
                            case "t" -> { openTime = p.getLongValue(); seen |= OPEN_TIME; }
                            case "h" -> { high = parseDecimal(p); seen |= HIGH; }
                            case "l" -> { low = parseDecimal(p); seen |= LOW; }
                            case "c" -> { close = parseDecimal(p); seen |= CLOSE; }
                            case "q" -> { quoteVolume = parseDecimal(p); seen |= QUOTE_VOLUME; }
                            default -> p.skipChildren();
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
        } catch (Exception e) {
            log.debug("[{}] Failed to parse kline: {}", slot.instrument().logName(), e.getMessage());
            return false;
        }

        if (seen != REQUIRED || !positiveFinite(high) || !positiveFinite(low) || !positiveFinite(close)
                || low > high || !(quoteVolume >= 0 && quoteVolume < Double.POSITIVE_INFINITY)) {
            log.debug("[{}] Rejected kline frame: {}", slot.instrument().logName(), rawJson);
            return false;
        }

        sink.onKline(slot, openTime, high, low, close, quoteVolume, eventTime);
        return true;
    }

    /** Binance sends prices and volumes as JSON strings; parsed in place from the parser's buffer. */
    private static double parseDecimal(JsonParser p) {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            throw new IllegalArgumentException("expected a string, got " + p.currentToken());
        }
        return JavaDoubleParser.parseDouble(p.getStringCharacters(), p.getStringOffset(), p.getStringLength());
    }

    /** Also rejects {@code NaN}, which fastdoubleparser accepts as text. */
    private static boolean positiveFinite(double v) {
        return v > 0 && v < Double.POSITIVE_INFINITY;
    }
}
