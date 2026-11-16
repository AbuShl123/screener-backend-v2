package dev.abu.screener_backend.marketdata.core.ingress;

import com.lmax.disruptor.EventFactory;

public class MarketEventFactory implements EventFactory<MarketEvent> {
    @Override
    public MarketEvent newInstance() {
        return new MarketEvent();
    }
}
