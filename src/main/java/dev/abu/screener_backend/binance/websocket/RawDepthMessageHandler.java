package dev.abu.screener_backend.binance.websocket;

@FunctionalInterface
public interface RawDepthMessageHandler {
    /**
     * Called from the WebSocket onMessage callback. Must be fast — no blocking, no heavy parsing.
     *
     * @param instrumentId already resolved against the connection's {@link SubscriptionIndex};
     *                     always a valid, published id — the caller drops unresolvable frames
     * @param rawJson      the full depth update payload as received
     */
    void handle(int instrumentId, String rawJson);
}
