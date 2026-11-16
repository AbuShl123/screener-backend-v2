package dev.abu.screener_backend.marketdata.adapter.binance;

import dev.abu.screener_backend.config.ExchangesProperties.StreamProperties;
import dev.abu.screener_backend.config.TestVenueProps;
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

    private static final StreamProperties PROPS = TestVenueProps.stream().build();

    private static final BinanceStreamProtocol SPOT = BinanceStreamProtocol.depth(Venue.BINANCE_SPOT, PROPS);
    private static final BinanceStreamProtocol FUTURES = BinanceStreamProtocol.depth(Venue.BINANCE_FUTURES, PROPS);
    private static final BinanceStreamProtocol SPOT_TRADE = BinanceStreamProtocol.aggTrade(Venue.BINANCE_SPOT, PROPS);
    private static final BinanceStreamProtocol FUTURES_TRADE =
            BinanceStreamProtocol.aggTrade(Venue.BINANCE_FUTURES, PROPS);

    /** Real frames, captured 2026-11-17. */
    private static final String SPOT_AGG_TRADE = "{\"e\":\"aggTrade\",\"E\":1794857964130,\"s\":\"BTCUSDT\","
            + "\"a\":4120467472,\"p\":\"64826.00000000\",\"q\":\"0.02711000\",\"f\":6870090619,\"l\":6870090619,"
            + "\"T\":1794857964129,\"m\":true,\"M\":true}";
    private static final String FUTURES_AGG_TRADE = "{\"e\":\"aggTrade\",\"E\":1794860803575,\"a\":3528412961,"
            + "\"s\":\"BTCUSDT\",\"p\":\"65079.50\",\"q\":\"0.006\",\"nq\":\"0.006\",\"f\":8285985083,"
            + "\"l\":8285985083,\"T\":1794860803450,\"m\":true,\"st\":1}";

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
    @DisplayName("aggTrade subscribe frames use the @aggTrade topic on both venues")
    void aggTradeSubscribeFrames() {
        assertEquals("{\"method\":\"SUBSCRIBE\",\"params\":[\"btcusdt@aggTrade\",\"ethusdt@aggTrade\"],\"id\":1}",
                SPOT_TRADE.subscribeFrame(List.of(BTC_SPOT, ETH_SPOT), 1));
        assertEquals("{\"method\":\"SUBSCRIBE\",\"params\":[\"btcusdt@aggTrade\"],\"id\":2}",
                FUTURES_TRADE.subscribeFrame(List.of(BTC_FUT), 2));
    }

    @Test
    @DisplayName("a spot aggTrade frame routes to its instrument id")
    void routesSpotAggTrade() {
        assertEquals(7, SPOT_TRADE.route(SPOT_AGG_TRADE, index(SPOT_TRADE, BTC_SPOT, ETH_SPOT)));
    }

    @Test
    @DisplayName("a futures aggTrade frame (\"a\" before \"s\", extra \"st\" field) routes to its instrument id")
    void routesFuturesAggTrade() {
        assertEquals(9, FUTURES_TRADE.route(FUTURES_AGG_TRADE, index(FUTURES_TRADE, BTC_FUT)));
    }

    @Test
    @DisplayName("an aggTrade frame for a symbol this connection never subscribed is UNKNOWN")
    void unsubscribedAggTradeUnknown() {
        assertEquals(StreamProtocol.UNKNOWN, SPOT_TRADE.route(SPOT_AGG_TRADE, index(SPOT_TRADE, ETH_SPOT)));
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
