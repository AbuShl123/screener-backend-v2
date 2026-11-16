package dev.abu.screener_backend.marketdata.core.ingress;

import com.lmax.disruptor.RingBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DisruptorMarketEventPublisherTest {

    /**
     * A one-slot ring with no consumer: every publish lands in the same reused slot, which is
     * exactly the reuse the publisher must not leak stale fields through.
     */
    private RingBuffer<MarketEvent> ring;
    private DisruptorMarketEventPublisher publisher;

    @BeforeEach
    void setUp() {
        ring = RingBuffer.createMultiProducer(new MarketEventFactory(), 1);
        DisruptorShardManager shards = new DisruptorShardManager(null, null, null, null, null) {
            @Override
            public RingBuffer<MarketEvent> getRingBuffer(int instrumentId) {
                return ring;
            }
        };
        publisher = new DisruptorMarketEventPublisher(shards);
    }

    private MarketEvent published() {
        return ring.get(ring.getCursor());
    }

    @Test
    @DisplayName("a text frame carries WS_MSG, the given stream, the id and the text")
    void textFrame() {
        publisher.publishFrame(StreamKind.DEPTH, 7, "{\"a\":1}");

        MarketEvent e = published();
        assertEquals(EventSource.WS_MSG, e.source);
        assertEquals(StreamKind.DEPTH, e.stream);
        assertEquals(7, e.instrumentId);
        assertEquals("{\"a\":1}", e.rawJson);
        assertNull(e.rawBytes);
    }

    @Test
    @DisplayName("a binary frame carries WS_MSG, the given stream, the id and the same buffer")
    void binaryFrame() {
        ByteBuffer frame = ByteBuffer.wrap(new byte[] {1, 2, 3});

        publisher.publishFrame(StreamKind.DEPTH, 8, frame);

        MarketEvent e = published();
        assertEquals(EventSource.WS_MSG, e.source);
        assertEquals(StreamKind.DEPTH, e.stream);
        assertEquals(8, e.instrumentId);
        assertSame(frame, e.rawBytes, "published without a copy");
        assertNull(e.rawJson);
    }

    @Test
    @DisplayName("a snapshot is REST_MSG on DEPTH with its body in rawJson")
    void snapshot() {
        publisher.publishSnapshot(3, "{\"lastUpdateId\":1}");

        MarketEvent e = published();
        assertEquals(EventSource.REST_MSG, e.source);
        assertEquals(StreamKind.DEPTH, e.stream);
        assertEquals(3, e.instrumentId);
        assertEquals("{\"lastUpdateId\":1}", e.rawJson);
        assertNull(e.rawBytes);
    }

    @Test
    @DisplayName("a snapshot failure is REST_FAILED on DEPTH with no payload")
    void snapshotFailure() {
        publisher.publishSnapshotFailure(4);

        MarketEvent e = published();
        assertEquals(EventSource.REST_FAILED, e.source);
        assertEquals(StreamKind.DEPTH, e.stream);
        assertEquals(4, e.instrumentId);
        assertNull(e.rawJson);
        assertNull(e.rawBytes);
    }

    @Test
    @DisplayName("every field is overwritten when the slot is reused — no payload leaks through")
    void reuseOverwritesEveryField() {
        publisher.publishFrame(StreamKind.DEPTH, 1, "text");
        publisher.publishFrame(StreamKind.DEPTH, 2, ByteBuffer.wrap(new byte[] {9}));

        MarketEvent afterBinary = published();
        assertEquals(2, afterBinary.instrumentId);
        assertNull(afterBinary.rawJson, "the earlier text must not survive a binary publish");
        assertEquals(1, afterBinary.rawBytes.remaining());

        publisher.publishFrame(StreamKind.DEPTH, 3, "later text");

        MarketEvent afterText = published();
        assertEquals(3, afterText.instrumentId);
        assertEquals("later text", afterText.rawJson);
        assertNull(afterText.rawBytes, "the earlier buffer must not survive a text publish");

        publisher.publishSnapshotFailure(5);

        MarketEvent afterFailure = published();
        assertEquals(EventSource.REST_FAILED, afterFailure.source);
        assertNull(afterFailure.rawJson);
        assertNull(afterFailure.rawBytes);
    }
}
