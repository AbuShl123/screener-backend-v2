package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * One venue's wire protocol: what to send, how to read what comes back, and how to stay alive.
 *
 * <p>Core ({@code core/stream/}) owns connection lifecycle, reconnect, connection fan-out, subscribe
 * chunking and heartbeat scheduling. The protocol owns only byte-level knowledge of the venue.
 * One instance per venue, contributed through a {@link VenueStreamBinding}.
 */
public interface StreamProtocol {

    /** The frame was not a data frame for any instrument: ack, pong, error, or unrecognised. */
    int IGNORED = -2;
    /** A data frame whose routing key this connection never subscribed. Same value as {@link SubscriptionIndex#resolve} misses. */
    int UNKNOWN = -1;

    /**
     * Cold. One subscribe frame for a chunk of instruments. Core chunks by the venue's
     * {@code subscribe-chunk-size}; {@code requestId} is the chunk's index on this connection.
     */
    String subscribeFrame(List<Instrument> chunk, int requestId);

    /** Cold. The key the connection's {@link SubscriptionIndex} is built on. */
    String routingKey(Instrument instrument);

    /**
     * <b>Hot path</b>, called on the WebSocket reader thread for every frame. No allocation beyond
     * what {@link SubscriptionIndex#resolve} does and no logging above DEBUG, except for rare
     * error frames.
     *
     * @return the instrument id ({@code >= 0}), {@link #IGNORED}, or {@link #UNKNOWN}
     */
    int route(String frame, SubscriptionIndex index);

    /**
     * <b>Hot path</b>, the binary twin of {@link #route(String, SubscriptionIndex)}, with the same
     * allocation and logging rules. The default suits a venue that streams text only: every binary
     * frame is {@link #IGNORED}, and core logs it as an anomaly.
     *
     * <p><b>Absolute reads only</b> ({@code frame.get(int)}, never {@code get()}), between
     * {@code frame.position()} and {@code frame.limit()}. A routed buffer goes into the ring as-is
     * and is parsed again on the shard thread, so moving {@code position} or {@code limit} here
     * would corrupt that parse.
     *
     * @return the instrument id ({@code >= 0}), {@link #IGNORED}, or {@link #UNKNOWN}
     */
    default int route(ByteBuffer frame, SubscriptionIndex index) {
        return IGNORED;
    }

    /** Cold; read once per connection open. */
    Heartbeat heartbeat();
}
