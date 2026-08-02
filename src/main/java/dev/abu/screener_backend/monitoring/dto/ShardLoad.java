package dev.abu.screener_backend.monitoring.dto;

/**
 * Load carried by one Disruptor shard's consumer thread.
 *
 * <p>Ticker → shard is {@code Math.abs(symbol.hashCode()) % shardCount}, which distributes <b>book
 * count</b> almost perfectly and says nothing whatsoever about <b>work</b>. Those are not the same
 * thing: a single book can hold tens of thousands of levels and receive two diffs a second, and the
 * mid-price sweep walks all of them on every one of those diffs. In a representative sample the
 * largest book held 52× the median book's levels, so which shard it lands on is decided by a hash
 * and changes the balance materially.
 *
 * <p>{@link #levelVisitsPerSecond} is therefore the field to compare across shards — not
 * {@code books}, and not {@code updatesPerSecond}. Two shards with equal book counts can differ
 * severalfold in real CPU cost.
 *
 * @param shard                index, {@code 0 .. shard-count-1}
 * @param books                books whose symbol hashes to this shard
 * @param totalLevels          Σ size over those books
 * @param updatesPerSecond     Σ per-book diffs/second on this shard
 * @param levelVisitsPerSecond Σ {@code size × updatesPerSecond} — price levels this consumer thread
 *                             sweeps per second, and the closest available proxy for its CPU load
 * @param largestBook          symbol of the biggest book on the shard; {@code null} if it has none
 * @param largestBookSize      that book's level count
 * @param sampleAgeMs          age of this shard's newest publication; a shard whose consumer is not
 *                             clearing its publish gate reports frozen numbers, and this is how far
 *                             behind they are
 */
public record ShardLoad(
        int shard,
        int books,
        long totalLevels,
        double updatesPerSecond,
        double levelVisitsPerSecond,
        String largestBook,
        int largestBookSize,
        long sampleAgeMs
) {}
