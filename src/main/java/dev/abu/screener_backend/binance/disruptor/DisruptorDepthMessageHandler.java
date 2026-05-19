package dev.abu.screener_backend.binance.disruptor;

import com.lmax.disruptor.RingBuffer;
import dev.abu.screener_backend.binance.websocket.Market;
import dev.abu.screener_backend.binance.websocket.RawDepthMessageHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DisruptorDepthMessageHandler implements RawDepthMessageHandler {

    private final DisruptorShardManager shardManager;

    @Override
    public void handle(String symbol, Market market, String rawJson) {
        RingBuffer<DepthEvent> rb = shardManager.getRingBuffer(symbol);
        long seq = rb.next();
        try {
            DepthEvent event = rb.get(seq);
            event.type    = EventType.DIFF;
            event.symbol  = symbol;
            event.market  = market;
            event.rawJson = rawJson;
        } finally {
            rb.publish(seq);
        }
    }
}
