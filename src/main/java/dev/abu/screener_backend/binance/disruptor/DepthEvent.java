package dev.abu.screener_backend.binance.disruptor;

/**
 * Mutable ring-buffer slot. Reused forever — never allocate one per message.
 *
 * <p>{@code String symbol} + {@code Market market} collapsed into a single {@code int
 * instrumentId}: the id already encodes the venue, and the consumer resolves the book with one
 * array index instead of a map lookup on a freshly concatenated key.
 */
public class DepthEvent {
    public EventType type;
    public int       instrumentId;
    public String    rawJson;

    public void clear() {
        type         = null;
        instrumentId = -1;
        rawJson      = null;
    }
}
