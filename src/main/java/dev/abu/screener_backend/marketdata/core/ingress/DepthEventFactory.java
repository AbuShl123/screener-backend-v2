package dev.abu.screener_backend.marketdata.core.ingress;

import com.lmax.disruptor.EventFactory;

public class DepthEventFactory implements EventFactory<DepthEvent> {
    @Override
    public DepthEvent newInstance() {
        return new DepthEvent();
    }
}
