package dev.abu.screener_backend.binance.disruptor;

import com.lmax.disruptor.EventHandler;
import dev.abu.screener_backend.analysis.OrderBookClassifier;
import dev.abu.screener_backend.binance.orderbook.OrderBook;
import dev.abu.screener_backend.binance.orderbook.OrderBookProcessor;
import lombok.AllArgsConstructor;
import lombok.Getter;

// Consumer for an assigned shard in LMAX Disruptor
@AllArgsConstructor
public class DepthEventHandler implements EventHandler<DepthEvent> {

    @Getter
    private final int shardIndex;
    private final OrderBookProcessor processor;
    private final OrderBookClassifier classifier;

    @Override
    public void onEvent(DepthEvent event, long sequence, boolean endOfBatch) {
        OrderBook ob = processor.process(event);
        if (ob != null) classifier.process(ob);
        event.clear();
    }
}
