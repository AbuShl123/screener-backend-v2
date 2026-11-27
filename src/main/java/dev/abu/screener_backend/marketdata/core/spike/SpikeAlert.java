package dev.abu.screener_backend.marketdata.core.spike;

import dev.abu.screener_backend.marketdata.Instrument;

/**
 * One price or volume spike.
 *
 * <p>Holds the {@link Instrument}, whose dense id must not leave the process: anything that
 * serializes an alert maps it to its own view first, and uses {@link #id()} as its identity.
 *
 * @param instrument    where it happened
 * @param kind          price or volume
 * @param pct           price: signed change in percent, positive for a pump, negative for a dump.
 *                      Volume: increase of the current window over the previous one in percent,
 *                      {@link Double#POSITIVE_INFINITY} when the previous window traded nothing.
 * @param from          price: the window's low (pump) or high (dump). Volume: the previous window's
 *                      quote volume.
 * @param to            price: the current price. Volume: the current window's quote volume.
 * @param windowMinutes the length of the window the change was measured over
 * @param timeMs        venue event time of the candle push that raised it, epoch millis
 * @param detectedAtMs  local time the alert was raised, epoch millis
 */
public record SpikeAlert(
        Instrument instrument,
        SpikeKind kind,
        double pct,
        double from,
        double to,
        int windowMinutes,
        long timeMs,
        long detectedAtMs
) {

    /** {@code true} for a price pump, {@code false} for a dump. Meaningless for volume. */
    public boolean up() {
        return pct >= 0;
    }

    /**
     * Stable identity of the alert: {@code VENUE:SYMBOL:KIND:timeMs}, e.g.
     * {@code BINANCE_FUTURES:BTCUSDT:PRICE:1795689455628}. One push raises at most one alert of each
     * kind for an instrument, so it cannot collide.
     */
    public String id() {
        return instrument.venue().name() + ':' + instrument.nativeSymbol() + ':' + kind.name() + ':' + timeMs;
    }
}
