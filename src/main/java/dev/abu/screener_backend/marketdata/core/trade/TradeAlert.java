package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.marketdata.Instrument;

/**
 * One large executed taker order: a sweep of one or more fills that {@link TradeDetector} coalesced
 * and judged as a whole.
 *
 * <p>Holds the {@link Instrument}, whose dense id must not leave the process: anything that
 * serializes an alert maps it to its own view first, and uses {@link #id()} as its identity.
 *
 * @param instrument     where it traded
 * @param aggressorBuy   {@code true} if the taker bought
 * @param vwap           volume-weighted average price, {@code notional / qty}
 * @param qty            executed base quantity
 * @param notional       executed quote amount, the sum of {@code price × qty} over the fills
 * @param minNotional    the threshold the sweep was judged against ({@link TradeThresholds#minNotional});
 *                       {@code notional ≥ minNotional} always
 * @param firstPrice    price of the first fill
 * @param lastPrice      price of the last fill
 * @param fills          number of {@code TradeSink.onTrade} calls folded in; {@code 1} for a single
 *                       trade
 * @param coalescedOnly  {@code true} when no single fill reached the threshold on its own: the alert
 *                       exists only because the fills were summed. Always {@code false} for one fill.
 * @param firstTradeId   first venue trade id, or {@code -1} when the venue has none
 * @param lastTradeId    last venue trade id, or {@code -1}
 * @param tradeTimeMs    venue trade time, epoch millis
 * @param detectedAtMs   local time the alert was raised, epoch millis. A sweep is judged when the
 *                       instrument's next trade closes it, so this includes that wait.
 */
public record TradeAlert(
        Instrument instrument,
        boolean aggressorBuy,
        double vwap,
        double qty,
        double notional,
        double minNotional,
        double firstPrice,
        double lastPrice,
        int fills,
        boolean coalescedOnly,
        long firstTradeId,
        long lastTradeId,
        long tradeTimeMs,
        long detectedAtMs
) {

    /**
     * Stable identity of the alert, the same for the same trades across restarts and replays:
     * {@code VENUE:SYMBOL:firstTradeId}, e.g. {@code BINANCE_FUTURES:BTCUSDT:8285985083}. A trade id
     * belongs to one sweep only, so it cannot collide.
     *
     * <p>A venue without numeric trade ids gets {@code VENUE:SYMBOL:t<tradeTimeMs><B|S>}, which two
     * same-side orders in one millisecond would share. No such venue has a decoder yet; when one
     * does, it needs a better identity.
     */
    public String id() {
        String prefix = instrument.venue().name() + ':' + instrument.nativeSymbol() + ':';
        return firstTradeId >= 0
                ? prefix + firstTradeId
                : prefix + 't' + tradeTimeMs + (aggressorBuy ? 'B' : 'S');
    }
}
