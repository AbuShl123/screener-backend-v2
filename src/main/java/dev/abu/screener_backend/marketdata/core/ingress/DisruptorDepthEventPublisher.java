package dev.abu.screener_backend.marketdata.core.ingress;

import com.lmax.disruptor.RingBuffer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * The single fill-and-publish site for both producers.
 *
 * <p>Claims with the blocking {@code rb.next()}: a full ring stalls the calling thread — for
 * {@code WS_MSG}, a WebSocket reader thread and so its whole connection. This class is the one
 * place the future {@code tryNext()} + reset-flag change goes (P3).
 */
@Component
@RequiredArgsConstructor
public class DisruptorDepthEventPublisher implements DepthEventPublisher {

    private final DisruptorShardManager shardManager;

    @Override
    public void publishFrame(int instrumentId, String payload) {
        publish(EventType.WS_MSG, instrumentId, payload, null);
    }

    @Override
    public void publishFrame(int instrumentId, ByteBuffer payload) {
        publish(EventType.WS_MSG, instrumentId, null, payload);
    }

    @Override
    public void publishSnapshot(int instrumentId, String payload) {
        publish(EventType.REST_MSG, instrumentId, payload, null);
    }

    @Override
    public void publishSnapshotFailure(int instrumentId) {
        publish(EventType.REST_FAILED, instrumentId, null, null);
    }

    /** Writes both payload fields every time: the slot is reused, so a stale one would leak through. */
    private void publish(EventType type, int instrumentId, String text, ByteBuffer bytes) {
        RingBuffer<DepthEvent> rb = shardManager.getRingBuffer(instrumentId);
        long seq = rb.next();
        try {
            DepthEvent event = rb.get(seq);
            event.type         = type;
            event.instrumentId = instrumentId;
            event.rawJson      = text;
            event.rawBytes     = bytes;
        } finally {
            rb.publish(seq);
        }
    }
}
