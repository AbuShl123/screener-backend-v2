package dev.abu.screener_backend.exchange.ingress;

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
     * Snapshot-fetch completion threads (Reactor). Publishing — rather than writing to the book
     * from the HTTP thread — is what lets the consumer apply the snapshot in sequence with diffs.
     *
     * @param instrumentId the instrument the snapshot was fetched for
     * @param payload      the REST response body as received
     */
    void publishSnapshot(int instrumentId, String payload);
}
