package dev.abu.screener_backend.exchange;

/**
 * One tradable instrument on one {@link Venue}, with a dense runtime id.
 *
 * <p><b>{@code (venue, nativeSymbol)} is the durable identity.</b> The {@code id} is a
 * process-local array index handed out by {@link InstrumentRegistry}; it is never persisted and
 * never leaves the process except through the debug {@code /api/tickers} endpoint. See
 * {@link InstrumentRegistry} for the id rules.
 *
 * <p>{@code BTCUSDT} on spot and {@code BTCUSDT} on futures are <b>two</b> instruments with two
 * ids, two slots and two order books.
 *
 * @param id           dense runtime index, assigned at registration; stable for the process lifetime
 * @param venue        the venue this instrument trades on
 * @param nativeSymbol exactly what the exchange expects, e.g. {@code "BTCUSDT"}
 * @param base         base asset, e.g. {@code "BTC"}
 * @param quote        quote asset, e.g. {@code "USDT"}
 * @param canonical    cross-venue pair name, e.g. {@code "BTC/USDT"} — populated but unread this
 *                     phase; it is free to fill at discovery time and expensive to backfill later
 * @param feedKey      precomputed {@code nativeSymbol + ":" + market.name()} — the exact string the
 *                     classifier, feed store and per-user rule map are keyed on. Precomputing it
 *                     removes a string concatenation from every depth message
 * @param logName      precomputed {@code venue.name() + "/" + nativeSymbol}, for log lines only
 */
public record Instrument(
        int id,
        Venue venue,
        String nativeSymbol,
        String base,
        String quote,
        String canonical,
        String feedKey,
        String logName
) {

    /**
     * Builds an instrument with all derived strings precomputed.
     *
     * <p>{@code feedKey} must remain byte-for-byte {@code SYMBOL:MARKET} — that is what
     * {@code UserClassificationRules.configuredKeys()} is populated with from the database. A
     * difference here would silently degrade custom-rule users to default tiers.
     */
    public static Instrument of(int id, Venue venue, String nativeSymbol, String base, String quote) {
        return new Instrument(
                id,
                venue,
                nativeSymbol,
                base,
                quote,
                base + "/" + quote,
                nativeSymbol + ":" + venue.market().name(),
                venue.name() + "/" + nativeSymbol
        );
    }

    public Market market() {
        return venue.market();
    }
}
