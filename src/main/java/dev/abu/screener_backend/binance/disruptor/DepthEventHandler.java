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
    private final OrderBookProcessor obSyncMachine;
    private final OrderBookClassifier classificationModule;

    @Override
    public void onEvent(DepthEvent event, long sequence, boolean endOfBatch) {
        // manage local orderbook of this event
        OrderBook ob = obSyncMachine.process(event);

        // run default & per-user classification
        if (ob != null) classificationModule.process(ob);

        // free
        event.clear();
    }
}
