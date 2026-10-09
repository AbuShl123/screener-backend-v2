package dev.abu.screener_backend.marketdata.core.ingress;

import java.nio.ByteBuffer;

/**
 * The only way events enter the sharded pipeline. Implementations pick the shard from the id
 * ({@code id & (shardCount - 1)}), so both producers route an instrument to the same shard.
 */
public interface DepthEventPublisher {

    /**
     * Hot path, WebSocket reader threads. Must not parse.
     *
     * @param instrumentId already resolved by the transport; always a valid, published id
     * @param payload      the frame exactly as received
     */
    void publishFrame(int instrumentId, String payload);

    /**
     * Hot path, WebSocket reader threads. The binary twin of {@link #publishFrame(int, String)}:
     * the buffer goes into the ring as-is, so the caller must not touch it afterwards.
     *
     * @param instrumentId already resolved by the transport; always a valid, published id
     * @param payload      the frame exactly as received, {@code position} and {@code limit} untouched
     */
    void publishFrame(int instrumentId, ByteBuffer payload);

    /**
     * Snapshot-fetch completion threads (Reactor). Publishing — rather than writing to the book
     * from the HTTP thread — is what lets the consumer apply the snapshot in sequence with diffs.
     *
     * @param instrumentId the instrument the snapshot was fetched for
     * @param payload      the REST response body as received
     */
    void publishSnapshot(int instrumentId, String payload);

    /**
     * Snapshot-fetch completion threads. A failed request is published rather than handled on the
     * HTTP thread for the same reason a successful one is: only the shard's consumer may change
     * the book's state and its sync context, and it must see the failure <em>after</em> it marked
     * the book {@code RECOVERING}.
     *
     * @param instrumentId the instrument whose snapshot request ended without a usable body
     */
    void publishSnapshotFailure(int instrumentId);
}
