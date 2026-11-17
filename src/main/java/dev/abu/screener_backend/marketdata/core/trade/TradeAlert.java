package dev.abu.screener_backend.marketdata.core.trade;

import dev.abu.screener_backend.marketdata.Instrument;

/**
 * One large executed trade: a single aggTrade today, a coalesced sweep from slice 3 on.
 *
 * <p>Holds the {@link Instrument}, whose dense id must not leave the process: anything that
 * serializes an alert maps it to its own view first.
 *
 * @param instrument   where it traded
 * @param aggressorBuy {@code true} if the taker bought
 * @param vwap         volume-weighted average price, {@code notional / qty}
 * @param qty          executed base quantity
 * @param notional     executed quote amount, the sum of {@code price × qty} over the fills
 * @param firstPrice   price of the first fill
 * @param lastPrice    price of the last fill
 * @param fills        number of {@code TradeSink.onTrade} calls folded in; {@code 1} for a single
 *                     trade
 * @param firstTradeId first venue trade id, or {@code -1} when the venue has none
 * @param lastTradeId  last venue trade id, or {@code -1}
 * @param tradeTimeMs  venue trade time, epoch millis
 * @param detectedAtMs local time the alert was raised, epoch millis
 */
public record TradeAlert(
        Instrument instrument,
        boolean aggressorBuy,
        double vwap,
        double qty,
        double notional,
        double firstPrice,
        double lastPrice,
        int fills,
        long firstTradeId,
        long lastTradeId,
        long tradeTimeMs,
        long detectedAtMs
) {}
