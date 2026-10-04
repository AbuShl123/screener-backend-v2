package dev.abu.screener_backend.exchange.spi;

/**
 * One discovered instrument, as reported by an {@link InstrumentSource}.
 *
 * <p>Venue-less on purpose: core attaches the venue from the key of the map
 * {@link InstrumentSource#fetch()} returns, so a source cannot file a candidate under the wrong
 * venue. Deliberately minimal — {@code tickSize} / {@code stepSize} arrive with the first venue
 * that needs them.
 *
 * @param quantityMultiplier converts the venue's wire quantity into base-asset quantity, so every
 *                           book holds base asset and the classifier's notional stays comparable
 *                           across exchanges. {@code 1.0} where the wire is already in base asset
 *                           (Binance); MEXC futures quotes contracts, so it is the contract size
 *                           (BTC_USDT: {@code 0.0001})
 */
public record InstrumentCandidate(String nativeSymbol, String base, String quote, double quantityMultiplier) {

    public InstrumentCandidate {
        if (!(quantityMultiplier > 0) || Double.isInfinite(quantityMultiplier)) {
            throw new IllegalArgumentException("quantityMultiplier must be positive and finite for "
                    + nativeSymbol + ", got " + quantityMultiplier);
        }
    }

    /** A candidate whose wire quantities are already in base asset. */
    public InstrumentCandidate(String nativeSymbol, String base, String quote) {
        this(nativeSymbol, base, quote, 1.0);
    }
}
