package dev.abu.screener_backend.marketdata.core.ingress;

import java.nio.ByteBuffer;

/**
 * Mutable ring-buffer slot. Reused forever — never allocate one per message.
 *
 * <p>{@code String symbol} + {@code Market market} collapsed into a single {@code int
 * instrumentId}: the id already encodes the venue, and the consumer resolves the instrument's slot
 * with one array index instead of a map lookup on a freshly concatenated key.
 *
 * <p>{@link #source} says where the bytes came from and {@link #stream} says what they are; the
 * consumer dispatches on {@code stream}.
 *
 * <p>At most one payload field is set. A text frame or a REST body arrives in {@link #rawJson}, a
 * binary frame in {@link #rawBytes}, and {@code REST_FAILED} carries neither. Which one a venue's
 * {@code WS_MSG} uses is fixed by its wire protocol, so the stream's handler reads the right field
 * without checking.
 */
public class MarketEvent {
    public EventSource source;
    public StreamKind  stream;
    public int         instrumentId;
    public String      rawJson;
    public ByteBuffer  rawBytes;

    public void clear() {
        source       = null;
        stream       = null;
        instrumentId = -1;
        rawJson      = null;
        rawBytes     = null;
    }
}
