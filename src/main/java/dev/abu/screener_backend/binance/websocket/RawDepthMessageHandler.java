package dev.abu.screener_backend.binance.websocket;

@FunctionalInterface
public interface RawDepthMessageHandler {
    /**
     * Called from the WebSocket onMessage callback. Must be fast — no blocking, no heavy parsing.
     * symbol is already interned. rawJson is the full depth update payload as received.
     */
    void handle(String symbol, Market market, String rawJson);
}
