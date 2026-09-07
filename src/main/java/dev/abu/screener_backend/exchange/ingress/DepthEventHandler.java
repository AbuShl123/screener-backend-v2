package dev.abu.screener_backend.exchange.ingress;

import com.lmax.disruptor.EventHandler;
import dev.abu.screener_backend.analysis.OrderBookClassifier;
import dev.abu.screener_backend.exchange.book.BookSlot;
import dev.abu.screener_backend.exchange.book.BookSlotTable;
import lombok.AllArgsConstructor;
import lombok.Getter;

// Consumer for an assigned shard in LMAX Disruptor
@AllArgsConstructor
public class DepthEventHandler implements EventHandler<DepthEvent> {

    @Getter
    private final int shardIndex;
    private final OrderBookProcessor obSyncMachine;
    private final OrderBookClassifier classificationModule;
    private final BookSlotTable slots;

    @Override
    public void onEvent(DepthEvent event, long sequence, boolean endOfBatch) {
        BookSlot slot = slots.get(event.instrumentId);

        // manage local orderbook of this event
        slot.strategy().onEvent(slot, event);

        // run default & per-user classification
        classificationModule.process(slot.instrument(), slot.book());

        // free
        event.clear();
    }
}
