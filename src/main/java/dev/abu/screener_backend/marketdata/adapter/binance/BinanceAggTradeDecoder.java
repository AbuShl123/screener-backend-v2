package dev.abu.screener_backend.marketdata.adapter.binance;

import ch.randelshofer.fastdoubleparser.JavaDoubleParser;
import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;
import dev.abu.screener_backend.marketdata.spi.TradeDecoder;
import dev.abu.screener_backend.marketdata.spi.TradeSink;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

/**
 * Decodes Binance {@code aggTrade} frames, spot and futures. One trade per frame.
 *
 * <pre>
 * spot:    {"e":"aggTrade","E":…,"s":"BTCUSDT","a":…,"p":"64826.00000000","q":"0.02711000",
 *           "f":…,"l":…,"T":…,"m":true,"M":true}
 * futures: {"e":"aggTrade","E":…,"a":…,"s":"BTCUSDT","p":"65079.50","q":"0.006","nq":"0.006",
 *           "f":…,"l":…,"T":…,"m":true,"st":1}
 * </pre>
 *
 * <ul>
 *   <li>Field order differs between the venues, so the loop switches on the field name and nothing
 *       depends on position. Names are case-sensitive: {@code T} (trade time) is not {@code E}
 *       (event time), and {@code m} is not spot's {@code M}.</li>
 *   <li>{@code m = true} means the buyer was the maker, so the aggressor <b>sold</b>:
 *       {@code aggressorBuy = !m}.</li>
 *   <li>Quantity is {@code q}, the full executed quantity. Futures {@code nq} (believed to exclude
 *       RPI fills) is skipped, with {@code st} and spot {@code M}.</li>
 *   <li>{@code p}, {@code q}, {@code T} and {@code m} are required; a frame missing one, or with a
 *       price or quantity that is not a positive finite number, returns {@code false} and reports
 *       nothing. {@code f} / {@code l} are always present on Binance, but a frame without them
 *       still decodes with ids {@code -1}.</li>
 * </ul>
 *
 * <p>One {@code aggTrade} already sums a taker order's fills at one price, and Binance publishes no
 * taker-order id, so the decoder keeps the SPI defaults: {@code takerOrderId = -1}, no maker fills,
 * no close at frame end.
 *
 * <p>Stateless: one instance serves both Binance venues.
 */
@Slf4j
public final class BinanceAggTradeDecoder implements TradeDecoder {

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    private static final int PRICE = 1;
    private static final int QTY = 1 << 1;
    private static final int TIME = 1 << 2;
    private static final int MAKER = 1 << 3;
    private static final int REQUIRED = PRICE | QTY | TIME | MAKER;

    @Override
    public boolean decode(InstrumentSlot slot, String rawJson, TradeSink sink) {
        double price = 0;
        double qty = 0;
        long tradeTimeMs = 0;
        boolean buyerMaker = false;
        long firstTradeId = -1;
        long lastTradeId = -1;
        int seen = 0;

        try (JsonParser p = JSON_FACTORY.createParser(ObjectReadContext.empty(), rawJson)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return false;
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "p" -> { price = parseDecimal(p); seen |= PRICE; }
                    case "q" -> { qty = parseDecimal(p); seen |= QTY; }
                    case "T" -> { tradeTimeMs = p.getLongValue(); seen |= TIME; }
                    case "m" -> { buyerMaker = p.getBooleanValue(); seen |= MAKER; }
                    case "f" -> firstTradeId = p.getLongValue();
                    case "l" -> lastTradeId = p.getLongValue();
                    default -> p.skipChildren();
                }
            }
        } catch (Exception e) {
            log.debug("[{}] Failed to parse aggTrade: {}", slot.instrument().logName(), e.getMessage());
            return false;
        }

        if (seen != REQUIRED || !positiveFinite(price) || !positiveFinite(qty)) {
            log.debug("[{}] Rejected aggTrade frame: {}", slot.instrument().logName(), rawJson);
            return false;
        }

        sink.onTrade(slot, price, qty, !buyerMaker, tradeTimeMs, firstTradeId, lastTradeId, -1);
        return true;
    }

    /** Binance sends prices and quantities as JSON strings; parsed in place from the parser's buffer. */
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
