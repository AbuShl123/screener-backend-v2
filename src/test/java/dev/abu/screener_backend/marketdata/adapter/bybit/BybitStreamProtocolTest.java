package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.ExchangesProperties.StreamProperties;
import dev.abu.screener_backend.config.TestVenueProps;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.StreamKey;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.stream.SubscriptionIndex;
import dev.abu.screener_backend.marketdata.spi.Heartbeat;
import dev.abu.screener_backend.marketdata.spi.StreamProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link BybitStreamProtocol} against the frames captured in the phase-0 spike
 * ({@code src/test/resources/bybit/}), which carry both categories' field orders, plus the limits
 * in {@code external-docs/bybit/bybit-api-contracts.md} §4.
 */
class BybitStreamProtocolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Instrument SPOT_ARB = Instrument.of(21, Venue.BYBIT_SPOT, "ARBUSDT", "ARB", "USDT", 1);
    private static final Instrument SPOT_BTC = Instrument.of(22, Venue.BYBIT_SPOT, "BTCUSDT", "BTC", "USDT", 1);
    private static final Instrument LINEAR_ARB = Instrument.of(31, Venue.BYBIT_FUTURES, "ARBUSDT", "ARB", "USDT", 1);
    private static final Instrument LINEAR_BABYDOGE = Instrument.of(32, Venue.BYBIT_FUTURES,
            "1000000BABYDOGEUSDT", "1000000BABYDOGE", "USDT", 1);

    private static StreamProperties props(int chunkSize) {
        return TestVenueProps.stream()
                .maxStreamsPerConnection(300).connections(1, 8).chunk(chunkSize).heartbeatSeconds(20).build();
    }

    private static final BybitStreamProtocol SPOT = new BybitStreamProtocol(Venue.BYBIT_SPOT, props(10));
    private static final BybitStreamProtocol LINEAR = new BybitStreamProtocol(Venue.BYBIT_FUTURES, props(200));

    private static final SubscriptionIndex SPOT_INDEX =
            new SubscriptionIndex(List.of(SPOT_ARB, SPOT_BTC), SPOT::routingKey);
    private static final SubscriptionIndex LINEAR_INDEX =
            new SubscriptionIndex(List.of(LINEAR_ARB, LINEAR_BABYDOGE), LINEAR::routingKey);

    // --- Subscribe -----------------------------------------------------------------------------

    @Test
    @DisplayName("subscribe frame lists one orderbook.1000 topic per instrument, with the chunk index as req_id")
    void subscribeFrame() {
        assertEquals("{\"req_id\":\"3\",\"op\":\"subscribe\",\"args\":"
                        + "[\"orderbook.1000.ARBUSDT\",\"orderbook.1000.1000000BABYDOGEUSDT\"]}",
                LINEAR.subscribeFrame(List.of(LINEAR_ARB, LINEAR_BABYDOGE), 3));
    }

    @Test
    @DisplayName("routing key is the native symbol, the suffix of the frame's topic")
    void routingKey() {
        assertEquals("1000000BABYDOGEUSDT", LINEAR.routingKey(LINEAR_BABYDOGE));
    }

    @Test
    @DisplayName("argsLength matches the serialized args array exactly")
    void argsLengthIsExact() {
        String frame = LINEAR.subscribeFrame(List.of(LINEAR_ARB, LINEAR_ARB, LINEAR_ARB), 0);
        String args = frame.substring(frame.indexOf('['), frame.lastIndexOf(']') + 1);
        int topicLength = (BybitStreamProtocol.TOPIC_PREFIX + "ARBUSDT").length();

        assertEquals(args.length(), BybitStreamProtocol.argsLength(3, topicLength));
    }

    // --- Construction limits -------------------------------------------------------------------

    @Test
    @DisplayName("spot accepts 10 args per frame and rejects 11")
    void spotChunkCap() {
        new BybitStreamProtocol(Venue.BYBIT_SPOT, props(10));
        assertThrows(IllegalArgumentException.class, () -> new BybitStreamProtocol(Venue.BYBIT_SPOT, props(11)));
    }

    @Test
    @DisplayName("linear has no arg-count cap, but a chunk whose worst-case args exceed 21 000 chars is rejected")
    void linearArgsLengthCap() {
        // 15-char prefix + 32-char symbol = 47 per topic, so 50 per arg: 419 fits, 420 does not.
        new BybitStreamProtocol(Venue.BYBIT_FUTURES, props(419));
        assertThrows(IllegalArgumentException.class, () -> new BybitStreamProtocol(Venue.BYBIT_FUTURES, props(420)));
    }

    @Test
    @DisplayName("a worst-case chunk at the largest accepted size stays within the limit")
    void largestAcceptedChunkFits() {
        String longest = "X".repeat(BybitStreamProtocol.MAX_SYMBOL_LENGTH);
        List<Instrument> chunk = IntStream.range(0, 419)
                .mapToObj(i -> Instrument.of(i, Venue.BYBIT_FUTURES, longest, longest, "USDT", 1))
                .toList();
        String frame = new BybitStreamProtocol(Venue.BYBIT_FUTURES, props(419)).subscribeFrame(chunk, 0);
        String args = frame.substring(frame.indexOf('['), frame.lastIndexOf(']') + 1);

        assertEquals(20_951, args.length());
    }

    @ParameterizedTest
    @EnumSource(value = Venue.class, names = {"BYBIT_SPOT", "BYBIT_FUTURES"})
    @DisplayName("the shipped application.yml passes the construction checks")
    void shippedYamlConstructs(Venue venue) throws IOException {
        new BybitStreamProtocol(venue, shippedYaml().stream(new StreamKey(venue, StreamKind.DEPTH)));
    }

    // --- Routing -------------------------------------------------------------------------------

    @Test
    @DisplayName("every captured spot frame (topic, ts, type order) routes to its instrument")
    void routesCapturedSpot() {
        List<String> frames = lines("ws-spot-ARBUSDT-1000.jsonl");
        assertFalse(frames.isEmpty());
        for (String frame : frames) {
            assertEquals(21, SPOT.route(frame, SPOT_INDEX), () -> frame.substring(0, 80));
        }
    }

    @Test
    @DisplayName("every captured linear frame (topic, type, ts order) routes to its instrument")
    void routesCapturedLinear() {
        List<String> frames = lines("ws-linear-ARBUSDT-1000.jsonl");
        assertFalse(frames.isEmpty());
        for (String frame : frames) {
            assertEquals(31, LINEAR.route(frame, LINEAR_INDEX), () -> frame.substring(0, 80));
        }
    }

    @Test
    @DisplayName("a data frame that does not lead with topic still routes")
    void routesOtherFieldOrder() {
        String frame = "{\"type\":\"delta\",\"ts\":1,\"data\":{\"s\":\"BTCUSDT\",\"b\":[],\"a\":[[\"1\",\"2\"]],"
                + "\"u\":5,\"seq\":9},\"topic\":\"orderbook.1000.BTCUSDT\",\"cts\":1}";

        assertEquals(22, SPOT.route(frame, SPOT_INDEX));
    }

    @Test
    @DisplayName("a data frame for a symbol this connection never subscribed is UNKNOWN")
    void unsubscribedSymbolUnknown() {
        String frame = "{\"topic\":\"orderbook.1000.ETHUSDT\",\"ts\":1,\"type\":\"delta\","
                + "\"data\":{\"s\":\"ETHUSDT\",\"b\":[],\"a\":[],\"u\":2,\"seq\":3},\"cts\":1}";

        assertEquals(StreamProtocol.UNKNOWN, SPOT.route(frame, SPOT_INDEX));
    }

    @Test
    @DisplayName("captured acks, pongs and the rejected subscribe are all ignored, on either venue")
    void capturedControlFramesIgnored() throws IOException {
        Map<String, String> frames = JSON.readValue(BybitRestClientTest.fixture("control-frames.json"),
                new TypeReference<Map<String, String>>() {});
        assertFalse(frames.isEmpty());
        frames.forEach((name, frame) -> {
            assertEquals(StreamProtocol.IGNORED, SPOT.route(frame, SPOT_INDEX), name);
            assertEquals(StreamProtocol.IGNORED, LINEAR.route(frame, LINEAR_INDEX), name);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Linear's rejected duplicate subscribe names a topic in ret_msg, but not as a "topic" field.
            "{\"success\":false,\"ret_msg\":\"error:already subscribed,topic:orderbook.1000.BTCUSDT\","
                    + "\"conn_id\":\"c\",\"req_id\":\"0\",\"op\":\"subscribe\"}",
            "{\"success\":false,\"ret_msg\":\"args size >10\",\"conn_id\":\"c\",\"op\":\"subscribe\"}",
            "{\"op\":\"ping\",\"success\":true,\"ret_msg\":\"pong\",\"conn_id\":\"c\"}",
            // Another depth is never subscribed, so its topic is not this protocol's.
            "{\"topic\":\"orderbook.50.BTCUSDT\",\"ts\":1,\"type\":\"delta\",\"data\":{}}",
            "{\"topic\":\"publicTrade.BTCUSDT\",\"ts\":1,\"type\":\"snapshot\",\"data\":[]}"
    })
    @DisplayName("other control frames and topics this protocol never subscribes are ignored")
    void otherFramesIgnored(String frame) {
        assertEquals(StreamProtocol.IGNORED, SPOT.route(frame, SPOT_INDEX));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "{}", "[1,2,3]", "{\"topic\":\"orderbook.1000.BTCUS", "{\"data\":{},\"topic\":\"orderbook.1000.BTC"
    })
    @DisplayName("malformed frames are ignored without throwing")
    void malformedIgnored(String frame) {
        assertEquals(StreamProtocol.IGNORED, SPOT.route(frame, SPOT_INDEX));
    }

    // --- Heartbeat -----------------------------------------------------------------------------

    @Test
    @DisplayName("heartbeat is a text {\"op\":\"ping\"} at the configured interval")
    void heartbeat() {
        assertEquals(new Heartbeat.TextPing(Duration.ofSeconds(20), "{\"op\":\"ping\"}"), LINEAR.heartbeat());
    }

    // --- Helpers -------------------------------------------------------------------------------

    private static List<String> lines(String fixture) {
        return BybitRestClientTest.fixture(fixture).lines().filter(line -> !line.isBlank()).toList();
    }

    private static ExchangesProperties shippedYaml() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("screener", ExchangesProperties.class)
                .get();
    }
}
