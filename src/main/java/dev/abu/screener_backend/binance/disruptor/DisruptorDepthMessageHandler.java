package dev.abu.screener_backend.binance.disruptor;

import com.lmax.disruptor.RingBuffer;
import dev.abu.screener_backend.binance.websocket.RawDepthMessageHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DisruptorDepthMessageHandler implements RawDepthMessageHandler {

    private final DisruptorShardManager shardManager;

    @Override
    public void handle(int instrumentId, String rawJson) {
        RingBuffer<DepthEvent> rb = shardManager.getRingBuffer(instrumentId);
        long seq = rb.next();
        try {
            DepthEvent event = rb.get(seq);
            event.type         = EventType.DIFF;
            event.instrumentId = instrumentId;
            event.rawJson      = rawJson;
        } finally {
            rb.publish(seq);
        }
    }
}
