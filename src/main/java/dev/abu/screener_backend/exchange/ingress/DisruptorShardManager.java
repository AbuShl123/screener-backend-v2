package dev.abu.screener_backend.exchange.ingress;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import dev.abu.screener_backend.analysis.DefaultClassificationRule;
import dev.abu.screener_backend.analysis.OrderBookClassifier;
import dev.abu.screener_backend.analysis.UserClassificationContext;
import dev.abu.screener_backend.config.DisruptorProperties;
import dev.abu.screener_backend.feed.OrderBookFeedStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DisruptorShardManager {

    private final DisruptorProperties      props;
    private final OrderBookProcessor       orderBookProcessor;
    private final OrderBookFeedStore       feedStore;
    private final DefaultClassificationRule defaultRule;

    private Disruptor<DepthEvent>[]  disruptors;
    private RingBuffer<DepthEvent>[] ringBuffers;
    private OrderBookClassifier[]    classifiers;

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
            disruptor.handleEventsWith(new DepthEventHandler(i, orderBookProcessor, classifiers[i]));

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

    @PreDestroy
    public void shutdown() {
        for (Disruptor<DepthEvent> d : disruptors) {
            d.shutdown();
        }
        log.info("Disruptor pipeline shut down");
    }
}
