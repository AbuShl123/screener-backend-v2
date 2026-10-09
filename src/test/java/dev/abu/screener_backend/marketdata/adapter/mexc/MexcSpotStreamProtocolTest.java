package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties;
import dev.abu.screener_backend.config.ExchangesProperties.VenueProperties.RestProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.Proto;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import dev.abu.screener_backend.marketdata.spi.Heartbeat;
import dev.abu.screener_backend.marketdata.spi.StreamProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;

import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.WRAPPER_AGGRE_DEPTHS;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotFrameReader.WRAPPER_CHANNEL;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.EVENT_TYPE;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.body;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.fixture;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.lvl;
import static dev.abu.screener_backend.marketdata.adapter.mexc.MexcSpotTestSupport.push;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MexcSpotStreamProtocol} against MEXC spot frames as delivered
 * ({@code external-docs/mexc/mexc-spot-depth-empirical.md} §1, §5): binary depth pushes, text control
 * replies.
 */
class MexcSpotStreamProtocolTest {

    private static final Instrument BTC = Instrument.of(21, Venue.MEXC_SPOT, "BTCUSDT", "BTC", "USDT");
    private static final Instrument ETH = Instrument.of(22, Venue.MEXC_SPOT, "ETHUSDT", "ETH", "USDT");
    private static final Instrument LONGXIA = Instrument.of(23, Venue.MEXC_SPOT, "龙虾USDT", "龙虾", "USDT");

    private static final RestProperties REST =
            new RestProperties("https://x", 1, Duration.ofSeconds(5), Duration.ofSeconds(10));

    private static VenueProperties props(int maxStreams, int chunkSize) {
        return new VenueProperties(true, "wss://x", REST, maxStreams, 1, 30, chunkSize, 20, null);
    }

    private static final MexcSpotStreamProtocol PROTOCOL = new MexcSpotStreamProtocol(Venue.MEXC_SPOT, props(30, 30));

    private static final SubscriptionIndex INDEX =
            new SubscriptionIndex(List.of(BTC, ETH, LONGXIA), PROTOCOL::routingKey);

    // --- Construction --------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {31, 300})
    @DisplayName("more than 30 streams per connection fails at construction: the server rejects the excess")
    void maxStreamsCapped(int maxStreams) {
        assertThrows(IllegalArgumentException.class,
                () -> new MexcSpotStreamProtocol(Venue.MEXC_SPOT, props(maxStreams, 30)));
    }

    @Test
    @DisplayName("a subscribe chunk over 30 fails at construction")
    void chunkSizeCapped() {
        assertThrows(IllegalArgumentException.class,
                () -> new MexcSpotStreamProtocol(Venue.MEXC_SPOT, props(30, 31)));
    }

    // --- Subscribe and heartbeat ---------------------------------------------------------------

    @Test
    @DisplayName("subscribe frame is one SUBSCRIPTION carrying every channel of the chunk, symbols as listed")
    void subscribeFrame() {
        assertEquals("{\"method\":\"SUBSCRIPTION\",\"params\":["
                        + "\"spot@public.aggre.depth.v3.api.pb@100ms@BTCUSDT\","
                        + "\"spot@public.aggre.depth.v3.api.pb@100ms@龙虾USDT\"]}",
                PROTOCOL.subscribeFrame(List.of(BTC, LONGXIA), 3));
    }

    @Test
    @DisplayName("heartbeat is a text {\"method\":\"PING\"} at the configured interval")
    void heartbeat() {
        assertEquals(new Heartbeat.TextPing(Duration.ofSeconds(20), "{\"method\":\"PING\"}"), PROTOCOL.heartbeat());
    }

    @Test
    @DisplayName("routing key is the native symbol")
    void routingKey() {
        assertEquals("BTCUSDT", PROTOCOL.routingKey(BTC));
    }

    // --- Binary routing ------------------------------------------------------------------------

    @Test
    @DisplayName("a captured push routes by its symbol, leaving position and limit untouched")
    void routesCapturedFrame() {
        ByteBuffer frame = ByteBuffer.wrap(fixture("push-btcusdt.bin"));
        int position = frame.position();
        int limit = frame.limit();

        assertEquals(21, PROTOCOL.route(frame, INDEX));
        assertEquals(position, frame.position());
        assertEquals(limit, frame.limit());
    }

    @Test
    @DisplayName("a push for a CJK symbol routes by its UTF-8 symbol")
    void routesCjkSymbol() {
        ByteBuffer frame = ByteBuffer.wrap(push("龙虾USDT", 1, 2, List.of(lvl("1.5", "3")), List.of()));

        assertEquals(23, PROTOCOL.route(frame, INDEX));
    }

    @Test
    @DisplayName("a push for a symbol this connection did not subscribe is UNKNOWN")
    void unsubscribedSymbolUnknown() {
        ByteBuffer frame = ByteBuffer.wrap(push("SOLUSDT", 1, 2, List.of(lvl("150", "3")), List.of()));

        assertEquals(StreamProtocol.UNKNOWN, PROTOCOL.route(frame, INDEX));
    }

    @Test
    @DisplayName("without a symbol field, the channel's suffix after the last @ routes the push")
    void routesByChannelSuffix() {
        ByteBuffer frame = ByteBuffer.wrap(new Proto()
                .string(WRAPPER_CHANNEL, EVENT_TYPE + "@ETHUSDT")
                .message(WRAPPER_AGGRE_DEPTHS, body(1, 2, List.of(lvl("3000", "1")), List.of()))
                .toByteArray());

        assertEquals(22, PROTOCOL.route(frame, INDEX));
    }

    @Test
    @DisplayName("a malformed or keyless binary frame is IGNORED, not thrown")
    void malformedFrameIgnored() {
        ByteBuffer truncated = ByteBuffer.wrap(new Proto().raw(0x0A, 0x7F, 's', 'p').toByteArray());
        ByteBuffer keyless = ByteBuffer.wrap(new Proto()
                .message(WRAPPER_AGGRE_DEPTHS, body(1, 2, List.of(lvl("3000", "1")), List.of()))
                .toByteArray());

        assertEquals(StreamProtocol.IGNORED, PROTOCOL.route(truncated, INDEX));
        assertEquals(StreamProtocol.IGNORED, PROTOCOL.route(keyless, INDEX));
    }

    // --- Text control frames -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":0,\"code\":0,\"msg\":\"PONG\"}",
            "{\"id\":0,\"code\":0,\"msg\":\"spot@public.aggre.depth.v3.api.pb@100ms@ETHUSDT,spot@public.aggre.depth.v3.api.pb@100ms@BTCUSDT\"}",
            "{\"id\":0,\"code\":0,\"msg\":\"Subscribed successful! [spot@public.aggre.depth.v3.api.pb@100ms@BTCUSDT]. "
                    + "Not Subscribed successfully! [spot@public.aggre.depth.v3.api.pb@100ms@NOPEUSDT].  Reason： Blocked! \"}",
            "{\"id\":0,\"code\":0,\"msg\":\"Not Subscribed successfully! [].\"}",
            "not json at all"
    })
    @DisplayName("every text frame is a control reply: IGNORED, whatever it says")
    void textFramesIgnored(String frame) {
        assertEquals(StreamProtocol.IGNORED, PROTOCOL.route(frame, INDEX));
    }
}
