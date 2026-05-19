package dev.abu.screener_backend.binance.disruptor;

import dev.abu.screener_backend.binance.websocket.Market;

public class DepthEvent {
    public EventType type;
    public String    symbol;
    public Market    market;
    public String    rawJson;

    public void clear() {
        type    = null;
        symbol  = null;
        market  = null;
        rawJson = null;
    }
}
