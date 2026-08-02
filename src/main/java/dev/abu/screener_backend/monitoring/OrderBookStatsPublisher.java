package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.binance.orderbook.OrderBook;
import dev.abu.screener_backend.binance.orderbook.OrderBookStore;
import dev.abu.screener_backend.binance.orderbook.ShardStatsPublisher;
import dev.abu.screener_backend.config.DisruptorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Publishes each shard's orderbook statistics from that shard's own Disruptor consumer thread.
 *
 * <h3>Why the shard walks every one of its books</h3>
 * The alternative — each book publishing itself when it is updated — would freeze a dead book's
 * stats at its last healthy value, hiding exactly the outage that monitoring exists to surface. The
 * walk refreshes silent books too, so a book that stopped receiving diffs shows a growing
 * {@code idleMs} instead of a plausible-looking stale snapshot.
 *
 * <h3>Thread-safety contract</h3>
 * {@link #publishShard} runs on shard {@code shardIndex}'s consumer thread and reads the live
 * {@code TreeMap} sizes of the books it touches. It must therefore visit <b>only</b> books owned by
 * that shard. Ownership uses the identical mapping as
 * {@code DisruptorShardManager.getRingBuffer}: {@code Math.abs(symbol.hashCode()) % shardCount}. If
 * that mapping ever changes, change it here in the same commit or monitoring becomes a data race.
 */
@Component
@RequiredArgsConstructor
public class OrderBookStatsPublisher implements ShardStatsPublisher {

    private final OrderBookStore store;
    private final DisruptorProperties disruptorProps;

    @Override
    public void publishShard(int shardIndex, long now) {
        int shardCount = disruptorProps.shardCount();
        for (OrderBook book : store.books()) {
            if (Math.abs(book.getSymbol().hashCode()) % shardCount == shardIndex) {
                book.publishStats(now);
            }
        }
    }
}
