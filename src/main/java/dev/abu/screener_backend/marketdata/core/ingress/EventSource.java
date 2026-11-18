package dev.abu.screener_backend.marketdata.core.ingress;

/**
 * Where the bytes in a {@link MarketEvent} came from — <b>provenance, not semantics</b>.
 *
 * <p>It says neither what the payload is (that is the event's stream kind) nor, for depth,
 * whether it is a snapshot or a delta: that is a venue-specific read the sync strategy performs.
 * On Binance the two coincide (a REST response is always a snapshot, a stream frame is always a
 * diff), but on a venue that delivers its snapshot in-stream the first {@code WS_MSG} after a
 * subscribe <i>is</i> the snapshot.
 *
 * <p>What core does derive from this is the parse path and the backpressure policy: a dropped
 * {@code WS_MSG} is recoverable, but <b>neither REST source may ever be dropped</b>, whatever the
 * stream kind. Each REST event is the single outcome of a request whose requester is waiting for
 * it, and losing it strands that requester with nothing in flight (for a depth snapshot, the book
 * stays in {@code RECOVERING}). When {@code WS_MSG} moves to a non-blocking {@code tryNext()}
 * claim (P3), both REST sources keep the blocking one.
 *
 * <p>{@link #TICK} is the one source with no venue behind it: core's own timer.
 */
public enum EventSource {
    /**
     * A frame delivered on a venue's WebSocket stream: text in {@code rawJson} or binary in
     * {@code rawBytes}, whichever the venue's wire protocol uses.
     */
    WS_MSG,
    /** A response body from a venue's REST API, in {@code rawJson}. */
    REST_MSG,
    /**
     * A REST request for this instrument completed without a usable body. Both {@code rawJson} and
     * {@code rawBytes} are {@code null}. Still provenance: it says the call ended with nothing to
     * apply, not why.
     */
    REST_FAILED,
    /**
     * Core's shard tick ({@code ShardTicker}): a signal to the shard's consumer that time has passed,
     * with no instrument ({@code instrumentId = -1}), no stream kind and no payload. The consumer
     * handles it before any slot lookup. Unlike the REST sources it may be dropped: a tick that does
     * not fit a full ring is skipped, because the next one follows.
     */
    TICK
}
