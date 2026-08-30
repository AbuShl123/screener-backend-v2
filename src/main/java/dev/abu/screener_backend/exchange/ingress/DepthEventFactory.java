package dev.abu.screener_backend.exchange.ingress;

import com.lmax.disruptor.EventFactory;

public class DepthEventFactory implements EventFactory<DepthEvent> {
    @Override
    public DepthEvent newInstance() {
        return new DepthEvent();
    }
}
