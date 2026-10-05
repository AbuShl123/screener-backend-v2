package dev.abu.screener_backend.marketdata.core.ingress;

/**
 * Where the bytes in a {@link DepthEvent} came from — <b>provenance, not semantics</b>.
 *
 * <p>It deliberately does not say whether the payload is a snapshot or a delta: that is a
 * venue-specific read the sync strategy performs. On Binance the two coincide (a REST response is
 * always a snapshot, a stream frame is always a diff), but on a venue that delivers its snapshot
 * in-stream the first {@code WS_MSG} after a subscribe <i>is</i> the snapshot.
 *
 * <p>What core does derive from this is the parse path and the backpressure policy: a dropped
 * {@code WS_MSG} is recoverable, but <b>neither REST type may ever be dropped</b>. Each one is the
 * single outcome of a snapshot request, and losing either strands its book in {@code RECOVERING}
 * with nothing in flight. When {@code WS_MSG} moves to a non-blocking {@code tryNext()} claim (P3),
 * both REST types keep the blocking one.
 */
public enum EventType {
    /** A frame delivered on a venue's WebSocket stream. */
    WS_MSG,
    /** A response body from a venue's REST API. */
    REST_MSG,
    /**
     * A REST request for this instrument completed without a usable body. {@code rawJson} is
     * {@code null}. Still provenance: it says the call ended with nothing to apply, not why.
     */
    REST_FAILED
}
