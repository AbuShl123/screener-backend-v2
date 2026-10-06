package dev.abu.screener_backend.marketdata.core.ingress;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import dev.abu.screener_backend.analysis.DefaultClassificationRule;
import dev.abu.screener_backend.analysis.OrderBookClassifier;
import dev.abu.screener_backend.analysis.UserClassificationContext;
import dev.abu.screener_backend.config.DisruptorProperties;
import dev.abu.screener_backend.marketdata.core.book.BookSlotTable;
import dev.abu.screener_backend.feed.depth.OrderBookFeedStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DisruptorShardManager {

    private final DisruptorProperties       props;
    private final BookSlotTable             slots;
    private final OrderBookFeedStore        feedStore;
    private final DefaultClassificationRule defaultRule;

    private Disruptor<DepthEvent>[]  disruptors;
    private RingBuffer<DepthEvent>[] ringBuffers;
    private OrderBookClassifier[]    classifiers;
    private DepthEventHandler[]      handlers;

    /** {@code shardCount - 1}; valid only because shardCount is validated as a power of two. */
    private int shardMask;

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void start() {
        int shardCount = props.shardCount();
        if (shardCount < 1 || Integer.bitCount(shardCount) != 1) {
            throw new IllegalStateException(
                    "screener.disruptor.shard-count must be a power of two and >= 1, got " + shardCount);
        }
        shardMask = shardCount - 1;
        disruptors  = new Disruptor[shardCount];
        ringBuffers = new RingBuffer[shardCount];
        classifiers = new OrderBookClassifier[shardCount];
        handlers    = new DepthEventHandler[shardCount];

        for (int i = 0; i < shardCount; i++) {
            int shardIndex = i;
            Disruptor<DepthEvent> disruptor = new Disruptor<>(
                    new DepthEventFactory(),
                    props.ringBufferSize(),
                    r -> new Thread(r, "disruptor-shard-" + shardIndex),
                    ProducerType.MULTI,
                    new BlockingWaitStrategy()
            );
            classifiers[i] = new OrderBookClassifier(feedStore, defaultRule);
            handlers[i]    = new DepthEventHandler(i, slots, classifiers[i]);
            disruptor.handleEventsWith(handlers[i]);

            ringBuffers[i] = disruptor.start();
            disruptors[i]  = disruptor;
        }
        log.info("Disruptor pipeline started — {} shards, {} slots each", shardCount, props.ringBufferSize());
    }

    /**
     * Fans the active user-context array out to every shard's classifier. Called from the Tomcat
     * connect/disconnect thread via {@code UserFeedRegistry}. Every shard receives the <b>same</b>
     * array reference because a user's configured symbols spread across all shards — each shard
     * must be able to match any configured key.
     */
    public void setActiveUserContexts(UserClassificationContext[] ctxs) {
        for (OrderBookClassifier c : classifiers) {
            c.setActiveUserContexts(ctxs);
        }
    }

    /**
     * Maps an instrument to its shard. Both producers — the WebSocket reader thread and the
     * snapshot queue's Reactor thread — must route through this one expression: an instrument's
     * events splitting across shards would mean two threads mutating one non-thread-safe book.
     *
     * <p>Dense ids make this a mask instead of {@code Math.abs(hashCode()) % n}, which distributes
     * perfectly rather than by hash luck — and removes a latent crash, since
     * {@code Math.abs(Integer.MIN_VALUE)} is negative and could index out of bounds.
     */
    public RingBuffer<DepthEvent> getRingBuffer(int instrumentId) {
        return ringBuffers[instrumentId & shardMask];
    }

    /** Number of shards, or 0 before {@link #start()} has run. */
    public int shardCount() {
        return ringBuffers == null ? 0 : ringBuffers.length;
    }

    /**
     * Events consumed by each shard since startup, for the health log. Cold path.
     *
     * <p>Shard totals should stay within a few percent of each other: instruments are spread by
     * {@code id & mask} over dense ids, so a persistent skew would mean the id space is not as
     * dense or as evenly distributed as the routing assumes.
     */
    public long[] processedPerShard() {
        long[] out = new long[shardCount()];
        for (int i = 0; i < out.length; i++) out[i] = handlers[i].processed();
        return out;
    }

    /**
     * Free slots in each shard's ring buffer, sampled from the caller's thread. Cold path.
     *
     * <p>The definitive "is the consumer keeping up" reading, and free of hot-path cost since it
     * derives from sequences the Disruptor already maintains. At steady state these should sit at
     * essentially {@link DisruptorProperties#ringBufferSize()}; a persistent dip means producers
     * are outrunning consumers, which is what makes the blocking {@code next()} in
     * {@link DisruptorDepthEventPublisher} a live risk rather than a theoretical one.
     */
    public long[] ringFreePerShard() {
        long[] out = new long[shardCount()];
        for (int i = 0; i < out.length; i++) out[i] = ringBuffers[i].remainingCapacity();
        return out;
    }

    @PreDestroy
    public void shutdown() {
        for (Disruptor<DepthEvent> d : disruptors) {
            d.shutdown();
        }
        log.info("Disruptor pipeline shut down");
    }
}
