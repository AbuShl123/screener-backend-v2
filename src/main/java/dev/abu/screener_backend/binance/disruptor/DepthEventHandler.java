package dev.abu.screener_backend.binance.disruptor;

import com.lmax.disruptor.EventHandler;
import dev.abu.screener_backend.binance.orderbook.OrderBookProcessor;
import lombok.Getter;

// Consumer for an assigned shard in LMAX Disruptor
public class DepthEventHandler implements EventHandler<DepthEvent> {

    @Getter
    private final int shardIndex;
    private final OrderBookProcessor processor;

    public DepthEventHandler(int shardIndex, OrderBookProcessor processor) {
        this.shardIndex = shardIndex;
        this.processor  = processor;
    }

    @Override
    public void onEvent(DepthEvent event, long sequence, boolean endOfBatch) {
        processor.process(event);
        event.clear();
    }
}
