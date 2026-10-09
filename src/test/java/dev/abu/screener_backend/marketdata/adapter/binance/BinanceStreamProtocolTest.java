package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.binance.BinanceStreamProtocol;
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

/**
 * {@link BinanceStreamProtocol} against real Binance frame shapes. {@code route} is the hot-path
 * method; a wrong answer there either misroutes a frame to another book or drops it silently.
 */
class BinanceStreamProtocolTest {

    private static final Instrument BTC_SPOT = Instrument.of(7, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT");
    private static final Instrument ETH_SPOT = Instrument.of(8, Venue.BINANCE_SPOT, "ETHUSDT", "ETH", "USDT");
    private static final Instrument BTC_FUT = Instrument.of(9, Venue.BINANCE_FUTURES, "BTCUSDT", "BTC", "USDT");

    private static final RestProperties REST =
            new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));

    private static final VenueProperties PROPS =
            new VenueProperties("wss://x", REST, 1024, 1, 1, 400, 120, null);

    private static final BinanceStreamProtocol SPOT =
            new BinanceStreamProtocol(Venue.BINANCE_SPOT, PROPS);
    private static final BinanceStreamProtocol FUTURES =
            new BinanceStreamProtocol(Venue.BINANCE_FUTURES, PROPS);

    private static SubscriptionIndex index(StreamProtocol protocol, Instrument... instruments) {
        return new SubscriptionIndex(List.of(instruments), protocol::routingKey);
    }

    @Test
    @DisplayName("spot subscribe frame: lower-cased symbols, @depth topic, id passed through")
    void spotSubscribeFrame() {
        assertEquals("{\"method\":\"SUBSCRIBE\",\"params\":[\"btcusdt@depth\",\"ethusdt@depth\"],\"id\":3}",
                SPOT.subscribeFrame(List.of(BTC_SPOT, ETH_SPOT), 3));
    }

    @Test
    @DisplayName("futures subscribe frame uses the 500ms topic")
    void futuresSubscribeFrame() {
        assertEquals("{\"method\":\"SUBSCRIBE\",\"params\":[\"btcusdt@depth@500ms\"],\"id\":0}",
                FUTURES.subscribeFrame(List.of(BTC_FUT), 0));
    }

    @Test
    @DisplayName("routing key is the upper-case native symbol, matching the frame's \"s\" field")
    void routingKey() {
        assertEquals("BTCUSDT", SPOT.routingKey(BTC_SPOT));
    }

    @Test
    @DisplayName("a spot depth frame routes to its instrument id")
    void routesSpotFrame() {
        String frame = "{\"e\":\"depthUpdate\",\"E\":1672515782136,\"s\":\"ETHUSDT\",\"U\":157,\"u\":160,"
                + "\"b\":[[\"0.0024\",\"10\"]],\"a\":[[\"0.0026\",\"100\"]]}";

        assertEquals(8, SPOT.route(frame, index(SPOT, BTC_SPOT, ETH_SPOT)));
    }

    @Test
    @DisplayName("a futures depth frame (with T and pu around s) routes to its instrument id")
    void routesFuturesFrame() {
        String frame = "{\"e\":\"depthUpdate\",\"E\":123456789,\"T\":123456788,\"s\":\"BTCUSDT\",\"U\":157,"
                + "\"u\":160,\"pu\":149,\"b\":[[\"0.0024\",\"10\"]],\"a\":[[\"0.0026\",\"100\"]]}";

        assertEquals(9, FUTURES.route(frame, index(FUTURES, BTC_FUT)));
    }

    @Test
    @DisplayName("a SUBSCRIBE ack is ignored")
    void ackIgnored() {
        assertEquals(StreamProtocol.IGNORED, SPOT.route("{\"result\":null,\"id\":0}", index(SPOT, BTC_SPOT)));
    }

    @Test
    @DisplayName("an error frame is ignored (and logged), not mistaken for a depth frame")
    void errorIgnored() {
        assertEquals(StreamProtocol.IGNORED,
                SPOT.route("{\"error\":{\"code\":2,\"msg\":\"Invalid request\"},\"id\":0}", index(SPOT, BTC_SPOT)));
    }

    @Test
    @DisplayName("a depth frame for a symbol this connection never subscribed is UNKNOWN")
    void unsubscribedSymbolUnknown() {
        String frame = "{\"e\":\"depthUpdate\",\"E\":1,\"s\":\"XRPUSDT\",\"U\":1,\"u\":2,\"b\":[],\"a\":[]}";

        assertEquals(StreamProtocol.UNKNOWN, SPOT.route(frame, index(SPOT, BTC_SPOT)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"e\":\"depthUpdate\",\"E\":1,\"U\":1}", "{\"e\":\"depthUpdate\",\"s\":\"BTC"})
    @DisplayName("malformed frames are ignored without throwing")
    void malformedIgnored(String frame) {
        assertEquals(StreamProtocol.IGNORED, SPOT.route(frame, index(SPOT, BTC_SPOT)));
    }

    @Test
    @DisplayName("heartbeat is a protocol-level PING at the configured interval")
    void heartbeat() {
        assertEquals(new Heartbeat.ProtocolPing(Duration.ofSeconds(120)), SPOT.heartbeat());
    }
}
