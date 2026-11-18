package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.config.ExchangesProperties.StreamProperties;
import dev.abu.screener_backend.config.TestVenueProps;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
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
 * ({@code external-docs/mexc/mexc-depth-versioning-empirical.md}, {@code mexc-trades-empirical.md} §2)
 * — notably {@code channel} <em>last</em> in a push, unlike the docs.
 */
class MexcFuturesStreamProtocolTest {

    private static final Instrument BTC = Instrument.of(11, Venue.MEXC_FUTURES, "BTC_USDT", "BTC", "USDT", 0.0001);
    private static final Instrument ETH = Instrument.of(12, Venue.MEXC_FUTURES, "ETH_USDT", "ETH", "USDT", 0.01);

    private static StreamProperties props(int chunkSize) {
        return TestVenueProps.stream()
                .maxStreamsPerConnection(300).connections(1, 8).chunk(chunkSize).heartbeatSeconds(15).build();
    }

    private static final MexcFuturesStreamProtocol PROTOCOL =
            MexcFuturesStreamProtocol.depth(Venue.MEXC_FUTURES, props(1));
    private static final MexcFuturesStreamProtocol DEAL =
            MexcFuturesStreamProtocol.deal(Venue.MEXC_FUTURES, props(1));

    private static final SubscriptionIndex INDEX = new SubscriptionIndex(List.of(BTC, ETH), PROTOCOL::routingKey);

    @Test
    @DisplayName("subscribe frame is a single-symbol sub.depth with the native symbol")
    void subscribeFrame() {
        assertEquals("{\"method\":\"sub.depth\",\"param\":{\"symbol\":\"BTC_USDT\"}}",
                PROTOCOL.subscribeFrame(List.of(BTC), 4));
    }

    @Test
    @DisplayName("a chunk size other than 1 fails at construction: sub.depth and sub.deal take one symbol")
    void chunkSizeMustBeOne() {
        assertThrows(IllegalArgumentException.class,
                () -> MexcFuturesStreamProtocol.depth(Venue.MEXC_FUTURES, props(2)));
        assertThrows(IllegalArgumentException.class,
                () -> MexcFuturesStreamProtocol.deal(Venue.MEXC_FUTURES, props(2)));
    }

    // --- deal channel ----------------------------------------------------------------------------

    @Test
    @DisplayName("the deal protocol subscribes with a single-symbol sub.deal")
    void dealSubscribeFrame() {
        assertEquals("{\"method\":\"sub.deal\",\"param\":{\"symbol\":\"ETH_USDT\"}}",
                DEAL.subscribeFrame(List.of(ETH), 4));
    }

    @Test
    @DisplayName("a deal push in the delivered order (symbol first, channel last) routes to its instrument")
    void dealRoutesDeliveredOrder() {
        String frame = "{\"symbol\":\"BTC_USDT\",\"data\":["
                + "{\"p\":64670.8,\"v\":1,\"T\":1,\"O\":1,\"M\":2,\"t\":1794983337848,\"i\":\"17067813085\",\"cts\":\"1794983337848\"},"
                + "{\"p\":64670.8,\"v\":1,\"T\":1,\"O\":1,\"M\":2,\"t\":1794983337847,\"i\":\"17067813084\",\"cts\":\"1794983337847\"}],"
                + "\"channel\":\"push.deal\",\"ts\":1794983337848}";

        assertEquals(11, DEAL.route(frame, INDEX));
    }

    @Test
    @DisplayName("a deal push in the documented order (channel first) still routes")
    void dealRoutesDocumentedOrder() {
        String frame = "{\"channel\":\"push.deal\",\"data\":[{\"p\":3450.1,\"v\":2,\"T\":2,\"O\":3,\"M\":2,"
                + "\"t\":1,\"i\":\"5\"}],\"symbol\":\"ETH_USDT\",\"ts\":1}";

        assertEquals(12, DEAL.route(frame, INDEX));
    }

    @Test
    @DisplayName("each protocol routes only its own channel's channel-led pushes")
    void channelLedPushOfTheOtherChannelIgnored() {
        String depth = "{\"channel\":\"push.depth\",\"data\":{},\"symbol\":\"BTC_USDT\",\"ts\":1}";
        String deal = "{\"channel\":\"push.deal\",\"data\":[],\"symbol\":\"BTC_USDT\",\"ts\":1}";

        assertEquals(StreamProtocol.IGNORED, DEAL.route(depth, INDEX));
        assertEquals(StreamProtocol.IGNORED, PROTOCOL.route(deal, INDEX));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"channel\":\"rs.sub.deal\",\"data\":\"success\",\"ts\":1794983318045}",
            "{\"channel\":\"rs.sub.deal\",\"data\":\"Contract does not exist\",\"ts\":1794983318045}",
            "{\"channel\":\"pong\",\"data\":1794983318045,\"ts\":1794983318045}",
            "{\"channel\":\"rs.error\",\"data\":\"invalid method\",\"ts\":1794983318045}"
    })
    @DisplayName("the deal protocol ignores acks, pongs and errors")
    void dealControlFramesIgnored(String frame) {
        assertEquals(StreamProtocol.IGNORED, DEAL.route(frame, INDEX));
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
    @DisplayName("heartbeat is a lower-case text ping at the configured interval, on either channel")
    void heartbeat() {
        Heartbeat ping = new Heartbeat.TextPing(Duration.ofSeconds(15), "{\"method\":\"ping\"}");
        assertEquals(ping, PROTOCOL.heartbeat());
        assertEquals(ping, DEAL.heartbeat());
    }
}
