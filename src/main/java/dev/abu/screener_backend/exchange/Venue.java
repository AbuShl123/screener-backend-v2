package dev.abu.screener_backend.exchange;

/**
 * {@code (exchange, market)} — the pipeline's adapter unit.
 *
 * <p>"Exchange" is the wrong granularity: an exchange's spot and futures sides routinely differ in
 * URL, sequence-validation rule, snapshot endpoint, weight ceiling and stream shape. Binance
 * already demonstrates this (spot validates {@code U == lastUpdateId + 1}, futures validates
 * {@code pu == lastUpdateId}). Everything transport- or sync-shaped is therefore keyed on a venue.
 *
 * <p>Persistence and the public API stay on {@link Market}; only the pipeline moves to {@code Venue}.
 * That split is what lets the identity refactor land without a schema migration or a payload change.
 */
public enum Venue {

    BINANCE_SPOT(Exchange.BINANCE, Market.SPOT),
    BINANCE_FUTURES(Exchange.BINANCE, Market.FUTURES);

    private final Exchange exchange;
    private final Market market;

    Venue(Exchange exchange, Market market) {
        this.exchange = exchange;
        this.market = market;
    }

    public Exchange exchange() {
        return exchange;
    }

    public Market market() {
        return market;
    }

    /**
     * Resolves the venue for an {@code (exchange, market)} pair.
     *
     * <p>This is the bridge used wherever an API- or DB-facing {@link Market} has to be turned into
     * a pipeline identity — {@code /api/rules} validation and {@code /api/monitoring/orderbook}.
     *
     * @throws IllegalArgumentException if the exchange does not serve that market
     */
    public static Venue of(Exchange exchange, Market market) {
        for (Venue v : values()) {
            if (v.exchange == exchange && v.market == market) return v;
        }
        throw new IllegalArgumentException("No venue for " + exchange + "/" + market);
    }
}
