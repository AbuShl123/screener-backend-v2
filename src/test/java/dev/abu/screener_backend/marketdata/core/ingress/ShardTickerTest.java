package dev.abu.screener_backend.marketdata.core.ingress;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ShardTickerTest {

    private final PipelineMetrics metrics = new PipelineMetrics();

    @Test
    @DisplayName("one tick publishes one TICK event into every shard's ring, overwriting every field of the reused slot")
    void tickReachesEveryRing() {
        RingBuffer<MarketEvent>[] rings = rings(2, 4);
        for (RingBuffer<MarketEvent> ring : rings) {
            MarketEvent stale = ring.get(0);
            stale.source = EventSource.WS_MSG;
            stale.stream = StreamKind.TRADE;
            stale.instrumentId = 7;
            stale.rawJson = "{}";
            stale.rawBytes = ByteBuffer.allocate(1);
        }

        new ShardTicker(rings, metrics).tick();

        for (RingBuffer<MarketEvent> ring : rings) {
            assertEquals(0, ring.getCursor(), "exactly one event published");
            MarketEvent e = ring.get(0);
            assertEquals(EventSource.TICK, e.source);
            assertNull(e.stream);
            assertEquals(-1, e.instrumentId);
            assertNull(e.rawJson);
            assertNull(e.rawBytes);
        }
        assertEquals(0, metrics.ticksSkipped());
    }

    @Test
    @DisplayName("a full ring skips the tick without blocking, counts it, and the other rings still get theirs")
    void fullRingSkipsWithoutBlocking() {
        RingBuffer<MarketEvent>[] rings = rings(2, 2);
        // A consumer that never advances: ring 0 is full after two claims.
        rings[0].addGatingSequences(new Sequence());
        ShardTicker ticker = new ShardTicker(rings, metrics);
        ticker.tick();
        ticker.tick();

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), ticker::tick);

        assertEquals(1, metrics.ticksSkipped());
        assertEquals(1, rings[0].getCursor(), "ring 0 kept its two ticks and took no third");
        assertEquals(2, rings[1].getCursor(), "ring 1 took all three");
    }

    @SuppressWarnings("unchecked")
    private static RingBuffer<MarketEvent>[] rings(int count, int size) {
        RingBuffer<MarketEvent>[] rings = new RingBuffer[count];
        for (int i = 0; i < count; i++) rings[i] = RingBuffer.createMultiProducer(new MarketEventFactory(), size);
        return rings;
    }
}
