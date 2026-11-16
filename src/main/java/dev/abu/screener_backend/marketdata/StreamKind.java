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
 * {@link MarketEventHandler}. Later work adds values here, together with the arm that handles them.
 */
public enum StreamKind {
    /** Order-book depth: diffs and in-stream snapshots, plus the REST snapshots that seed a book. */
    DEPTH
}
