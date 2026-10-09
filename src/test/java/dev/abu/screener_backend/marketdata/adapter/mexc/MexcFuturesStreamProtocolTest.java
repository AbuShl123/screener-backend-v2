package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcFuturesStreamProtocol;
import dev.abu.screener_backend.marketdata.spi.Heartbeat;
import dev.abu.screener_backend.marketdata.spi.StreamProtocol;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MexcFuturesStreamProtocol} against MEXC frame shapes as actually delivered
 * ({@code external-docs/mexc/mexc-depth-versioning-empirical.md}) — notably {@code channel}
 * <em>last</em> in a depth push, unlike the docs.
 */
class MexcFuturesStreamProtocolTest {

    private static final Instrument BTC = Instrument.of(11, Venue.MEXC_FUTURES, "BTC_USDT", "BTC", "USDT", 0.0001);
    private static final Instrument ETH = Instrument.of(12, Venue.MEXC_FUTURES, "ETH_USDT", "ETH", "USDT", 0.01);

    private static final RestProperties REST =
            new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));

    private static VenueProperties props(int chunkSize) {
        return new VenueProperties(true, "wss://x", REST, 300, 1, 8, chunkSize, 15, null);
    }

    private static final MexcFuturesStreamProtocol PROTOCOL =
            new MexcFuturesStreamProtocol(Venue.MEXC_FUTURES, props(1));

    private static final SubscriptionIndex INDEX = new SubscriptionIndex(List.of(BTC, ETH), PROTOCOL::routingKey);

    @Test
    @DisplayName("subscribe frame is a single-symbol sub.depth with the native symbol")
    void subscribeFrame() {
        assertEquals("{\"method\":\"sub.depth\",\"param\":{\"symbol\":\"BTC_USDT\"}}",
                PROTOCOL.subscribeFrame(List.of(BTC), 4));
    }

    @Test
    @DisplayName("a chunk size other than 1 fails at construction: sub.depth takes one symbol")
    void chunkSizeMustBeOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new MexcFuturesStreamProtocol(Venue.MEXC_FUTURES, props(2)));
    }

    @Test
    @DisplayName("routing key is the native symbol, matching the frame's \"symbol\" field")
    void routingKey() {
        assertEquals("BTC_USDT", PROTOCOL.routingKey(BTC));
    }

    @Test
    @DisplayName("a depth push in the delivered order (symbol first, channel last) routes to its instrument")
    void routesDeliveredOrder() {
        String frame = "{\"symbol\":\"ETH_USDT\",\"data\":{\"cts\":1791040915686,\"asks\":[[3450.1,4640,2]],"
                + "\"bids\":[],\"end\":1205403582,\"begin\":1205403582,\"version\":1205403582},"
                + "\"channel\":\"push.depth\",\"ts\":1791040915689}";

        assertEquals(12, PROTOCOL.route(frame, INDEX));
    }

    @Test
    @DisplayName("a depth push in the documented order (channel first, symbol after data) still routes")
    void routesDocumentedOrder() {
        String frame = "{\"channel\":\"push.depth\",\"data\":{\"asks\":[[3450.1,3251,1]],\"bids\":[],"
                + "\"version\":96801927},\"symbol\":\"BTC_USDT\",\"ts\":1587442022003}";

        assertEquals(11, PROTOCOL.route(frame, INDEX));
    }

    @Test
    @DisplayName("a depth push for a symbol this connection never subscribed is UNKNOWN")
    void unsubscribedSymbolUnknown() {
        String frame = "{\"symbol\":\"XRP_USDT\",\"data\":{\"asks\":[],\"bids\":[],\"end\":2,\"begin\":1,"
                + "\"version\":2},\"channel\":\"push.depth\",\"ts\":1}";

        assertEquals(StreamProtocol.UNKNOWN, PROTOCOL.route(frame, INDEX));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"channel\":\"rs.sub.depth\",\"data\":\"success\",\"ts\":1791040915689}",
            "{\"channel\":\"rs.sub.depth\",\"data\":\"Contract does not exist\",\"ts\":1791040915689}",
            "{\"channel\":\"pong\",\"data\":1791040915689,\"ts\":1791040915689}",
            "{\"channel\":\"rs.error\",\"data\":\"invalid method\",\"ts\":1791040915689}",
            "{\"channel\":\"push.something\",\"data\":{},\"ts\":1}"
    })
    @DisplayName("acks, pongs, errors and unknown control frames are ignored, not routed")
    void controlFramesIgnored(String frame) {
        assertEquals(StreamProtocol.IGNORED, PROTOCOL.route(frame, INDEX));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "{}", "{\"symbol\":\"BTC_US", "{\"channel\":\"push.depth\",\"data\":{}}",
            "{\"channel\":\"push.depth\",\"symbol\":\"BTC", "[1,2,3]"
    })
    @DisplayName("malformed frames are ignored without throwing")
    void malformedIgnored(String frame) {
        assertEquals(StreamProtocol.IGNORED, PROTOCOL.route(frame, INDEX));
    }

    @Test
    @DisplayName("heartbeat is a lower-case text ping at the configured interval")
    void heartbeat() {
        assertEquals(new Heartbeat.TextPing(Duration.ofSeconds(15), "{\"method\":\"ping\"}"), PROTOCOL.heartbeat());
    }
}
