package dev.abu.screener_backend.exchange;

/**
 * The market an instrument trades on.
 *
 * <p>The value names are load-bearing and must never change: {@code classification_rules.market} persists them via
 * {@code EnumType.STRING}, and they appear verbatim in the {@code /api/rules} and WebSocket feed
 * payloads.
 *
 * <p>{@code streamSuffix()} used to live here and is deliberately gone: {@code "@depth"} /
 * {@code "@depth@500ms"} are Binance transport details, not properties of a persisted enum.
 * They are configured per venue under {@code screener.exchanges.binance.venues.*.depth-stream}.
 *
 * @see Venue
 */
public enum Market {
    SPOT,
    FUTURES
}
