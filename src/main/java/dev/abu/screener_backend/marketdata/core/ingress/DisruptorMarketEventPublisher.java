package dev.abu.screener_backend.marketdata.core.ingress;

import dev.abu.screener_backend.marketdata.StreamKind;

import com.lmax.disruptor.RingBuffer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * The single fill-and-publish site for both instrument-event producers. The shard tick, which has
 * no instrument and must never block, publishes from {@link ShardTicker} instead.
 *
 * <p>Claims with the blocking {@code rb.next()}: a full ring stalls the calling thread — for
 * {@code WS_MSG}, a WebSocket reader thread and so its whole connection. This class is the one
 * place the future {@code tryNext()} + reset-flag change goes (P3).
 */
@Component
@RequiredArgsConstructor
public class DisruptorMarketEventPublisher implements MarketEventPublisher {

    private final DisruptorShardManager shardManager;

    @Override
    public void publishFrame(StreamKind stream, int instrumentId, String payload) {
        publish(EventSource.WS_MSG, stream, instrumentId, payload, null);
    }

    @Override
    public void publishFrame(StreamKind stream, int instrumentId, ByteBuffer payload) {
        publish(EventSource.WS_MSG, stream, instrumentId, null, payload);
    }

    @Override
    public void publishSnapshot(int instrumentId, String payload) {
        publish(EventSource.REST_MSG, StreamKind.DEPTH, instrumentId, payload, null);
    }

    @Override
    public void publishSnapshotFailure(int instrumentId) {
        publish(EventSource.REST_FAILED, StreamKind.DEPTH, instrumentId, null, null);
    }

    /** Writes every field every time: the slot is reused, so a stale one would leak through. */
    private void publish(EventSource source, StreamKind stream, int instrumentId,
                         String text, ByteBuffer bytes) {
        RingBuffer<MarketEvent> rb = shardManager.getRingBuffer(instrumentId);
        long seq = rb.next();
        try {
            MarketEvent event = rb.get(seq);
            event.source       = source;
            event.stream       = stream;
            event.instrumentId = instrumentId;
            event.rawJson      = text;
            event.rawBytes     = bytes;
        } finally {
            rb.publish(seq);
        }
    }
}
