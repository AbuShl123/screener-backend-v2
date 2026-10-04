package dev.abu.screener_backend.marketdata;

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
    BINANCE_FUTURES(Exchange.BINANCE, Market.FUTURES),
    // MEXC spot is deliberately absent: its stream is Protobuf-encoded and has no adapter. A venue
    // constant without an adapter is safe only while disabled — add it with its adapter.
    MEXC_FUTURES(Exchange.MEXC, Market.FUTURES);

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
     * <p>This is the bridge for turning an API- or DB-facing {@link Market} into a pipeline identity
     * when the exchange is known. Rule validation does not use it: rules are exchange-independent,
     * so it asks {@link InstrumentRegistry#isTracked} instead.
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
