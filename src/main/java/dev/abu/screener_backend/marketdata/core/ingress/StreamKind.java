package dev.abu.screener_backend.marketdata.core.ingress;

/**
 * What the payload of a {@link MarketEvent} <em>is</em> — the kind of market-data stream it belongs
 * to. Orthogonal to {@link EventSource}, which says where the bytes came from.
 *
 * <p>The consumer dispatches on this: each kind has its own arm in {@link MarketEventHandler}.
 * Later work adds values here, together with the arm that handles them.
 */
public enum StreamKind {
    /** Order-book depth: diffs and in-stream snapshots, plus the REST snapshots that seed a book. */
    DEPTH
}
