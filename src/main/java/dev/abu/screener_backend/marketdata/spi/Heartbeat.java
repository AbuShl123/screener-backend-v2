package dev.abu.screener_backend.marketdata.spi;

import java.time.Duration;

/** How a connection keeps itself alive. The interval comes from the venue's config. */
public sealed interface Heartbeat {

    Duration interval();

    /** A WebSocket control-frame PING (Binance). */
    record ProtocolPing(Duration interval) implements Heartbeat {}

    /** An application-level text frame (Bybit {@code {"op":"ping"}}, MEXC futures {@code {"method":"ping"}}). */
    record TextPing(Duration interval, String payload) implements Heartbeat {}
}
