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
 * @param nativeSymbol exactly what the exchange expects, e.g. {@code "BTCUSDT"} (MEXC futures would
 *                     be {@code "BTC_USDT"})
 * @param base         base asset, e.g. {@code "BTC"}
 * @param quote        quote asset, e.g. {@code "USDT"}
 * @param symbol       precomputed {@code base + quote}, e.g. {@code "BTCUSDT"} — the normalized,
 *                     exchange-independent spelling used by the rule API, the high-liquidity set
 *                     and the WebSocket payload
 * @param ruleKey      precomputed {@code symbol + ":" + market.name()}, e.g. {@code "BTCUSDT:SPOT"} —
 *                     venue-agnostic; the key user classification rules are looked up by
 * @param feedKey      precomputed {@code exchange.name() + ":" + market.name() + ":" + symbol}, e.g.
 *                     {@code "BINANCE:SPOT:BTCUSDT"} — venue-specific; the key classification state
 *                     and feed-store entries are held under
 * @param logName      precomputed {@code venue.name() + "/" + nativeSymbol}, for log lines only
 */
public record Instrument(
        int id,
        Venue venue,
        String nativeSymbol,
        String base,
        String quote,
        String symbol,
        String ruleKey,
        String feedKey,
        String logName
) {

    /**
     * Builds an instrument with all derived strings precomputed, so no key is concatenated per
     * depth message.
     *
     * <p>{@code ruleKey} must remain byte-for-byte {@code BASEQUOTE:MARKET} — that is the format
     * {@code ClassificationRuleService.buildRuntimeRule} builds {@code UserClassificationRules}
     * from the database with. A difference here would silently degrade custom-rule users to
     * default tiers. For Binance, {@code base + quote == nativeSymbol}, so stored rules match.
     *
     * <p>{@code feedKey} has no external format contract, but must be unique per instrument:
     * two instruments sharing one would share classification state and overwrite each other's
     * feed entry.
     */
    public static Instrument of(int id, Venue venue, String nativeSymbol, String base, String quote) {
        String symbol = base + quote;
        String market = venue.market().name();
        return new Instrument(
                id,
                venue,
                nativeSymbol,
                base,
                quote,
                symbol,
                symbol + ":" + market,
                venue.exchange().name() + ":" + market + ":" + symbol,
                venue.name() + "/" + nativeSymbol
        );
    }

    public Market market() {
        return venue.market();
    }

    public Exchange exchange() {
        return venue.exchange();
    }
}
