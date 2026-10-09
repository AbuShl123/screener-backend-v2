package dev.abu.screener_backend.marketdata.core.ingress;

import java.nio.ByteBuffer;

/**
 * Mutable ring-buffer slot. Reused forever — never allocate one per message.
 *
 * <p>{@code String symbol} + {@code Market market} collapsed into a single {@code int
 * instrumentId}: the id already encodes the venue, and the consumer resolves the book with one
 * array index instead of a map lookup on a freshly concatenated key.
 *
 * <p>At most one payload field is set. A text frame or a REST body arrives in {@link #rawJson}, a
 * binary frame in {@link #rawBytes}, and {@code REST_FAILED} carries neither. Which one a venue's
 * {@code WS_MSG} uses is fixed by its wire protocol, so the sync strategy reads the right field
 * without checking.
 */
public class DepthEvent {
    public EventType  type;
    public int        instrumentId;
    public String     rawJson;
    public ByteBuffer rawBytes;

    public void clear() {
        type         = null;
        instrumentId = -1;
        rawJson      = null;
        rawBytes     = null;
    }
}
