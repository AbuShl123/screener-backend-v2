package dev.abu.screener_backend.binance.orderbook;

/**
 * Inversion seam that lets the Disruptor consumer hand its books' statistics to the monitoring
 * module without {@code binance/} ever importing {@code monitoring/} — the same direction the
 * project already uses for {@code OrderBookProcessor} → {@code SnapshotFetchQueue}.
 *
 * <p>Implemented by {@code monitoring.OrderBookStatsPublisher}.
 */
public interface ShardStatsPublisher {

    /**
     * Republish the {@link BookStats} of every orderbook owned by the given shard.
     *
     * <p><b>Called on the shard's Disruptor consumer thread</b>, on a slow (≈1 s) cadence — never
     * per message. Implementations must touch only books belonging to {@code shardIndex}, because
     * publishing walks each book's live {@code TreeMap.size()}.
     *
     * @param shardIndex the calling consumer's shard
     * @param now        wall clock already read by the caller; reused to avoid a second clock read
     */
    void publishShard(int shardIndex, long now);
}
