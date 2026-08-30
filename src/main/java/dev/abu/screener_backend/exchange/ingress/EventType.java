package dev.abu.screener_backend.exchange.ingress;

/**
 * Where the bytes in a {@link DepthEvent} came from — <b>provenance, not semantics</b>.
 *
 * <p>It deliberately does not say whether the payload is a snapshot or a delta: that is a
 * venue-specific read the sync strategy performs. On Binance the two coincide (a REST response is
 * always a snapshot, a stream frame is always a diff), but on a venue that delivers its snapshot
 * in-stream the first {@code WS_MSG} after a subscribe <i>is</i> the snapshot.
 *
 * <p>What core does derive from this is the parse path and the backpressure policy: a dropped
 * {@code WS_MSG} is recoverable, a {@code REST_MSG} is rare and must not be lost.
 */
public enum EventType {
    /** A frame delivered on a venue's WebSocket stream. */
    WS_MSG,
    /** A response body from a venue's REST API. */
    REST_MSG
}
