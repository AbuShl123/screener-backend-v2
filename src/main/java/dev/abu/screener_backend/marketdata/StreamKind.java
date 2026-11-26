package dev.abu.screener_backend.marketdata;

import dev.abu.screener_backend.marketdata.core.ingress.EventSource;
import dev.abu.screener_backend.marketdata.core.ingress.MarketEvent;
import dev.abu.screener_backend.marketdata.core.ingress.MarketEventHandler;

/**
 * The kind of market-data stream: what the payload of a {@link MarketEvent} <em>is</em>, and which
 * channel a connection subscribes. Orthogonal to {@link EventSource}, which says where the bytes came
 * from.
 *
 * <p>An identity axis like {@link Venue}: a {@link StreamKey} pairs the two, and config, the adapter
 * SPIs and core are all keyed by it. The consumer dispatches on this: each kind has its own arm in
 * {@link MarketEventHandler}. A new value lands together with the arm that handles it.
 *
 * <p>A kind is bound per stream, so not every venue carries every kind: a venue with no binding for
 * a kind simply has no such stream.
 */
public enum StreamKind {
    /** Order-book depth: diffs and in-stream snapshots, plus the REST snapshots that seed a book. */
    DEPTH,
    /**
     * Executed trades (Binance {@code aggTrade}): events, not state. No sync and no REST recovery;
     * only WebSocket frames carry this kind.
     */
    TRADE,
    /**
     * Candlesticks (Binance {@code kline_1m}): each frame is the full current state of one
     * instrument's forming candle. No sync and no REST recovery; only WebSocket frames carry this
     * kind. Feeds the price and volume spike detectors.
     */
    KLINE
}
